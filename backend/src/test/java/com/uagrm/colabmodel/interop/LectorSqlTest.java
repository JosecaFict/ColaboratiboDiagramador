package com.uagrm.colabmodel.interop;

import com.uagrm.colabmodel.codegen.GeneradorSpringBoot;
import com.uagrm.colabmodel.modelcore.mapping.ModeloCanonico;
import com.uagrm.colabmodel.modelcore.metamodel.*;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Criterio de equivalencia (ADR-018): reimportar el DDL que generamos da un
 * modelo cuyo esquema derivado es IDENTICO al del original, salvo perdidas
 * declaradas, que el lector lista en sus advertencias.
 *
 * La comparacion es sobre el DDL y no sobre el UML a proposito: el UML tiene
 * cosas que ningun DDL guarda (nombres con acentos, clases abstractas,
 * metodos), y compararlo seria exigirle al lector que adivine.
 *
 * El camino es el real: generador -> LectorSql -> ServicioDeImportacion (la
 * propuesta que ve el usuario) -> aplicar sin cambios -> generador.
 */
class LectorSqlTest {

    private static final String DDL = "src/main/resources/db/migration/V1__init.sql";
    private final UUID diagramId = UUID.randomUUID();

    private record IdaYVuelta(String original, String reimportado, ModeloLeido leido,
                              ModeloCanonico.Cargado modelo) { }

    /** Package-private: `EscritorXmiTest` reutiliza este mismo helper para su propio criterio de equivalencia. */
    static String ddlDe(ModeloCanonico.Cargado m) {
        return GeneradorSpringBoot.generar(GeneradorSpringBoot.Opciones.por("ida-y-vuelta"), m.clases(),
                m.atributos(), m.metodos(), m.relaciones()).archivos().get(DDL);
    }

    private IdaYVuelta idaYVuelta(ModeloCanonico.Cargado original) {
        String ddl = ddlDe(original);
        assertThat(ddl).as("sin DDL la comparacion seria null == null").contains("CREATE TABLE");
        byte[] bytes = ddl.getBytes(StandardCharsets.UTF_8);
        UmlClassRepository classes = mock(UmlClassRepository.class);
        when(classes.findByDiagramIdAndDeletedAtIsNullOrderByNameAsc(diagramId)).thenReturn(List.of());
        ServicioDeImportacion servicio = new ServicioDeImportacion(List.of(new LectorSql()), classes,
                mock(UmlAttributeRepository.class), mock(UmlMethodRepository.class));

        PropuestaDeImportacion propuesta = servicio.proponer(diagramId, "V1__init.sql", bytes);
        ModeloCanonico.Cargado reimportado = RevisionPorDefecto.aplicar(propuesta);
        return new IdaYVuelta(ddl, ddlDe(reimportado), new LectorSql().leer(bytes), reimportado);
    }

    private IdaYVuelta canonico(String archivo) {
        return idaYVuelta(ModeloCanonico.cargar(archivo));
    }

    private static List<String> nombres(ModeloLeido m) {
        return m.clases().stream().map(ModeloLeido.ClaseLeida::nombre).toList();
    }

    // ============================================ LOS 7 CANONICOS, IDA Y VUELTA

    @Test
    void canonico_01_uno_a_uno() {
        IdaYVuelta v = canonico("01-uno-a-uno.json");

        assertThat(v.reimportado()).isEqualTo(v.original());
        ModeloLeido.RelacionLeida r = v.leido().relaciones().get(0);
        assertThat(r.multiplicidadDestino()).as("FK UNIQUE: 1 a 1").isEqualTo("1");
        assertThat(v.leido().clases()).filteredOn(c -> c.nombre().equals("perfil")).singleElement()
                .satisfies(c -> assertThat(c.atributos()).extracting(ModeloLeido.AtributoLeido::nombre)
                        .containsExactly("bio"));
    }

