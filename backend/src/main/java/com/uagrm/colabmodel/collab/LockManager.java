package com.uagrm.colabmodel.collab;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * EXCLUSION MUTUA - NIVEL 2 (semantica), segun ADR-004.
 *
 * Lease por ENTIDAD con TTL y renovacion por latido. La granularidad es la
 * entidad, no el atributo: agregar o renombrar un atributo modifica la
 * estructura de la entidad, y un lock mas fino duplicaria la complejidad
 * para una ganancia marginal.
 *
 * El dueno de un lease es una SESION de edicion, no un usuario. Antes era el
 * usuario, y dos pestanas de la misma persona contaban como un solo escritor:
 * las dos editaban la misma clase sin que el servidor rechazara ninguna, y
 * cerrar una soltaba los locks de la otra. Es exactamente el caso con el que
 * se muestra EC-01 en la demo, asi que el mecanismo no se estaba ejercitando.
 *
 * Los otros tres niveles NO pasan por aqui:
 *   Nivel 1 (estructura) - sin lock, operaciones conmutativas con UUID de cliente
 *   Nivel 3 (geometria)  - Last-Writer-Wins
 *   Nivel 4 (presencia)  - canal efimero, nunca toca el modelo
 *
 * En memoria porque somos un monolito de una instancia (ADR-001). Para
 * multi-instancia esto se mueve a Redis sin cambiar la interfaz.
 */
@Component
public class LockManager {

    /**
     * @param userId   quien, para mostrarlo y auditarlo
     * @param sesion   la pestana o cliente que lo tiene: es lo que decide
     */
    public record Lease(UUID userId, String sesion, String userName, Instant expiresAt) {}

    /** Un lease que se solto al cerrarse su sesion: hay que avisarle al resto. */
    public record Liberado(UUID diagramId, UUID elementId) {}

    private final Map<UUID, Map<UUID, Lease>> byDiagram = new ConcurrentHashMap<>();
    private final long ttlSeconds;

    public LockManager(@Value("${colabmodel.collab.lock-ttl-seconds:30}") long ttlSeconds) {
        this.ttlSeconds = ttlSeconds;
    }

    /**
     * Toma el lease o lo renueva si ya es de esta sesion.
     *
     * @return true si esta sesion quedo como duena
     */
    public boolean acquire(UUID diagramId, UUID elementId, UUID userId, String sesion, String userName) {
        if (sesion == null) return false;   // sin sesion no hay a quien renovarle el latido
        Map<UUID, Lease> locks = byDiagram.computeIfAbsent(diagramId, k -> new ConcurrentHashMap<>());
        Instant now = Instant.now();
        Lease result = locks.compute(elementId, (k, current) -> {
            boolean libre = current == null
                    || current.expiresAt().isBefore(now)
                    || current.sesion().equals(sesion);
            return libre ? new Lease(userId, sesion, userName, now.plusSeconds(ttlSeconds)) : current;
        });
        return result != null && sesion.equals(result.sesion());
    }

    /** Solo la sesion duena puede soltarlo: pedirlo desde otra no hace nada. */
    public void release(UUID diagramId, UUID elementId, String sesion) {
        Map<UUID, Lease> locks = byDiagram.get(diagramId);
        if (locks == null || sesion == null) return;
        locks.computeIfPresent(elementId, (k, current) -> current.sesion().equals(sesion) ? null : current);
    }

    /**
     * Se llama en SessionDisconnectEvent: sin esto, un cierre de laptop congela
     * una entidad. Suelta SOLO los de esa sesion: la otra pestana del mismo
     * usuario sigue editando.
     *
     * @return lo que se libero, para difundirlo; si no, los demas siguen viendo
     *         "editando: ..." sobre una entidad que ya esta libre
     */
    public List<Liberado> releaseAllOf(String sesion) {
        List<Liberado> liberados = new ArrayList<>();
        if (sesion == null) return liberados;
        byDiagram.forEach((diagramId, locks) ->
                locks.entrySet().removeIf(e -> {
                    boolean suyo = e.getValue().sesion().equals(sesion);
                    if (suyo) liberados.add(new Liberado(diagramId, e.getKey()));
                    return suyo;
                }));
        return liberados;
    }

    /**
     * Suelta TODO lo que un usuario tiene tomado en esos diagramas, sea cual sea
     * la pestana. Es lo que pasa cuando deja de poder escribir --lo pasaron a
     * VIEWER o lo quitaron del proyecto (ADR-026)--: su sesion sigue abierta, asi
     * que `releaseAllOf` no lo alcanza, y sin esto dejaria clases "en edicion"
     * hasta que su latido dejara de renovarlas.
     *
     * @return lo que se libero, para difundirlo
     */
    public List<Liberado> releaseAllOfUser(Collection<UUID> diagramIds, UUID userId) {
        List<Liberado> liberados = new ArrayList<>();
        if (userId == null) return liberados;
        for (UUID diagramId : diagramIds) {
            Map<UUID, Lease> locks = byDiagram.get(diagramId);
            if (locks == null) continue;
            locks.entrySet().removeIf(e -> {
                boolean suyo = e.getValue().userId().equals(userId);
                if (suyo) liberados.add(new Liberado(diagramId, e.getKey()));
                return suyo;
            });
        }
        return liberados;
    }

    /** El diagrama se elimino: no queda nada que bloquear en el. */
    public void forget(Collection<UUID> diagramIds) {
        diagramIds.forEach(byDiagram::remove);
    }

    /** @return true si el elemento esta libre, o si el lease es de esta sesion. */
    public boolean canWrite(UUID diagramId, UUID elementId, String sesion) {
        return holderOf(diagramId, elementId)
                .map(lease -> lease.sesion().equals(sesion))
                .orElse(true);
    }

    public Optional<Lease> holderOf(UUID diagramId, UUID elementId) {
        Map<UUID, Lease> locks = byDiagram.get(diagramId);
        if (locks == null) return Optional.empty();
        Lease lease = locks.get(elementId);
        if (lease == null || lease.expiresAt().isBefore(Instant.now())) return Optional.empty();
        return Optional.of(lease);
    }

    public Map<UUID, Lease> snapshot(UUID diagramId) {
        Instant now = Instant.now();
        Map<UUID, Lease> locks = byDiagram.getOrDefault(diagramId, Map.of());
        Map<UUID, Lease> vivos = new HashMap<>();
        locks.forEach((k, v) -> { if (v.expiresAt().isAfter(now)) vivos.put(k, v); });
        return vivos;
    }
}
