package com.uagrm.colabmodel.collab;

import com.uagrm.colabmodel.modelcore.command.CommandRejectedException;
import com.uagrm.colabmodel.modelcore.command.RejectionReason;
import com.uagrm.colabmodel.modelcore.metamodel.RelationKind;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static com.uagrm.colabmodel.modelcore.command.CommandType.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Contrato UML 2.5.1 v1, rebanada (a): interfaz (estereotipo) y REALIZATION (D-15).
 *
 * Cada regla tiene un caso que la acepta y uno que la rechaza con INVALID.
 *   V1: una realizacion va de una clase a una interfaz.
 *   V5: una clase no es interfaz y enumeracion (o valor embebido) a la vez.
 *   V9: una interfaz solo extiende a otra interfaz.
 */
class InterfazYRealizacionTest {

    private final ModeloEnMemoria d = new ModeloEnMemoria();

    private UUID crear(String nombre, String estereotipo) {
        UUID id = UUID.randomUUID();
        String est = estereotipo == null ? "" : ",'stereotype':'%s'".formatted(estereotipo);
        d.applier.apply(d.comando(CREATE_CLASS, "{'classId':'%s','name':'%s'%s}".formatted(id, nombre, est)), d.ana);
        return id;
    }

    private UUID relacionar(String tipo, UUID origen, UUID destino) {
        UUID id = UUID.randomUUID();
        d.applier.apply(d.comando(CREATE_RELATION,
                "{'relationId':'%s','sourceClassId':'%s','targetClassId':'%s','kind':'%s'}"
                        .formatted(id, origen, destino, tipo)), d.ana);
        return id;
    }

    private CommandRejectedException rechazo(org.assertj.core.api.ThrowableAssert.ThrowingCallable accion) {
        return catchThrowableOfType(accion, CommandRejectedException.class);
    }

    private CommandRejectedException rechazoDeRelacion(String tipo, UUID origen, UUID destino) {
        var e = rechazo(() -> relacionar(tipo, origen, destino));
        assertThat(e).isNotNull();
        assertThat(e.getReason()).isEqualTo(RejectionReason.INVALID);
        return e;
    }

    // ------------------------------------------------------------ interfaz

    @Test
    void una_interfaz_se_crea_como_estereotipo_y_se_guarda() {
        UUID repositorio = crear("Repositorio", "interface");

        assertThat(d.clases.get(repositorio).getStereotype()).isEqualTo("interface");
        assertThat(d.viva(repositorio)).isTrue();
    }

    @Test
    void V5_interfaz_y_enumeracion_a_la_vez_se_rechaza() {
        var e = rechazo(() -> crear("Raro", "interface, enumeration"));

        assertThat(e.getReason()).isEqualTo(RejectionReason.INVALID);
        assertThat(e.getMessage()).contains("interfaz");
        assertThat(d.clases).isEmpty();
    }

    @Test
    void V5_interfaz_y_valor_embebido_a_la_vez_se_rechaza() {
        var e = rechazo(() -> crear("Raro", "embeddable;interface"));

        assertThat(e.getReason()).isEqualTo(RejectionReason.INVALID);
    }

    @Test
    void V5_los_otros_estereotipos_siguen_igual_y_otro_texto_no_se_confunde_con_interface() {
        UUID e = crear("Estado", "enumeration");
        UUID i = crear("Interfaz2", "interfaces");   // otro texto: no es la marca

        assertThat(d.clases.get(e).getStereotype()).isEqualTo("enumeration");
        assertThat(d.clases.get(i).getStereotype()).isEqualTo("interfaces");
    }

    // -------------------------------------------------------- realizacion

