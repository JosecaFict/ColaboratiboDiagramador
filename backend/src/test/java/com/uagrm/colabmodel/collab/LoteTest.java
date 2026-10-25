package com.uagrm.colabmodel.collab;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.uagrm.colabmodel.modelcore.command.*;
import com.uagrm.colabmodel.modelcore.metamodel.*;
import com.uagrm.colabmodel.project.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * BATCH: varios cambios, una operacion.
 *
 * La atomicidad real (que un fallo no deje nada) la da la transaccion de
 * CommandApplier.apply y se verifica contra PostgreSQL; aca se prueba todo lo
 * que la rodea: la forma, los topes, el orden y que el rechazo diga donde fallo.
 */
class LoteTest {

    private final UUID diagramId = UUID.randomUUID();
    private final JsonNodeFactory json = JsonNodeFactory.instance;

    private ObjectNode crearClase(UUID id, String nombre) {
        ObjectNode p = json.objectNode();
        p.put("classId", id.toString());
        p.put("name", nombre);
        return p;
    }

    private Command batch(Origin origen, ObjectNode... cambios) {
        return batch(origen, "CREATE_CLASS", cambios);
    }

    private Command batch(Origin origen, String tipo, ObjectNode... payloads) {
        ObjectNode p = json.objectNode();
        p.put("descripcion", "prueba");
        ArrayNode lista = p.putArray("comandos");
        for (ObjectNode payload : payloads) {
            ObjectNode c = lista.addObject();
            c.put("type", tipo);
            c.set("payload", payload);
        }
        return new Command(UUID.randomUUID(), diagramId, 0, CommandType.BATCH, origen, p);
    }

    // ------------------------------------------------------------------ FORMA

    @Test
    void cada_cambio_hereda_el_diagrama_el_origen_y_el_op_id_del_lote() {
        Command b = batch(Origin.XMI_IMPORT, crearClase(UUID.randomUUID(), "A"), crearClase(UUID.randomUUID(), "B"));

        Lote lote = Lote.de(b);

        assertThat(lote.comandos()).hasSize(2).allSatisfy(c -> {
            assertThat(c.opId()).isEqualTo(b.opId());
            assertThat(c.diagramId()).isEqualTo(diagramId);
            assertThat(c.origin()).isEqualTo(Origin.XMI_IMPORT);
            assertThat(c.type()).isEqualTo(CommandType.CREATE_CLASS);
        });
        assertThat(lote.descripcion()).isEqualTo("prueba");
    }

    @Test
    void un_lote_vacio_se_rechaza() {
        ObjectNode sinCambios = json.objectNode();
        sinCambios.putArray("comandos");
        Command vacio = new Command(UUID.randomUUID(), diagramId, 0, CommandType.BATCH, Origin.MOUSE, sinCambios);
        assertThat(catchThrowableOfType(() -> Lote.de(vacio), CommandRejectedException.class).getMessage())
                .contains("al menos un cambio");
    }

    @Test
    void un_lote_no_puede_contener_otro_lote() {
        var e = catchThrowableOfType(() -> Lote.de(batch(Origin.MOUSE, "BATCH", json.objectNode())),
                CommandRejectedException.class);
        assertThat(e.getMessage()).contains("no puede contener otro BATCH");
    }

    @Test
    void confirmar_el_esquema_no_puede_ir_dentro_de_un_lote() {
        // Se confirma lo que se reviso (ADR-032): dentro de un lote, los otros
        // cambios estarian modificando el esquema que se confirma.
        ObjectNode p = json.objectNode();
        p.put("ddlHash", "0".repeat(64));
        var e = catchThrowableOfType(() -> Lote.de(batch(Origin.MOUSE, "CONFIRM_SCHEMA", p)),
                CommandRejectedException.class);
        assertThat(e.getMessage()).contains("Cambio 1").contains("CONFIRM_SCHEMA");
    }

    @Test
    void un_tipo_de_comando_inventado_se_rechaza_con_su_posicion() {
        var e = catchThrowableOfType(() -> Lote.de(batch(Origin.MOUSE, "BORRAR_TODO", json.objectNode())),
                CommandRejectedException.class);
        assertThat(e.getMessage()).contains("Cambio 1").contains("BORRAR_TODO");
    }

    @Test
    void desde_texto_o_voz_el_tope_es_treinta_y_dos_cambios() {
        ObjectNode[] treintaYTres = new ObjectNode[33];
        for (int i = 0; i < 33; i++) treintaYTres[i] = crearClase(UUID.randomUUID(), "C" + i);

        // Un plan completo pequeno entra; uno mayor se divide para mantenerlo revisable.
        var e = catchThrowableOfType(() -> Lote.de(batch(Origin.TEXT, treintaYTres)), CommandRejectedException.class);
        assertThat(e.getMessage()).contains("tope").contains("32");
        // La misma cantidad desde una importacion si entra.
        assertThat(Lote.de(batch(Origin.XMI_IMPORT, treintaYTres)).comandos()).hasSize(33);
    }

