package com.uagrm.colabmodel.collab;

import com.uagrm.colabmodel.modelcore.command.*;
import com.uagrm.colabmodel.modelcore.metamodel.DiagramLayout;
import com.uagrm.colabmodel.modelcore.metamodel.InheritanceStrategy;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static com.uagrm.colabmodel.modelcore.command.CommandType.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Deshacer crear y borrar (ADR-030, escalon 3) y lotes (escalon 4).
 *
 * El agujero: DELETE_CLASS borra en duro atributos y metodos, asi que el log no
 * alcanza para deshacerlo. Lo que lo resuelve es que el cliente arme, ANTES de
 * borrar, un lote que vuelve a crear todo con los MISMOS ids; el servidor
 * reusa la fila con tombstone. Los payloads de estas pruebas son los que arma
 * historialLocal.ts.
 */
class DeshacerCreacionesTest {

    private final ModeloEnMemoria d = new ModeloEnMemoria();

    // ------------------------------------------------------------ CLASES

    @Test
    void crear_se_deshace_borrando_y_se_rehace_con_el_mismo_id() {
        UUID socio = UUID.randomUUID();
        var creada = d.applier.apply(d.comando(CREATE_CLASS,
                "{'classId':'%s','name':'Socio','x':10,'y':20}".formatted(socio)), d.ana);

        var deshecha = d.applier.apply(d.deshacer(DELETE_CLASS, "{'classId':'%s'}".formatted(socio), creada.opId(),
                "{'name':'Socio','attributeIds':[],'methodIds':[],'relationIds':[]}"), d.ana);
        assertThat(d.viva(socio)).isFalse();
        assertThat(d.layouts).doesNotContainKey(socio);

        d.applier.apply(d.deshacer(CREATE_CLASS, "{'classId':'%s','name':'Socio','x':10,'y':20}".formatted(socio),
                deshecha.opId(), "{'eliminada':true}"), d.ana);
        assertThat(d.viva(socio)).isTrue();
        assertThat(d.clases.get(socio).getName()).isEqualTo("Socio");
        assertThat(d.x(socio)).isEqualTo(10);
    }

    @Test
    void si_otro_le_agrego_un_atributo_deshacer_crear_no_se_lo_lleva() {
        UUID socio = UUID.randomUUID();
        var creada = d.applier.apply(d.comando(CREATE_CLASS, "{'classId':'%s','name':'Socio'}".formatted(socio)), d.ana);
        UUID dni = UUID.randomUUID();
        d.applier.apply(d.comando(ADD_ATTRIBUTE,
                "{'classId':'%s','attributeId':'%s','name':'dni'}".formatted(socio, dni)), d.beto);

        var e = rechazo(() -> d.applier.apply(d.deshacer(DELETE_CLASS, "{'classId':'%s'}".formatted(socio), creada.opId(),
                "{'name':'Socio','attributeIds':[],'methodIds':[],'relationIds':[]}"), d.ana));

        assertThat(e.getReason()).isEqualTo(RejectionReason.STALE);
        assertThat(e.getMessage()).contains("los atributos de la clase");
        assertThat(d.viva(socio)).isTrue();
        assertThat(d.atributos).containsKey(dni);
    }

