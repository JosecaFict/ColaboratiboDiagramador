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

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * REGLA 6: los metodos del UML no se descartan.
 *
 * Hasta esta iteracion ADD_METHOD y REMOVE_METHOD se rechazaban con un
 * "todavia no esta implementado", asi que la mitad del modelo orientado a
 * objetos se perdia al guardar.
 */
class MetodosTest {

    private final UUID diagramId = UUID.randomUUID();
    private final UUID classId = UUID.randomUUID();

    private UmlMethodRepository methods;
    private CommandApplier applier;

    @BeforeEach
    void prepararDiagrama() {
        DiagramRepository diagrams = mock(DiagramRepository.class);
        DiagramOperationRepository operations = mock(DiagramOperationRepository.class);
        UmlClassRepository classes = mock(UmlClassRepository.class);
        methods = mock(UmlMethodRepository.class);

        Diagram d = new Diagram();
        d.setId(diagramId);
        when(diagrams.findById(diagramId)).thenReturn(Optional.of(d));
        when(operations.findByOpId(any())).thenReturn(Optional.empty());

        UmlClass c = new UmlClass();
        c.setId(classId);
        c.setDiagramId(diagramId);
        c.setName("Libro");
        when(classes.findById(classId)).thenReturn(Optional.of(c));
        when(methods.findByClassIdOrderByOrdinalAsc(classId)).thenReturn(List.of());

        applier = new CommandApplier(diagrams, operations, classes,
                mock(UmlAttributeRepository.class), methods,
                mock(UmlRelationshipRepository.class), mock(ModelingDecisionRepository.class),
                mock(DiagramLayoutRepository.class), new ObjectMapper());
    }

    private Command agregar(ObjectNode payload) {
        return new Command(UUID.randomUUID(), diagramId, 0, CommandType.ADD_METHOD,
                Origin.MOUSE, payload);
    }

    private ObjectNode payloadBase(String nombre) {
        ObjectNode p = JsonNodeFactory.instance.objectNode();
        p.put("methodId", UUID.randomUUID().toString());
        p.put("classId", classId.toString());
        p.put("name", nombre);
        return p;
    }

    @Test
    void un_metodo_con_parametros_se_guarda_con_su_firma() {
        ObjectNode p = payloadBase("prestar");
        p.put("returnType", "boolean");
        p.put("visibility", "PUBLIC");
        var params = p.putArray("params");
        params.addObject().put("name", "socio").put("type", "uuid");
        params.addObject().put("name", "dias").put("type", "int");

        applier.apply(agregar(p), UUID.randomUUID());

        ArgumentCaptor<UmlMethod> guardado = ArgumentCaptor.forClass(UmlMethod.class);
        verify(methods).save(guardado.capture());
        UmlMethod m = guardado.getValue();
        assertThat(m.getName()).isEqualTo("prestar");
        assertThat(m.getReturnType()).isEqualTo("boolean");
        assertThat(m.getVisibility()).isEqualTo(Visibility.PUBLIC);
        assertThat(m.getParams()).contains("socio").contains("int");
    }

    @Test
    void un_metodo_sin_parametros_guarda_una_lista_vacia_y_no_un_null() {
        applier.apply(agregar(payloadBase("recalcularMulta")), UUID.randomUUID());

        ArgumentCaptor<UmlMethod> guardado = ArgumentCaptor.forClass(UmlMethod.class);
        verify(methods).save(guardado.capture());
        // La columna es NOT NULL: un null aca seria una violacion de integridad
        // que aparece recien al hacer flush, lejos de la causa.
        assertThat(guardado.getValue().getParams()).isEqualTo("[]");
        assertThat(guardado.getValue().getVisibility()).isEqualTo(Visibility.PUBLIC);
    }

    @Test
    void un_parametro_sin_nombre_se_rechaza() {
        ObjectNode p = payloadBase("prestar");
        p.putArray("params").addObject().put("type", "int");

        var e = catchThrowableOfType(() -> applier.apply(agregar(p), UUID.randomUUID()),
                CommandRejectedException.class);

        assertThat(e.getReason()).isEqualTo(RejectionReason.INVALID);
        verify(methods, never()).save(any());
    }