    // ------------------------------------------------------------ APLICACION

    private UmlClassRepository classes;
    private CommandApplier applier;

    @BeforeEach
    void preparar() {
        DiagramRepository diagrams = mock(DiagramRepository.class);
        Diagram d = new Diagram();
        d.setId(diagramId);
        when(diagrams.findById(diagramId)).thenReturn(Optional.of(d));
        DiagramOperationRepository operations = mock(DiagramOperationRepository.class);
        when(operations.findByOpId(any())).thenReturn(Optional.empty());
        classes = mock(UmlClassRepository.class);
        applier = new CommandApplier(diagrams, operations, classes, mock(UmlAttributeRepository.class),
                mock(UmlMethodRepository.class), mock(UmlRelationshipRepository.class),
                mock(ModelingDecisionRepository.class), mock(DiagramLayoutRepository.class), new ObjectMapper());
    }

    @Test
    void los_cambios_se_aplican_en_orden_y_ocupan_una_sola_operacion() {
        applier.apply(batch(Origin.XMI_IMPORT, crearClase(UUID.randomUUID(), "Libro"),
                crearClase(UUID.randomUUID(), "Socio")), UUID.randomUUID());

        ArgumentCaptor<UmlClass> guardadas = ArgumentCaptor.forClass(UmlClass.class);
        verify(classes, times(2)).saveAndFlush(guardadas.capture());
        assertThat(guardadas.getAllValues()).extracting(UmlClass::getName).containsExactly("Libro", "Socio");
    }

    @Test
    void el_rechazo_dice_que_cambio_fallo_y_que_no_se_aplico_ninguno() {
        // El segundo cambio agrega un atributo a una clase que no existe.
        ObjectNode atributo = json.objectNode();
        atributo.put("attributeId", UUID.randomUUID().toString());
        atributo.put("classId", UUID.randomUUID().toString());
        atributo.put("name", "titulo");
        ObjectNode p = json.objectNode();
        ArrayNode lista = p.putArray("comandos");
        lista.addObject().put("type", "CREATE_CLASS").set("payload", crearClase(UUID.randomUUID(), "Libro"));
        lista.addObject().put("type", "ADD_ATTRIBUTE").set("payload", atributo);
        Command b = new Command(UUID.randomUUID(), diagramId, 0, CommandType.BATCH, Origin.XMI_IMPORT, p);

        var e = catchThrowableOfType(() -> applier.apply(b, UUID.randomUUID()), CommandRejectedException.class);

        assertThat(e.getOpId()).isEqualTo(b.opId());
        assertThat(e.getReason()).isEqualTo(RejectionReason.NOT_FOUND);
        assertThat(e.getMessage()).contains("Cambio 2 de 2 (ADD_ATTRIBUTE)").contains("No se aplico ninguno");
    }

    // ------------------------------------------------------------------ BUS

    @Test
    void un_lote_no_escribe_sobre_una_clase_que_otra_sesion_esta_editando() {
        UUID usuario = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID clase = UUID.randomUUID();
        DiagramRepository diagrams = mock(DiagramRepository.class);
        Diagram d = new Diagram();
        d.setId(diagramId);
        d.setProjectId(projectId);
        when(diagrams.findById(diagramId)).thenReturn(Optional.of(d));
        ProjectRepository projects = mock(ProjectRepository.class);
        Project pr = new Project();
        pr.setId(projectId);
        pr.setOwnerId(usuario);
        when(projects.findById(projectId)).thenReturn(Optional.of(pr));
        LockManager locks = new LockManager(30);
        locks.acquire(diagramId, clase, usuario, "otra-pestana", "Ana");
        CommandApplier mockApplier = mock(CommandApplier.class);
        DiagramCommandBus bus = new DiagramCommandBus(mockApplier, locks, mock(SimpMessagingTemplate.class),
                new AccesoAProyecto(diagrams, projects, mock(ProjectMemberRepository.class)),
                Bloqueables.sinModelo());

        ObjectNode renombrar = json.objectNode();
        renombrar.put("classId", clase.toString());
        renombrar.put("name", "Otro");
        var e = catchThrowableOfType(() -> bus.submit(batch(Origin.XMI_IMPORT, "RENAME_CLASS", renombrar),
                new Autor(usuario, "esta-pestana")), CommandRejectedException.class);

        assertThat(e.getReason()).isEqualTo(RejectionReason.LOCKED_BY_OTHER);
        assertThat(e.getMessage()).contains("Cambio 1 de 1 (RENAME_CLASS)").contains("Ana");
        verify(mockApplier, never()).apply(any(), any());
    }
}
