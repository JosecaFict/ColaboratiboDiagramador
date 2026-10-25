package com.uagrm.colabmodel.collab;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

/**
 * Sin esto, un usuario que cierra la laptop deja una entidad bloqueada
 * para siempre. Riesgo R-12 del documento de arquitectura.
 */
@Component
public class SessionListener {

    private static final Logger log = LoggerFactory.getLogger(SessionListener.class);
    private final LockManager locks;
    private final WsSessionRegistry sesiones;
    private final SimpMessagingTemplate broker;

    public SessionListener(LockManager locks, WsSessionRegistry sesiones, SimpMessagingTemplate broker) {
        this.locks = locks;
        this.sesiones = sesiones;
        this.broker = broker;
    }

    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        log.debug("Sesion WS desconectada: {}", event.getSessionId());
        sesiones.quitar(event.getSessionId());
        // Solo los de ESA sesion: la otra pestana del mismo usuario sigue
        // editando. Y se avisa: si no, las demas pantallas siguen mostrando
        // "editando: ..." sobre algo que ya esta libre, sin fecha de fin.
        for (LockManager.Liberado l : locks.releaseAllOf(event.getSessionId())) {
            broker.convertAndSend("/topic/diagram/" + l.diagramId() + "/locks",
                    new LockMessage(l.elementId(), null, null, false));
        }
    }
}