    @Test
    void dos_parametros_con_el_mismo_nombre_se_rechazan() {
        ObjectNode p = payloadBase("mover");
        var params = p.putArray("params");
        params.addObject().put("name", "x").put("type", "int");
        params.addObject().put("name", "x").put("type", "int");

        var e = catchThrowableOfType(() -> applier.apply(agregar(p), UUID.randomUUID()),
                CommandRejectedException.class);

        // Si entrara, el Service generado no compilaria y el error saldria
        // recien al correr `mvn package`, lejos de donde se cometio.
        assertThat(e.getMessage()).contains("repetido");
    }

    @Test
    void la_misma_firma_dos_veces_en_la_misma_clase_se_rechaza() {
        UmlMethod existente = new UmlMethod();
        existente.setId(UUID.randomUUID());
        existente.setClassId(classId);
        existente.setName("prestar");
        existente.setParams("[{\"name\":\"dias\",\"type\":\"int\"}]");
        when(methods.findByClassIdOrderByOrdinalAsc(classId)).thenReturn(List.of(existente));

        ObjectNode p = payloadBase("prestar");
        p.putArray("params").addObject().put("name", "cantidad").put("type", "int");

        var e = catchThrowableOfType(() -> applier.apply(agregar(p), UUID.randomUUID()),
                CommandRejectedException.class);

        assertThat(e.getMessage()).contains("prestar(int)").contains("Sobrecargar");
    }

    @Test
    void sobrecargar_cambiando_los_tipos_si_se_permite() {
        UmlMethod existente = new UmlMethod();
        existente.setId(UUID.randomUUID());
        existente.setClassId(classId);
        existente.setName("buscar");
        existente.setParams("[]");
        when(methods.findByClassIdOrderByOrdinalAsc(classId)).thenReturn(List.of(existente));

        ObjectNode p = payloadBase("buscar");
        p.putArray("params").addObject().put("name", "titulo").put("type", "string");

        applier.apply(agregar(p), UUID.randomUUID());

        // buscar() y buscar(String) conviven en Java: rechazarlo seria prohibir
        // algo legitimo.
        verify(methods).save(any());
    }

    @Test
    void un_metodo_sobre_una_clase_que_no_existe_se_rechaza() {
        ObjectNode p = payloadBase("prestar");
        p.put("classId", UUID.randomUUID().toString());

        var e = catchThrowableOfType(() -> applier.apply(agregar(p), UUID.randomUUID()),
                CommandRejectedException.class);

        assertThat(e.getReason()).isEqualTo(RejectionReason.NOT_FOUND);
    }

    @Test
    void quitar_un_metodo_que_no_existe_se_rechaza() {
        UUID id = UUID.randomUUID();
        when(methods.existsById(id)).thenReturn(false);
        ObjectNode p = JsonNodeFactory.instance.objectNode();
        p.put("methodId", id.toString());

        var e = catchThrowableOfType(() -> applier.apply(
                new Command(UUID.randomUUID(), diagramId, 0, CommandType.REMOVE_METHOD,
                        Origin.MOUSE, p), UUID.randomUUID()),
                CommandRejectedException.class);

        assertThat(e.getReason()).isEqualTo(RejectionReason.NOT_FOUND);
        verify(methods, never()).deleteById(any());
    }

    @Test
    void borrar_la_clase_se_lleva_sus_metodos() {
        UmlMethod m = new UmlMethod();
        m.setId(UUID.randomUUID());
        m.setClassId(classId);
        when(methods.findByClassIdOrderByOrdinalAsc(classId)).thenReturn(List.of(m));

        ObjectNode p = JsonNodeFactory.instance.objectNode();
        p.put("classId", classId.toString());
        applier.apply(new Command(UUID.randomUUID(), diagramId, 0, CommandType.DELETE_CLASS,
                Origin.MOUSE, p), UUID.randomUUID());

        // Los metodos no llevan tombstone: sin este borrado quedan apuntando a
        // una clase que ya no existe.
        verify(methods).deleteAll(List.of(m));
    }
}
