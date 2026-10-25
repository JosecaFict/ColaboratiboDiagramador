package com.uagrm.colabmodel.collab;

import com.uagrm.colabmodel.modelcore.command.*;
import com.uagrm.colabmodel.modelcore.mapping.Ambiguedad.Lectura;
import com.uagrm.colabmodel.modelcore.metamodel.InheritanceStrategy;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static com.uagrm.colabmodel.modelcore.command.CommandType.*;
import static com.uagrm.colabmodel.modelcore.mapping.Ambiguedad.TipoAmbiguedad.COMPOSITION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Deshacer semantica sin perdida (ADR-027, escalon 2).
 *
 * Cada comando se revierte con el MISMO comando y el valor anterior, que el
 * cliente capturo al emitir. Lo que se prueba aca es la otra mitad: que el
 * servidor solo lo deje pasar si nadie cambio lo mismo despues, y que si otro
 * cambio OTRA cosa del mismo elemento, lo suyo quede (degradar, no rechazar).
 */
class DeshacerSemanticaTest {

    private final ModeloEnMemoria d = new ModeloEnMemoria();
    private final Autor pestanaDeAna = new Autor(d.ana, "sesion-ana");
    private final Autor pestanaDeBeto = new Autor(d.beto, "sesion-beto");

    // ------------------------------------------------------------ RENAME_CLASS

    @Test
    void renombrar_se_deshace_y_se_rehace() {
        UUID libro = d.clase("Libro", 0, 0);
        var renombrada = d.applier.apply(d.comando(RENAME_CLASS, "{'classId':'%s','name':'Libros'}".formatted(libro)), d.ana);

        var deshecha = d.applier.apply(d.deshacer(RENAME_CLASS, "{'classId':'%s','name':'Libro'}".formatted(libro),
                renombrada.opId(), "{'name':'Libros'}"), d.ana);
        assertThat(d.clases.get(libro).getName()).isEqualTo("Libro");

        d.applier.apply(d.deshacer(RENAME_CLASS, "{'classId':'%s','name':'Libros'}".formatted(libro),
                deshecha.opId(), "{'name':'Libro'}"), d.ana);
        assertThat(d.clases.get(libro).getName()).isEqualTo("Libros");
    }

    @Test
    void si_otro_la_renombro_despues_no_se_pisa_y_el_motivo_dice_que_cambio() {
        UUID libro = d.clase("Libro", 0, 0);
        var mia = d.applier.apply(d.comando(RENAME_CLASS, "{'classId':'%s','name':'Libros'}".formatted(libro)), d.ana);
        d.applier.apply(d.comando(RENAME_CLASS, "{'classId':'%s','name':'Ejemplar'}".formatted(libro)), d.beto);

        var e = rechazo(() -> d.applier.apply(d.deshacer(RENAME_CLASS, "{'classId':'%s','name':'Libro'}".formatted(libro),
                mia.opId(), "{'name':'Libros'}"), d.ana));

        assertThat(e.getReason()).isEqualTo(RejectionReason.STALE);
        assertThat(e.getMessage()).contains("el nombre");
        assertThat(d.clases.get(libro).getName()).isEqualTo("Ejemplar");
    }

    @Test
    void renombrar_una_clase_borrada_despues_no_la_resucita() {
        UUID libro = d.clase("Libro", 0, 0);
        var mia = d.applier.apply(d.comando(RENAME_CLASS, "{'classId':'%s','name':'Libros'}".formatted(libro)), d.ana);
        d.clases.get(libro).setDeletedAt(java.time.OffsetDateTime.now());

        var e = rechazo(() -> d.applier.apply(d.deshacer(RENAME_CLASS, "{'classId':'%s','name':'Libro'}".formatted(libro),
                mia.opId(), "{'name':'Libros'}"), d.ana));

        assertThat(e.getReason()).isEqualTo(RejectionReason.NOT_FOUND);
    }

    // -------------------------------------------------------- UPDATE_ATTRIBUTE

