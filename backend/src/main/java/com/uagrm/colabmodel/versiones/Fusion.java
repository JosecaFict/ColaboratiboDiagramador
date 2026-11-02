package com.uagrm.colabmodel.versiones;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.uagrm.colabmodel.versiones.Foto.Elemento;
import com.uagrm.colabmodel.versiones.Foto.Tipo;

import java.util.*;

/**
 * Merge a tres bandas (ADR-037): base comun, "nuestro" (el diagrama destino) y
 * "suyo" (el que se mergea). Las tres fotos llegan con los ids del DESTINO.
 *
 * Por elemento, emparejado por id:
 * <ul>
 *   <li>lo agrega un solo lado: queda;</li>
 *   <li>lo borra un lado y el otro no lo toco: se borra. Si el otro lo cambio
 *       --en una clase cuenta tambien agregarle o cambiarle atributos,
 *       metodos o relaciones-- es un conflicto de campo {@code existencia},
 *       con valores "existe" / "eliminado";</li>
 *   <li>los dos lo tienen: campo por campo. Si un lado no lo cambio gana el
 *       otro; si los dos lo cambiaron igual, ese valor; si distinto, conflicto.</li>
 * </ul>
 *
 * La geometria (posicion, tamano, quiebres) se combina igual pero un choque NO
 * es conflicto: gana "nuestro". Mover una caja no es una decision de modelado,
 * y preguntar por cada caja movida en los dos lados tapa los conflictos que
 * importan.
 *
 * Las decisiones de modelado tambien se combinan; un choque es conflicto con
 * el tipo del elemento decidido y {@code campo} = "decision." + tipo de ambiguedad.
 */
final class Fusion {

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    /** Conflicto de borrado contra modificacion: valores "existe" / "eliminado". */
    static final String EXISTENCIA = "existencia";
    /** Prefijo del campo de un conflicto sobre una decision de modelado: "decision.COMPOSITION". */
    static final String DECISION = "decision.";
    /** Motivo de un conflicto de nombre repetido entre elementos distintos. */
    static final String NOMBRE_REPETIDO = "nombre repetido";

    enum Lado { NUESTRO, SUYO }

    record Conflicto(UUID elementId, String tipo, String campo, JsonNode base, JsonNode nuestro, JsonNode suyo,
                     String nombre, String clase, String motivo, String sugerido) {

        Conflicto(UUID elementId, String tipo, String campo, JsonNode base, JsonNode nuestro, JsonNode suyo,
                  String nombre, String clase) {
            this(elementId, tipo, campo, base, nuestro, suyo, nombre, clase, null, null);
        }

        String clave() { return Fusion.clave(elementId, campo); }

        ObjectNode aJson() {
            ObjectNode n = JSON.objectNode();
            n.put("elementId", elementId.toString());
            n.put("tipo", tipo);
            n.put("campo", campo);
            n.set("base", base);
            n.set("nuestro", nuestro);
            n.set("suyo", suyo);
            n.put("nombre", nombre);
            if (clase != null) n.put("clase", clase);
            if (motivo != null) n.put("motivo", motivo);
            if (sugerido != null) n.put("sugerido", sugerido);
            return n;
        }
    }

    /** El resultado del merge y los conflictos que quedaron sin resolver. */
    record Resultado(Foto combinada, List<Conflicto> conflictos, List<Conflicto> sinResolver) { }

    private Fusion() { }

    static String clave(UUID elementId, String campo) { return elementId + "/" + campo; }

