package com.uagrm.colabmodel.collab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uagrm.colabmodel.modelcore.command.*;
import com.uagrm.colabmodel.modelcore.mapping.Ambiguedad;
import com.uagrm.colabmodel.modelcore.metamodel.*;
import com.uagrm.colabmodel.project.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.UUID;

/**
 * Aplica UNA operacion dentro de una transaccion.
 *
 * Vive separado del bus a proposito: el bus corre en un hilo propio por
 * diagrama, y @Transactional solo funciona a traves del proxy de Spring,
 * no en auto-invocacion.
 */
@Service
public class CommandApplier {

    private final DiagramRepository diagrams;
    private final DiagramOperationRepository operations;
    private final UmlClassRepository classes;
    private final UmlAttributeRepository attributes;
    private final UmlMethodRepository methods;
    private final UmlRelationshipRepository relationships;
    private final ModelingDecisionRepository decisions;
    private final DiagramLayoutRepository layouts;
    private final ObjectMapper mapper;
    private final PrecondicionDeDeshacer precondicion;
    /** El efecto de CONFIRM_SCHEMA: deriva, verifica en PostgreSQL y guarda (ADR-032). */
    private final ConfirmadorDeEsquema confirmador;

    @Autowired
    public CommandApplier(DiagramRepository diagrams,
                          DiagramOperationRepository operations,
                          UmlClassRepository classes,
                          UmlAttributeRepository attributes,
                          UmlMethodRepository methods,
                          UmlRelationshipRepository relationships,
                          ModelingDecisionRepository decisions,
                          DiagramLayoutRepository layouts,
                          ObjectMapper mapper,
                          ConfirmadorDeEsquema confirmador) {
        this.diagrams = diagrams;
        this.operations = operations;
        this.classes = classes;
        this.attributes = attributes;
        this.methods = methods;
        this.relationships = relationships;
        this.decisions = decisions;
        this.layouts = layouts;
        this.mapper = mapper;
        this.precondicion = new PrecondicionDeDeshacer(operations, classes, attributes, methods, relationships,
                decisions, layouts, mapper);
        this.confirmador = confirmador;
    }

    /**
     * Sin confirmador: para las pruebas del resto de los comandos, que no
     * necesitan una base PostgreSQL de verdad. CONFIRM_SCHEMA se rechaza.
     */
    public CommandApplier(DiagramRepository diagrams,
                          DiagramOperationRepository operations,
                          UmlClassRepository classes,
                          UmlAttributeRepository attributes,
                          UmlMethodRepository methods,
                          UmlRelationshipRepository relationships,
                          ModelingDecisionRepository decisions,
                          DiagramLayoutRepository layouts,
                          ObjectMapper mapper) {
        this(diagrams, operations, classes, attributes, methods, relationships, decisions, layouts,
                mapper, null);
    }

    @Transactional
    public AppliedOperation apply(Command command, UUID userId) {

        // Idempotencia: si este opId ya entro, devolvemos lo mismo de antes.
        var previa = operations.findByOpId(command.opId());
        if (previa.isPresent()) {
            return toApplied(previa.get());
        }

        Diagram diagram = diagrams.findById(command.diagramId())
                .orElseThrow(() -> new CommandRejectedException(
                        command.opId(), RejectionReason.NOT_FOUND, "El diagrama no existe"));

        // El efecto se aplica ANTES de consumir seq: si se rechaza, no se gasta
        // numero de secuencia ni queda un registro de operacion sin efecto real.
        aplicarEfecto(command, userId);

        long seq = diagram.getCurrentSeq() + 1;
        diagram.setCurrentSeq(seq);
        diagram.setUpdatedAt(OffsetDateTime.now());
        diagrams.save(diagram);

        DiagramOperation op = new DiagramOperation();
        op.setDiagramId(command.diagramId());
        op.setSeq(seq);
        op.setOpId(command.opId());
        op.setUserId(userId);
        op.setOpType(command.type());
        op.setOrigin(command.origin());
        op.setPayload(command.payload().toString());
        op.setCreatedAt(OffsetDateTime.now());
        operations.save(op);

        return toApplied(op);
    }

