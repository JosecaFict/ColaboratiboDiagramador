package com.uagrm.colabmodel.codegen;

import com.uagrm.colabmodel.codegen.GeneradorSpringBoot.Almacen;
import com.uagrm.colabmodel.modelcore.mapping.DerivadorLogico;
import com.uagrm.colabmodel.modelcore.mapping.ModeloCanonico;
import com.uagrm.colabmodel.modelcore.metamodel.UmlAttribute;
import com.uagrm.colabmodel.modelcore.metamodel.UmlClass;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-034: el backend generado sale listo para produccion. Lo que se afirma
 * aca es la FORMA del codigo; que ademas compile, arranque y responda lo
 * prueban ArranqueDelProyectoGeneradoTest y `mvn package` sobre lo generado
 * (EscribirGeneradosTest), que ejecuta las pruebas MockMvc del propio zip.
 */
class GeneradorProduccionTest {

    private static final String JAVA = "src/main/java/com/uagrm/generado/";

    private static ProyectoGenerado generar(String canonico, Almacen almacen) {
        ModeloCanonico.Cargado m = ModeloCanonico.cargar(canonico);
        return GeneradorSpringBoot.generar(GeneradorSpringBoot.Opciones.por("Demo", almacen),
                m.clases(), m.atributos(), List.of(), m.relaciones());
    }

    @Test
    void el_controlador_cumple_el_contrato_rest() {
        String c = generar("02-uno-a-muchos.json", Almacen.POSTGRES).archivos().get(JAVA + "web/PedidoController.java");

        assertThat(c)
                .contains("@RequestParam(required = false) Integer page")
                .contains("@RequestParam(required = false) String sort")
                .contains(".header(Pagina.CABECERA_TOTAL, String.valueOf(resultado.total()))")
                .contains("return ResponseEntity.created(ubicacion).body(creado);")
                .contains("@PatchMapping(\"/{id}\")")
                .contains("Parches.aplicar(mapper, validador, servicio.obtener(id), cambios,")
                .contains("return ResponseEntity.noContent().build();");
    }

    @Test
    void sin_page_el_listado_devuelve_todo() {
        String s = generar("02-uno-a-muchos.json", Almacen.POSTGRES).archivos().get(JAVA + "service/PedidoService.java");

        // La app movil lista sin page: tiene que seguir recibiendo todo.
        assertThat(s).contains("if (page == null) {")
                .contains("repositorio.findAll(orden)")
                .contains("repositorio.findAll(Paginas.pagina(page, size, orden))");
    }

    @Test
    void borrar_algo_referenciado_es_un_409_que_dice_quien_lo_sostiene() {
        String s = generar("02-uno-a-muchos.json", Almacen.POSTGRES).archivos().get(JAVA + "service/ClienteService.java");

        assertThat(s).contains("Map.entry(\"fk_pedido_cliente_id\", \"Pedido.cliente\")")
                .contains("catch (DataIntegrityViolationException e)")
                .contains("throw new ConflictoException(\"No se puede eliminar Cliente \"");
    }

    @Test
    void apuntar_a_un_id_inexistente_es_un_409_que_dice_cual() {
        String s = generar("02-uno-a-muchos.json", Almacen.POSTGRES).archivos().get(JAVA + "service/PedidoService.java");

        assertThat(s).contains("!clienteRepository.existsById(dto.clienteId())")
                .contains("\"No existe Cliente \" + dto.clienteId() + \" (clienteId)\"");
    }

    @Test
    void la_version_vieja_es_un_409() {
        ProyectoGenerado p = generar("02-uno-a-muchos.json", Almacen.POSTGRES);

        assertThat(p.archivos().get(JAVA + "domain/Pedido.java"))
                .contains("@Version").contains("private Long version;")
                .contains("@PrePersist").contains("@PreUpdate").contains("private LocalDateTime createdAt;");
        assertThat(p.archivos().get(JAVA + "service/PedidoService.java"))
                .contains("Versiones.comprobar(dto.version(), entidad.getVersion(), \"Pedido\", id);");
        assertThat(p.archivos().get(JAVA + "web/dto/PedidoDto.java"))
                .contains("Long version").contains("LocalDateTime createdAt").contains("LocalDateTime updatedAt");
        assertThat(p.archivos().get(JAVA + "web/ManejadorDeErrores.java"))
                .contains("@ExceptionHandler(OptimisticLockingFailureException.class)");
    }

    @Test
    void la_auditoria_va_en_V2_y_V1_sigue_siendo_la_base_confirmada() {
        ModeloCanonico.Cargado m = ModeloCanonico.cargar("04-herencia.json");
        ProyectoGenerado p = GeneradorSpringBoot.generar(GeneradorSpringBoot.Opciones.por("Demo"),
                m.clases(), m.atributos(), List.of(), m.relaciones());

        // V1 es exactamente el DDL de la Vista Fisica (ADR-032): su huella no cambia.
        assertThat(p.archivos().get(GeneradorSpringBoot.RUTA_DDL))
                .isEqualTo(GeneradorSpringBoot.ddl(DerivadorLogico.derivar(m.clases(), m.atributos(), m.relaciones())))
                .doesNotContain("created_at");
        // Solo la raiz JOINED lleva las columnas: la hija comparte la fila.
        assertThat(p.archivos().get(GeneradorSpringBoot.RUTA_AUDITORIA))
                .contains("ALTER TABLE persona ADD COLUMN version bigint DEFAULT 0 NOT NULL;")
                .doesNotContain("ALTER TABLE empleado");
        assertThat(p.archivos().get(JAVA + "domain/Empleado.java")).doesNotContain("@Version");
    }

