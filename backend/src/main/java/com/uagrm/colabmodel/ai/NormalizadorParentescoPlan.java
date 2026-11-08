package com.uagrm.colabmodel.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;

/** Simplifica exclusivamente roles familiares redundantes dentro de una propuesta. */
final class NormalizadorParentescoPlan {
    private NormalizadorParentescoPlan() { }

    static boolean puedeSimplificar(JsonNode plan, JsonNode clase) {
        return base(plan, clase) != null;
    }

    /** Completa exclusivamente los roles familiares expresamente pedidos, sin crear entidades. */
    static int completarExplicitos(ObjectNode plan, String requisito) {
        String pedido = requisito.toLowerCase(Locale.ROOT);
        if (!pedido.contains("padre") || !pedido.contains("madre")) return 0;
        ArrayNode clases = (ArrayNode) plan.path("classes"), relaciones = (ArrayNode) plan.path("relations");
        List<String> bases = new ArrayList<>();
        for (JsonNode c : clases) if (Set.of("animal", "persona").contains(c.path("name").asText().toLowerCase(Locale.ROOT)))
            bases.add(c.path("name").asText());
        if (bases.size() != 1) return 0;
        String entidad = bases.getFirst();
        int cambios = 0;
        // Una relacion familiar reificada sin datos ni otros participantes es redundante.
        // Se conserva cuando tiene atributos propios: no se descarta informacion del usuario.
        for (int i = clases.size() - 1; i >= 0; i--) {
            JsonNode c = clases.get(i);
            if (!c.path("name").asText().matches("(?i)(RelacionPadreHijo|Parentesco)")) continue;
            boolean soloIdentidad = true, soloBase = true;
            for (JsonNode a : c.path("attributes")) {
                String campo = a.path("name").asText();
                if (!campo.equalsIgnoreCase("id") && !campo.matches("^id[_A-Z].*")) soloIdentidad = false;
            }
            String n = c.path("name").asText();
            for (JsonNode r : relaciones) if (n.equals(r.path("source").asText()) || n.equals(r.path("target").asText())) {
                String otro = n.equals(r.path("source").asText()) ? r.path("target").asText() : r.path("source").asText();
                if (!entidad.equals(otro) || !"ASSOCIATION".equals(r.path("kind").asText("ASSOCIATION"))) soloBase = false;
            }
            if (!soloIdentidad || !soloBase) return cambios;
            for (int r = relaciones.size() - 1; r >= 0; r--) if (n.equals(relaciones.get(r).path("source").asText())
                    || n.equals(relaciones.get(r).path("target").asText())) relaciones.remove(r);
            clases.remove(i); cambios++;
        }
        for (String rol : List.of("padre", "madre")) {
            boolean existe = false;
            for (JsonNode r : relaciones) if (entidad.equals(r.path("source").asText()) && entidad.equals(r.path("target").asText())
                    && (rol.equalsIgnoreCase(r.path("sourceRole").asText()) || rol.equalsIgnoreCase(r.path("targetRole").asText()))) existe = true;
            if (!existe) {
                relaciones.addObject().put("source", entidad).put("target", entidad).put("kind", "ASSOCIATION")
                        .put("name", rol.equals("padre") ? "tienePadre" : "tieneMadre")
                        .put("sourceRole", "descendientes").put("targetRole", rol)
                        .put("sourceMultiplicity", "0..*").put("targetMultiplicity", "0..1");
                cambios++;
            }
        }
        return cambios;
    }