    @Test
    void deshacer_un_cambio_de_tipo_revierte_solo_lo_propio_y_deja_lo_ajeno() {
        UUID libro = d.clase("Libro", 0, 0);
        UUID paginas = d.atributo(libro, "paginas", "string");
        var mia = d.applier.apply(d.comando(UPDATE_ATTRIBUTE, "{'attributeId':'%s','dataType':'int'}".formatted(paginas)), d.ana);
        // Beto cambia OTRO campo del mismo atributo: no choca con lo de Ana.
        d.applier.apply(d.comando(UPDATE_ATTRIBUTE, "{'attributeId':'%s','name':'cantidadPaginas'}".formatted(paginas)), d.beto);

        d.applier.apply(d.deshacer(UPDATE_ATTRIBUTE, "{'attributeId':'%s','dataType':'string'}".formatted(paginas),
                mia.opId(), "{'dataType':'int'}"), d.ana);

        assertThat(d.atributos.get(paginas).getDataType()).isEqualTo("string");
        assertThat(d.atributos.get(paginas).getName()).isEqualTo("cantidadPaginas");
    }

    @Test
    void si_otro_cambio_el_mismo_campo_el_undo_se_rechaza() {
        UUID libro = d.clase("Libro", 0, 0);
        UUID paginas = d.atributo(libro, "paginas", "string");
        var mia = d.applier.apply(d.comando(UPDATE_ATTRIBUTE, "{'attributeId':'%s','dataType':'int'}".formatted(paginas)), d.ana);
        d.applier.apply(d.comando(UPDATE_ATTRIBUTE, "{'attributeId':'%s','dataType':'long'}".formatted(paginas)), d.beto);

        var e = rechazo(() -> d.applier.apply(d.deshacer(UPDATE_ATTRIBUTE,
                "{'attributeId':'%s','dataType':'string'}".formatted(paginas), mia.opId(), "{'dataType':'int'}"), d.ana));

        assertThat(e.getReason()).isEqualTo(RejectionReason.STALE);
        assertThat(e.getMessage()).contains("el tipo");
        assertThat(d.atributos.get(paginas).getDataType()).isEqualTo("long");
    }

    @Test
    void un_rango_escrito_como_texto_en_el_panel_coincide_con_el_numero_guardado() {
        UUID libro = d.clase("Libro", 0, 0);
        UUID paginas = d.atributo(libro, "paginas", "int");
        var mia = d.applier.apply(d.comando(UPDATE_ATTRIBUTE,
                "{'attributeId':'%s','minValue':'10','maxValue':null}".formatted(paginas)), d.ana);

        d.applier.apply(d.deshacer(UPDATE_ATTRIBUTE, "{'attributeId':'%s','minValue':null,'maxValue':null}".formatted(paginas),
                mia.opId(), "{'minValue':10,'maxValue':''}"), d.ana);

        assertThat(d.atributos.get(paginas).getMinValue()).isNull();
    }

    @Test
    void un_rango_decimal_se_compara_exacto_no_con_la_tolerancia_de_la_geometria() {
        UUID libro = d.clase("Libro", 0, 0);
        UUID precio = d.atributo(libro, "precio", "decimal");
        var mia = d.applier.apply(d.comando(UPDATE_ATTRIBUTE, "{'attributeId':'%s','minValue':0.005}".formatted(precio)), d.ana);
        d.applier.apply(d.comando(UPDATE_ATTRIBUTE, "{'attributeId':'%s','minValue':0.001}".formatted(precio)), d.beto);

        var e = rechazo(() -> d.applier.apply(d.deshacer(UPDATE_ATTRIBUTE,
                "{'attributeId':'%s','minValue':null}".formatted(precio), mia.opId(), "{'minValue':0.005}"), d.ana));

        assertThat(e.getReason()).isEqualTo(RejectionReason.STALE);
        assertThat(d.atributos.get(precio).getMinValue()).isEqualByComparingTo(new BigDecimal("0.001"));
    }

    @Test
    void un_atributo_quitado_despues_no_se_puede_deshacer() {
        UUID libro = d.clase("Libro", 0, 0);
        UUID paginas = d.atributo(libro, "paginas", "string");
        var mia = d.applier.apply(d.comando(UPDATE_ATTRIBUTE, "{'attributeId':'%s','dataType':'int'}".formatted(paginas)), d.ana);
        d.atributos.remove(paginas);

        var e = rechazo(() -> d.applier.apply(d.deshacer(UPDATE_ATTRIBUTE,
                "{'attributeId':'%s','dataType':'string'}".formatted(paginas), mia.opId(), "{'dataType':'int'}"), d.ana));

        assertThat(e.getReason()).isEqualTo(RejectionReason.NOT_FOUND);
    }

