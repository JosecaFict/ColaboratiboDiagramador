package com.uagrm.colabmodel.collab;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.uagrm.colabmodel.modelcore.command.*;
import com.uagrm.colabmodel.project.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Escenario de calidad EC-11: "VIEWER intenta escribir -> 100% rechazados".
 *
 * Se prueba en el BUS y no mas arriba a proposito: el bus es el unico camino
 * de escritura (ADR-002), asi que un VIEWER rechazado aqui esta rechazado
 * venga del mouse, de la voz, del texto, de una foto o de un XMI.
 */
class PermisoDeEscrituraTest {

    private final UUID diagramId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private final UUID ownerId = UUID.randomUUID();

    private CommandApplier applier;
    private ProjectMemberRepository members;
    private DiagramRepository diagrams;
    private ProjectRepository projects;
    private DiagramCommandBus bus;

    @BeforeEach
    void prepararProyectoConDueño() {
        applier = mock(CommandApplier.class);
        members = mock(ProjectMemberRepository.class);
        diagrams = mock(DiagramRepository.class);
        projects = mock(ProjectRepository.class);

        Diagram d = new Diagram();
        d.setId(diagramId);
        d.setProjectId(projectId);
        when(diagrams.findById(diagramId)).thenReturn(Optional.of(d));

        Project p = new Project();
        p.setId(projectId);
        p.setOwnerId(ownerId);
        when(projects.findById(projectId)).thenReturn(Optional.of(p));

        bus = new DiagramCommandBus(applier, new LockManager(30), mock(SimpMessagingTemplate.class),
                new AccesoAProyecto(diagrams, projects, members),
                Bloqueables.sinModelo());
    }

    @Test
    void un_viewer_no_puede_escribir() {
        UUID viewer = conRol("VIEWER");

        assertThatThrownBy(() -> bus.submit(crearClase(), new Autor(viewer, "sesion-" + viewer)))
                .isInstanceOf(CommandRejectedException.class)
                .satisfies(e -> assertThat(((CommandRejectedException) e).getReason())
                        .isEqualTo(RejectionReason.FORBIDDEN));

        // Lo que exige EC-11 no es solo el error: es que el modelo no se toque.
        verifyNoInteractions(applier);
    }

    @Test
    void quien_no_es_miembro_del_proyecto_tampoco_puede_escribir() {
        UUID extraño = UUID.randomUUID();
        when(members.findByProjectIdAndUserId(projectId, extraño)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> bus.submit(crearClase(), new Autor(extraño, "sesion-" + extraño)))
                .isInstanceOf(CommandRejectedException.class)
                .satisfies(e -> assertThat(((CommandRejectedException) e).getReason())
                        .isEqualTo(RejectionReason.FORBIDDEN));

        verifyNoInteractions(applier);
    }

    @Test
    void un_editor_si_puede_escribir() {
        UUID editor = conRol("EDITOR");
        when(applier.apply(any(), any())).thenAnswer(inv -> aplicada(inv.getArgument(0)));

        AppliedOperation resultado = bus.submit(crearClase(), new Autor(editor, "sesion-" + editor));

        assertThat(resultado.seq()).isEqualTo(1L);
        verify(applier).apply(any(), eq(editor));
    }

    @Test
    void el_dueño_del_proyecto_si_puede_escribir() {
        when(applier.apply(any(), any())).thenAnswer(inv -> aplicada(inv.getArgument(0)));

        bus.submit(crearClase(), new Autor(ownerId, "sesion-" + ownerId));

        // El OWNER es implicito por projects.owner_id: no necesita fila en project_members.
        verify(applier).apply(any(), eq(ownerId));
        verifyNoInteractions(members);
    }

    @Test
    void un_viewer_si_puede_leer_aunque_no_pueda_escribir() {
        // El VIEWER existe para mirar: si no pudiera leer, el rol no serviria
        // de nada. La misma regla la usa el snapshot (GET /diagrams/{id}).
        UUID viewer = conRol("VIEWER");
        AccesoAProyecto acceso = new AccesoAProyecto(diagrams, projects, members);

        AccesoAProyecto.Rol rol = acceso.rolSobreDiagrama(diagramId, viewer).orElseThrow();

        assertThat(rol).isEqualTo(AccesoAProyecto.Rol.VIEWER);
        assertThat(acceso.puedeEditar(rol)).isFalse();
    }

    private UUID conRol(String rol) {
        UUID userId = UUID.randomUUID();
        ProjectMember m = new ProjectMember();
        m.setProjectId(projectId);
        m.setUserId(userId);
        m.setRole(rol);
        when(members.findByProjectIdAndUserId(projectId, userId)).thenReturn(Optional.of(m));
        return userId;
    }

    private Command crearClase() {
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("classId", UUID.randomUUID().toString());
        payload.put("name", "Prueba");
        return new Command(UUID.randomUUID(), diagramId, 0L, CommandType.CREATE_CLASS, Origin.MOUSE, payload);
    }

    private AppliedOperation aplicada(Command c) {
        return new AppliedOperation(c.opId(), 1L, c.type(), c.origin(), c.payload(), null, OffsetDateTime.now());
    }
}
