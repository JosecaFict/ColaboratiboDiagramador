package com.uagrm.colabmodel.collab;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.uagrm.colabmodel.modelcore.command.*;
import com.uagrm.colabmodel.modelcore.metamodel.*;
import com.uagrm.colabmodel.project.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * EC-01 para TODA la semantica, no solo para lo que trae `classId` (ADR-028).
 *
 * Antes el bus buscaba el lease en `classId` o `elementId`, y si el payload no
 * los traia dejaba pasar. Cada caso de aca pasaba sin ningun control: la
 * sesion B editaba los atributos, metodos, banderas y relaciones de una clase
 * que la sesion A tenia tomada.
 */
class LeaseDeTodaLaSemanticaTest {

    private final UUID diagramId = UUID.randomUUID();
    private final UUID usuario = UUID.randomUUID();
    private final UUID clase = UUID.randomUUID();
    private final UUID atributo = UUID.randomUUID();
    private final UUID metodo = UUID.randomUUID();
    private final UUID relacion = UUID.randomUUID();

    private final Autor sesionA = new Autor(usuario, "sesion-A");
    private final Autor sesionB = new Autor(usuario, "sesion-B");

    private LockManager locks;
    private CommandApplier applier;
    private DiagramCommandBus bus;

    @BeforeEach
    void prepararClaseConAtributoMetodoYRelacion() {
        locks = new LockManager(30);
        applier = mock(CommandApplier.class);
        when(applier.apply(any(), any())).thenReturn(mock(AppliedOperation.class));

        UUID projectId = UUID.randomUUID();
        DiagramRepository diagrams = mock(DiagramRepository.class);
        Diagram d = new Diagram();
        d.setId(diagramId);
        d.setProjectId(projectId);
        when(diagrams.findById(diagramId)).thenReturn(Optional.of(d));
        ProjectRepository projects = mock(ProjectRepository.class);
        Project p = new Project();
        p.setId(projectId);
        p.setOwnerId(usuario);
        when(projects.findById(projectId)).thenReturn(Optional.of(p));

        UmlAttributeRepository attributes = mock(UmlAttributeRepository.class);
        UmlAttribute a = new UmlAttribute();
        a.setId(atributo);
        a.setClassId(clase);
        when(attributes.findById(atributo)).thenReturn(Optional.of(a));
        UmlMethodRepository methods = mock(UmlMethodRepository.class);
        UmlMethod m = new UmlMethod();
        m.setId(metodo);
        m.setClassId(clase);
        when(methods.findById(metodo)).thenReturn(Optional.of(m));

        bus = new DiagramCommandBus(applier, locks, mock(SimpMessagingTemplate.class),
                new AccesoAProyecto(diagrams, projects, mock(ProjectMemberRepository.class)),
                new EntidadBloqueable(attributes, methods));
    }

    /** Lo que antes pasaba sin revisar el lease de la clase. */
    static Stream<Function<LeaseDeTodaLaSemanticaTest, Command>> sobreLaClase() {
        return Stream.of(
                t -> t.cmd(CommandType.UPDATE_ATTRIBUTE, "attributeId", t.atributo, "name", "titulo"),
                t -> t.cmd(CommandType.REMOVE_ATTRIBUTE, "attributeId", t.atributo),
                t -> t.cmd(CommandType.REMOVE_METHOD, "methodId", t.metodo),
                t -> t.cmd(CommandType.SET_ER_FLAG, "targetType", "ATTRIBUTE", "targetId", t.atributo,
                        "flag", "pk"),
                t -> t.cmd(CommandType.SET_ER_FLAG, "targetType", "CLASS", "targetId", t.clase,
                        "flag", "weak"),
                // Estos ya se revisaban: quedan para fijar que no se rompieron.
                t -> t.cmd(CommandType.RENAME_CLASS, "classId", t.clase, "name", "Libro"),
                t -> t.cmd(CommandType.ADD_ATTRIBUTE, "classId", t.clase, "attributeId", UUID.randomUUID(),
                        "name", "isbn"));
    }

