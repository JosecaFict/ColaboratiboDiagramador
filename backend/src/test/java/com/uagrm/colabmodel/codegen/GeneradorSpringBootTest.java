package com.uagrm.colabmodel.codegen;

import com.uagrm.colabmodel.modelcore.mapping.ModeloCanonico;
import com.uagrm.colabmodel.modelcore.metamodel.RelationKind;
import com.uagrm.colabmodel.modelcore.metamodel.UmlClass;
import com.uagrm.colabmodel.modelcore.metamodel.UmlMethod;
import com.uagrm.colabmodel.modelcore.metamodel.Visibility;
import com.uagrm.colabmodel.modelcore.metamodel.UmlRelationship;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El esqueleto del generador contra los modelos canonicos.
 *
 * Que compile no se comprueba aca (tardaria minutos y necesitaria bajar
 * dependencias): se comprueba corriendo `mvn package` sobre lo que deja
 * `emite_el_proyecto_listo_para_compilar`, en target/generado-demo.
 */
class GeneradorSpringBootTest {

    private static final String BASE_JAVA = "src/main/java/com/uagrm/generado/";

    private static final GeneradorSpringBoot.Opciones OPCIONES =
            GeneradorSpringBoot.Opciones.por("Demo Generado");

    private ProyectoGenerado generar(String modelo) {
        ModeloCanonico.Cargado m = ModeloCanonico.cargar(modelo);
        // Los modelos canonicos no llevan metodos: la Regla 6 se prueba aparte.
        return GeneradorSpringBoot.generar(OPCIONES, m.clases(), m.atributos(),
                List.of(), m.relaciones());
    }

    @Test
    void cada_entidad_trae_sus_cinco_capas() {
        ProyectoGenerado p = generar("02-uno-a-muchos.json");
        String base = "src/main/java/com/uagrm/generado/";

        assertThat(p.archivos()).containsKeys(
                base + "domain/Pedido.java",
                base + "repository/PedidoRepository.java",
                base + "service/PedidoService.java",
                base + "web/dto/PedidoDto.java",
                base + "web/PedidoController.java");
    }

    @Test
    void el_proyecto_trae_el_andamiaje_para_arrancar() {
        ProyectoGenerado p = generar("02-uno-a-muchos.json");

        assertThat(p.archivos()).containsKeys("pom.xml", "README.md",
                "src/main/resources/application.yml",
                "src/main/resources/db/migration/V1__init.sql",
                "src/main/java/com/uagrm/generado/GeneratedApplication.java");
    }

    @Test
    void la_entidad_y_el_ddl_no_pueden_discrepar() {
        ProyectoGenerado p = generar("02-uno-a-muchos.json");

        // Los dos salen de la MISMA derivacion, asi que la FK aparece igual
        // en la clase Java y en el SQL.
        assertThat(p.archivos().get("src/main/java/com/uagrm/generado/domain/Pedido.java"))
                .contains("@JoinColumn(name = \"cliente_id\", nullable = false)");
        assertThat(p.archivos().get("src/main/resources/db/migration/V1__init.sql"))
                .contains("cliente_id uuid NOT NULL")
                .contains("ALTER TABLE pedido ADD CONSTRAINT fk_pedido_cliente_id"
                        + " FOREIGN KEY (cliente_id) REFERENCES cliente(id)");
    }

    @Test
    void una_fecha_se_emite_con_el_tipo_que_entienden_las_dos_bases() {
        ProyectoGenerado p = generar("02-uno-a-muchos.json");

        // `timestamptz` es un alias propio de PostgreSQL: H2 no lo conoce ni
        // con MODE=PostgreSQL, asi que Flyway cortaba el arranque con "Unknown
        // data type". Y su forma larga, `timestamp with time zone`, si la
        // entiende H2 pero ya no case con LocalDateTime: Hibernate la rechaza
        // al validar. El que sirve en las dos es `timestamp` a secas, que es
        // exactamente lo que LocalDateTime representa: fecha y hora sin zona.
        assertThat(p.archivos().get("src/main/resources/db/migration/V1__init.sql"))
                .contains("fecha timestamp NOT NULL")
                .doesNotContain("timestamptz");
        assertThat(p.archivos().get(BASE_JAVA + "domain/Pedido.java"))
                .contains("private LocalDateTime fecha;");
    }