    static int simplificar(ObjectNode plan) {
        if (!(plan.path("classes") instanceof ArrayNode clases)
                || !(plan.path("relations") instanceof ArrayNode relaciones)) return 0;
        int cambios = 0;
        for (int i = clases.size() - 1; i >= 0; i--) {
            JsonNode clase = clases.get(i), base = base(plan, clase);
            if (base == null) continue;
            String nombre = clase.path("name").asText(), entidad = base.path("name").asText();
            String rol = nombre.toLowerCase(Locale.ROOT).startsWith("padre") ? "padre" : "madre";
            for (int r = relaciones.size() - 1; r >= 0; r--) {
                ObjectNode relacion = (ObjectNode) relaciones.get(r);
                boolean sale = nombre.equals(relacion.path("source").asText()), llega = nombre.equals(relacion.path("target").asText());
                if (!sale && !llega) continue;
                String otro = relacion.path(sale ? "target" : "source").asText();
                // La relacion con la base es la del rol (se recrea abajo como recursiva). Con otra clase,
                // pasa a la base si la base todavia no la tiene; si ya la tiene, sobra.
                if (otro.equals(entidad) || otro.equals(nombre) || relacionadas(relaciones, entidad, otro)) relaciones.remove(r);
                else relacion.put(sale ? "source" : "target", entidad);
            }
            // El padre es un Animal: sus datos propios pasan a la entidad base en vez de perderse.
            ArrayNode atributosBase = (ArrayNode) base.path("attributes");
            for (JsonNode a : clase.path("attributes")) {
                String campo = a.path("name").asText();
                if (campo.equalsIgnoreCase("id") || campo.matches("^id[_A-Z].*") || a.path("pk").asBoolean()) continue;
                boolean existe = false;
                for (JsonNode b : atributosBase) if (campo.equalsIgnoreCase(b.path("name").asText())) existe = true;
                if (!existe) atributosBase.add(a.deepCopy());
            }
            clases.remove(i);
            ObjectNode recursiva = recursivaSinRol(relaciones, entidad, rol);
            if (recursiva == null) recursiva = relaciones.addObject().put("source", entidad).put("target", entidad);
            recursiva.put("kind", "ASSOCIATION")
                    .put("name", rol.equals("padre") ? "tienePadre" : "tieneMadre")
                    .put("sourceRole", "descendientes").put("targetRole", rol)
                    .put("sourceMultiplicity", "0..*").put("targetMultiplicity", "0..1");
            cambios++;
        }
        return cambios;
    }

    /**
     * La asociacion recursiva que ya cumple ese rol, o una sin roles que el modelo
     * dibujo junto a la clase PadreX: se reutiliza en vez de agregar otra.
     */
    private static ObjectNode recursivaSinRol(ArrayNode relaciones, String entidad, String rol) {
        ObjectNode libre = null;
        for (JsonNode r : relaciones) {
            if (!entidad.equals(r.path("source").asText()) || !entidad.equals(r.path("target").asText())) continue;
            String rs = r.path("sourceRole").asText(), rt = r.path("targetRole").asText();
            if (rol.equalsIgnoreCase(rs) || rol.equalsIgnoreCase(rt)) return (ObjectNode) r;
            if (libre == null && rs.isBlank() && rt.isBlank()) libre = (ObjectNode) r;
        }
        return libre;
    }

    private static boolean relacionadas(ArrayNode relaciones, String a, String b) {
        for (JsonNode r : relaciones) {
            String s = r.path("source").asText(), t = r.path("target").asText();
            if ((s.equals(a) && t.equals(b)) || (s.equals(b) && t.equals(a))) return true;
        }
        return false;
    }

    private static JsonNode base(JsonNode plan, JsonNode clase) {
        String nombre = clase.path("name").asText(), n = nombre.toLowerCase(Locale.ROOT);
        if (!n.matches("(padre|madre)(animal|persona)?")) return null;
        Set<String> vecinas = new HashSet<>();
        for (JsonNode r : plan.path("relations")) {
            String s = r.path("source").asText(), t = r.path("target").asText();
            if (!s.equals(nombre) && !t.equals(nombre)) continue;
            if (!r.path("kind").asText("ASSOCIATION").equals("ASSOCIATION")) return null;
            vecinas.add(s.equals(nombre) ? t : s);
        }
        String sufijo = n.substring(5);
        String candidata;
        if (!sufijo.isEmpty()) {
            // Ganaderia 2026-11-08: PadreAnimal unida a Animal y tambien a Lote o Especie no se fusionaba.
            // El nombre ya dice de que entidad es rol; sus otras relaciones pasan a la base en simplificar().
            candidata = vecinas.stream().filter(v -> v.equalsIgnoreCase(sufijo)).findFirst().orElse(null);
            if (candidata == null) return null;
        } else {
            if (vecinas.size() != 1) return null;
            candidata = vecinas.iterator().next();
        }
        if (!Set.of("animal", "persona").contains(candidata.toLowerCase(Locale.ROOT))) return null;
        JsonNode base = null;
        for (JsonNode c : plan.path("classes")) if (c.path("name").asText().equals(candidata)) base = c;
        // Una clave propia del rol (registroGenealogico pk) es una identidad deliberada:
        // no se fusiona. Los datos comunes no lo impiden; simplificar() los pasa a la base.
        for (JsonNode a : clase.path("attributes")) {
            String campo = a.path("name").asText();
            if (a.path("pk").asBoolean() && !campo.equalsIgnoreCase("id") && !campo.matches("^id[_A-Z].*")) return null;
        }
        return base;
    }
}
