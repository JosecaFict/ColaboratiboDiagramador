package com.uagrm.colabmodel.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.uagrm.colabmodel.modelcore.command.Lote;
import java.util.*;

/** Hallazgos estructurales verificables, sin inventar asociaciones de negocio. */
final class ValidadorPlanFinal {
    private ValidadorPlanFinal() { }

    static List<String> revisar(RespuestaIA respuesta) {
        return revisar(respuesta, Set.of());
    }

    static List<String> revisar(RespuestaIA respuesta, Set<String> existentes) {
        var comando = respuesta.comandos().stream()
                .filter(c -> EsquemaPlanUml.NOMBRE.equals(c.tipo())).findFirst().orElse(null);
        if (comando == null) return List.of(); // Compatibilidad con comandos atomicos.
        JsonNode plan = comando.parametros();
        List<String> errores = new ArrayList<>();
        Map<String, Set<String>> grafo = new LinkedHashMap<>();
        Set<String> propuestas = new HashSet<>();
        int cambios = plan.path("classes").size() + plan.path("relations").size();
        for (JsonNode clase : plan.path("classes")) {
            String nombre = clase.path("name").asText();
            propuestas.add(nombre);
            if (grafo.putIfAbsent(nombre, new LinkedHashSet<>()) != null)
                errores.add("Clase duplicada: " + nombre);
            cambios += clase.path("attributes").size();
            boolean dato = false;
            for (JsonNode a : clase.path("attributes")) {
                String n = a.path("name").asText();
                if (!a.path("pk").asBoolean() && !n.equalsIgnoreCase("id") && !n.matches("^id[_A-Z].*")) dato = true;
            }
            if (!dato && !asociativaSinDatosValida(plan, nombre))
                errores.add(nombre + " solo tiene identidad; conserva al menos un dato de negocio o justifica su funcion asociativa con relaciones y roles completos.");
        }
        Set<String> asociaciones = new HashSet<>();
        Map<String, Integer> conexiones = new HashMap<>();
        for (JsonNode r : plan.path("relations")) {
            String s = r.path("source").asText(), t = r.path("target").asText();
            if (existentes.contains(s)) grafo.computeIfAbsent(s, k -> new LinkedHashSet<>());
            if (existentes.contains(t)) grafo.computeIfAbsent(t, k -> new LinkedHashSet<>());
            if (!grafo.containsKey(s) || !grafo.containsKey(t)) {
                errores.add("Relacion con extremo inexistente: " + s + " - " + t);
                continue;
            }
            grafo.get(s).add(t); grafo.get(t).add(s);
            boolean asociacion = !Set.of("GENERALIZATION", "DEPENDENCY").contains(r.path("kind").asText());
            boolean directa = !asociacion || s.compareTo(t) <= 0;
            String rolS = r.path("sourceRole").asText(), rolT = r.path("targetRole").asText();
            String roles = (rolS + " " + rolT + " " + r.path("name").asText()).toLowerCase(Locale.ROOT);
            boolean direccional = roles.contains("origen") || roles.contains("destino");
            boolean eventoS = esTraslado(s), eventoT = esTraslado(t);
            if (asociacion && direccional && (eventoS ^ eventoT)) {
                String evento = r.path(eventoS ? "sourceMultiplicity" : "targetMultiplicity").asText();
                String extremo = r.path(eventoS ? "targetMultiplicity" : "sourceMultiplicity").asText();
                if (!(evento.equals("0..*") || evento.equals("1..*") || evento.equals("*"))
                        || !(extremo.equals("1") || extremo.equals("0..1")))
                    errores.add("Revisa " + s + " - " + t + " por rol " + roles.trim()
                            + ": un extremo puede intervenir en multiples movimientos historicos; "
                            + "cada movimiento identifica un extremo por rol.");
            }
            String clave = (directa ? s + "|" + t + "|" + rolS + "|" + rolT
                    : t + "|" + s + "|" + rolT + "|" + rolS) + "|" + r.path("kind").asText();
            if (asociaciones.add(clave)) {
                if (asociacion) { conexiones.merge(s, 1, Integer::sum); conexiones.merge(t, 1, Integer::sum); }
            } else errores.add("Relaciones repetidas entre " + s + " y " + t
                    + ": elimina el duplicado o especifica roles diferentes, como origen/destino.");
        }
        for (var entrada : grafo.entrySet()) {
            if (!propuestas.contains(entrada.getKey())) continue;
            String n = entrada.getKey().toLowerCase(Locale.ROOT);
            if (grafo.size() > 1 && entrada.getValue().isEmpty())
                errores.add("Clase desconectada: " + entrada.getKey());
            else if ((n.contains("detalle") || n.startsWith("titular") || n.contains("prerrequisito")
                    || n.contains("asignacion") || n.contains("membresia") || n.contains("aplicacionproducto")) && conexiones.getOrDefault(entrada.getKey(), 0) < 2)
                errores.add("La clase asociativa " + entrada.getKey() + " no conecta sus dos extremos o roles.");
            // Tener dos enlaces no basta: DetalleItinerario debe pertenecer a
            // Itinerario, no solamente conectar Actividad con Destino.
            if (n.startsWith("detalle") && n.length() > 7) {
                String cabecera = n.substring(7);
                List<String> candidatas = grafo.keySet().stream()
                        .filter(c -> !c.equals(entrada.getKey()) && c.toLowerCase(Locale.ROOT).startsWith(cabecera))
                        .filter(c -> !c.toLowerCase(Locale.ROOT).startsWith("detalle")).toList();
                if (candidatas.size() == 1 && !entrada.getValue().contains(candidatas.getFirst()))
                    errores.add(entrada.getKey() + " no identifica su cabecera " + candidatas.getFirst()
                            + "; conecta cada detalle con el contenedor al que pertenece.");
            }
            if (n.equals("aplicacionproducto")) {
                List<String> productos = grafo.keySet().stream()
                        .filter(c -> c.toLowerCase(Locale.ROOT).startsWith("producto")).toList();
                if (productos.size() == 1 && !entrada.getValue().contains(productos.getFirst()))
                    errores.add(entrada.getKey() + " no identifica el producto aplicado " + productos.getFirst());
            }
            for (String rol : List.of("padre", "madre")) {
                if (n.startsWith(rol)) {
                    String base = n.substring(rol.length());
                    if (!base.isEmpty() && grafo.keySet().stream().anyMatch(c -> c.equalsIgnoreCase(base)))
                        errores.add(entrada.getKey() + " duplica una entidad existente como rol familiar; "
                                + "usa una asociacion recursiva con rol " + rol + " y descendientes.");
                }
            }
        }
        Set<String> visitadas = new HashSet<>();
        List<Set<String>> componentes = new ArrayList<>();
        for (String nombre : grafo.keySet()) {
            if (visitadas.contains(nombre)) continue;
            Set<String> componente = new LinkedHashSet<>();
            Deque<String> pendientes = new ArrayDeque<>(); pendientes.add(nombre);
            while (!pendientes.isEmpty()) {
                String actual = pendientes.removeFirst();
                if (!visitadas.add(actual)) continue;
                componente.add(actual); pendientes.addAll(grafo.get(actual));
            }
            componentes.add(componente);
        }
        if (componentes.size() > 1) errores.add("El modelo contiene grupos sin conexion: " + componentes
                + ". Revisa las asociaciones requeridas; no inventes enlaces solo para conectar el grafo.");
        if (cambios > Lote.TOPE_TEXTO_O_VOZ) errores.add("El plan final requiere " + cambios
                + " cambios y el limite es " + Lote.TOPE_TEXTO_O_VOZ
                + "; compacta el diseno sin eliminar requisitos esenciales.");
        return List.copyOf(errores);
    }

    private static boolean esTraslado(String nombre) {
        String n = nombre.toLowerCase(Locale.ROOT);
        return n.contains("transferencia") || n.contains("movimiento") || n.contains("traslado");
    }

    static boolean asociativaSinDatosValida(JsonNode plan, String nombre) {
        String n = nombre.toLowerCase(Locale.ROOT);
        if (!(n.contains("detalle") || n.contains("prerrequisito") || n.startsWith("titular")
                || n.contains("asignacion") || n.contains("membresia") || n.contains("inscripcion"))) return false;
        Set<String> extremos = new HashSet<>();
        for (JsonNode r : plan.path("relations")) {
            if (Set.of("GENERALIZATION", "DEPENDENCY").contains(r.path("kind").asText())) continue;
            String s = r.path("source").asText(), t = r.path("target").asText();
            if (nombre.equals(s) && !nombre.equals(t)) extremos.add(t + "|" + r.path("targetRole").asText()
                    + "|" + r.path("sourceRole").asText());
            else if (nombre.equals(t) && !nombre.equals(s)) extremos.add(s + "|" + r.path("sourceRole").asText()
                    + "|" + r.path("targetRole").asText());
        }
        return extremos.size() >= 2;
    }
}