    @Test
    void borrar_se_deshace_con_todo_lo_que_el_borrado_destruyo() {
        UUID libro = d.clase("Libro", 40, 50);
        d.clases.get(libro).setInheritanceStrategy(InheritanceStrategy.SINGLE_TABLE);
        UUID paginas = d.atributo(libro, "paginas", "int");
        d.atributos.get(paginas).setPk(true);
        d.atributos.get(paginas).setMinValue(new BigDecimal("1"));
        UUID prestar = d.metodo(libro, "prestar");
        UUID autor = d.clase("Autor", 400, 50);
        UUID escribe = d.relacion(libro, autor);
        quiebres(escribe, "[{\"x\":5,\"y\":6}]");

        var borrada = d.applier.apply(d.comando(DELETE_CLASS, "{'classId':'%s'}".formatted(libro)), d.ana);
        // Lo que hace que esto sea dificil: el borrado se llevo todo.
        assertThat(d.atributos).doesNotContainKey(paginas);
        assertThat(d.metodos).doesNotContainKey(prestar);
        assertThat(d.relaciones.get(escribe).getDeletedAt()).isNotNull();
        assertThat(d.layouts).doesNotContainKey(escribe);

        d.applier.apply(restaurar(libro, paginas, prestar, autor, escribe, borrada.opId()), d.ana);

        assertThat(d.viva(libro)).isTrue();
        assertThat(d.clases.get(libro).getInheritanceStrategy()).isEqualTo(InheritanceStrategy.SINGLE_TABLE);
        assertThat(d.x(libro)).isEqualTo(40);
        assertThat(d.atributos.get(paginas).isPk()).isTrue();
        assertThat(d.atributos.get(paginas).getMinValue()).isEqualByComparingTo("1");
        assertThat(d.metodos.get(prestar).getName()).isEqualTo("prestar");
        assertThat(d.relaciones.get(escribe).getDeletedAt()).isNull();
        assertThat(d.layouts.get(escribe).getWaypoints()).contains("\"x\":5");
    }

    @Test
    void si_el_otro_extremo_se_borro_restaurar_se_rechaza_diciendo_cual_cambio_fallo() {
        UUID libro = d.clase("Libro", 0, 0);
        UUID paginas = d.atributo(libro, "paginas", "int");
        UUID prestar = d.metodo(libro, "prestar");
        UUID autor = d.clase("Autor", 400, 0);
        UUID escribe = d.relacion(libro, autor);
        quiebres(escribe, "[{\"x\":5,\"y\":6}]");
        var borrada = d.applier.apply(d.comando(DELETE_CLASS, "{'classId':'%s'}".formatted(libro)), d.ana);
        d.applier.apply(d.comando(DELETE_CLASS, "{'classId':'%s'}".formatted(autor)), d.beto);

        var e = rechazo(() -> d.applier.apply(
                restaurar(libro, paginas, prestar, autor, escribe, borrada.opId()), d.ana));

        // La relacion quedaria colgando (ADR-027 D): se rechaza el lote entero,
        // y en la base la transaccion no deja nada a medias.
        assertThat(e.getReason()).isEqualTo(RejectionReason.NOT_FOUND);
        assertThat(e.getMessage()).contains("(CREATE_RELATION)").contains("No se aplico ninguno");
    }

    @Test
    void crear_una_clase_con_el_id_de_una_viva_es_un_error_y_lo_dice() {
        UUID libro = d.clase("Libro", 0, 0);

        var e = rechazo(() -> d.applier.apply(d.comando(CREATE_CLASS,
                "{'classId':'%s','name':'Otra'}".formatted(libro)), d.ana));

        assertThat(e.getReason()).isEqualTo(RejectionReason.INVALID);
        assertThat(e.getMessage()).contains("ese id");
        assertThat(d.clases.get(libro).getName()).isEqualTo("Libro");
    }

    // --------------------------------------------------- ATRIBUTOS Y METODOS

    @Test
    void agregar_un_atributo_se_deshace_y_se_rehace() {
        UUID libro = d.clase("Libro", 0, 0);
        UUID isbn = UUID.randomUUID();
        String agregar = "{'classId':'%s','attributeId':'%s','name':'isbn','dataType':'string'}".formatted(libro, isbn);
        var agregado = d.applier.apply(d.comando(ADD_ATTRIBUTE, agregar), d.ana);

        var deshecho = d.applier.apply(d.deshacer(REMOVE_ATTRIBUTE, "{'attributeId':'%s'}".formatted(isbn),
                agregado.opId(), "{'name':'isbn','dataType':'string'}"), d.ana);
        assertThat(d.atributos).doesNotContainKey(isbn);

        d.applier.apply(d.deshacer(ADD_ATTRIBUTE, agregar, deshecho.opId(), "{'existe':false}"), d.ana);
        assertThat(d.atributos.get(isbn).getName()).isEqualTo("isbn");
    }