    @Test
    void canonico_02_uno_a_muchos() {
        IdaYVuelta v = canonico("02-uno-a-muchos.json");

        assertThat(v.reimportado()).isEqualTo(v.original());
        assertThat(v.leido().relaciones()).singleElement().satisfies(r -> {
            assertThat(r.claveOrigen()).isEqualTo("cliente");
            assertThat(r.claveDestino()).isEqualTo("pedido");
            assertThat(r.multiplicidadOrigen()).as("cliente_id NOT NULL").isEqualTo("1");
            assertThat(r.multiplicidadDestino()).isEqualTo("0..*");
        });
    }

    @Test
    void canonico_03_la_tabla_puente_vuelve_como_relacion_muchos_a_muchos() {
        IdaYVuelta v = canonico("03-muchos-a-muchos.json");

        assertThat(v.reimportado()).isEqualTo(v.original());
        assertThat(nombres(v.leido())).containsExactly("estudiante", "materia");
        assertThat(v.leido().relaciones()).singleElement().satisfies(r -> {
            assertThat(r.multiplicidadOrigen()).isEqualTo("0..*");
            assertThat(r.multiplicidadDestino()).isEqualTo("0..*");
        });
        assertThat(v.leido().advertencias()).anyMatch(a -> a.contains("muchos a muchos"));
    }

    @Test
    void canonico_04_la_herencia_joined_se_redetecta_y_se_declara_lo_que_no_se_sabe() {
        IdaYVuelta v = canonico("04-herencia.json");

        assertThat(v.reimportado()).isEqualTo(v.original());
        assertThat(v.leido().relaciones()).hasSize(2)
                .allSatisfy(r -> {
                    assertThat(r.tipo()).isEqualTo(RelationKind.GENERALIZATION);
                    assertThat(r.claveDestino()).isEqualTo("persona");
                });
        // Perdidas declaradas: la ambiguedad con 1:1 y que Persona era abstracta.
        assertThat(v.leido().advertencias()).anyMatch(a -> a.contains("1:1 con clave compartida"))
                .anyMatch(a -> a.contains("abstracta"));
    }

    @Test
    void canonico_05_clase_asociativa() {
        IdaYVuelta v = canonico("05-clase-asociativa.json");

        assertThat(v.reimportado()).isEqualTo(v.original());
        assertThat(v.leido().relaciones()).hasSize(2).allSatisfy(r -> assertThat(r.claveDestino()).isEqualTo("inscripcion"));
    }

    @Test
    void canonico_06_nombres_hostiles_vuelven_como_identificadores() {
        IdaYVuelta v = canonico("06-nombres-acentos.json");

        assertThat(v.reimportado()).isEqualTo(v.original());
        // Perdida declarada en ADR-018: el DDL guarda "anio_academico", no "Año Académico".
        assertThat(nombres(v.leido())).containsExactly("anio_academico", "ninio", "pez");
    }

    @Test
    void canonico_07_reflexiva_y_doble_recupera_los_roles_desde_los_nombres_de_columna() {
        IdaYVuelta v = canonico("07-reflexiva-doble.json");

        assertThat(v.reimportado()).isEqualTo(v.original());
        assertThat(v.leido().relaciones()).extracting(ModeloLeido.RelacionLeida::rolOrigen)
                .containsExactly("jefe", "origen", "destino");
    }

    // ============================================= PERDIDAS DECLARADAS (b)

