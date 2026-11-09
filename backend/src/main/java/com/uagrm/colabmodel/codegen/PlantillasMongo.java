package com.uagrm.colabmodel.codegen;

import com.uagrm.colabmodel.codegen.GeneradorSpringBoot.Asociacion;
import com.uagrm.colabmodel.codegen.GeneradorSpringBoot.Campo;
import com.uagrm.colabmodel.codegen.GeneradorSpringBoot.ColeccionDeValores;
import com.uagrm.colabmodel.codegen.GeneradorSpringBoot.Meta;
import com.uagrm.colabmodel.codegen.GeneradorSpringBoot.Opciones;
import com.uagrm.colabmodel.codegen.GeneradorSpringBoot.Unidad;
import com.uagrm.colabmodel.codegen.MapeoDocumental.ArregloDeIds;
import com.uagrm.colabmodel.codegen.MapeoDocumental.Incrustacion;
import com.uagrm.colabmodel.modelcore.mapping.ModeloLogico;
import com.uagrm.colabmodel.modelcore.mapping.Nombres;
import com.uagrm.colabmodel.modelcore.mapping.TipoJava;
import com.uagrm.colabmodel.modelcore.metamodel.RelationKind;

import java.util.*;
import java.util.stream.Collectors;

/**
 * La variante MongoDB del backend generado (ADR-035).
 *
 * Solo cambia lo que toca la base: los documentos, los repositorios y los
 * Services. El DTO, el controlador, las pruebas y el manifiesto son los de
 * PostgreSQL tal cual (PlantillasProduccion), asi que quien consume la API no
 * sabe que base hay atras.
 *
 * MongoDB no tiene claves foraneas. Lo que en PostgreSQL hace la base, aca lo
 * hace el Service, con el mismo resultado visible: apuntar a un id que no
 * existe es un 409, y borrar algo que otro referencia tambien.
 */
final class PlantillasMongo {

    private PlantillasMongo() { }

    static String pom(Opciones o) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>

                    <parent>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-starter-parent</artifactId>
                        <version>3.5.0</version>
                        <relativePath/>
                    </parent>

                    <groupId>com.uagrm</groupId>
                    <artifactId>{{artefacto}}</artifactId>
                    <version>0.1.0</version>
                    <name>{{proyecto}}</name>

                    <properties>
                        <java.version>21</java.version>
                    </properties>

                    <dependencies>
                        <dependency>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-starter-web</artifactId>
                        </dependency>
                        <dependency>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-starter-data-mongodb</artifactId>
                        </dependency>
                        <dependency>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-starter-validation</artifactId>
                        </dependency>
                        <dependency>
                            <groupId>org.springdoc</groupId>
                            <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
                            <version>2.8.9</version>
                        </dependency>
                {{comunes}}    </dependencies>

                    <build>
                        <plugins>
                            <plugin>
                                <groupId>org.springframework.boot</groupId>
                                <artifactId>spring-boot-maven-plugin</artifactId>
                            </plugin>
                        </plugins>
                    </build>
                </project>
                """
                .replace("{{comunes}}", PlantillasProduccion.dependenciasComunes())
                .replace("{{artefacto}}", GeneradorSpringBoot.artefacto(o))
                .replace("{{proyecto}}", o.nombreProyecto());
    }

    static String configuracion(Opciones o) {
        return (PlantillasProduccion.yamlComun(o) + """
                spring:
                  application:
                    name: {{proyecto}}
                  profiles:
                    default: dev
                  data:
                    mongodb:
                      # UUID como Binary subtipo 4, el estandar: legible desde cualquier driver.
                      uuid-representation: standard
                      # Los indices (@Indexed: unicos y de referencias) se crean al arrancar.
                      auto-index-creation: true