    // ------------------------------------------------------------- EFECTOS

    private void aplicarEfecto(Command command, UUID userId) {
        JsonNode p = command.payload();
        // Antes del efecto: un undo que ya no aplica se rechaza sin tocar nada
        // ni gastar seq (ADR-027).
        precondicion.exigir(command, userId);
        switch (command.type()) {

            case CREATE_CLASS -> {
                UUID classId = requireUuid(command, "classId");
                String name = requireText(command, "name");
                // Deshacer un borrado (ADR-030) vuelve a crear la clase con SU id,
                // para que atributos, relaciones y decisiones que la nombran
                // sigan apuntando a ella. La fila con tombstone se reusa: una
                // entidad nueva con el mismo id chocaria con la PK.
                UmlClass c = conTombstone(classes.findById(classId).orElse(null), command)
                        .orElseGet(UmlClass::new);
                c.setId(classId);
                c.setDiagramId(command.diagramId());
                c.setDeletedAt(null);
                c.setName(name);
                // Como una clase nueva: si tenia SINGLE_TABLE, el lote que la
                // restaura lo vuelve a marcar, igual que el espejo del cliente.
                c.setInheritanceStrategy(InheritanceStrategy.JOINED);
                c.setStereotype(text(p, "stereotype"));
                c.setAbstract(bool(p, "isAbstract", false));
                c.setWeak(bool(p, "weak", false));
                c.setErKind(enumOrDefault(ErKind.class, text(p, "erKind"), ErKind.STRONG, command));
                saveClassUnique(c, command);
                upsertLayoutOnCreate(command.diagramId(), classId, "CLASS",
                        num(p, "x", 200), num(p, "y", 200), num(p, "width", 170), num(p, "height", 60));
            }

            case RENAME_CLASS -> {
                UmlClass c = findClass(command, requireUuid(command, "classId"));
                c.setName(requireText(command, "name"));
                saveClassUnique(c, command);
            }

            case DELETE_CLASS -> {
                UUID classId = requireUuid(command, "classId");
                UmlClass c = findClass(command, classId);
                c.setDeletedAt(OffsetDateTime.now());
                classes.save(c);
                attributes.deleteAll(attributes.findByClassId(classId));
                // Los metodos no llevan tombstone: sin esto quedan huerfanos
                // apuntando a una clase que ya no existe.
                methods.deleteAll(methods.findByClassIdOrderByOrdinalAsc(classId));
                for (UmlRelationship r : relationships.findBySourceClassIdOrTargetClassId(classId, classId)) {
                    r.setDeletedAt(OffsetDateTime.now());
                    relationships.save(r);
                    borrarGeometria(command.diagramId(), r.getId());   // se lleva sus quiebres
                }
                // La geometria no lleva tombstone: si no se borra aca, queda
                // huerfana para siempre y el snapshot la sigue mandando.
                borrarGeometria(command.diagramId(), classId);
            }

            case ADD_ATTRIBUTE -> {
                UUID classId = requireUuid(command, "classId");
                findClass(command, classId);
                UmlAttribute a = new UmlAttribute();
                a.setId(requireUuid(command, "attributeId"));
                a.setClassId(classId);
                a.setName(requireText(command, "name"));
                a.setDataType(textOr(p, "dataType", "string"));
                // UML 2.5.1: la Property lleva visibilidad. PRIVATE por defecto,
                // que es la convencion para atributos y lo que ya se dibujaba.
                a.setVisibility(enumOrDefault(Visibility.class, text(p, "visibility"),
                        Visibility.PRIVATE, command));
                a.setLength(intOrNull(p, "length"));
                a.setPk(bool(p, "pk", false));
                a.setRequired(bool(p, "required", false));
                a.setUnique(bool(p, "unique", false));
                a.setDefaultValue(text(p, "defaultValue"));
                a.setOrdinal((int) num(p, "ordinal", 0));
                a.setMultivalued(bool(p, "multivalued", false));
                a.setDerived(bool(p, "derived", false));
                a.setPartialKey(bool(p, "partialKey", false));
                String parent = text(p, "parentAttributeId");
                a.setParentAttributeId(parent == null ? null : UUID.fromString(parent));
                if (p.has("minValue")) a.setMinValue(decimalOrNull(command, p.get("minValue")));
                if (p.has("maxValue")) a.setMaxValue(decimalOrNull(command, p.get("maxValue")));
                validarRango(command, a);
                saveAttributeUnique(a, command);
            }

            case UPDATE_ATTRIBUTE -> {
                UmlAttribute a = attributes.findById(requireUuid(command, "attributeId"))
                        .orElseThrow(() -> notFound(command, "El atributo no existe"));
                if (p.has("name")) a.setName(p.get("name").asText());
                if (p.has("dataType")) a.setDataType(p.get("dataType").asText());
                if (p.has("visibility")) a.setVisibility(enumOrThrow(Visibility.class,
                        p.get("visibility").asText(), command, "Visibilidad invalida"));
                if (p.has("length")) a.setLength(p.get("length").isNull() ? null : p.get("length").asInt());
                if (p.has("pk")) a.setPk(p.get("pk").asBoolean());
                if (p.has("required")) a.setRequired(p.get("required").asBoolean());
                if (p.has("unique")) a.setUnique(p.get("unique").asBoolean());
                if (p.has("defaultValue")) a.setDefaultValue(p.get("defaultValue").isNull() ? null : p.get("defaultValue").asText());
                if (p.has("ordinal")) a.setOrdinal(p.get("ordinal").asInt());
                if (p.has("multivalued")) a.setMultivalued(p.get("multivalued").asBoolean());
                if (p.has("derived")) a.setDerived(p.get("derived").asBoolean());
                if (p.has("partialKey")) a.setPartialKey(p.get("partialKey").asBoolean());
                if (p.has("minValue")) a.setMinValue(decimalOrNull(command, p.get("minValue")));
                if (p.has("maxValue")) a.setMaxValue(decimalOrNull(command, p.get("maxValue")));
                validarRango(command, a);
                saveAttributeUnique(a, command);
            }

            case REMOVE_ATTRIBUTE -> {
                UUID id = requireUuid(command, "attributeId");
                if (!attributes.existsById(id)) throw notFound(command, "El atributo no existe");
                attributes.deleteById(id);
            }

            case CREATE_RELATION -> {
                UUID relId = requireUuid(command, "relationId");
                UUID srcId = requireUuid(command, "sourceClassId");
                UUID tgtId = requireUuid(command, "targetClassId");
                findClass(command, srcId);
                findClass(command, tgtId);
                int degree = (int) num(p, "degree", 2);
                if (degree > 2) {
                    throw invalid(command, "Relacion n-aria (grado " + degree
                            + "): reificar como clase asociativa (Regla 10).");
                }
                // Igual que CREATE_CLASS: restaurar una relacion borrada reusa su fila.
                UmlRelationship r = relationships.findById(relId)
                        .filter(x -> x.getDiagramId().equals(command.diagramId()) && x.getDeletedAt() != null)
                        .orElseGet(UmlRelationship::new);
                r.setDeletedAt(null);
                r.setId(relId);
                r.setDiagramId(command.diagramId());
                r.setSourceClassId(srcId);
                r.setTargetClassId(tgtId);
                r.setKind(enumOrThrow(RelationKind.class, text(p, "kind"), command, "Tipo de relacion invalido"));
                r.setName(text(p, "name"));
                r.setSourceRole(text(p, "sourceRole"));
                r.setTargetRole(text(p, "targetRole"));
                r.setSourceMultiplicity(text(p, "sourceMultiplicity"));
                r.setTargetMultiplicity(text(p, "targetMultiplicity"));
                r.setIdentifying(bool(p, "identifying", false));
                r.setSourceParticipation(enumOrDefault(Participation.class, text(p, "sourceParticipation"), null, command));
                r.setTargetParticipation(enumOrDefault(Participation.class, text(p, "targetParticipation"), null, command));
                r.setDegree(degree);
                relationships.save(r);
            }

            case UPDATE_RELATION -> {
                UmlRelationship r = relationships.findById(requireUuid(command, "relationId"))
                        .filter(x -> x.getDeletedAt() == null)
                        .orElseThrow(() -> notFound(command, "La relacion no existe"));
                if (p.has("kind")) r.setKind(enumOrThrow(RelationKind.class, p.get("kind").asText(), command, "Tipo de relacion invalido"));
                if (p.has("name")) r.setName(p.get("name").isNull() ? null : p.get("name").asText());
                if (p.has("sourceRole")) r.setSourceRole(p.get("sourceRole").isNull() ? null : p.get("sourceRole").asText());
                if (p.has("targetRole")) r.setTargetRole(p.get("targetRole").isNull() ? null : p.get("targetRole").asText());
                if (p.has("sourceMultiplicity")) r.setSourceMultiplicity(p.get("sourceMultiplicity").isNull() ? null : p.get("sourceMultiplicity").asText());
                if (p.has("targetMultiplicity")) r.setTargetMultiplicity(p.get("targetMultiplicity").isNull() ? null : p.get("targetMultiplicity").asText());
                if (p.has("identifying")) r.setIdentifying(p.get("identifying").asBoolean());
                if (p.has("sourceParticipation")) r.setSourceParticipation(enumOrDefault(Participation.class, text(p, "sourceParticipation"), null, command));
                if (p.has("targetParticipation")) r.setTargetParticipation(enumOrDefault(Participation.class, text(p, "targetParticipation"), null, command));
                relationships.save(r);
            }

            case DELETE_RELATION -> {
                UmlRelationship r = relationships.findById(requireUuid(command, "relationId"))
                        .orElseThrow(() -> notFound(command, "La relacion no existe"));
                r.setDeletedAt(OffsetDateTime.now());
                relationships.save(r);
                // Sus quiebres se van con ella: si no, viajan en cada snapshot
                // para siempre, apuntando a una relacion que ya no existe.
                borrarGeometria(command.diagramId(), r.getId());
            }

            case SET_ER_FLAG -> {
                String targetType = requireText(command, "targetType");
                String flag = requireText(command, "flag");
                boolean value = bool(p, "value", true);
                if ("CLASS".equals(targetType)) {
                    UmlClass c = findClass(command, requireUuid(command, "targetId"));
                    switch (flag) {
                        case "weak" -> c.setWeak(value);
                        case "erKind" -> c.setErKind(enumOrThrow(ErKind.class, text(p, "erKind"), command, "erKind invalido"));
                        // Regla 4: la estrategia es de la jerarquia y se marca en la
                        // raiz. Es una anotacion de mapeo, como erKind.
                        case "inheritanceStrategy" -> c.setInheritanceStrategy(enumOrThrow(
                                InheritanceStrategy.class, text(p, "strategy"), command,
                                "strategy debe ser JOINED o SINGLE_TABLE"));
                        default -> throw invalid(command, "Bandera desconocida para clase: " + flag);
                    }
                    classes.save(c);
                } else if ("ATTRIBUTE".equals(targetType)) {
                    UmlAttribute a = attributes.findById(requireUuid(command, "targetId"))
                            .orElseThrow(() -> notFound(command, "El atributo no existe"));
                    switch (flag) {
                        case "multivalued" -> a.setMultivalued(value);
                        case "derived" -> a.setDerived(value);
                        case "partialKey" -> a.setPartialKey(value);
                        case "pk" -> a.setPk(value);
                        default -> throw invalid(command, "Bandera desconocida para atributo: " + flag);
                    }
                    attributes.save(a);
                } else {
                    throw invalid(command, "targetType debe ser CLASS o ATTRIBUTE");
                }
            }

            case SET_MODELING_DECISION -> {
                UUID elementId = requireUuid(command, "elementId");
                var tipo = enumOrThrow(Ambiguedad.TipoAmbiguedad.class, text(p, "ambiguityKind"),
                        command, "ambiguityKind invalido");
                var lectura = enumOrThrow(Ambiguedad.Lectura.class, text(p, "chosen"),
                        command, "chosen debe ser OO o DATA");
                // Decidir dos veces sobre lo mismo ACTUALIZA la fila: hay un
                // indice unico sobre (diagram, element, kind) y una segunda
                // insercion lo violaria.
                ModelingDecision d = decisions
                        .findByDiagramIdAndElementIdAndAmbiguityKind(command.diagramId(), elementId, tipo)
                        .orElseGet(() -> {
                            ModelingDecision nueva = new ModelingDecision();
                            nueva.setId(UUID.randomUUID());
                            nueva.setDiagramId(command.diagramId());
                            nueva.setElementId(elementId);
                            nueva.setAmbiguityKind(tipo);
                            return nueva;
                        });
                d.setChosen(lectura);
                d.setDecidedBy(userId);
                d.setDecidedAt(OffsetDateTime.now());
                decisions.save(d);
            }

            case CONFIRM_SCHEMA -> {
                if (confirmador == null) throw invalid(command, "Confirmar el esquema no esta disponible");
                String hash = requireText(command, "ddlHash");
                confirmador.confirmar(command.diagramId(), hash, userId).ifPresent(r -> {
                    throw new CommandRejectedException(command.opId(), r.razon(), r.mensaje());
                });
            }

            case MOVE_ELEMENT -> {
                UUID elementId = requireUuid(command, "elementId");
                String kind = textOr(p, "elementKind", "CLASS");
                // Acomodar los quiebres de una linea es geometria igual que
                // mover una caja (nivel 3, ADR-004): entra por el mismo
                // comando en vez de agregar uno al catalogo cerrado (ADR-014).
                if (p.has("waypoints")) {
                    upsertWaypoints(command, elementId, kind, p.get("waypoints"));
                } else {
                    upsertLayoutPosition(command.diagramId(), elementId, kind,
                            num(p, "x", 0), num(p, "y", 0));
                }
            }

            case RESIZE_ELEMENT -> {
                UUID elementId = requireUuid(command, "elementId");
                String kind = textOr(p, "elementKind", "CLASS");
                upsertLayoutSize(command.diagramId(), elementId, kind, num(p, "width", 170), num(p, "height", 60));
            }

            case ADD_METHOD -> {
                UUID classId = requireUuid(command, "classId");
                findClass(command, classId);
                UmlMethod m = new UmlMethod();
                m.setId(requireUuid(command, "methodId"));
                m.setClassId(classId);
                m.setName(requireText(command, "name"));
                m.setReturnType(textOr(p, "returnType", "void"));
                m.setParams(parametrosValidados(command, p.get("params")));
                m.setVisibility(enumOrDefault(Visibility.class, text(p, "visibility"),
                        Visibility.PUBLIC, command));
                m.setOrdinal((int) num(p, "ordinal", 0));
                rechazarFirmaRepetida(command, m);
                methods.save(m);
            }

            case REMOVE_METHOD -> {
                UUID id = requireUuid(command, "methodId");
                if (!methods.existsById(id)) throw notFound(command, "El metodo no existe");
                methods.deleteById(id);
            }

            case BATCH -> aplicarLote(command, userId);
        }
    }

