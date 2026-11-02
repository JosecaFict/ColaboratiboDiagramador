package com.uagrm.colabmodel.ai;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Difunde el historial de la IA a todas las pestañas abiertas del diagrama
 * por `/topic/diagram/{id}/ai`. Lo leen los tres roles, igual que `ops`.
 *
 * Cada aviso lleva la entrada completa: el cliente la reemplaza por id, así que
 * recibirla dos veces, o antes que la respuesta HTTP, da el mismo resultado.
 */
@Component
public class AvisosDeIA {

    public enum Tipo { NUEVA, APLICADA }

    public record Aviso(Tipo tipo, HistorialIA.Entrada entrada) { }

    private final SimpMessagingTemplate broker;

    public AvisosDeIA(SimpMessagingTemplate broker) {
        this.broker = broker;
    }

    public static String topico(UUID diagramId) {
        return "/topic/diagram/" + diagramId + "/ai";
    }

    void nueva(UUID diagramId, HistorialIA.Entrada entrada) {
        broker.convertAndSend(topico(diagramId), new Aviso(Tipo.NUEVA, entrada));
    }

    void aplicada(UUID diagramId, HistorialIA.Entrada entrada) {
        broker.convertAndSend(topico(diagramId), new Aviso(Tipo.APLICADA, entrada));
    }
}
