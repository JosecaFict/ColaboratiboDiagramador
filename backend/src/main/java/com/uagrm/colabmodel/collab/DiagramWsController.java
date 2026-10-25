package com.uagrm.colabmodel.collab;

import com.uagrm.colabmodel.modelcore.command.*;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.util.UUID;

/**
 * Puerta de entrada del plano de datos.
 *
 *   Cliente ->  /app/diagram/{id}/op        (Command)
 *               /app/diagram/{id}/presence  (efimero)
 *               /app/diagram/{id}/lock  |  /unlock
 *
 *   Servidor -> /topic/diagram/{id}/ops
 *               /topic/diagram/{id}/presence
 *               /topic/diagram/{id}/locks
 *               /user/queue/errors          (cola PRIVADA de la sesion que fallo)
 *
 * FASE 1: la identidad sale del JWT validado en el CONNECT
 * (StompAuthChannelInterceptor), no del payload del mensaje. Un cliente ya
 * no puede mandar comandos "como" otro userId.
 */
@Controller
public class DiagramWsController {

    private final CommandBus bus;
    private final LockManager locks;
    private final SimpMessagingTemplate broker;

    public DiagramWsController(CommandBus bus, LockManager locks, SimpMessagingTemplate broker) {
        this.bus = bus;
        this.locks = locks;
        this.broker = broker;
    }

    @MessageMapping("/diagram/{diagramId}/op")
    public void onCommand(@DestinationVariable String diagramId, Command command, Principal principal,
                          @Header(SimpMessageHeaderAccessor.SESSION_ID_HEADER) String sesion) {
        try {
            bus.submit(command, new Autor(autor(principal), sesion));
        } catch (CommandRejectedException e) {
            // A la SESION que lo provoco, no a todas las del usuario. Con
            // `convertAndSendToUser` a secas, el rechazo de una pestana le
            // llegaba tambien a la otra del mismo usuario, que mostraba
            // "Ese elemento lo esta editando ..." con su propio nombre.
            SimpMessageHeaderAccessor destino = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
            destino.setSessionId(sesion);
            destino.setLeaveMutable(true);
            broker.convertAndSendToUser(principal.getName(), "/queue/errors", e.toRejection(),
                    destino.getMessageHeaders());
        }
    }

    @MessageMapping("/diagram/{diagramId}/presence")
    public void onPresence(@DestinationVariable String diagramId, PresenceMessage msg, Principal principal) {
        broker.convertAndSend("/topic/diagram/" + diagramId + "/presence",
                new PresenceMessage(autor(principal), msg.userName(), msg.x(), msg.y(), msg.selectedElementId()));
    }

    @MessageMapping("/diagram/{diagramId}/lock")
    public void onLock(@DestinationVariable String diagramId, LockMessage msg, Principal principal,
                       @Header(SimpMessageHeaderAccessor.SESSION_ID_HEADER) String sesion) {
        locks.acquire(UUID.fromString(diagramId), msg.elementId(), autor(principal), sesion, msg.userName());
        difundirEstado(diagramId, msg.elementId());
    }

    @MessageMapping("/diagram/{diagramId}/unlock")
    public void onUnlock(@DestinationVariable String diagramId, LockMessage msg, Principal principal,
                         @Header(SimpMessageHeaderAccessor.SESSION_ID_HEADER) String sesion) {
        locks.release(UUID.fromString(diagramId), msg.elementId(), sesion);
        difundirEstado(diagramId, msg.elementId());
    }

    /**
     * Difunde quien tiene REALMENTE el lease despues del intento, no lo que se
     * pidio.
     *
     * Antes se difundia el resultado del pedido tal cual: si otro no lograba
     * tomar el lock, salia un "held=false" y todas las pantallas borraban el
     * "editando: ..." del que SI lo tenia. Lo mismo pasaba al soltar un lock
     * ajeno. La pantalla quedaba mintiendo sobre quien estaba editando.
     */
    private void difundirEstado(String diagramId, UUID elementId) {
        LockMessage estado = locks.holderOf(UUID.fromString(diagramId), elementId)
                .map(l -> new LockMessage(elementId, l.userId(), l.userName(), true))
                .orElseGet(() -> new LockMessage(elementId, null, null, false));
        broker.convertAndSend("/topic/diagram/" + diagramId + "/locks", estado);
    }

    /**
     * La identidad SIEMPRE sale del JWT, nunca del cuerpo del mensaje. Si se
     * confiara en el `userId` del payload, cualquiera podria soltar el lock de
     * otro y romper la exclusion mutua de nivel 2 (ADR-004).
     */
    private UUID autor(Principal principal) {
        return UUID.fromString(principal.getName());
    }
}
