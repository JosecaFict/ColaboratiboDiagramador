package com.uagrm.colabmodel.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Que la memoria del equipo quepa en el contexto del modelo (T-09).
 *
 * Estimacion: 1 token cada 3 caracteres, sobre la instruccion de sistema, el
 * JSON de las herramientas y el mensaje de usuario completo (memoria, quien
 * pregunta, pedido y diagrama). Es conservadora a proposito: en espanol y en
 * JSON un token suele ser de 3 a 4 caracteres, y pasarse corta el contexto en
 * silencio. Ademas se reserva lugar para la respuesta.
 *
 * Primero se sacan los turnos mas viejos. Si aun sin memoria el contenido
 * obligatorio no entra, se avisa con {@link ContextoExcedidoException} antes de
 * llamar al proveedor: no se corta el pedido ni el diagrama.
 */
final class PresupuestoDeContexto {

    static final int CARACTERES_POR_TOKEN = 3;

    private final int tokens;
    private final int reservaRespuesta;
    private final ObjectMapper mapper;

    PresupuestoDeContexto(int tokens, int reservaRespuesta, ObjectMapper mapper) {
        this.tokens = tokens;
        this.reservaRespuesta = reservaRespuesta;
        this.mapper = mapper;
    }

    int disponibles() {
        return tokens - reservaRespuesta;
    }

    static int estimar(int caracteres) {
        return (caracteres + CARACTERES_POR_TOKEN - 1) / CARACTERES_POR_TOKEN;
    }

    int estimar(SolicitudIA s) {
        return estimar(s.instruccionDeSistema().length() + herramientas(s).length() + s.mensajeParaElModelo().length());
    }

    /**
     * La solicitud con todos los turnos que entran, empezando por los mas
     * recientes. Sin equipo, solo comprueba que el contenido obligatorio entre.
     */
    SolicitudIA ajustar(SolicitudIA s) {
        ContextoDeEquipo equipo = s.equipo();
        if (equipo == null) {
            exigirQueEntre(s);
            return s;
        }
        SolicitudIA sinMemoria = s.conEquipo(equipo.conTurnos(List.of(), 0));
        exigirQueEntre(sinMemoria);
        if (estimar(s) <= disponibles()) return s;

        List<ContextoDeEquipo.Turno> todos = equipo.turnos();
        int omitidosPrevios = equipo.turnosOmitidos();
        // Se agregan de a uno desde el mas reciente mientras entren.
        List<ContextoDeEquipo.Turno> elegidos = new ArrayList<>();
        SolicitudIA mejor = sinMemoria.conEquipo(equipo.conTurnos(List.of(), omitidosPrevios + todos.size()));
        for (int i = todos.size() - 1; i >= 0; i--) {
            elegidos.add(0, todos.get(i));
            int omitidos = omitidosPrevios + i;
            SolicitudIA candidata = s.conEquipo(equipo.conTurnos(List.copyOf(elegidos), omitidos));
            if (estimar(candidata) > disponibles()) break;
            mejor = candidata;
        }
        return mejor;
    }

    private void exigirQueEntre(SolicitudIA s) {
        int necesarios = estimar(s);
        if (necesarios > disponibles()) {
            throw new ContextoExcedidoException("El pedido y el diagrama no entran en el contexto del modelo: hacen falta"
                    + " unos " + necesarios + " tokens y hay " + disponibles() + " (de " + tokens + ", reservando "
                    + reservaRespuesta + " para la respuesta). Acorta el pedido o usa un modelo con mas contexto.");
        }
    }

    private String herramientas(SolicitudIA s) {
        if (s.herramientas().isEmpty()) return "";
        try {
            return mapper.writeValueAsString(s.herramientas());
        } catch (JsonProcessingException e) {
            return String.valueOf(s.herramientas());
        }
    }
}