    @Test
    void single_table_y_embebido_no_se_adivinan_pero_se_declaran_con_pista() {
        UUID d = UUID.randomUUID();
        UmlClass vehiculo = clase(d, "Vehiculo", null);
        vehiculo.setInheritanceStrategy(InheritanceStrategy.SINGLE_TABLE);
        UmlClass auto = clase(d, "Auto", null);
        UmlClass moto = clase(d, "Moto", null);
        UmlClass direccion = clase(d, "Direccion", "embeddable");
        UmlClass cliente = clase(d, "Cliente", null);
        List<UmlAttribute> atributos = List.of(
                atributo(vehiculo, "marca", "string"), atributo(auto, "puertas", "int"),
                atributo(moto, "cilindrada", "int"), atributo(direccion, "calle", "string"),
                atributo(direccion, "numero", "string"), atributo(cliente, "domicilio", "Direccion"));
        List<UmlRelationship> relaciones = List.of(herencia(d, auto, vehiculo), herencia(d, moto, vehiculo));

        IdaYVuelta v = idaYVuelta(new ModeloCanonico.Cargado(
                List.of(vehiculo, auto, moto, direccion, cliente), atributos, relaciones, List.of()));

        // No se inventa una jerarquia ni un valor embebido...
        assertThat(v.leido().relaciones()).noneMatch(r -> r.tipo() == RelationKind.GENERALIZATION);
        assertThat(nombres(v.leido())).containsExactly("vehiculo", "cliente");
        assertThat(v.reimportado()).isNotEqualTo(v.original());
        // ...pero se dice que puede haberlos, y donde.
        assertThat(v.leido().advertencias())
                .anyMatch(a -> a.contains("vehiculo.tipo") && a.contains("SINGLE_TABLE"))
                .anyMatch(a -> a.contains("domicilio_calle") && a.contains("embeddable"));
    }

    // ================================================== DDL AJENO (tolerancia)

    private static final String AJENO = """
            -- Biblioteca, escrito a mano (no por el generador)
            CREATE TABLE public.autor (
              id_autor SERIAL PRIMARY KEY,
              nombre VARCHAR(80) NOT NULL
            );
            /* libros */
            CREATE TABLE IF NOT EXISTS libro (
              isbn character varying(13) PRIMARY KEY,
              titulo text NOT NULL,
              estado varchar(10) NOT NULL DEFAULT 'libre' CHECK (estado IN ('libre', 'prestado')),
              autor_id integer REFERENCES autor(id_autor) ON UPDATE CASCADE ON DELETE SET NULL
            );
            CREATE TABLE ejemplar (
              isbn varchar(13) NOT NULL,
              numero integer NOT NULL,
              CONSTRAINT pk_ejemplar PRIMARY KEY (isbn, numero),
              CONSTRAINT fk_ejemplar_libro FOREIGN KEY (isbn) REFERENCES libro (isbn) ON DELETE CASCADE
            );
            CREATE INDEX idx_libro_titulo ON libro (titulo);
            INSERT INTO autor (nombre) VALUES ('Borges; Jorge');
            """;

    private final ModeloLeido ajeno = new LectorSql().leer(AJENO.getBytes(StandardCharsets.UTF_8));

    @Test
    void lee_ddl_ajeno_con_references_en_linea_esquemas_y_comentarios() {
        assertThat(nombres(ajeno)).containsExactly("autor", "libro", "ejemplar");
        assertThat(ajeno.relaciones()).filteredOn(r -> r.claveDestino().equals("libro")).singleElement()
                .satisfies(r -> {
                    assertThat(r.claveOrigen()).isEqualTo("autor");
                    assertThat(r.tipo()).as("ON DELETE SET NULL").isEqualTo(RelationKind.AGGREGATION);
                    assertThat(r.multiplicidadOrigen()).as("autor_id admite NULL").isEqualTo("0..1");
                });
        assertThat(ajeno.clases().get(1).atributos()).extracting(ModeloLeido.AtributoLeido::nombre)
                .as("la FK no queda como atributo").containsExactly("isbn", "titulo", "estado");
    }

    @Test
    void una_pk_propia_que_no_es_el_id_uuid_queda_como_clave_natural() {
        assertThat(ajeno.clases().get(0).atributos().get(0)).satisfies(a -> {
            assertThat(a.nombre()).isEqualTo("id_autor");
            assertThat(a.pk()).isTrue();
            assertThat(a.tipoLeido()).isEqualTo("serial");
        });
        assertThat(ajeno.clases().get(1).atributos().get(0).tipoLeido()).isEqualTo("varchar(13)");
    }