    @Test
    void la_fk_se_emite_como_ManyToOne_con_LAZY_explicito() {
        ProyectoGenerado p = generar("02-uno-a-muchos.json");

        // REGLA 7: JPA trae EAGER por defecto en @ManyToOne. Dejarlo implicito
        // es el N+1 clasico, asi que el LAZY va escrito.
        assertThat(p.archivos().get("src/main/java/com/uagrm/generado/domain/Pedido.java"))
                .contains("@ManyToOne(fetch = FetchType.LAZY)")
                .contains("private Cliente cliente;")
                .doesNotContain("private UUID clienteId;");   // ya no es un uuid suelto
    }

    @Test
    void el_lado_inverso_queda_navegable() {
        ProyectoGenerado p = generar("02-uno-a-muchos.json");

        assertThat(p.archivos().get("src/main/java/com/uagrm/generado/domain/Cliente.java"))
                .contains("@OneToMany(mappedBy = \"cliente\"")
                .contains("private List<Pedido> pedidos = new ArrayList<>();");
    }

    @Test
    void el_cascade_sale_del_tipo_de_relacion() {
        // Composicion: la parte no vive sin el todo.
        assertThat(entidadDe(RelationKind.COMPOSITION, "Todo"))
                .contains("cascade = CascadeType.ALL, orphanRemoval = true");
        // Agregacion: la parte sobrevive, asi que solo se propagan altas y cambios.
        assertThat(entidadDe(RelationKind.AGGREGATION, "Todo"))
                .contains("cascade = { CascadeType.PERSIST, CascadeType.MERGE }")
                .doesNotContain("orphanRemoval");
        // Asociacion comun: sin cascade.
        assertThat(entidadDe(RelationKind.ASSOCIATION, "Todo"))
                .contains("@OneToMany(mappedBy = \"todo\")");
    }

    @Test
    void el_repositorio_trae_las_asociaciones_en_una_sola_consulta() {
        ProyectoGenerado p = generar("02-uno-a-muchos.json");

        // REGLA 7: sin @EntityGraph, listar N pedidos y tocar su cliente son
        // N+1 consultas.
        assertThat(p.archivos().get("src/main/java/com/uagrm/generado/repository/PedidoRepository.java"))
                .contains("@EntityGraph(attributePaths = { \"cliente\" })")
                .contains("List<Pedido> findAll();");
    }

    @Test
    void el_dto_lleva_el_id_de_la_asociacion_y_no_la_entidad() {
        ProyectoGenerado p = generar("02-uno-a-muchos.json");

        // Un DTO que arrastra entidades termina serializando medio grafo.
        assertThat(p.archivos().get("src/main/java/com/uagrm/generado/web/dto/PedidoDto.java"))
                .contains("UUID clienteId")
                .doesNotContain("Cliente cliente");
        assertThat(p.archivos().get("src/main/java/com/uagrm/generado/service/PedidoService.java"))
                .contains("clienteRepository.getReferenceById(dto.clienteId())");
    }

    /** Todo 1 --- parte 0..*, con el tipo de relacion que se quiera probar. */
    private String entidadDe(RelationKind kind, String claseDelTodo) {
        UUID diagrama = UUID.randomUUID();
        UmlClass todo = new UmlClass();
        todo.setId(UUID.randomUUID());
        todo.setDiagramId(diagrama);
        todo.setName(claseDelTodo);

        UmlClass parte = new UmlClass();
        parte.setId(UUID.randomUUID());
        parte.setDiagramId(diagrama);
        parte.setName("Parte");

        UmlRelationship r = new UmlRelationship();
        r.setId(UUID.randomUUID());
        r.setDiagramId(diagrama);
        r.setSourceClassId(todo.getId());
        r.setTargetClassId(parte.getId());
        r.setKind(kind);
        r.setSourceMultiplicity("1");
        r.setTargetMultiplicity("0..*");

        ProyectoGenerado p = GeneradorSpringBoot.generar(OPCIONES,
                List.of(todo, parte), List.of(), List.of(), List.of(r));
        return p.archivos().get("src/main/java/com/uagrm/generado/domain/" + claseDelTodo + ".java");
    }

