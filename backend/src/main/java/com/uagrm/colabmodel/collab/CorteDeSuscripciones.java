package com.uagrm.colabmodel.collab;

import org.springframework.context.ApplicationContext;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

/**
 * Cancela una suscripcion STOMP desde el servidor.
 *
 * Un cliente se suscribe a `/topic/diagram/{id}/ops` una vez, y el broker le
 * sigue mandando todo lo que se publique ahi hasta que el propio cliente se
 * desuscriba. Cuando a alguien lo sacan de un proyecto no se puede esperar a
 * que el cliente coopere: hay que dejarlo sin suscripcion desde el servidor.
 *
 * El broker no expone eso como metodo, pero su registro de suscripciones si
 * acepta un mensaje UNSUBSCRIBE armado a mano, que es exactamente lo que el
 * broker recibe cuando el cliente se desuscribe. Se busca el broker a la hora
 * de usarlo y no al construir: es un bean que Spring arma con la configuracion
 * de mensajeria, y pedirlo en el constructor ordenaria el arranque al reves.
 */
@Component
public class CorteDeSuscripciones {

    private final ApplicationContext contexto;

    public CorteDeSuscripciones(ApplicationContext contexto) {
        this.contexto = contexto;
    }

    public void cancelar(String sesion, String subscriptionId) {
        SimpleBrokerMessageHandler broker =
                contexto.getBean("simpleBrokerMessageHandler", SimpleBrokerMessageHandler.class);
        SimpMessageHeaderAccessor cabeceras = SimpMessageHeaderAccessor.create(SimpMessageType.UNSUBSCRIBE);
        cabeceras.setSessionId(sesion);
        cabeceras.setSubscriptionId(subscriptionId);
        broker.getSubscriptionRegistry()
                .unregisterSubscription(MessageBuilder.createMessage(new byte[0], cabeceras.getMessageHeaders()));
    }
}
