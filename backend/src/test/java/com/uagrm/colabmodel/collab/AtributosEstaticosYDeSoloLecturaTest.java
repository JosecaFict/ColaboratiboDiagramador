package com.uagrm.colabmodel.collab;

import com.uagrm.colabmodel.modelcore.command.CommandRejectedException;
import com.uagrm.colabmodel.modelcore.command.RejectionReason;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static com.uagrm.colabmodel.modelcore.command.CommandType.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/** Contrato UML 2.5.1 v1, rebanada (c): isStatic e isReadOnly del atributo (D-15). */
class AtributosEstaticosYDeSoloLecturaTest {

    private final ModeloEnMemoria d = new ModeloEnMemoria();

    private UUID clase() {
        UUID id = UUID.randomUUID();
        d.applier.apply(d.comando(CREATE_CLASS, "{'classId':'%s','name':'Libro'}".formatted(id)), d.ana);
        return id;
    }

    private UUID agregar(UUID clase, String banderas) {
        UUID id = UUID.randomUUID();
        d.applier.apply(d.comando(ADD_ATTRIBUTE, "{'attributeId':'%s','classId':'%s','name':'total'%s}".formatted(
                id, clase, banderas.isEmpty() ? "" : "," + banderas)), d.ana);
        return id;
    }

    @Test
    void sin_banderas_un_atributo_no_es_estatico_ni_de_solo_lectura() {
        UUID a = agregar(clase(), "");

        assertThat(d.atributos.get(a).isStatic()).isFalse();
        assertThat(d.atributos.get(a).isReadOnly()).isFalse();
    }

    @Test
    void las_banderas_se_guardan_al_agregar_y_son_independientes() {
        UUID c = clase();
        UUID estatico = agregar(c, "'isStatic':true");
        UUID lectura = agregar(c, "'readOnly':true");

        assertThat(d.atributos.get(estatico).isStatic()).isTrue();
        assertThat(d.atributos.get(estatico).isReadOnly()).isFalse();
        assertThat(d.atributos.get(lectura).isReadOnly()).isTrue();
        assertThat(d.atributos.get(lectura).isStatic()).isFalse();
    }

    @Test
    void estatico_y_de_solo_lectura_a_la_vez_es_una_constante_y_se_acepta() {
        UUID a = agregar(clase(), "'isStatic':true,'readOnly':true");

        assertThat(d.atributos.get(a).isStatic()).isTrue();
        assertThat(d.atributos.get(a).isReadOnly()).isTrue();
    }

    @Test
    void actualizar_cambia_solo_la_bandera_que_trae_el_payload() {
        UUID a = agregar(clase(), "'isStatic':true");

        d.applier.apply(d.comando(UPDATE_ATTRIBUTE, "{'attributeId':'%s','readOnly':true}".formatted(a)), d.ana);

        assertThat(d.atributos.get(a).isStatic()).isTrue();
        assertThat(d.atributos.get(a).isReadOnly()).isTrue();
        assertThat(d.atributos.get(a).getName()).isEqualTo("total");
    }

    @Test
    void agregar_actualizar_quitar_y_deshacer_conserva_las_dos_banderas() {
        UUID c = clase();
        UUID a = UUID.randomUUID();
        String agregarAtributo = "{'attributeId':'%s','classId':'%s','name':'total','isStatic':true,'readOnly':true}"
                .formatted(a, c);
        d.applier.apply(d.comando(ADD_ATTRIBUTE, agregarAtributo), d.ana);

        var editado = d.applier.apply(d.comando(UPDATE_ATTRIBUTE,
                "{'attributeId':'%s','isStatic':false,'readOnly':false}".formatted(a)), d.ana);
        assertThat(d.atributos.get(a).isStatic()).isFalse();
        assertThat(d.atributos.get(a).isReadOnly()).isFalse();

        // deshacer la edicion: vuelven las dos
        d.applier.apply(d.deshacer(UPDATE_ATTRIBUTE, "{'attributeId':'%s','isStatic':true,'readOnly':true}".formatted(a),
                editado.opId(), "{'isStatic':false,'readOnly':false}"), d.ana);
        assertThat(d.atributos.get(a).isStatic()).isTrue();
        assertThat(d.atributos.get(a).isReadOnly()).isTrue();

        // quitar y deshacer el quitar (el inverso es ADD con lo que volco la precondicion)
        var quitado = d.applier.apply(d.comando(REMOVE_ATTRIBUTE, "{'attributeId':'%s'}".formatted(a)), d.ana);
        assertThat(d.atributos).doesNotContainKey(a);
        d.applier.apply(d.deshacer(ADD_ATTRIBUTE, agregarAtributo, quitado.opId(), "{'existe':false}"), d.ana);
        assertThat(d.atributos.get(a).isStatic()).isTrue();
        assertThat(d.atributos.get(a).isReadOnly()).isTrue();
    }

    @Test
    void deshacer_una_edicion_que_otro_cambio_despues_se_rechaza_con_STALE() {
        UUID a = agregar(clase(), "");
        var mio = d.applier.apply(d.comando(UPDATE_ATTRIBUTE, "{'attributeId':'%s','readOnly':true}".formatted(a)), d.ana);
        d.applier.apply(d.comando(UPDATE_ATTRIBUTE, "{'attributeId':'%s','readOnly':false}".formatted(a)), d.beto);

        var e = catchThrowableOfType(() -> d.applier.apply(d.deshacer(UPDATE_ATTRIBUTE,
                "{'attributeId':'%s','readOnly':false}".formatted(a), mio.opId(), "{'readOnly':true}"), d.ana),
                CommandRejectedException.class);

        assertThat(e).isNotNull();
        assertThat(e.getReason()).isEqualTo(RejectionReason.STALE);
    }
}
