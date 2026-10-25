package com.uagrm.colabmodel.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.uagrm.colabmodel.modelcore.command.CommandType;
import com.uagrm.colabmodel.modelcore.mapping.InferenciaDeAtributos;
import com.uagrm.colabmodel.modelcore.metamodel.RelationKind;
import com.uagrm.colabmodel.modelcore.metamodel.UmlAttribute;
import com.uagrm.colabmodel.modelcore.metamodel.UmlClass;
import com.uagrm.colabmodel.modelcore.metamodel.UmlMethod;
import com.uagrm.colabmodel.modelcore.metamodel.UmlRelationship;

import java.util.*;

/**
 * Traduce UNA llamada a herramienta (refs legibles: nombres de clase y de
 * atributo) al payload nativo que espera CommandApplier (UUID).
 *
 * Es la parte "cruzar con el diagrama" del modulo de IA, igual en espiritu a
 * `ServicioDeImportacion.cruzarConElDiagrama`: arranca con lo que YA existe en
 * el diagrama y va actualizando su estado con cada cambio aceptado, para que
 * el segundo comando de la respuesta pueda referirse a la clase que creo el
 * primero. Por eso el orden de la lista de la IA importa y una referencia
 * hacia ADELANTE (usar algo que se crea despues) se rechaza: no hay forma de
 * saber si el modelo la va a crear todavia.
 *
 * Una instancia se usa para UNA propuesta y se descarta: no es thread-safe ni
 * hace falta que lo sea.
 */
class ResolutorDeReferencias {

    private static final JsonNodeFactory J = JsonNodeFactory.instance;
    private static final Set<String> FLAGS_DE_ATRIBUTO = Set.of("multivalued", "derived", "partialKey", "pk");
    private static final Set<String> MULTIPLICIDADES = Set.of("1", "0..1", "*", "0..*", "1..*");

    private final Map<String, UUID> clasesPorNombre = new HashMap<>();
    private final Map<UUID, Map<String, UUID>> atributosPorClase = new HashMap<>();
    private final Map<UUID, Map<String, List<UUID>>> metodosPorClase = new HashMap<>();
    private final List<RelacionResuelta> relaciones = new ArrayList<>();

    private record RelacionResuelta(UUID id, UUID origenId, UUID destinoId) { }

    record Traduccion(JsonNode payload, String resumen) { }

    ResolutorDeReferencias(List<UmlClass> clasesExistentes, Map<UUID, List<UmlAttribute>> atributosExistentes,
                           Map<UUID, List<UmlMethod>> metodosExistentes, List<UmlRelationship> relacionesExistentes) {
        for (UmlClass c : clasesExistentes) {
            clasesPorNombre.put(normal(c.getName()), c.getId());
            Map<String, UUID> attrs = new HashMap<>();
            for (UmlAttribute a : atributosExistentes.getOrDefault(c.getId(), List.of())) {
                attrs.put(normal(a.getName()), a.getId());
            }
            atributosPorClase.put(c.getId(), attrs);
            Map<String, List<UUID>> mets = new HashMap<>();
            for (UmlMethod m : metodosExistentes.getOrDefault(c.getId(), List.of())) {
                mets.computeIfAbsent(normal(m.getName()), k -> new ArrayList<>()).add(m.getId());
            }
            metodosPorClase.put(c.getId(), mets);
        }
        for (UmlRelationship r : relacionesExistentes) {
            relaciones.add(new RelacionResuelta(r.getId(), r.getSourceClassId(), r.getTargetClassId()));
        }
    }

