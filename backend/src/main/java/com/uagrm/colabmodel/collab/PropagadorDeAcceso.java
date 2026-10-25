package com.uagrm.colabmodel.collab;

import com.uagrm.colabmodel.project.AccesoAProyecto;
import com.uagrm.colabmodel.project.CambioDeAcceso;
import com.uagrm.colabmodel.project.RecursoEliminado;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Aplica "en caliente" un cambio de acceso a un proyecto (ADR-026): sin que
 * nadie recargue, sin esperar a que se venza nada.
 *
 * Cuando el acceso de alguien cambia --le cambian el rol, lo agregan, lo
 * quitan, se va, transfieren la propiedad-- pasan cuatro cosas a la vez:
 *
 *  1. Su PROXIMO comando se evalua con el rol nuevo. Eso no lo hace esta clase:
 *     `DiagramCommandBus` consulta el rol en cada comando, sin cache.
 *  2. Si ya no puede escribir, se le sueltan los locks y se difunde, para que
 *     nadie siga viendo "editando: ..." sobre algo que quedo libre.
 *  3. Si ya no tiene acceso, se le CANCELAN las suscripciones a los diagramas:
 *     sus sesiones siguen conectadas pero dejan de recibir operaciones.
 *  4. Recibe un aviso privado con su rol nuevo o con la baja, en todas sus
 *     pestanas, y su interfaz se adapta.
 *
 * Se ejecuta DESPUES del commit de quien lo provoco. Si la transaccion se
 * deshace --una transferencia que falla a la mitad--, nada de esto ocurre: no
 * se le avisa a nadie de algo que no paso.
 *
 * Escucha eventos de `project` en vez de ser llamada por el, porque `collab` ya
 * depende de `project` y lo contrario cerraria un ciclo (EC-12).
 */
@Component
public class PropagadorDeAcceso {

    private static final Logger log = LoggerFactory.getLogger(PropagadorDeAcceso.class);

    static final String COLA_DE_AVISOS = "/queue/membresia";

    private final AccesoAProyecto acceso;
    private final LockManager locks;
    private final WsSessionRegistry sesiones;
    private final CorteDeSuscripciones corte;
    private final DiagramCommandBus bus;
    private final SimpMessagingTemplate broker;

    public PropagadorDeAcceso(AccesoAProyecto acceso, LockManager locks, WsSessionRegistry sesiones,
                              CorteDeSuscripciones corte, DiagramCommandBus bus, SimpMessagingTemplate broker) {
        this.acceso = acceso;
        this.locks = locks;
        this.sesiones = sesiones;
        this.corte = corte;
        this.bus = bus;
        this.broker = broker;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void alCambiarElAcceso(CambioDeAcceso cambio) {
        boolean sigueEscribiendo = cambio.rol() != null && acceso.puedeEditar(cambio.rol());
        boolean sinAcceso = cambio.rol() == null;
        log.debug("Acceso de {} a {}: {} -> {}", cambio.usuarioId(), cambio.proyectoId(),
                cambio.motivo(), cambio.rol());

        if (!sigueEscribiendo) soltarLocks(cambio.diagramas(), cambio.usuarioId());
        if (sinAcceso) cortarSuscripciones(cambio.diagramas(), cambio.usuarioId());

        avisar(cambio.usuarioId(), new AvisoDeAcceso(cambio.proyectoId(), cambio.proyectoNombre(),
                cambio.motivo().name(), cambio.rol() == null ? null : cambio.rol().name(), null));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void alEliminarse(RecursoEliminado eliminado) {
        log.debug("Eliminado {} de {}", eliminado.fueElProyecto() ? "el proyecto" : "un diagrama",
                eliminado.proyectoId());

        locks.forget(eliminado.diagramas());
        bus.olvidar(eliminado.diagramas());
        cortarSuscripciones(eliminado.diagramas(), null);

        String motivo = eliminado.fueElProyecto() ? "PROYECTO_ELIMINADO" : "DIAGRAMA_ELIMINADO";
        for (UUID miembro : eliminado.miembros()) {
            avisar(miembro, new AvisoDeAcceso(eliminado.proyectoId(), eliminado.proyectoNombre(), motivo,
                    null, eliminado.diagramaEliminado()));
        }
    }

    private void soltarLocks(Collection<UUID> diagramas, UUID usuario) {
        for (LockManager.Liberado l : locks.releaseAllOfUser(diagramas, usuario)) {
            broker.convertAndSend("/topic/diagram/" + l.diagramId() + "/locks",
                    new LockMessage(l.elementId(), null, null, false));
        }
    }

    /**
     * Cancela las suscripciones a los topics de esos diagramas; de un usuario, o
     * de todos si `usuario` es null (el diagrama dejo de existir).
     */
    private void cortarSuscripciones(Collection<UUID> diagramas, UUID usuario) {
        Set<String> prefijos = diagramas.stream()
                .map(d -> "/topic/diagram/" + d + "/")
                .collect(java.util.stream.Collectors.toSet());

        List<WsSessionRegistry.Suscripcion> aCortar = sesiones.suscripcionesQue(s ->
                (usuario == null || usuario.equals(s.usuario()))
                        && prefijos.stream().anyMatch(s.destino()::startsWith));

        for (WsSessionRegistry.Suscripcion s : aCortar) {
            // Primero del registro propio: asi, aunque el corte del broker fallara,
            // la sesion ya no puede enviar presencia a ese diagrama.
            sesiones.desuscribir(s.sesion(), s.id());
            try {
                corte.cancelar(s.sesion(), s.id());
            } catch (RuntimeException e) {
                log.warn("No se pudo cancelar la suscripcion {} de la sesion {}: {}", s.id(), s.sesion(), e.getMessage());
            }
        }
    }

    private void avisar(UUID usuario, AvisoDeAcceso aviso) {
        broker.convertAndSendToUser(usuario.toString(), COLA_DE_AVISOS, aviso);
    }
}
