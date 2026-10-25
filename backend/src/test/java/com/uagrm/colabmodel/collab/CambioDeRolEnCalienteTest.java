package com.uagrm.colabmodel.collab;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.uagrm.colabmodel.iam.AppUser;
import com.uagrm.colabmodel.modelcore.command.*;
import com.uagrm.colabmodel.project.*;
import com.uagrm.colabmodel.project.AccesoAProyecto.Rol;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Un cambio de rol se aplica "en caliente", sin recargar (ADR-026).
 *
 * Se arma el recorrido real: el servicio de miembros hace el cambio y publica su
 * evento, y el evento llega al propagador como llegaria despues del commit. Lo
 * que se afirma es lo que el usuario afectado vive:
 *
 *  - su PROXIMO comando ya se evalua con el rol nuevo (o lo rechazan);
 *  - se le sueltan los locks y se difunde;
 *  - recibe el aviso privado;
 *  - si lo quitaron, deja de recibir operaciones.
 */
class CambioDeRolEnCalienteTest {

    private ProyectosEnMemoria datos;
    private CommandApplier applier;
    private LockManager locks;
    private SimpMessagingTemplate broker;
    private WsSessionRegistry sesiones;
    private CorteDeSuscripciones corte;
    private DiagramCommandBus bus;
    private PropagadorDeAcceso propagador;
    private GestionDeMiembros miembros;

    private AppUser ana;     // propietaria
    private AppUser beto;    // EDITOR
    private AppUser carla;   // EDITOR
    private Project proyecto;
    private UUID diagramaId;

    @BeforeEach
    void prepararUnProyectoEnVivo() {
        datos = new ProyectosEnMemoria();
        applier = mock(CommandApplier.class);
        when(applier.apply(any(), any())).thenAnswer(inv -> aplicada(inv.getArgument(0)));
        locks = new LockManager(30);
        broker = mock(SimpMessagingTemplate.class);
        sesiones = new WsSessionRegistry();
        corte = mock(CorteDeSuscripciones.class);
        bus = new DiagramCommandBus(applier, locks, broker, datos.acceso,
                Bloqueables.sinModelo());
        propagador = new PropagadorDeAcceso(datos.acceso, locks, sesiones, corte, bus, broker);

        // El evento llega al propagador como llegaria tras el commit.
        miembros = new GestionDeMiembros(datos.projects, datos.members, datos.diagrams, datos.users, datos.acceso,
                new org.springframework.context.ApplicationEventPublisher() {
                    @Override public void publishEvent(Object evento) {
                        if (evento instanceof CambioDeAcceso c) propagador.alCambiarElAcceso(c);
                    }
                });

        ana = datos.usuario("Ana");
        proyecto = datos.proyecto(ana, "Biblioteca");
        diagramaId = datos.diagramas.values().iterator().next().getId();
        beto = datos.miembro(proyecto, "Beto", Rol.EDITOR);
        carla = datos.miembro(proyecto, "Carla", Rol.EDITOR);
    }

    // ---------------------------------------------------- el proximo comando

    @Test
    void un_editor_degradado_a_viewer_recibe_FORBIDDEN_en_su_proximo_comando() {
        bus.submit(crearClase(), autor(beto));   // antes puede

        miembros.cambiarRol(proyecto.getId(), ana.getId(), beto.getId(), "VIEWER");

        assertThatThrownBy(() -> bus.submit(crearClase(), autor(beto)))
                .isInstanceOfSatisfying(CommandRejectedException.class,
                        e -> assertThat(e.getReason()).isEqualTo(RejectionReason.FORBIDDEN));
        // El modelo no se toco por el segundo: solo el primero llego al applier.
        verify(applier, times(1)).apply(any(), eq(beto.getId()));
        // Y sigue pudiendo LEER: el rol de observador no es una baja.
        assertThat(datos.acceso.rolSobreDiagrama(diagramaId, beto.getId())).contains(Rol.VIEWER);
    }

