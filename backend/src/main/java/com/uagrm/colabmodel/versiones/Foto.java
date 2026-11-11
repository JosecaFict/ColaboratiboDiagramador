package com.uagrm.colabmodel.versiones;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.uagrm.colabmodel.modelcore.metamodel.ModelingDecision;
import com.uagrm.colabmodel.project.DiagramSnapshot;

import java.math.BigDecimal;
import java.util.*;
import java.util.function.Function;

/**
 * El modelo de un diagrama en un momento, en una forma que se puede comparar
 * campo por campo: lo que guarda un commit, lo que se compara en un diff y lo
 * que se combina en un merge.
 *
 * Cada elemento es su id mas un mapa de campos con valores JSON NORMALIZADOS
 * (un 200 y un 200.0 son lo mismo, un rango 1.0000 de NUMERIC es 1). Sin eso,
 * leer la foto guardada y la viva daria diferencias que no existen.
 *
 * Se guarda en `diagram_versions.snapshot` con la forma de GET /diagrams/{id}
 * mas `decisions`, para que el cliente pueda dibujar una version vieja con el
 * mismo codigo con el que dibuja la actual.
 */
final class Foto {

    enum Tipo { CLASE, ATRIBUTO, METODO, RELACION }

    /** Un elemento del modelo. `campos` no incluye el id; las referencias van como texto. */
    record Elemento(UUID id, Tipo tipo, Map<String, JsonNode> campos) {
        JsonNode campo(String nombre) { return campos.getOrDefault(nombre, NullNode.getInstance()); }

        String texto(String nombre) {
            JsonNode v = campo(nombre);
            return v.isNull() ? null : v.asText();
        }

        UUID ref(String nombre) {
            String v = texto(nombre);
            return v == null ? null : UUID.fromString(v);
        }

        /** El elemento con un campo cambiado (o nulo). */
        Elemento con(String nombre, JsonNode valor) {
            Map<String, JsonNode> c = new LinkedHashMap<>(campos);
            c.put(nombre, valor == null ? NullNode.getInstance() : valor);
            return new Elemento(id, tipo, c);
        }
    }

    /** La geometria de una caja o de una linea. */
    record Geometria(UUID elementId, Map<String, JsonNode> campos) { }

    /** Una de las 4 decisiones de modelado (ADR-033). */
    record Decision(UUID elementId, String tipo, String elegida) {
        String clave() { return elementId + "/" + tipo; }
    }

    static final List<String> CAMPOS_CLASE = List.of(
            "name", "stereotype", "isAbstract", "weak", "erKind", "inheritanceStrategy");
    static final List<String> CAMPOS_ATRIBUTO = List.of(
            "classId", "name", "dataType", "visibility", "length", "pk", "required", "unique", "ordinal",
            "multivalued", "derived", "partialKey", "parentAttributeId", "defaultValue", "minValue", "maxValue");
    static final List<String> CAMPOS_METODO = List.of(
            "classId", "name", "returnType", "params", "visibility", "ordinal", "isStatic", "isAbstract");
    static final List<String> CAMPOS_RELACION = List.of(
            "sourceClassId", "targetClassId", "kind", "name", "sourceRole", "targetRole",
            "sourceMultiplicity", "targetMultiplicity", "identifying", "sourceParticipation",
            "targetParticipation", "degree");
    static final List<String> CAMPOS_GEOMETRIA = List.of("x", "y", "width", "height", "waypoints");

    /** Campos que guardan el id de otro elemento: los unicos que cambian al pasar de rama. */
    static final Set<String> CAMPOS_REFERENCIA = Set.of(
            "classId", "parentAttributeId", "sourceClassId", "targetClassId");

    private static final Set<String> DOBLES = Set.of("x", "y", "width", "height");
    /**
     * Banderas que un snapshot guardado ANTES de que existieran no traen. Ausente es lo mismo que false:
     * sin esto la foto vieja diria null y la viva false, y Diferencias y Fusion verian un cambio que nadie hizo.
     */
    private static final Set<String> BANDERAS = Set.of(
            "isAbstract", "weak", "pk", "required", "unique", "multivalued", "derived", "partialKey",
            "identifying", "isStatic");
    private static final Set<String> DECIMALES = Set.of("minValue", "maxValue");
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    final UUID diagramId;
    final String nombre;
    final long seq;
    final String vistaPorDefecto;
    final Map<UUID, Elemento> clases;
    final Map<UUID, Elemento> atributos;
    final Map<UUID, Elemento> metodos;
    final Map<UUID, Elemento> relaciones;
    final Map<UUID, Geometria> geometria;
    final Map<String, Decision> decisiones;

