package com.uagrm.colabmodel.collab;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.uagrm.colabmodel.modelcore.command.*;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Deshacer geometria (ADR-027, escalon 1).
 *
 * El undo no es un camino aparte: es un MOVE_ELEMENT o RESIZE_ELEMENT comun,
 * con `deshace` (a que operacion revierte) y `esperado` (donde tiene que estar
 * el elemento para que revertir no pise a nadie). Estas pruebas fijan que esas
 * dos marcas se cumplan y que un rechazo nunca gaste un seq.
 */
class DeshacerGeometriaTest {

    private final ModeloEnMemoria d = new ModeloEnMemoria();

    @Test
    void deshacer_devuelve_la_clase_a_donde_estaba_y_ocupa_un_seq_nuevo() {
        UUID libro = d.clase(100, 100);
        var movida = d.applier.apply(d.mover(libro, 300, 300), d.ana);

        var deshecha = d.applier.apply(d.deshacerMover(libro, movida.opId(), 100, 100, 300, 300), d.ana);

        assertThat(d.x(libro)).isEqualTo(100);
        assertThat(d.y(libro)).isEqualTo(100);
        // Se escribe hacia adelante: la historia no se reescribe.
        assertThat(deshecha.seq()).isEqualTo(movida.seq() + 1);
        assertThat(d.log).hasSize(2);
        assertThat(deshecha.payload().path("deshace").asText()).isEqualTo(movida.opId().toString());
        assertThat(deshecha.origin()).isEqualTo(Origin.MOUSE);
    }

    @Test
    void rehacer_es_deshacer_el_deshacer() {
        UUID libro = d.clase(100, 100);
        var movida = d.applier.apply(d.mover(libro, 300, 300), d.ana);
        var deshecha = d.applier.apply(d.deshacerMover(libro, movida.opId(), 100, 100, 300, 300), d.ana);

        d.applier.apply(d.deshacerMover(libro, deshecha.opId(), 300, 300, 100, 100), d.ana);

        assertThat(d.x(libro)).isEqualTo(300);
    }

    @Test
    void si_otro_lo_movio_despues_el_undo_se_rechaza_con_motivo_y_sin_gastar_seq() {
        UUID libro = d.clase(100, 100);
        var movida = d.applier.apply(d.mover(libro, 300, 300), d.ana);
        d.applier.apply(d.mover(libro, 500, 500), d.beto);
        long seqAntes = d.diagrama.getCurrentSeq();

        var e = catchThrowableOfType(
                () -> d.applier.apply(d.deshacerMover(libro, movida.opId(), 100, 100, 300, 300), d.ana),
                CommandRejectedException.class);

        assertThat(e.getReason()).isEqualTo(RejectionReason.STALE);
        assertThat(e.getMessage()).contains("para no pisar su cambio");
        assertThat(d.x(libro)).isEqualTo(500);
        assertThat(d.diagrama.getCurrentSeq()).isEqualTo(seqAntes);
    }

    @Test
    void si_el_elemento_se_borro_no_se_le_inventa_geometria() {
        UUID libro = d.clase(100, 100);
        var movida = d.applier.apply(d.mover(libro, 300, 300), d.ana);
        d.layouts.remove(libro);   // lo que hace DELETE_CLASS con su geometria

        var e = catchThrowableOfType(
                () -> d.applier.apply(d.deshacerMover(libro, movida.opId(), 100, 100, 300, 300), d.ana),
                CommandRejectedException.class);

        assertThat(e.getReason()).isEqualTo(RejectionReason.NOT_FOUND);
        assertThat(d.layouts).doesNotContainKey(libro);
    }

    @Test
    void no_se_puede_deshacer_la_operacion_de_otro() {
        UUID libro = d.clase(100, 100);
        var deBeto = d.applier.apply(d.mover(libro, 300, 300), d.beto);

        var e = catchThrowableOfType(
                () -> d.applier.apply(d.deshacerMover(libro, deBeto.opId(), 100, 100, 300, 300), d.ana),
                CommandRejectedException.class);

        assertThat(e.getReason()).isEqualTo(RejectionReason.INVALID);
        assertThat(e.getMessage()).contains("cada quien deshace lo suyo");
        assertThat(d.x(libro)).isEqualTo(300);
    }

