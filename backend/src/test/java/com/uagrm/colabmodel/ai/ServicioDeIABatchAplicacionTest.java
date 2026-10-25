package com.uagrm.colabmodel.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.uagrm.colabmodel.collab.CommandApplier;
import com.uagrm.colabmodel.modelcore.command.Command;
import com.uagrm.colabmodel.modelcore.command.Origin;
import com.uagrm.colabmodel.modelcore.metamodel.*;
import com.uagrm.colabmodel.project.*;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * No alcanza con que `PropuestaDeIA` DIGA que hay un maximo de cambios: hay
 * que probar que, cuando eso se manda por el mismo camino que cualquier otro
 * BATCH (CommandApplier, ADR-002/ADR-016), el efecto real sobre el modelo
 * tampoco pasa de 32, aunque el proveedor haya propuesto 50.
 */
class ServicioDeIABatchAplicacionTest {

    private static final JsonNodeFactory J = JsonNodeFactory.instance;
    private final UUID diagramId = UUID.randomUUID();

    @Test
    void aplicar_el_batch_de_la_ia_nunca_crea_mas_de_treinta_y_dos_clases_aunque_el_modelo_proponga_cincuenta() {
        UmlClassRepository classes = mock(UmlClassRepository.class);
        when(classes.findByDiagramIdAndDeletedAtIsNullOrderByNameAsc(diagramId)).thenReturn(List.of());
        UmlAttributeRepository attributes = mock(UmlAttributeRepository.class);
        UmlMethodRepository methods = mock(UmlMethodRepository.class);
        UmlRelationshipRepository relationships = mock(UmlRelationshipRepository.class);
        when(relationships.findByDiagramIdAndDeletedAtIsNull(diagramId)).thenReturn(List.of());

        List<ComandoPropuestoIA> cincuenta = new ArrayList<>();
        for (int i = 1; i <= 50; i++) {
            ObjectNode p = J.objectNode();
            p.put("name", "Clase" + i);
            cincuenta.add(new ComandoPropuestoIA("CREATE_CLASS", p));
        }
        ServicioDeIA servicio = new ServicioDeIA(new ProveedorDeIAFalso(new RespuestaIA(cincuenta, "")),
                classes, attributes, methods, relationships);

        PropuestaDeIA propuesta = servicio.generar(diagramId, "disename un sistema completo de biblioteca", Origin.TEXT);
        Command batch = servicio.comandoBatchDe(propuesta);
        assertThat(propuesta.aceptados()).hasSize(32);

        DiagramRepository diagrams = mock(DiagramRepository.class);
        Diagram d = new Diagram();
        d.setId(diagramId);
        when(diagrams.findById(diagramId)).thenReturn(Optional.of(d));
        DiagramOperationRepository operations = mock(DiagramOperationRepository.class);
        when(operations.findByOpId(any())).thenReturn(Optional.empty());
        CommandApplier applier = new CommandApplier(diagrams, operations, classes, attributes, methods,
                relationships, mock(ModelingDecisionRepository.class), mock(DiagramLayoutRepository.class),
                new ObjectMapper());

        applier.apply(batch, UUID.randomUUID());

        // El efecto real, no solo la propuesta: 32 clases guardadas, ni una mas
        // de las 50 que el proveedor simulado ofrecio.
        verify(classes, times(32)).saveAndFlush(any());
    }
}
