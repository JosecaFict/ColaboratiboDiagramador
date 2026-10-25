package com.uagrm.colabmodel.modelcore.mapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.uagrm.colabmodel.modelcore.metamodel.*;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.*;

/**
 * Carga los modelos de `canonical-models/` como metamodelo real.
 *
 * Se leen los archivos de verdad, no una copia en test/resources: si alguien
 * agrega un caso dificil al modelo canonico, este test lo ejercita solo. Una
 * copia se desincroniza y deja de avisar, que es justo lo contrario de para
 * lo que existen esos archivos.
 */
public final class ModeloCanonico {

    public record Cargado(List<UmlClass> clases, List<UmlAttribute> atributos,
                          List<UmlRelationship> relaciones, List<UmlMethod> metodos) { }

    private static final UUID DIAGRAMA = UUID.randomUUID();

    private ModeloCanonico() { }

    public static Cargado cargar(String archivo) {
        JsonNode raiz = leer(archivo);
        Map<String, UUID> ids = new HashMap<>();

        List<UmlClass> clases = new ArrayList<>();
        List<UmlAttribute> atributos = new ArrayList<>();
        List<UmlMethod> metodos = new ArrayList<>();
        for (JsonNode c : raiz.path("clases")) {
            UUID id = ids.computeIfAbsent(c.path("id").asText(), k -> UUID.randomUUID());
            UmlClass clase = new UmlClass();
            clase.setId(id);
            clase.setDiagramId(DIAGRAMA);
            clase.setName(c.path("name").asText());
            clase.setAbstract(c.path("isAbstract").asBoolean(false));
            clase.setWeak(c.path("weak").asBoolean(false));
            clase.setErKind(ErKind.valueOf(c.path("erKind").asText("STRONG")));
            clases.add(clase);

            int ordinal = 0;
            for (JsonNode a : c.path("attributes")) {
                UmlAttribute attr = new UmlAttribute();
                attr.setId(UUID.randomUUID());
                attr.setClassId(id);
                attr.setName(a.path("name").asText());
                attr.setDataType(a.path("dataType").asText("string"));
                attr.setPk(a.path("pk").asBoolean(false));
                attr.setRequired(a.path("required").asBoolean(false));
                attr.setUnique(a.path("unique").asBoolean(false));
                attr.setMultivalued(a.path("multivalued").asBoolean(false));
                attr.setDerived(a.path("derived").asBoolean(false));
                attr.setPartialKey(a.path("partialKey").asBoolean(false));
                attr.setOrdinal(ordinal++);
                atributos.add(attr);
            }

            int ordinalMetodo = 0;
            for (JsonNode m : c.path("methods")) {
                UmlMethod metodo = new UmlMethod();
                metodo.setId(UUID.randomUUID());
                metodo.setClassId(id);
                metodo.setName(m.path("name").asText());
                metodo.setReturnType(m.path("returnType").asText("void"));
                metodo.setParams(paramsJson(m.path("params")));
                metodo.setOrdinal(ordinalMetodo++);
                metodos.add(metodo);
            }
        }

        List<UmlRelationship> relaciones = new ArrayList<>();
        for (JsonNode r : raiz.path("relaciones")) {
            UmlRelationship rel = new UmlRelationship();
            rel.setId(UUID.randomUUID());
            rel.setDiagramId(DIAGRAMA);
            rel.setSourceClassId(ids.get(r.path("sourceClassId").asText()));
            rel.setTargetClassId(ids.get(r.path("targetClassId").asText()));
            rel.setKind(RelationKind.valueOf(r.path("kind").asText("ASSOCIATION")));
            rel.setSourceRole(texto(r, "sourceRole"));
            rel.setTargetRole(texto(r, "targetRole"));
            rel.setSourceMultiplicity(texto(r, "sourceMultiplicity"));
            rel.setTargetMultiplicity(texto(r, "targetMultiplicity"));
            rel.setIdentifying(r.path("identifying").asBoolean(false));
            relaciones.add(rel);
        }

        return new Cargado(clases, atributos, relaciones, metodos);
    }

    public static ModeloLogico derivar(String archivo) {
        Cargado m = cargar(archivo);
        return DerivadorLogico.derivar(m.clases(), m.atributos(), m.relaciones());
    }

    private static String texto(JsonNode n, String campo) {
        JsonNode v = n.path(campo);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }

    private static String paramsJson(JsonNode params) {
        ArrayNode arr = new ObjectMapper().createArrayNode();
        for (JsonNode p : params) {
            ObjectNode o = arr.addObject();
            o.put("name", p.path("name").asText());
            o.put("type", p.path("type").asText("string"));
        }
        return arr.toString();
    }

    private static JsonNode leer(String archivo) {
        try {
            return new ObjectMapper().readTree(Path.of("..", "canonical-models", archivo).toFile());
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo leer el modelo canonico " + archivo, e);
        }
    }
}