    @Test
    void V1_una_clase_realiza_una_interfaz() {
        UUID repo = crear("Repositorio", "interface");
        UUID impl = crear("RepoLibros", null);

        UUID rel = relacionar("REALIZATION", impl, repo);

        assertThat(d.relaciones.get(rel).getKind()).isEqualTo(RelationKind.REALIZATION);
        assertThat(d.relaciones.get(rel).getSourceClassId()).isEqualTo(impl);
        assertThat(d.relaciones.get(rel).getTargetClassId()).isEqualTo(repo);
    }

    @Test
    void V1_una_clase_abstracta_tambien_puede_realizar() {
        UUID repo = crear("Repositorio", "interface");
        UUID base = UUID.randomUUID();
        d.applier.apply(d.comando(CREATE_CLASS,
                "{'classId':'%s','name':'RepoBase','isAbstract':true}".formatted(base)), d.ana);

        assertThat(relacionar("REALIZATION", base, repo)).isNotNull();
    }

    @Test
    void V1_realizar_algo_que_no_es_interfaz_se_rechaza() {
        UUID persona = crear("Persona", null);
        UUID impl = crear("Alumno", null);

        var e = rechazoDeRelacion("REALIZATION", impl, persona);

        assertThat(e.getMessage()).contains("termina en una interfaz").contains("Persona");
        assertThat(d.relaciones).isEmpty();
    }

    @Test
    void V1_una_interfaz_no_realiza_a_otra() {
        UUID a = crear("A", "interface");
        UUID b = crear("B", "interface");

        var e = rechazoDeRelacion("REALIZATION", a, b);

        assertThat(e.getMessage()).contains("generalizacion");
    }

    @Test
    void V1_una_enumeracion_no_realiza_una_interfaz() {
        UUID repo = crear("Repositorio", "interface");
        UUID estado = crear("Estado", "enumeration");

        var e = rechazoDeRelacion("REALIZATION", estado, repo);

        assertThat(e.getMessage()).contains("enumeracion");
    }

    @Test
    void V1_una_interfaz_no_se_realiza_a_si_misma() {
        UUID repo = crear("Repositorio", "interface");

        var e = rechazoDeRelacion("REALIZATION", repo, repo);

        assertThat(e.getMessage()).contains("a si misma");
    }

    @Test
    void V1_cambiar_el_tipo_de_una_asociacion_a_realizacion_se_revalida() {
        UUID persona = crear("Persona", null);
        UUID alumno = crear("Alumno", null);
        UUID repo = crear("Repositorio", "interface");
        UUID haciaPersona = relacionar("ASSOCIATION", alumno, persona);
        UUID haciaRepo = relacionar("ASSOCIATION", alumno, repo);

        var e = rechazo(() -> d.applier.apply(d.comando(UPDATE_RELATION,
                "{'relationId':'%s','kind':'REALIZATION'}".formatted(haciaPersona)), d.ana));
        assertThat(e.getReason()).isEqualTo(RejectionReason.INVALID);
        assertThat(d.relaciones.get(haciaPersona).getKind()).isEqualTo(RelationKind.ASSOCIATION);

        d.applier.apply(d.comando(UPDATE_RELATION,
                "{'relationId':'%s','kind':'REALIZATION'}".formatted(haciaRepo)), d.ana);
        assertThat(d.relaciones.get(haciaRepo).getKind()).isEqualTo(RelationKind.REALIZATION);
    }

    @Test
    void un_cambio_que_no_toca_el_tipo_no_revalida_una_relacion_existente() {
        UUID a = crear("A", null);
        UUID b = crear("B", null);
        UUID rel = relacionar("ASSOCIATION", a, b);

        d.applier.apply(d.comando(UPDATE_RELATION, "{'relationId':'%s','name':'tiene'}".formatted(rel)), d.ana);

        assertThat(d.relaciones.get(rel).getName()).isEqualTo("tiene");
    }

    // ------------------------------------------------------ generalizacion

    @Test
    void V9_una_interfaz_extiende_a_otra_interfaz() {
        UUID base = crear("Lector", "interface");
        UUID hija = crear("LectorConBusqueda", "interface");

        UUID rel = relacionar("GENERALIZATION", hija, base);

        assertThat(d.relaciones.get(rel).getKind()).isEqualTo(RelationKind.GENERALIZATION);
    }

