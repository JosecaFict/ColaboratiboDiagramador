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

    private static ObjectNode clase(String id, String nombre) {
        return J.objectNode().put("classId", id).put("name", nombre);
    }

    private static PropuestaDeIA.ComandoAceptado aceptado(CommandType tipo, ObjectNode payload) {
        return new PropuestaDeIA.ComandoAceptado(tipo, payload, tipo.name());
    }
}