    @Test
    void un_miembro_quitado_ve_rechazado_su_proximo_comando() {
        bus.submit(crearClase(), autor(beto));

        miembros.quitar(proyecto.getId(), ana.getId(), beto.getId());

        assertThatThrownBy(() -> bus.submit(crearClase(), autor(beto)))
                .isInstanceOfSatisfying(CommandRejectedException.class, e -> {
                    assertThat(e.getReason()).isEqualTo(RejectionReason.FORBIDDEN);
                    assertThat(e.getMessage()).contains("No sos miembro");
                });
        verify(applier, times(1)).apply(any(), eq(beto.getId()));
    }

    @Test
    void promoverlo_de_vuelta_le_devuelve_el_permiso_sin_reiniciar_nada() {
        miembros.cambiarRol(proyecto.getId(), ana.getId(), beto.getId(), "VIEWER");
        assertThatThrownBy(() -> bus.submit(crearClase(), autor(beto))).isInstanceOf(CommandRejectedException.class);

        miembros.cambiarRol(proyecto.getId(), ana.getId(), beto.getId(), "EDITOR");

        assertThat(bus.submit(crearClase(), autor(beto)).seq()).isEqualTo(1L);
    }

    // ----------------------------------------------------------------- locks

    @Test
    void al_pasar_a_viewer_se_liberan_sus_locks_en_todas_sus_pestanas_y_se_difunde() {
        UUID claseDeBeto = UUID.randomUUID();
        UUID otraDeBeto = UUID.randomUUID();
        UUID claseDeCarla = UUID.randomUUID();
        locks.acquire(diagramaId, claseDeBeto, beto.getId(), "pestana-1", "Beto");
        locks.acquire(diagramaId, otraDeBeto, beto.getId(), "pestana-2", "Beto");   // otra pestana de Beto
        locks.acquire(diagramaId, claseDeCarla, carla.getId(), "pestana-3", "Carla");

        miembros.cambiarRol(proyecto.getId(), ana.getId(), beto.getId(), "VIEWER");

        assertThat(locks.holderOf(diagramaId, claseDeBeto)).isEmpty();
        assertThat(locks.holderOf(diagramaId, otraDeBeto)).isEmpty();
        // El de otra persona no se toca.
        assertThat(locks.holderOf(diagramaId, claseDeCarla)).isPresent();

        ArgumentCaptor<LockMessage> difundidos = ArgumentCaptor.forClass(LockMessage.class);
        verify(broker, times(2)).convertAndSend(eq("/topic/diagram/" + diagramaId + "/locks"), difundidos.capture());
        assertThat(difundidos.getAllValues()).extracting(LockMessage::elementId)
                .containsExactlyInAnyOrder(claseDeBeto, otraDeBeto);
        assertThat(difundidos.getAllValues()).allMatch(l -> !l.held());
    }

    @Test
    void quien_sigue_pudiendo_escribir_conserva_sus_locks() {
        UUID clase = UUID.randomUUID();
        locks.acquire(diagramaId, clase, beto.getId(), "pestana-1", "Beto");

        // EDITOR -> EDITOR no es un cambio; ROL_CAMBIADO a EDITOR desde otro rol tampoco quita nada.
        propagador.alCambiarElAcceso(new CambioDeAcceso(proyecto.getId(), "Biblioteca", List.of(diagramaId),
                beto.getId(), CambioDeAcceso.Motivo.ROL_CAMBIADO, Rol.EDITOR));

        assertThat(locks.holderOf(diagramaId, clase)).isPresent();
        verify(broker, never()).convertAndSend(eq("/topic/diagram/" + diagramaId + "/locks"), any(Object.class));
    }

    // ---------------------------------------------------------------- avisos

    @Test
    void el_afectado_recibe_un_aviso_privado_con_su_rol_nuevo() {
        miembros.cambiarRol(proyecto.getId(), ana.getId(), beto.getId(), "VIEWER");

        ArgumentCaptor<AvisoDeAcceso> aviso = ArgumentCaptor.forClass(AvisoDeAcceso.class);
        verify(broker).convertAndSendToUser(eq(beto.getId().toString()), eq("/queue/membresia"), aviso.capture());
        assertThat(aviso.getValue().reason()).isEqualTo("ROL_CAMBIADO");
        assertThat(aviso.getValue().role()).isEqualTo("VIEWER");
        assertThat(aviso.getValue().projectId()).isEqualTo(proyecto.getId());
        assertThat(aviso.getValue().projectName()).isEqualTo("Biblioteca");
        // Nadie mas recibe nada: es privado.
        verify(broker, never()).convertAndSendToUser(eq(carla.getId().toString()), any(), any(Object.class));
        verify(broker, never()).convertAndSendToUser(eq(ana.getId().toString()), any(), any(Object.class));
    }