    @Test
    void no_se_quita_un_atributo_que_otro_edito_despues() {
        UUID libro = d.clase("Libro", 0, 0);
        UUID isbn = UUID.randomUUID();
        var agregado = d.applier.apply(d.comando(ADD_ATTRIBUTE,
                "{'classId':'%s','attributeId':'%s','name':'isbn','dataType':'string'}".formatted(libro, isbn)), d.ana);
        d.applier.apply(d.comando(UPDATE_ATTRIBUTE, "{'attributeId':'%s','dataType':'long'}".formatted(isbn)), d.beto);

        var e = rechazo(() -> d.applier.apply(d.deshacer(REMOVE_ATTRIBUTE, "{'attributeId':'%s'}".formatted(isbn),
                agregado.opId(), "{'name':'isbn','dataType':'string'}"), d.ana));

        assertThat(e.getReason()).isEqualTo(RejectionReason.STALE);
        assertThat(d.atributos).containsKey(isbn);
    }

    @Test
    void quitar_un_atributo_se_deshace_con_todas_sus_banderas() {
        UUID libro = d.clase("Libro", 0, 0);
        UUID paginas = d.atributo(libro, "paginas", "int");
        var quitado = d.applier.apply(d.comando(REMOVE_ATTRIBUTE, "{'attributeId':'%s'}".formatted(paginas)), d.ana);

        d.applier.apply(d.deshacer(ADD_ATTRIBUTE, ("{'classId':'%s','attributeId':'%s','name':'paginas',"
                + "'dataType':'int','required':true,'minValue':1,'maxValue':900}").formatted(libro, paginas),
                quitado.opId(), "{'existe':false}"), d.ana);

        assertThat(d.atributos.get(paginas).isRequired()).isTrue();
        assertThat(d.atributos.get(paginas).getMaxValue()).isEqualByComparingTo("900");
    }

    @Test
    void agregar_un_metodo_se_deshace() {
        UUID libro = d.clase("Libro", 0, 0);
        UUID prestar = UUID.randomUUID();
        var agregado = d.applier.apply(d.comando(ADD_METHOD,
                "{'classId':'%s','methodId':'%s','name':'prestar'}".formatted(libro, prestar)), d.ana);

        d.applier.apply(d.deshacer(REMOVE_METHOD, "{'methodId':'%s'}".formatted(prestar), agregado.opId(),
                "{'name':'prestar'}"), d.ana);

        assertThat(d.metodos).doesNotContainKey(prestar);
    }

    // ------------------------------------------------------------ RELACIONES

    @Test
    void crear_una_relacion_se_deshace_y_rehacer_reusa_su_fila() {
        UUID socio = d.clase("Socio", 0, 0);
        UUID libro = d.clase("Libro", 300, 0);
        UUID pide = UUID.randomUUID();
        String crear = "{'relationId':'%s','sourceClassId':'%s','targetClassId':'%s','kind':'ASSOCIATION'}"
                .formatted(pide, socio, libro);
        var creada = d.applier.apply(d.comando(CREATE_RELATION, crear), d.ana);

        var deshecha = d.applier.apply(d.deshacer(DELETE_RELATION, "{'relationId':'%s'}".formatted(pide),
                creada.opId(), "{'kind':'ASSOCIATION'}"), d.ana);
        assertThat(d.relaciones.get(pide).getDeletedAt()).isNotNull();

        var fila = d.relaciones.get(pide);
        d.applier.apply(d.deshacer(CREATE_RELATION, crear, deshecha.opId(), "{'eliminada':true}"), d.ana);
        assertThat(d.relaciones.get(pide)).isSameAs(fila);
        assertThat(fila.getDeletedAt()).isNull();
    }

    // ----------------------------------------------------------------- LOTE

