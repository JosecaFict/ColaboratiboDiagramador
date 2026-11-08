package com.uagrm.colabmodel.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uagrm.colabmodel.modelcore.metamodel.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class VerificacionDeReglasTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final UUID diagramId = UUID.randomUUID();
    private final List<UmlClass> clases = new ArrayList<>();
    private final List<UmlAttribute> atributos = new ArrayList<>();
    private final List<UmlRelationship> relaciones = new ArrayList<>();

    private UmlClass clase(String nombre, String... campos) {
        UmlClass c = new UmlClass();
        c.setId(UUID.randomUUID());
        c.setName(nombre);
        clases.add(c);
        for (String campo : campos) {
            UmlAttribute a = new UmlAttribute();
            a.setId(UUID.randomUUID());
            a.setClassId(c.getId());
            a.setName(campo);
            a.setDataType("string");
            a.setPk(campo.equals("id"));
            atributos.add(a);
        }
        return c;
    }

    private void relacion(UmlClass s, UmlClass t) {
        UmlRelationship r = new UmlRelationship();
        r.setId(UUID.randomUUID());
        r.setSourceClassId(s.getId());
        r.setTargetClassId(t.getId());
        r.setKind(RelationKind.ASSOCIATION);
        r.setSourceMultiplicity("0..*");
        r.setTargetMultiplicity("1");
        relaciones.add(r);
    }

    private ServicioDeRecomendaciones.Diagrama diagrama() {
        return new ServicioDeRecomendaciones.Diagrama(clases,
                atributos.stream().collect(Collectors.groupingBy(UmlAttribute::getClassId)), relaciones,
                clases.stream().collect(Collectors.toMap(UmlClass::getId, UmlClass::getName)));
    }

    private void academico() {
        UmlClass asignatura = clase("Asignatura", "id", "creditos");
        UmlClass detalle = clase("DetallePlanAsignatura", "id", "nivel");
        relacion(detalle, asignatura);
    }

    private static final ReglasDelRequisito.Regla REGLA = new ReglasDelRequisito.Regla(3,
            "conservar creditos dentro del plan correspondiente", ReglasDelRequisito.Seccion.NEGOCIO);

    @Test
    void extrae_reglas_de_negocio_y_verificacion_pero_no_alcance_modelado_ni_salida() throws Exception {
        String requisito = new String(getClass().getResourceAsStream("/evaluacion/academico.txt").readAllBytes(),
                StandardCharsets.UTF_8);
        var reglas = ReglasDelRequisito.extraer(requisito);

        assertThat(reglas).hasSize(15);
        assertThat(reglas).filteredOn(r -> r.seccion() == ReglasDelRequisito.Seccion.NEGOCIO).hasSize(8);
        assertThat(reglas.getFirst().texto()).isEqualTo("que una carrera tenga varias versiones históricas de su plan");
        assertThat(reglas).extracting(ReglasDelRequisito.Regla::texto)
                .noneMatch(t -> t.startsWith("carreras universitarias"))
                .noneMatch(t -> t.startsWith("Usa PascalCase"));
        assertThat(reglas).extracting(ReglasDelRequisito.Regla::numero).startsWith(1, 2, 3);
    }

    @Test
    void un_pedido_sin_titulos_ni_vinetas_no_tiene_reglas() {
        assertThat(ReglasDelRequisito.extraer("Disena una biblioteca con prestamos")).isEmpty();
        assertThat(ReglasDelRequisito.extraer(null)).isEmpty();
    }

    @Test
    void una_regla_incumplida_con_evidencia_real_se_vuelve_recomendacion_alta() throws Exception {
        academico();
        var salida = mapper.readTree("""
                {"evidencia":["asignatura.CREDITOS","DetallePlanAsignatura - Asignatura"],
                 "explicacion":"Los creditos estan en el catalogo","cumple":false,
                 "accion":"Mover creditos (int) de Asignatura a DetallePlanAsignatura"}
                """);
        var e = ServicioDeRecomendaciones.evaluar(REGLA, salida, diagrama());

        assertThat(e.verificacion().estado()).isEqualTo("NO_CUMPLE");
        assertThat(e.verificacion().evidencia()).containsExactly("Asignatura.creditos", "DetallePlanAsignatura-Asignatura");
        assertThat(e.recomendacion().gravedad()).isEqualTo("ALTA");
        assertThat(e.recomendacion().elementos()).containsExactly("Asignatura.creditos", "DetallePlanAsignatura", "Asignatura");
        assertThat(e.recomendacion().regla()).isEqualTo(REGLA.texto());
    }

    @Test
    void citar_solo_atributos_o_relaciones_inexistentes_deja_la_regla_no_verificable() throws Exception {
        academico();
        var salida = mapper.readTree("""
                {"evidencia":["DetallePlanAsignatura.creditos","Asignatura-Docente"],
                 "explicacion":"x","cumple":false,"accion":"Eliminar creditos de DetallePlanAsignatura"}
                """);
        var e = ServicioDeRecomendaciones.evaluar(REGLA, salida, diagrama());

        assertThat(e.verificacion().estado()).isEqualTo("NO_VERIFICABLE");
        assertThat(e.verificacion().explicacion()).contains("DetallePlanAsignatura.creditos");
        assertThat(e.recomendacion()).isNull();
    }

    @Test
    void acepta_las_formas_de_citar_que_usa_el_contexto_del_modelo() {
        academico();
        var d = diagrama();
        assertThat(ServicioDeRecomendaciones.resolverElemento("Clase.DetallePlanAsignatura.nivel", d))
                .isEqualTo("DetallePlanAsignatura.nivel");
        assertThat(ServicioDeRecomendaciones.resolverElemento("Clase Asignatura", d)).isEqualTo("Asignatura");
        assertThat(ServicioDeRecomendaciones.resolverElemento(
                "DetallePlanAsignatura [0..*] --ASSOCIATION--> Asignatura [1]", d))
                .isEqualTo("DetallePlanAsignatura-Asignatura");
        assertThat(ServicioDeRecomendaciones.resolverElemento("Asignatura -> DetallePlanAsignatura", d))
                .isEqualTo("Asignatura-DetallePlanAsignatura");
        assertThat(ServicioDeRecomendaciones.resolverElemento("A --X--> B --Y--> C", d)).isNull();
    }

    @Test
    void una_regla_cumplida_no_genera_recomendacion_aunque_traiga_accion() throws Exception {
        academico();
        var salida = mapper.readTree("""
                {"evidencia":["DetallePlanAsignatura.nivel"],"explicacion":"ok","cumple":true,"accion":"Nada"}
                """);
        var e = ServicioDeRecomendaciones.evaluar(REGLA, salida, diagrama());
        assertThat(e.verificacion().estado()).isEqualTo("CUMPLE");
        assertThat(e.recomendacion()).isNull();
    }

    @Test
    void con_reglas_hace_una_pregunta_por_regla_y_la_regla_va_al_final_del_pedido() throws Exception {
        academico();
        UmlClassRepository classes = mock(UmlClassRepository.class);
        when(classes.findByDiagramIdAndDeletedAtIsNullOrderByNameAsc(diagramId)).thenReturn(clases);
        UmlAttributeRepository attributes = mock(UmlAttributeRepository.class);
        when(attributes.findByClassIdInOrderByOrdinalAsc(any())).thenReturn(atributos);
        UmlRelationshipRepository relationships = mock(UmlRelationshipRepository.class);
        when(relationships.findByDiagramIdAndDeletedAtIsNull(diagramId)).thenReturn(relaciones);
        ProveedorDeIA proveedor = mock(ProveedorDeIA.class);
        when(proveedor.nombre()).thenReturn("falso");
        var noCumple = new RespuestaIA(List.of(new ComandoPropuestoIA(EsquemaVerificacionRegla.NOMBRE, mapper.readTree("""
                {"evidencia":["Asignatura.creditos"],"explicacion":"En catalogo","cumple":false,
                 "accion":"Mover creditos a DetallePlanAsignatura"}"""))), "");
        var cumple = new RespuestaIA(List.of(new ComandoPropuestoIA(EsquemaVerificacionRegla.NOMBRE, mapper.readTree("""
                {"evidencia":["DetallePlanAsignatura"],"explicacion":"ok","cumple":true,"accion":""}"""))), "");
        when(proveedor.generar(any())).thenReturn(noCumple).thenReturn(cumple).thenThrow(new ProveedorIAException("caido"));
        var servicio = new ServicioDeRecomendaciones(proveedor, classes, attributes, relationships, mock(ServicioDeIA.class));

        String requisito = """
                REGLAS DE NEGOCIO
                - conservar creditos dentro del plan;
                AUTOVERIFICACION
                - que ninguna clase quede desconectada;
                - que todas las relaciones tengan multiplicidad.
                """;
        var r = servicio.recomendar(diagramId, requisito, null);

        var captor = ArgumentCaptor.forClass(SolicitudIA.class);
        verify(proveedor, times(3)).generar(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(s -> {
            assertThat(s.modo()).isEqualTo(ModoIA.VERIFICAR);
            assertThat(s.herramientas()).extracting(HerramientaIA::nombre).containsExactly(EsquemaVerificacionRegla.NOMBRE);
            assertThat(s.mensajeParaElModelo()).contains("Asignatura (id: string [pk], creditos: string)");
        });
        assertThat(captor.getAllValues().getFirst().mensajeParaElModelo())
                .endsWith("REGLA A VERIFICAR:\nconservar creditos dentro del plan");
        assertThat(r.verificaciones()).extracting(RecomendacionesIA.Verificacion::estado)
                .containsExactly("NO_CUMPLE", "CUMPLE", "NO_VERIFICABLE");
        assertThat(r.recomendaciones()).singleElement().satisfies(rec -> {
            assertThat(rec.id()).isEqualTo(1);
            assertThat(rec.gravedad()).isEqualTo("ALTA");
            assertThat(rec.accion()).isEqualTo("Mover creditos a DetallePlanAsignatura");
        });
        assertThat(r.resumen()).contains("3 reglas").contains("1 se cumplen");
    }
}
