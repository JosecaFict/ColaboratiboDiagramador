package com.uagrm.colabmodel.collab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.uagrm.colabmodel.modelcore.command.*;
import com.uagrm.colabmodel.modelcore.mapping.Ambiguedad;
import com.uagrm.colabmodel.modelcore.metamodel.*;
import com.uagrm.colabmodel.project.DiagramOperation;
import com.uagrm.colabmodel.project.DiagramOperationRepository;

import java.math.BigDecimal;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * DESHACER SIN PISAR A NADIE (ADR-027).
 *
 * Un undo es un comando comun del catalogo con dos marcas en el payload:
 *   - `deshace`: la operacion PROPIA que revierte. Se valida porque queda en
 *     el log como auditoria, y un "deshizo la operacion de otro" falso seria
 *     peor que no tenerla.
 *   - `esperado`: el estado que el objetivo tiene que tener para aplicar. Es
 *     un compare-and-set: si otro lo cambio despues, se rechaza con STALE.
 *
 * Se evalua dentro del hilo unico del diagrama (ADR-005), antes del efecto:
 * el cliente puede estar una operacion atrasado, el secuenciador no. Por eso
 * "otro lo cambio despues" no se puede pisar aunque el undo y el cambio ajeno
 * lleguen en el mismo milisegundo.
 *
 * Solo se comparan las claves que trae `esperado`. Un UPDATE_ATTRIBUTE que
 * revierte el tipo no se rechaza porque otro haya cambiado el nombre: se
 * revierte lo propio y se deja lo ajeno (degradar, no rechazar).
 *
 * Crear y borrar tambien (ADR-030): deshacer un CREATE es un DELETE que solo
 * pasa si nadie le agrego nada, y deshacer un DELETE es volver a crear con los
 * MISMOS ids, que solo pasa si sigue borrado.
 *
 * Vive aparte del CommandApplier para que el applier siga diciendo QUE le pasa
 * al modelo; esto dice CUANDO se puede. No es un bean: lo arma el applier con
 * sus mismos repositorios.
 */
final class PrecondicionDeDeshacer {

    /** Como se llama cada campo en la interfaz, para que el rechazo se entienda. */
    private static final Map<String, String> NOMBRE_DEL_CAMPO = Map.ofEntries(
            Map.entry("x", "la posicion"), Map.entry("y", "la posicion"),
            Map.entry("width", "el tamano"), Map.entry("height", "el tamano"),
            Map.entry("waypoints", "los quiebres de la linea"),
            Map.entry("name", "el nombre"), Map.entry("dataType", "el tipo"),
            Map.entry("chosen", "la decision"),
            Map.entry("attributeIds", "los atributos de la clase"),
            Map.entry("methodIds", "los metodos de la clase"),
            Map.entry("relationIds", "las relaciones de la clase"),
            Map.entry("eliminada", "si estaba borrada (alguien la restauro)"),
            Map.entry("existe", "si existia (alguien lo agrego)"));

    private final DiagramOperationRepository operations;
    private final UmlClassRepository classes;
    private final UmlAttributeRepository attributes;
    private final UmlMethodRepository methods;
    private final UmlRelationshipRepository relationships;
    private final ModelingDecisionRepository decisions;
    private final DiagramLayoutRepository layouts;
    private final ObjectMapper mapper;

    PrecondicionDeDeshacer(DiagramOperationRepository operations, UmlClassRepository classes,
                           UmlAttributeRepository attributes, UmlMethodRepository methods,
                           UmlRelationshipRepository relationships,
                           ModelingDecisionRepository decisions, DiagramLayoutRepository layouts,
                           ObjectMapper mapper) {
        this.operations = operations;
        this.classes = classes;
        this.attributes = attributes;
        this.methods = methods;
        this.relationships = relationships;
        this.decisions = decisions;
        this.layouts = layouts;
        this.mapper = mapper;
    }