    @Test
    void los_nombres_hostiles_dan_clases_y_campos_java_validos() {
        ProyectoGenerado p = generar("06-nombres-acentos.json");
        String base = "src/main/java/com/uagrm/generado/";

        assertThat(p.archivos()).containsKey(base + "domain/AnioAcademico.java");
        assertThat(p.archivos()).containsKey(base + "domain/Ninio.java");

        String ninio = p.archivos().get(base + "domain/Ninio.java");
        // El campo Java se llama "user" (valido en Java); la columna, "user_"
        // (obligatorio en PostgreSQL). Dos alfabetos distintos de reservadas.
        assertThat(ninio).contains("@Column(name = \"user_\"");
        assertThat(ninio).contains("private String user;");
    }

    @Test
    void no_emite_imports_duplicados() {
        String ninio = generar("06-nombres-acentos.json")
                .archivos().get("src/main/java/com/uagrm/generado/domain/Ninio.java");

        // Compila igual, pero codigo generado descuidado es lo primero que
        // mira alguien que desconfia del generador.
        long veces = ninio.lines().filter(l -> l.equals("import java.util.UUID;")).count();
        assertThat(veces).isEqualTo(1);
    }

    @Test
    void la_entity_compara_solo_por_id() {
        String pedido = generar("02-uno-a-muchos.json")
                .archivos().get("src/main/java/com/uagrm/generado/domain/Pedido.java");

        // REGLA 3: con los demas campos, editar una entidad le cambia la
        // identidad y rompe los Set de JPA.
        assertThat(pedido).contains("return id != null && id.equals(otro.id);");
        assertThat(pedido).contains("return Pedido.class.hashCode();");
    }

    @Test
    void una_tabla_con_pk_compuesta_entra_al_ddl_pero_avisa_que_no_genera_entity() {
        ProyectoGenerado p = generar("03-muchos-a-muchos.json");

        assertThat(p.archivos().get("src/main/resources/db/migration/V1__init.sql"))
                .contains("CREATE TABLE estudiante_materia");
        assertThat(p.advertencias()).anyMatch(a -> a.contains("clave primaria compuesta"));
        // Regla 10: lo que no se hace se dice, y ademas queda en el README.
        assertThat(p.archivos().get("README.md")).contains("clave primaria compuesta");
    }

    // --------------------------------------------- EL FRONTEND SE CONECTA

    @Test
    void el_listado_acepta_un_filtro_por_cada_fk() {
        ProyectoGenerado p = generar("02-uno-a-muchos.json");

        // El nombre del parametro es el MISMO con el que el id viaja en el DTO
        // y en app-manifest.json. Si no coincidiera, el `inverseOf` del
        // manifiesto seguiria apuntando a una ruta que no existe.
        assertThat(p.archivos().get(BASE_JAVA + "web/PedidoController.java"))
                .contains("public List<PedidoDto> listar(@RequestParam(required = false) UUID clienteId)")
                .contains("if (clienteId != null) return servicio.listarPorCliente(clienteId);")
                .contains("return servicio.listar();");
    }