    /**
     * @param resoluciones clave(elementId, campo) -> lado elegido. Un conflicto
     *                     sin resolucion se resuelve "nuestro" en la foto
     *                     combinada y queda en {@code sinResolver}.
     */
    static Resultado combinar(Foto base, Foto nuestro, Foto suyo, Map<String, Lado> resoluciones) {
        List<Conflicto> conflictos = new ArrayList<>();
        Map<UUID, Elemento> clases = new LinkedHashMap<>();
        Map<UUID, Elemento> atributos = new LinkedHashMap<>();
        Map<UUID, Elemento> metodos = new LinkedHashMap<>();
        Map<UUID, Elemento> relaciones = new LinkedHashMap<>();

        // Clases con conflicto de existencia: lo que se elija decide tambien
        // que pasa con lo de adentro (atributos, metodos, relaciones).
        Map<UUID, Lado> clasesEnDisputa = new HashMap<>();

        for (UUID id : union(base.clases, nuestro.clases, suyo.clases)) {
            Elemento b = base.clases.get(id), o = nuestro.clases.get(id), t = suyo.clases.get(id);
            if (b != null && (o == null) != (t == null)) {
                boolean otroCambio = o == null ? cambioProfundo(id, base, suyo) : cambioProfundo(id, base, nuestro);
                if (otroCambio) {
                    Conflicto c = existencia(id, "clase", o, t, b, base, nuestro, suyo);
                    conflictos.add(c);
                    Lado lado = resoluciones.getOrDefault(c.clave(), Lado.NUESTRO);
                    clasesEnDisputa.put(id, lado);
                    Elemento elegido = lado == Lado.NUESTRO ? o : t;
                    if (elegido != null) clases.put(id, elegido);
                    continue;
                }
            }
            combinarElemento(Tipo.CLASE, id, b, o, t, clases, conflictos, resoluciones, base, nuestro, suyo);
        }

        for (Tipo tipo : List.of(Tipo.ATRIBUTO, Tipo.METODO, Tipo.RELACION)) {
            Map<UUID, Elemento> destino = switch (tipo) {
                case ATRIBUTO -> atributos;
                case METODO -> metodos;
                default -> relaciones;
            };
            for (UUID id : union(base.de(tipo), nuestro.de(tipo), suyo.de(tipo))) {
                Elemento b = base.de(tipo).get(id), o = nuestro.de(tipo).get(id), t = suyo.de(tipo).get(id);
                Lado decide = ladoDeLaClase(tipo, b, o, t, clasesEnDisputa);
                if (decide != null) {
                    Elemento elegido = decide == Lado.NUESTRO ? o : t;
                    if (elegido != null) destino.put(id, elegido);
                    continue;
                }
                if (b != null && (o == null) != (t == null)) {
                    Elemento queda = o == null ? t : o;
                    if (!queda.campos().equals(b.campos())) {
                        Conflicto c = existencia(id, nombreDeTipo(tipo), o, t, b, base, nuestro, suyo);
                        conflictos.add(c);
                        Elemento elegido = resoluciones.getOrDefault(c.clave(), Lado.NUESTRO) == Lado.NUESTRO ? o : t;
                        if (elegido != null) destino.put(id, elegido);
                        continue;
                    }
                }
                combinarElemento(tipo, id, b, o, t, destino, conflictos, resoluciones, base, nuestro, suyo);
            }
        }

        nombresRepetidos(Tipo.CLASE, clases, nuestro, base, conflictos, resoluciones);
        nombresRepetidos(Tipo.ATRIBUTO, atributos, nuestro, base, conflictos, resoluciones);

        // Nada puede quedar colgando de algo que el merge se llevo.
        boolean limpio = false;
        while (!limpio) {
            limpio = true;
            for (Iterator<Elemento> it = atributos.values().iterator(); it.hasNext(); ) {
                Elemento a = it.next();
                UUID padre = a.ref("parentAttributeId");
                if (!clases.containsKey(a.ref("classId")) || (padre != null && !atributos.containsKey(padre))) {
                    it.remove();
                    limpio = false;
                }
            }
        }
        metodos.values().removeIf(m -> !clases.containsKey(m.ref("classId")));
        relaciones.values().removeIf(r -> !clases.containsKey(r.ref("sourceClassId"))
                || !clases.containsKey(r.ref("targetClassId")));

        Map<UUID, Foto.Geometria> geometria = new LinkedHashMap<>();
        for (UUID id : union(clases, relaciones)) {
            Foto.Geometria g = combinarGeometria(base.geometria.get(id), nuestro.geometria.get(id),
                    suyo.geometria.get(id));
            if (g != null) geometria.put(id, g);
        }

        Map<String, Foto.Decision> decisiones = new LinkedHashMap<>();
        Set<String> claves = new LinkedHashSet<>(base.decisiones.keySet());
        claves.addAll(nuestro.decisiones.keySet());
        claves.addAll(suyo.decisiones.keySet());
        for (String k : claves) {
            Foto.Decision b = base.decisiones.get(k), o = nuestro.decisiones.get(k), t = suyo.decisiones.get(k);
            UUID decidido = (o != null ? o : t != null ? t : b).elementId();
            // Una decision sobre algo que el merge se llevo no se escribe ni se
            // pregunta: SET_MODELING_DECISION no valida el elemento, y quedaria
            // una decision huerfana en el diagrama.
            if (!clases.containsKey(decidido) && !atributos.containsKey(decidido)
                    && !relaciones.containsKey(decidido) && !metodos.containsKey(decidido)) continue;
            String vb = b == null ? null : b.elegida(), vo = o == null ? null : o.elegida(), vt = t == null ? null : t.elegida();
            Foto.Decision ref = o != null ? o : t != null ? t : b;
            String elegida;
            if (Objects.equals(vo, vt) || Objects.equals(vt, vb)) elegida = vo;
            else if (Objects.equals(vo, vb)) elegida = vt;
            else {
                // El tipo es el del elemento decidido: la interfaz solo conoce
                // clase|atributo|metodo|relacion. El campo dice que es una decision.
                Conflicto c = new Conflicto(ref.elementId(), tipoDe(ref.elementId(), base, nuestro, suyo),
                        DECISION + ref.tipo(), texto(vb), texto(vo), texto(vt),
                        nombreDe(ref.elementId(), base, nuestro, suyo), null);
                conflictos.add(c);
                elegida = resoluciones.getOrDefault(c.clave(), Lado.NUESTRO) == Lado.NUESTRO ? vo : vt;
            }
            if (elegida == null) elegida = vo != null ? vo : vt;   // no hay forma de olvidar una decision
            if (elegida != null) decisiones.put(k, new Foto.Decision(ref.elementId(), ref.tipo(), elegida));
        }

        Foto combinada = new Foto(nuestro.diagramId, nuestro.nombre, nuestro.seq, nuestro.vistaPorDefecto,
                clases, atributos, metodos, relaciones, geometria, decisiones);
        List<Conflicto> sinResolver = conflictos.stream()
                .filter(c -> !resoluciones.containsKey(c.clave())).toList();
        return new Resultado(combinada, List.copyOf(conflictos), sinResolver);
    }