    @Test
    void al_quitarlo_el_aviso_no_trae_rol() {
        miembros.quitar(proyecto.getId(), ana.getId(), beto.getId());

        ArgumentCaptor<AvisoDeAcceso> aviso = ArgumentCaptor.forClass(AvisoDeAcceso.class);
        verify(broker).convertAndSendToUser(eq(beto.getId().toString()), eq("/queue/membresia"), aviso.capture());
        assertThat(aviso.getValue().reason()).isEqualTo("QUITADO");
        assertThat(aviso.getValue().role()).isNull();   // sin rol = sin acceso
    }

    @Test
    void al_transferir_la_propiedad_avisan_a_los_dos() {
        miembros.transferir(proyecto.getId(), ana.getId(), beto.getId());

        ArgumentCaptor<AvisoDeAcceso> aBeto = ArgumentCaptor.forClass(AvisoDeAcceso.class);
        ArgumentCaptor<AvisoDeAcceso> aAna = ArgumentCaptor.forClass(AvisoDeAcceso.class);
        verify(broker).convertAndSendToUser(eq(beto.getId().toString()), eq("/queue/membresia"), aBeto.capture());
        verify(broker).convertAndSendToUser(eq(ana.getId().toString()), eq("/queue/membresia"), aAna.capture());
        assertThat(aBeto.getValue().reason()).isEqualTo("PROPIEDAD_RECIBIDA");
        assertThat(aBeto.getValue().role()).isEqualTo("OWNER");
        assertThat(aAna.getValue().reason()).isEqualTo("PROPIEDAD_CEDIDA");
        assertThat(aAna.getValue().role()).isEqualTo("EDITOR");
    }

    // ---------------------------------------------------------- suscripciones

    @Test
    void al_quitarlo_sus_sesiones_dejan_de_recibir_operaciones() {
        String base = "/topic/diagram/" + diagramaId;
        suscribir("sesion-beto-1", beto, "sub-0", base + "/ops");
        suscribir("sesion-beto-1", beto, "sub-1", base + "/locks");
        suscribir("sesion-beto-1", beto, "sub-2", base + "/presence");
        suscribir("sesion-beto-1", beto, "sub-3", "/user/queue/errors");     // no es de un diagrama
        suscribir("sesion-beto-2", beto, "sub-0", base + "/ops");            // su otra pestana
        suscribir("sesion-carla", carla, "sub-0", base + "/ops");            // otra persona

        miembros.quitar(proyecto.getId(), ana.getId(), beto.getId());

        verify(corte).cancelar("sesion-beto-1", "sub-0");
        verify(corte).cancelar("sesion-beto-1", "sub-1");
        verify(corte).cancelar("sesion-beto-1", "sub-2");
        verify(corte).cancelar("sesion-beto-2", "sub-0");
        verifyNoMoreInteractions(corte);     // ni su cola privada ni la suscripcion de Carla
        // Y tampoco puede seguir mandando presencia a ese diagrama.
        assertThat(sesiones.estaSuscrita("sesion-beto-1", base + "/presence")).isFalse();
        assertThat(sesiones.estaSuscrita("sesion-carla", base + "/ops")).isTrue();
    }

    @Test
    void degradarlo_a_viewer_no_le_corta_la_lectura() {
        String base = "/topic/diagram/" + diagramaId;
        suscribir("sesion-beto-1", beto, "sub-0", base + "/ops");

        miembros.cambiarRol(proyecto.getId(), ana.getId(), beto.getId(), "VIEWER");

        verifyNoInteractions(corte);
        assertThat(sesiones.estaSuscrita("sesion-beto-1", base + "/ops")).isTrue();
    }

