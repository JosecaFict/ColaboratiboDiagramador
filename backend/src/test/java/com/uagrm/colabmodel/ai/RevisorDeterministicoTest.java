package com.uagrm.colabmodel.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uagrm.colabmodel.modelcore.metamodel.*;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RevisorDeterministicoTest {

    private final UUID diagramId = UUID.randomUUID();
    private final List<UmlClass> clases = new ArrayList<>();
    private final List<UmlAttribute> atributos = new ArrayList<>();
    private final List<UmlRelationship> relaciones = new ArrayList<>();

    /** Atributos "nombre" o "nombre:pk". */
    private UmlClass clase(String nombre, String... campos) {
        UmlClass c = new UmlClass();
        c.setId(UUID.randomUUID());
        c.setName(nombre);
        clases.add(c);
        for (String campo : campos) {
            UmlAttribute a = new UmlAttribute();
            a.setId(UUID.randomUUID());
            a.setClassId(c.getId());
            a.setName(campo.replace(":pk", ""));
            a.setDataType("string");
            a.setPk(campo.endsWith(":pk"));
            atributos.add(a);
        }
        return c;
    }

    private void relacion(UmlClass s, UmlClass t, RelationKind kind) {
        UmlRelationship r = new UmlRelationship();
        r.setId(UUID.randomUUID());
        r.setSourceClassId(s.getId());
        r.setTargetClassId(t.getId());
        r.setKind(kind);
        r.setSourceMultiplicity("1");
        r.setTargetMultiplicity("0..*");
        relaciones.add(r);
    }

    private List<RevisorDeterministico.Hallazgo> revisar(String requisito) {
        var d = new ServicioDeRecomendaciones.Diagrama(clases,
                atributos.stream().collect(Collectors.groupingBy(UmlAttribute::getClassId)), relaciones,
                clases.stream().collect(Collectors.toMap(UmlClass::getId, UmlClass::getName)));
        return RevisorDeterministico.revisar(d, requisito);
    }

    private List<String> textos(String requisito) {
        return revisar(requisito).stream().map(RevisorDeterministico.Hallazgo::texto).toList();
    }

    @Test
    void una_composition_se_senala_solo_si_el_requisito_pide_conservar() {
        UmlClass carrera = clase("Carrera", "id:pk", "nombre");
        UmlClass plan = clase("PlanAcademico", "id:pk", "version");
        relacion(carrera, plan, RelationKind.COMPOSITION);

        assertThat(textos("Gestionar carreras y planes")).noneMatch(t -> t.contains("COMPOSITION"));
        var hallazgo = revisar("Evitar que modificar un plan elimine sus versiones anteriores").stream()
                .filter(h -> h.texto().contains("COMPOSITION")).findFirst().orElseThrow();
        assertThat(hallazgo.texto()).startsWith("La COMPOSITION Carrera - PlanAcademico implica que cada PlanAcademico");
        assertThat(hallazgo.elementos()).containsExactly("Carrera", "PlanAcademico");
        assertThat(textos("Conservar el historial de ventas")).anyMatch(t -> t.contains("COMPOSITION"));
    }

    @Test
    void senala_identificadores_que_no_siguen_la_forma_mayoritaria() {
        UmlClass carrera = clase("Carrera", "idCarrera:pk", "nombre");
        UmlClass plan = clase("Plan", "id:pk", "version");
        UmlClass docente = clase("Docente", "id:pk", "nombre");
        relacion(carrera, plan, RelationKind.ASSOCIATION);
        relacion(plan, docente, RelationKind.ASSOCIATION);

        var hallazgo = revisar(null).stream().filter(h -> h.texto().startsWith("Identificadores inconsistentes"))
                .findFirst().orElseThrow();
        assertThat(hallazgo.texto()).contains("Carrera.idCarrera").contains("2 clases usan id");
        assertThat(hallazgo.gravedad()).isEqualTo("BAJA");
    }

    @Test
    void sin_mayoria_de_id_no_hay_inconsistencia_que_senalar() {
        UmlClass a = clase("Alumno", "idAlumno:pk", "nombre");
        UmlClass b = clase("Curso", "idCurso:pk", "nombre");
        relacion(a, b, RelationKind.ASSOCIATION);
        assertThat(textos(null)).noneMatch(t -> t.startsWith("Identificadores inconsistentes"));
    }

    @Test
    void senala_nombres_que_rompen_el_generador() {
        UmlClass mala = clase("detalle venta", "id:pk", "Precio", "class");
        UmlClass buena = clase("Venta", "id:pk", "fecha");
        relacion(buena, mala, RelationKind.ASSOCIATION);

        var hallazgos = revisar(null);
        assertThat(hallazgos).extracting(RevisorDeterministico.Hallazgo::texto)
                .anyMatch(t -> t.startsWith("El nombre de clase detalle venta no es PascalCase"))
                .anyMatch(t -> t.equals("El atributo detalle venta.Precio no es camelCase ASCII."))
                .anyMatch(t -> t.startsWith("El atributo detalle venta.class es una palabra reservada"));
        assertThat(hallazgos).filteredOn(h -> h.texto().contains("reservada"))
                .extracting(RevisorDeterministico.Hallazgo::gravedad).containsExactly("ALTA");
    }

    @Test
    void una_clase_con_solo_identificador_vale_si_conecta_dos_conceptos() {
        UmlClass asignatura = clase("Asignatura", "id:pk", "nombre");
        UmlClass plan = clase("Plan", "id:pk", "version");
        UmlClass inscripcion = clase("Inscripcion", "id:pk");
        UmlClass vacia = clase("Marca", "id:pk");
        relacion(plan, inscripcion, RelationKind.ASSOCIATION);
        relacion(asignatura, inscripcion, RelationKind.ASSOCIATION);
        relacion(plan, vacia, RelationKind.ASSOCIATION);

        assertThat(textos(null))
                .anyMatch(t -> t.startsWith("Marca solo tiene su identificador"))
                .noneMatch(t -> t.startsWith("Inscripcion solo tiene su identificador"));
    }

    @Test
    void las_recomendaciones_verificadas_van_primero_a_igual_gravedad_y_se_marcan() throws Exception {
        UmlClass carrera = clase("Carrera", "id:pk", "nombre");
        UmlClass plan = clase("PlanAcademico", "id:pk", "version");
        relacion(carrera, plan, RelationKind.COMPOSITION);

        UmlClassRepository classes = mock(UmlClassRepository.class);
        when(classes.findByDiagramIdAndDeletedAtIsNullOrderByNameAsc(diagramId)).thenReturn(clases);
        UmlAttributeRepository attributes = mock(UmlAttributeRepository.class);
        when(attributes.findByClassIdInOrderByOrdinalAsc(any())).thenReturn(atributos);
        UmlRelationshipRepository relationships = mock(UmlRelationshipRepository.class);
        when(relationships.findByDiagramIdAndDeletedAtIsNull(diagramId)).thenReturn(relaciones);
        ProveedorDeIA proveedor = mock(ProveedorDeIA.class);
        when(proveedor.nombre()).thenReturn("falso");
        when(proveedor.generar(any())).thenReturn(new RespuestaIA(List.of(new ComandoPropuestoIA(
                EsquemaRecomendaciones.NOMBRE, new ObjectMapper().readTree("""
                {"resumen":"r","recomendaciones":[{"titulo":"Del modelo","gravedad":"MEDIA","categoria":"OTRO",
                 "regla":"","elementos":["Carrera"],"problema":"p","accion":"Hacer algo"}]}"""))), ""));
        var servicio = new ServicioDeRecomendaciones(proveedor, classes, attributes, relationships, mock(ServicioDeIA.class));

        var r = servicio.recomendar(diagramId, "Conservar las versiones anteriores", null);

        assertThat(r.recomendaciones()).extracting(RecomendacionesIA.Recomendacion::origen)
                .containsExactly(RecomendacionesIA.ORIGEN_VERIFICADO, RecomendacionesIA.ORIGEN_MODELO);
        assertThat(r.recomendaciones().getFirst().accion()).startsWith("Corregir: La COMPOSITION Carrera - PlanAcademico");
        assertThat(r.recomendaciones()).extracting(RecomendacionesIA.Recomendacion::id).containsExactly(1, 2);
    }
}
