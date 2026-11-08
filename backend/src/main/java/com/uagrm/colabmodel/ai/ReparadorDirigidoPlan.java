package com.uagrm.colabmodel.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;

/** Ediciones acotadas de un borrador virtual; nunca escribe en el diagrama. */
final class ReparadorDirigidoPlan {
    static final Set<String> OPERACIONES = Set.of("CREATE_CLASS", "ADD_ATTRIBUTE", "CREATE_RELATION", "UPDATE_RELATION", "DELETE_RELATION");
    private ReparadorDirigidoPlan() { }

    static List<HerramientaIA> herramientas() {
        return EsquemaDeComandos.herramientas().stream().filter(h -> OPERACIONES.contains(h.nombre())).map(h -> {
            ObjectNode esquema = (ObjectNode) h.parametros().deepCopy();
            ObjectNode propiedades = (ObjectNode) esquema.path("properties");
            if (h.nombre().equals("UPDATE_RELATION") || h.nombre().equals("DELETE_RELATION"))
                propiedades.putObject("indiceRelacion").put("type", "integer").put("minimum", 0)
                        .put("description", "Indice cero de la relacion en relations del borrador; permite distinguir roles repetidos");
            if (h.nombre().equals("UPDATE_RELATION")) {
                propiedades.putObject("sourceRole").put("type", "string");
                propiedades.putObject("targetRole").put("type", "string");
            }
            return new HerramientaIA(h.nombre(), h.descripcion(), esquema);
        }).toList();
    }

