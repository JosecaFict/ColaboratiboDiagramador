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
import org.mockito.ArgumentCaptor;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Acomodar los quiebres de una linea entra por MOVE_ELEMENT y no por un
 * comando nuevo: es geometria, nivel 3 (ADR-004), y el catalogo esta cerrado
 * (ADR-014).
 */
class QuiebresDeLineaTest {

    private final UUID diagramId = UUID.randomUUID();
    private final UUID relationId = UUID.randomUUID();

    private DiagramLayoutRepository layouts;
    private CommandApplier applier;

    @BeforeEach
    void prepararDiagrama() {
        DiagramRepository diagrams = mock(DiagramRepository.class);
        DiagramOperationRepository operations = mock(DiagramOperationRepository.class);
        layouts = mock(DiagramLayoutRepository.class);

        Diagram d = new Diagram();
        d.setId(diagramId);
        when(diagrams.findById(diagramId)).thenReturn(Optional.of(d));
        when(operations.findByOpId(any())).thenReturn(Optional.empty());
        when(layouts.findByDiagramIdAndElementId(any(), any())).thenReturn(Optional.empty());

        applier = new CommandApplier(diagrams, operations, mock(UmlClassRepository.class),
                mock(UmlAttributeRepository.class), mock(UmlMethodRepository.class),
                mock(UmlRelationshipRepository.class), mock(ModelingDecisionRepository.class),
                layouts, new ObjectMapper());
    }

    @Test
    void los_quiebres_se_guardan_en_la_geometria_de_la_relacion() {
        applier.apply(mover("[{\"x\":10,\"y\":20},{\"x\":30,\"y\":40}]"), UUID.randomUUID());

        ArgumentCaptor<DiagramLayout> guardado = ArgumentCaptor.forClass(DiagramLayout.class);
        verify(layouts).save(guardado.capture());
        assertThat(guardado.getValue().getWaypoints()).contains("\"x\":10").contains("\"y\":40");
        assertThat(guardado.getValue().getElementId()).isEqualTo(relationId);
    }

    @Test
    void un_arreglo_vacio_devuelve_la_linea_a_recta() {
        applier.apply(mover("[]"), UUID.randomUUID());

        ArgumentCaptor<DiagramLayout> guardado = ArgumentCaptor.forClass(DiagramLayout.class);
        verify(layouts).save(guardado.capture());
        // null y no "[]": recta es la ausencia de quiebres, no una lista vacia.
        assertThat(guardado.getValue().getWaypoints()).isNull();
    }

    @Test
    void un_quiebre_sin_coordenadas_se_rechaza_antes_de_guardarse() {
        // Un waypoint corrupto no rompe aca: rompe despues, en el canvas de
        // todos los usuarios, que es mucho mas dificil de rastrear.
        CommandRejectedException e = catchThrowableOfType(
                () -> applier.apply(mover("[{\"x\":10}]"), UUID.randomUUID()),
                CommandRejectedException.class);

        assertThat(e.getReason()).isEqualTo(RejectionReason.INVALID);
        verify(layouts, never()).save(any());
    }

    @Test
    void mover_sin_waypoints_sigue_moviendo_la_caja_como_siempre() {
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("elementId", relationId.toString());
        payload.put("x", 100);
        payload.put("y", 200);

        applier.apply(new Command(UUID.randomUUID(), diagramId, 0L, CommandType.MOVE_ELEMENT,
                Origin.MOUSE, payload), UUID.randomUUID());

        ArgumentCaptor<DiagramLayout> guardado = ArgumentCaptor.forClass(DiagramLayout.class);
        verify(layouts).save(guardado.capture());
        assertThat(guardado.getValue().getX()).isEqualTo(100);
        assertThat(guardado.getValue().getWaypoints()).isNull();
    }

    @Test
    void borrar_la_relacion_se_lleva_sus_quiebres() {
        UmlRelationshipRepository relaciones = mock(UmlRelationshipRepository.class);
        DiagramRepository diagrams = mock(DiagramRepository.class);
        DiagramOperationRepository operations = mock(DiagramOperationRepository.class);
        Diagram d = new Diagram();
        d.setId(diagramId);
        when(diagrams.findById(diagramId)).thenReturn(Optional.of(d));
        when(operations.findByOpId(any())).thenReturn(Optional.empty());

        UmlRelationship r = new UmlRelationship();
        r.setId(relationId);
        when(relaciones.findById(relationId)).thenReturn(Optional.of(r));

        DiagramLayout geo = new DiagramLayout();
        geo.setDiagramId(diagramId);
        geo.setElementId(relationId);
        geo.setWaypoints("[{\"x\":1,\"y\":2}]");
        when(layouts.findByDiagramIdAndElementId(diagramId, relationId)).thenReturn(Optional.of(geo));

        CommandApplier conRelaciones = new CommandApplier(diagrams, operations,
                mock(UmlClassRepository.class), mock(UmlAttributeRepository.class),
                mock(UmlMethodRepository.class), relaciones, mock(ModelingDecisionRepository.class), layouts, new ObjectMapper());

        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("relationId", relationId.toString());
        conRelaciones.apply(new Command(UUID.randomUUID(), diagramId, 0L, CommandType.DELETE_RELATION,
                Origin.MOUSE, payload), UUID.randomUUID());

        // Sin esto los quiebres viajan en cada snapshot apuntando a una
        // relacion que ya no existe.
        verify(layouts).delete(any(DiagramLayout.class));
    }

    private Command mover(String waypointsJson) {
        try {
            ObjectNode payload = JsonNodeFactory.instance.objectNode();
            payload.put("elementId", relationId.toString());
            payload.put("elementKind", "RELATION");
            payload.set("waypoints", new ObjectMapper().readTree(waypointsJson));
            return new Command(UUID.randomUUID(), diagramId, 0L, CommandType.MOVE_ELEMENT,
                    Origin.MOUSE, payload);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
