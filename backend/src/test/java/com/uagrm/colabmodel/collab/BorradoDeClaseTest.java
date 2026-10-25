package com.uagrm.colabmodel.collab;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.uagrm.colabmodel.modelcore.command.*;
import com.uagrm.colabmodel.modelcore.metamodel.*;
import com.uagrm.colabmodel.project.Diagram;
import com.uagrm.colabmodel.project.DiagramOperationRepository;
import com.uagrm.colabmodel.project.DiagramRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Borrar una clase toca CUATRO tablas. Es facil olvidarse de una, y el olvido
 * no se nota: el canvas sigue dibujando bien porque itera las clases, no la
 * geometria. La fila huerfana solo aparece cuando alguien mira el snapshot.
 *
 * Ademas este efecto esta espejado en `frontend/src/canvas/applyOperation.ts`;
 * este test es lo que sostiene que los dos lados hagan lo mismo.
 */
class BorradoDeClaseTest {

    private final UUID diagramId = UUID.randomUUID();
    private final UUID classId = UUID.randomUUID();

    private UmlClassRepository classes;
    private UmlAttributeRepository attributes;
    private UmlRelationshipRepository relationships;
    private DiagramLayoutRepository layouts;
    private CommandApplier applier;

    @BeforeEach
    void prepararClaseConAtributoRelacionYGeometria() {
        DiagramRepository diagrams = mock(DiagramRepository.class);
        DiagramOperationRepository operations = mock(DiagramOperationRepository.class);
        classes = mock(UmlClassRepository.class);
        attributes = mock(UmlAttributeRepository.class);
        relationships = mock(UmlRelationshipRepository.class);
        layouts = mock(DiagramLayoutRepository.class);

        Diagram d = new Diagram();
        d.setId(diagramId);
        when(diagrams.findById(diagramId)).thenReturn(Optional.of(d));
        when(operations.findByOpId(any())).thenReturn(Optional.empty());

        UmlClass c = new UmlClass();
        c.setId(classId);
        c.setDiagramId(diagramId);
        c.setName("Libro");
        when(classes.findById(classId)).thenReturn(Optional.of(c));

        UmlAttribute a = new UmlAttribute();
        a.setId(UUID.randomUUID());
        a.setClassId(classId);
        when(attributes.findByClassId(classId)).thenReturn(List.of(a));

        UmlRelationship r = new UmlRelationship();
        r.setId(UUID.randomUUID());
        r.setSourceClassId(classId);
        when(relationships.findBySourceClassIdOrTargetClassId(classId, classId)).thenReturn(List.of(r));

        DiagramLayout geo = new DiagramLayout();
        geo.setDiagramId(diagramId);
        geo.setElementId(classId);
        when(layouts.findByDiagramIdAndElementId(diagramId, classId)).thenReturn(Optional.of(geo));

        applier = new CommandApplier(diagrams, operations, classes, attributes,
                mock(UmlMethodRepository.class), relationships, mock(ModelingDecisionRepository.class), layouts, new ObjectMapper());
    }

    @Test
    void borrar_una_clase_se_lleva_su_geometria() {
        applier.apply(borrarClase(), UUID.randomUUID());

        // Sin esto la fila queda huerfana y viaja en cada snapshot, para siempre.
        verify(layouts).delete(any(DiagramLayout.class));
    }

    @Test
    void borrar_una_clase_la_marca_con_tombstone_en_vez_de_borrarla() {
        applier.apply(borrarClase(), UUID.randomUUID());

        // El tombstone evita que una operacion concurrente la resucite.
        verify(classes).save(argThat(c -> c.getDeletedAt() != null));
        verify(classes, never()).delete(any());
    }

    @Test
    void borrar_una_clase_arrastra_sus_atributos_y_sus_relaciones() {
        applier.apply(borrarClase(), UUID.randomUUID());

        verify(attributes).deleteAll(anyList());
        verify(relationships).save(argThat(r -> r.getDeletedAt() != null));
    }

    @Test
    void no_se_puede_borrar_una_clase_que_no_existe() {
        when(classes.findById(classId)).thenReturn(Optional.empty());

        assertThat(org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> applier.apply(borrarClase(), UUID.randomUUID()), CommandRejectedException.class)
                .getReason()).isEqualTo(RejectionReason.NOT_FOUND);
    }

    private Command borrarClase() {
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("classId", classId.toString());
        return new Command(UUID.randomUUID(), diagramId, 0L, CommandType.DELETE_CLASS, Origin.MOUSE, payload);
    }
}