    Foto(UUID diagramId, String nombre, long seq, String vistaPorDefecto,
         Map<UUID, Elemento> clases, Map<UUID, Elemento> atributos, Map<UUID, Elemento> metodos,
         Map<UUID, Elemento> relaciones, Map<UUID, Geometria> geometria, Map<String, Decision> decisiones) {
        this.diagramId = diagramId;
        this.nombre = nombre;
        this.seq = seq;
        this.vistaPorDefecto = vistaPorDefecto;
        this.clases = clases;
        this.atributos = atributos;
        this.metodos = metodos;
        this.relaciones = relaciones;
        this.geometria = geometria;
        this.decisiones = decisiones;
    }

    static Foto vacia(UUID diagramId) {
        return new Foto(diagramId, "", 0, "CLASS", new LinkedHashMap<>(), new LinkedHashMap<>(),
                new LinkedHashMap<>(), new LinkedHashMap<>(), new LinkedHashMap<>(), new LinkedHashMap<>());
    }

    Map<UUID, Elemento> de(Tipo tipo) {
        return switch (tipo) {
            case CLASE -> clases;
            case ATRIBUTO -> atributos;
            case METODO -> metodos;
            case RELACION -> relaciones;
        };
    }

    static List<String> camposDe(Tipo tipo) {
        return switch (tipo) {
            case CLASE -> CAMPOS_CLASE;
            case ATRIBUTO -> CAMPOS_ATRIBUTO;
            case METODO -> CAMPOS_METODO;
            case RELACION -> CAMPOS_RELACION;
        };
    }

    // ------------------------------------------------------------- LECTURA