    /**
     * Cada cambio del lote pasa por el MISMO aplicarEfecto que un comando suelto
     * (ADR-002): no hay una segunda forma de crear una clase.
     *
     * Todo ocurre dentro de la transaccion de apply(): si un cambio se rechaza,
     * la excepcion la aborta entera y ninguno de los anteriores queda. Por eso
     * no hace falta deshacer a mano lo que ya se aplico.
     */
    private void aplicarLote(Command batch, UUID userId) {
        Lote.de(batch).paraCadaCambio(batch, cambio -> aplicarEfecto(cambio, userId));
    }

    // ------------------------------------------------------------ HELPERS

    /**
     * La clase con ese id, si esta borrada en ESTE diagrama. Si esta viva, crear
     * otra con el mismo id es un error del cliente y se dice asi, no como el
     * "ya existe una clase con ese nombre" que daria la PK.
     */
    private java.util.Optional<UmlClass> conTombstone(UmlClass existente, Command command) {
        if (existente == null) return java.util.Optional.empty();
        if (!existente.getDiagramId().equals(command.diagramId()) || existente.getDeletedAt() == null) {
            throw invalid(command, "Ya existe un elemento con ese id.");
        }
        return java.util.Optional.of(existente);
    }

    private UmlClass findClass(Command command, UUID id) {
        return classes.findById(id)
                .filter(c -> c.getDeletedAt() == null)
                .orElseThrow(() -> notFound(command, "La clase no existe"));
    }