    @Test
    void el_filtro_baja_hasta_la_consulta_en_vez_de_traerse_la_tabla() {
        ProyectoGenerado p = generar("02-uno-a-muchos.json");

        // Con @EntityGraph como el findAll(): filtrar no puede reintroducir el
        // N+1 que la Regla 7 ya habia cerrado.
        assertThat(p.archivos().get(BASE_JAVA + "repository/PedidoRepository.java"))
                .contains("@EntityGraph(attributePaths = { \"cliente\" })")
                .contains("List<Pedido> findByCliente_Id(UUID clienteId);");
        assertThat(p.archivos().get(BASE_JAVA + "service/PedidoService.java"))
                .contains("public List<PedidoDto> listarPorCliente(UUID clienteId)")
                .contains("repositorio.findByCliente_Id(clienteId)");
    }

    @Test
    void una_entidad_sin_fk_no_gana_filtros_que_nadie_puede_usar() {
        ProyectoGenerado p = generar("02-uno-a-muchos.json");

        // Cliente no tiene FK propia: su listado sigue sin parametros.
        assertThat(p.archivos().get(BASE_JAVA + "web/ClienteController.java"))
                .contains("public List<ClienteDto> listar() {")
                .doesNotContain("@RequestParam");
        assertThat(p.archivos().get(BASE_JAVA + "service/ClienteService.java"))
                .doesNotContain("listarPor");
    }

    @Test
    void un_cuerpo_invalido_dice_que_campo_fallo() {
        ProyectoGenerado p = generar("02-uno-a-muchos.json");

        // Sin esto el 400 salia con el JSON por defecto de Spring y un
        // formulario no podia marcar el campo: las anotaciones del DTO ya
        // saben cual es.
        assertThat(p.archivos().get(BASE_JAVA + "web/ManejadorDeErrores.java"))
                .contains("@ExceptionHandler(MethodArgumentNotValidException.class)")
                .contains("e.getBindingResult().getFieldErrors()")
                .contains("\"error\", \"Hay campos invalidos\", \"errores\", errores")
                // Y un uuid mal escrito en la URL o en un filtro conserva la
                // misma forma de respuesta que el resto de la API.
                .contains("@ExceptionHandler(MethodArgumentTypeMismatchException.class)");
    }

    @Test
    void escribir_deja_los_archivos_en_disco(@org.junit.jupiter.api.io.TempDir Path destino)
            throws IOException {
        generar("02-uno-a-muchos.json").escribirEn(destino);

        assertThat(Files.readString(destino.resolve("pom.xml"))).contains("spring-boot-starter-parent");
        assertThat(destino.resolve("src/main/java/com/uagrm/generado/domain/Pedido.java")).exists();
    }

    @Test
    void emite_el_proyecto_listo_para_compilar() throws IOException {
        // Deja el artefacto en target/ (ignorado por git) para poder correrle
        // `mvn package` encima: ese es el criterio de salida del hito.
        Path destino = Path.of("target", "generado-demo");
        generar("06-nombres-acentos.json").escribirEn(destino);

        assertThat(destino.resolve("pom.xml")).exists();
    }

    // ------------------------------------------------------------- REGLA 6

    /** Una clase Libro con los metodos que se le pasen. */
    private ProyectoGenerado conMetodos(UmlMethod... metodos) {
        UmlClass libro = new UmlClass();
        libro.setId(UUID.randomUUID());
        libro.setDiagramId(UUID.randomUUID());
        libro.setName("Libro");
        for (UmlMethod m : metodos) m.setClassId(libro.getId());
        return GeneradorSpringBoot.generar(OPCIONES, List.of(libro), List.of(),
                List.of(metodos), List.of());
    }

    private static UmlMethod metodo(String nombre, String retorno, String paramsJson) {
        UmlMethod m = new UmlMethod();
        m.setId(UUID.randomUUID());
        m.setName(nombre);
        m.setReturnType(retorno);
        m.setParams(paramsJson);
        return m;
    }

    private static String servicioDe(ProyectoGenerado p) {
        return p.archivos().get("src/main/java/com/uagrm/generado/service/LibroService.java");
    }