    @Test
    void si_el_broker_falla_al_cortar_igual_se_le_avisa_y_se_limpia_el_registro() {
        String base = "/topic/diagram/" + diagramaId;
        suscribir("sesion-beto-1", beto, "sub-0", base + "/ops");
        doThrow(new IllegalStateException("broker caido")).when(corte).cancelar(any(), any());

        miembros.quitar(proyecto.getId(), ana.getId(), beto.getId());

        assertThat(sesiones.estaSuscrita("sesion-beto-1", base + "/ops")).isFalse();
        verify(broker).convertAndSendToUser(eq(beto.getId().toString()), eq("/queue/membresia"), any(Object.class));
    }

    // ----------------------------------------------------------- eliminacion

    @Test
    void al_eliminar_el_proyecto_todos_pierden_locks_suscripciones_y_reciben_el_aviso() {
        String base = "/topic/diagram/" + diagramaId;
        UUID clase = UUID.randomUUID();
        locks.acquire(diagramaId, clase, beto.getId(), "pestana-1", "Beto");
        suscribir("sesion-beto", beto, "sub-0", base + "/ops");
        suscribir("sesion-ana", ana, "sub-0", base + "/ops");

        propagador.alEliminarse(new RecursoEliminado(proyecto.getId(), "Biblioteca", null,
                List.of(diagramaId), List.of(ana.getId(), beto.getId(), carla.getId())));

        assertThat(locks.holderOf(diagramaId, clase)).isEmpty();
        verify(corte).cancelar("sesion-beto", "sub-0");
        verify(corte).cancelar("sesion-ana", "sub-0");
        ArgumentCaptor<AvisoDeAcceso> aviso = ArgumentCaptor.forClass(AvisoDeAcceso.class);
        verify(broker, times(3)).convertAndSendToUser(any(), eq("/queue/membresia"), aviso.capture());
        assertThat(aviso.getAllValues()).allMatch(a -> a.reason().equals("PROYECTO_ELIMINADO") && a.role() == null);
    }

    @Test
    void al_eliminar_un_diagrama_el_aviso_dice_cual() {
        propagador.alEliminarse(new RecursoEliminado(proyecto.getId(), "Biblioteca", diagramaId,
                List.of(diagramaId), List.of(ana.getId(), beto.getId())));

        ArgumentCaptor<AvisoDeAcceso> aviso = ArgumentCaptor.forClass(AvisoDeAcceso.class);
        verify(broker, times(2)).convertAndSendToUser(any(), eq("/queue/membresia"), aviso.capture());
        assertThat(aviso.getAllValues()).allMatch(a ->
                a.reason().equals("DIAGRAMA_ELIMINADO") && diagramaId.equals(a.diagramId()));
    }

    // ----------------------------------------------------------- transaccion

    @Test
    void los_avisos_se_entregan_despues_del_commit_no_antes() throws Exception {
        // Si la transaccion se deshace (una transferencia que falla a la mitad), no
        // se le avisa a nadie de algo que no paso. Eso depende de esta fase.
        for (String metodo : List.of("alCambiarElAcceso", "alEliminarse")) {
            var m = java.util.stream.Stream.of(PropagadorDeAcceso.class.getDeclaredMethods())
                    .filter(x -> x.getName().equals(metodo)).findFirst().orElseThrow();
            TransactionalEventListener anotacion = m.getAnnotation(TransactionalEventListener.class);
            assertThat(anotacion).as("@TransactionalEventListener en %s", metodo).isNotNull();
            assertThat(anotacion.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
        }
    }

    // ----------------------------------------------------------------- apoyo

    private void suscribir(String sesion, AppUser usuario, String id, String destino) {
        sesiones.registrar(sesion, usuario.getId());
        sesiones.suscribir(sesion, id, destino);
    }

    private Autor autor(AppUser u) {
        return new Autor(u.getId(), "sesion-" + u.getId());
    }

    private Command crearClase() {
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("classId", UUID.randomUUID().toString());
        payload.put("name", "Prueba");
        return new Command(UUID.randomUUID(), diagramaId, 0L, CommandType.CREATE_CLASS, Origin.MOUSE, payload);
    }

    private final java.util.concurrent.atomic.AtomicLong seq = new java.util.concurrent.atomic.AtomicLong();

    private AppliedOperation aplicada(Command c) {
        return new AppliedOperation(c.opId(), seq.incrementAndGet(), c.type(), c.origin(), c.payload(), null,
                OffsetDateTime.now());
    }
}
