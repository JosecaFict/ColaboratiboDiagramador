package com.uagrm.colabmodel.versiones;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.uagrm.colabmodel.versiones.Foto.Elemento;

import java.util.*;

/**
 * Los comandos que llevan el modelo de `actual` a `deseada`.
 *
 * Restaurar, crear una rama y mergear NO agregan comandos al catalogo cerrado
 * (ADR-014, ADR-037): se calcula esta lista y viaja como UN BATCH por el bus.
 * Asi se secuencia, todos lo ven en vivo, queda en el log con su autor y pasa
 * por los locks, igual que cualquier edicion.
 *
 * El orden importa, porque el lote se aplica cambio por cambio dentro de una
 * transaccion y la base tiene restricciones que se verifican al momento:
 * <ol>
 *   <li>primero se borra (relaciones, metodos, atributos de hijo a padre, clases),
 *       para liberar nombres;</li>
 *   <li>despues se renombra, en dos pasos cuando dos elementos intercambian
 *       nombres (si no, el indice unico de nombres rechaza el primero);</li>
 *   <li>despues se crea y se actualiza (clases, atributos de padre a hijo,
 *       metodos, relaciones);</li>
 *   <li>al final la geometria y las decisiones de modelado.</li>
 * </ol>
 *
 * Lo que el catalogo no sabe cambiar en el lugar se rehace con el MISMO id:
 * un metodo distinto se quita y se vuelve a agregar (no hay UPDATE_METHOD); un
 * atributo que cambio de clase o de padre, igual; una relacion que cambio de
 * extremos se borra y se recrea (CREATE_RELATION reusa el tombstone); y una
 * clase que cambio de estereotipo o de abstracta se borra y se recrea con todo
 * lo suyo (CREATE_CLASS tambien reusa el tombstone).
 */
final class PlanDeCambios {

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private PlanDeCambios() { }

