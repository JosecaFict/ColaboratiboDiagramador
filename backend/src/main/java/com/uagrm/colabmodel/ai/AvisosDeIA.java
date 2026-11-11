package com.uagrm.colabmodel.ai;

import com.uagrm.colabmodel.ai.imagenes.ImagenMeta;
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

    public enum Tipo { NUEVA, APLICADA, IMAGEN_SUBIDA, IMAGEN_ELIMINADA }

    /**
     * NUEVA y APLICADA traen `entrada`; IMAGEN_SUBIDA e IMAGEN_ELIMINADA traen `imagen` y no `entrada`
     * (con `non_null` el que falta no sale en el JSON). docs/DISENO-CHAT-IMAGENES.md, seccion 6.
     */
    public record Aviso(Tipo tipo, HistorialIA.Entrada entrada, ImagenMeta imagen) {
        public Aviso(Tipo tipo, HistorialIA.Entrada entrada) {
            this(tipo, entrada, null);
        }
    }

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

    /** Una imagen NUEVA (un duplicado no avisa). Se manda despues del commit. */
    public void imagenSubida(UUID diagramId, ImagenMeta imagen) {
        broker.convertAndSend(topico(diagramId), new Aviso(Tipo.IMAGEN_SUBIDA, null, imagen));
    }

    /** `imagen.eliminada` es true. */
    public void imagenEliminada(UUID diagramId, ImagenMeta imagen) {
        broker.convertAndSend(topico(diagramId), new Aviso(Tipo.IMAGEN_ELIMINADA, null, imagen));
    }
}
