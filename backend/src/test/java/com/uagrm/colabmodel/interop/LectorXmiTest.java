package com.uagrm.colabmodel.interop;

import com.uagrm.colabmodel.codegen.GeneradorSpringBoot;
import com.uagrm.colabmodel.interop.HerramientasXmi.EstereotiposEa;
import com.uagrm.colabmodel.interop.HerramientasXmi.Herramienta;
import com.uagrm.colabmodel.modelcore.mapping.ModeloCanonico;
import com.uagrm.colabmodel.modelcore.metamodel.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Los 7 canonicos, exportados a XMI como lo hace cada herramienta externa,
 * vuelven a importarse (ADR-019).
 *
 * Mismo criterio que el lector SQL (ADR-018): el esquema derivado del modelo
 * reimportado es el MISMO DDL que el del original. El camino es el real:
 * XMI -> LectorXmi -> ServicioDeImportacion (la propuesta que ve el usuario)
 * -> aplicar sin cambios -> generador.
 *
 * La tolerancia a archivos rotos y a construcciones ajenas esta en
 * LectorXmiToleranciaTest.
 */
class LectorXmiTest {

    static final List<String> CANONICOS = List.of("01-uno-a-uno.json", "02-uno-a-muchos.json",
            "03-muchos-a-muchos.json", "04-herencia.json", "05-clase-asociativa.json", "06-nombres-acentos.json",
            "07-reflexiva-doble.json");
    private static final String DDL = "src/main/resources/db/migration/V1__init.sql";
    private final UUID diagramId = UUID.randomUUID();

    static String ddlDe(ModeloCanonico.Cargado m) {
        return GeneradorSpringBoot.generar(GeneradorSpringBoot.Opciones.por("ida-y-vuelta"), m.clases(),
                m.atributos(), m.metodos(), m.relaciones()).archivos().get(DDL);
    }

    private ModeloCanonico.Cargado importar(byte[] xmi) {
        UmlClassRepository classes = mock(UmlClassRepository.class);
        when(classes.findByDiagramIdAndDeletedAtIsNullOrderByNameAsc(diagramId)).thenReturn(List.of());
        ServicioDeImportacion servicio = new ServicioDeImportacion(List.of(new LectorModeloJson(), new LectorSql(),
                new LectorXmi()), classes, mock(UmlAttributeRepository.class), mock(UmlMethodRepository.class));
        return RevisionPorDefecto.aplicar(servicio.proponer(diagramId, "export.xmi", xmi));
    }

    static Stream<Arguments> umlPorHerramienta() {
        return Stream.of(Herramienta.values())
                .flatMap(h -> CANONICOS.stream().map(c -> Arguments.of(h, c)));
    }

    static Stream<Arguments> modeloDeDatos() {
        return Stream.of(EstereotiposEa.values())
                .flatMap(e -> CANONICOS.stream().map(c -> Arguments.of(e, c)));
    }

