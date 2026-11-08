package com.uagrm.colabmodel.ai;

import com.fasterxml.jackson.databind.JsonNode;
import java.text.Normalizer;
import java.util.*;

/** Comprueba obligaciones observables del requisito, sin crear datos ni enlaces. */
final class RevisorCoberturaPlan {
    private RevisorCoberturaPlan() { }

    static List<String> revisar(RespuestaIA respuesta, String requisito) {
        var comando = respuesta.comandos().stream().filter(c -> EsquemaPlanUml.NOMBRE.equals(c.tipo()))
                .findFirst().orElse(null);
        if (comando == null) return List.of();
        JsonNode plan = comando.parametros();
        String pedido = normal(requisito);
        List<String> errores = new ArrayList<>();
        Map<String, JsonNode> clases = new LinkedHashMap<>();
        Map<String, Set<String>> vecinas = new HashMap<>();
        for (JsonNode c : plan.path("classes")) clases.put(c.path("name").asText(), c);
        for (JsonNode r : plan.path("relations")) {
            if (Set.of("GENERALIZATION", "DEPENDENCY").contains(r.path("kind").asText())) continue;
            String s = r.path("source").asText(), t = r.path("target").asText();
            vecinas.computeIfAbsent(s, k -> new HashSet<>()).add(t);
            vecinas.computeIfAbsent(t, k -> new HashSet<>()).add(s);
        }
        if (pedido.contains("inventario") || pedido.contains("stock")) {
            boolean cantidadDisponible = clases.values().stream()
                    .anyMatch(c -> tieneAtributo(c, "stock", "existencia", "cantidaddisponible", "unidadesdisponibles", "saldoinventario"));
            if (!cantidadDisponible) errores.add("El requisito de inventario no esta cubierto: falta un dato de stock o cantidad disponible, "
                    + "en el producto o en un inventario asociado; no es obligatorio crear una clase separada.");
        }
        if (pedido.contains("creditos") && pedido.contains("en cada plan")) {
            boolean contextual = clases.entrySet().stream().anyMatch(e -> tieneAtributo(e.getValue(), "creditos")
                    && vecinas.getOrDefault(e.getKey(), Set.of()).stream().map(RevisorCoberturaPlan::normal)
                    .anyMatch(v -> v.equals("plan") || v.startsWith("planacademico"))
                    && vecinas.getOrDefault(e.getKey(), Set.of()).stream().map(RevisorCoberturaPlan::normal)
                    .anyMatch(v -> v.startsWith("asignatura")));
            if (!contextual) errores.add("Los creditos requeridos por cada plan no tienen contexto: conserva esos datos "
                    + "en la participacion de Asignatura en Plan, no como propiedad global del catalogo de asignaturas.");
        }
        if (pedido.contains("prerrequis")) {
            boolean cubierto = false;
            for (JsonNode r : plan.path("relations")) {
                String s = normal(r.path("source").asText()), t = normal(r.path("target").asText());
                if (s.equals(t) && (s.contains("asignatura") || s.contains("materia"))
                        && !Set.of("GENERALIZATION", "DEPENDENCY").contains(r.path("kind").asText())) cubierto = true;
            }
            for (String c : clases.keySet()) if (normal(c).contains("prerrequisito")
                    && ValidadorPlanFinal.asociativaSinDatosValida(plan, c)) cubierto = true;
            if (!cubierto) errores.add("Los prerrequisitos solicitados estan ausentes o incompletos: representa la dependencia "
                    + "recursiva entre asignaturas, directamente o con una asociativa que distinga asignatura actual y requerida.");
        }
        if (pedido.contains("padre") && pedido.contains("madre")) {
            List<String> animales = clases.keySet().stream().filter(c -> normal(c).equals("animal")).toList();
            if (animales.size() == 1) {
                String animal = animales.getFirst(); boolean padre = false, madre = false;
                for (JsonNode r : plan.path("relations")) {
                    if (!animal.equals(r.path("source").asText()) && !animal.equals(r.path("target").asText())) continue;
                    String roles = normal(r.path("sourceRole").asText() + " " + r.path("targetRole").asText()
                            + " " + r.path("name").asText());
                    padre |= roles.contains("padre"); madre |= roles.contains("madre");
                }
                if (!padre || !madre) errores.add("El parentesco solicitado no esta cubierto: faltan los roles padre y/o madre "
                        + "vinculados a Animal; no basta con crear animales sin su genealogia.");
            }
        }
        String pedidoTemporal = pedido.replace("sin historial", "").replace("no requiere historial", "")
                .replace("no guardar historial", "");
        boolean historia = pedidoTemporal.contains("histori") || pedidoTemporal.contains("trazabilidad")
                || pedido.contains("fecha") || pedido.contains("auditoria");
        for (var entrada : clases.entrySet()) {
            String nombre = entrada.getKey(), n = normal(nombre);
            JsonNode clase = entrada.getValue();
            Set<String> enlaces = vecinas.getOrDefault(nombre, Set.of());
            if (n.startsWith("asignacion") && pedido.contains("docente") && pedido.contains("plan")) {
                boolean contextoMateria = enlaces.stream().map(RevisorCoberturaPlan::normal)
                        .anyMatch(v -> v.startsWith("asignatura")) || enlaces.stream().anyMatch(v ->
                        vecinas.getOrDefault(v, Set.of()).stream().map(RevisorCoberturaPlan::normal)
                                .anyMatch(x -> x.startsWith("asignatura")));
                boolean contextoPlan = enlaces.stream().anyMatch(v -> normal(v).equals("plan") || normal(v).startsWith("planacademico")
                        || vecinas.getOrDefault(v, Set.of()).stream().map(RevisorCoberturaPlan::normal)
                                .anyMatch(x -> x.equals("plan") || x.startsWith("planacademico")));
                if (!contextoMateria || !contextoPlan) errores.add(nombre
                        + " no identifica la asignatura en su contexto de plan; no basta con vincular docente y periodo.");
            }
            boolean cabeceraItinerario = n.equals("itinerario") || (n.contains("itinerario")
                    && clases.keySet().stream().map(RevisorCoberturaPlan::normal).noneMatch(v -> v.equals("itinerario")));
            if (cabeceraItinerario && clases.keySet().stream().map(RevisorCoberturaPlan::normal)
                    .anyMatch(v -> v.startsWith("paquete") || v.startsWith("salida"))
                    && enlaces.stream().map(RevisorCoberturaPlan::normal)
                    .noneMatch(v -> v.startsWith("paquete") || v.startsWith("salida")))
                errores.add(nombre + " no identifica el paquete o la salida al que pertenece; "
                        + "compartir destinos no establece la propiedad del itinerario.");
            if (n.startsWith("salida") && pedido.contains("cupo") && !tieneAtributo(clase, "cupo", "capacidad"))
                errores.add(nombre + " no registra cupos o capacidad para las salidas solicitadas.");
            if (n.startsWith("reserva") && pedido.contains("participantes") && pedido.contains("viajero")) {
                boolean participacion = false;
                for (JsonNode r : plan.path("relations")) {
                    boolean sale = nombre.equals(r.path("source").asText()), llega = nombre.equals(r.path("target").asText());
                    if (!sale && !llega) continue;
                    String otro = r.path(sale ? "target" : "source").asText();
                    // Participante como persona que viaja equivale a Viajero.
                    if ((normal(otro).startsWith("viajero") || normal(otro).equals("participante"))
                            && r.path(sale ? "targetMultiplicity" : "sourceMultiplicity")
                            .asText().contains("*")) participacion = true;
                    if ((normal(otro).contains("particip") || normal(otro).contains("detalle"))
                            && vecinas.getOrDefault(otro, Set.of()).stream().map(RevisorCoberturaPlan::normal)
                            .anyMatch(v -> v.startsWith("viajero"))) participacion = true;
                }
                if (!participacion) errores.add(nombre + " no permite representar multiples viajeros participantes; "
                        + "distingue al titular de la reserva de la lista de participantes solicitada.");
            }
            for (JsonNode a : clase.path("attributes")) {
                if (a.path("pk").asBoolean()) continue;
                String campo = a.path("name").asText();
                String base = campo.startsWith("id") && campo.length() > 2 && Character.isUpperCase(campo.charAt(2))
                        ? campo.substring(2) : campo.endsWith("Id") ? campo.substring(0, campo.length() - 2) : "";
                for (String destino : clases.keySet()) {
                    if (destino.equals(nombre)) continue;
                    boolean referencia = normal(a.path("dataType").asText()).equals(normal(destino))
                            || (!base.isBlank() && normal(destino).equals(normal(base)));
                    if (referencia && !enlaces.contains(destino)) errores.add(nombre + "." + campo
                            + " representa una referencia a " + destino + " sin asociacion UML; conserva el vinculo como relacion.");
                }
            }
            boolean evento = n.contains("movimiento") || n.contains("transferencia") || n.equals("venta")
                    || n.startsWith("pago") || n.startsWith("reserva") || n.equals("prestamo") || n.startsWith("cuota") || n.startsWith("salida")
                    || n.contains("controlsanitario") || n.contains("aplicacionproducto")
                    || (n.startsWith("tratamiento") && enlaces.stream().map(RevisorCoberturaPlan::normal)
                    .anyMatch(v -> v.equals("animal") || v.equals("paciente")));
            if (historia && evento && !tieneCampoTemporal(clase)) errores.add(nombre
                    + " registra eventos pero no tiene fecha u otro dato temporal; el requisito exige historial o trazabilidad.");
            if ((n.startsWith("pago") && pedido.contains("pago"))
                    || (n.contains("transferencia") && pedido.contains("transferencia"))) {
                if (!tieneAtributo(clase, "monto", "importe", "valor")) errores.add(nombre
                        + " no registra el importe de la operacion solicitada.");
            }
            if (n.contains("detalleventa") && pedido.contains("cantidad") && !tieneAtributo(clase, "cantidad"))
                errores.add(nombre + " no registra la cantidad vendida requerida.");
            if (n.contains("detalleventa") && pedido.contains("precio") && pedido.contains("historic")
                    && !tieneAtributo(clase, "precio", "importeunitario")) errores.add(nombre
                    + " no conserva el precio historico de la venta; el precio actual del catalogo no lo reemplaza.");
            if ((n.startsWith("tratamiento") || n.contains("aplicacionproducto")) && pedido.contains("dosis")
                    && !tieneAtributo(clase, "dosis")) errores.add(nombre + " no registra la dosis requerida.");
            if (n.contains("aplicacionproducto") && pedido.contains("producto")
                    && enlaces.stream().map(RevisorCoberturaPlan::normal).noneMatch(v -> v.startsWith("producto")))
                errores.add(nombre + " no identifica el producto aplicado; la fecha y dosis no sustituyen el catalogo "
                        + "o la referencia al producto sanitario solicitado.");
            boolean movimientoDeCuenta = n.contains("movimiento") && enlaces.stream()
                    .map(RevisorCoberturaPlan::normal).anyMatch(v -> v.startsWith("cuenta"))
                    && clases.keySet().stream().map(RevisorCoberturaPlan::normal).anyMatch(v -> v.contains("transferencia"));
            if (historia && !movimientoDeCuenta && (n.contains("movimiento") || n.contains("traslado") || n.contains("transferencia"))
                    && ((pedido.contains("origen") && pedido.contains("destino"))
                    || pedido.contains("entre lotes") || pedido.contains("entre establecimientos"))) {
                boolean origen = false, destino = false;
                for (JsonNode r : plan.path("relations")) {
                    boolean sale = nombre.equals(r.path("source").asText()), llega = nombre.equals(r.path("target").asText());
                    if (!sale && !llega) continue;
                    String rol = normal(r.path(sale ? "targetRole" : "sourceRole").asText());
                    if (rol.isBlank()) rol = normal(r.path("name").asText());
                    // Se examina el rol del objeto, no el del evento. Una
                    // unica relacion no puede representar ambos extremos.
                    if (rol.contains("origen") && !rol.contains("destino")) origen = true;
                    if (rol.contains("destino") && !rol.contains("origen")) destino = true;
                }
                if (!origen || !destino)
                    errores.add(nombre + " no distingue origen y destino mediante roles, necesarios para los movimientos solicitados.");
            }
            if (n.startsWith("titular") && clases.keySet().stream().map(RevisorCoberturaPlan::normal)
                    .anyMatch(c -> c.equals("cuenta"))) {
                List<String> actores = clases.keySet().stream().filter(c -> Set.of("cliente", "usuario", "persona")
                        .contains(normal(c))).toList();
                if (!actores.isEmpty() && actores.stream().noneMatch(enlaces::contains)) errores.add(nombre
                        + " no identifica al titular mediante una asociacion con " + actores + ".");
            }
            if (n.equals("prestamo") && pedido.contains("banc")) {
                List<String> clientes = clases.keySet().stream().filter(c -> normal(c).equals("cliente")).toList();
                // Tambien admite un participante intermedio vinculado al cliente.
                boolean prestatario = clientes.stream().anyMatch(enlaces::contains)
                        || enlaces.stream().filter(v -> normal(v).contains("prestatario") || normal(v).contains("titular"))
                        .anyMatch(v -> clientes.stream().anyMatch(vecinas.getOrDefault(v, Set.of())::contains));
                if (!clientes.isEmpty() && !prestatario) errores.add(nombre
                        + " no identifica al cliente prestatario, directamente o mediante una participacion explicita.");
            }
            if (n.startsWith("garantia") && pedido.contains("garantia")
                    && (pedido.contains("vendid") || pedido.contains("compra concreta"))) {
                boolean compra = enlaces.stream().map(RevisorCoberturaPlan::normal)
                        .anyMatch(v -> v.contains("detalleventa") || v.contains("unidadvendida") || v.contains("ejemplarvendido"));
                if (!compra) errores.add(nombre + " no identifica la unidad o el detalle vendido que cubre la garantia solicitada; "
                        + "la asociacion al catalogo no identifica una compra concreta.");
            }
        }
        if (pedido.contains("reutiliz") || pedido.contains("compart")) {
            for (JsonNode r : plan.path("relations")) {
                if (Set.of("GENERALIZATION", "DEPENDENCY").contains(r.path("kind").asText())) continue;
                String s = normal(r.path("source").asText()), t = normal(r.path("target").asText());
                boolean paqueteOrigen = s.startsWith("paquete") && (t.equals("destino") || t.startsWith("servicioturistico"));
                boolean paqueteDestino = t.startsWith("paquete") && (s.equals("destino") || s.startsWith("servicioturistico"));
                if ((paqueteOrigen || paqueteDestino) && !r.path(paqueteOrigen ? "sourceMultiplicity" : "targetMultiplicity")
                        .asText().contains("*")) errores.add("El catalogo reutilizable " + (paqueteOrigen ? r.path("target").asText()
                        : r.path("source").asText()) + " queda limitado a un paquete; revisa la multiplicidad junto a Paquete.");
            }
        }
        errores.addAll(RevisorHerenciaPlan.revisar(plan, requisito));
        return List.copyOf(new LinkedHashSet<>(errores));
    }

    private static boolean tieneCampoTemporal(JsonNode clase) {
        for (JsonNode a : clase.path("attributes")) {
            if (a.path("pk").asBoolean()) continue;
            String n = normal(a.path("name").asText()), tipo = a.path("dataType").asText();
            if (tipo.equals("date") || tipo.equals("datetime") || n.contains("fecha") || n.contains("timestamp")
                    || n.equals("inicio") || n.equals("fin") || n.equals("instante") || n.equals("marcatiempo")) return true;
        }
        return false;
    }

    private static boolean tieneAtributo(JsonNode clase, String... patrones) {
        for (JsonNode a : clase.path("attributes")) {
            if (a.path("pk").asBoolean()) continue;
            String nombre = normal(a.path("name").asText());
            for (String patron : patrones) if (nombre.contains(patron)) return true;
        }
        return false;
    }

    private static String normal(String s) {
        return Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT).replace("_", "");
    }
}
