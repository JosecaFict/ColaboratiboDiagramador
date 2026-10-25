package com.uagrm.colabmodel.ai;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.uagrm.colabmodel.modelcore.command.Command;
import com.uagrm.colabmodel.modelcore.command.CommandType;
import com.uagrm.colabmodel.modelcore.command.Origin;
import com.uagrm.colabmodel.modelcore.metamodel.*;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * El nucleo del item 4.4: validar server-side la respuesta de la IA ANTES de
 * tocar el bus. El tope del plan y el rechazo de lo invalido tienen que
 * cumplirse pase lo que pase en el prompt o en el modelo, asi que el
 * proveedor de estas pruebas es un doble que devuelve una respuesta fija.
 */
class ServicioDeIATest {

    private static final JsonNodeFactory J = JsonNodeFactory.instance;
    private final UUID diagramId = UUID.randomUUID();

    private ServicioDeIA servicioSinNadaEnElDiagrama(RespuestaIA respuesta) {
        UmlClassRepository classes = mock(UmlClassRepository.class);
        when(classes.findByDiagramIdAndDeletedAtIsNullOrderByNameAsc(diagramId)).thenReturn(List.of());
        UmlRelationshipRepository relationships = mock(UmlRelationshipRepository.class);
        when(relationships.findByDiagramIdAndDeletedAtIsNull(diagramId)).thenReturn(List.of());
        return new ServicioDeIA(new ProveedorDeIAFalso(respuesta), classes, mock(UmlAttributeRepository.class),
                mock(UmlMethodRepository.class), relationships);
    }

    private static ObjectNode crearClase(String nombre) {
        ObjectNode p = J.objectNode();
        p.put("name", nombre);
        return p;
    }

    // ------------------------------------------------ EL TOPE DEL PLAN COMPLETO

    @Test
    void con_cincuenta_comandos_del_modelo_nunca_acepta_mas_de_treinta_y_dos() {
        List<ComandoPropuestoIA> cincuenta = new ArrayList<>();
        for (int i = 1; i <= 50; i++) {
            cincuenta.add(new ComandoPropuestoIA("CREATE_CLASS", crearClase("Clase" + i)));
        }
        ServicioDeIA servicio = servicioSinNadaEnElDiagrama(new RespuestaIA(cincuenta, ""));

        PropuestaDeIA propuesta = servicio.generar(diagramId, "disename un sistema completo", Origin.TEXT);

        assertThat(propuesta.propuestosPorElModelo()).isEqualTo(50);
        assertThat(propuesta.aceptados()).hasSize(32);
        assertThat(propuesta.truncada()).isTrue();
        assertThat(propuesta.descartadosPorTope()).hasSize(18);
        assertThat(propuesta.rechazados()).isEmpty();
    }

    @Test
    void un_comando_inventado_fuera_del_catalogo_se_rechaza_con_motivo() {
        List<ComandoPropuestoIA> comandos = List.of(
                new ComandoPropuestoIA("CREATE_CLASS", crearClase("Cliente")),
                new ComandoPropuestoIA("BORRAR_TODO_EL_DIAGRAMA", J.objectNode()));
        ServicioDeIA servicio = servicioSinNadaEnElDiagrama(new RespuestaIA(comandos, ""));

        PropuestaDeIA propuesta = servicio.generar(diagramId, "prueba", Origin.TEXT);

        assertThat(propuesta.aceptados()).hasSize(1);
        assertThat(propuesta.rechazados()).hasSize(1);
        PropuestaDeIA.ComandoRechazado r = propuesta.rechazados().get(0);
        assertThat(r.tipoCrudo()).isEqualTo("BORRAR_TODO_EL_DIAGRAMA");
        assertThat(r.motivo()).contains("catalogo");
    }

    @Test
    void un_comando_del_catalogo_que_la_ia_no_puede_tocar_tambien_se_rechaza() {
        // BATCH y MOVE_ELEMENT existen en CommandType pero no son herramientas
        // de la IA (EsquemaDeComandos): la IA no disena ni mueve cajas.
        List<ComandoPropuestoIA> comandos = List.of(new ComandoPropuestoIA("BATCH", J.objectNode()));
        ServicioDeIA servicio = servicioSinNadaEnElDiagrama(new RespuestaIA(comandos, ""));

        PropuestaDeIA propuesta = servicio.generar(diagramId, "prueba", Origin.TEXT);

        assertThat(propuesta.aceptados()).isEmpty();
        assertThat(propuesta.rechazados()).singleElement()
                .satisfies(r -> assertThat(r.motivo()).contains("no puede proponer"));
    }