    /**
     * Dos elementos DISTINTOS que quedarian con el mismo nombre (sin distinguir
     * mayusculas, como el indice unico de la base): los dos lados agregan
     * "Prestamo", o uno renombra a un nombre que el otro uso para algo nuevo.
     * Sin esto la previa decia "se puede mergear" y el lote lo rechazaba la base.
     *
     * Es un conflicto sobre el elemento que trae "suyo" (el nuevo, o el que
     * "suyo" renombro), campo {@code name}, motivo "nombre repetido":
     * <ul>
     *   <li>"nuestro" (por defecto): ese cambio no entra. Si era nuevo, no se
     *       trae (con lo que cuelga de el); si era un renombre, queda el nombre
     *       de "nuestro".</li>
     *   <li>"suyo": entra con el nombre libre que dice {@code sugerido}
     *       ("Prestamo_2"). Nunca se borra ni se renombra algo de "nuestro".</li>
     * </ul>
     */
    private static void nombresRepetidos(Tipo tipo, Map<UUID, Elemento> elementos, Foto nuestro, Foto base,
                                         List<Conflicto> conflictos, Map<String, Lado> resoluciones) {
        Map<String, List<Elemento>> porNombre = new LinkedHashMap<>();
        for (Elemento e : elementos.values()) {
            String n = e.texto("name");
            if (n == null) continue;
            porNombre.computeIfAbsent(ambito(tipo, e) + "/" + n.toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(e);
        }
        Set<String> ocupados = new HashSet<>(porNombre.keySet());
        for (List<Elemento> grupo : porNombre.values()) {
            if (grupo.size() < 2) continue;
            for (Elemento e : grupo) {
                Elemento enNuestro = nuestro.de(tipo).get(e.id());
                // El que ya tenia ese nombre en "nuestro" se queda como esta.
                if (enNuestro != null && Objects.equals(enNuestro.texto("name"), e.texto("name"))) continue;
                String sugerido = libre(e.texto("name"), ambito(tipo, e), ocupados);
                Elemento enBase = base.de(tipo).get(e.id());
                Conflicto c = new Conflicto(e.id(), nombreDeTipo(tipo), "name",
                        enBase == null ? com.fasterxml.jackson.databind.node.NullNode.getInstance() : enBase.campo("name"),
                        enNuestro == null ? com.fasterxml.jackson.databind.node.NullNode.getInstance() : enNuestro.campo("name"),
                        e.campo("name"), e.texto("name"), claseDe(tipo, e, nuestro), NOMBRE_REPETIDO, sugerido);
                conflictos.add(c);
                if (resoluciones.getOrDefault(c.clave(), Lado.NUESTRO) == Lado.SUYO) {
                    ocupados.add(ambito(tipo, e) + "/" + sugerido.toLowerCase(Locale.ROOT));
                    elementos.put(e.id(), e.con("name", JSON.textNode(sugerido)));
                } else if (enNuestro == null) {
                    elementos.remove(e.id());
                } else {
                    elementos.put(e.id(), e.con("name", enNuestro.campo("name")));
                }
            }
        }
    }

    private static String ambito(Tipo tipo, Elemento e) {
        return tipo == Tipo.ATRIBUTO ? e.texto("classId") : "";
    }

    private static String libre(String nombre, String ambito, Set<String> ocupados) {
        for (int i = 2; ; i++) {
            String n = nombre + "_" + i;
            if (!ocupados.contains(ambito + "/" + n.toLowerCase(Locale.ROOT))) return n;
        }
    }

    /** Si el elemento cuelga de una clase en disputa, el lado elegido para la clase. */
    private static Lado ladoDeLaClase(Tipo tipo, Elemento b, Elemento o, Elemento t, Map<UUID, Lado> enDisputa) {
        if (enDisputa.isEmpty()) return null;
        Set<UUID> clases = new HashSet<>();
        for (Elemento e : Arrays.asList(b, o, t)) {
            if (e == null) continue;
            if (tipo == Tipo.RELACION) {
                clases.add(e.ref("sourceClassId"));
                clases.add(e.ref("targetClassId"));
            } else {
                clases.add(e.ref("classId"));
            }
        }
        for (UUID c : clases) if (enDisputa.containsKey(c)) return enDisputa.get(c);
        return null;
    }

    private static void combinarElemento(Tipo tipo, UUID id, Elemento b, Elemento o, Elemento t,
                                         Map<UUID, Elemento> destino, List<Conflicto> conflictos,
                                         Map<String, Lado> resoluciones, Foto base, Foto nuestro, Foto suyo) {
        if (b == null) {                       // lo agrego un lado
            if (o != null) destino.put(id, o);
            else if (t != null) destino.put(id, t);
            return;
        }
        if (o == null || t == null) return;   // lo borro un lado y el otro no lo toco (o lo borraron los dos)

        Map<String, JsonNode> campos = new LinkedHashMap<>();
        for (String campo : Foto.camposDe(tipo)) {
            JsonNode vb = b.campo(campo), vo = o.campo(campo), vt = t.campo(campo);
            if (vo.equals(vt) || vt.equals(vb)) campos.put(campo, vo);
            else if (vo.equals(vb)) campos.put(campo, vt);
            else {
                Conflicto c = new Conflicto(id, nombreDeTipo(tipo), campo, vb, vo, vt,
                        Diferencias.nombreDe(o, nuestro), claseDe(tipo, o, nuestro));
                conflictos.add(c);
                campos.put(campo, resoluciones.getOrDefault(c.clave(), Lado.NUESTRO) == Lado.NUESTRO ? vo : vt);
            }
        }
        destino.put(id, new Elemento(id, tipo, campos));
    }

    private static Conflicto existencia(UUID id, String tipo, Elemento o, Elemento t, Elemento b,
                                        Foto base, Foto nuestro, Foto suyo) {
        Elemento ref = o != null ? o : t;
        Foto donde = o != null ? nuestro : suyo;
        return new Conflicto(id, tipo, EXISTENCIA, existe(true), existe(o != null),
                existe(t != null), Diferencias.nombreDe(ref, donde),
                claseDe(b.tipo(), ref, donde));
    }

    /**
     * Si un lado cambio una clase o algo que cuelga de ella respecto de la base:
     * lo que hace que borrarla del otro lado sea un conflicto y no un borrado limpio.
     */
    private static boolean cambioProfundo(UUID claseId, Foto base, Foto lado) {
        Elemento b = base.clases.get(claseId), l = lado.clases.get(claseId);
        if (l == null || !l.campos().equals(b.campos())) return true;
        String id = claseId.toString();
        for (Tipo tipo : List.of(Tipo.ATRIBUTO, Tipo.METODO, Tipo.RELACION)) {
            for (UUID e : union(base.de(tipo), lado.de(tipo))) {
                Elemento eb = base.de(tipo).get(e), el = lado.de(tipo).get(e);
                boolean deLaClase = Arrays.asList(eb, el).stream().filter(Objects::nonNull).anyMatch(x ->
                        tipo == Tipo.RELACION
                                ? id.equals(x.texto("sourceClassId")) || id.equals(x.texto("targetClassId"))
                                : id.equals(x.texto("classId")));
                if (!deLaClase) continue;
                if (eb == null || el == null || !eb.campos().equals(el.campos())) return true;
            }
        }
        return false;
    }

    private static Foto.Geometria combinarGeometria(Foto.Geometria b, Foto.Geometria o, Foto.Geometria t) {
        if (o == null && t == null) return null;
        if (o == null) return t;
        if (t == null || b == null) return o;
        Map<String, JsonNode> campos = new LinkedHashMap<>();
        for (String campo : Foto.CAMPOS_GEOMETRIA) {
            JsonNode vb = b.campos().get(campo), vo = o.campos().get(campo), vt = t.campos().get(campo);
            // Solo cambio "suyo": gana suyo. En cualquier otro caso, "nuestro".
            campos.put(campo, Objects.equals(vo, vb) && !Objects.equals(vt, vb) ? vt : vo);
        }
        return new Foto.Geometria(o.elementId(), campos);
    }

    private static String claseDe(Tipo tipo, Elemento e, Foto foto) {
        if (tipo != Tipo.ATRIBUTO && tipo != Tipo.METODO) return null;
        Elemento c = foto.clases.get(e.ref("classId"));
        return c == null ? null : c.texto("name");
    }

    private static String nombreDe(UUID elementId, Foto... fotos) {
        for (Foto f : fotos) {
            for (Tipo t : Tipo.values()) {
                Elemento e = f.de(t).get(elementId);
                if (e != null) return Diferencias.nombreDe(e, f);
            }
        }
        return null;
    }

    private static JsonNode existe(boolean existe) {
        return JSON.textNode(existe ? "existe" : "eliminado");
    }

    private static String tipoDe(UUID elementId, Foto... fotos) {
        for (Foto f : fotos) {
            for (Tipo t : Tipo.values()) if (f.de(t).containsKey(elementId)) return nombreDeTipo(t);
        }
        return "clase";
    }

    static String nombreDeTipo(Tipo tipo) {
        return switch (tipo) {
            case CLASE -> "clase";
            case ATRIBUTO -> "atributo";
            case METODO -> "metodo";
            case RELACION -> "relacion";
        };
    }

    private static JsonNode texto(String v) {
        return v == null ? com.fasterxml.jackson.databind.node.NullNode.getInstance() : JSON.textNode(v);
    }

    @SafeVarargs
    private static <V> Set<UUID> union(Map<UUID, V>... mapas) {
        Set<UUID> ids = new LinkedHashSet<>();
        for (Map<UUID, V> m : mapas) ids.addAll(m.keySet());
        return ids;
    }
}