    /** Del modelo vivo, tal como lo arma el snapshot, mas las decisiones guardadas. */
    static Foto de(DiagramSnapshot s, List<ModelingDecision> decisiones) {
        Map<UUID, Elemento> clases = new LinkedHashMap<>();
        Map<UUID, Elemento> atributos = new LinkedHashMap<>();
        Map<UUID, Elemento> metodos = new LinkedHashMap<>();
        Map<UUID, Elemento> relaciones = new LinkedHashMap<>();
        Map<UUID, Geometria> geometria = new LinkedHashMap<>();
        Map<String, Decision> elegidas = new LinkedHashMap<>();

        for (DiagramSnapshot.Clase c : s.classes()) {
            Map<String, JsonNode> campos = new LinkedHashMap<>();
            campos.put("name", texto(c.name()));
            campos.put("stereotype", texto(c.stereotype()));
            campos.put("isAbstract", JSON.booleanNode(c.isAbstract()));
            campos.put("weak", JSON.booleanNode(c.weak()));
            campos.put("erKind", texto(c.erKind() == null ? null : c.erKind().name()));
            campos.put("inheritanceStrategy",
                    texto(c.inheritanceStrategy() == null ? null : c.inheritanceStrategy().name()));
            clases.put(c.id(), new Elemento(c.id(), Tipo.CLASE, campos));

            for (DiagramSnapshot.Atributo a : c.attributes()) {
                Map<String, JsonNode> ca = new LinkedHashMap<>();
                ca.put("classId", texto(a.classId()));
                ca.put("name", texto(a.name()));
                ca.put("dataType", texto(a.dataType()));
                ca.put("visibility", texto(a.visibility() == null ? null : a.visibility().name()));
                ca.put("length", a.length() == null ? NullNode.getInstance() : IntNode.valueOf(a.length()));
                ca.put("pk", JSON.booleanNode(a.pk()));
                ca.put("required", JSON.booleanNode(a.required()));
                ca.put("unique", JSON.booleanNode(a.unique()));
                ca.put("ordinal", IntNode.valueOf(a.ordinal()));
                ca.put("multivalued", JSON.booleanNode(a.multivalued()));
                ca.put("derived", JSON.booleanNode(a.derived()));
                ca.put("partialKey", JSON.booleanNode(a.partialKey()));
                ca.put("parentAttributeId", texto(a.parentAttributeId()));
                ca.put("defaultValue", texto(a.defaultValue()));
                ca.put("minValue", decimal(a.minValue()));
                ca.put("maxValue", decimal(a.maxValue()));
                atributos.put(a.id(), new Elemento(a.id(), Tipo.ATRIBUTO, ca));
            }
            for (DiagramSnapshot.Metodo m : c.methods()) {
                Map<String, JsonNode> cm = new LinkedHashMap<>();
                cm.put("classId", texto(m.classId()));
                cm.put("name", texto(m.name()));
                cm.put("returnType", texto(m.returnType()));
                ArrayNode params = JSON.arrayNode();
                for (DiagramSnapshot.Parametro p : m.params()) {
                    params.addObject().put("name", p.name()).put("type", p.type());
                }
                cm.put("params", params);
                cm.put("visibility", texto(m.visibility() == null ? null : m.visibility().name()));
                cm.put("ordinal", IntNode.valueOf(m.ordinal()));
                cm.put("isStatic", JSON.booleanNode(m.isStatic()));
                cm.put("isAbstract", JSON.booleanNode(m.isAbstract()));
                metodos.put(m.id(), new Elemento(m.id(), Tipo.METODO, cm));
            }
        }
        for (DiagramSnapshot.Relacion r : s.relationships()) {
            Map<String, JsonNode> cr = new LinkedHashMap<>();
            cr.put("sourceClassId", texto(r.sourceClassId()));
            cr.put("targetClassId", texto(r.targetClassId()));
            cr.put("kind", texto(r.kind() == null ? null : r.kind().name()));
            cr.put("name", texto(r.name()));
            cr.put("sourceRole", texto(r.sourceRole()));
            cr.put("targetRole", texto(r.targetRole()));
            cr.put("sourceMultiplicity", texto(r.sourceMultiplicity()));
            cr.put("targetMultiplicity", texto(r.targetMultiplicity()));
            cr.put("identifying", JSON.booleanNode(r.identifying()));
            cr.put("sourceParticipation", texto(r.sourceParticipation() == null ? null : r.sourceParticipation().name()));
            cr.put("targetParticipation", texto(r.targetParticipation() == null ? null : r.targetParticipation().name()));
            cr.put("degree", IntNode.valueOf(r.degree()));
            relaciones.put(r.id(), new Elemento(r.id(), Tipo.RELACION, cr));
        }
        for (DiagramSnapshot.Layout l : s.layout()) {
            // Geometria huerfana (de algo que ya no existe) no es parte del modelo.
            if (!clases.containsKey(l.elementId()) && !relaciones.containsKey(l.elementId())) continue;
            Map<String, JsonNode> cg = new LinkedHashMap<>();
            cg.put("x", DoubleNode.valueOf(l.x()));
            cg.put("y", DoubleNode.valueOf(l.y()));
            cg.put("width", DoubleNode.valueOf(l.width()));
            cg.put("height", DoubleNode.valueOf(l.height()));
            ArrayNode puntos = JSON.arrayNode();
            for (DiagramSnapshot.Punto p : l.waypoints()) {
                puntos.addObject().put("x", p.x()).put("y", p.y());
            }
            cg.put("waypoints", puntos);
            geometria.put(l.elementId(), new Geometria(l.elementId(), cg));
        }
        for (ModelingDecision d : decisiones) {
            Decision dec = new Decision(d.getElementId(), d.getAmbiguityKind().name(), d.getChosen().name());
            elegidas.put(dec.clave(), dec);
        }
        return new Foto(s.id(), s.name(), s.currentSeq(),
                s.defaultView() == null ? "CLASS" : s.defaultView().name(),
                clases, atributos, metodos, relaciones, geometria, elegidas);
    }