    @Test
    void pk_compuesta_con_parte_propia_se_lee_como_entidad_debil_y_se_avisa() {
        ModeloLeido.ClaseLeida ejemplar = ajeno.clases().get(2);
        assertThat(ejemplar.atributos()).singleElement().satisfies(a -> {
            assertThat(a.nombre()).isEqualTo("numero");
            assertThat(a.claveParcial()).isTrue();
            assertThat(a.pk()).isFalse();
        });
        assertThat(ajeno.relaciones()).filteredOn(r -> r.claveDestino().equals("ejemplar")).singleElement()
                .satisfies(r -> {
                    assertThat(r.identificadora()).isTrue();
                    assertThat(r.tipo()).isEqualTo(RelationKind.COMPOSITION);
                });
        assertThat(ajeno.advertencias()).anyMatch(a -> a.contains("entidad debil"));
    }

    @Test
    void lo_que_no_es_create_ni_alter_table_se_ignora_con_advertencia_y_un_punto_y_coma_en_una_cadena_no_corta() {
        assertThat(ajeno.advertencias()).anyMatch(a -> a.startsWith("2 sentencias se ignoraron"));
        assertThat(ajeno.advertencias()).anyMatch(a -> a.contains("libro.estado") && a.contains("enumeracion"));
    }

    @Test
    void una_sentencia_rota_no_tumba_el_archivo() {
        ModeloLeido m = new LectorSql().leer("""
                CREATE TABLE ;
                ALTER TABLE fantasma ADD CONSTRAINT x FOREIGN KEY (a) REFERENCES b(id);
                CREATE TABLE sana (id uuid NOT NULL, PRIMARY KEY (id));
                """.getBytes(StandardCharsets.UTF_8));

        assertThat(nombres(m)).containsExactly("sana");
        assertThat(m.advertencias()).anyMatch(a -> a.startsWith("No se pudo interpretar un CREATE TABLE"))
                .anyMatch(a -> a.contains("fantasma"));
    }

    @Test
    void sin_ningun_create_table_el_archivo_es_invalido() {
        assertThat(catchThrowableOfType(() -> new LectorSql().leer("SELECT 1;".getBytes(StandardCharsets.UTF_8)),
                ArchivoInvalidoException.class)).hasMessageContaining("CREATE TABLE");
    }

    @Test
    void acepta_sql_por_extension_o_por_contenido_pero_no_un_json() {
        LectorSql lector = new LectorSql();
        assertThat(lector.acepta("modelo.sql", new byte[0])).isTrue();
        assertThat(lector.acepta("sin-extension", "create table x (id int);".getBytes())).isTrue();
        assertThat(lector.acepta("modelo.json", "{\"create table\": 1}".getBytes())).isFalse();
    }

    // ------------------------------------------------------------- apoyo

    private static UmlClass clase(UUID diagrama, String nombre, String estereotipo) {
        UmlClass c = new UmlClass();
        c.setId(UUID.randomUUID());
        c.setDiagramId(diagrama);
        c.setName(nombre);
        c.setStereotype(estereotipo);
        c.setErKind(ErKind.STRONG);
        return c;
    }

    private static int ordinal;

    private static UmlAttribute atributo(UmlClass duenio, String nombre, String tipo) {
        UmlAttribute a = new UmlAttribute();
        a.setId(UUID.randomUUID());
        a.setClassId(duenio.getId());
        a.setName(nombre);
        a.setDataType(tipo);
        a.setOrdinal(ordinal++);
        return a;
    }

    private static UmlRelationship herencia(UUID diagrama, UmlClass hija, UmlClass madre) {
        UmlRelationship r = new UmlRelationship();
        r.setId(UUID.randomUUID());
        r.setDiagramId(diagrama);
        r.setSourceClassId(hija.getId());
        r.setTargetClassId(madre.getId());
        r.setKind(RelationKind.GENERALIZATION);
        return r;
    }
}
