package com.uagrm.colabmodel.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uagrm.colabmodel.modelcore.command.Origin;
import com.uagrm.colabmodel.modelcore.metamodel.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ServicioDeRecomendacionesTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final UUID diagramId = UUID.randomUUID();
    private final List<UmlClass> clases = new ArrayList<>();
    private final List<UmlAttribute> atributos = new ArrayList<>();
    private final List<UmlRelationship> relaciones = new ArrayList<>();

    private UmlClass clase(String nombre) {
        UmlClass c = new UmlClass();
        c.setId(UUID.randomUUID());
        c.setDiagramId(diagramId);
        c.setName(nombre);
        clases.add(c);
        return c;
    }

    private void atributo(UmlClass c, String nombre, String tipo, boolean pk) {
        UmlAttribute a = new UmlAttribute();
        a.setId(UUID.randomUUID());
        a.setClassId(c.getId());
        a.setName(nombre);
        a.setDataType(tipo);
        a.setPk(pk);
        atributos.add(a);
    }

    private void relacion(UmlClass s, UmlClass t, RelationKind kind, String ms, String mt) {
        UmlRelationship r = new UmlRelationship();
        r.setId(UUID.randomUUID());
        r.setDiagramId(diagramId);
        r.setSourceClassId(s.getId());
        r.setTargetClassId(t.getId());
        r.setKind(kind);
        r.setSourceMultiplicity(ms);
        r.setTargetMultiplicity(mt);
        relaciones.add(r);
    }

    private ServicioDeRecomendaciones servicio(ProveedorDeIA proveedor, ServicioDeIA servicioDeIA) {
        UmlClassRepository classes = mock(UmlClassRepository.class);
        when(classes.findByDiagramIdAndDeletedAtIsNullOrderByNameAsc(diagramId)).thenReturn(clases);
        UmlAttributeRepository attributes = mock(UmlAttributeRepository.class);
        when(attributes.findByClassIdInOrderByOrdinalAsc(any())).thenReturn(atributos);
        UmlRelationshipRepository relationships = mock(UmlRelationshipRepository.class);
        when(relationships.findByDiagramIdAndDeletedAtIsNull(diagramId)).thenReturn(relaciones);
        return new ServicioDeRecomendaciones(proveedor, classes, attributes, relationships, servicioDeIA);
    }

    private ProveedorDeIA proveedorQueResponde(String json) throws Exception {
        ProveedorDeIA proveedor = mock(ProveedorDeIA.class);
        when(proveedor.nombre()).thenReturn("falso");
        when(proveedor.generar(any())).thenReturn(new RespuestaIA(List.of(
                new ComandoPropuestoIA(EsquemaRecomendaciones.NOMBRE, mapper.readTree(json))), ""));
        return proveedor;
    }

    private void diagramaAcademico() {
        UmlClass asignatura = clase("Asignatura");
        atributo(asignatura, "id", "uuid", true);
        atributo(asignatura, "creditos", "int", false);
        UmlClass detalle = clase("DetallePlanAsignatura");
        atributo(detalle, "id", "uuid", true);
        atributo(detalle, "nivel", "int", false);
        relacion(detalle, asignatura, RelationKind.ASSOCIATION, "0..*", "1");
    }

    @Test
    void ordena_por_gravedad_numera_y_conserva_solo_elementos_que_existen() throws Exception {
        diagramaAcademico();
        var proveedor = proveedorQueResponde("""
                {"resumen":"Dos problemas","recomendaciones":[
                 {"titulo":"Nombre","gravedad":"BAJA","categoria":"NOMBRE","regla":"","elementos":["Asignatura"],
                  "problema":"p","accion":"Renombrar algo"},
                 {"titulo":"Creditos fuera de contexto","gravedad":"alta","categoria":"ATRIBUTO","regla":"creditos en el plan",
                  "elementos":["asignatura.CREDITOS","DetallePlanAsignatura","ClaseInventada"],
                  "problema":"Dependen del plan","accion":"Mover el atributo creditos (int) de Asignatura a DetallePlanAsignatura"}]}
                """);
        var r = servicio(proveedor, mock(ServicioDeIA.class)).recomendar(diagramId, "requisito", null);

        assertThat(r.resumen()).isEqualTo("Dos problemas");
        assertThat(r.recomendaciones()).extracting(RecomendacionesIA.Recomendacion::gravedad).containsExactly("ALTA", "BAJA");
        assertThat(r.recomendaciones()).extracting(RecomendacionesIA.Recomendacion::id).containsExactly(1, 2);
        assertThat(r.recomendaciones().getFirst().elementos())
                .containsExactly("Asignatura.creditos", "DetallePlanAsignatura");
        assertThat(r.modelo()).isEqualTo("falso");
    }

    @Test
    void descarta_recomendaciones_sin_accion_y_repetidas_y_normaliza_valores_desconocidos() throws Exception {
        diagramaAcademico();
        var proveedor = proveedorQueResponde("""
                {"resumen":"","recomendaciones":[
                 {"titulo":"Sin accion","gravedad":"ALTA","categoria":"OTRO","regla":"","elementos":[],"problema":"","accion":" "},
                 {"titulo":"A","gravedad":"URGENTE","categoria":"RARA","regla":"","elementos":[],"problema":"","accion":"Hacer X"},
                 {"titulo":"B","gravedad":"MEDIA","categoria":"OTRO","regla":"","elementos":[],"problema":"","accion":"hacer x"}]}
                """);
        var r = servicio(proveedor, mock(ServicioDeIA.class)).recomendar(diagramId, null, null);

        assertThat(r.recomendaciones()).singleElement().satisfies(rec -> {
            assertThat(rec.titulo()).isEqualTo("A");
            assertThat(rec.gravedad()).isEqualTo("MEDIA");
            assertThat(rec.categoria()).isEqualTo("OTRO");
        });
    }

    @Test
    void envia_requisito_hallazgos_y_banderas_de_atributos_al_modelo() throws Exception {
        diagramaAcademico();
        var proveedor = proveedorQueResponde("{\"resumen\":\"ok\",\"recomendaciones\":[]}");
        servicio(proveedor, mock(ServicioDeIA.class)).recomendar(diagramId, "Conservar creditos en cada version", "qwen");

        var captor = ArgumentCaptor.forClass(SolicitudIA.class);
        verify(proveedor).generar(captor.capture());
        SolicitudIA s = captor.getValue();
        assertThat(s.modo()).isEqualTo(ModoIA.RECOMENDAR);
        assertThat(s.modelo()).isEqualTo("qwen");
        assertThat(s.herramientas()).extracting(HerramientaIA::nombre).containsExactly(EsquemaRecomendaciones.NOMBRE);
        assertThat(s.mensajeParaElModelo())
                .contains("Conservar creditos en cada version")
                .contains("HALLAZGOS ESTRUCTURALES")
                .contains("id: uuid [pk]")
                .contains("DetallePlanAsignatura [0..*] --ASSOCIATION--> Asignatura [1]");
        assertThat(s.instruccionDeSistema()).contains("revisor senior");
    }

    @Test
    void un_diagrama_vacio_no_llama_al_modelo() {
        ProveedorDeIA proveedor = mock(ProveedorDeIA.class);
        assertThatThrownBy(() -> servicio(proveedor, mock(ServicioDeIA.class)).recomendar(diagramId, null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("vacio");
        verifyNoInteractions(proveedor);
    }

    @Test
    void si_el_modelo_no_devuelve_recomendaciones_lo_informa() {
        diagramaAcademico();
        ProveedorDeIA proveedor = mock(ProveedorDeIA.class);
        when(proveedor.generar(any())).thenReturn(new RespuestaIA(List.of(), "no se"));
        assertThatThrownBy(() -> servicio(proveedor, mock(ServicioDeIA.class)).recomendar(diagramId, null, null))
                .isInstanceOf(ProveedorIAException.class).hasMessageContaining("no se");
    }

    @Test
    void detecta_hallazgos_estructurales_genericos_sin_ia() {
        UmlClass cliente = clase("Cliente");
        atributo(cliente, "id", "uuid", true);
        atributo(cliente, "codigo", "string", true);
        UmlClass pedido = clase("Pedido");
        atributo(pedido, "clienteId", "uuid", false);
        UmlClass suelta = clase("Suelta");
        UmlClass etiqueta = clase("Etiqueta");
        atributo(etiqueta, "id", "uuid", true);
        relacion(pedido, cliente, RelationKind.ASSOCIATION, "0..*", "1");
        relacion(pedido, etiqueta, RelationKind.ASSOCIATION, "0..*", "1..*");
        relacion(pedido, etiqueta, RelationKind.ASSOCIATION, "0..*", "1..*");
        relacion(cliente, etiqueta, RelationKind.AGGREGATION, null, "1");

        var d = new ServicioDeRecomendaciones.Diagrama(clases,
                atributos.stream().collect(java.util.stream.Collectors.groupingBy(UmlAttribute::getClassId)),
                relaciones, clases.stream().collect(java.util.stream.Collectors.toMap(UmlClass::getId, UmlClass::getName)));
        var hallazgos = RevisorDeterministico.revisar(d, null).stream().map(RevisorDeterministico.Hallazgo::texto).toList();

        assertThat(hallazgos).anyMatch(h -> h.startsWith("Cliente tiene 2 atributos marcados como clave primaria"))
                .anyMatch(h -> h.equals("Pedido no tiene clave primaria."))
                .anyMatch(h -> h.startsWith("Pedido.clienteId duplica la asociacion con Cliente"))
                .anyMatch(h -> h.equals("Suelta no tiene atributos."))
                .anyMatch(h -> h.equals("Suelta esta desconectada."))
                .anyMatch(h -> h.startsWith("Hay relaciones ASSOCIATION repetidas entre Pedido y Etiqueta"))
                .anyMatch(h -> h.startsWith("La relacion Pedido - Etiqueta es muchos a muchos"))
                .anyMatch(h -> h.equals("La relacion Cliente - Etiqueta no tiene multiplicidad en ambos extremos."));
    }

    @Test
    void aplicar_convierte_las_acciones_elegidas_en_un_pedido_modificar() {
        ServicioDeIA servicioDeIA = mock(ServicioDeIA.class);
        var esperada = mock(PropuestaDeIA.class);
        when(servicioDeIA.generar(eq(diagramId), anyString(), eq(Origin.TEXT), eq(ModoIA.MODIFICAR), eq("qwen")))
                .thenReturn(esperada);
        var elegidas = List.of(new RecomendacionesIA.Recomendacion(1, "t", "ALTA", "ATRIBUTO", "", List.of(),
                "Dependen del plan", "Mover el atributo creditos (int) de Asignatura a DetallePlanAsignatura",
                RecomendacionesIA.ORIGEN_MODELO));

        var propuesta = servicio(mock(ProveedorDeIA.class), servicioDeIA)
                .proponerAplicacion(diagramId, elegidas, Origin.TEXT, "qwen");

        assertThat(propuesta).isSameAs(esperada);
        var pedido = ArgumentCaptor.forClass(String.class);
        verify(servicioDeIA).generar(eq(diagramId), pedido.capture(), eq(Origin.TEXT), eq(ModoIA.MODIFICAR), eq("qwen"));
        assertThat(pedido.getValue())
                .contains("1. Mover el atributo creditos (int) de Asignatura a DetallePlanAsignatura (motivo: Dependen del plan)")
                .contains("REMOVE_ATTRIBUTE").contains("UPDATE_RELATION");
    }

    @Test
    void los_borrados_que_nadie_pidio_se_descartan_y_una_relacion_recreada_tambien() {
        // Banco y turismo, 2026-11-08: ante "Corrige en el diagrama: ..." el modelo borro
        // Titular-Cuenta y borro y recreo DetalleReserva-Reserva.
        var titularCuenta = mapper.createObjectNode().put("relationId", "r-titular");
        var detalleReserva = mapper.createObjectNode().put("relationId", "r-detalle");
        var recreada = mapper.createObjectNode().put("sourceClassId", "reserva").put("targetClassId", "detalle");
        var nueva = mapper.createObjectNode().put("sourceClassId", "titular").put("targetClassId", "cliente");
        var p = new PropuestaDeIA(diagramId, Origin.TEXT, List.of(
                new PropuestaDeIA.ComandoAceptado(com.uagrm.colabmodel.modelcore.command.CommandType.DELETE_RELATION, titularCuenta, "Borrar Titular-Cuenta"),
                new PropuestaDeIA.ComandoAceptado(com.uagrm.colabmodel.modelcore.command.CommandType.DELETE_RELATION, detalleReserva, "Borrar DetalleReserva-Reserva"),
                new PropuestaDeIA.ComandoAceptado(com.uagrm.colabmodel.modelcore.command.CommandType.CREATE_RELATION, recreada, "Crear Reserva-DetalleReserva"),
                new PropuestaDeIA.ComandoAceptado(com.uagrm.colabmodel.modelcore.command.CommandType.CREATE_RELATION, nueva, "Crear Titular-Cliente")),
                List.of(), List.of(), List.of(), 4, "", "qwen", null);
        var corregir = List.of(new RecomendacionesIA.Recomendacion(1, "Titular", "ALTA", "ESTRUCTURA", "", List.of(), "",
                "Corrige en el diagrama: La clase asociativa Titular no conecta sus dos extremos o roles.", "VERIFICADO"));
        Map<String, Set<String>> extremos = Map.of("r-titular", Set.of("titular", "cuenta"), "r-detalle", Set.of("detalle", "reserva"));

        var filtrada = ServicioDeRecomendaciones.sinBorradosNoPedidos(p, corregir, id -> extremos.getOrDefault(id, Set.of()));

        assertThat(filtrada.aceptados()).extracting(PropuestaDeIA.ComandoAceptado::resumen).containsExactly("Crear Titular-Cliente");
        assertThat(filtrada.rechazados()).hasSize(3);

        var borrar = List.of(new RecomendacionesIA.Recomendacion(1, "Duplicada", "ALTA", "RELACION", "", List.of(), "",
                "Elimina la relacion duplicada Titular-Cuenta", "VERIFICADO"));
        assertThat(ServicioDeRecomendaciones.sinBorradosNoPedidos(p, borrar, id -> Set.of())).isSameAs(p);
    }

    @Test
    void aplicar_sin_recomendaciones_es_un_error_del_pedido() {
        var s = servicio(mock(ProveedorDeIA.class), mock(ServicioDeIA.class));
        assertThatThrownBy(() -> s.proponerAplicacion(diagramId, List.of(), Origin.TEXT, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void el_modo_generico_rechaza_recomendar() {
        UmlClassRepository classes = mock(UmlClassRepository.class);
        when(classes.findByDiagramIdAndDeletedAtIsNullOrderByNameAsc(diagramId)).thenReturn(List.of());
        UmlRelationshipRepository relationships = mock(UmlRelationshipRepository.class);
        var s = new ServicioDeIA(mock(ProveedorDeIA.class), classes, mock(UmlAttributeRepository.class),
                mock(UmlMethodRepository.class), relationships);
        assertThatThrownBy(() -> s.generar(diagramId, "x", Origin.TEXT, ModoIA.RECOMENDAR, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
