package com.uagrm.colabmodel.collab;

import com.uagrm.colabmodel.iam.JwtService;
import com.uagrm.colabmodel.project.AccesoAProyecto;
import io.jsonwebtoken.JwtException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

import java.security.Principal;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Autentica el CONNECT de STOMP y autoriza lo que sigue (Fase 1, ADR-026).
 *
 * La identidad NO sale del payload del comando (eso era el atajo de Fase 0, ver
 * comentario historico en DiagramWsController): sale de aqui, del JWT en el
 * header "Authorization" del frame CONNECT. Sin esto, EC-11 (un VIEWER no puede
 * escribir "ni llamando la API a mano") seria imposible de cumplir: cualquiera
 * podria mandar el userId que quisiera en el mensaje.
 *
 * Autenticar el CONNECT no alcanza. Sin mirar el SUBSCRIBE, cualquier usuario
 * con un token valido podia suscribirse a `/topic/diagram/{id}/...` de un
 * proyecto ajeno con solo saber el id, y leer en vivo todo lo que otros
 * editaban. Aca se autoriza cada SUBSCRIBE con `AccesoAProyecto`: la misma
 * regla que decide quien lee por REST. Y el envio a `lock` y `presence`, que
 * tampoco pasa por el bus (que es donde se controla `op`), se autoriza igual: un
 * VIEWER o un ajeno no toma locks ni difunde cursores.
 *
 * Un SUBSCRIBE no autorizado corta la conexion con un frame ERROR (Spring lo
 * hace con cualquier excepcion del canal). Un SEND no autorizado se descarta en
 * silencio: es lo que manda una pestana que aun no se entero de que la pasaron a
 * VIEWER, y no vale la pena tumbarle la conexion por un latido de lock.
 */
@Component
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    private static final Logger log = LoggerFactory.getLogger(StompAuthChannelInterceptor.class);

    private static final String UUID_TEXTO = "([0-9a-fA-F-]{36})";
    private static final Pattern TOPIC_DE_DIAGRAMA =
            Pattern.compile("^/topic/diagram/" + UUID_TEXTO + "/(ops|presence|locks)$");
    private static final Pattern ENVIO_A_DIAGRAMA =
            Pattern.compile("^/app/diagram/" + UUID_TEXTO + "/(op|presence|lock|unlock)$");

    private final JwtService jwt;
    private final WsSessionRegistry sesiones;
    private final AccesoAProyecto acceso;

    public StompAuthChannelInterceptor(JwtService jwt, WsSessionRegistry sesiones, AccesoAProyecto acceso) {
        this.jwt = jwt;
        this.sesiones = sesiones;
        this.acceso = acceso;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) return message;

        StompCommand comando = accessor.getCommand();
        if (StompCommand.CONNECT.equals(comando)) {
            autenticar(accessor);
        } else if (StompCommand.SUBSCRIBE.equals(comando)) {
            autorizarSuscripcion(accessor);
        } else if (StompCommand.UNSUBSCRIBE.equals(comando)) {
            sesiones.desuscribir(accessor.getSessionId(), accessor.getSubscriptionId());
        } else if (StompCommand.SEND.equals(comando) && !envioPermitido(accessor)) {
            log.debug("Envio descartado a {} de la sesion {}", accessor.getDestination(), accessor.getSessionId());
            return null;
        }
        return message;
    }

    private void autenticar(StompHeaderAccessor accessor) {
        String header = accessor.getFirstNativeHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            throw new MessagingException("Falta el header Authorization en el CONNECT");
        }
        try {
            UUID userId = jwt.validarYObtenerUserId(header.substring(7));
            Principal principal = userId::toString;
            accessor.setUser(principal);
            sesiones.registrar(accessor.getSessionId(), userId);
        } catch (JwtException | IllegalArgumentException e) {
            throw new MessagingException("Token invalido: " + e.getMessage(), e);
        }
    }

    /**
     * Los topics de un diagrama los puede leer quien lee el diagrama. Las colas
     * privadas (`/user/queue/...`) las resuelve Spring por sesion: cada quien
     * recibe solo lo suyo. Todo lo demas se niega: lo unico que el servidor
     * publica es eso, y un destino inventado no tiene por que existir.
     */
    private void autorizarSuscripcion(StompHeaderAccessor accessor) {
        String destino = accessor.getDestination();
        if (destino == null) throw new MessagingException("Suscripcion sin destino");

        if (!destino.startsWith("/user/queue/")) {
            Matcher m = TOPIC_DE_DIAGRAMA.matcher(destino);
            if (!m.matches()) throw new MessagingException("Destino no permitido: " + destino);

            Optional<AccesoAProyecto.Rol> rol = rolDe(accessor, m.group(1));
            if (rol.isEmpty()) throw new MessagingException("No sos miembro de este proyecto");
        }
        sesiones.suscribir(accessor.getSessionId(), accessor.getSubscriptionId(), destino);
    }

    /** `op` lo controla el bus (con su rechazo privado); `lock`, `unlock` y `presence`, aca. */
    private boolean envioPermitido(StompHeaderAccessor accessor) {
        String destino = accessor.getDestination();
        Matcher m = destino == null ? null : ENVIO_A_DIAGRAMA.matcher(destino);
        if (m == null || !m.matches()) return true;   // no es de un diagrama: no es cosa de este control

        String diagrama = m.group(1);
        switch (m.group(2)) {
            case "op":
                return true;
            case "presence":
                // Los cursores son cientos de mensajes por segundo: consultar la
                // base en cada uno seria el error. Alcanza con mirar que la sesion
                // este suscrita a la presencia de ese diagrama: para suscribirse
                // ya paso por AccesoAProyecto, y al sacar a alguien del proyecto se
                // le cancela la suscripcion, asi que el permiso se apaga solo.
                return sesiones.estaSuscrita(accessor.getSessionId(), "/topic/diagram/" + diagrama + "/presence");
            case "lock":
                // Tomar un lock es prepararse para escribir: solo quien puede escribir.
                return rolDe(accessor, diagrama).filter(acceso::puedeEditar).isPresent();
            default:   // unlock: soltar lo propio es inofensivo, pero sigue siendo cosa de miembros
                return rolDe(accessor, diagrama).isPresent();
        }
    }

    private Optional<AccesoAProyecto.Rol> rolDe(StompHeaderAccessor accessor, String diagramaTexto) {
        Principal quien = accessor.getUser();
        if (quien == null) return Optional.empty();
        try {
            return acceso.rolSobreDiagrama(UUID.fromString(diagramaTexto), UUID.fromString(quien.getName()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();   // un id que no es un UUID: no es de nadie
        }
    }
}