    /**
     * @throws CommandRejectedException INVALID si las marcas estan mal formadas
     *         o apuntan a algo ajeno, NOT_FOUND si el objetivo ya no existe,
     *         STALE si otro lo cambio despues
     */
    void exigir(Command command, UUID userId) {
        JsonNode p = command.payload();
        JsonNode esperado = p.get("esperado");
        boolean tieneEsperado = esperado != null && !esperado.isNull();
        if (!p.has("deshace") && !tieneEsperado) return;

        if (p.has("deshace")) {
            // Sin precondicion, un undo restauraria lo viejo aunque otro ya
            // hubiera cambiado el elemento: eso es pisar lo ajeno.
            if (!tieneEsperado) {
                throw invalid(command, "Un undo necesita \"esperado\": sin eso podria pisar el cambio de otro.");
            }
            exigirQueDeshagaAlgoPropio(command, userId);
        }
        if (!esperado.isObject()) throw invalid(command, "\"esperado\" tiene que ser un objeto");

        ObjectNode vigente = estadoVigente(command);
        // La geometria admite 0.01 porque la base guarda double y el cliente
        // redondea; un rango decimal (Regla 9) se compara exacto.
        BigDecimal tolerancia = command.type() == CommandType.MOVE_ELEMENT
                || command.type() == CommandType.RESIZE_ELEMENT ? new BigDecimal("0.01") : BigDecimal.ZERO;
        for (Iterator<String> it = esperado.fieldNames(); it.hasNext(); ) {
            String campo = it.next();
            if (!mismoValor(esperado.get(campo), vigente.get(campo), tolerancia)) {
                throw new CommandRejectedException(command.opId(), RejectionReason.STALE,
                        "Otra persona cambio " + NOMBRE_DEL_CAMPO.getOrDefault(campo, "\"" + campo + "\"")
                                + " despues que vos: no se aplico, para no pisar su cambio.");
            }
        }
    }

    /** Rehacer no necesita marca propia: es deshacer el deshacer y apunta al opId del undo. */
    private void exigirQueDeshagaAlgoPropio(Command command, UUID userId) {
        UUID original = uuid(command, "deshace");
        DiagramOperation op = operations.findByOpId(original)
                .filter(o -> o.getDiagramId().equals(command.diagramId()))
                .orElseThrow(() -> notFound(command, "La operacion que se quiere deshacer no existe en este diagrama."));
        if (!Objects.equals(op.getUserId(), userId)) {
            throw invalid(command, "Solo se puede deshacer una operacion propia: cada quien deshace lo suyo.");
        }
    }