    /** Cada elemento: {"type": ..., "payload": {...}}, listo para un BATCH. */
    static List<ObjectNode> de(Foto actual, Foto deseada) {
        List<ObjectNode> plan = new ArrayList<>();

        // ------------------------------------------------------- quien queda
        Set<UUID> recrear = new LinkedHashSet<>();
        for (Elemento c : deseada.clases.values()) {
            Elemento ahora = actual.clases.get(c.id());
            if (ahora != null && (!ahora.campo("stereotype").equals(c.campo("stereotype"))
                    || !ahora.campo("isAbstract").equals(c.campo("isAbstract")))) {
                recrear.add(c.id());
            }
        }
        Set<UUID> clasesQueSeBorran = new LinkedHashSet<>();
        for (UUID id : actual.clases.keySet()) {
            if (!deseada.clases.containsKey(id) || recrear.contains(id)) clasesQueSeBorran.add(id);
        }

        Set<UUID> relacionesQueQuedan = new HashSet<>();
        for (Elemento r : actual.relaciones.values()) {
            Elemento d = deseada.relaciones.get(r.id());
            if (d != null && mismos(r, d, "sourceClassId", "targetClassId", "degree")
                    && !clasesQueSeBorran.contains(r.ref("sourceClassId"))
                    && !clasesQueSeBorran.contains(r.ref("targetClassId"))) {
                relacionesQueQuedan.add(r.id());
            }
        }

        Set<UUID> atributosQueQuedan = new HashSet<>();
        for (Elemento a : actual.atributos.values()) {
            Elemento d = deseada.atributos.get(a.id());
            if (d != null && mismos(a, d, "classId", "parentAttributeId")
                    && !clasesQueSeBorran.contains(a.ref("classId"))) {
                atributosQueQuedan.add(a.id());
            }
        }
        // Si el padre de un atributo compuesto se va, la base se lleva al hijo
        // (ON DELETE CASCADE): el hijo tampoco queda, aunque no haya cambiado.
        boolean cambio = true;
        while (cambio) {
            cambio = false;
            for (UUID id : new ArrayList<>(atributosQueQuedan)) {
                UUID padre = actual.atributos.get(id).ref("parentAttributeId");
                if (padre != null && !atributosQueQuedan.contains(padre)) {
                    atributosQueQuedan.remove(id);
                    cambio = true;
                }
            }
        }

        Set<UUID> metodosQueQuedan = new HashSet<>();
        for (Elemento m : actual.metodos.values()) {
            Elemento d = deseada.metodos.get(m.id());
            if (d != null && d.campos().equals(m.campos()) && !clasesQueSeBorran.contains(m.ref("classId"))) {
                metodosQueQuedan.add(m.id());
            }
        }

        // ------------------------------------------------------------ borrar
        for (Elemento r : actual.relaciones.values()) {
            if (relacionesQueQuedan.contains(r.id())) continue;
            // DELETE_CLASS ya se lleva las relaciones de la clase.
            if (clasesQueSeBorran.contains(r.ref("sourceClassId"))
                    || clasesQueSeBorran.contains(r.ref("targetClassId"))) continue;
            plan.add(cmd("DELETE_RELATION", p().put("relationId", r.id().toString())));
        }
        for (Elemento m : actual.metodos.values()) {
            if (metodosQueQuedan.contains(m.id()) || clasesQueSeBorran.contains(m.ref("classId"))) continue;
            plan.add(cmd("REMOVE_METHOD", p().put("methodId", m.id().toString())));
        }
        List<Elemento> atributosAQuitar = new ArrayList<>();
        for (Elemento a : actual.atributos.values()) {
            if (atributosQueQuedan.contains(a.id()) || clasesQueSeBorran.contains(a.ref("classId"))) continue;
            atributosAQuitar.add(a);
        }
        // De hijo a padre: quitar primero al padre se lleva al hijo, y el
        // REMOVE_ATTRIBUTE del hijo fallaria con NOT_FOUND.
        atributosAQuitar.sort(Comparator.comparingInt((Elemento a) -> profundidad(a, actual)).reversed());
        for (Elemento a : atributosAQuitar) {
            plan.add(cmd("REMOVE_ATTRIBUTE", p().put("attributeId", a.id().toString())));
        }
        for (UUID id : clasesQueSeBorran) {
            plan.add(cmd("DELETE_CLASS", p().put("classId", id.toString())));
        }

        // ---------------------------------------------------------- renombrar
        List<Elemento> clasesQueQuedan = actual.clases.values().stream()
                .filter(c -> !clasesQueSeBorran.contains(c.id())).toList();
        renombrarEnDosPasos(plan, clasesQueQuedan, deseada.clases, (c, nombre) ->
                cmd("RENAME_CLASS", p().put("classId", c.id().toString()).put("name", nombre)));

        Map<UUID, List<Elemento>> quedanPorClase = new LinkedHashMap<>();
        for (Elemento a : actual.atributos.values()) {
            if (atributosQueQuedan.contains(a.id())) {
                quedanPorClase.computeIfAbsent(a.ref("classId"), k -> new ArrayList<>()).add(a);
            }
        }
        for (List<Elemento> deUnaClase : quedanPorClase.values()) {
            renombrarEnDosPasos(plan, deUnaClase, deseada.atributos, (a, nombre) ->
                    cmd("UPDATE_ATTRIBUTE", p().put("attributeId", a.id().toString()).put("name", nombre)));
        }

        // ------------------------------------------------------ crear y cambiar
        for (Elemento c : deseada.clases.values()) {
            boolean nueva = !actual.clases.containsKey(c.id()) || recrear.contains(c.id());
            if (nueva) {
                ObjectNode p = p().put("classId", c.id().toString());
                for (String campo : List.of("name", "stereotype", "isAbstract", "weak", "erKind")) {
                    p.set(campo, c.campo(campo));
                }
                Foto.Geometria g = deseada.geometria.get(c.id());
                if (g != null) {
                    for (String campo : List.of("x", "y", "width", "height")) p.set(campo, g.campos().get(campo));
                }
                plan.add(cmd("CREATE_CLASS", p));
                // CREATE_CLASS deja JOINED, como una clase nueva.
                if (!"JOINED".equals(c.texto("inheritanceStrategy")) && c.texto("inheritanceStrategy") != null) {
                    plan.add(banderaDeClase(c.id(), "inheritanceStrategy", "strategy", c.campo("inheritanceStrategy")));
                }
            } else {
                Elemento ahora = actual.clases.get(c.id());
                if (!ahora.campo("weak").equals(c.campo("weak"))) {
                    plan.add(cmd("SET_ER_FLAG", p().put("targetType", "CLASS").put("targetId", c.id().toString())
                            .put("flag", "weak").put("value", c.campo("weak").asBoolean())));
                }
                if (!ahora.campo("erKind").equals(c.campo("erKind"))) {
                    plan.add(banderaDeClase(c.id(), "erKind", "erKind", c.campo("erKind")));
                }
                if (!ahora.campo("inheritanceStrategy").equals(c.campo("inheritanceStrategy"))) {
                    plan.add(banderaDeClase(c.id(), "inheritanceStrategy", "strategy", c.campo("inheritanceStrategy")));
                }
            }
        }

        // Atributos: de padre a hijo, para que el padre exista al agregar el hijo.
        List<Elemento> atributosNuevos = deseada.atributos.values().stream()
                .filter(a -> !atributosQueQuedan.contains(a.id()))
                .sorted(Comparator.comparingInt(a -> profundidad(a, deseada)))
                .toList();
        for (Elemento a : atributosNuevos) {
            ObjectNode p = p().put("attributeId", a.id().toString());
            a.campos().forEach(p::set);
            plan.add(cmd("ADD_ATTRIBUTE", p));
        }
        for (UUID id : atributosQueQuedan) {
            Elemento ahora = actual.atributos.get(id);
            Elemento d = deseada.atributos.get(id);
            ObjectNode p = p().put("attributeId", id.toString());
            boolean hay = false;
            for (String campo : Foto.CAMPOS_ATRIBUTO) {
                // name ya se cambio arriba; clase y padre no cambian si quedo.
                if (campo.equals("name") || campo.equals("classId") || campo.equals("parentAttributeId")) continue;
                if (!ahora.campo(campo).equals(d.campo(campo))) {
                    p.set(campo, d.campo(campo));
                    hay = true;
                }
            }
            if (hay) plan.add(cmd("UPDATE_ATTRIBUTE", p));
        }

        for (Elemento m : deseada.metodos.values()) {
            if (metodosQueQuedan.contains(m.id())) continue;
            ObjectNode p = p().put("methodId", m.id().toString());
            m.campos().forEach(p::set);
            plan.add(cmd("ADD_METHOD", p));
        }

        for (Elemento r : deseada.relaciones.values()) {
            if (relacionesQueQuedan.contains(r.id())) {
                Elemento ahora = actual.relaciones.get(r.id());
                ObjectNode p = p().put("relationId", r.id().toString());
                boolean hay = false;
                for (String campo : Foto.CAMPOS_RELACION) {
                    if (campo.equals("sourceClassId") || campo.equals("targetClassId") || campo.equals("degree")) continue;
                    if (!ahora.campo(campo).equals(r.campo(campo))) {
                        p.set(campo, r.campo(campo));
                        hay = true;
                    }
                }
                if (hay) plan.add(cmd("UPDATE_RELATION", p));
            } else {
                ObjectNode p = p().put("relationId", r.id().toString());
                r.campos().forEach(p::set);
                plan.add(cmd("CREATE_RELATION", p));
            }
        }

        // ---------------------------------------------------------- geometria
        for (Elemento c : deseada.clases.values()) {
            boolean nueva = !actual.clases.containsKey(c.id()) || recrear.contains(c.id());
            Foto.Geometria d = deseada.geometria.get(c.id());
            if (nueva || d == null) continue;
            Foto.Geometria ahora = actual.geometria.get(c.id());
            if (ahora == null || !mismos(ahora.campos(), d.campos(), "x", "y")) {
                plan.add(cmd("MOVE_ELEMENT", p().put("elementId", c.id().toString()).put("elementKind", "CLASS")
                        .<ObjectNode>set("x", d.campos().get("x")).set("y", d.campos().get("y"))));
            }
            if (ahora == null || !mismos(ahora.campos(), d.campos(), "width", "height")) {
                plan.add(cmd("RESIZE_ELEMENT", p().put("elementId", c.id().toString()).put("elementKind", "CLASS")
                        .<ObjectNode>set("width", d.campos().get("width")).set("height", d.campos().get("height"))));
            }
        }
        for (Elemento r : deseada.relaciones.values()) {
            Foto.Geometria d = deseada.geometria.get(r.id());
            JsonNode quiebres = d == null ? JSON.arrayNode() : d.campos().get("waypoints");
            boolean queda = relacionesQueQuedan.contains(r.id());
            Foto.Geometria ahora = queda ? actual.geometria.get(r.id()) : null;
            JsonNode actuales = ahora == null ? JSON.arrayNode() : ahora.campos().get("waypoints");
            if (!quiebres.equals(actuales)) {
                plan.add(cmd("MOVE_ELEMENT", p().put("elementId", r.id().toString()).put("elementKind", "RELATION")
                        .set("waypoints", quiebres)));
            }
        }

        // --------------------------------------------------------- decisiones
        // No hay comando para "olvidar" una decision: una que hoy existe y en la
        // version no, queda. Es la unica parte del modelo que restaurar no
        // devuelve exacta, y no cambia el esquema mas alla de la lectura elegida.
        for (Foto.Decision d : deseada.decisiones.values()) {
            Foto.Decision ahora = actual.decisiones.get(d.clave());
            if (ahora != null && ahora.elegida().equals(d.elegida())) continue;
            plan.add(cmd("SET_MODELING_DECISION", p().put("elementId", d.elementId().toString())
                    .put("ambiguityKind", d.tipo()).put("chosen", d.elegida())));
        }
        return plan;
    }