    @Test
    void un_metodo_del_uml_aterriza_en_el_service_con_su_firma() {
        ProyectoGenerado p = conMetodos(metodo("prestar", "boolean", """
                [{"name": "socio", "type": "uuid"}, {"name": "dias", "type": "int"}]
                """));

        // REGLA 6: el comportamiento vive en el Service, no en la Entity.
        assertThat(servicioDe(p))
                .contains("public Boolean prestar(UUID socio, Integer dias) {")
                .contains("REGLA 6: firma tomada del UML (+ prestar(UUID, Integer): Boolean)");
    }

    @Test
    void el_stub_falla_ruidosamente_en_vez_de_devolver_null() {
        ProyectoGenerado p = conMetodos(metodo("recalcularMulta", "decimal", "[]"));

        // Un stub que devuelve null se despliega sin que nadie lo note; uno que
        // tira excepcion obliga a completarlo.
        assertThat(servicioDe(p))
                .contains("public BigDecimal recalcularMulta() {")
                .contains("throw new UnsupportedOperationException(")
                .contains("sin implementar");
    }

    @Test
    void el_tipo_del_stub_arrastra_su_import() {
        ProyectoGenerado p = conMetodos(metodo("vencimiento", "date", "[]"));

        // Sin el import, la firma no compila y el fallo aparece recien al
        // correr `mvn package`.
        assertThat(servicioDe(p))
                .contains("import java.time.LocalDate;")
                .contains("public LocalDate vencimiento() {");
    }

    @Test
    void la_visibilidad_del_uml_se_respeta() {
        UmlMethod privado = metodo("validarIsbn", "boolean", "[]");
        privado.setVisibility(Visibility.PRIVATE);

        assertThat(servicioDe(conMetodos(privado)))
                .contains("private Boolean validarIsbn() {");
    }

    @Test
    void un_metodo_void_sigue_siendo_void_y_no_se_mapea_a_String() {
        assertThat(servicioDe(conMetodos(metodo("archivar", "void", "[]"))))
                .contains("public void archivar() {");
    }

    @Test
    void un_metodo_que_choca_con_el_crud_se_avisa_y_no_se_emite() {
        ProyectoGenerado p = conMetodos(metodo("listar", "void", "[]"));

        // El Service ya define listar(): emitir el stub daria un metodo
        // duplicado y el proyecto no compilaria. Regla 10: se avisa.
        assertThat(servicioDe(p)).doesNotContain("REGLA 6: firma tomada del UML");
        assertThat(p.advertencias()).anyMatch(a -> a.contains("listar") && a.contains("CRUD"));
        assertThat(p.archivos().get("README.md")).contains("CRUD");
    }

    @Test
    void los_metodos_no_llegan_ni_a_la_entity_ni_al_ddl() {
        ProyectoGenerado p = conMetodos(metodo("prestar", "boolean", "[]"));

        // La base guarda ESTADO; el objeto, estado y comportamiento. Un metodo
        // en el DDL seria un error de categoria.
        assertThat(p.archivos().get("src/main/java/com/uagrm/generado/domain/Libro.java"))
                .doesNotContain("prestar");
        assertThat(p.archivos().get("src/main/resources/db/migration/V1__init.sql"))
                .doesNotContain("prestar");
    }

    @Test
    void un_nombre_de_metodo_reservado_en_java_se_esquiva() {
        // "return" es un nombre de metodo imposible en Java.
        assertThat(servicioDe(conMetodos(metodo("return", "void", "[]"))))
                .contains("public void return_() {");
    }

    // ------------------------------------------------------- DDL EJECUTABLE

