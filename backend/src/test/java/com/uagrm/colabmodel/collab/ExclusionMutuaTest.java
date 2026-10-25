package com.uagrm.colabmodel.collab;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.uagrm.colabmodel.modelcore.command.*;
import com.uagrm.colabmodel.project.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.security.Principal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Escenario de calidad EC-01: "dos usuarios editan la misma entidad ->
 * exactamente 1 escritor".
 *
 * Hasta esta version EC-01 no tenia ningun test, y el mecanismo tenia un
 * agujero justo en el caso con el que se muestra: el lease era por USUARIO,
 * asi que dos pestanas de la misma persona editaban la misma clase a la vez.
 * Casi todos los tests de aca usan un solo usuario con dos sesiones a
 * proposito: es el caso que estaba roto.
 */
class ExclusionMutuaTest {

    private final UUID diagramId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private final UUID usuario = UUID.randomUUID();
    private final UUID clase = UUID.randomUUID();

    private final Autor pestanaA = new Autor(usuario, "sesion-A");
    private final Autor pestanaB = new Autor(usuario, "sesion-B");

    private LockManager locks;
    private CommandApplier applier;
    private SimpMessagingTemplate broker;
    private DiagramCommandBus bus;

    @BeforeEach
    void prepararDiagramaConDuenio() {
        locks = new LockManager(30);
        applier = mock(CommandApplier.class);
        broker = mock(SimpMessagingTemplate.class);

        DiagramRepository diagrams = mock(DiagramRepository.class);
        ProjectRepository projects = mock(ProjectRepository.class);
        Diagram d = new Diagram();
        d.setId(diagramId);
        d.setProjectId(projectId);
        when(diagrams.findById(diagramId)).thenReturn(Optional.of(d));
        Project p = new Project();
        p.setId(projectId);
        p.setOwnerId(usuario);
        when(projects.findById(projectId)).thenReturn(Optional.of(p));

        bus = new DiagramCommandBus(applier, locks, broker,
                new AccesoAProyecto(diagrams, projects, mock(ProjectMemberRepository.class)),
                Bloqueables.sinModelo());
    }

    private Command renombrar() {
        ObjectNode p = JsonNodeFactory.instance.objectNode();
        p.put("classId", clase.toString());
        p.put("name", "Libro");
        return new Command(UUID.randomUUID(), diagramId, 0, CommandType.RENAME_CLASS, Origin.MOUSE, p);
    }

    // ------------------------------------------------------------------ BUS

    @Test
    void dos_pestanas_del_mismo_usuario_no_pueden_editar_la_misma_clase() {
        locks.acquire(diagramId, clase, usuario, pestanaA.sesion(), "Usuario-A");

        var e = catchThrowableOfType(() -> bus.submit(renombrar(), pestanaB),
                CommandRejectedException.class);

        // Antes esto se aceptaba: para el servidor las dos pestanas eran el
        // mismo escritor.
        assertThat(e.getReason()).isEqualTo(RejectionReason.LOCKED_BY_OTHER);
        assertThat(e.getMessage()).contains("Usuario-A");
        verify(applier, never()).apply(any(), any());
    }

    @Test
    void la_pestana_duena_del_lease_si_puede_escribir() {
        locks.acquire(diagramId, clase, usuario, pestanaA.sesion(), "Usuario-A");
        when(applier.apply(any(), any())).thenReturn(mock(AppliedOperation.class));

        bus.submit(renombrar(), pestanaA);

        verify(applier).apply(any(), eq(usuario));
    }

    @Test
    void un_autor_sin_sesion_no_puede_escribir_sobre_algo_que_otro_edita() {
        locks.acquire(diagramId, clase, usuario, pestanaA.sesion(), "Usuario-A");

        var e = catchThrowableOfType(() -> bus.submit(renombrar(), new Autor(usuario, null)),
                CommandRejectedException.class);

        assertThat(e.getReason()).isEqualTo(RejectionReason.LOCKED_BY_OTHER);
    }

    // ---------------------------------------------------------- LOCK MANAGER

    @Test
    void una_segunda_pestana_no_puede_robar_el_lease() {
        assertThat(locks.acquire(diagramId, clase, usuario, "sesion-A", "Usuario-A")).isTrue();

        assertThat(locks.acquire(diagramId, clase, usuario, "sesion-B", "Usuario-B")).isFalse();
        assertThat(locks.holderOf(diagramId, clase)).get()
                .extracting(LockManager.Lease::userName).isEqualTo("Usuario-A");
    }