    private void saveClassUnique(UmlClass c, Command command) {
        try {
            classes.saveAndFlush(c);
        } catch (DataIntegrityViolationException e) {
            throw invalid(command, "Ya existe una clase llamada \"" + c.getName() + "\" en este diagrama.");
        }
    }

    /**
     * Normaliza los parametros a [{"name":...,"type":...}] y rechaza
     * cualquier otra forma.
     *
     * Lo que entra aca termina siendo la firma de un metodo Java: si se
     * guarda basura, el proyecto generado no compila y el error aparece
     * recien cuando el usuario corre `mvn package`, lejos de la causa.
     */
    private String parametrosValidados(Command command, JsonNode params) {
        if (params == null || params.isNull()) return "[]";
        if (!params.isArray()) throw invalid(command, "params debe ser una lista");
        var limpios = mapper.createArrayNode();
        var vistos = new HashSet<String>();
        for (JsonNode par : params) {
            String nombre = par.path("name").asText(null);
            if (nombre == null || nombre.isBlank()) {
                throw invalid(command, "Cada parametro necesita un nombre");
            }
            // Dos parametros con el mismo nombre no compilan en Java, y el error
            // saldria recien al correr `mvn package` sobre el proyecto generado.
            if (!vistos.add(nombre.trim())) {
                throw invalid(command, "El parametro \"" + nombre.trim()
                        + "\" esta repetido en la firma");
            }
            var nodo = mapper.createObjectNode();
            nodo.put("name", nombre.trim());
            nodo.put("type", par.path("type").asText("string"));
            limpios.add(nodo);
        }
        return limpios.toString();
    }