    /** De lo guardado en `diagram_versions.snapshot` (lo que escribe {@link #aJson()}). */
    static Foto leer(JsonNode raiz) {
        Map<UUID, Elemento> clases = new LinkedHashMap<>();
        Map<UUID, Elemento> atributos = new LinkedHashMap<>();
        Map<UUID, Elemento> metodos = new LinkedHashMap<>();
        Map<UUID, Elemento> relaciones = new LinkedHashMap<>();
        Map<UUID, Geometria> geometria = new LinkedHashMap<>();
        Map<String, Decision> decisiones = new LinkedHashMap<>();

        for (JsonNode c : raiz.path("classes")) {
            UUID id = UUID.fromString(c.path("id").asText());
            clases.put(id, new Elemento(id, Tipo.CLASE, campos(c, CAMPOS_CLASE)));
            for (JsonNode a : c.path("attributes")) {
                UUID aid = UUID.fromString(a.path("id").asText());
                atributos.put(aid, new Elemento(aid, Tipo.ATRIBUTO, campos(a, CAMPOS_ATRIBUTO)));
            }
            for (JsonNode m : c.path("methods")) {
                UUID mid = UUID.fromString(m.path("id").asText());
                metodos.put(mid, new Elemento(mid, Tipo.METODO, campos(m, CAMPOS_METODO)));
            }
        }
        for (JsonNode r : raiz.path("relationships")) {
            UUID id = UUID.fromString(r.path("id").asText());
            relaciones.put(id, new Elemento(id, Tipo.RELACION, campos(r, CAMPOS_RELACION)));
        }
        for (JsonNode l : raiz.path("layout")) {
            UUID id = UUID.fromString(l.path("elementId").asText());
            geometria.put(id, new Geometria(id, campos(l, CAMPOS_GEOMETRIA)));
        }
        for (JsonNode d : raiz.path("decisions")) {
            Decision dec = new Decision(UUID.fromString(d.path("elementId").asText()),
                    d.path("ambiguityKind").asText(), d.path("chosen").asText());
            decisiones.put(dec.clave(), dec);
        }
        String diagrama = raiz.path("id").asText(null);
        return new Foto(diagrama == null ? null : UUID.fromString(diagrama), raiz.path("name").asText(""),
                raiz.path("currentSeq").asLong(0), raiz.path("defaultView").asText("CLASS"),
                clases, atributos, metodos, relaciones, geometria, decisiones);
    }

    private static Map<String, JsonNode> campos(JsonNode nodo, List<String> nombres) {
        Map<String, JsonNode> c = new LinkedHashMap<>();
        for (String n : nombres) c.put(n, normalizar(n, nodo.get(n)));
        return c;
    }

    /** Un mismo valor, una misma representacion: sin esto la foto guardada y la viva no coinciden. */
    static JsonNode normalizar(String campo, JsonNode v) {
        if (v == null || v.isNull() || v.isMissingNode()) {
            // Una lista vacia y una ausente son lo mismo para params y quiebres.
            if (campo.equals("params") || campo.equals("waypoints")) return JSON.arrayNode();
            if (BANDERAS.contains(campo)) return JSON.booleanNode(false);
            return NullNode.getInstance();
        }
        if (DECIMALES.contains(campo)) return decimal(v.decimalValue());
        if (DOBLES.contains(campo)) return DoubleNode.valueOf(v.asDouble());
        if (v.isNumber()) return IntNode.valueOf(v.asInt());
        if (campo.equals("waypoints")) {
            ArrayNode puntos = JSON.arrayNode();
            for (JsonNode p : v) puntos.addObject().put("x", p.path("x").asDouble()).put("y", p.path("y").asDouble());
            return puntos;
        }
        if (campo.equals("params")) {
            ArrayNode params = JSON.arrayNode();
            for (JsonNode p : v) {
                params.addObject().put("name", p.path("name").asText("")).put("type", p.path("type").asText("string"));
            }
            return params;
        }
        return v;
    }

    private static JsonNode texto(Object v) {
        return v == null ? NullNode.getInstance() : TextNode.valueOf(v.toString());
    }

    private static JsonNode decimal(BigDecimal v) {
        if (v == null) return NullNode.getInstance();
        // 1.0000 (NUMERIC(19,4)) y 1 son el mismo limite. toPlainString evita 1E+2.
        return DecimalNode.valueOf(new BigDecimal(v.stripTrailingZeros().toPlainString()));
    }

    // ------------------------------------------------------------ ESCRITURA