    @ParameterizedTest
    @MethodSource("sobreLaClase")
    void otra_sesion_no_puede_editar_una_clase_tomada(Function<LeaseDeTodaLaSemanticaTest, Command> armar) {
        locks.acquire(diagramId, clase, usuario, sesionA.sesion(), "Ana #1");

        var e = catchThrowableOfType(() -> bus.submit(armar.apply(this), sesionB),
                CommandRejectedException.class);

        assertThat(e.getReason()).isEqualTo(RejectionReason.LOCKED_BY_OTHER);
        assertThat(e.getMessage()).contains("Ana #1");
        verify(applier, never()).apply(any(), any());
    }

    @ParameterizedTest
    @MethodSource("sobreLaClase")
    void la_sesion_duenia_si_puede(Function<LeaseDeTodaLaSemanticaTest, Command> armar) {
        locks.acquire(diagramId, clase, usuario, sesionA.sesion(), "Ana #1");

        bus.submit(armar.apply(this), sesionA);

        verify(applier).apply(any(), eq(usuario));
    }

    @Test
    void una_relacion_tomada_no_la_edita_otra_sesion() {
        locks.acquire(diagramId, relacion, usuario, sesionA.sesion(), "Ana #1");

        var e = catchThrowableOfType(
                () -> bus.submit(cmd(CommandType.UPDATE_RELATION, "relationId", relacion,
                        "sourceMultiplicity", "0..*"), sesionB),
                CommandRejectedException.class);

        assertThat(e.getReason()).isEqualTo(RejectionReason.LOCKED_BY_OTHER);
    }

    @Test
    void editar_una_relacion_no_bloquea_sus_clases() {
        // Tomar la relacion es tomar la relacion: renombrar un extremo sigue libre.
        locks.acquire(diagramId, relacion, usuario, sesionA.sesion(), "Ana #1");

        bus.submit(cmd(CommandType.RENAME_CLASS, "classId", clase, "name", "Libro"), sesionB);

        verify(applier).apply(any(), eq(usuario));
    }

    @Test
    void un_lote_tampoco_puede_colar_un_update_attribute_sobre_una_clase_tomada() {
        locks.acquire(diagramId, clase, usuario, sesionA.sesion(), "Ana #1");
        ObjectNode lote = JsonNodeFactory.instance.objectNode();
        lote.put("descripcion", "prueba");
        ObjectNode cambio = lote.putArray("comandos").addObject();
        cambio.put("type", "UPDATE_ATTRIBUTE");
        cambio.set("payload", cmd(CommandType.UPDATE_ATTRIBUTE, "attributeId", atributo, "name", "x").payload());

        var e = catchThrowableOfType(
                () -> bus.submit(new Command(UUID.randomUUID(), diagramId, 0, CommandType.BATCH, Origin.MOUSE, lote),
                        sesionB),
                CommandRejectedException.class);

        assertThat(e.getReason()).isEqualTo(RejectionReason.LOCKED_BY_OTHER);
    }

    @Test
    void un_atributo_que_no_existe_no_pide_lease_y_lo_rechaza_el_applier() {
        locks.acquire(diagramId, clase, usuario, sesionA.sesion(), "Ana #1");

        bus.submit(cmd(CommandType.REMOVE_ATTRIBUTE, "attributeId", UUID.randomUUID()), sesionB);

        // Llega al applier, que responde NOT_FOUND: mas util que un lock ajeno.
        verify(applier).apply(any(), any());
    }

    private Command cmd(CommandType tipo, Object... pares) {
        ObjectNode p = JsonNodeFactory.instance.objectNode();
        for (int i = 0; i < pares.length; i += 2) p.put((String) pares[i], String.valueOf(pares[i + 1]));
        return new Command(UUID.randomUUID(), diagramId, 0, tipo, Origin.MOUSE, p);
    }
}