    /**
     * Lo que el objetivo del comando tiene AHORA, con las mismas claves que
     * usa su payload: asi `esperado` se escribe igual que el comando.
     *
     * Cubre los 17 comandos del catalogo: todos se pueden deshacer desde el
     * escalon 3 (ADR-030). Un comando nuevo tiene que sumar su caso aca.
     */
    private ObjectNode estadoVigente(Command command) {
        JsonNode p = command.payload();
        ObjectNode n = mapper.createObjectNode();
        switch (command.type()) {
            case MOVE_ELEMENT, RESIZE_ELEMENT -> {
                // Sin esto el upsert del applier le crearia geometria a un
                // elemento borrado, y el snapshot la mandaria para siempre.
                DiagramLayout l = layouts.findByDiagramIdAndElementId(command.diagramId(), uuid(command, "elementId"))
                        .orElseThrow(() -> notFound(command,
                                "El elemento ya no existe: alguien lo borro despues. No hay nada que deshacer."));
                n.put("x", l.getX()).put("y", l.getY()).put("width", l.getWidth()).put("height", l.getHeight());
                n.set("waypoints", quiebres(l.getWaypoints()));
            }
            case RENAME_CLASS -> n.put("name", clase(command, uuid(command, "classId")).getName());
            case UPDATE_ATTRIBUTE -> volcarAtributo(n, atributo(command, uuid(command, "attributeId")));
            case UPDATE_RELATION -> volcarRelacion(n, relaciones(command));
            case SET_ER_FLAG -> {
                String flag = p.path("flag").asText();
                UUID target = uuid(command, "targetId");
                // `value`, `erKind` y `strategy` son las claves con que
                // SET_ER_FLAG escribe cada bandera: se leen con las mismas.
                if ("CLASS".equals(p.path("targetType").asText())) {
                    UmlClass c = clase(command, target);
                    if ("weak".equals(flag)) n.put("value", c.isWeak());
                    n.put("erKind", texto(c.getErKind()));
                    n.put("strategy", texto(c.getInheritanceStrategy()));
                } else {
                    ObjectNode a = volcarAtributo(mapper.createObjectNode(), atributo(command, target));
                    n.set("value", a.get(flag));
                }
            }
            case SET_MODELING_DECISION -> {
                // Despues de decidir SIEMPRE hay fila: la dejo la operacion que
                // se deshace. Por eso no hace falta repetir aca la lectura por
                // defecto de cada ambiguedad, que vive en Ambiguedades.
                Ambiguedad.TipoAmbiguedad tipo;
                try {
                    tipo = Ambiguedad.TipoAmbiguedad.valueOf(p.path("ambiguityKind").asText());
                } catch (IllegalArgumentException e) {
                    throw invalid(command, "ambiguityKind invalido");
                }
                decisions.findByDiagramIdAndElementIdAndAmbiguityKind(
                                command.diagramId(), uuid(command, "elementId"), tipo)
                        .ifPresent(d -> n.put("chosen", texto(d.getChosen())));
            }
            // Deshacer un CREATE_CLASS es borrarla: solo si sigue como la
            // deje. Si otro le agrego un atributo o una relacion, borrarla se
            // llevaria su trabajo (ADR-027 D): se rechaza.
            case DELETE_CLASS -> {
                UmlClass c = clase(command, uuid(command, "classId"));
                n.put("name", c.getName());
                n.set("attributeIds", idsOrdenados(attributes.findByClassId(c.getId()).stream()
                        .map(UmlAttribute::getId)));
                n.set("methodIds", idsOrdenados(methods.findByClassIdOrderByOrdinalAsc(c.getId()).stream()
                        .map(UmlMethod::getId)));
                n.set("relationIds", idsOrdenados(relationships.findBySourceClassIdOrTargetClassId(c.getId(), c.getId())
                        .stream().filter(r -> r.getDeletedAt() == null).map(UmlRelationship::getId)));
            }
            // Restaurar (deshacer un borrado, o rehacer una creacion) reusa el
            // mismo id: tiene que seguir borrado. Si nunca existio, no hay
            // nada que restaurar.
            case CREATE_CLASS -> n.put("eliminada", classes.findById(uuid(command, "classId"))
                    .orElseThrow(() -> notFound(command, "La clase que se queria restaurar no existe."))
                    .getDeletedAt() != null);
            case CREATE_RELATION -> n.put("eliminada", relationships.findById(uuid(command, "relationId"))
                    .orElseThrow(() -> notFound(command, "La relacion que se queria restaurar no existe."))
                    .getDeletedAt() != null);
            case ADD_ATTRIBUTE -> n.put("existe", attributes.existsById(uuid(command, "attributeId")));
            case ADD_METHOD -> n.put("existe", methods.existsById(uuid(command, "methodId")));
            // Quitar lo que uno agrego: solo si nadie lo edito despues.
            case REMOVE_ATTRIBUTE -> volcarAtributo(n, atributo(command, uuid(command, "attributeId")));
            case REMOVE_METHOD -> {
                UmlMethod m = methods.findById(uuid(command, "methodId"))
                        .orElseThrow(() -> notFound(command, "El metodo ya no existe: alguien lo quito despues."));
                n.put("name", m.getName());
                n.put("returnType", m.getReturnType());
                n.put("visibility", texto(m.getVisibility()));
            }
            case DELETE_RELATION -> volcarRelacion(n, relaciones(command));
            // El lote no tiene estado propio: cada cambio de adentro trae su
            // `esperado` y se revisa al aplicarse (aplicarLote -> aplicarEfecto).
            case BATCH -> { }
        }
        return n;
    }

    private UmlRelationship relaciones(Command command) {
        return relationships.findById(uuid(command, "relationId"))
                .filter(x -> x.getDeletedAt() == null)
                .orElseThrow(() -> notFound(command, "La relacion ya no existe: alguien la borro despues."));
    }

    private void volcarRelacion(ObjectNode n, UmlRelationship r) {
        n.put("kind", texto(r.getKind()));
        n.put("name", r.getName());
        n.put("sourceRole", r.getSourceRole());
        n.put("targetRole", r.getTargetRole());
        n.put("sourceMultiplicity", r.getSourceMultiplicity());
        n.put("targetMultiplicity", r.getTargetMultiplicity());
        n.put("identifying", r.isIdentifying());
        n.put("sourceParticipation", texto(r.getSourceParticipation()));
        n.put("targetParticipation", texto(r.getTargetParticipation()));
    }

