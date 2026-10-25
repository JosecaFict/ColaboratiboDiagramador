package com.uagrm.colabmodel.collab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.uagrm.colabmodel.modelcore.command.*;
import com.uagrm.colabmodel.modelcore.mapping.Ambiguedad;
import com.uagrm.colabmodel.modelcore.metamodel.*;
import com.uagrm.colabmodel.project.*;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Un diagrama con modelo, geometria y log de verdad, pero en memoria.
 *
 * Las pruebas de deshacer necesitan ESTADO: la precondicion compara contra lo
 * que quedo guardado, el log tiene que devolver el autor de la operacion que
 * se deshace, y el bus tiene que resolver de que clase es un atributo para
 * revisar su lease. Con mocks sueltos cada prueba estaria afirmando lo que ella
 * misma programo. El applier, el bus, los locks y el resolvedor son los reales.
 */
class ModeloEnMemoria {

    private static final ObjectMapper JSON = new ObjectMapper();

    final UUID diagramId = UUID.randomUUID();
    final UUID projectId = UUID.randomUUID();
    /** Duenia del proyecto. */
    final UUID ana = UUID.randomUUID();
    /** Modelador del mismo proyecto. */
    final UUID beto = UUID.randomUUID();

    final Map<UUID, DiagramLayout> layouts = new ConcurrentHashMap<>();
    final Map<UUID, DiagramOperation> log = new ConcurrentHashMap<>();
    final Map<UUID, UmlClass> clases = new ConcurrentHashMap<>();
    final Map<UUID, UmlAttribute> atributos = new ConcurrentHashMap<>();
    final Map<UUID, UmlRelationship> relaciones = new ConcurrentHashMap<>();
    final Map<UUID, UmlMethod> metodos = new ConcurrentHashMap<>();
    final Map<String, ModelingDecision> decisiones = new ConcurrentHashMap<>();
    final Diagram diagrama = new Diagram();

    final LockManager locks = new LockManager(30);
    final CommandApplier applier;
    final DiagramCommandBus bus;

