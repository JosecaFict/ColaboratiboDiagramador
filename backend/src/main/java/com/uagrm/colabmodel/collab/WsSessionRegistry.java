package com.uagrm.colabmodel.collab;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Lo que el servidor sabe de cada sesion STOMP abierta:
 *
 *   - que usuario hay detras (autenticado en el CONNECT, por
 *     StompAuthChannelInterceptor);
 *   - a que esta suscrita (registrado en el SUBSCRIBE, ya autorizado).
 *
 * Los locks ya no necesitan lo primero: se identifican por la sesion misma.
 * Lo segundo existe para poder CORTAR: si a alguien lo sacan de un proyecto, sus
 * suscripciones a los diagramas de ese proyecto se cancelan y deja de recibir
 * operaciones, aunque siga conectado (ADR-026). El broker en memoria de Spring
 * no ofrece "que esta suscrito el usuario X", asi que se lleva la cuenta aca.
 *
 * Se limpia al desconectar, para no crecer sin limite.
 */
@Component
public class WsSessionRegistry {

    /** Una suscripcion viva: de que sesion, con que id, de que usuario, a que destino. */
    public record Suscripcion(String sesion, String id, UUID usuario, String destino) { }

    private final Map<String, UUID> porSesion = new ConcurrentHashMap<>();
    private final Map<String, Map<String, String>> suscripciones = new ConcurrentHashMap<>();

    public void registrar(String sessionId, UUID userId) {
        porSesion.put(sessionId, userId);
    }

    public Optional<UUID> quitar(String sessionId) {
        suscripciones.remove(sessionId);
        return Optional.ofNullable(porSesion.remove(sessionId));
    }

    public void suscribir(String sessionId, String subscriptionId, String destino) {
        if (sessionId == null || subscriptionId == null) return;
        suscripciones.computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>()).put(subscriptionId, destino);
    }

    public void desuscribir(String sessionId, String subscriptionId) {
        if (sessionId == null || subscriptionId == null) return;
        Map<String, String> deLaSesion = suscripciones.get(sessionId);
        if (deLaSesion != null) deLaSesion.remove(subscriptionId);
    }

    /** true si esa sesion tiene ahora mismo una suscripcion a ese destino. */
    public boolean estaSuscrita(String sessionId, String destino) {
        if (sessionId == null) return false;
        Map<String, String> deLaSesion = suscripciones.get(sessionId);
        return deLaSesion != null && deLaSesion.containsValue(destino);
    }

    /** Las suscripciones vivas que cumplen `filtro`. */
    public List<Suscripcion> suscripcionesQue(Predicate<Suscripcion> filtro) {
        List<Suscripcion> encontradas = new ArrayList<>();
        suscripciones.forEach((sesion, deLaSesion) -> {
            UUID usuario = porSesion.get(sesion);
            deLaSesion.forEach((id, destino) -> {
                Suscripcion s = new Suscripcion(sesion, id, usuario, destino);
                if (filtro.test(s)) encontradas.add(s);
            });
        });
        return encontradas;
    }
}