    @Test
    void ninguna_tabla_referencia_a_otra_antes_de_que_exista() {
        // Modelo 02: pedido -> cliente, y "cliente" < "pedido" alfabeticamente.
        // El canonico con herencia cruza en los dos sentidos del abecedario.
        for (String modelo : List.of("02-uno-a-muchos.json", "04-herencia.json",
                "05-clase-asociativa.json", "07-reflexiva-doble.json")) {
            String ddl = generar(modelo).archivos().get("src/main/resources/db/migration/V1__init.sql");

            // Antes las FK iban en linea y el orden de las tablas decidia si
            // Flyway arrancaba o cortaba con "relation ... does not exist".
            int ultimaTabla = ddl.lastIndexOf("CREATE TABLE");
            int primeraFk = ddl.indexOf("ALTER TABLE");
            assertThat(ddl.substring(0, primeraFk < 0 ? ddl.length() : primeraFk))
                    .as(modelo).doesNotContain("REFERENCES");
            if (primeraFk >= 0) assertThat(primeraFk).as(modelo).isGreaterThan(ultimaTabla);
        }
    }

    @Test
    void las_claves_foraneas_llevan_indice_salvo_que_ya_lo_cubra_la_pk() {
        String ddl = generar("02-uno-a-muchos.json")
                .archivos().get("src/main/resources/db/migration/V1__init.sql");
        assertThat(ddl).contains("CREATE INDEX idx_pedido_cliente_id ON pedido (cliente_id);");

        // En la tabla puente la PK (estudiante_id, materia_id) ya indexa la
        // primera columna, pero no la segunda.
        String puente = generar("03-muchos-a-muchos.json")
                .archivos().get("src/main/resources/db/migration/V1__init.sql");
        assertThat(puente).doesNotContain("idx_estudiante_materia_estudiante_id")
                .contains("idx_estudiante_materia_materia_id");
    }

    // ------------------------------------------------------ API QUE FUNCIONA

    @Test
    void el_controlador_nunca_recibe_ni_devuelve_una_entidad() {
        String controlador = generar("02-uno-a-muchos.json")
                .archivos().get("src/main/java/com/uagrm/generado/web/PedidoController.java");

        // Devolver la entidad daba 500 en casi toda la API: Jackson la
        // serializaba con la sesion cerrada y las relaciones LAZY explotaban.
        // Se descubrio recien arrancando el proyecto generado y llamandolo.
        // Lo que se afirma es el TIPO que sale, no la firma completa: el
        // listado lleva filtros por FK desde que el frontend los necesito.
        assertThat(controlador)
                .contains("public List<PedidoDto> listar(")
                .contains("public PedidoDto obtener(")
                .contains("ResponseEntity<PedidoDto> crear(")
                .doesNotContain("import com.uagrm.generado.domain.");
    }

    @Test
    void el_servicio_mapea_a_dto_dentro_de_la_transaccion() {
        String servicio = generar("02-uno-a-muchos.json")
                .archivos().get("src/main/java/com/uagrm/generado/service/PedidoService.java");

        assertThat(servicio)
                .contains("@Transactional(readOnly = true)")
                .contains("public List<PedidoDto> listar()")
                .contains("static PedidoDto aDto(Pedido e)")
                // La asociacion sale como id; leer el id de un proxy LAZY no lo inicializa.
                .contains("e.getCliente() == null ? null : e.getCliente().getId()")
                // Flush dentro del metodo: una violacion de FK o UNIQUE sale como
                // 409 y no como 500 al hacer commit.
                .contains("repositorio.saveAndFlush(entidad)");
    }

    @Test
    void los_errores_del_cliente_no_salen_como_500() {
        String manejador = generar("02-uno-a-muchos.json")
                .archivos().get("src/main/java/com/uagrm/generado/web/ManejadorDeErrores.java");

        assertThat(manejador)
                .contains("@RestControllerAdvice")
                .contains("HttpStatus.NOT_FOUND")
                .contains("HttpStatus.CONFLICT");
    }