    /** La forma de GET /diagrams/{id} mas `decisions`. */
    ObjectNode aJson() {
        ObjectNode raiz = JSON.objectNode();
        raiz.put("id", diagramId == null ? null : diagramId.toString());
        raiz.put("name", nombre);
        raiz.put("currentSeq", seq);
        raiz.put("defaultView", vistaPorDefecto);
        ArrayNode cs = raiz.putArray("classes");
        for (Elemento c : clases.values()) {
            ObjectNode n = cs.addObject();
            n.put("id", c.id().toString());
            if (diagramId != null) n.put("diagramId", diagramId.toString());
            c.campos().forEach(n::set);
            ArrayNode as = n.putArray("attributes");
            atributosDe(c.id()).forEach(a -> {
                ObjectNode an = as.addObject();
                an.put("id", a.id().toString());
                a.campos().forEach(an::set);
            });
            ArrayNode ms = n.putArray("methods");
            metodosDe(c.id()).forEach(m -> {
                ObjectNode mn = ms.addObject();
                mn.put("id", m.id().toString());
                m.campos().forEach(mn::set);
            });
        }
        ArrayNode rs = raiz.putArray("relationships");
        for (Elemento r : relaciones.values()) {
            ObjectNode n = rs.addObject();
            n.put("id", r.id().toString());
            if (diagramId != null) n.put("diagramId", diagramId.toString());
            r.campos().forEach(n::set);
        }
        ArrayNode ls = raiz.putArray("layout");
        for (Geometria g : geometria.values()) {
            ObjectNode n = ls.addObject();
            n.put("elementId", g.elementId().toString());
            g.campos().forEach(n::set);
        }
        ArrayNode ds = raiz.putArray("decisions");
        for (Decision d : decisiones.values()) {
            ds.addObject().put("elementId", d.elementId().toString())
                    .put("ambiguityKind", d.tipo()).put("chosen", d.elegida());
        }
        return raiz;
    }

    List<Elemento> atributosDe(UUID claseId) {
        String id = claseId.toString();
        return atributos.values().stream().filter(a -> id.equals(a.texto("classId")))
                .sorted(Comparator.comparingInt(a -> a.campo("ordinal").asInt())).toList();
    }

    List<Elemento> metodosDe(UUID claseId) {
        String id = claseId.toString();
        return metodos.values().stream().filter(m -> id.equals(m.texto("classId")))
                .sorted(Comparator.comparingInt(m -> m.campo("ordinal").asInt())).toList();
    }

    // --------------------------------------------------------- TRADUCCION

    /** La misma foto con todos los ids de elementos pasados por `f` (ver IdentidadLogica). */
    Foto conIds(Function<UUID, UUID> f) {
        return new Foto(diagramId, nombre, seq, vistaPorDefecto,
                traducir(clases, f), traducir(atributos, f), traducir(metodos, f), traducir(relaciones, f),
                traducirGeometria(f), traducirDecisiones(f));
    }

    private static Map<UUID, Elemento> traducir(Map<UUID, Elemento> elementos, Function<UUID, UUID> f) {
        Map<UUID, Elemento> r = new LinkedHashMap<>();
        for (Elemento e : elementos.values()) {
            Map<String, JsonNode> c = new LinkedHashMap<>(e.campos());
            for (String ref : CAMPOS_REFERENCIA) {
                JsonNode v = c.get(ref);
                if (v != null && !v.isNull()) c.put(ref, TextNode.valueOf(f.apply(UUID.fromString(v.asText())).toString()));
            }
            UUID id = f.apply(e.id());
            r.put(id, new Elemento(id, e.tipo(), c));
        }
        return r;
    }

    private Map<UUID, Geometria> traducirGeometria(Function<UUID, UUID> f) {
        Map<UUID, Geometria> r = new LinkedHashMap<>();
        geometria.values().forEach(g -> {
            UUID id = f.apply(g.elementId());
            r.put(id, new Geometria(id, g.campos()));
        });
        return r;
    }

    private Map<String, Decision> traducirDecisiones(Function<UUID, UUID> f) {
        Map<String, Decision> r = new LinkedHashMap<>();
        decisiones.values().forEach(d -> {
            Decision t = new Decision(f.apply(d.elementId()), d.tipo(), d.elegida());
            r.put(t.clave(), t);
        });
        return r;
    }

    /** Otra foto con el mismo contenido y otro diagrama/seq: la que se escribe al copiar o al mergear. */
    Foto como(UUID otroDiagrama, String otroNombre, long otroSeq) {
        return new Foto(otroDiagrama, otroNombre, otroSeq, vistaPorDefecto, clases, atributos, metodos,
                relaciones, geometria, decisiones);
    }
}