    @Test
    void una_importacion_entera_se_deshace_con_un_lote_inverso_y_se_rehace() {
        UUID libro = d.clase("Libro", 0, 0);
        UUID socio = UUID.randomUUID(), dni = UUID.randomUUID(), pide = UUID.randomUUID();
        String importar = ("{'descripcion':'Importado de biblioteca.xmi','comandos':["
                + "{'type':'CREATE_CLASS','payload':{'classId':'%1$s','name':'Socio'}},"
                + "{'type':'ADD_ATTRIBUTE','payload':{'classId':'%1$s','attributeId':'%2$s','name':'dni'}},"
                + "{'type':'CREATE_RELATION','payload':{'relationId':'%3$s','sourceClassId':'%1$s',"
                + "'targetClassId':'%4$s','kind':'ASSOCIATION'}}]}").formatted(socio, dni, pide, libro);
        var importado = d.applier.apply(d.comando(BATCH, importar), d.ana);

        var deshecho = d.applier.apply(d.comando(BATCH, deshacerImportacion(socio, dni, pide, importado.opId())), d.ana);
        assertThat(d.viva(socio)).isFalse();
        assertThat(d.atributos).doesNotContainKey(dni);
        assertThat(d.relaciones.get(pide).getDeletedAt()).isNotNull();
        // Un solo seq para deshacer toda la importacion, como para hacerla.
        assertThat(deshecho.seq()).isEqualTo(importado.seq() + 1);

        String rehacer = ("{'descripcion':'Importado de biblioteca.xmi','esperado':{},'deshace':'%5$s','comandos':["
                + "{'type':'CREATE_CLASS','payload':{'classId':'%1$s','name':'Socio','esperado':{'eliminada':true}}},"
                + "{'type':'ADD_ATTRIBUTE','payload':{'classId':'%1$s','attributeId':'%2$s','name':'dni','esperado':{'existe':false}}},"
                + "{'type':'CREATE_RELATION','payload':{'relationId':'%3$s','sourceClassId':'%1$s',"
                + "'targetClassId':'%4$s','kind':'ASSOCIATION','esperado':{'eliminada':true}}}]}")
                .formatted(socio, dni, pide, libro, deshecho.opId());
        d.applier.apply(d.comando(BATCH, rehacer), d.ana);
        assertThat(d.viva(socio)).isTrue();
        assertThat(d.atributos).containsKey(dni);
        assertThat(d.relaciones.get(pide).getDeletedAt()).isNull();
    }

    @Test
    void si_otro_edito_algo_de_lo_importado_deshacer_la_importacion_se_rechaza_entera() {
        UUID libro = d.clase("Libro", 0, 0);
        UUID socio = UUID.randomUUID(), dni = UUID.randomUUID(), pide = UUID.randomUUID();
        var importado = d.applier.apply(d.comando(BATCH, ("{'descripcion':'x','comandos':["
                + "{'type':'CREATE_CLASS','payload':{'classId':'%1$s','name':'Socio'}},"
                + "{'type':'ADD_ATTRIBUTE','payload':{'classId':'%1$s','attributeId':'%2$s','name':'dni'}},"
                + "{'type':'CREATE_RELATION','payload':{'relationId':'%3$s','sourceClassId':'%1$s',"
                + "'targetClassId':'%4$s','kind':'ASSOCIATION'}}]}").formatted(socio, dni, pide, libro)), d.ana);
        d.applier.apply(d.comando(UPDATE_ATTRIBUTE, "{'attributeId':'%s','name':'documento'}".formatted(dni)), d.beto);

        var e = rechazo(() -> d.applier.apply(d.comando(BATCH,
                deshacerImportacion(socio, dni, pide, importado.opId())), d.ana));

        assertThat(e.getReason()).isEqualTo(RejectionReason.STALE);
        assertThat(e.getMessage()).startsWith("Cambio 2 de 3 (REMOVE_ATTRIBUTE)");
    }