    @Test
    void la_misma_pestana_renueva_su_propio_lease() {
        locks.acquire(diagramId, clase, usuario, "sesion-A", "Usuario-A");

        // El latido cada 10 s tiene que poder renovarlo.
        assertThat(locks.acquire(diagramId, clase, usuario, "sesion-A", "Usuario-A")).isTrue();
    }

    @Test
    void soltar_desde_otra_pestana_no_libera_el_lease() {
        locks.acquire(diagramId, clase, usuario, "sesion-A", "Usuario-A");

        locks.release(diagramId, clase, "sesion-B");

        assertThat(locks.canWrite(diagramId, clase, "sesion-B")).isFalse();
    }

    @Test
    void un_lease_vencido_queda_libre_para_cualquiera() {
        LockManager vencidos = new LockManager(-1);   // nace ya vencido
        vencidos.acquire(diagramId, clase, usuario, "sesion-A", "Usuario-A");

        assertThat(vencidos.canWrite(diagramId, clase, "sesion-B")).isTrue();
    }

    @Test
    void sin_sesion_no_se_puede_tomar_un_lease() {
        // Sin sesion no hay latido que lo renueve ni desconexion que lo suelte.
        assertThat(locks.acquire(diagramId, clase, usuario, null, "Usuario-A")).isFalse();
    }

    // ----------------------------------------------------- LO QUE SE DIFUNDE

    private LockMessage ultimoDifundido() {
        ArgumentCaptor<Object> mensaje = ArgumentCaptor.forClass(Object.class);
        verify(broker, atLeastOnce()).convertAndSend(eq("/topic/diagram/" + diagramId + "/locks"),
                mensaje.capture());
        List<Object> todos = mensaje.getAllValues();
        return (LockMessage) todos.get(todos.size() - 1);
    }

    @Test
    void un_intento_fallido_no_borra_el_cartel_del_que_esta_editando() {
        DiagramWsController ws = new DiagramWsController(bus, locks, broker);
        Principal principal = usuario::toString;
        locks.acquire(diagramId, clase, usuario, "sesion-A", "Usuario-A");

        ws.onLock(diagramId.toString(), new LockMessage(clase, null, "Usuario-B", true),
                principal, "sesion-B");

        // Antes salia held=false y todas las pantallas dejaban de mostrar que
        // Usuario-A estaba editando.
        assertThat(ultimoDifundido().held()).isTrue();
        assertThat(ultimoDifundido().userName()).isEqualTo("Usuario-A");
    }

    @Test
    void soltar_un_lease_ajeno_tampoco_borra_el_cartel() {
        DiagramWsController ws = new DiagramWsController(bus, locks, broker);
        Principal principal = usuario::toString;
        locks.acquire(diagramId, clase, usuario, "sesion-A", "Usuario-A");

        ws.onUnlock(diagramId.toString(), new LockMessage(clase, null, "Usuario-B", false),
                principal, "sesion-B");

        assertThat(ultimoDifundido().held()).isTrue();
        assertThat(ultimoDifundido().userName()).isEqualTo("Usuario-A");
    }

    @Test
    void cerrar_una_pestana_suelta_solo_sus_leases_y_avisa() {
        UUID otraClase = UUID.randomUUID();
        locks.acquire(diagramId, clase, usuario, "sesion-A", "Usuario-A");
        locks.acquire(diagramId, otraClase, usuario, "sesion-B", "Usuario-B");
        SessionListener listener = new SessionListener(locks, new WsSessionRegistry(), broker);

        listener.onDisconnect(new SessionDisconnectEvent(this,
                MessageBuilder.withPayload(new byte[0]).build(), "sesion-A", CloseStatus.NORMAL));

        // La clase de la pestana cerrada queda libre, y se avisa.
        assertThat(locks.canWrite(diagramId, clase, "sesion-C")).isTrue();
        assertThat(ultimoDifundido().elementId()).isEqualTo(clase);
        assertThat(ultimoDifundido().held()).isFalse();
        // La otra pestana del MISMO usuario sigue siendo duena de la suya.
        assertThat(locks.canWrite(diagramId, otraClase, "sesion-C")).isFalse();
    }
}
