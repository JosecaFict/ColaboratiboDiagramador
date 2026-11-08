package com.uagrm.colabmodel.ai;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.uagrm.colabmodel.modelcore.command.CommandType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AuditorDePropuestaIATest {

    private static final JsonNodeFactory J = JsonNodeFactory.instance;

    @Test
    void no_lee_la_herencia_de_un_evento_como_asociacion_uno_a_uno_o_historial() {
        ObjectNode r = J.objectNode().put("sourceClassId", "pago").put("targetClassId", "operacion")
                .put("kind", "GENERALIZATION").put("sourceMultiplicity", "1").put("targetMultiplicity", "1");
        var avisos = AuditorDePropuestaIA.auditar(List.of(
                aceptado(CommandType.CREATE_CLASS, clase("pago", "Pago")),
                aceptado(CommandType.CREATE_CLASS, clase("operacion", "Operacion")),
                aceptado(CommandType.CREATE_RELATION, r)));
        assertThat(avisos).noneMatch(a -> a.contains("uno a uno") || a.contains("limitar el historial")
                || a.contains("multiplicidad"));
    }

    @Test
    void turismo_reserva_duena_de_participantes_y_titular_persona_no_generan_avisos() {
        // Prueba desde la interfaz, 2026-11-08: dos avisos falsos sobre un diseno correcto.
        ObjectNode participantes = J.objectNode().put("sourceClassId", "reserva").put("targetClassId", "participante")
                .put("kind", "ASSOCIATION").put("sourceMultiplicity", "1").put("targetMultiplicity", "1..*");
        ObjectNode titular = J.objectNode().put("sourceClassId", "reserva").put("targetClassId", "titular")
                .put("kind", "ASSOCIATION").put("sourceMultiplicity", "0..*").put("targetMultiplicity", "1");
        var avisos = AuditorDePropuestaIA.auditar(List.of(
                aceptado(CommandType.CREATE_CLASS, clase("reserva", "Reserva")),
                aceptado(CommandType.CREATE_CLASS, clase("participante", "Participante")),
                aceptado(CommandType.CREATE_CLASS, clase("titular", "TitularReserva")),
                aceptado(CommandType.ADD_ATTRIBUTE, J.objectNode().put("classId", "titular").put("name", "nombre")),
                aceptado(CommandType.ADD_ATTRIBUTE, J.objectNode().put("classId", "titular").put("name", "email")),
                aceptado(CommandType.CREATE_RELATION, participantes),
                aceptado(CommandType.CREATE_RELATION, titular)));
        assertThat(avisos).noneMatch(a -> a.contains("limitar el historial") || a.contains("TitularReserva solo tiene"));
    }

    @Test
    void una_garantia_unica_por_detalle_no_se_marca_como_historial_limitado() {
        ObjectNode r = J.objectNode().put("sourceClassId", "garantia").put("targetClassId", "detalle")
                .put("kind", "ASSOCIATION").put("sourceMultiplicity", "1").put("targetMultiplicity", "1");
        var avisos = AuditorDePropuestaIA.auditar(List.of(
                aceptado(CommandType.CREATE_CLASS, clase("garantia", "Garantia")),
                aceptado(CommandType.CREATE_CLASS, clase("detalle", "DetalleVenta")),
                aceptado(CommandType.CREATE_RELATION, r)));
        assertThat(avisos).noneMatch(a -> a.contains("uno a uno"));
    }

    @Test
    void advierte_uno_a_uno_y_clave_foranea_duplicada() {
        ObjectNode libro = clase("libro", "Book");
        ObjectNode copia = clase("copia", "Copy");
        ObjectNode bookId = J.objectNode().put("classId", "copia").put("name", "bookId");
        ObjectNode relacion = J.objectNode().put("sourceClassId", "libro").put("targetClassId", "copia")
                .put("sourceMultiplicity", "1").put("targetMultiplicity", "1");

        List<String> avisos = AuditorDePropuestaIA.auditar(List.of(
                aceptado(CommandType.CREATE_CLASS, libro), aceptado(CommandType.CREATE_CLASS, copia),
                aceptado(CommandType.ADD_ATTRIBUTE, bookId), aceptado(CommandType.CREATE_RELATION, relacion)));

        assertThat(avisos).anyMatch(a -> a.contains("uno a uno"));
        assertThat(avisos).anyMatch(a -> a.contains("Copy.bookId") && a.contains("duplica"));
    }

    @Test
    void advierte_clase_de_estado_sin_identidad_y_relacion_incompleta() {
        ObjectNode estado = clase("estado", "Status");
        ObjectNode valor = J.objectNode().put("classId", "estado").put("name", "status");
        ObjectNode relacion = J.objectNode().put("sourceClassId", "estado").put("targetClassId", "otra")
                .put("sourceMultiplicity", "1");

        List<String> avisos = AuditorDePropuestaIA.auditar(List.of(
                aceptado(CommandType.CREATE_CLASS, estado), aceptado(CommandType.ADD_ATTRIBUTE, valor),
                aceptado(CommandType.CREATE_RELATION, relacion)));

        assertThat(avisos).anyMatch(a -> a.contains("enumeracion"));
        assertThat(avisos).anyMatch(a -> a.contains("no define multiplicidad"));
    }

    @Test
    void advierte_composicion_historica_tilde_y_clave_natural() {
        ObjectNode copia = clase("copia", "Copia");
        ObjectNode prestamo = clase("prestamo", "Préstamo");
        ObjectNode codigo = J.objectNode().put("classId", "copia").put("name", "codigoInventario")
                .put("pk", true).put("unique", true);
        ObjectNode fecha = J.objectNode().put("classId", "prestamo").put("name", "fechaDevolucion");
        ObjectNode relacion = J.objectNode().put("sourceClassId", "copia").put("targetClassId", "prestamo")
                .put("kind", "COMPOSITION").put("sourceMultiplicity", "1").put("targetMultiplicity", "0..*");

        List<String> avisos = AuditorDePropuestaIA.auditar(List.of(
                aceptado(CommandType.CREATE_CLASS, copia), aceptado(CommandType.CREATE_CLASS, prestamo),
                aceptado(CommandType.ADD_ATTRIBUTE, codigo), aceptado(CommandType.ADD_ATTRIBUTE, fecha),
                aceptado(CommandType.CREATE_RELATION, relacion)));

        assertThat(avisos).anyMatch(a -> a.contains("composicion") && a.contains("historica"));
        assertThat(avisos).anyMatch(a -> a.contains("Préstamo") && a.contains("tildes"));
        assertThat(avisos).anyMatch(a -> a.contains("codigoInventario") && a.contains("clave natural"));
    }

    @Test
    void identificacion_no_se_confunde_con_un_id_surrogado() {
        ObjectNode usuario = clase("usuario", "Usuario");
        ObjectNode identificacion = J.objectNode().put("classId", "usuario")
                .put("name", "identificacionUniversitaria").put("pk", true).put("unique", true);

        List<String> avisos = AuditorDePropuestaIA.auditar(List.of(
                aceptado(CommandType.CREATE_CLASS, usuario),
                aceptado(CommandType.ADD_ATTRIBUTE, identificacion)));

        assertThat(avisos).anyMatch(a -> a.contains("identificacionUniversitaria")
                && a.contains("clave natural"));
    }

    @Test
    void advierte_clase_asociativa_con_un_solo_extremo() {
        ObjectNode receta = clase("receta", "Receta");
        ObjectNode detalle = clase("detalle", "DetalleReceta");
        ObjectNode relacion = J.objectNode().put("sourceClassId", "receta").put("targetClassId", "detalle")
                .put("kind", "ASSOCIATION").put("sourceMultiplicity", "1")
                .put("targetMultiplicity", "1..*");

        List<String> avisos = AuditorDePropuestaIA.auditar(List.of(
                aceptado(CommandType.CREATE_CLASS, receta), aceptado(CommandType.CREATE_CLASS, detalle),
                aceptado(CommandType.CREATE_RELATION, relacion)));

        assertThat(avisos).anyMatch(a -> a.contains("posible clase asociativa DetalleReceta")
                && a.contains("solo tiene 1 relacion"));
    }

    @Test
    void participante_tambien_se_considera_clase_asociativa() {
        ObjectNode modelo = clase("modelo", "ModeloColaborativo");
        ObjectNode participante = clase("participante", "Participante");
        ObjectNode rol = J.objectNode().put("classId", "participante").put("name", "rolAsignado");
        ObjectNode relacion = J.objectNode().put("sourceClassId", "modelo")
                .put("targetClassId", "participante").put("kind", "ASSOCIATION")
                .put("sourceMultiplicity", "1").put("targetMultiplicity", "0..*");

        List<String> avisos = AuditorDePropuestaIA.auditar(List.of(
                aceptado(CommandType.CREATE_CLASS, modelo), aceptado(CommandType.CREATE_CLASS, participante),
                aceptado(CommandType.ADD_ATTRIBUTE, rol),
                aceptado(CommandType.CREATE_RELATION, relacion)));

        assertThat(avisos).anyMatch(a -> a.contains("posible clase asociativa Participante")
                && a.contains("solo tiene 1 relacion"));
    }

    @Test
    void participante_persona_no_se_confunde_con_clase_asociativa() {
        ObjectNode reserva = clase("reserva", "Reserva");
        ObjectNode participante = clase("participante", "Participante");
        ObjectNode nombre = J.objectNode().put("classId", "participante").put("name", "nombreCompleto");
        ObjectNode relacion = J.objectNode().put("sourceClassId", "reserva")
                .put("targetClassId", "participante").put("kind", "ASSOCIATION")
                .put("sourceMultiplicity", "1").put("targetMultiplicity", "1..*");

        List<String> avisos = AuditorDePropuestaIA.auditar(List.of(
                aceptado(CommandType.CREATE_CLASS, reserva), aceptado(CommandType.CREATE_CLASS, participante),
                aceptado(CommandType.ADD_ATTRIBUTE, nombre), aceptado(CommandType.CREATE_RELATION, relacion)));

        assertThat(avisos).noneMatch(a -> a.contains("posible clase asociativa Participante"));
    }

    @Test
    void no_marca_como_historial_incompleto_un_resultado_clinico_o_eventos_opcionales() {
        ObjectNode cita = clase("cita", "Cita");
        ObjectNode consulta = clase("consulta", "ConsultaMedica");
        ObjectNode diagnostico = clase("diagnostico", "Diagnostico");
        ObjectNode citaConsulta = J.objectNode().put("sourceClassId", "cita")
                .put("targetClassId", "consulta").put("kind", "ASSOCIATION")
                .put("sourceMultiplicity", "0..1").put("targetMultiplicity", "0..1");
        ObjectNode consultaDiagnostico = J.objectNode().put("sourceClassId", "consulta")
                .put("targetClassId", "diagnostico").put("kind", "ASSOCIATION")
                .put("sourceMultiplicity", "1").put("targetMultiplicity", "0..*");

        List<String> avisos = AuditorDePropuestaIA.auditar(List.of(
                aceptado(CommandType.CREATE_CLASS, cita), aceptado(CommandType.CREATE_CLASS, consulta),
                aceptado(CommandType.CREATE_CLASS, diagnostico),
                aceptado(CommandType.CREATE_RELATION, citaConsulta),
                aceptado(CommandType.CREATE_RELATION, consultaDiagnostico)));

        assertThat(avisos).noneMatch(a -> a.contains("entidad historica")
                || a.contains("multiplicidad junto a la entidad historica"));
    }

    @Test
    void no_marca_receta_como_historial_incompleto_cuando_conecta_su_indicacion() {
        ObjectNode receta = clase("receta", "Receta");
        ObjectNode indicacion = clase("indicacion", "IndicacionMedicamento");
        ObjectNode relacion = J.objectNode().put("sourceClassId", "receta")
                .put("targetClassId", "indicacion").put("kind", "ASSOCIATION")
                .put("sourceMultiplicity", "1").put("targetMultiplicity", "1..*");

        List<String> avisos = AuditorDePropuestaIA.auditar(List.of(
                aceptado(CommandType.CREATE_CLASS, receta),
                aceptado(CommandType.CREATE_CLASS, indicacion),
                aceptado(CommandType.CREATE_RELATION, relacion)));

        assertThat(avisos).noneMatch(a -> a.contains("multiplicidad junto a la entidad historica Receta"));
    }

    @Test
    void la_pk_de_una_clase_con_asociacion_recursiva_no_es_una_clave_foranea() {
        ObjectNode animal = clase("animal", "Animal");
        ObjectNode id = J.objectNode().put("classId", "animal").put("name", "idAnimal")
                .put("dataType", "uuid").put("pk", true);
        ObjectNode madre = J.objectNode().put("sourceClassId", "animal").put("targetClassId", "animal")
                .put("kind", "ASSOCIATION").put("sourceMultiplicity", "0..*").put("targetMultiplicity", "0..1")
                .put("targetRole", "madre");

        List<String> avisos = AuditorDePropuestaIA.auditar(List.of(
                aceptado(CommandType.CREATE_CLASS, animal), aceptado(CommandType.ADD_ATTRIBUTE, id),
                aceptado(CommandType.CREATE_RELATION, madre)));

        assertThat(avisos).noneMatch(a -> a.contains("idAnimal") && a.contains("duplica"));
    }

    private static ObjectNode clase(String id, String nombre) {
        return J.objectNode().put("classId", id).put("name", nombre);
    }

    private static PropuestaDeIA.ComandoAceptado aceptado(CommandType tipo, ObjectNode payload) {
        return new PropuestaDeIA.ComandoAceptado(tipo, payload, tipo.name());
    }
}