    /** Ordenados: el cliente manda los suyos ordenados igual, y se comparan en orden. */
    private ArrayNode idsOrdenados(java.util.stream.Stream<UUID> ids) {
        ArrayNode a = mapper.createArrayNode();
        ids.map(UUID::toString).sorted().forEach(a::add);
        return a;
    }

    private ObjectNode volcarAtributo(ObjectNode n, UmlAttribute a) {
        n.put("name", a.getName());
        n.put("dataType", a.getDataType());
        n.put("visibility", texto(a.getVisibility()));
        n.put("length", a.getLength());
        n.put("pk", a.isPk());
        n.put("required", a.isRequired());
        n.put("unique", a.isUnique());
        n.put("defaultValue", a.getDefaultValue());
        n.put("ordinal", a.getOrdinal());
        n.put("multivalued", a.isMultivalued());
        n.put("derived", a.isDerived());
        n.put("partialKey", a.isPartialKey());
        n.put("minValue", a.getMinValue());
        n.put("maxValue", a.getMaxValue());
        return n;
    }

    /**
     * Igualdad tolerante a como viajan los valores, no a lo que significan:
     * ausente = null = texto en blanco (asi los trata el applier), un numero
     * puede llegar como texto desde un campo del panel, y `10` es `10.00`.
     */
    private boolean mismoValor(JsonNode a, JsonNode b, BigDecimal tolerancia) {
        if (vacio(a) || vacio(b)) return vacio(a) && vacio(b);
        BigDecimal na = numero(a), nb = numero(b);
        if (na != null && nb != null) return na.subtract(nb).abs().compareTo(tolerancia) <= 0;
        if (a.isArray() && b.isArray()) {
            if (a.size() != b.size()) return false;
            for (int i = 0; i < a.size(); i++) if (!mismoValor(a.get(i), b.get(i), tolerancia)) return false;
            return true;
        }
        if (a.isObject() && b.isObject()) {
            for (Iterator<String> it = a.fieldNames(); it.hasNext(); ) {
                String k = it.next();
                if (!mismoValor(a.get(k), b.get(k), tolerancia)) return false;
            }
            return true;
        }
        return a.asText().equals(b.asText());
    }

    private static boolean vacio(JsonNode n) {
        return n == null || n.isNull() || n.isMissingNode() || (n.isTextual() && n.asText().isBlank());
    }

    private static BigDecimal numero(JsonNode n) {
        if (n.isNumber()) return n.decimalValue();
        if (!n.isTextual()) return null;
        try {
            return new BigDecimal(n.asText().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Sin quiebres guardados y una lista vacia son la misma linea recta. */
    private ArrayNode quiebres(String guardados) {
        if (guardados == null) return mapper.createArrayNode();
        try {
            JsonNode n = mapper.readTree(guardados);
            return n.isArray() ? (ArrayNode) n : mapper.createArrayNode().add(n);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // Una fila corrupta no coincide con nada: se rechaza en vez de pisarla.
            return mapper.createArrayNode().add("corrupto");
        }
    }

    private UmlClass clase(Command command, UUID id) {
        return classes.findById(id).filter(c -> c.getDeletedAt() == null)
                .orElseThrow(() -> notFound(command, "La clase ya no existe: alguien la borro despues."));
    }

    private UmlAttribute atributo(Command command, UUID id) {
        return attributes.findById(id)
                .orElseThrow(() -> notFound(command, "El atributo ya no existe: alguien lo quito despues."));
    }

    private static String texto(Enum<?> e) { return e == null ? null : e.name(); }

    private UUID uuid(Command command, String campo) {
        try {
            return UUID.fromString(command.payload().path(campo).asText());
        } catch (IllegalArgumentException e) {
            throw invalid(command, "\"" + campo + "\" no es un UUID valido");
        }
    }

    private static CommandRejectedException notFound(Command command, String msg) {
        return new CommandRejectedException(command.opId(), RejectionReason.NOT_FOUND, msg);
    }

    private static CommandRejectedException invalid(Command command, String msg) {
        return new CommandRejectedException(command.opId(), RejectionReason.INVALID, msg);
    }
}
