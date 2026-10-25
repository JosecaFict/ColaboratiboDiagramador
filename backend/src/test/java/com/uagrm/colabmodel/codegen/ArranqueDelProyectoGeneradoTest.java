package com.uagrm.colabmodel.codegen;

import com.uagrm.colabmodel.modelcore.mapping.ModeloCanonico;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.DefaultResourceLoader;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * El backend generado ARRANCA, no solo compila.
 *
 * Hasta aca ninguna prueba pasaba de "el archivo existe" o "el texto contiene";
 * compilar se verificaba a mano corriendo `mvn package` sobre lo que queda en
 * target/. Eso deja pasar todo lo que falla en el arranque y no antes: el DDL
 * que la base rechaza y el mapeo que Hibernate declara incompatible con la
 * tabla. Las dos cosas COMPILAN.
 *
 * El caso que la trajo: `datetime` se mapeaba a `timestamptz`, un alias que
 * solo existe en PostgreSQL. El artefacto arrancaba contra H2 --como promete
 * su application.yml-- y Flyway cortaba con "Unknown data type: TIMESTAMPTZ".
 * Ninguno de los 7 modelos canonicos con fecha llegaba a levantar.
 *
 * Lo que hace, en el mismo JVM de `mvn test`:
 *
 *   1. genera el proyecto y lo escribe en una carpeta temporal;
 *   2. lo compila con el javac del JDK contra ESTE classpath, que es un
 *      superconjunto del pom generado (por eso H2 es dependencia de prueba);
 *   3. levanta un contexto de Spring con las clases recien compiladas, con el
 *      application.yml generado y su perfil h2 por defecto. Ahi corre Flyway y
 *      ahi Hibernate valida el mapeo contra la tabla (`ddl-auto: validate`);
 *   4. lee por el repositorio generado, que es lo unico que prueba que la
 *      columna ademas se puede LEER con el tipo de Java que se emitio.
 *
 * Dos diferencias a proposito con `mvn spring-boot:run` sobre el zip: no se
 * levanta Tomcat (el agujero esta en JPA, y sin servidor la prueba no pelea por
 * un puerto) y las migraciones se apuntan por `filesystem:` en vez de
 * `classpath:`, porque el classpath de ColabModel ya trae SUS propias
 * migraciones y Flyway encontraria dos V1. El SQL que se ejecuta es el mismo
 * archivo generado; que ademas quede en la ruta correcta del jar lo comprueba
 * `GeneradorSpringBootTest.el_proyecto_trae_el_andamiaje_para_arrancar`.
 */
class ArranqueDelProyectoGeneradoTest {

    /** El unico modelo canonico con un atributo `datetime`. */
    private static final String MODELO = "02-uno-a-muchos.json";

    /**
     * Los 7 canonicos, no solo el 02: cada uno ejercita una regla de mapeo
     * distinta, y una que rompa la compilacion o el arranque no se ve desde
     * ningun otro modelo.
     *
     * El caso que lo trajo: el 07 tiene Vuelo con DOS asociaciones a
     * Aeropuerto (origen y destino) y el Service inyectaba un repositorio por
     * ASOCIACION en vez de uno por clase destino, asi que emitia dos campos y
     * dos parametros llamados `aeropuertoRepository`. javac cortaba con
     * "variable aeropuertoRepository is already defined".
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "01-uno-a-uno.json",
            "02-uno-a-muchos.json",
            "03-muchos-a-muchos.json",
            "04-herencia.json",
            "05-clase-asociativa.json",
            "06-nombres-acentos.json",
            "07-reflexiva-doble.json" })
    void cada_modelo_canonico_compila_y_arranca_contra_h2(String modelo, @TempDir Path raiz) throws Exception {
        Arrancado a = generarCompilarYArrancar(modelo, "Arranque " + modelo.replace(".json", ""), raiz);
        try (URLClassLoader ignorado = a.clases(); ConfigurableApplicationContext ctx = a.contexto()) {
            assertThat(ctx.isRunning()).isTrue();
        }
    }

    @Test
    void el_proyecto_generado_arranca_contra_h2_y_sirve_sus_datos(@TempDir Path raiz) throws Exception {
        Arrancado a = generarCompilarYArrancar(MODELO, "Arranque con datos", raiz);
        try (URLClassLoader generado = a.clases(); ConfigurableApplicationContext ctx = a.contexto()) {
            // Llegar hasta aca ya significa que Flyway aplico V1__init.sql y
            // que Hibernate valido el mapeo contra la tabla resultante.
            assertThat(ctx.isRunning()).isTrue();

            Class<?> repositorio = generado.loadClass("com.uagrm.generado.repository.PedidoRepository");
            Method findAll = repositorio.getMethod("findAll");
            List<?> pedidos = (List<?>) findAll.invoke(ctx.getBean(repositorio));

            // Los datos de ejemplo del perfil h2: tres pedidos con fecha.
            assertThat(pedidos).hasSize(3);
            Method getFecha = generado.loadClass("com.uagrm.generado.domain.Pedido").getMethod("getFecha");
            assertThat(getFecha.invoke(pedidos.get(0))).isInstanceOf(LocalDateTime.class);
        }
    }

    /** El contexto ya levantado y el cargador con el que se levanto, para pedirle clases por reflexion. */
    private record Arrancado(ConfigurableApplicationContext contexto, URLClassLoader clases) { }