    @Test
    void una_operacion_de_otro_diagrama_no_existe_para_este() {
        UUID libro = d.clase(100, 100);

        var e = catchThrowableOfType(
                () -> d.applier.apply(d.deshacerMover(libro, UUID.randomUUID(), 100, 100, 100, 100), d.ana),
                CommandRejectedException.class);

        assertThat(e.getReason()).isEqualTo(RejectionReason.NOT_FOUND);
    }

    @Test
    void un_undo_sin_precondicion_se_rechaza_porque_podria_pisar() {
        UUID libro = d.clase(100, 100);
        var movida = d.applier.apply(d.mover(libro, 300, 300), d.ana);
        ObjectNode p = (ObjectNode) d.mover(libro, 100, 100).payload();
        p.put("deshace", movida.opId().toString());

        var e = catchThrowableOfType(() -> d.applier.apply(d.comando(CommandType.MOVE_ELEMENT, p), d.ana),
                CommandRejectedException.class);

        assertThat(e.getReason()).isEqualTo(RejectionReason.INVALID);
        assertThat(d.x(libro)).isEqualTo(300);
    }

    @Test
    void deshacer_un_redimensionado_compara_el_tamano() {
        UUID libro = d.clase(100, 100);
        var agrandada = d.applier.apply(redimensionar(libro, 300, 120, null), d.ana);
        d.applier.apply(redimensionar(libro, 400, 150, null), d.beto);

        var e = catchThrowableOfType(
                () -> d.applier.apply(redimensionar(libro, 170, 60, agrandada.opId()), d.ana),
                CommandRejectedException.class);

        assertThat(e.getReason()).isEqualTo(RejectionReason.STALE);
        assertThat(d.layouts.get(libro).getWidth()).isEqualTo(400);
    }

    @Test
    void deshacer_los_quiebres_devuelve_la_linea_a_recta() {
        UUID relacion = UUID.randomUUID();
        var acomodada = d.applier.apply(quiebres(relacion, "[{\"x\":10,\"y\":20}]", null, null), d.ana);

        d.applier.apply(quiebres(relacion, "[]", acomodada.opId(), "[{\"x\":10,\"y\":20}]"), d.ana);

        assertThat(d.layouts.get(relacion).getWaypoints()).isNull();
    }

    @Test
    void rehacer_quiebres_sobre_una_linea_recta_acepta_sin_quiebres_guardados_como_lista_vacia() {
        UUID relacion = UUID.randomUUID();
        var acomodada = d.applier.apply(quiebres(relacion, "[{\"x\":10,\"y\":20}]", null, null), d.ana);
        var deshecha = d.applier.apply(
                quiebres(relacion, "[]", acomodada.opId(), "[{\"x\":10,\"y\":20}]"), d.ana);

        d.applier.apply(quiebres(relacion, "[{\"x\":10,\"y\":20}]", deshecha.opId(), "[]"), d.ana);

        assertThat(d.layouts.get(relacion).getWaypoints()).contains("\"x\":10");
    }

    @Test
    void sin_marcas_mover_sigue_siendo_last_writer_wins() {
        UUID libro = d.clase(100, 100);
        d.applier.apply(d.mover(libro, 300, 300), d.ana);
        d.applier.apply(d.mover(libro, 500, 500), d.beto);

        d.applier.apply(d.mover(libro, 700, 700), d.ana);

        assertThat(d.x(libro)).isEqualTo(700);
    }

    // ------------------------------------------------------------ AYUDAS

    private Command redimensionar(UUID elementId, double w, double h, UUID deshace) {
        ObjectNode p = JsonNodeFactory.instance.objectNode();
        p.put("elementId", elementId.toString());
        p.put("width", w);
        p.put("height", h);
        if (deshace != null) {
            p.put("deshace", deshace.toString());
            p.putObject("esperado").put("width", 300).put("height", 120);
        }
        return d.comando(CommandType.RESIZE_ELEMENT, p);
    }

    private Command quiebres(UUID relationId, String puntos, UUID deshace, String esperados) {
        try {
            var m = new com.fasterxml.jackson.databind.ObjectMapper();
            ObjectNode p = JsonNodeFactory.instance.objectNode();
            p.put("elementId", relationId.toString());
            p.put("elementKind", "RELATION");
            p.set("waypoints", m.readTree(puntos));
            if (deshace != null) {
                p.put("deshace", deshace.toString());
                p.putObject("esperado").set("waypoints", m.readTree(esperados));
            }
            return d.comando(CommandType.MOVE_ELEMENT, p);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
