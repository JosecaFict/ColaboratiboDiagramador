package com.uagrm.colabmodel.collab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uagrm.colabmodel.modelcore.command.CommandRejectedException;
import com.uagrm.colabmodel.modelcore.command.CommandType;
import com.uagrm.colabmodel.modelcore.command.RejectionReason;
import com.uagrm.colabmodel.modelcore.metamodel.UmlMethod;
import com.uagrm.colabmodel.modelcore.metamodel.UmlMethodRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static com.uagrm.colabmodel.modelcore.command.CommandType.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Contrato UML 2.5.1 v1, rebanada (b): metodos estaticos y abstractos, UPDATE_METHOD (D-15).
 *
 *   V2: un metodo no es estatico y abstracto a la vez.
 *   V3: un metodo abstracto vive en una clase abstracta o en una interfaz.
 *   V4: una clase con metodos abstractos no deja de ser abstracta.
 */
class MetodosEstaticosYAbstractosTest {

    private final ModeloEnMemoria d = new ModeloEnMemoria();

    private UUID clase(String nombre, boolean abstracta, String estereotipo) {
        UUID id = UUID.randomUUID();
        d.applier.apply(d.comando(CREATE_CLASS, "{'classId':'%s','name':'%s','isAbstract':%s%s}".formatted(
                id, nombre, abstracta, estereotipo == null ? "" : ",'stereotype':'" + estereotipo + "'")), d.ana);
        return id;
    }

    private UUID agregar(UUID clase, String nombre, String banderas) {
        UUID id = UUID.randomUUID();
        d.applier.apply(d.comando(ADD_METHOD, "{'methodId':'%s','classId':'%s','name':'%s'%s}".formatted(
                id, clase, nombre, banderas.isEmpty() ? "" : "," + banderas)), d.ana);
        return id;
    }

    private CommandRejectedException rechazo(org.assertj.core.api.ThrowableAssert.ThrowingCallable accion) {
        var e = catchThrowableOfType(accion, CommandRejectedException.class);
        assertThat(e).isNotNull();
        return e;
    }

    private CommandRejectedException invalido(org.assertj.core.api.ThrowableAssert.ThrowingCallable accion) {
        var e = rechazo(accion);
        assertThat(e.getReason()).isEqualTo(RejectionReason.INVALID);
        return e;
    }

    private void actualizar(UUID metodo, String cambios) {
        d.applier.apply(d.comando(UPDATE_METHOD, "{'methodId':'%s',%s}".formatted(metodo, cambios)), d.ana);
    }

    // --------------------------------------------------------- por defecto

    @Test
    void sin_banderas_un_metodo_no_es_estatico_ni_abstracto() {
        UUID m = agregar(clase("Libro", false, null), "prestar", "");

        assertThat(d.metodos.get(m).isStatic()).isFalse();
        assertThat(d.metodos.get(m).isAbstract()).isFalse();
    }

    @Test
    void las_banderas_se_guardan_al_agregar() {
        UUID base = clase("Figura", true, null);
        UUID estatico = agregar(clase("Util", false, null), "crear", "'isStatic':true");
        UUID abstracto = agregar(base, "area", "'isAbstract':true");

        assertThat(d.metodos.get(estatico).isStatic()).isTrue();
        assertThat(d.metodos.get(estatico).isAbstract()).isFalse();
        assertThat(d.metodos.get(abstracto).isAbstract()).isTrue();
        assertThat(d.metodos.get(abstracto).isStatic()).isFalse();
    }

    // ------------------------------------------------------------------ V2

    @Test
    void V2_estatico_solo_o_abstracto_solo_se_aceptan() {
        UUID base = clase("Figura", true, null);

        assertThat(agregar(base, "crear", "'isStatic':true")).isNotNull();
        assertThat(agregar(base, "area", "'isAbstract':true")).isNotNull();
    }

    @Test
    void V2_estatico_y_abstracto_a_la_vez_se_rechaza_al_agregar() {
        UUID base = clase("Figura", true, null);

        var e = invalido(() -> agregar(base, "raro", "'isStatic':true,'isAbstract':true"));

        assertThat(e.getMessage()).contains("estatico y abstracto");
        assertThat(d.metodos).isEmpty();
    }

    @Test
    void V2_estatico_y_abstracto_a_la_vez_se_rechaza_al_editar_y_no_cambia_nada() {
        UUID base = clase("Figura", true, null);
        UUID m = agregar(base, "area", "'isAbstract':true");

        invalido(() -> actualizar(m, "'isStatic':true"));

        assertThat(d.metodos.get(m).isStatic()).isFalse();
        assertThat(d.metodos.get(m).isAbstract()).isTrue();
    }

    // ------------------------------------------------------------------ V3

