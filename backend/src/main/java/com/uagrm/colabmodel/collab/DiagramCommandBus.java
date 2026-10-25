package com.uagrm.colabmodel.collab;

import com.uagrm.colabmodel.modelcore.command.*;
import com.uagrm.colabmodel.project.AccesoAProyecto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

/**
 * BUS UNICO DE ESCRITURA (ADR-002 + ADR-005).
 *
 * Un ejecutor de UN SOLO HILO por diagramId. Las operaciones de un mismo
 * diagrama se serializan entre si; distintos diagramas avanzan en paralelo.
 *
 * Es la respuesta simple y explicable a la exclusion mutua a nivel de
 * servidor, y evita tener que razonar sobre condiciones de carrera en el
 * resto del sistema.
 */
@Component
public class DiagramCommandBus implements CommandBus {

    private static final Logger log = LoggerFactory.getLogger(DiagramCommandBus.class);

    private final Map<UUID, ExecutorService> ejecutores = new ConcurrentHashMap<>();
    private final CommandApplier applier;
    private final LockManager locks;
    private final SimpMessagingTemplate broker;
    private final AccesoAProyecto acceso;
    private final EntidadBloqueable entidades;

    public DiagramCommandBus(CommandApplier applier, LockManager locks, SimpMessagingTemplate broker,
                             AccesoAProyecto acceso, EntidadBloqueable entidades) {
        this.applier = applier;
        this.locks = locks;
        this.broker = broker;
        this.acceso = acceso;
        this.entidades = entidades;
    }

    @Override
    public AppliedOperation submit(Command command, Autor autor) {
        ExecutorService hilo = ejecutores.computeIfAbsent(
                command.diagramId(),
                id -> Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "diagrama-" + id);
                    t.setDaemon(true);
                    return t;
                }));

        try {
            AppliedOperation aplicada = hilo.submit(() -> {
                verificarPermiso(command, autor.userId());
                if (command.type() == CommandType.BATCH) {
                    // El permiso ya se verifico una vez; los locks, cambio por
                    // cambio: un lote no puede escribir sobre una clase que otro
                    // esta editando, igual que un comando suelto.
                    Lote.de(command).paraCadaCambio(command, c -> verificarLock(c, autor));
                } else {
                    verificarLock(command, autor);
                }
                return applier.apply(command, autor.userId());
            }).get(10, TimeUnit.SECONDS);

            broker.convertAndSend("/topic/diagram/" + command.diagramId() + "/ops", aplicada);
            log.debug("op seq={} type={} origin={}", aplicada.seq(), aplicada.type(), aplicada.origin());
            return aplicada;

        } catch (ExecutionException e) {
            if (e.getCause() instanceof CommandRejectedException cre) throw cre;
            throw new CommandRejectedException(command.opId(), RejectionReason.INVALID, e.getCause().getMessage());
        } catch (TimeoutException e) {
            throw new CommandRejectedException(command.opId(), RejectionReason.INVALID, "Tiempo agotado");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CommandRejectedException(command.opId(), RejectionReason.INVALID, "Interrumpido");
        }
    }

    /** El diagrama se elimino: su hilo ya no tiene nada que serializar. */
    public void olvidar(java.util.Collection<UUID> diagramIds) {
        for (UUID id : diagramIds) {
            ExecutorService hilo = ejecutores.remove(id);
            if (hilo != null) hilo.shutdown();
        }
    }

    /**
     * EC-11: un VIEWER no puede escribir, ni siquiera llamando la API a mano.
     * La regla de rol vive en AccesoAProyecto, compartida con el camino de
     * lectura: una sola definicion de quien puede que.
     *
     * Se consulta en CADA comando, sin cache: es lo que hace que un cambio de
     * rol se aplique en caliente (ADR-026). El proximo comando de quien acaba de
     * pasar a VIEWER --o de quien fue quitado del proyecto-- ya se rechaza.
     */
    private void verificarPermiso(Command command, UUID userId) {
        AccesoAProyecto.Rol rol = acceso.rolSobreDiagrama(command.diagramId(), userId)
                .orElseThrow(() -> new CommandRejectedException(command.opId(),
                        RejectionReason.FORBIDDEN, "No sos miembro de este proyecto"));

        if (!acceso.puedeEditar(rol)) {
            throw new CommandRejectedException(command.opId(), RejectionReason.FORBIDDEN,
                    "Los " + rol + " no pueden editar (EC-11)");
        }
    }

    /**
     * Nivel 2 de la exclusion mutua. Solo los comandos que tocan SEMANTICA
     * necesitan lease. Crear y borrar (nivel 1) y mover (nivel 3) no.
     */
    private void verificarLock(Command command, Autor autor) {
        // UN LOTE SE REVISA COMANDO POR COMANDO.
        //
        // `requiereLease(BATCH)` es false, asi que sin esto un BATCH pasaba sin
        // ningun control: bastaba meterle adentro un ADD_ATTRIBUTE sobre una
        // clase ajena para escribirla mientras otra persona la tenia tomada.
        // El lote no es una excepcion a la exclusion mutua, es un sobre.
        if (command.type() == CommandType.BATCH) {
            for (var nodo : command.payload().path("comandos")) {
                String tipo = nodo.path("type").asText(null);
                if (tipo == null) continue;          // lo rechaza el applier, con mejor mensaje
                try {
                    verificarLockDe(command, CommandType.valueOf(tipo), nodo.path("payload"), autor);
                } catch (IllegalArgumentException e) {
                    // Tipo fuera del catalogo: tambien es cosa del applier.
                }
            }
            return;
        }
        verificarLockDe(command, command.type(), command.payload(), autor);
    }

    private void verificarLockDe(Command command, CommandType tipo,
                                 com.fasterxml.jackson.databind.JsonNode payload, Autor autor) {
        // Que entidad pide lease lo decide EntidadBloqueable leyendo el modelo,
        // no el nombre de un campo del payload: un UPDATE_ATTRIBUTE no trae
        // classId y antes pasaba sin ningun control (ADR-028).
        var entidad = entidades.de(tipo, payload);
        if (entidad.isEmpty()) return;

        UUID id = entidad.get();
        // Por SESION, no por usuario: la otra pestana de la misma persona es
        // otro escritor y tiene que recibir el mismo rechazo que un extrano.
        if (!locks.canWrite(command.diagramId(), id, autor.sesion())) {
            String duenio = locks.holderOf(command.diagramId(), id)
                    .map(LockManager.Lease::userName).orElse("otro usuario");
            throw new CommandRejectedException(command.opId(), RejectionReason.LOCKED_BY_OTHER,
                    "Ese elemento lo esta editando " + duenio);
        }
    }
}
