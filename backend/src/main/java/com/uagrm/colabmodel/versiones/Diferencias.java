package com.uagrm.colabmodel.versiones;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.uagrm.colabmodel.versiones.Foto.Elemento;
import com.uagrm.colabmodel.versiones.Foto.Tipo;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Lo que cambio entre dos fotos, emparejando por id de elemento (ADR-037).
 *
 * La forma es el contrato con la interfaz (docs/DISENO-FASE-2.md, seccion B):
 * <pre>
 * { "clases":     { "agregadas": [ {id, nombre} ], "eliminadas": [...], "modificadas": [ {id, nombre, cambios: [ {campo, antes, despues} ]} ] },
 *   "atributos":  { ... cada uno con claseId y clase },
 *   "metodos":    { ... cada uno con claseId y clase },
 *   "relaciones": { ... } }
 * </pre>
 *
 * Mover o redimensionar una caja y acomodar los quiebres de una linea tambien
 * son cambios: aparecen en `cambios` de la clase o de la relacion con los
 * campos x, y, width, height o waypoints. Restaurar los deshace, asi que el
 * diff tiene que mostrarlos.
 */
final class Diferencias {

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private Diferencias() { }

    /** Las cuatro secciones del diff de `antes` a `despues`. */
    static ObjectNode entre(Foto antes, Foto despues) {
        ObjectNode r = JSON.objectNode();
        r.set("clases", seccion(Tipo.CLASE, antes, despues));
        r.set("atributos", seccion(Tipo.ATRIBUTO, antes, despues));
        r.set("metodos", seccion(Tipo.METODO, antes, despues));
        r.set("relaciones", seccion(Tipo.RELACION, antes, despues));
        return r;
    }

    /** Si dos fotos tienen el mismo modelo (sin contar decisiones). */
    static boolean vacio(ObjectNode diff) {
        for (JsonNode s : diff) {
            if (!s.path("agregadas").isEmpty() || !s.path("eliminadas").isEmpty()
                    || !s.path("modificadas").isEmpty()) return false;
        }
        return true;
    }

    private static ObjectNode seccion(Tipo tipo, Foto antes, Foto despues) {
        ObjectNode s = JSON.objectNode();
        ArrayNode agregadas = s.putArray("agregadas");
        ArrayNode eliminadas = s.putArray("eliminadas");
        ArrayNode modificadas = s.putArray("modificadas");
        Map<UUID, Elemento> a = antes.de(tipo);
        Map<UUID, Elemento> b = despues.de(tipo);

        for (Elemento e : b.values()) {
            if (!a.containsKey(e.id())) agregadas.add(resumen(e, despues));
        }
        for (Elemento e : a.values()) {
            if (!b.containsKey(e.id())) eliminadas.add(resumen(e, antes));
        }
        for (Elemento e : b.values()) {
            Elemento viejo = a.get(e.id());
            if (viejo == null) continue;
            ArrayNode cambios = JSON.arrayNode();
            for (String campo : Foto.camposDe(tipo)) {
                agregarSiCambio(cambios, campo, viejo.campo(campo), e.campo(campo));
            }
            if (tipo == Tipo.CLASE || tipo == Tipo.RELACION) {
                Foto.Geometria ga = antes.geometria.get(e.id());
                Foto.Geometria gb = despues.geometria.get(e.id());
                for (String campo : tipo == Tipo.CLASE
                        ? java.util.List.of("x", "y", "width", "height") : java.util.List.of("waypoints")) {
                    agregarSiCambio(cambios, campo, valor(ga, campo), valor(gb, campo));
                }
            }
            if (!cambios.isEmpty()) {
                ObjectNode m = resumen(e, despues);
                m.set("cambios", cambios);
                modificadas.add(m);
            }
        }
        return s;
    }

    private static JsonNode valor(Foto.Geometria g, String campo) {
        if (g == null) return campo.equals("waypoints") ? JSON.arrayNode() : NullNode.getInstance();
        return g.campos().getOrDefault(campo, NullNode.getInstance());
    }

    private static void agregarSiCambio(ArrayNode cambios, String campo, JsonNode antes, JsonNode despues) {
        if (Objects.equals(antes, despues)) return;
        cambios.addObject().put("campo", campo).<ObjectNode>set("antes", antes).set("despues", despues);
    }

    /** {id, nombre} y, para atributos y metodos, la clase a la que pertenecen. */
    static ObjectNode resumen(Elemento e, Foto foto) {
        ObjectNode n = JSON.objectNode();
        n.put("id", e.id().toString());
        n.put("nombre", nombreDe(e, foto));
        if (e.tipo() == Tipo.ATRIBUTO || e.tipo() == Tipo.METODO) {
            UUID claseId = e.ref("classId");
            n.put("claseId", claseId == null ? null : claseId.toString());
            Elemento clase = claseId == null ? null : foto.clases.get(claseId);
            n.put("clase", clase == null ? null : clase.texto("name"));
        }
        return n;
    }

    /** Una relacion sin nombre se nombra por sus extremos, para que el diff diga algo. */
    static String nombreDe(Elemento e, Foto foto) {
        String nombre = e.texto("name");
        if (e.tipo() != Tipo.RELACION || (nombre != null && !nombre.isBlank())) return nombre;
        return nombreDeClase(foto, e.ref("sourceClassId")) + " – " + nombreDeClase(foto, e.ref("targetClassId"));
    }

    private static String nombreDeClase(Foto foto, UUID id) {
        Elemento c = id == null ? null : foto.clases.get(id);
        return c == null ? "?" : c.texto("name");
    }
}