    // --------------------------------------------------------- UPDATE_RELATION

    @Test
    void deshacer_una_multiplicidad() {
        UUID socio = d.clase("Socio", 0, 0);
        UUID prestamo = d.clase("Prestamo", 300, 0);
        UUID rel = d.relacion(socio, prestamo);
        var mia = d.applier.apply(d.comando(UPDATE_RELATION, "{'relationId':'%s','targetMultiplicity':'1..*'}".formatted(rel)), d.ana);

        d.applier.apply(d.deshacer(UPDATE_RELATION, "{'relationId':'%s','targetMultiplicity':'0..*'}".formatted(rel),
                mia.opId(), "{'targetMultiplicity':'1..*'}"), d.ana);

        assertThat(d.relaciones.get(rel).getTargetMultiplicity()).isEqualTo("0..*");
    }

    @Test
    void una_relacion_borrada_despues_no_se_edita() {
        UUID socio = d.clase("Socio", 0, 0);
        UUID rel = d.relacion(socio, d.clase("Prestamo", 300, 0));
        var mia = d.applier.apply(d.comando(UPDATE_RELATION, "{'relationId':'%s','kind':'COMPOSITION'}".formatted(rel)), d.ana);
        d.relaciones.get(rel).setDeletedAt(java.time.OffsetDateTime.now());

        var e = rechazo(() -> d.applier.apply(d.deshacer(UPDATE_RELATION, "{'relationId':'%s','kind':'ASSOCIATION'}".formatted(rel),
                mia.opId(), "{'kind':'COMPOSITION'}"), d.ana));

        assertThat(e.getReason()).isEqualTo(RejectionReason.NOT_FOUND);
    }

    // ------------------------------------------------------------- SET_ER_FLAG

    @Test
    void deshacer_marcar_clave_primaria() {
        UUID libro = d.clase("Libro", 0, 0);
        UUID isbn = d.atributo(libro, "isbn", "string");
        String marcar = "{'targetType':'ATTRIBUTE','targetId':'%s','flag':'pk','value':%s}";
        var mia = d.applier.apply(d.comando(SET_ER_FLAG, marcar.formatted(isbn, true)), d.ana);

        d.applier.apply(d.deshacer(SET_ER_FLAG, marcar.formatted(isbn, false), mia.opId(), "{'value':true}"), d.ana);

        assertThat(d.atributos.get(isbn).isPk()).isFalse();
    }

    @Test
    void deshacer_la_estrategia_de_herencia_de_la_raiz() {
        UUID persona = d.clase("Persona", 0, 0);
        String fijar = "{'targetType':'CLASS','targetId':'%s','flag':'inheritanceStrategy','strategy':'%s'}";
        var mia = d.applier.apply(d.comando(SET_ER_FLAG, fijar.formatted(persona, "SINGLE_TABLE")), d.ana);

        d.applier.apply(d.deshacer(SET_ER_FLAG, fijar.formatted(persona, "JOINED"), mia.opId(),
                "{'strategy':'SINGLE_TABLE'}"), d.ana);

        assertThat(d.clases.get(persona).getInheritanceStrategy()).isEqualTo(InheritanceStrategy.JOINED);
    }

    @Test
    void si_otro_desmarco_la_bandera_despues_no_se_pisa() {
        UUID libro = d.clase("Libro", 0, 0);
        UUID autores = d.atributo(libro, "autores", "string");
        String marcar = "{'targetType':'ATTRIBUTE','targetId':'%s','flag':'multivalued','value':%s}";
        var mia = d.applier.apply(d.comando(SET_ER_FLAG, marcar.formatted(autores, true)), d.ana);
        d.applier.apply(d.comando(SET_ER_FLAG, marcar.formatted(autores, false)), d.beto);

        var e = rechazo(() -> d.applier.apply(d.deshacer(SET_ER_FLAG, marcar.formatted(autores, false),
                mia.opId(), "{'value':true}"), d.ana));

        assertThat(e.getReason()).isEqualTo(RejectionReason.STALE);
    }