    @Test
    void la_clase_abstracta_no_recibe_filas_de_ejemplo_huerfanas() {
        // Una fila de persona sin la de un Empleado o un Cliente es un objeto
        // que Java no puede construir: GET /api/persona respondia 500.
        String demo = generar("04-herencia.json", Almacen.POSTGRES).archivos().get(DatosDeEjemplo.RUTA);

        assertThat(demo == null ? "" : demo).doesNotContain("INSERT INTO persona ");
        if (demo != null) assertThat(demo).contains("persona: es de una clase abstracta");
    }

    @Test
    void si_el_modelo_ya_usa_version_la_auditoria_cambia_de_nombre() {
        UmlClass software = new UmlClass();
        software.setId(UUID.randomUUID());
        software.setDiagramId(UUID.randomUUID());
        software.setName("Software");
        UmlAttribute version = new UmlAttribute();
        version.setId(UUID.randomUUID());
        version.setClassId(software.getId());
        version.setName("version");
        version.setDataType("string");

        ProyectoGenerado p = GeneradorSpringBoot.generar(GeneradorSpringBoot.Opciones.por("Demo"),
                List.of(software), List.of(version), List.of(), List.of());

        assertThat(p.archivos().get(JAVA + "domain/Software.java"))
                .contains("private String version;").contains("private Long versionRegistro;");
        assertThat(p.archivos().get(GeneradorSpringBoot.RUTA_AUDITORIA)).contains("ADD COLUMN version_registro");
    }

    @Test
    void los_perfiles_dev_y_prod_y_actuator() {
        String yml = generar("02-uno-a-muchos.json", Almacen.POSTGRES).archivos().get("src/main/resources/application.yml");

        assertThat(yml)
                .contains("default: dev")
                .contains("port: ${PORT:8081}")
                .contains("shutdown: graceful")
                .contains("include: health,info")
                .contains("on-profile: prod")
                .contains("url: ${DB_URL}").contains("username: ${DB_USER}").contains("password: ${DB_PASSWORD}")
                .contains("origenes: ${CORS_ORIGINS:}")
                .contains("include-stacktrace: never");
    }

    @Test
    void el_perfil_postgres_apunta_a_la_base_del_docker_de_colabmodel() {
        var p = generar("02-uno-a-muchos.json", Almacen.POSTGRES);
        String yml = p.archivos().get("src/main/resources/application.yml");
        String readme = p.archivos().get("README.md");

        assertThat(yml).contains("on-profile: postgres")
                .contains("jdbc:postgresql://127.0.0.1:55432/").doesNotContain(":5433/");
        assertThat(readme).contains("CREATE DATABASE ").doesNotContain("{{db}}");
    }

    @Test
    void trae_docker_readme_y_una_prueba_por_controlador() {
        ProyectoGenerado p = generar("02-uno-a-muchos.json", Almacen.POSTGRES);

        assertThat(p.archivos()).containsKeys("Dockerfile", "docker-compose.yml", ".dockerignore", "README.md",
                "src/test/java/com/uagrm/generado/web/PedidoControllerTest.java",
                "src/test/java/com/uagrm/generado/web/ClienteControllerTest.java");
        assertThat(p.archivos().get("Dockerfile")).contains("AS construccion").contains("FROM eclipse-temurin:21-jre")
                .contains("USER app");
        assertThat(p.archivos().get("docker-compose.yml")).contains("image: postgres:16-alpine")
                .contains("DB_URL: jdbc:postgresql://db:5432/");
        assertThat(p.archivos().get("README.md")).contains("CORS_ORIGINS").contains("X-Total-Count")
                .contains("docker compose up --build");
        assertThat(p.archivos().get("src/test/java/com/uagrm/generado/web/PedidoControllerTest.java"))
                .contains("@WebMvcTest(PedidoController.class)")
                .contains("crear_responde_201_con_location")
                .contains("un_id_que_no_existe_responde_404_con_el_formato_de_error")
                .contains("un_cuerpo_sin_lo_obligatorio_dice_que_campo_falta");
        assertThat(p.archivos().get("pom.xml")).contains("spring-boot-starter-actuator")
                .contains("spring-boot-starter-test");
    }

    /** ADR-035: la app movil no puede enterarse de que base hay atras. */
    @Test
    void el_dto_el_controlador_y_el_manifiesto_son_iguales_en_las_dos_bases() {
        for (String canonico : List.of("02-uno-a-muchos.json", "04-herencia.json", "05-clase-asociativa.json")) {
            ProyectoGenerado pg = generar(canonico, Almacen.POSTGRES);
            ProyectoGenerado mg = generar(canonico, Almacen.MONGO);
            assertThat(mg.archivos().get(ManifestDeApp.RUTA)).as(canonico)
                    .isEqualTo(pg.archivos().get(ManifestDeApp.RUTA));
            pg.archivos().keySet().stream()
                    .filter(r -> r.contains("/web/") || r.startsWith("src/test/"))
                    .forEach(r -> assertThat(mg.archivos().get(r)).as(canonico + " " + r)
                            .isEqualTo(pg.archivos().get(r)));
        }
    }
}