    @Test
    void una_referencia_a_una_clase_inexistente_se_rechaza_con_motivo() {
        ObjectNode atributo = J.objectNode();
        atributo.put("clase", "Fantasma");
        atributo.put("name", "titulo");
        List<ComandoPropuestoIA> comandos = List.of(new ComandoPropuestoIA("ADD_ATTRIBUTE", atributo));
        ServicioDeIA servicio = servicioSinNadaEnElDiagrama(new RespuestaIA(comandos, ""));

        PropuestaDeIA propuesta = servicio.generar(diagramId, "prueba", Origin.TEXT);

        assertThat(propuesta.aceptados()).isEmpty();
        assertThat(propuesta.rechazados()).singleElement()
                .satisfies(r -> assertThat(r.motivo()).contains("Fantasma"));
    }

    @Test
    void la_mezcla_realista_trunca_lo_que_sobra_y_rechaza_lo_invalido_por_separado() {
        List<ComandoPropuestoIA> comandos = new ArrayList<>();
        for (int i = 1; i <= 3; i++) comandos.add(new ComandoPropuestoIA("CREATE_CLASS", crearClase("Clase" + i)));
        comandos.add(new ComandoPropuestoIA("BORRAR_TODO", J.objectNode()));
        comandos.add(new ComandoPropuestoIA("BORRAR_TODO", J.objectNode()));
        ObjectNode atributoFantasma1 = J.objectNode();
        atributoFantasma1.put("clase", "Fantasma");
        atributoFantasma1.put("name", "x");
        ObjectNode atributoFantasma2 = J.objectNode();
        atributoFantasma2.put("clase", "Fantasma");
        atributoFantasma2.put("name", "y");
        comandos.add(new ComandoPropuestoIA("ADD_ATTRIBUTE", atributoFantasma1));
        comandos.add(new ComandoPropuestoIA("ADD_ATTRIBUTE", atributoFantasma2));
        for (int i = 4; i <= 46; i++) comandos.add(new ComandoPropuestoIA("CREATE_CLASS", crearClase("Clase" + i)));
        // 3 + 2 + 2 + 43 = 50 comandos; 46 son CREATE_CLASS validos, 4 son invalidos.

        ServicioDeIA servicio = servicioSinNadaEnElDiagrama(new RespuestaIA(comandos, ""));
        PropuestaDeIA propuesta = servicio.generar(diagramId, "disename un sistema completo", Origin.TEXT);

        assertThat(propuesta.propuestosPorElModelo()).isEqualTo(50);
        assertThat(propuesta.aceptados()).hasSize(32);
        assertThat(propuesta.aceptados()).allSatisfy(a -> assertThat(a.tipo()).isEqualTo(CommandType.CREATE_CLASS));
        assertThat(propuesta.rechazados()).hasSize(4);
        assertThat(propuesta.rechazados()).allSatisfy(r -> assertThat(r.motivo()).isNotBlank());
        assertThat(propuesta.descartadosPorTope()).hasSize(14); // 46 validos - 32 aceptados
    }

    // ------------------------------------------------------------ EL ORIGEN

    @Test
    void solo_acepta_pedidos_con_origen_text_o_voice() {
        ServicioDeIA servicio = servicioSinNadaEnElDiagrama(new RespuestaIA(List.of(), ""));

        assertThatThrownBy(() -> servicio.generar(diagramId, "prueba", Origin.MOUSE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // -------------------------------------------------------- EL BATCH FINAL

    @Test
    void el_batch_final_lleva_el_origen_de_la_propuesta_y_solo_lo_aceptado() {
        List<ComandoPropuestoIA> comandos = List.of(new ComandoPropuestoIA("CREATE_CLASS", crearClase("Cliente")));
        ServicioDeIA servicio = servicioSinNadaEnElDiagrama(new RespuestaIA(comandos, ""));
        PropuestaDeIA propuesta = servicio.generar(diagramId, "prueba", Origin.VOICE);

        Command batch = servicio.comandoBatchDe(propuesta);

        assertThat(batch.type()).isEqualTo(CommandType.BATCH);
        assertThat(batch.origin()).isEqualTo(Origin.VOICE);
        assertThat(batch.diagramId()).isEqualTo(diagramId);
        assertThat(batch.payload().path("comandos").size()).isEqualTo(1);
    }

    @Test
    void no_se_puede_armar_un_batch_de_una_propuesta_sin_nada_aceptado() {
        ServicioDeIA servicio = servicioSinNadaEnElDiagrama(new RespuestaIA(List.of(), ""));
        PropuestaDeIA propuesta = servicio.generar(diagramId, "prueba", Origin.TEXT);

        assertThatThrownBy(() -> servicio.comandoBatchDe(propuesta)).isInstanceOf(IllegalStateException.class);
    }
}