    ModeloEnMemoria() {
        diagrama.setId(diagramId);
        diagrama.setProjectId(projectId);

        DiagramRepository diagrams = mock(DiagramRepository.class);
        when(diagrams.findById(diagramId)).thenReturn(Optional.of(diagrama));

        DiagramOperationRepository operations = mock(DiagramOperationRepository.class);
        when(operations.findByOpId(any())).thenAnswer(i -> Optional.ofNullable(log.get(i.<UUID>getArgument(0))));
        when(operations.save(any())).thenAnswer(i -> guardar(log, i.<DiagramOperation>getArgument(0).getOpId(), i.getArgument(0)));

        DiagramLayoutRepository repoLayouts = mock(DiagramLayoutRepository.class);
        when(repoLayouts.findByDiagramIdAndElementId(any(), any()))
                .thenAnswer(i -> Optional.ofNullable(layouts.get(i.<UUID>getArgument(1))));
        when(repoLayouts.save(any())).thenAnswer(i -> guardar(layouts, i.<DiagramLayout>getArgument(0).getElementId(), i.getArgument(0)));
        doAnswer(i -> layouts.remove(i.<DiagramLayout>getArgument(0).getElementId())).when(repoLayouts).delete(any());

        UmlClassRepository repoClases = mock(UmlClassRepository.class);
        when(repoClases.findById(any())).thenAnswer(i -> Optional.ofNullable(clases.get(i.<UUID>getArgument(0))));
        when(repoClases.save(any())).thenAnswer(i -> guardar(clases, i.<UmlClass>getArgument(0).getId(), i.getArgument(0)));
        when(repoClases.saveAndFlush(any())).thenAnswer(i -> guardar(clases, i.<UmlClass>getArgument(0).getId(), i.getArgument(0)));

        UmlAttributeRepository repoAtributos = mock(UmlAttributeRepository.class);
        when(repoAtributos.findById(any())).thenAnswer(i -> Optional.ofNullable(atributos.get(i.<UUID>getArgument(0))));
        when(repoAtributos.save(any())).thenAnswer(i -> guardar(atributos, i.<UmlAttribute>getArgument(0).getId(), i.getArgument(0)));
        when(repoAtributos.saveAndFlush(any())).thenAnswer(i -> guardar(atributos, i.<UmlAttribute>getArgument(0).getId(), i.getArgument(0)));
        when(repoAtributos.existsById(any())).thenAnswer(i -> atributos.containsKey(i.<UUID>getArgument(0)));
        when(repoAtributos.findByClassId(any())).thenAnswer(i -> atributos.values().stream()
                .filter(a -> a.getClassId().equals(i.getArgument(0))).toList());
        doAnswer(i -> atributos.remove(i.<UUID>getArgument(0))).when(repoAtributos).deleteById(any());
        doAnswer(i -> {
            i.<Iterable<UmlAttribute>>getArgument(0).forEach(a -> atributos.remove(a.getId()));
            return null;
        }).when(repoAtributos).deleteAll(any());

        UmlRelationshipRepository repoRelaciones = mock(UmlRelationshipRepository.class);
        when(repoRelaciones.findById(any())).thenAnswer(i -> Optional.ofNullable(relaciones.get(i.<UUID>getArgument(0))));
        when(repoRelaciones.save(any())).thenAnswer(i -> guardar(relaciones, i.<UmlRelationship>getArgument(0).getId(), i.getArgument(0)));
        when(repoRelaciones.findBySourceClassIdOrTargetClassId(any(), any())).thenAnswer(i -> relaciones.values().stream()
                .filter(r -> r.getSourceClassId().equals(i.getArgument(0)) || r.getTargetClassId().equals(i.getArgument(1)))
                .toList());

        ModelingDecisionRepository repoDecisiones = mock(ModelingDecisionRepository.class);
        when(repoDecisiones.findByDiagramIdAndElementIdAndAmbiguityKind(any(), any(), any()))
                .thenAnswer(i -> Optional.ofNullable(decisiones.get(clave(i.getArgument(1), i.getArgument(2)))));
        when(repoDecisiones.save(any())).thenAnswer(i -> {
            ModelingDecision d = i.getArgument(0);
            return guardar(decisiones, clave(d.getElementId(), d.getAmbiguityKind()), d);
        });

        UmlMethodRepository repoMetodos = mock(UmlMethodRepository.class);
        when(repoMetodos.findById(any())).thenAnswer(i -> Optional.ofNullable(metodos.get(i.<UUID>getArgument(0))));
        when(repoMetodos.existsById(any())).thenAnswer(i -> metodos.containsKey(i.<UUID>getArgument(0)));
        when(repoMetodos.save(any())).thenAnswer(i -> guardar(metodos, i.<UmlMethod>getArgument(0).getId(), i.getArgument(0)));
        when(repoMetodos.findByClassIdOrderByOrdinalAsc(any())).thenAnswer(i -> metodos.values().stream()
                .filter(m -> m.getClassId().equals(i.getArgument(0))).toList());
        doAnswer(i -> metodos.remove(i.<UUID>getArgument(0))).when(repoMetodos).deleteById(any());
        doAnswer(i -> {
            i.<Iterable<UmlMethod>>getArgument(0).forEach(m -> metodos.remove(m.getId()));
            return null;
        }).when(repoMetodos).deleteAll(any());
        applier = new CommandApplier(diagrams, operations, repoClases, repoAtributos, repoMetodos,
                repoRelaciones, repoDecisiones, repoLayouts, JSON);

        ProjectRepository projects = mock(ProjectRepository.class);
        Project p = new Project();
        p.setId(projectId);
        p.setOwnerId(ana);
        when(projects.findById(projectId)).thenReturn(Optional.of(p));
        ProjectMemberRepository members = mock(ProjectMemberRepository.class);
        ProjectMember editor = new ProjectMember();
        editor.setProjectId(projectId);
        editor.setUserId(beto);
        editor.setRole("EDITOR");
        when(members.findByProjectIdAndUserId(projectId, beto)).thenReturn(Optional.of(editor));

        bus = new DiagramCommandBus(applier, locks, mock(SimpMessagingTemplate.class),
                new AccesoAProyecto(diagrams, projects, members),
                new EntidadBloqueable(repoAtributos, repoMetodos));
    }

    private static <K, V> V guardar(Map<K, V> mapa, K clave, V valor) {
        mapa.put(clave, valor);
        return valor;
    }

    private static String clave(Object elementId, Object tipo) { return elementId + "/" + tipo; }

    // ------------------------------------------------------------ MODELO

    /** Una clase ya dibujada, como la deja CREATE_CLASS. */
    UUID clase(double x, double y) {
        return clase("C" + clases.size(), x, y);
    }