    private static String normal(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    Traduccion traducir(CommandType tipo, JsonNode p) {
        return switch (tipo) {
            case CREATE_CLASS -> crearClase(p);
            case RENAME_CLASS -> renombrarClase(p);
            case DELETE_CLASS -> borrarClase(p);
            case ADD_ATTRIBUTE -> agregarAtributo(p);
            case UPDATE_ATTRIBUTE -> actualizarAtributo(p);
            case REMOVE_ATTRIBUTE -> quitarAtributo(p);
            case ADD_METHOD -> agregarMetodo(p);
            case REMOVE_METHOD -> quitarMetodo(p);
            case CREATE_RELATION -> crearRelacion(p);
            case UPDATE_RELATION -> actualizarRelacion(p);
            case DELETE_RELATION -> borrarRelacion(p);
            case SET_ER_FLAG -> marcarFlag(p);
            case MOVE_ELEMENT, RESIZE_ELEMENT, SET_MODELING_DECISION, CONFIRM_SCHEMA, BATCH ->
                    throw new ComandoIAInvalidoException(tipo + " no es una edicion que la IA pueda proponer");
        };
    }

    // ---------------------------------------------------------------- CLASES

    private Traduccion crearClase(JsonNode p) {
        String nombre = requerido(p, "name");
        if (clasesPorNombre.containsKey(normal(nombre))) {
            throw new ComandoIAInvalidoException("ya existe una clase llamada \"" + nombre + "\"");
        }
        UUID id = UUID.randomUUID();
        ObjectNode payload = J.objectNode();
        payload.put("classId", id.toString());
        payload.put("name", nombre);
        String stereotype = opcional(p, "stereotype");
        if (stereotype != null) payload.put("stereotype", stereotype);

        clasesPorNombre.put(normal(nombre), id);
        atributosPorClase.put(id, new HashMap<>());
        metodosPorClase.put(id, new HashMap<>());
        return new Traduccion(payload, "Crear clase " + nombre);
    }

    private Traduccion renombrarClase(JsonNode p) {
        String actual = requerido(p, "clase");
        UUID id = resolverClase(actual);
        String nuevo = requerido(p, "name");
        if (!normal(actual).equals(normal(nuevo)) && clasesPorNombre.containsKey(normal(nuevo))) {
            throw new ComandoIAInvalidoException("ya existe una clase llamada \"" + nuevo + "\"");
        }
        ObjectNode payload = J.objectNode();
        payload.put("classId", id.toString());
        payload.put("name", nuevo);

        clasesPorNombre.remove(normal(actual));
        clasesPorNombre.put(normal(nuevo), id);
        return new Traduccion(payload, "Renombrar " + actual + " a " + nuevo);
    }

    private Traduccion borrarClase(JsonNode p) {
        String nombre = requerido(p, "clase");
        UUID id = resolverClase(nombre);
        ObjectNode payload = J.objectNode();
        payload.put("classId", id.toString());

        clasesPorNombre.remove(normal(nombre));
        atributosPorClase.remove(id);
        metodosPorClase.remove(id);
        return new Traduccion(payload, "Borrar clase " + nombre);
    }

    // ------------------------------------------------------------ ATRIBUTOS

    private Traduccion agregarAtributo(JsonNode p) {
        String claseNombre = requerido(p, "clase");
        UUID classId = resolverClase(claseNombre);
        String nombre = requerido(p, "name");
        Map<String, UUID> attrs = atributosPorClase.computeIfAbsent(classId, k -> new HashMap<>());
        if (attrs.containsKey(normal(nombre))) {
            throw new ComandoIAInvalidoException(
                    "la clase \"" + claseNombre + "\" ya tiene un atributo \"" + nombre + "\"");
        }
        String dataType = opcional(p, "dataType");
        if (dataType == null) {
            // Misma inferencia que el panel y la importacion: si la IA no dice el
            // tipo, se sugiere por el nombre en vez de caer siempre a string.
            dataType = InferenciaDeAtributos.sugerir(nombre, null, null, List.of()).dataType();
        }
        UUID attributeId = UUID.randomUUID();
        ObjectNode payload = J.objectNode();
        payload.put("classId", classId.toString());
        payload.put("attributeId", attributeId.toString());
        payload.put("name", nombre);
        payload.put("dataType", dataType);
        copiarBooleano(p, payload, "required");
        copiarBooleano(p, payload, "unique");
        copiarBooleano(p, payload, "pk");

        attrs.put(normal(nombre), attributeId);
        return new Traduccion(payload, "Agregar atributo " + nombre + " (" + dataType + ") a " + claseNombre);
    }

    private Traduccion actualizarAtributo(JsonNode p) {
        String claseNombre = requerido(p, "clase");
        UUID classId = resolverClase(claseNombre);
        String atributoNombre = requerido(p, "atributo");
        Map<String, UUID> attrs = atributosPorClase.computeIfAbsent(classId, k -> new HashMap<>());
        UUID attributeId = attrs.get(normal(atributoNombre));
        if (attributeId == null) {
            throw new ComandoIAInvalidoException(
                    "la clase \"" + claseNombre + "\" no tiene un atributo \"" + atributoNombre + "\"");
        }
        ObjectNode payload = J.objectNode();
        payload.put("attributeId", attributeId.toString());
        String nuevoNombre = opcional(p, "name");
        if (nuevoNombre != null) {
            payload.put("name", nuevoNombre);
            attrs.remove(normal(atributoNombre));
            attrs.put(normal(nuevoNombre), attributeId);
        }
        String dataType = opcional(p, "dataType");
        if (dataType != null) payload.put("dataType", dataType);
        copiarBooleano(p, payload, "required");
        copiarBooleano(p, payload, "unique");

        return new Traduccion(payload, "Actualizar atributo " + atributoNombre + " de " + claseNombre);
    }

    private Traduccion quitarAtributo(JsonNode p) {
        String claseNombre = requerido(p, "clase");
        UUID classId = resolverClase(claseNombre);
        String atributoNombre = requerido(p, "atributo");
        Map<String, UUID> attrs = atributosPorClase.computeIfAbsent(classId, k -> new HashMap<>());
        UUID attributeId = attrs.remove(normal(atributoNombre));
        if (attributeId == null) {
            throw new ComandoIAInvalidoException(
                    "la clase \"" + claseNombre + "\" no tiene un atributo \"" + atributoNombre + "\"");
        }
        ObjectNode payload = J.objectNode();
        payload.put("attributeId", attributeId.toString());
        return new Traduccion(payload, "Quitar atributo " + atributoNombre + " de " + claseNombre);
    }

    // -------------------------------------------------------------- METODOS

    private Traduccion agregarMetodo(JsonNode p) {
        String claseNombre = requerido(p, "clase");
        UUID classId = resolverClase(claseNombre);
        String nombre = requerido(p, "name");
        UUID methodId = UUID.randomUUID();
        ObjectNode payload = J.objectNode();
        payload.put("classId", classId.toString());
        payload.put("methodId", methodId.toString());
        payload.put("name", nombre);
        String returnType = opcional(p, "returnType");
        payload.put("returnType", returnType == null ? "void" : returnType);
        if (p.has("params")) payload.set("params", p.get("params"));

        metodosPorClase.computeIfAbsent(classId, k -> new HashMap<>())
                .computeIfAbsent(normal(nombre), k -> new ArrayList<>()).add(methodId);
        return new Traduccion(payload, "Agregar metodo " + nombre + "() a " + claseNombre);
    }

    private Traduccion quitarMetodo(JsonNode p) {
        String claseNombre = requerido(p, "clase");
        UUID classId = resolverClase(claseNombre);
        String metodoNombre = requerido(p, "metodo");
        List<UUID> candidatos = metodosPorClase.computeIfAbsent(classId, k -> new HashMap<>())
                .getOrDefault(normal(metodoNombre), List.of());
        if (candidatos.isEmpty()) {
            throw new ComandoIAInvalidoException(
                    "la clase \"" + claseNombre + "\" no tiene un metodo \"" + metodoNombre + "\"");
        }
        if (candidatos.size() > 1) {
            throw new ComandoIAInvalidoException("\"" + claseNombre + "\" tiene " + candidatos.size()
                    + " metodos llamados \"" + metodoNombre + "\" sobrecargados: no se puede saber cual sin ver"
                    + " los parametros");
        }
        UUID methodId = candidatos.get(0);
        metodosPorClase.get(classId).remove(normal(metodoNombre));
        ObjectNode payload = J.objectNode();
        payload.put("methodId", methodId.toString());
        return new Traduccion(payload, "Quitar metodo " + metodoNombre + "() de " + claseNombre);
    }

    // ------------------------------------------------------------ RELACIONES

    private Traduccion crearRelacion(JsonNode p) {
        String origenNombre = requerido(p, "claseOrigen");
        String destinoNombre = requerido(p, "claseDestino");
        UUID origenId = resolverClase(origenNombre);
        UUID destinoId = resolverClase(destinoNombre);
        RelationKind kind = enumRequerido(p, "kind", RelationKind.class);

        UUID relId = UUID.randomUUID();
        ObjectNode payload = J.objectNode();
        payload.put("relationId", relId.toString());
        payload.put("sourceClassId", origenId.toString());
        payload.put("targetClassId", destinoId.toString());
        payload.put("kind", kind.name());
        copiarTexto(p, payload, "name");
        copiarMultiplicidad(p, payload, "sourceMultiplicity");
        copiarMultiplicidad(p, payload, "targetMultiplicity");
        copiarTexto(p, payload, "sourceRole");
        copiarTexto(p, payload, "targetRole");

        relaciones.add(new RelacionResuelta(relId, origenId, destinoId));
        String multiplicidadOrigen = opcional(p, "sourceMultiplicity");
        String multiplicidadDestino = opcional(p, "targetMultiplicity");
        String cardinalidades = " [" + (multiplicidadOrigen == null ? "?" : multiplicidadOrigen) + "] — ["
                + (multiplicidadDestino == null ? "?" : multiplicidadDestino) + "]";
        return new Traduccion(payload, "Crear relacion " + kind + " entre " + origenNombre + " y "
                + destinoNombre + cardinalidades);
    }

    private Traduccion actualizarRelacion(JsonNode p) {
        String origenNombre = requerido(p, "claseOrigen");
        String destinoNombre = requerido(p, "claseDestino");
        UUID relId = resolverRelacion(origenNombre, destinoNombre);
        ObjectNode payload = J.objectNode();
        payload.put("relationId", relId.toString());
        String kindTexto = opcional(p, "kind");
        if (kindTexto != null) payload.put("kind", enumValido(kindTexto, RelationKind.class).name());
        copiarTexto(p, payload, "name");
        copiarMultiplicidad(p, payload, "sourceMultiplicity");
        copiarMultiplicidad(p, payload, "targetMultiplicity");
        return new Traduccion(payload, "Actualizar relacion entre " + origenNombre + " y " + destinoNombre);
    }

    private Traduccion borrarRelacion(JsonNode p) {
        String origenNombre = requerido(p, "claseOrigen");
        String destinoNombre = requerido(p, "claseDestino");
        UUID relId = resolverRelacion(origenNombre, destinoNombre);
        relaciones.removeIf(r -> r.id().equals(relId));
        ObjectNode payload = J.objectNode();
        payload.put("relationId", relId.toString());
        return new Traduccion(payload, "Borrar relacion entre " + origenNombre + " y " + destinoNombre);
    }

    private UUID resolverRelacion(String origenNombre, String destinoNombre) {
        UUID origenId = resolverClase(origenNombre);
        UUID destinoId = resolverClase(destinoNombre);
        List<RelacionResuelta> candidatas = relaciones.stream()
                .filter(r -> (r.origenId().equals(origenId) && r.destinoId().equals(destinoId))
                        || (r.origenId().equals(destinoId) && r.destinoId().equals(origenId)))
                .toList();
        if (candidatas.isEmpty()) {
            throw new ComandoIAInvalidoException("no existe una relacion entre \"" + origenNombre
                    + "\" y \"" + destinoNombre + "\"");
        }
        if (candidatas.size() > 1) {
            throw new ComandoIAInvalidoException("hay " + candidatas.size() + " relaciones entre \"" + origenNombre
                    + "\" y \"" + destinoNombre + "\": no se puede saber cual sin verlas");
        }
        return candidatas.get(0).id();
    }

    // ---------------------------------------------------------------- FLAGS

    private Traduccion marcarFlag(JsonNode p) {
        String claseNombre = requerido(p, "clase");
        UUID classId = resolverClase(claseNombre);
        String flag = requerido(p, "flag");
        boolean value = p.path("value").asBoolean(true);
        String atributoNombre = opcional(p, "atributo");

        ObjectNode payload = J.objectNode();
        String resumenObjetivo;
        if (atributoNombre != null) {
            if (!FLAGS_DE_ATRIBUTO.contains(flag)) {
                throw new ComandoIAInvalidoException("\"" + flag + "\" no es una bandera valida para un atributo");
            }
            Map<String, UUID> attrs = atributosPorClase.computeIfAbsent(classId, k -> new HashMap<>());
            UUID attributeId = attrs.get(normal(atributoNombre));
            if (attributeId == null) {
                throw new ComandoIAInvalidoException(
                        "la clase \"" + claseNombre + "\" no tiene un atributo \"" + atributoNombre + "\"");
            }
            payload.put("targetType", "ATTRIBUTE");
            payload.put("targetId", attributeId.toString());
            resumenObjetivo = claseNombre + "." + atributoNombre;
        } else {
            if (!"weak".equals(flag)) {
                throw new ComandoIAInvalidoException("\"" + flag + "\" no es una bandera valida para una clase");
            }
            payload.put("targetType", "CLASS");
            payload.put("targetId", classId.toString());
            resumenObjetivo = claseNombre;
        }
        payload.put("flag", flag);
        payload.put("value", value);
        return new Traduccion(payload, "Marcar " + flag + "=" + value + " en " + resumenObjetivo);
    }

    // ---------------------------------------------------------------- helpers

    private UUID resolverClase(String nombre) {
        UUID id = clasesPorNombre.get(normal(nombre));
        if (id == null) {
            throw new ComandoIAInvalidoException("no existe una clase llamada \"" + nombre + "\" en el diagrama");
        }
        return id;
    }

    private static String requerido(JsonNode p, String campo) {
        JsonNode v = p.path(campo);
        if (v.isMissingNode() || v.isNull() || v.asText().isBlank()) {
            throw new ComandoIAInvalidoException("falta el campo \"" + campo + "\"");
        }
        return v.asText();
    }

    private static String opcional(JsonNode p, String campo) {
        JsonNode v = p.path(campo);
        return v.isMissingNode() || v.isNull() || v.asText().isBlank() ? null : v.asText();
    }

    private static void copiarTexto(JsonNode origen, ObjectNode destino, String campo) {
        String v = opcional(origen, campo);
        if (v != null) destino.put(campo, v);
    }

    private static void copiarMultiplicidad(JsonNode origen, ObjectNode destino, String campo) {
        String v = opcional(origen, campo);
        if (v == null) return;
        if (!MULTIPLICIDADES.contains(v)) {
            throw new ComandoIAInvalidoException("\"" + v + "\" no es una multiplicidad UML admitida en \""
                    + campo + "\"; usa 1, 0..1, *, 0..* o 1..*");
        }
        destino.put(campo, v);
    }

    private static void copiarBooleano(JsonNode origen, ObjectNode destino, String campo) {
        if (origen.has(campo)) destino.put(campo, origen.get(campo).asBoolean());
    }

    private static <E extends Enum<E>> E enumRequerido(JsonNode p, String campo, Class<E> tipo) {
        return enumValido(requerido(p, campo), tipo);
    }

    private static <E extends Enum<E>> E enumValido(String crudo, Class<E> tipo) {
        try {
            return Enum.valueOf(tipo, crudo);
        } catch (IllegalArgumentException e) {
            throw new ComandoIAInvalidoException("\"" + crudo + "\" no es un valor valido de " + tipo.getSimpleName());
        }
    }
}