    @Test
    void la_coleccion_de_valores_viaja_en_el_dto() {
        UmlClass libro = new UmlClass();
        libro.setId(UUID.randomUUID());
        libro.setDiagramId(UUID.randomUUID());
        libro.setName("Libro");
        com.uagrm.colabmodel.modelcore.metamodel.UmlAttribute autor =
                new com.uagrm.colabmodel.modelcore.metamodel.UmlAttribute();
        autor.setId(UUID.randomUUID());
        autor.setClassId(libro.getId());
        autor.setName("autor");
        autor.setDataType("string");
        autor.setMultivalued(true);

        ProyectoGenerado p = GeneradorSpringBoot.generar(OPCIONES, List.of(libro), List.of(autor),
                List.of(), List.of(), com.uagrm.colabmodel.modelcore.mapping.Decisiones.constructor()
                        .con(com.uagrm.colabmodel.modelcore.mapping.Ambiguedad.TipoAmbiguedad.MULTIVALUED,
                                autor.getId(), com.uagrm.colabmodel.modelcore.mapping.Ambiguedad.Lectura.OO)
                        .construir());

        // Antes el @ElementCollection existia en la Entity pero la API no
        // tenia como cargarlo ni leerlo.
        assertThat(p.archivos().get("src/main/java/com/uagrm/generado/web/dto/LibroDto.java"))
                .contains("List<String> autor");
        assertThat(p.archivos().get("src/main/java/com/uagrm/generado/service/LibroService.java"))
                .contains("List.copyOf(e.getAutor())")
                .contains("new ArrayList<>(dto.autor())");
    }

    @Test
    void emite_la_herencia_lista_para_compilar_y_arrancar() throws IOException {
        // Se deja en target/ para compilarlo y ARRANCARLO: compilar solo ya
        // demostro no alcanzar (el DDL desordenado y la API con 500 compilaban).
        generar("04-herencia.json").escribirEn(Path.of("target", "generado-herencia"));
        assertThat(Path.of("target", "generado-herencia", "pom.xml")).exists();
    }

    // ----------------------------------------------------- REGLA 4 (JOINED)

    @Test
    void la_raiz_de_la_jerarquia_declara_la_estrategia_joined() {
        String persona = generar("04-herencia.json").archivos().get(BASE_JAVA + "domain/Persona.java");

        // El modelo 04 marca a Persona como abstracta: sigue teniendo tabla
        // (JOINED), pero en Java no se instancia.
        assertThat(persona)
                .contains("@Inheritance(strategy = InheritanceType.JOINED)")
                .contains("public abstract class Persona {");
    }

    @Test
    void la_hija_extiende_a_la_madre_y_no_redeclara_el_id() {
        String empleado = generar("04-herencia.json").archivos().get(BASE_JAVA + "domain/Empleado.java");

        // Con su propio @Id y @GeneratedValue, la hija era una entidad suelta:
        // insertarla violaba la FK de su id hacia persona.
        assertThat(empleado)
                .contains("@PrimaryKeyJoinColumn(name = \"id\")")
                .contains("public class Empleado extends Persona {")
                .doesNotContain("@Id")
                .doesNotContain("public boolean equals");   // la hereda de la raiz
    }

    @Test
    void el_dto_de_la_hija_lleva_los_campos_heredados() {
        String dto = generar("04-herencia.json").archivos().get(BASE_JAVA + "web/dto/EmpleadoDto.java");

        // Sin nombre y ci no habia forma de crear un Empleado valido por la API.
        assertThat(dto).contains("UUID id").contains("String nombre").contains("String ci")
                .contains("String cargo");
    }

    @Test
    void la_api_de_una_clase_abstracta_no_ofrece_crear_ni_actualizar() {
        ProyectoGenerado p = generar("04-herencia.json");

        // `new Persona()` no compila si Persona es abstracta: se crea por sus hijas.
        assertThat(p.archivos().get(BASE_JAVA + "web/PersonaController.java"))
                .contains("@GetMapping").contains("@DeleteMapping")
                .doesNotContain("@PostMapping").doesNotContain("@PutMapping");
        assertThat(p.archivos().get(BASE_JAVA + "service/PersonaService.java"))
                .doesNotContain("new Persona()");
        assertThat(p.archivos().get(BASE_JAVA + "web/EmpleadoController.java"))
                .contains("@PostMapping");
    }
}