                ---
                spring:
                  config:
                    activate:
                      on-profile: dev
                  data:
                    mongodb:
                      uri: ${DB_URL:mongodb://localhost:27017/{{db}}}
                app.mongo.usuario: ${DB_USER:}
                app.mongo.clave: ${DB_PASSWORD:}
                logging:
                  level:
                    "{{pkg}}": DEBUG

                ---
                # PRODUCCION: sin DB_URL la aplicacion no arranca. DB_USER y DB_PASSWORD
                # se agregan a la conexion si vienen (MongoConfig).
                spring:
                  config:
                    activate:
                      on-profile: prod
                  data:
                    mongodb:
                      uri: ${DB_URL}
                app.mongo.usuario: ${DB_USER:}
                app.mongo.clave: ${DB_PASSWORD:}
                """
                + PlantillasProduccion.yamlProd(o))
                .replace("{{proyecto}}", o.nombreProyecto())
                .replace("{{pkg}}", o.paquete())
                .replace("{{db}}", Nombres.identificador(o.nombreProyecto()));
    }

    /** REGLA 5 en MongoDB: el objeto de valor es un subdocumento. Sin anotaciones de JPA. */
    static String valorEmbebido(String pkg, String nombre, List<Campo> campos) {
        StringBuilder cuerpo = new StringBuilder();
        StringBuilder acc = new StringBuilder();
        for (Campo c : campos) {
            cuerpo.append("    private ").append(c.tipoJava()).append(" ").append(c.nombreJava()).append(";\n");
            acc.append(accesores(c.tipoJava(), c.nombreJava()));
        }
        return """
                package {{pkg}}.domain;

                {{imports}}
                /** REGLA 5: objeto de valor. Sin identidad propia: viaja dentro del documento que lo contiene. */
                public class {{nombre}} {

                {{cuerpo}}
                {{accesores}}}
                """
                .replace("{{pkg}}", pkg)
                .replace("{{imports}}", imports(campos.stream().map(Campo::tipoJava).toList()))
                .replace("{{nombre}}", nombre)
                .replace("{{cuerpo}}", cuerpo.toString())
                .replace("{{accesores}}", acc.toString());
    }

    /** Todos los archivos que dependen de MongoDB. */
    static Map<String, String> archivos(String pkg, String ruta, List<Unidad> unidades, MapeoDocumental m, Meta meta) {
        Map<String, String> a = new LinkedHashMap<>();
        a.put(ruta + "/config/MongoConfig.java", config(pkg));
        a.put(ruta + "/domain/Registro.java", registro(pkg, meta));
        a.put(ruta + "/repository/AlmacenMongo.java", almacen(pkg));
        a.put(ruta + "/repository/ColeccionMongo.java", coleccion(pkg, meta));
        a.put(ruta + "/repository/IncrustadoMongo.java", incrustado(pkg, meta));
        a.put("src/test/java/" + pkg.replace('.', '/') + "/repository/IncrustadoMongoSeguridadTest.java",
                pruebaIncrustado(pkg, meta));
        for (Unidad u : unidades) {
            a.put(ruta + "/domain/" + u.clase() + ".java", documento(pkg, u, m, meta));
            a.put(ruta + "/repository/" + u.clase() + "Repository.java", repositorio(pkg, u, m));
            a.put(ruta + "/service/" + u.clase() + "Service.java", servicio(pkg, u, unidades, m, meta));
            String prueba = pruebaCascada(pkg, u, unidades, m);
            if (!prueba.isEmpty()) a.put("src/test/java/" + pkg.replace('.', '/') + "/service/"
                    + u.clase() + "IntegridadMongoTest.java", prueba);
        }
        return a;
    }

    // ------------------------------------------------------------- CONFIG

    private static String config(String pkg) {
        return """
                package {{pkg}}.config;

                import com.mongodb.MongoCredential;
                import org.bson.types.Decimal128;
                import org.springframework.beans.factory.annotation.Value;
                import org.springframework.boot.autoconfigure.mongo.MongoClientSettingsBuilderCustomizer;
                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;
                import org.springframework.core.convert.converter.Converter;
                import org.springframework.data.convert.ReadingConverter;
                import org.springframework.data.convert.WritingConverter;
                import org.springframework.data.mongodb.MongoDatabaseFactory;
                import org.springframework.data.mongodb.core.convert.DefaultDbRefResolver;
                import org.springframework.data.mongodb.core.convert.DefaultMongoTypeMapper;
                import org.springframework.data.mongodb.core.convert.MappingMongoConverter;
                import org.springframework.data.mongodb.core.convert.MongoCustomConversions;
                import org.springframework.data.mongodb.core.mapping.MongoMappingContext;

                import java.math.BigDecimal;
                import java.util.List;

                /**
                 * Tres ajustes sobre lo que Spring Boot configura solo:
                 *
                 *   - el discriminador de la herencia se llama `_tipo` (ADR-035), no
                 *     `_class`, y guarda el nombre de la clase del modelo (@TypeAlias),
                 *     no el paquete de Java: renombrar un paquete no rompe los datos;
                 *   - BigDecimal se guarda como Decimal128, un numero de verdad: como
                 *     texto, ordenar por precio pondria "10" antes que "9";
                 *   - DB_USER y DB_PASSWORD se agregan a la conexion si vienen, asi la
                 *     clave no tiene que ir escrita dentro de DB_URL.
                 */
                @Configuration
                public class MongoConfig {

                    @Bean
                    MongoCustomConversions mongoCustomConversions() {
                        return new MongoCustomConversions(List.of(new DecimalAMongo(), new MongoADecimal()));
                    }

                    @Bean
                    MappingMongoConverter mappingMongoConverter(MongoDatabaseFactory fabrica, MongoMappingContext contexto,
                                                                MongoCustomConversions conversiones) {
                        MappingMongoConverter convertidor = new MappingMongoConverter(new DefaultDbRefResolver(fabrica), contexto);
                        convertidor.setCustomConversions(conversiones);
                        convertidor.setTypeMapper(new DefaultMongoTypeMapper("_tipo", contexto));
                        return convertidor;
                    }

                    @Bean
                    MongoClientSettingsBuilderCustomizer credenciales(@Value("${app.mongo.usuario:}") String usuario,
                                                                      @Value("${app.mongo.clave:}") String clave,
                                                                      @Value("${app.mongo.base-de-autenticacion:admin}") String base) {
                        return ajustes -> {
                            if (!usuario.isBlank()) {
                                ajustes.credential(MongoCredential.createCredential(usuario, base, clave.toCharArray()));
                            }
                        };
                    }

                    @WritingConverter
                    static class DecimalAMongo implements Converter<BigDecimal, Decimal128> {
                        @Override
                        public Decimal128 convert(BigDecimal valor) {
                            return new Decimal128(valor);
                        }
                    }

                    @ReadingConverter
                    static class MongoADecimal implements Converter<Decimal128, BigDecimal> {
                        @Override
                        public BigDecimal convert(Decimal128 valor) {
                            return valor.bigDecimalValue();
                        }
                    }
                }
                """.replace("{{pkg}}", pkg);
    }

    private static String registro(String pkg, Meta m) {
        return """
                package {{pkg}}.domain;

                import java.time.LocalDateTime;
                import java.util.UUID;

                /**
                 * Lo que tiene todo documento, este en su coleccion o incrustado en
                 * otro: identidad, version (bloqueo optimista) y auditoria (ADR-034).
                 */
                public interface Registro {

                    UUID getId();

                    void setId(UUID id);

                    Long {{gv}}();

                    void {{sv}}(Long version);

                    LocalDateTime {{gc}}();

                    void {{sc}}(LocalDateTime cuando);

                    LocalDateTime {{ga}}();

                    void {{sa}}(LocalDateTime cuando);
                }
                """
                .replace("{{pkg}}", pkg)
                .replace("{{gv}}", NombresJava.getter(m.version())).replace("{{sv}}", NombresJava.setter(m.version()))
                .replace("{{gc}}", NombresJava.getter(m.creado())).replace("{{sc}}", NombresJava.setter(m.creado()))
                .replace("{{ga}}", NombresJava.getter(m.actualizado()))
                .replace("{{sa}}", NombresJava.setter(m.actualizado()));
    }

    // ---------------------------------------------------------- DOCUMENTO

    private static String documento(String pkg, Unidad u, MapeoDocumental m, Meta meta) {
        Optional<Incrustacion> incrustada = m.incrustacionDe(u.clase());
        boolean raiz = u.herencia().madre() == null;
        StringBuilder cuerpo = new StringBuilder();
        StringBuilder acc = new StringBuilder();
        List<String> tipos = new ArrayList<>();

        if (raiz) {
            cuerpo.append("    @Id\n    private UUID id;\n\n");
            acc.append(accesores("UUID", "id"));
        }
        for (Campo c : u.propios()) {
            if (c.esId()) continue;
            // Unico disperso: un documento de otra clase de la jerarquia, sin el
            // campo, no cuenta como repetido.
            if (c.unico() && incrustada.isEmpty()) cuerpo.append("    @Indexed(unique = true, sparse = true)\n");
            cuerpo.append("    private ").append(c.tipoJava()).append(" ").append(c.nombreJava()).append(";\n\n");
            acc.append(accesores(c.tipoJava(), c.nombreJava()));
            tipos.add(c.tipoJava());
        }
        for (Asociacion a : u.asociacionesPropias()) {
            String campo = a.campo() + "Id";
            if (incrustada.isPresent() && incrustada.get().campoPadre().equals(campo)) {
                cuerpo.append("    /** Es el documento que la contiene: se completa al leer y no se guarda. */\n")
                        .append("    @Transient\n");
            } else if (incrustada.isEmpty()) {
                cuerpo.append("    /** Referencia por id a ").append(a.claseDestino()).append(" (ADR-035). */\n")
                        .append(a.unoAUno() ? "    @Indexed(unique = true, sparse = true)\n" : "    @Indexed\n");
            }
            cuerpo.append("    private UUID ").append(campo).append(";\n\n");
            acc.append(accesores("UUID", campo));
        }
        for (ColeccionDeValores v : u.valoresPropios()) {
            String tipo = "List<" + v.tipoElemento() + ">";
            cuerpo.append("    private ").append(tipo).append(" ").append(v.campo()).append(" = new ArrayList<>();\n\n");
            acc.append(accesores(tipo, v.campo()));
            tipos.add(v.tipoElemento());
        }
        for (ModeloLogico.Incrustado i : u.incrustadosPropios()) {
            String tipo = NombresJava.clase(i.clase());
            String campo = NombresJava.campo(i.campo());
            cuerpo.append("    private ").append(tipo).append(" ").append(campo).append(";\n\n");
            acc.append(accesores(tipo, campo));
        }
        for (Incrustacion i : m.partesDe(u.clase())) {
            String tipo = i.multiple() ? "List<" + i.parte().clase() + ">" : i.parte().clase();
            cuerpo.append("    /** Composicion: las partes viven dentro de este documento (ADR-035). */\n")
                    .append("    private ").append(tipo).append(" ").append(i.arreglo())
                    .append(i.multiple() ? " = new ArrayList<>();\n\n" : ";\n\n");
            acc.append(accesores(tipo, i.arreglo()));
        }
        for (ArregloDeIds a : m.arreglosDe(u.clase())) {
            cuerpo.append("    /** N:M con ").append(a.destino().clase()).append(": arreglo de ids (ADR-035). */\n")
                    .append("    private List<UUID> ").append(a.campo()).append(" = new ArrayList<>();\n\n");
            acc.append(accesores("List<UUID>", a.campo()));
        }
        if (raiz) {
            cuerpo.append("    private Long ").append(meta.version()).append(";\n\n")
                    .append("    private LocalDateTime ").append(meta.creado()).append(";\n\n")
                    .append("    private LocalDateTime ").append(meta.actualizado()).append(";\n\n");
            acc.append(accesores("Long", meta.version()))
                    .append(accesores("LocalDateTime", meta.creado()))
                    .append(accesores("LocalDateTime", meta.actualizado()));
        }

        String anotaciones = incrustada.isPresent() ? ""
                : "@Document(collection = \"" + m.coleccionDe(u.clase()) + "\")\n@TypeAlias(\"" + u.clase() + "\")\n";
        String declaracion = "public " + (u.herencia().abstracta() ? "abstract " : "") + "class " + u.clase()
                + (raiz ? " implements Registro" : " extends " + u.herencia().madre());
        String descripcion = incrustada.map(i -> "Parte de " + i.todo().clase()
                + ": vive incrustada en su documento, en `" + i.arreglo() + "`.")
                .orElse("Documento de la coleccion `" + m.coleccionDe(u.clase()) + "`"
                        + (m.enJerarquia(u.clase()) ? ", discriminado por `_tipo`." : "."));
        String igualdad = !raiz ? "" : """

                    // Igualdad SOLO por id, como en la variante JPA.
                    @Override
                    public boolean equals(Object o) {
                        if (this == o) return true;
                        if (!(o instanceof {{clase}} otro)) return false;
                        return id != null && id.equals(otro.id);
                    }

                    @Override
                    public int hashCode() { return {{clase}}.class.hashCode(); }
                """;

        return """
                package {{pkg}}.domain;

                import org.springframework.data.annotation.Id;
                import org.springframework.data.annotation.Transient;
                import org.springframework.data.annotation.TypeAlias;
                import org.springframework.data.mongodb.core.index.Indexed;
                import org.springframework.data.mongodb.core.mapping.Document;

                import java.time.LocalDateTime;
                import java.util.ArrayList;
                import java.util.List;
                import java.util.UUID;
                {{imports}}
                /** {{descripcion}} */
                {{anotaciones}}{{declaracion}} {

                {{cuerpo}}{{accesores}}{{igualdad}}}
                """
                .replace("{{igualdad}}", igualdad)
                .replace("{{pkg}}", pkg)
                .replace("{{imports}}", imports(tipos))
                .replace("{{descripcion}}", descripcion)
                .replace("{{anotaciones}}", anotaciones)
                .replace("{{declaracion}}", declaracion)
                .replace("{{cuerpo}}", cuerpo.toString())
                .replace("{{accesores}}", acc.toString())
                .replace("{{clase}}", u.clase());
    }

    // ------------------------------------------------------- REPOSITORIOS

    private static String repositorio(String pkg, Unidad u, MapeoDocumental m) {
        Optional<Incrustacion> incrustada = m.incrustacionDe(u.clase());
        if (incrustada.isPresent()) {
            Incrustacion i = incrustada.get();
            String todo = i.todo().clase();
            String getter = NombresJava.getter(i.arreglo());
            String setter = NombresJava.setter(i.arreglo());
            String leer = i.multiple() ? todo + "::" + getter
                    : "t -> t." + getter + "() == null ? new ArrayList<>() : new ArrayList<>(List.of(t." + getter + "()))";
            String escribir = i.multiple() ? todo + "::" + setter
                    : "(t, partes) -> t." + setter + "(partes.isEmpty() ? null : partes.get(0))";
            return """
                    package {{pkg}}.repository;

                    import {{pkg}}.domain.{{clase}};
                    import {{pkg}}.domain.{{todo}};
                    import org.springframework.stereotype.Repository;

                    import java.util.ArrayList;
                    import java.util.List;

                    /** {{clase}} vive incrustada en los documentos de {{todo}}, en `{{arreglo}}` (composicion, ADR-035). */
                    @Repository
                    public class {{clase}}Repository extends IncrustadoMongo<{{clase}}, {{todo}}> {

                        public {{clase}}Repository({{todo}}Repository padres) {
                            super(padres, "{{arreglo}}", "{{campoPadre}}", {{multiple}}, "{{clase}}",
                                    {{leer}},
                                    {{escribir}},
                                    {{clase}}::{{getPadre}}, {{clase}}::{{setPadre}});
                        }
                    }
                    """
                    .replace("{{leer}}", leer)
                    .replace("{{escribir}}", escribir)
                    .replace("{{getPadre}}", NombresJava.getter(i.campoPadre()))
                    .replace("{{setPadre}}", NombresJava.setter(i.campoPadre()))
                    .replace("{{campoPadre}}", i.campoPadre())
                    .replace("{{multiple}}", String.valueOf(i.multiple()))
                    .replace("{{arreglo}}", i.arreglo())
                    .replace("{{todo}}", todo)
                    .replace("{{pkg}}", pkg)
                    .replace("{{clase}}", u.clase());
        }
        // Una hija ve solo los documentos de su tipo y de sus descendientes; la
        // raiz de la jerarquia, todos los de la coleccion.
        String tipos = u.herencia().madre() == null ? "" : m.conDescendientes(u.clase()).stream()
                .map(t -> "\"" + t + "\"").collect(Collectors.joining(", "));
        return """
                package {{pkg}}.repository;

                import {{pkg}}.domain.{{clase}};
                import org.springframework.data.mongodb.core.MongoTemplate;
                import org.springframework.stereotype.Repository;

                import java.util.List;

                /** Coleccion `{{coleccion}}`. */
                @Repository
                public class {{clase}}Repository extends ColeccionMongo<{{clase}}> {

                    public {{clase}}Repository(MongoTemplate plantilla) {
                        super(plantilla, {{clase}}.class, List.of({{tipos}}));
                    }
                }
                """
                .replace("{{coleccion}}", m.coleccionDe(u.clase()))
                .replace("{{tipos}}", tipos)
                .replace("{{pkg}}", pkg)
                .replace("{{clase}}", u.clase());
    }

    private static String almacen(String pkg) {
        return """
                package {{pkg}}.repository;

                import {{pkg}}.domain.Registro;
                import {{pkg}}.service.Pagina;
                import org.springframework.data.domain.Pageable;
                import org.springframework.data.domain.Sort;

                import java.util.List;
                import java.util.Optional;
                import java.util.UUID;

                /**
                 * Lo que un Service necesita de la base, este el documento en su
                 * propia coleccion o incrustado en otro. Los Services no saben cual
                 * de los dos es: por eso el mismo Service sirve para los dos casos.
                 */
                public abstract class AlmacenMongo<T extends Registro> {

                    public abstract Optional<T> buscar(UUID id);

                    public boolean existe(UUID id) {
                        return buscar(id).isPresent();
                    }

                    public abstract List<T> todos(Sort orden);

                    public abstract Pagina<T> pagina(Pageable pagina);

                    public abstract List<T> porCampo(String campo, Object valor, Sort orden);

                    public abstract Pagina<T> porCampo(String campo, Object valor, Pageable pagina);

                    /** Si algun documento tiene ese valor en esa ruta (`clienteId`, `lineas.productoId`). */
                    public abstract boolean existeConCampo(String ruta, Object valor);

                    /** Le da id, version 0 y fechas, y lo guarda. */
                    public abstract T insertar(T nuevo);

                    /** Lo guarda solo si su version sigue siendo `versionEsperada`; si no, 409. */
                    public abstract T reemplazar(T cambiado, Long versionEsperada);

                    public abstract void eliminar(UUID id);

                    public abstract void eliminarPorCampo(String campo, Object valor);
                }
                """.replace("{{pkg}}", pkg);
    }

    private static String coleccion(String pkg, Meta m) {
        return """
                package {{pkg}}.repository;

                import {{pkg}}.domain.Registro;
                import {{pkg}}.service.Pagina;
                import org.springframework.dao.OptimisticLockingFailureException;
                import org.springframework.data.domain.Pageable;
                import org.springframework.data.domain.Sort;
                import org.springframework.data.mongodb.core.MongoTemplate;
                import org.springframework.data.mongodb.core.query.Criteria;
                import org.springframework.data.mongodb.core.query.Query;

                import java.time.LocalDateTime;
                import java.util.ArrayList;
                import java.util.List;
                import java.util.Optional;
                import java.util.UUID;

                /**
                 * Documentos en su propia coleccion. Si la clase es parte de una
                 * jerarquia, `tipos` limita las consultas a su `_tipo` y al de sus
                 * descendientes: la coleccion la comparte toda la jerarquia.
                 */
                public class ColeccionMongo<T extends Registro> extends AlmacenMongo<T> {

                    private final MongoTemplate plantilla;
                    private final Class<T> clase;
                    private final List<String> tipos;

                    public ColeccionMongo(MongoTemplate plantilla, Class<T> clase, List<String> tipos) {
                        this.plantilla = plantilla;
                        this.clase = clase;
                        this.tipos = List.copyOf(tipos);
                    }

                    private Query consulta(Criteria filtro) {
                        List<Criteria> partes = new ArrayList<>();
                        if (!tipos.isEmpty()) partes.add(Criteria.where("_tipo").in(tipos));
                        if (filtro != null) partes.add(filtro);
                        if (partes.isEmpty()) return new Query();
                        if (partes.size() == 1) return new Query(partes.get(0));
                        return new Query(new Criteria().andOperator(partes.toArray(new Criteria[0])));
                    }

                    @Override
                    public Optional<T> buscar(UUID id) {
                        return Optional.ofNullable(plantilla.findOne(consulta(Criteria.where("id").is(id)), clase));
                    }

                    @Override
                    public List<T> todos(Sort orden) {
                        return plantilla.find(consulta(null).with(orden), clase);
                    }

                    @Override
                    public Pagina<T> pagina(Pageable pagina) {
                        long total = plantilla.count(consulta(null), clase);
                        return new Pagina<>(plantilla.find(consulta(null).with(pagina), clase), total);
                    }

                    @Override
                    public List<T> porCampo(String campo, Object valor, Sort orden) {
                        return plantilla.find(consulta(Criteria.where(campo).is(valor)).with(orden), clase);
                    }

                    @Override
                    public Pagina<T> porCampo(String campo, Object valor, Pageable pagina) {
                        long total = plantilla.count(consulta(Criteria.where(campo).is(valor)), clase);
                        return new Pagina<>(plantilla.find(consulta(Criteria.where(campo).is(valor)).with(pagina), clase),
                                total);
                    }

                    /** El primer documento con ese valor en esa ruta: lo usa quien tiene partes incrustadas. */
                    public Optional<T> unoConCampo(String ruta, Object valor) {
                        return Optional.ofNullable(plantilla.findOne(consulta(Criteria.where(ruta).is(valor)), clase));
                    }

                    @Override
                    public boolean existeConCampo(String ruta, Object valor) {
                        return plantilla.exists(consulta(Criteria.where(ruta).is(valor)), clase);
                    }

                    @Override
                    public T insertar(T nuevo) {
                        LocalDateTime ahora = LocalDateTime.now();
                        if (nuevo.getId() == null) nuevo.setId(UUID.randomUUID());
                        nuevo.{{sv}}(0L);
                        nuevo.{{sc}}(ahora);
                        nuevo.{{sa}}(ahora);
                        return plantilla.insert(nuevo);
                    }

                    /**
                     * Reemplazo condicionado a la version: `findAndReplace` solo
                     * encuentra el documento si nadie lo cambio desde que se leyo. Es
                     * atomico en el servidor, asi que dos escrituras a la vez no se pisan.
                     */
                    @Override
                    public T reemplazar(T cambiado, Long versionEsperada) {
                        Query actual = consulta(Criteria.where("id").is(cambiado.getId())
                                .and("{{v}}").is(versionEsperada));
                        cambiado.{{sv}}(versionEsperada == null ? 1L : versionEsperada + 1);
                        cambiado.{{sa}}(LocalDateTime.now());
                        if (plantilla.findAndReplace(actual, cambiado) == null) {
                            cambiado.{{sv}}(versionEsperada);
                            throw new OptimisticLockingFailureException(clase.getSimpleName() + " " + cambiado.getId()
                                    + " cambio mientras se editaba");
                        }
                        return cambiado;
                    }

                    @Override
                    public void eliminar(UUID id) {
                        plantilla.remove(consulta(Criteria.where("id").is(id)), clase);
                    }

                    @Override
                    public void eliminarPorCampo(String campo, Object valor) {
                        plantilla.remove(consulta(Criteria.where(campo).is(valor)), clase);
                    }
                }
                """
                .replace("{{pkg}}", pkg)
                .replace("{{v}}", m.version())
                .replace("{{sv}}", NombresJava.setter(m.version()))
                .replace("{{sc}}", NombresJava.setter(m.creado()))
                .replace("{{sa}}", NombresJava.setter(m.actualizado()));
    }

    private static String incrustado(String pkg, Meta m) {
        return """
                package {{pkg}}.repository;

                import {{pkg}}.domain.Registro;
                import {{pkg}}.service.ConflictoException;
                import {{pkg}}.service.Pagina;
                import {{pkg}}.service.PeticionInvalidaException;
                import org.springframework.beans.BeanWrapperImpl;
                import org.springframework.dao.OptimisticLockingFailureException;
                import org.springframework.data.domain.Pageable;
                import org.springframework.data.domain.Sort;

                import java.time.LocalDateTime;
                import java.util.ArrayList;
                import java.util.Comparator;
                import java.util.List;
                import java.util.NoSuchElementException;
                import java.util.Objects;
                import java.util.Optional;
                import java.util.UUID;
                import java.util.function.BiConsumer;
                import java.util.function.Function;

                /**
                 * Partes de una composicion, guardadas DENTRO del documento de su todo
                 * (ADR-035). La API las sigue tratando como un recurso propio
                 * (`/api/linea-pedido`), igual que en PostgreSQL: este almacen
                 * traduce cada operacion a leer y reescribir el documento del todo.
                 *
                 * Toda escritura reemplaza el todo condicionado a SU version, asi que
                 * dos cambios a la vez sobre el mismo todo no se pisan; el que llega
                 * segundo recibe un 409. El precio es que cambiar una parte tambien
                 * sube la version del todo.
                 */
                public class IncrustadoMongo<T extends Registro, P extends Registro> extends AlmacenMongo<T> {

                    private final ColeccionMongo<P> padres;
                    private final String arreglo;
                    private final String campoPadre;
                    private final boolean multiple;
                    private final String nombre;
                    private final Function<P, List<T>> leer;
                    private final BiConsumer<P, List<T>> escribir;
                    private final Function<T, UUID> padreDe;
                    private final BiConsumer<T, UUID> asignarPadre;

                    public IncrustadoMongo(ColeccionMongo<P> padres, String arreglo, String campoPadre, boolean multiple,
                                           String nombre, Function<P, List<T>> leer, BiConsumer<P, List<T>> escribir,
                                           Function<T, UUID> padreDe, BiConsumer<T, UUID> asignarPadre) {
                        this.padres = padres;
                        this.arreglo = arreglo;
                        this.campoPadre = campoPadre;
                        this.multiple = multiple;
                        this.nombre = nombre;
                        this.leer = leer;
                        this.escribir = escribir;
                        this.padreDe = padreDe;
                        this.asignarPadre = asignarPadre;
                    }

                    /** Las partes de un todo, con el id del todo ya puesto (no se guarda: es implicito). */
                    private List<T> partesDe(P padre) {
                        List<T> partes = new ArrayList<>(leer.apply(padre));
                        partes.forEach(p -> asignarPadre.accept(p, padre.getId()));
                        return partes;
                    }

                    private Optional<P> padreConParte(UUID id) {
                        return padres.unoConCampo(arreglo + ".id", id);
                    }

                    @Override
                    public Optional<T> buscar(UUID id) {
                        return padreConParte(id).flatMap(p -> partesDe(p).stream()
                                .filter(x -> id.equals(x.getId())).findFirst());
                    }

                    @Override
                    public List<T> todos(Sort orden) {
                        return ordenar(padres.todos(Sort.unsorted()).stream().flatMap(p -> partesDe(p).stream()).toList(),
                                orden);
                    }

                    @Override
                    public Pagina<T> pagina(Pageable pagina) {
                        return recortar(todos(pagina.getSort()), pagina);
                    }

                    @Override
                    public List<T> porCampo(String campo, Object valor, Sort orden) {
                        List<T> encontradas;
                        if (campo.equals(campoPadre)) {
                            encontradas = valor instanceof UUID id
                                    ? padres.buscar(id).map(this::partesDe).orElse(List.of()) : List.of();
                        } else {
                            encontradas = padres.porCampo(arreglo + "." + campo, valor, Sort.unsorted()).stream()
                                    .flatMap(p -> partesDe(p).stream())
                                    .filter(x -> Objects.equals(valor, valorDe(x, campo)))
                                    .toList();
                        }
                        return ordenar(encontradas, orden);
                    }

                    @Override
                    public Pagina<T> porCampo(String campo, Object valor, Pageable pagina) {
                        return recortar(porCampo(campo, valor, pagina.getSort()), pagina);
                    }

                    @Override
                    public boolean existeConCampo(String ruta, Object valor) {
                        return !porCampo(ruta, valor, Sort.unsorted()).isEmpty();
                    }

                    @Override
                    public T insertar(T nueva) {
                        P padre = padreObligatorio(padreDe.apply(nueva));
                        List<T> partes = new ArrayList<>(leer.apply(padre));
                        if (!multiple && !partes.isEmpty()) {
                            throw new ConflictoException(padre.getId() + " ya tiene su " + nombre);
                        }
                        LocalDateTime ahora = LocalDateTime.now();
                        if (nueva.getId() == null) nueva.setId(UUID.randomUUID());
                        nueva.{{sv}}(0L);
                        nueva.{{sc}}(ahora);
                        nueva.{{sa}}(ahora);
                        partes.add(nueva);
                        escribir.accept(padre, partes);
                        padres.reemplazar(padre, padre.{{gv}}());
                        asignarPadre.accept(nueva, padre.getId());
                        return nueva;
                    }

                    @Override
                    public T reemplazar(T cambiada, Long versionEsperada) {
                        P actual = padreConParte(cambiada.getId())
                                .orElseThrow(() -> new NoSuchElementException("No existe " + nombre + " " + cambiada.getId()));
                        List<T> partes = new ArrayList<>(leer.apply(actual));
                        int i = indice(partes, cambiada.getId());
                        T previa = partes.get(i);
                        if (!Objects.equals(previa.{{gv}}(), versionEsperada)) {
                            throw new OptimisticLockingFailureException(nombre + " " + cambiada.getId()
                                    + " cambio mientras se editaba");
                        }
                        cambiada.{{sv}}(versionEsperada == null ? 1L : versionEsperada + 1);
                        cambiada.{{sc}}(previa.{{gc}}());
                        cambiada.{{sa}}(LocalDateTime.now());

                        UUID destino = padreDe.apply(cambiada);
                        if (destino == null || destino.equals(actual.getId())) {
                            partes.set(i, cambiada);
                            escribir.accept(actual, partes);
                            padres.reemplazar(actual, actual.{{gv}}());
                            asignarPadre.accept(cambiada, actual.getId());
                            return cambiada;
                        }
                        // Dos documentos no se pueden reemplazar atomicamente sin
                        // transacciones MongoDB. Rechazar antes de escribir preserva
                        // la parte incluso si el destino cambia concurrentemente.
                        throw new ConflictoException("No se puede cambiar " + campoPadre
                                + " de una parte incrustada: requiere una operacion atomica entre documentos");
                    }

                    @Override
                    public void eliminar(UUID id) {
                        padreConParte(id).ifPresent(padre -> {
                            List<T> partes = new ArrayList<>(leer.apply(padre));
                            partes.removeIf(x -> id.equals(x.getId()));
                            escribir.accept(padre, partes);
                            padres.reemplazar(padre, padre.{{gv}}());
                        });
                    }

                    @Override
                    public void eliminarPorCampo(String campo, Object valor) {
                        for (T parte : porCampo(campo, valor, Sort.unsorted())) eliminar(parte.getId());
                    }

                    private P padreObligatorio(UUID id) {
                        if (id == null) {
                            throw new PeticionInvalidaException(campoPadre, "Es obligatorio: dice a que registro pertenece");
                        }
                        return padres.buscar(id)
                                .orElseThrow(() -> new ConflictoException("No existe el registro " + id + " (" + campoPadre + ")"));
                    }

                    private static int indice(List<? extends Registro> partes, UUID id) {
                        for (int i = 0; i < partes.size(); i++) {
                            if (id.equals(partes.get(i).getId())) return i;
                        }
                        throw new NoSuchElementException("No existe " + id);
                    }

                    private static Object valorDe(Object objeto, String propiedad) {
                        return new BeanWrapperImpl(objeto).getPropertyValue(propiedad);
                    }

                    /** El orden en memoria: las partes ya se leyeron dentro de sus todos. */
                    @SuppressWarnings({"unchecked", "rawtypes"})
                    private List<T> ordenar(List<T> lista, Sort orden) {
                        if (orden == null || orden.isUnsorted()) return lista;
                        Comparator<T> comparador = null;
                        for (Sort.Order o : orden) {
                            Comparator<T> uno = (a, b) -> {
                                Comparable va = (Comparable) valorDe(a, o.getProperty());
                                Comparable vb = (Comparable) valorDe(b, o.getProperty());
                                if (va == null) return vb == null ? 0 : 1;
                                if (vb == null) return -1;
                                return va.compareTo(vb);
                            };
                            if (o.isDescending()) uno = uno.reversed();
                            comparador = comparador == null ? uno : comparador.thenComparing(uno);
                        }
                        return lista.stream().sorted(comparador).toList();
                    }

                    private Pagina<T> recortar(List<T> todas, Pageable pagina) {
                        int desde = (int) Math.min(pagina.getOffset(), todas.size());
                        int hasta = Math.min(desde + pagina.getPageSize(), todas.size());
                        return new Pagina<>(todas.subList(desde, hasta), todas.size());
                    }
                }
                """
                .replace("{{pkg}}", pkg)
                .replace("{{gv}}", NombresJava.getter(m.version()))
                .replace("{{sv}}", NombresJava.setter(m.version()))
                .replace("{{gc}}", NombresJava.getter(m.creado()))
                .replace("{{sc}}", NombresJava.setter(m.creado()))
                .replace("{{sa}}", NombresJava.setter(m.actualizado()));
    }

    // ------------------------------------------------------------ SERVICIO

    /**
     * Algo que hacer antes de borrar: comprobar si la coleccion de `claseRepo`
     * tiene en `ruta` un id que apunta al que se borra, o borrar en cascada.
     */
    private record Comprobacion(String claseRepo, String repositorio, String ruta, String quien) { }

    private static Comprobacion comprobacion(String clase, String claseRepo, String ruta, String quien) {
        return new Comprobacion(claseRepo, repo(clase, claseRepo), ruta, quien);
    }

    private static String servicio(String pkg, Unidad u, List<Unidad> unidades, MapeoDocumental m, Meta meta) {
        String clase = u.clase();
        Set<String> destinos = new LinkedHashSet<>(m.conAncestros(clase));

        // Quien apunta a esta entidad (o a una de sus madres: la fila es la misma).
        List<Comprobacion> comprobaciones = new ArrayList<>();
        List<Comprobacion> cascadas = new ArrayList<>();
        for (Unidad otra : unidades) {
            Optional<Incrustacion> inc = m.incrustacionDe(otra.clase());
            for (Asociacion a : otra.asociacionesPropias()) {
                if (!destinos.contains(a.claseDestino())) continue;
                String campo = a.campo() + "Id";
                String quien = otra.clase() + "." + a.campo();
                if (inc.isPresent() && inc.get().campoPadre().equals(campo)) continue;   // es la incrustacion misma
                if (inc.isPresent()) {
                    comprobaciones.add(comprobacion(clase, inc.get().todo().clase(),
                            inc.get().arreglo() + "." + campo, quien));
                } else if (a.kind() == RelationKind.COMPOSITION) {
                    // Composicion en coleccion propia: la parte muere con el todo.
                    cascadas.add(comprobacion(clase, otra.clase(), campo, quien));
                } else {
                    comprobaciones.add(comprobacion(clase, otra.clase(), campo, quien));
                }
            }
        }
        for (ArregloDeIds a : m.arreglos()) {
            if (destinos.contains(a.destino().clase())) {
                comprobaciones.add(comprobacion(clase, a.duenia().clase(), a.campo(),
                        a.duenia().clase() + "." + a.campo()));
            }
        }

        // Repositorios que hacen falta: los de las referencias y los de quien referencia.
        Set<String> necesarios = new LinkedHashSet<>();
        u.asociaciones().forEach(a -> necesarios.add(a.claseDestino()));
        for (Comprobacion c : comprobaciones) necesarios.add(c.claseRepo());
        for (Comprobacion c : cascadas) necesarios.add(c.claseRepo());
        necesarios.remove(clase);

        StringBuilder deps = new StringBuilder();
        StringBuilder params = new StringBuilder();
        StringBuilder asigna = new StringBuilder();
        StringBuilder importsRel = new StringBuilder();
        for (String otra : necesarios) {
            String r = repo(clase, otra);
            deps.append("    private final ").append(otra).append("Repository ").append(r).append(";\n");
            params.append(", ").append(otra).append("Repository ").append(r);
            asigna.append("        this.").append(r).append(" = ").append(r).append(";\n");
            importsRel.append("import ").append(pkg).append(".repository.").append(otra).append("Repository;\n");
        }
        new TreeSet<>(u.incrustados().stream().map(i -> NombresJava.clase(i.clase())).toList())
                .forEach(v -> importsRel.append("import ").append(pkg).append(".web.dto.").append(v).append("Dto;\n"));
        for (String imp : u.stubs().imports()) importsRel.append("import ").append(imp).append(";\n");

        StringBuilder copiar = new StringBuilder();
        for (Campo c : u.campos()) {
            if (c.esId()) continue;
            copiar.append("        entidad.").append(NombresJava.setter(c.nombreJava()))
                    .append("(dto.").append(c.nombreJava()).append("());\n");
        }
        for (Asociacion a : u.asociaciones()) {
            String id = a.campo() + "Id";
            copiar.append("        entidad.").append(NombresJava.setter(id)).append("(dto.").append(id).append("());\n");
        }
        for (ColeccionDeValores v : u.valores()) {
            copiar.append("        entidad.").append(NombresJava.setter(v.campo()))
                    .append("(dto.").append(v.campo()).append("() == null ? new ArrayList<>() : new ArrayList<>(dto.")
                    .append(v.campo()).append("()));\n");
        }
        for (ModeloLogico.Incrustado i : u.incrustados()) {
            String campo = NombresJava.campo(i.campo());
            copiar.append("        entidad.").append(NombresJava.setter(campo))
                    .append("(dto.").append(campo).append("() == null ? null : dto.").append(campo)
                    .append("().aEntidad());\n");
        }

        StringBuilder referencias = new StringBuilder();
        for (Asociacion a : u.asociaciones()) {
            String getter = "entidad." + NombresJava.getter(a.campo() + "Id") + "()";
            referencias.append("        if (").append(getter).append(" != null && !")
                    .append(repo(clase, a.claseDestino())).append(".existe(").append(getter).append(")) {\n")
                    .append("            throw new ConflictoException(\"No existe ").append(a.claseDestino())
                    .append(" \" + ").append(getter).append(" + \" (").append(a.campo()).append("Id)\");\n")
                    .append("        }\n");
        }

        StringBuilder antesDeBorrar = new StringBuilder();
        for (Comprobacion c : comprobaciones) {
            antesDeBorrar.append("        if (").append(c.repositorio()).append(".existeConCampo(\"").append(c.ruta())
                    .append("\", id)) {\n")
                    .append("            throw new ConflictoException(\"No se puede eliminar ").append(clase)
                    .append(" \" + id + \": ").append(c.quien())
                    .append(" todavia lo referencia. Elimina o reasigna eso primero.\");\n")
                    .append("        }\n");
        }
        for (Comprobacion c : cascadas) {
            antesDeBorrar.append("        // Composicion en otra coleccion: no hacer una cascada parcial.\n")
                    .append("        if (").append(c.repositorio()).append(".existeConCampo(\"").append(c.ruta())
                    .append("\", id)) {\n")
                    .append("            throw new ConflictoException(\"Elimina primero las partes ")
                    .append(c.quien()).append(": la cascada entre colecciones requiere una transaccion atomica\");\n")
                    .append("        }\n");
        }

        List<String> aDto = new ArrayList<>();
        for (Campo c : u.campos()) aDto.add("e." + NombresJava.getter(c.nombreJava()) + "()");
        for (Asociacion a : u.asociaciones()) aDto.add("e." + NombresJava.getter(a.campo() + "Id") + "()");
        for (ColeccionDeValores v : u.valores()) {
            String g = "e." + NombresJava.getter(v.campo()) + "()";
            aDto.add(g + " == null ? List.of() : List.copyOf(" + g + ")");
        }
        for (ModeloLogico.Incrustado i : u.incrustados()) {
            String g = "e." + NombresJava.getter(NombresJava.campo(i.campo())) + "()";
            aDto.add(g + " == null ? null : " + NombresJava.clase(i.clase()) + "Dto.de(" + g + ")");
        }
        aDto.add("e." + NombresJava.getter(meta.version()) + "()");
        aDto.add("e." + NombresJava.getter(meta.creado()) + "()");
        aDto.add("e." + NombresJava.getter(meta.actualizado()) + "()");

        StringBuilder porFk = new StringBuilder();
        for (Asociacion a : u.asociaciones()) {
            porFk.append("""

                        public Pagina<{{clase}}Dto> listarPor{{Campo}}(UUID {{id}}, Integer page, Integer size, String sort) {
                            Sort orden = Paginas.orden(sort, ORDENABLES);
                            if (page == null) {
                                List<{{clase}}Dto> todos = repositorio.porCampo("{{id}}", {{id}}, orden).stream()
                                        .map({{clase}}Service::aDto).toList();
                                return new Pagina<>(todos, todos.size());
                            }
                            Pagina<{{clase}}> pagina = repositorio.porCampo("{{id}}", {{id}}, Paginas.pagina(page, size, orden));
                            return new Pagina<>(pagina.contenido().stream().map({{clase}}Service::aDto).toList(), pagina.total());
                        }
                    """
                    .replace("{{Campo}}", PlantillasProduccion.mayuscula(a.campo()))
                    .replace("{{id}}", a.campo() + "Id")
                    .replace("{{clase}}", clase));
        }

        String escritura = !u.instanciable() ? "" : """
                    public {{clase}}Dto crear({{clase}}Dto dto) {
                        {{clase}} entidad = new {{clase}}();
                        copiar(dto, entidad);
                        comprobarReferencias(entidad);
                        return aDto(repositorio.insertar(entidad));
                    }

                    /**
                     * Reemplazo completo. Si el cliente manda la version que leyo y
                     * ya no es la actual, alguien escribio en el medio: 409.
                     */
                    public {{clase}}Dto actualizar(UUID id, {{clase}}Dto dto) {
                        {{clase}} entidad = buscar(id);
                        Versiones.comprobar(dto.{{version}}(), entidad.{{getVersion}}(), "{{clase}}", id);
                        Long esperada = entidad.{{getVersion}}();
                        copiar(dto, entidad);
                        comprobarReferencias(entidad);
                        return aDto(repositorio.reemplazar(entidad, esperada));
                    }

                """;
        String auxiliares = !u.instanciable() ? "" : """
                    private void copiar({{clase}}Dto dto, {{clase}} entidad) {
                {{copiar}}    }

                    /** MongoDB no tiene FK: apuntar a un id que no existe es un 409, como en PostgreSQL. */
                    private void comprobarReferencias({{clase}} entidad) {
                {{referencias}}    }

                """;

        return """
                package {{pkg}}.service;

                import {{pkg}}.domain.{{clase}};
                import {{pkg}}.repository.{{clase}}Repository;
                {{importsRel}}import {{pkg}}.web.dto.{{clase}}Dto;
                import org.springframework.data.domain.Sort;
                import org.springframework.stereotype.Service;

                import java.util.ArrayList;
                import java.util.List;
                import java.util.NoSuchElementException;
                import java.util.Set;
                import java.util.UUID;

                /**
                 * REGLA 6: el comportamiento vive aca. Tambien la integridad
                 * referencial, que en MongoDB no la cuida la base (ADR-035).
                 */
                @Service
                public class {{clase}}Service {

                    /** Por que campos se puede ordenar el listado (`sort=campo,asc|desc`). */
                    static final Set<String> ORDENABLES = {{ordenables}};

                    private final {{clase}}Repository repositorio;
                {{deps}}
                    public {{clase}}Service({{clase}}Repository repositorio{{params}}) {
                        this.repositorio = repositorio;
                {{asigna}}    }

                    /** Sin `page` devuelve todo; con `page`, esa pagina. El total viaja en X-Total-Count. */
                    public Pagina<{{clase}}Dto> listar(Integer page, Integer size, String sort) {
                        Sort orden = Paginas.orden(sort, ORDENABLES);
                        if (page == null) {
                            List<{{clase}}Dto> todos = repositorio.todos(orden).stream().map({{clase}}Service::aDto).toList();
                            return new Pagina<>(todos, todos.size());
                        }
                        Pagina<{{clase}}> pagina = repositorio.pagina(Paginas.pagina(page, size, orden));
                        return new Pagina<>(pagina.contenido().stream().map({{clase}}Service::aDto).toList(), pagina.total());
                    }
                {{porFk}}
                    public {{clase}}Dto obtener(UUID id) {
                        return aDto(buscar(id));
                    }

                {{escritura}}    public void eliminar(UUID id) {
                        if (!repositorio.existe(id)) {
                            throw new NoSuchElementException("No existe {{clase}} " + id);
                        }
                {{antesDeBorrar}}        repositorio.eliminar(id);
                    }

                    private {{clase}} buscar(UUID id) {
                        return repositorio.buscar(id)
                                .orElseThrow(() -> new NoSuchElementException("No existe {{clase}} " + id));
                    }

                {{auxiliares}}    static {{clase}}Dto aDto({{clase}} e) {
                        return new {{clase}}Dto(
                                {{aDto}});
                    }
                {{stubs}}}
                """
                .replace("{{stubs}}", u.stubs().codigo())
                .replace("{{escritura}}", escritura)
                .replace("{{auxiliares}}", auxiliares)
                .replace("{{copiar}}", copiar.toString())
                .replace("{{referencias}}", referencias.toString())
                .replace("{{antesDeBorrar}}", antesDeBorrar.toString())
                .replace("{{porFk}}", porFk.toString())
                .replace("{{ordenables}}", GeneradorSpringBoot.ordenables(u.campos(), meta))
                .replace("{{deps}}", deps.toString())
                .replace("{{params}}", params.toString())
                .replace("{{asigna}}", asigna.toString())
                .replace("{{importsRel}}", importsRel.toString())
                .replace("{{aDto}}", String.join(",\n                ", aDto))
                .replace("{{version}}", meta.version())
                .replace("{{getVersion}}", NombresJava.getter(meta.version()))
                .replace("{{pkg}}", pkg)
                .replace("{{clase}}", clase);
    }

    /** El campo del repositorio de `otra` dentro del Service de `clase` (el propio es `repositorio`). */
    private static String repo(String clase, String otra) {
        return clase.equals(otra) ? "repositorio" : NombresJava.campo(otra) + "Repository";
    }

    // ------------------------------------------------------------- AYUDAS

    /** Ejecuta el almacen real contra un padre simulado: no requiere MongoDB. */
    private static String pruebaIncrustado(String pkg, Meta meta) {
        return """
                package {{pkg}}.repository;

                import {{pkg}}.domain.Registro;
                import {{pkg}}.service.ConflictoException;
                import org.junit.jupiter.api.Test;
                import java.util.*;
                import static org.junit.jupiter.api.Assertions.*;
                import static org.mockito.Mockito.*;

                class IncrustadoMongoSeguridadTest {
                    @SuppressWarnings("unchecked")
                    @Test
                    void trasladar_una_parte_se_rechaza_sin_escribir_ningun_padre() {
                        ColeccionMongo<Registro> padres = mock(ColeccionMongo.class);
                        Registro origen = mock(Registro.class);
                        Registro anterior = mock(Registro.class);
                        Registro cambio = mock(Registro.class);
                        UUID id = UUID.randomUUID(), origenId = UUID.randomUUID(), destinoId = UUID.randomUUID();
                        when(origen.getId()).thenReturn(origenId);
                        when(anterior.getId()).thenReturn(id);
                        when(anterior.{{gv}}()).thenReturn(0L);
                        when(cambio.getId()).thenReturn(id);
                        when(padres.unoConCampo("partes.id", id)).thenReturn(Optional.of(origen));
                        List<Registro> originales = new ArrayList<>(List.of(anterior));
                        IncrustadoMongo<Registro, Registro> almacen = new IncrustadoMongo<>(padres,
                                "partes", "padreId", true, "Parte", p -> originales,
                                (p, partes) -> fail("No debe escribir ni siquiera en el padre en memoria"),
                                p -> destinoId, (p, padre) -> {});
                        assertThrows(ConflictoException.class, () -> almacen.reemplazar(cambio, 0L));
                        assertEquals(List.of(anterior), originales);
                        verify(padres, never()).reemplazar(any(), any());
                        verify(padres, never()).insertar(any());
                        verify(padres, never()).eliminar(any());
                    }

                    @SuppressWarnings("unchecked")
                    @Test
                    void editar_en_el_mismo_padre_sigue_guardando_una_vez() {
                        ColeccionMongo<Registro> padres = mock(ColeccionMongo.class);
                        Registro origen = mock(Registro.class);
                        Registro anterior = mock(Registro.class);
                        Registro cambio = mock(Registro.class);
                        UUID id = UUID.randomUUID(), origenId = UUID.randomUUID();
                        when(origen.getId()).thenReturn(origenId);
                        when(origen.{{gv}}()).thenReturn(3L);
                        when(anterior.getId()).thenReturn(id);
                        when(anterior.{{gv}}()).thenReturn(0L);
                        when(cambio.getId()).thenReturn(id);
                        when(padres.unoConCampo("partes.id", id)).thenReturn(Optional.of(origen));
                        List<Registro> originales = new ArrayList<>(List.of(anterior));
                        IncrustadoMongo<Registro, Registro> almacen = new IncrustadoMongo<>(padres,
                                "partes", "padreId", true, "Parte", p -> originales,
                                (p, partes) -> { originales.clear(); originales.addAll(partes); },
                                p -> origenId, (p, padre) -> {});
                        assertSame(cambio, almacen.reemplazar(cambio, 0L));
                        assertEquals(List.of(cambio), originales);
                        verify(padres).reemplazar(origen, 3L);
                        verify(cambio).{{sv}}(1L);
                    }
                }
                """.replace("{{pkg}}", pkg)
                .replace("{{gv}}", NombresJava.getter(meta.version()))
                .replace("{{sv}}", NombresJava.setter(meta.version()));
    }

    /** Prueba el Service emitido, sin una base y sin ejecutar cascadas parciales. */
    private static String pruebaCascada(String pkg, Unidad u, List<Unidad> unidades, MapeoDocumental m) {
        List<Comprobacion> cascadas = new ArrayList<>();
        Set<String> destinos = new HashSet<>(m.conAncestros(u.clase()));
        for (Unidad otra : unidades) {
            if (m.incrustacionDe(otra.clase()).isPresent()) continue;
            for (Asociacion a : otra.asociacionesPropias()) {
                if (a.kind() == RelationKind.COMPOSITION && destinos.contains(a.claseDestino()))
                    cascadas.add(comprobacion(u.clase(), otra.clase(), a.campo() + "Id", otra.clase()));
            }
        }
        if (cascadas.isEmpty()) return "";
        StringBuilder mocks = new StringBuilder(), pruebas = new StringBuilder();
        for (Unidad otra : unidades) mocks.append("    @Mock ").append(otra.clase()).append("Repository ")
                .append(repo(u.clase(), otra.clase())).append(";\n");
        for (int i = 0; i < cascadas.size(); i++) {
            Comprobacion c = cascadas.get(i);
            pruebas.append("""
                        @Test
                        void rechaza_cascada_{{i}}_antes_de_borrar() {
                            UUID id = UUID.randomUUID();
                            when(repositorio.existe(id)).thenReturn(true);
                            when({{repo}}.existeConCampo("{{campo}}", id)).thenReturn(true);
                            assertThrows(ConflictoException.class, () -> servicio.eliminar(id));
                            verify(repositorio, never()).eliminar(any());
                            verify({{repo}}, never()).eliminarPorCampo(anyString(), any());
                        }
                    """.replace("{{i}}", Integer.toString(i)).replace("{{repo}}", c.repositorio())
                    .replace("{{campo}}", c.ruta()));
        }
        return """
                package {{pkg}}.service;
                import {{pkg}}.repository.*;
                import org.junit.jupiter.api.Test;
                import org.junit.jupiter.api.extension.ExtendWith;
                import org.mockito.InjectMocks;
                import org.mockito.Mock;
                import org.mockito.junit.jupiter.MockitoExtension;
                import java.util.UUID;
                import static org.junit.jupiter.api.Assertions.assertThrows;
                import static org.mockito.Mockito.*;
                @ExtendWith(MockitoExtension.class)
                class {{clase}}IntegridadMongoTest {
                    @InjectMocks {{clase}}Service servicio;
                {{mocks}}
                {{pruebas}}
                }
                """.replace("{{pkg}}", pkg).replace("{{clase}}", u.clase())
                .replace("{{mocks}}", mocks).replace("{{pruebas}}", pruebas);
    }

    private static String accesores(String tipo, String campo) {
        return "    public " + tipo + " " + NombresJava.getter(campo) + "() { return " + campo + "; }\n"
                + "    public void " + NombresJava.setter(campo) + "(" + tipo + " " + campo
                + ") { this." + campo + " = " + campo + "; }\n";
    }

    /** Los imports de java.* que piden estos tipos (UUID, List y LocalDateTime ya estan). */
    private static String imports(List<String> tipos) {
        Set<String> salida = new TreeSet<>();
        for (String t : tipos) {
            String imp = TipoJava.importDe(t);
            if (imp != null && !imp.equals("java.util.UUID") && !imp.equals("java.time.LocalDateTime")
                    && !imp.equals("java.util.List")) {
                salida.add("import " + imp + ";");
            }
        }
        return salida.isEmpty() ? "" : String.join("\n", salida) + "\n";
    }
}