    /**
     * Renombra los que cambian de nombre. Si el nombre nuevo de uno lo tiene
     * HOY otro que tambien queda, primero va a un nombre temporal: el indice
     * unico (case-insensitive) rechazaria el cambio directo, por ejemplo al
     * intercambiar dos nombres.
     */
    private static void renombrarEnDosPasos(List<ObjectNode> plan, List<Elemento> quedan,
                                            Map<UUID, Elemento> deseados,
                                            java.util.function.BiFunction<Elemento, String, ObjectNode> renombrar) {
        Map<String, UUID> ocupados = new HashMap<>();
        for (Elemento e : quedan) ocupados.put(clave(e.texto("name")), e.id());

        List<Elemento> directos = new ArrayList<>();
        List<Elemento> conTemporal = new ArrayList<>();
        for (Elemento e : quedan) {
            String nuevo = deseados.get(e.id()).texto("name");
            if (Objects.equals(nuevo, e.texto("name"))) continue;
            UUID duenio = ocupados.get(clave(nuevo));
            if (duenio != null && !duenio.equals(e.id())) conTemporal.add(e);
            else directos.add(e);
        }
        for (Elemento e : conTemporal) {
            plan.add(renombrar.apply(e, "_tmp_" + e.id().toString().substring(0, 8)));
        }
        for (Elemento e : directos) plan.add(renombrar.apply(e, deseados.get(e.id()).texto("name")));
        for (Elemento e : conTemporal) plan.add(renombrar.apply(e, deseados.get(e.id()).texto("name")));
    }