    // ================================================== DIAGRAMA DE CLASES

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("umlPorHerramienta")
    void un_diagrama_de_clases_exportado_por_otra_herramienta_vuelve_con_el_mismo_esquema(Herramienta h, String archivo) {
        ModeloCanonico.Cargado original = ModeloCanonico.cargar(archivo);
        byte[] xmi = HerramientasXmi.exportar(h, original);

        ModeloLeido leido = new LectorXmi().leer(xmi);
        assertThat(leido.clases()).as("todas las clases del canonico").hasSameSizeAs(original.clases());
        assertThat(leido.advertencias()).as("nada se omitio").noneMatch(a -> a.contains("se omitio"));
        assertThat(ddlDe(importar(xmi))).isEqualTo(ddlDe(original));
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("modeloDeDatos")
    void un_modelo_de_datos_de_ea_vuelve_con_el_mismo_esquema(EstereotiposEa donde, String archivo) {
        ModeloCanonico.Cargado original = ModeloCanonico.cargar(archivo);
        byte[] xmi = HerramientasXmi.modeloDeDatosEa(original, donde);

        assertThat(new LectorXmi().leer(xmi).advertencias()).noneMatch(a -> a.contains("se omitio"));
        assertThat(ddlDe(importar(xmi))).isEqualTo(ddlDe(original));
    }

    // ====================================================== TODO Y PARTE

    /**
     * Ningun canonico tiene composicion ni agregacion, y es justo donde las
     * herramientas no se ponen de acuerdo: UML 1 marca el rombo en el extremo
     * del todo, UML 2 en el extremo tipado por la parte, y EA dibuja de la
     * parte al todo. En el modelo de datos, la diferencia es ON DELETE.
     */
    static ModeloCanonico.Cargado todoParte() {
        UUID d = UUID.randomUUID();
        UmlClass pedido = clase(d, "Pedido");
        UmlClass linea = clase(d, "LineaPedido");
        UmlClass depto = clase(d, "Departamento");
        UmlClass empleado = clase(d, "Empleado");
        return new ModeloCanonico.Cargado(List.of(pedido, linea, depto, empleado),
                List.of(atributo(pedido, "numero", "string", 0), atributo(linea, "cantidad", "int", 0),
                        atributo(depto, "nombre", "string", 0), atributo(empleado, "legajo", "string", 0)),
                List.of(relacion(d, pedido, linea, RelationKind.COMPOSITION, "1", "1..*", "pedido", "lineas"),
                        relacion(d, depto, empleado, RelationKind.AGGREGATION, "0..1", "0..*", "departamento", "empleados")),
                List.of());
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(Herramienta.class)
    void composicion_y_agregacion_conservan_quien_es_el_todo(Herramienta h) {
        ModeloCanonico.Cargado original = todoParte();
        byte[] xmi = HerramientasXmi.exportar(h, original);

        assertThat(new LectorXmi().leer(xmi).relaciones()).extracting(ModeloLeido.RelacionLeida::tipo)
                .containsExactlyInAnyOrder(RelationKind.COMPOSITION, RelationKind.AGGREGATION);
        assertThat(ddlDe(importar(xmi))).isEqualTo(ddlDe(original)).contains("ON DELETE CASCADE").contains("ON DELETE SET NULL");
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(EstereotiposEa.class)
    void en_el_modelo_de_datos_de_ea_el_on_delete_decide_composicion_o_agregacion(EstereotiposEa donde) {
        ModeloCanonico.Cargado original = todoParte();
        byte[] xmi = HerramientasXmi.modeloDeDatosEa(original, donde);

        assertThat(new LectorXmi().leer(xmi).relaciones()).extracting(ModeloLeido.RelacionLeida::tipo)
                .containsExactlyInAnyOrder(RelationKind.COMPOSITION, RelationKind.AGGREGATION);
        assertThat(ddlDe(importar(xmi))).isEqualTo(ddlDe(original));
    }

    private static UmlClass clase(UUID diagrama, String nombre) {
        UmlClass c = new UmlClass();
        c.setId(UUID.randomUUID());
        c.setDiagramId(diagrama);
        c.setName(nombre);
        c.setErKind(ErKind.STRONG);
        return c;
    }

    private static UmlAttribute atributo(UmlClass duenio, String nombre, String tipo, int ordinal) {
        UmlAttribute a = new UmlAttribute();
        a.setId(UUID.randomUUID());
        a.setClassId(duenio.getId());
        a.setName(nombre);
        a.setDataType(tipo);
        a.setRequired(true);
        a.setOrdinal(ordinal);
        return a;
    }

    private static UmlRelationship relacion(UUID d, UmlClass origen, UmlClass destino, RelationKind tipo,
                                            String multOrigen, String multDestino, String rolOrigen, String rolDestino) {
        UmlRelationship r = new UmlRelationship();
        r.setId(UUID.randomUUID());
        r.setDiagramId(d);
        r.setSourceClassId(origen.getId());
        r.setTargetClassId(destino.getId());
        r.setKind(tipo);
        r.setSourceMultiplicity(multOrigen);
        r.setTargetMultiplicity(multDestino);
        r.setSourceRole(rolOrigen);
        r.setTargetRole(rolDestino);
        return r;
    }

    // ============================================== LO QUE DICE CADA DIALECTO

    @Test
    void enterprise_architect_nombres_con_acentos_en_windows_1252_y_roles_de_los_extremos() {
        ModeloLeido m = new LectorXmi().leer(HerramientasXmi.exportar(Herramienta.ENTERPRISE_ARCHITECT,
                ModeloCanonico.cargar("06-nombres-acentos.json")));

        assertThat(m.clases()).extracting(ModeloLeido.ClaseLeida::nombre).containsExactly("Año Académico", "Niño", "Pez");
        assertThat(m.clases().get(0).atributos().get(0).nombre()).isEqualTo("descripción");
        assertThat(m.advertencias().get(0)).contains("3 clases UML").contains("Enterprise Architect");
    }

    @Test
    void papyrus_la_multiplicidad_omitida_es_la_de_uml_y_el_extremo_navegable_dice_el_sentido() {
        ModeloLeido m = new LectorXmi().leer(HerramientasXmi.exportar(Herramienta.PAPYRUS,
                ModeloCanonico.cargar("01-uno-a-uno.json")));

        // email: isID, [1] omitida -> obligatorio; unique viene de un estereotipo de perfil.
        assertThat(m.clases().get(0).atributos().get(0)).satisfies(a -> {
            assertThat(a.pk()).isTrue();
            assertThat(a.requerido()).isTrue();
            assertThat(a.unico()).isTrue();
            assertThat(a.tipoLeido()).isEqualTo("String");
        });
        // bio: [0..1] con la cota inferior sin `value`.
        assertThat(m.clases().get(1).atributos().get(0).requerido()).isFalse();
        // memberEnd trae primero el extremo destino; lo que manda es cual es navegable.
        assertThat(m.relaciones()).singleElement().satisfies(r -> {
            assertThat(m.clases()).filteredOn(c -> c.clave().equals(r.claveOrigen()))
                    .extracting(ModeloLeido.ClaseLeida::nombre).containsExactly("Usuario");
            assertThat(r.rolDestino()).isEqualTo("perfil");
        });
        assertThat(m.advertencias()).noneMatch(a -> a.contains("no dice en el XMI cual es el origen"));
    }

    @Test
    void argouml_estereotipos_y_tagged_values_por_referencia() {
        ModeloLeido m = new LectorXmi().leer(HerramientasXmi.exportar(Herramienta.ARGOUML,
                ModeloCanonico.cargar("04-herencia.json")));

        assertThat(m.clases()).extracting(ModeloLeido.ClaseLeida::nombre).containsExactly("Persona", "Empleado", "Cliente");
        assertThat(m.clases().get(0).abstracta()).isTrue();
        assertThat(m.clases().get(0).atributos()).filteredOn(a -> a.nombre().equals("ci")).singleElement()
                .satisfies(a -> assertThat(a.unico()).as("TaggedValue -> TagDefinition \"unique\"").isTrue());
        assertThat(m.relaciones()).hasSize(2).allSatisfy(r -> assertThat(r.tipo()).isEqualTo(RelationKind.GENERALIZATION));
        assertThat(m.advertencias().get(0)).contains("ArgoUML").contains("XMI 1.2");
    }

    @Test
    void modelo_de_datos_de_ea_la_herencia_y_la_tabla_puente_se_reconstruyen() {
        ModeloLeido herencia = new LectorXmi().leer(HerramientasXmi.modeloDeDatosEa(
                ModeloCanonico.cargar("04-herencia.json"), EstereotiposEa.EXTENSION));
        assertThat(herencia.relaciones()).hasSize(2)
                .allSatisfy(r -> assertThat(r.tipo()).isEqualTo(RelationKind.GENERALIZATION));
        assertThat(herencia.advertencias().get(0)).contains("3 tablas de un modelo de datos");

        ModeloLeido puente = new LectorXmi().leer(HerramientasXmi.modeloDeDatosEa(
                ModeloCanonico.cargar("03-muchos-a-muchos.json"), EstereotiposEa.PERFIL));
        assertThat(puente.clases()).extracting(ModeloLeido.ClaseLeida::nombre).containsExactly("estudiante", "materia");
        assertThat(puente.advertencias()).anyMatch(a -> a.contains("muchos a muchos"));
    }
}