    /**
     * Dos metodos con la MISMA firma en la misma clase.
     *
     * No hay restriccion en la base porque sobrecargar es legitimo: `buscar()`
     * y `buscar(String)` conviven. Lo que no compila es repetir nombre Y tipos,
     * asi que se rechaza al entrar en vez de emitir un Service roto.
     */
    private void rechazarFirmaRepetida(Command command, UmlMethod nuevo) {
        String firma = firmaDe(nuevo);
        boolean repetida = methods.findByClassIdOrderByOrdinalAsc(nuevo.getClassId()).stream()
                .anyMatch(m -> !m.getId().equals(nuevo.getId()) && firmaDe(m).equals(firma));
        if (repetida) {
            throw invalid(command, "Ya existe un metodo con la firma " + firma
                    + " en esta clase. Sobrecargar exige cambiar los tipos de los parametros.");
        }
    }

    /** Nombre + tipos de los parametros: lo que Java usa para distinguir metodos. */
    private String firmaDe(UmlMethod m) {
        StringBuilder sb = new StringBuilder(m.getName()).append('(');
        try {
            JsonNode params = mapper.readTree(m.getParams() == null ? "[]" : m.getParams());
            for (int i = 0; i < params.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(params.get(i).path("type").asText("string"));
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // Una fila corrupta no puede tumbar el comando: cuenta como sin parametros.
        }
        return sb.append(')').toString();
    }

    /** Tipos UML que admiten rango: lo que en Java es un numero. */
    private static final java.util.Set<String> NUMERICOS = java.util.Set.of("int", "integer", "long", "decimal");
    private static final java.util.Set<String> ENTEROS = java.util.Set.of("int", "integer", "long");

    private java.math.BigDecimal decimalOrNull(Command command, JsonNode n) {
        if (n == null || n.isNull() || (n.isTextual() && n.asText().isBlank())) return null;
        try {
            return n.isNumber() ? n.decimalValue() : new java.math.BigDecimal(n.asText().trim());
        } catch (NumberFormatException e) {
            throw invalid(command, "El limite \"" + n.asText() + "\" no es un numero");
        }
    }

    /**
     * REGLA 9: el rango se valida al ENTRAR, no al generar.
     *
     * Un minimo mayor que el maximo produciria un CHECK que no deja insertar
     * ninguna fila; un rango sobre un texto no tiene sentido; y un limite con
     * decimales sobre un entero daria un @Min que no compila. Los tres saldrian
     * a la luz recien con el proyecto generado corriendo.
     */
    private void validarRango(Command command, UmlAttribute a) {
        if (a.getMinValue() == null && a.getMaxValue() == null) return;
        String tipo = a.getDataType() == null ? "" : a.getDataType().toLowerCase();
        if (!NUMERICOS.contains(tipo)) {
            throw invalid(command, "Solo un atributo numerico (int, long, decimal) puede tener rango: "
                    + a.getName() + " es " + a.getDataType());
        }
        if (a.getMinValue() != null && a.getMaxValue() != null && a.getMinValue().compareTo(a.getMaxValue()) > 0) {
            throw invalid(command, "El minimo (" + a.getMinValue().toPlainString() + ") es mayor que el maximo ("
                    + a.getMaxValue().toPlainString() + ")");
        }
        if (ENTEROS.contains(tipo)) {
            for (java.math.BigDecimal limite : java.util.Arrays.asList(a.getMinValue(), a.getMaxValue())) {
                if (limite != null && limite.stripTrailingZeros().scale() > 0) {
                    throw invalid(command, a.getName() + " es entero: su limite " + limite.toPlainString()
                            + " no puede tener decimales");
                }
            }
        }
    }

    private void saveAttributeUnique(UmlAttribute a, Command command) {
        try {
            attributes.saveAndFlush(a);
        } catch (DataIntegrityViolationException e) {
            throw invalid(command, "Ya existe un atributo llamado \"" + a.getName() + "\" en esa clase.");
        }
    }

    private void upsertLayoutOnCreate(UUID diagramId, UUID elementId, String kind,
                                      double x, double y, double w, double h) {
        DiagramLayout l = new DiagramLayout();
        l.setDiagramId(diagramId);
        l.setElementId(elementId);
        l.setElementKind(kind);
        l.setX(x);
        l.setY(y);
        l.setWidth(w);
        l.setHeight(h);
        layouts.save(l);
    }

    private void upsertLayoutPosition(UUID diagramId, UUID elementId, String kind, double x, double y) {
        DiagramLayout l = layouts.findByDiagramIdAndElementId(diagramId, elementId)
                .orElseGet(() -> nuevaLayout(diagramId, elementId, kind));
        l.setX(x);
        l.setY(y);
        layouts.save(l);
    }

    /**
     * Guarda los quiebres de una linea. Un arreglo vacio la devuelve a recta.
     * Se valida la forma: un waypoint corrupto no rompe aca, rompe despues en
     * el canvas de todos, que es mucho mas dificil de rastrear.
     */
    private void upsertWaypoints(Command command, UUID elementId, String kind, JsonNode puntos) {
        if (!puntos.isArray()) {
            throw invalid(command, "\"waypoints\" tiene que ser un arreglo de puntos");
        }
        for (JsonNode punto : puntos) {
            if (!punto.path("x").isNumber() || !punto.path("y").isNumber()) {
                throw invalid(command, "Cada waypoint necesita x e y numericos");
            }
        }
        DiagramLayout l = layouts.findByDiagramIdAndElementId(command.diagramId(), elementId)
                .orElseGet(() -> nuevaLayout(command.diagramId(), elementId, kind));
        l.setWaypoints(puntos.isEmpty() ? null : puntos.toString());
        layouts.save(l);
    }

    private void upsertLayoutSize(UUID diagramId, UUID elementId, String kind, double w, double h) {
        DiagramLayout l = layouts.findByDiagramIdAndElementId(diagramId, elementId)
                .orElseGet(() -> nuevaLayout(diagramId, elementId, kind));
        l.setWidth(w);
        l.setHeight(h);
        layouts.save(l);
    }

    private void borrarGeometria(UUID diagramId, UUID elementId) {
        layouts.findByDiagramIdAndElementId(diagramId, elementId).ifPresent(layouts::delete);
    }

    private DiagramLayout nuevaLayout(UUID diagramId, UUID elementId, String kind) {
        DiagramLayout l = new DiagramLayout();
        l.setDiagramId(diagramId);
        l.setElementId(elementId);
        l.setElementKind(kind);
        return l;
    }

    private CommandRejectedException notFound(Command command, String msg) {
        return new CommandRejectedException(command.opId(), RejectionReason.NOT_FOUND, msg);
    }

    private CommandRejectedException invalid(Command command, String msg) {
        return new CommandRejectedException(command.opId(), RejectionReason.INVALID, msg);
    }

    private UUID requireUuid(Command command, String field) {
        JsonNode v = command.payload().path(field);
        if (v.isMissingNode() || v.isNull() || v.asText().isBlank()) {
            throw invalid(command, "Falta el campo \"" + field + "\"");
        }
        try {
            return UUID.fromString(v.asText());
        } catch (IllegalArgumentException e) {
            throw invalid(command, "\"" + field + "\" no es un UUID valido");
        }
    }

    private String requireText(Command command, String field) {
        JsonNode v = command.payload().path(field);
        if (v.isMissingNode() || v.isNull() || v.asText().isBlank()) {
            throw invalid(command, "Falta el campo \"" + field + "\"");
        }
        return v.asText();
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }

    private static String textOr(JsonNode n, String field, String def) {
        String v = text(n, field);
        return v == null ? def : v;
    }

    private static double num(JsonNode n, String field, double def) {
        JsonNode v = n.path(field);
        return v.isMissingNode() || v.isNull() ? def : v.asDouble();
    }

    private static Integer intOrNull(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asInt();
    }

    private static boolean bool(JsonNode n, String field, boolean def) {
        JsonNode v = n.path(field);
        return v.isMissingNode() || v.isNull() ? def : v.asBoolean();
    }

    private <E extends Enum<E>> E enumOrThrow(Class<E> type, String raw, Command command, String msg) {
        if (raw == null) throw invalid(command, msg);
        try {
            return Enum.valueOf(type, raw);
        } catch (IllegalArgumentException e) {
            throw invalid(command, msg + ": " + raw);
        }
    }

    private <E extends Enum<E>> E enumOrDefault(Class<E> type, String raw, E def, Command command) {
        if (raw == null) return def;
        try {
            return Enum.valueOf(type, raw);
        } catch (IllegalArgumentException e) {
            throw invalid(command, "Valor invalido para " + type.getSimpleName() + ": " + raw);
        }
    }

    private AppliedOperation toApplied(DiagramOperation op) {
        try {
            return new AppliedOperation(
                    op.getOpId(), op.getSeq(), op.getOpType(), op.getOrigin(),
                    mapper.readTree(op.getPayload()), op.getUserId(), op.getCreatedAt());
        } catch (Exception e) {
            throw new IllegalStateException("Payload almacenado no es JSON valido", e);
        }
    }
}
