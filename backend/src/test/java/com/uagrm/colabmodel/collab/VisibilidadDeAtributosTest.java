package com.uagrm.colabmodel.collab;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.uagrm.colabmodel.modelcore.command.Command;
import com.uagrm.colabmodel.modelcore.command.CommandRejectedException;
import com.uagrm.colabmodel.modelcore.command.CommandType;
import com.uagrm.colabmodel.modelcore.command.Origin;
import com.uagrm.colabmodel.modelcore.metamodel.*;
import com.uagrm.colabmodel.project.Diagram;
import com.uagrm.colabmodel.project.DiagramOperationRepository;
import com.uagrm.colabmodel.project.DiagramRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * UML 2.5.1: una Property lleva visibilidad, igual que una Operation.
 *
 * Antes no se podia ni expresar. `uml_method` la guardaba desde la Fase 0 y
 * `uml_attribute` no, asi que el canvas dibujaba "-" fijo delante de TODO
 * atributo: la vista afirmaba algo que el modelo no decia.
 */
class VisibilidadDeAtributosTest {

    private final UUID diagramId = UUID.randomUUID();
    private final UUID classId = UUID.randomUUID();
    private final UUID attributeId = UUID.randomUUID();

    private UmlAttributeRepository attributes;
    private CommandApplier applier;

    @BeforeEach
    void prepararDiagrama() {
        DiagramRepository diagrams = mock(DiagramRepository.class);
        DiagramOperationRepository operations = mock(DiagramOperationRepository.class);
        UmlClassRepository classes = mock(UmlClassRepository.class);
        attributes = mock(UmlAttributeRepository.class);

        Diagram d = new Diagram();
        d.setId(diagramId);
        when(diagrams.findById(diagramId)).thenReturn(Optional.of(d));
        when(operations.findByOpId(any())).thenReturn(Optional.empty());

        UmlClass c = new UmlClass();
        c.setId(classId);
        c.setDiagramId(diagramId);
        c.setName("Libro");
        when(classes.findById(classId)).thenReturn(Optional.of(c));

        applier = new CommandApplier(diagrams, operations, classes, attributes,
                mock(UmlMethodRepository.class), mock(UmlRelationshipRepository.class),
                mock(ModelingDecisionRepository.class), mock(DiagramLayoutRepository.class),
                new ObjectMapper());
    }

    @Test
    @DisplayName("Un atributo puede declararse publico")
    void se_guarda_la_visibilidad_declarada() {
        ObjectNode p = payload("titulo");
        p.put("visibility", "PUBLIC");

        applier.apply(comando(CommandType.ADD_ATTRIBUTE, p), UUID.randomUUID());

        assertThat(guardado().getVisibility()).isEqualTo(Visibility.PUBLIC);
    }

    @Test
    @DisplayName("Sin declararla queda PRIVATE, que es lo que el canvas ya dibujaba")
    void el_valor_por_defecto_no_mueve_ningun_diagrama() {
        applier.apply(comando(CommandType.ADD_ATTRIBUTE, payload("titulo")), UUID.randomUUID());

        // Importa que sea PRIVATE y no PUBLIC: asi la migracion no cambia como
        // se ve ni uno de los diagramas que ya existen.
        assertThat(guardado().getVisibility()).isEqualTo(Visibility.PRIVATE);
    }

    @Test
    @DisplayName("Los cuatro simbolos UML se aceptan")
    void los_cuatro_valores() {
        for (Visibility v : Visibility.values()) {
            ObjectNode p = payload("campo");
            p.put("visibility", v.name());
            applier.apply(comando(CommandType.ADD_ATTRIBUTE, p), UUID.randomUUID());
            assertThat(guardado().getVisibility()).isEqualTo(v);
            reset(attributes);
        }
        assertThat(Visibility.PACKAGE.simbolo()).isEqualTo("~");
    }

    @Test
    @DisplayName("Se puede cambiar despues, sin tocar el resto del atributo")
    void se_actualiza_sola() {
        UmlAttribute existente = new UmlAttribute();
        existente.setId(attributeId);
        existente.setClassId(classId);
        existente.setName("titulo");
        existente.setDataType("string");
        existente.setRequired(true);
        when(attributes.findById(attributeId)).thenReturn(Optional.of(existente));

        ObjectNode p = JsonNodeFactory.instance.objectNode();
        p.put("attributeId", attributeId.toString());
        p.put("visibility", "PROTECTED");

        applier.apply(comando(CommandType.UPDATE_ATTRIBUTE, p), UUID.randomUUID());

        UmlAttribute a = guardado();
        assertThat(a.getVisibility()).isEqualTo(Visibility.PROTECTED);
        assertThat(a.getName()).isEqualTo("titulo");
        assertThat(a.isRequired()).isTrue();
    }

    @Test
    @DisplayName("Una visibilidad que no existe en UML se rechaza al entrar")
    void visibilidad_invalida() {
        ObjectNode p = payload("titulo");
        p.put("visibility", "INTERNAL");   // existe en C#, no en UML

        var error = catchThrowableOfType(
                () -> applier.apply(comando(CommandType.ADD_ATTRIBUTE, p), UUID.randomUUID()),
                CommandRejectedException.class);

        assertThat(error).isNotNull();
        verify(attributes, never()).saveAndFlush(any());
    }

    // ------------------------------------------------------------- UTILES

    private ObjectNode payload(String nombre) {
        ObjectNode p = JsonNodeFactory.instance.objectNode();
        p.put("attributeId", UUID.randomUUID().toString());
        p.put("classId", classId.toString());
        p.put("name", nombre);
        return p;
    }

    private Command comando(CommandType tipo, ObjectNode payload) {
        return new Command(UUID.randomUUID(), diagramId, 0, tipo, Origin.MOUSE, payload);
    }

    private UmlAttribute guardado() {
        ArgumentCaptor<UmlAttribute> captor = ArgumentCaptor.forClass(UmlAttribute.class);
        verify(attributes).saveAndFlush(captor.capture());
        return captor.getValue();
    }
}