    @Test
    void V3_abstracto_en_clase_abstracta_y_en_interfaz_se_aceptan() {
        UUID abstracta = clase("Figura", true, null);
        UUID interfaz = clase("Dibujable", false, "interface");

        assertThat(agregar(abstracta, "area", "'isAbstract':true")).isNotNull();
        assertThat(agregar(interfaz, "dibujar", "'isAbstract':true")).isNotNull();
    }

    @Test
    void V3_abstracto_en_una_clase_concreta_se_rechaza_al_agregar_y_al_editar() {
        UUID concreta = clase("Libro", false, null);
        UUID normal = agregar(concreta, "prestar", "");

        var alAgregar = invalido(() -> agregar(concreta, "raro", "'isAbstract':true"));
        var alEditar = invalido(() -> actualizar(normal, "'isAbstract':true"));

        assertThat(alAgregar.getMessage()).contains("no es abstracta ni interfaz");
        assertThat(alEditar.getMessage()).contains("Libro");
        assertThat(d.metodos.get(normal).isAbstract()).isFalse();
    }

    // ------------------------------------------------------------------ V4

    @Test
    void V4_quitar_isAbstract_a_una_clase_sin_metodos_abstractos_se_acepta() {
        UUID base = clase("Figura", true, null);
        agregar(base, "describir", "");

        d.applier.apply(d.comando(SET_ER_FLAG,
                "{'targetType':'CLASS','targetId':'%s','flag':'isAbstract','value':false}".formatted(base)), d.ana);

        assertThat(d.clases.get(base).isAbstract()).isFalse();
    }

    @Test
    void V4_quitar_isAbstract_a_una_clase_con_un_metodo_abstracto_se_rechaza() {
        UUID base = clase("Figura", true, null);
        agregar(base, "area", "'isAbstract':true");

        var e = invalido(() -> d.applier.apply(d.comando(SET_ER_FLAG,
                "{'targetType':'CLASS','targetId':'%s','flag':'isAbstract','value':false}".formatted(base)), d.ana));

        assertThat(e.getMessage()).contains("area").contains("no puede dejar de ser abstracta");
        assertThat(d.clases.get(base).isAbstract()).isTrue();
    }

    @Test
    void V4_marcar_abstracta_una_clase_concreta_se_acepta() {
        UUID c = clase("Figura", false, null);

        d.applier.apply(d.comando(SET_ER_FLAG,
                "{'targetType':'CLASS','targetId':'%s','flag':'isAbstract','value':true}".formatted(c)), d.ana);

        assertThat(d.clases.get(c).isAbstract()).isTrue();
    }

    // -------------------------------------------------------- UPDATE_METHOD

    @Test
    void un_cambio_parcial_no_toca_lo_que_no_dice() {
        UUID m = agregar(clase("Libro", false, null), "buscar", "'returnType':'Libro','visibility':'PRIVATE','isStatic':true");

        actualizar(m, "'name':'encontrar'");

        UmlMethod g = d.metodos.get(m);
        assertThat(g.getName()).isEqualTo("encontrar");
        assertThat(g.getReturnType()).isEqualTo("Libro");
        assertThat(g.getVisibility().name()).isEqualTo("PRIVATE");
        assertThat(g.isStatic()).isTrue();
    }

    @Test
    void se_editan_los_parametros_y_la_firma_repetida_se_rechaza() {
        UUID clase = clase("Libro", false, null);
        UUID a = agregar(clase, "buscar", "");
        agregar(clase, "buscar", "'params':[{'name':'t','type':'string'}]");

        actualizar(a, "'params':[{'name':'n','type':'int'}]");
        assertThat(d.metodos.get(a).getParams()).contains("int");

        var e = invalido(() -> actualizar(a, "'params':[{'name':'otro','type':'string'}]"));
        assertThat(e.getMessage()).contains("buscar(string)");
    }

    @Test
    void editar_un_metodo_que_no_existe_se_rechaza_con_NOT_FOUND() {
        var e = rechazo(() -> actualizar(UUID.randomUUID(), "'name':'x'"));

        assertThat(e.getReason()).isEqualTo(RejectionReason.NOT_FOUND);
    }

    @Test
    void una_visibilidad_invalida_al_editar_se_rechaza() {
        UUID m = agregar(clase("Libro", false, null), "prestar", "");

        invalido(() -> actualizar(m, "'visibility':'SECRETA'"));
    }

    // ------------------------------------------- recorrido: agregar y deshacer