    @Test
    void no_se_puede_deshacer_el_lote_de_otro() {
        UUID socio = UUID.randomUUID();
        var deBeto = d.applier.apply(d.comando(BATCH, ("{'descripcion':'x','comandos':["
                + "{'type':'CREATE_CLASS','payload':{'classId':'%s','name':'Socio'}}]}").formatted(socio)), d.beto);

        var e = rechazo(() -> d.applier.apply(d.comando(BATCH, ("{'descripcion':'x','esperado':{},'deshace':'%s',"
                + "'comandos':[{'type':'DELETE_CLASS','payload':{'classId':'%s'}}]}").formatted(deBeto.opId(), socio)),
                d.ana));

        assertThat(e.getReason()).isEqualTo(RejectionReason.INVALID);
        assertThat(d.viva(socio)).isTrue();
    }

    // ---------------------------------------------------------------- AYUDAS

    /** Lo que arma historialLocal.restaurarClase antes de mandar el DELETE_CLASS. */
    private Command restaurar(UUID libro, UUID paginas, UUID prestar, UUID autor, UUID escribe, UUID deshace) {
        return d.comando(BATCH, ("{'descripcion':'Deshacer borrar Libro','esperado':{},'deshace':'%6$s','comandos':["
                + "{'type':'CREATE_CLASS','payload':{'classId':'%1$s','name':'Libro','x':40,'y':50,'width':170,"
                + "'height':60,'esperado':{'eliminada':true}}},"
                + "{'type':'SET_ER_FLAG','payload':{'targetType':'CLASS','targetId':'%1$s','flag':'inheritanceStrategy',"
                + "'strategy':'SINGLE_TABLE'}},"
                + "{'type':'ADD_ATTRIBUTE','payload':{'classId':'%1$s','attributeId':'%2$s','name':'paginas',"
                + "'dataType':'int','pk':true,'minValue':1,'esperado':{'existe':false}}},"
                + "{'type':'ADD_METHOD','payload':{'classId':'%1$s','methodId':'%3$s','name':'prestar',"
                + "'returnType':'void','params':[],'esperado':{'existe':false}}},"
                + "{'type':'CREATE_RELATION','payload':{'relationId':'%5$s','sourceClassId':'%1$s','targetClassId':'%4$s',"
                + "'kind':'ASSOCIATION','sourceMultiplicity':'1','targetMultiplicity':'0..*','esperado':{'eliminada':true}}},"
                + "{'type':'MOVE_ELEMENT','payload':{'elementId':'%5$s','elementKind':'RELATION',"
                + "'waypoints':[{'x':5,'y':6}]}}]}")
                .formatted(libro, paginas, prestar, autor, escribe, deshace));
    }

    /** Los inversos de la importacion, al reves, cada uno con su precondicion. */
    private String deshacerImportacion(UUID socio, UUID dni, UUID pide, UUID deshace) {
        return ("{'descripcion':'Deshacer: x','esperado':{},'deshace':'%4$s','comandos':["
                + "{'type':'DELETE_RELATION','payload':{'relationId':'%3$s','esperado':{'kind':'ASSOCIATION'}}},"
                + "{'type':'REMOVE_ATTRIBUTE','payload':{'attributeId':'%2$s','esperado':{'name':'dni'}}},"
                + "{'type':'DELETE_CLASS','payload':{'classId':'%1$s','esperado':{'name':'Socio',"
                + "'attributeIds':[],'methodIds':[],'relationIds':[]}}}]}").formatted(socio, dni, pide, deshace);
    }

    private void quiebres(UUID relationId, String puntos) {
        DiagramLayout l = new DiagramLayout();
        l.setDiagramId(d.diagramId);
        l.setElementId(relationId);
        l.setElementKind("RELATION");
        l.setWaypoints(puntos);
        d.layouts.put(relationId, l);
    }

    private static CommandRejectedException rechazo(org.assertj.core.api.ThrowableAssert.ThrowingCallable accion) {
        return catchThrowableOfType(accion, CommandRejectedException.class);
    }
}