    @Test
    void V9_una_interfaz_no_extiende_a_una_clase() {
        UUID clase = crear("Persona", null);
        UUID interfaz = crear("Lector", "interface");

        var e = rechazoDeRelacion("GENERALIZATION", interfaz, clase);

        assertThat(e.getMessage()).contains("solo puede extender a otra interfaz");
    }

    @Test
    void V9_la_herencia_entre_clases_comunes_sigue_igual() {
        UUID persona = crear("Persona", null);
        UUID alumno = crear("Alumno", null);

        assertThat(relacionar("GENERALIZATION", alumno, persona)).isNotNull();
    }

    // ---------------------------------------------------- deshacer y lotes

    @Test
    void borrar_una_realizacion_se_deshace_con_el_mismo_id_y_conserva_el_tipo() {
        UUID repo = crear("Repositorio", "interface");
        UUID impl = crear("RepoLibros", null);
        UUID rel = UUID.randomUUID();
        String crearRel = "{'relationId':'%s','sourceClassId':'%s','targetClassId':'%s','kind':'REALIZATION'}"
                .formatted(rel, impl, repo);
        d.applier.apply(d.comando(CREATE_RELATION, crearRel), d.ana);

        var borrada = d.applier.apply(d.comando(DELETE_RELATION, "{'relationId':'%s'}".formatted(rel)), d.ana);
        assertThat(d.relaciones.get(rel).getDeletedAt()).isNotNull();

        d.applier.apply(d.deshacer(CREATE_RELATION, crearRel, borrada.opId(), "{'eliminada':true}"), d.ana);
        assertThat(d.relaciones.get(rel).getDeletedAt()).isNull();
        assertThat(d.relaciones.get(rel).getKind()).isEqualTo(RelationKind.REALIZATION);
    }

    @Test
    void un_lote_de_importacion_crea_la_interfaz_la_clase_y_la_realizacion_juntas() {
        UUID repo = UUID.randomUUID();
        UUID impl = UUID.randomUUID();
        UUID rel = UUID.randomUUID();

        d.applier.apply(d.comando(BATCH, ("{'descripcion':'import','comandos':["
                + "{'type':'CREATE_CLASS','payload':{'classId':'%s','name':'Repositorio','stereotype':'interface'}},"
                + "{'type':'CREATE_CLASS','payload':{'classId':'%s','name':'RepoLibros'}},"
                + "{'type':'CREATE_RELATION','payload':{'relationId':'%s','sourceClassId':'%s','targetClassId':'%s','kind':'REALIZATION'}}"
                + "]}").formatted(repo, impl, rel, impl, repo)), d.ana);

        assertThat(d.relaciones.get(rel).getKind()).isEqualTo(RelationKind.REALIZATION);
    }

    @Test
    void un_lote_con_una_realizacion_invalida_no_aplica_nada() {
        UUID persona = UUID.randomUUID();
        UUID impl = UUID.randomUUID();

        var e = rechazo(() -> d.applier.apply(d.comando(BATCH, ("{'descripcion':'import','comandos':["
                + "{'type':'CREATE_CLASS','payload':{'classId':'%s','name':'Persona'}},"
                + "{'type':'CREATE_CLASS','payload':{'classId':'%s','name':'RepoLibros'}},"
                + "{'type':'CREATE_RELATION','payload':{'relationId':'%s','sourceClassId':'%s','targetClassId':'%s','kind':'REALIZATION'}}"
                + "]}").formatted(persona, impl, UUID.randomUUID(), impl, persona)), d.ana));

        assertThat(e.getReason()).isEqualTo(RejectionReason.INVALID);
        assertThat(e.getMessage()).contains("Cambio 3 de 3");
    }
}