    private static String clave(String nombre) {
        return nombre == null ? "" : nombre.toLowerCase(Locale.ROOT);
    }

    private static int profundidad(Elemento a, Foto foto) {
        int n = 0;
        UUID padre = a.ref("parentAttributeId");
        Set<UUID> vistos = new HashSet<>();
        while (padre != null && vistos.add(padre)) {
            n++;
            Elemento p = foto.atributos.get(padre);
            padre = p == null ? null : p.ref("parentAttributeId");
        }
        return n;
    }

    private static boolean mismos(Elemento a, Elemento b, String... campos) {
        for (String c : campos) if (!a.campo(c).equals(b.campo(c))) return false;
        return true;
    }

    private static boolean mismos(Map<String, JsonNode> a, Map<String, JsonNode> b, String... campos) {
        for (String c : campos) if (!Objects.equals(a.get(c), b.get(c))) return false;
        return true;
    }

    private static ObjectNode banderaDeClase(UUID id, String bandera, String campo, JsonNode valor) {
        return cmd("SET_ER_FLAG", p().put("targetType", "CLASS").put("targetId", id.toString())
                .put("flag", bandera).set(campo, valor));
    }

    private static ObjectNode p() { return JSON.objectNode(); }

    private static ObjectNode cmd(String tipo, ObjectNode payload) {
        ObjectNode c = JSON.objectNode();
        c.put("type", tipo);
        c.set("payload", payload);
        return c;
    }
}