    @Test
    void agregar_editar_quitar_y_deshacer_conserva_las_banderas() {
        UUID base = clase("Figura", true, null);
        UUID m = UUID.randomUUID();
        String agregarMetodo = "{'methodId':'%s','classId':'%s','name':'crear','isStatic':true}".formatted(m, base);
        var agregado = d.applier.apply(d.comando(ADD_METHOD, agregarMetodo), d.ana);

        // editar: deja de ser estatico y pasa a abstracto
        var editado = d.applier.apply(d.comando(UPDATE_METHOD,
                "{'methodId':'%s','isStatic':false,'isAbstract':true}".formatted(m)), d.ana);
        assertThat(d.metodos.get(m).isStatic()).isFalse();
        assertThat(d.metodos.get(m).isAbstract()).isTrue();

        // deshacer la edicion: vuelve a estatico y no abstracto
        d.applier.apply(d.deshacer(UPDATE_METHOD, "{'methodId':'%s','isStatic':true,'isAbstract':false}".formatted(m),
                editado.opId(), "{'isStatic':false,'isAbstract':true}"), d.ana);
        assertThat(d.metodos.get(m).isStatic()).isTrue();
        assertThat(d.metodos.get(m).isAbstract()).isFalse();

        // quitar y deshacer el quitar: vuelve con sus banderas
        var quitado = d.applier.apply(d.comando(REMOVE_METHOD, "{'methodId':'%s'}".formatted(m)), d.ana);
        assertThat(d.metodos).doesNotContainKey(m);
        d.applier.apply(d.deshacer(ADD_METHOD, agregarMetodo, quitado.opId(), "{'existe':false}"), d.ana);
        assertThat(d.metodos.get(m).isStatic()).isTrue();
        assertThat(d.metodos.get(m).isAbstract()).isFalse();
        assertThat(agregado.seq()).isLessThan(editado.seq());
    }

    @Test
    void deshacer_una_edicion_que_otro_cambio_despues_se_rechaza_con_STALE() {
        UUID base = clase("Figura", true, null);
        UUID m = agregar(base, "area", "");
        var mio = d.applier.apply(d.comando(UPDATE_METHOD, "{'methodId':'%s','isAbstract':true}".formatted(m)), d.ana);
        d.applier.apply(d.comando(UPDATE_METHOD, "{'methodId':'%s','isAbstract':false,'isStatic':true}".formatted(m)), d.beto);

        var e = rechazo(() -> d.applier.apply(d.deshacer(UPDATE_METHOD,
                "{'methodId':'%s','isAbstract':false}".formatted(m), mio.opId(), "{'isAbstract':true}"), d.ana));

        assertThat(e.getReason()).isEqualTo(RejectionReason.STALE);
        assertThat(e.getMessage()).contains("si es abstracto");
        assertThat(d.metodos.get(m).isStatic()).isTrue();
    }

    @Test
    void un_lote_de_importacion_agrega_metodos_con_banderas_y_uno_invalido_lo_rechaza_entero() {
        UUID base = UUID.randomUUID();
        UUID ok = UUID.randomUUID();
        d.applier.apply(d.comando(BATCH, ("{'descripcion':'import','comandos':["
                + "{'type':'CREATE_CLASS','payload':{'classId':'%s','name':'Figura','isAbstract':true}},"
                + "{'type':'ADD_METHOD','payload':{'methodId':'%s','classId':'%s','name':'area','isAbstract':true}}"
                + "]}").formatted(base, ok, base)), d.ana);
        assertThat(d.metodos.get(ok).isAbstract()).isTrue();

        UUID otra = UUID.randomUUID();
        var e = invalido(() -> d.applier.apply(d.comando(BATCH, ("{'descripcion':'import','comandos':["
                + "{'type':'CREATE_CLASS','payload':{'classId':'%s','name':'Concreta'}},"
                + "{'type':'ADD_METHOD','payload':{'methodId':'%s','classId':'%s','name':'x','isAbstract':true}}"
                + "]}").formatted(otra, UUID.randomUUID(), otra)), d.ana));
        // Que no quede nada aplicado lo garantiza la transaccion de la base (LoteTest): los dobles no la tienen.
        assertThat(e.getMessage()).contains("Cambio 2 de 2");
    }

    // ------------------------------------------------------------- locks

    @Test
    void UPDATE_METHOD_pide_el_lease_de_la_clase_duenia_como_REMOVE_METHOD() {
        UUID clase = UUID.randomUUID();
        UUID metodo = UUID.randomUUID();
        UmlMethod guardado = new UmlMethod();
        guardado.setId(metodo);
        guardado.setClassId(clase);
        UmlMethodRepository repo = mock(UmlMethodRepository.class);
        when(repo.findById(metodo)).thenReturn(Optional.of(guardado));
        var bloqueable = new EntidadBloqueable(
                mock(com.uagrm.colabmodel.modelcore.metamodel.UmlAttributeRepository.class), repo);
        JsonNode payload = new ObjectMapper().createObjectNode().put("methodId", metodo.toString());

        assertThat(bloqueable.de(CommandType.UPDATE_METHOD, payload)).contains(clase);
    }
}