    /**
     * @param proyecto nombre del proyecto generado, DISTINTO en cada arranque
     *                 de esta clase: de el sale tambien el nombre de la base H2
     *                 en memoria, y con `DB_CLOSE_DELAY=-1` la base sobrevive al
     *                 cierre del contexto. Dos arranques homonimos la
     *                 compartirian y el segundo traeria un V1__init.sql distinto
     *                 al ya aplicado, asi que Flyway cortaria por checksum.
     */
    private static Arrancado generarCompilarYArrancar(String modelo, String proyecto, Path raiz) throws Exception {
        ModeloCanonico.Cargado m = ModeloCanonico.cargar(modelo);

        ProyectoGenerado p = GeneradorSpringBoot.generar(
                GeneradorSpringBoot.Opciones.por(proyecto),
                m.clases(), m.atributos(), List.of(), m.relaciones());
        p.escribirEn(raiz);

        Path clases = compilar(raiz);
        Path recursos = raiz.resolve("src/main/resources");

        URLClassLoader generado = new URLClassLoader("generado",
                new URL[] { clases.toUri().toURL(), recursos.toUri().toURL() },
                ArranqueDelProyectoGeneradoTest.class.getClassLoader());
        try {
            SpringApplication app = new SpringApplication(new DefaultResourceLoader(generado),
                    generado.loadClass("com.uagrm.generado.GeneratedApplication"));
            app.setWebApplicationType(WebApplicationType.NONE);
            app.setBannerMode(Banner.Mode.OFF);

            return new Arrancado(app.run(
                    "--spring.config.location=" + recursos.resolve("application.yml").toUri(),
                    "--spring.flyway.locations=filesystem:" + recursos.resolve("db/migration")
                            + ",filesystem:" + recursos.resolve("db/demo")),
                    generado);
        } catch (Throwable e) {
            generado.close();
            throw e;
        }
    }

    /**
     * Compila el proyecto generado contra el classpath de esta prueba.
     *
     * UTF-8 explicito: los modelos canonicos traen acentos y enies (Regla 8), y
     * el javac de Windows leeria los fuentes en cp1252 sin esto.
     */
    private static Path compilar(Path raiz) throws IOException {
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        assumeTrue(javac != null, "Sin javac (JRE en vez de JDK): no se puede compilar el proyecto generado");

        Path destino = Files.createDirectories(raiz.resolve("target/classes"));
        List<String> argumentos = new ArrayList<>(List.of(
                "-classpath", System.getProperty("java.class.path"),
                "-encoding", "UTF-8",
                "-nowarn",
                "-d", destino.toString()));
        try (Stream<Path> fuentes = Files.walk(raiz.resolve("src/main/java"))) {
            fuentes.filter(f -> f.toString().endsWith(".java")).map(Path::toString).forEach(argumentos::add);
        }

        ByteArrayOutputStream errores = new ByteArrayOutputStream();
        int codigo = javac.run(null, null, errores, argumentos.toArray(String[]::new));
        assertThat(codigo)
                .withFailMessage("El proyecto generado no compila:%n%s", errores.toString(StandardCharsets.UTF_8))
                .isZero();
        return destino;
    }
}