    // --------------------------------------------------- SET_MODELING_DECISION

    @Test
    void deshacer_una_decision_vuelve_a_la_lectura_anterior() {
        UUID rel = d.relacion(d.clase("Pedido", 0, 0), d.clase("Linea", 300, 0));
        String decidir = "{'elementId':'%s','ambiguityKind':'COMPOSITION','chosen':'%s'}";
        var mia = d.applier.apply(d.comando(SET_MODELING_DECISION, decidir.formatted(rel, "OO")), d.ana);

        // La primera vez no habia fila: "antes" es la lectura que regia por
        // defecto, y deshacer la deja escrita explicita (mismo esquema).
        d.applier.apply(d.deshacer(SET_MODELING_DECISION, decidir.formatted(rel, "DATA"), mia.opId(),
                "{'chosen':'OO'}"), d.ana);

        assertThat(d.decision(rel, COMPOSITION)).isEqualTo(Lectura.DATA);
    }

    @Test
    void si_otro_contesto_distinto_despues_la_decision_no_se_pisa() {
        UUID rel = d.relacion(d.clase("Pedido", 0, 0), d.clase("Linea", 300, 0));
        String decidir = "{'elementId':'%s','ambiguityKind':'COMPOSITION','chosen':'%s'}";
        var mia = d.applier.apply(d.comando(SET_MODELING_DECISION, decidir.formatted(rel, "OO")), d.ana);
        d.applier.apply(d.comando(SET_MODELING_DECISION, decidir.formatted(rel, "DATA")), d.beto);

        var e = rechazo(() -> d.applier.apply(d.deshacer(SET_MODELING_DECISION, decidir.formatted(rel, "DATA"),
                mia.opId(), "{'chosen':'OO'}"), d.ana));

        assertThat(e.getReason()).isEqualTo(RejectionReason.STALE);
        assertThat(e.getMessage()).contains("la decision");
    }

    // ---------------------------------------------- LOCKS (decision E, ADR-027)

    @Test
    void un_undo_semantico_respeta_el_lease_de_otra_sesion() {
        UUID libro = d.clase("Libro", 0, 0);
        UUID paginas = d.atributo(libro, "paginas", "string");
        var mia = d.bus.submit(d.comando(UPDATE_ATTRIBUTE, "{'attributeId':'%s','dataType':'int'}".formatted(paginas)),
                pestanaDeAna);
        // Ana solto la clase; ahora la tiene Beto abierta.
        d.locks.acquire(d.diagramId, libro, d.beto, pestanaDeBeto.sesion(), "Beto #2");

        var e = rechazo(() -> d.bus.submit(d.deshacer(UPDATE_ATTRIBUTE,
                "{'attributeId':'%s','dataType':'string'}".formatted(paginas), mia.opId(), "{'dataType':'int'}"),
                pestanaDeAna));

        assertThat(e.getReason()).isEqualTo(RejectionReason.LOCKED_BY_OTHER);
        assertThat(e.getMessage()).contains("Beto #2");
        assertThat(d.atributos.get(paginas).getDataType()).isEqualTo("int");
    }

    @Test
    void con_la_clase_libre_el_undo_semantico_pasa_sin_tener_que_tomarla() {
        UUID libro = d.clase("Libro", 0, 0);
        var mia = d.bus.submit(d.comando(RENAME_CLASS, "{'classId':'%s','name':'Libros'}".formatted(libro)), pestanaDeAna);

        d.bus.submit(d.deshacer(RENAME_CLASS, "{'classId':'%s','name':'Libro'}".formatted(libro), mia.opId(),
                "{'name':'Libros'}"), pestanaDeAna);

        assertThat(d.clases.get(libro).getName()).isEqualTo("Libro");
    }

    private static CommandRejectedException rechazo(org.assertj.core.api.ThrowableAssert.ThrowingCallable accion) {
        return catchThrowableOfType(accion, CommandRejectedException.class);
    }
}