    UUID clase(String nombre, double x, double y) {
        UUID id = UUID.randomUUID();
        UmlClass c = new UmlClass();
        c.setId(id);
        c.setDiagramId(diagramId);
        c.setName(nombre);
        c.setErKind(ErKind.STRONG);
        c.setInheritanceStrategy(InheritanceStrategy.JOINED);
        clases.put(id, c);
        DiagramLayout l = new DiagramLayout();
        l.setDiagramId(diagramId);
        l.setElementId(id);
        l.setElementKind("CLASS");
        l.setX(x);
        l.setY(y);
        layouts.put(id, l);
        return id;
    }

    UUID atributo(UUID classId, String nombre, String tipo) {
        UUID id = UUID.randomUUID();
        UmlAttribute a = new UmlAttribute();
        a.setId(id);
        a.setClassId(classId);
        a.setName(nombre);
        a.setDataType(tipo);
        a.setVisibility(Visibility.PRIVATE);
        atributos.put(id, a);
        return id;
    }

    UUID relacion(UUID origen, UUID destino) {
        UUID id = UUID.randomUUID();
        UmlRelationship r = new UmlRelationship();
        r.setId(id);
        r.setDiagramId(diagramId);
        r.setSourceClassId(origen);
        r.setTargetClassId(destino);
        r.setKind(RelationKind.ASSOCIATION);
        r.setSourceMultiplicity("1");
        r.setTargetMultiplicity("0..*");
        r.setDegree(2);
        relaciones.put(id, r);
        return id;
    }

    UUID metodo(UUID classId, String nombre) {
        UUID id = UUID.randomUUID();
        UmlMethod m = new UmlMethod();
        m.setId(id);
        m.setClassId(classId);
        m.setName(nombre);
        m.setReturnType("void");
        m.setParams("[]");
        m.setVisibility(Visibility.PUBLIC);
        metodos.put(id, m);
        return id;
    }

    /** Viva = sin tombstone, como la leen el snapshot y la Vista Logica. */
    boolean viva(UUID classId) {
        UmlClass c = clases.get(classId);
        return c != null && c.getDeletedAt() == null;
    }

    Ambiguedad.Lectura decision(UUID elementId, Ambiguedad.TipoAmbiguedad tipo) {
        ModelingDecision d = decisiones.get(clave(elementId, tipo));
        return d == null ? null : d.getChosen();
    }

    // ---------------------------------------------------------- COMANDOS

    Command mover(UUID elementId, double x, double y) {
        ObjectNode p = JsonNodeFactory.instance.objectNode();
        p.put("elementId", elementId.toString());
        p.put("elementKind", "CLASS");
        p.put("x", x);
        p.put("y", y);
        return comando(CommandType.MOVE_ELEMENT, p);
    }

    /** Exactamente lo que arma el cliente al apretar Ctrl+Z (historialLocal.ts). */
    Command deshacerMover(UUID elementId, UUID deshace, double haciaX, double haciaY,
                          double esperadoX, double esperadoY) {
        ObjectNode p = (ObjectNode) mover(elementId, haciaX, haciaY).payload();
        p.put("deshace", deshace.toString());
        p.putObject("esperado").put("x", esperadoX).put("y", esperadoY);
        return comando(CommandType.MOVE_ELEMENT, p);
    }

    /** Un comando escrito como JSON, con comillas simples para que se lea en la prueba. */
    Command comando(CommandType tipo, String payload) {
        try {
            return comando(tipo, JSON.readTree(payload.replace('\'', '"')));
        } catch (Exception e) {
            throw new IllegalArgumentException(payload, e);
        }
    }

    /** El mismo comando convertido en undo: `deshace` + `esperado`. */
    Command deshacer(CommandType tipo, String payload, UUID deshace, String esperado) {
        ObjectNode p = (ObjectNode) comando(tipo, payload).payload();
        p.put("deshace", deshace.toString());
        try {
            p.set("esperado", JSON.readTree(esperado.replace('\'', '"')));
        } catch (Exception e) {
            throw new IllegalArgumentException(esperado, e);
        }
        return comando(tipo, p);
    }

    Command comando(CommandType tipo, JsonNode payload) {
        return new Command(UUID.randomUUID(), diagramId, diagrama.getCurrentSeq(), tipo, Origin.MOUSE, payload);
    }

    double x(UUID elementId) { return layouts.get(elementId).getX(); }
    double y(UUID elementId) { return layouts.get(elementId).getY(); }
}