    static RespuestaIA aplicar(RespuestaIA borrador, RespuestaIA ajustes) {
        var original = borrador.comandos().stream().filter(c -> EsquemaPlanUml.NOMBRE.equals(c.tipo()))
                .findFirst().orElseThrow(() -> new ProveedorIAException("No hay plan virtual para reparar"));
        ObjectNode plan = (ObjectNode) original.parametros().deepCopy();
        if (ajustes.comandos().isEmpty() || ajustes.comandos().size() > 16)
            throw new ProveedorIAException("La reparacion dirigida debe contener de 1 a 16 ajustes");
        Map<String, ObjectNode> clases = new LinkedHashMap<>();
        for (JsonNode c : plan.path("classes")) clases.put(c.path("name").asText(), (ObjectNode) c);
        ArrayNode relaciones = (ArrayNode) plan.path("relations");
        List<JsonNode> relacionesIniciales = new ArrayList<>();
        relaciones.forEach(relacionesIniciales::add);
        int clasesAgregadas = 0;
        Set<String> clasesNuevas = new HashSet<>();
        for (ComandoPropuestoIA c : ajustes.comandos()) {
            if (!OPERACIONES.contains(c.tipo())) throw new ProveedorIAException("Ajuste no permitido: " + c.tipo());
            JsonNode p = c.parametros();
            if (c.tipo().equals("CREATE_CLASS")) {
                String nombre = p.path("name").asText();
                if (++clasesAgregadas > 2 || !nombre.matches("[A-Z][a-zA-Z0-9_]*")
                        || clases.keySet().stream().anyMatch(n -> n.equalsIgnoreCase(nombre)))
                    throw new ProveedorIAException("La reparacion solo puede agregar hasta dos clases nuevas con nombre unico");
                ObjectNode nueva = ((ArrayNode) plan.path("classes")).addObject().put("name", nombre);
                nueva.putArray("attributes"); clases.put(nombre, nueva); clasesNuevas.add(nombre);
                continue;
            }
            if (c.tipo().equals("ADD_ATTRIBUTE")) {
                ObjectNode clase = clases.get(p.path("clase").asText());
                String nombre = p.path("name").asText();
                if (clase == null || !nombre.matches("[a-zA-Z_][a-zA-Z0-9_]*"))
                    throw new ProveedorIAException("Clase o atributo inexistente/invalido en reparacion");
                if (p.path("pk").asBoolean() && !clasesNuevas.contains(p.path("clase").asText()))
                    throw new ProveedorIAException("La reparacion dirigida no modifica identidades existentes");
                if (!Set.of("string", "text", "int", "long", "decimal", "boolean", "date", "datetime", "uuid")
                        .contains(p.path("dataType").asText())) throw new ProveedorIAException("Tipo de atributo invalido");
                ArrayNode attrs = (ArrayNode) clase.path("attributes");
                for (JsonNode a : attrs) if (nombre.equals(a.path("name").asText()))
                    throw new ProveedorIAException("El atributo ya existe: " + nombre);
                ObjectNode nuevo = attrs.addObject().put("name", nombre).put("dataType", p.path("dataType").asText());
                nuevo.put("required", p.path("required").asBoolean()).put("unique", p.path("unique").asBoolean())
                        .put("pk", p.path("pk").asBoolean());
                continue;
            }
            String s = p.path("claseOrigen").asText(), t = p.path("claseDestino").asText();
            if (!clases.containsKey(s) || !clases.containsKey(t)) throw new ProveedorIAException("El ajuste referencia una clase fuera del plan");
            List<Integer> candidatas = new ArrayList<>();
            for (int i = 0; i < relaciones.size(); i++) {
                JsonNode r = relaciones.get(i);
                if ((s.equals(r.path("source").asText()) && t.equals(r.path("target").asText()))
                        || (t.equals(r.path("source").asText()) && s.equals(r.path("target").asText()))) candidatas.add(i);
            }
            if (c.tipo().equals("DELETE_RELATION") || c.tipo().equals("UPDATE_RELATION")) {
                if (p.has("indiceRelacion")) {
                    int originalIndice = p.path("indiceRelacion").asInt(-1);
                    if (!p.path("indiceRelacion").isIntegralNumber() || originalIndice < 0
                            || originalIndice >= relacionesIniciales.size())
                        throw new ProveedorIAException("El indice no corresponde a los extremos declarados");
                    JsonNode originalRelacion = relacionesIniciales.get(originalIndice);
                    int seleccionada = -1;
                    for (int i : candidatas) if (relaciones.get(i) == originalRelacion) seleccionada = i;
                    if (seleccionada < 0) throw new ProveedorIAException("La relacion seleccionada ya no existe o no coincide con sus extremos");
                    candidatas = new ArrayList<>(List.of(seleccionada));
                }
                if (candidatas.size() != 1) throw new ProveedorIAException("Relacion ambigua o inexistente: " + s + " - " + t);
                int indice = candidatas.getFirst();
                if (c.tipo().equals("DELETE_RELATION")) { relaciones.remove(indice); continue; }
                ObjectNode r = (ObjectNode) relaciones.get(indice);
                boolean inversa = !s.equals(r.path("source").asText());
                for (String campo : List.of("kind", "name")) if (p.has(campo)) r.set(campo, p.get(campo).deepCopy());
                if (r.path("kind").asText().equals("GENERALIZATION")) {
                    r.put("source", s).put("target", t);
                    r.remove(List.of("sourceRole", "targetRole"));
                    inversa = false;
                }
                for (String extremo : List.of("sourceMultiplicity", "targetMultiplicity")) {
                    if (!p.has(extremo)) continue;
                    String destino = inversa ? extremo.equals("sourceMultiplicity") ? "targetMultiplicity" : "sourceMultiplicity" : extremo;
                    r.set(destino, p.get(extremo).deepCopy());
                }
                if (!r.path("kind").asText().equals("GENERALIZATION")) for (String extremo : List.of("sourceRole", "targetRole")) {
                    if (!p.has(extremo)) continue;
                    String destino = inversa ? extremo.equals("sourceRole") ? "targetRole" : "sourceRole" : extremo;
                    r.set(destino, p.get(extremo).deepCopy());
                }
            } else {
                // El modelo suele "corregir" una relacion volviendo a crearla. Si ya
                // existe una con los mismos extremos, tipo y roles, se actualiza esa
                // en lugar de agregar un duplicado que el validador rechaza.
                ObjectNode existente = relacionEquivalente(relaciones, candidatas, s, t, p);
                if (existente != null) {
                    boolean inversa = !s.equals(existente.path("source").asText());
                    if (p.has("name")) existente.set("name", p.get("name").deepCopy());
                    for (String extremo : List.of("sourceMultiplicity", "targetMultiplicity")) {
                        if (!p.has(extremo)) continue;
                        String destino = inversa ? extremo.equals("sourceMultiplicity") ? "targetMultiplicity" : "sourceMultiplicity" : extremo;
                        existente.set(destino, p.get(extremo).deepCopy());
                    }
                    continue;
                }
                ObjectNode r = relaciones.addObject().put("source", s).put("target", t);
                for (String campo : List.of("kind", "name", "sourceRole", "targetRole", "sourceMultiplicity", "targetMultiplicity"))
                    if (p.has(campo)) r.set(campo, p.get(campo).deepCopy());
                if (!r.hasNonNull("kind")) throw new ProveedorIAException("La relacion nueva necesita tipo");
            }
        }
        for (JsonNode r : relaciones) {
            String tipo = r.path("kind").asText();
            if (!Set.of("ASSOCIATION", "AGGREGATION", "COMPOSITION", "GENERALIZATION", "DEPENDENCY").contains(tipo))
                throw new ProveedorIAException("Tipo de relacion invalido en los ajustes");
            if (tipo.equals("GENERALIZATION") || tipo.equals("DEPENDENCY")) continue;
            for (String extremo : List.of("sourceMultiplicity", "targetMultiplicity"))
                if (!Set.of("1", "0..1", "*", "0..*", "1..*").contains(r.path(extremo).asText()))
                    throw new ProveedorIAException("Multiplicidad ausente o invalida en los ajustes");
        }
        plan.putArray("audit").add("Se corrigio el borrador mediante ajustes dirigidos sobre sus clases existentes.")
                .add("Las restricciones temporales y transaccionales requieren validacion en la logica de negocio.");
        return new RespuestaIA(List.of(new ComandoPropuestoIA(EsquemaPlanUml.NOMBRE, plan)), "");
    }

    /** Misma relacion que la pedida: extremos, tipo y roles iguales en cualquier sentido. */
    private static ObjectNode relacionEquivalente(ArrayNode relaciones, List<Integer> candidatas, String s, String t,
                                                  JsonNode p) {
        String tipo = p.path("kind").asText();
        String rolS = p.path("sourceRole").asText(), rolT = p.path("targetRole").asText();
        for (int i : candidatas) {
            ObjectNode r = (ObjectNode) relaciones.get(i);
            if (!tipo.equals(r.path("kind").asText())) continue;
            boolean directa = s.equals(r.path("source").asText()) && t.equals(r.path("target").asText());
            String rs = r.path(directa ? "sourceRole" : "targetRole").asText();
            String rt = r.path(directa ? "targetRole" : "sourceRole").asText();
            if (rolS.equals(rs) && rolT.equals(rt)) return r;
        }
        return null;
    }
}
