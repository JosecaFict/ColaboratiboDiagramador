package com.uagrm.colabmodel.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.text.Normalizer;
import java.util.*;

/**
 * Correcciones mecanicas de hallazgos que el modelo local no logra reparar
 * (pruebas de ventas, banco, academico y turismo del 2026-11-08). Cada regla
 * tiene una unica respuesta correcta; lo ambiguo queda para el revisor y para
 * RECOMENDAR, sin inventar enlaces solo para conectar el grafo.
 */
final class CorrectorEstructuralPlan {
    private CorrectorEstructuralPlan() { }

    /** Subtipos de un concepto base que el modelo suele dejar sueltos. */
    private static final Map<String, Set<String>> SUBTIPOS = Map.of(
            "producto", Set.of("computadora", "laptop", "portatil", "notebook", "componente", "accesorio",
                    "periferico", "dispositivo", "repuesto", "celular", "telefono", "impresora", "monitor", "tablet"),
            "persona", Set.of("cliente", "empleado", "docente", "estudiante", "paciente", "medico", "proveedor"));

    private static final Set<String> ASOCIATIVAS = Set.of("titular", "detalle", "asignacion", "inscripcion",
            "membresia", "participacion");

    private static final List<String> REGISTROS = List.of("reserva", "venta", "compra", "pedido", "pago",
            "prestamo", "movimiento", "transferencia", "cita", "inscripcion", "factura",
            // Ganaderia 2026-11-08: ControlSanitario [1] - [1] Animal dejaba un unico control por animal.
            "control", "tratamiento", "aplicacion", "vacunacion", "diagnostico", "consulta", "evaluacion");

    static int corregir(ObjectNode plan, String requisito) {
        if (!(plan.path("classes") instanceof ArrayNode) || !(plan.path("relations") instanceof ArrayNode)) return 0;
        String pedido = normal(requisito);
        return limpiarAuditoria(plan)
                + generalizarSubtiposSueltos(plan, pedido)
                + retirarAtributosHeredados(plan)
                + conectarDetallesSinCabecera(plan)
                + corregirUnoAUnoDeRegistros(plan)
                + registrarCuposEnSalida(plan, pedido)
                + corregirPersonasDeReserva(plan, pedido)
                + permitirVariosPagos(plan, pedido)
                + retirarMuchosAMuchosCubiertos(plan)
                + retirarGarantiaAlCatalogo(plan)
                + retirarRecursivaCubierta(plan)
                + completarImportes(plan, pedido)
                + conectarPrestatario(plan, pedido)
                + conectarAsociativaConSuNombre(plan)
                + unaSolaIdentidad(plan)
                + ubicacionActualDeLoMovido(plan)
                + alinearCicloDeVidaConAuditoria(plan)
                + retirarAfirmacionesFalsas(plan);
    }

    /** Computadora y Componente sin relaciones junto a Producto: son subclases. */
    static int generalizarSubtiposSueltos(ObjectNode plan, String pedido) {
        ArrayNode relaciones = (ArrayNode) plan.path("relations");
        int cambios = 0;
        List<String> generalizadas = new ArrayList<>();
        String baseUsada = null;
        for (var entrada : SUBTIPOS.entrySet()) {
            String base = clase(plan, n -> n.equals(entrada.getKey()));
            if (base == null || !pedido.contains(entrada.getKey()) || grado(plan, base) == 0) continue;
            for (JsonNode c : plan.path("classes")) {
                String nombre = c.path("name").asText();
                if (!entrada.getValue().contains(normal(nombre)) || grado(plan, nombre) > 0) continue;
                relaciones.addObject().put("source", nombre).put("target", base).put("kind", "GENERALIZATION");
                ArrayNode propios = (ArrayNode) c.path("attributes");
                for (int i = propios.size() - 1; i >= 0; i--) {
                    JsonNode a = propios.get(i);
                    if (!a.path("pk").asBoolean() && tieneAtributo(plan, base, a.path("name").asText())) propios.remove(i);
                }
                generalizadas.add(nombre); baseUsada = base; cambios++;
            }
        }
        if (!generalizadas.isEmpty()) {
            quitarAuditoria(plan, generalizadas);
            auditar(plan, "El backend modelo " + String.join(" y ", generalizadas) + " como subclase(s) de " + baseUsada
                    + " (GENERALIZATION); heredan sus datos comunes y participan en ventas, inventario y garantias.");
        }
        return cambios;
    }

    /**
     * Turismo 2026-11-08: el modelo dejo en audit "]}**Nota:** El campo `classes_count`
     * en el JSON anterior...". Restos de formato o comentarios sobre el JSON no son auditoria.
     */
    static int limpiarAuditoria(ObjectNode plan) {
        if (!(plan.path("audit") instanceof ArrayNode audit)) return 0;
        int cambios = 0;
        for (int i = audit.size() - 1; i >= 0; i--) {
            String original = audit.get(i).asText(), punto = original.replace("**", "").trim(), n = normal(punto);
            boolean basura = punto.isEmpty() || !Character.isLetter(punto.codePointAt(0))
                    || punto.contains("]}") || punto.contains("{\"")
                    || n.contains("json") || n.contains("esquema solicitado") || n.contains("classescount");
            if (basura) { audit.remove(i); cambios++; }
            else if (!punto.equals(original)) audit.set(i, audit.textNode(punto));
        }
        return cambios;
    }

    /** Componente hereda nombre de Producto: repetirlo en la subclase lo duplica. */
    static int retirarAtributosHeredados(ObjectNode plan) {
        int cambios = 0;
        for (JsonNode r : plan.path("relations")) {
            if (!"GENERALIZATION".equals(r.path("kind").asText())) continue;
            String hija = r.path("source").asText(), padre = r.path("target").asText();
            if (hija.equals(padre) || clase(plan, n -> n.equals(normal(hija))) == null
                    || clase(plan, n -> n.equals(normal(padre))) == null) continue;
            ArrayNode propios = (ArrayNode) objeto(plan, hija).path("attributes");
            for (int i = propios.size() - 1; i >= 0; i--) {
                JsonNode a = propios.get(i);
                if (!a.path("pk").asBoolean() && tieneAtributo(plan, padre, a.path("name").asText())) {
                    propios.remove(i);
                    cambios++;
                }
            }
        }
        return cambios;
    }

    /** DetalleReserva sin Reserva: cada detalle pertenece a su cabecera. */
    static int conectarDetallesSinCabecera(ObjectNode plan) {
        int cambios = 0;
        for (JsonNode c : plan.path("classes")) {
            String detalle = c.path("name").asText(), n = normal(detalle);
            if (!n.startsWith("detalle") || n.length() <= 7) continue;
            String sufijo = n.substring(7);
            List<String> candidatas = new ArrayList<>();
            for (JsonNode o : plan.path("classes")) {
                String otro = normal(o.path("name").asText());
                if (!otro.equals(n) && !otro.startsWith("detalle") && otro.startsWith(sufijo))
                    candidatas.add(o.path("name").asText());
            }
            if (candidatas.size() != 1 || relacionados(plan, detalle, candidatas.getFirst())) continue;
            ((ArrayNode) plan.path("relations")).addObject().put("source", candidatas.getFirst()).put("target", detalle)
                    .put("kind", "ASSOCIATION").put("name", "contieneDetalles")
                    .put("sourceMultiplicity", "1").put("targetMultiplicity", "1..*");
            auditar(plan, "El backend conecto " + detalle + " con su cabecera " + candidatas.getFirst() + ".");
            cambios++;
        }
        return cambios;
    }

    /** Reserva [1] - [1] Salida: una salida acumula muchas reservas historicas. */
    static int corregirUnoAUnoDeRegistros(ObjectNode plan) {
        int cambios = 0;
        for (JsonNode nodo : plan.path("relations")) {
            ObjectNode r = (ObjectNode) nodo;
            String s = r.path("source").asText(), t = r.path("target").asText();
            if (!"ASSOCIATION".equals(r.path("kind").asText("ASSOCIATION")) || s.equals(t)) continue;
            if (!"1".equals(r.path("sourceMultiplicity").asText()) || !"1".equals(r.path("targetMultiplicity").asText())) continue;
            boolean registroS = esRegistro(s), registroT = esRegistro(t);
            if (registroS == registroT) continue;
            r.put(registroS ? "sourceMultiplicity" : "targetMultiplicity", "0..*");
            cambios++;
        }
        return cambios;
    }

    /** "salidas con cupos": el cupo es un dato de la salida, no un catalogo aparte. */
    static int registrarCuposEnSalida(ObjectNode plan, String pedido) {
        if (!pedido.contains("cupo")) return 0;
        String salida = clase(plan, n -> n.startsWith("salida"));
        if (salida == null || tieneAtributoParecido(plan, salida, "cupo", "capacidad")) return 0;
        int cambios = 0;
        String cupo = clase(plan, n -> n.equals("cupo") || n.equals("cupos"));
        if (cupo != null && vecinas(plan, cupo).equals(Set.of(salida))) {
            retirarClase(plan, cupo);
            quitarAuditoria(plan, List.of(cupo));
            cambios++;
        }
        objeto(plan, salida).withArray("attributes").addObject().put("name", "cupoMaximo").put("dataType", "int")
                .put("required", true).put("unique", false).put("pk", false);
        auditar(plan, "El backend registro el cupo como dato de " + salida + " (cupoMaximo).");
        return cambios + 1;
    }

    /**
     * Reserva - Participante: una reserva lleva varios viajeros. TitularReserva
     * es quien reserva: una persona con muchas reservas, no una asociativa.
     */
    static int corregirPersonasDeReserva(ObjectNode plan, String pedido) {
        String reserva = clase(plan, n -> n.equals("reserva"));
        if (reserva == null) return 0;
        // Con una clase Viajero aparte, Participante es la participacion (0..* por reserva), no la persona.
        boolean participanteEsPersona = clase(plan, n -> n.startsWith("viajero")) == null;
        boolean sinParticipacion = clase(plan, n -> n.startsWith("participante") || n.startsWith("participacion")) == null;
        int cambios = 0;
        for (JsonNode nodo : plan.path("relations")) {
            ObjectNode r = (ObjectNode) nodo;
            if (!"ASSOCIATION".equals(r.path("kind").asText("ASSOCIATION"))) continue;
            String s = r.path("source").asText(), t = r.path("target").asText();
            if (!s.equals(reserva) && !t.equals(reserva)) continue;
            String otro = s.equals(reserva) ? t : s, n = normal(otro);
            String juntoReserva = s.equals(reserva) ? "sourceMultiplicity" : "targetMultiplicity";
            String juntoOtro = s.equals(reserva) ? "targetMultiplicity" : "sourceMultiplicity";
            if (n.equals("participante") && participanteEsPersona && pedido.contains("participantes")) {
                cambios += poner(r, juntoReserva, "1") + poner(r, juntoOtro, "1..*");
            } else if (n.startsWith("viajero") && sinParticipacion && pedido.contains("participantes")) {
                // Turismo 2026-11-08: Reserva [0..*] - [1] Viajero dejaba un solo viajero por reserva.
                // Sin clase de participacion, la relacion directa es N:M: varios viajeros por reserva.
                cambios += poner(r, juntoReserva, "0..*") + poner(r, juntoOtro, "1..*");
            } else if (n.startsWith("titular") && vecinas(plan, otro).stream().noneMatch(v -> normal(v).startsWith("cuenta"))) {
                cambios += poner(r, juntoReserva, "0..*") + poner(r, juntoOtro, "1");
            }
        }
        return cambios;
    }

    /** "historial de pagos": Venta [1] - [0..1] Pago impide registrar mas de un pago. */
    static int permitirVariosPagos(ObjectNode plan, String pedido) {
        if (!pedido.contains("pagos")) return 0;
        int cambios = 0;
        for (JsonNode nodo : plan.path("relations")) {
            ObjectNode r = (ObjectNode) nodo;
            if (!"ASSOCIATION".equals(r.path("kind").asText("ASSOCIATION"))) continue;
            String s = normal(r.path("source").asText()), t = normal(r.path("target").asText());
            if (s.startsWith("pago") == t.startsWith("pago")) continue;
            String juntoPago = s.startsWith("pago") ? "sourceMultiplicity" : "targetMultiplicity";
            String juntoDueno = s.startsWith("pago") ? "targetMultiplicity" : "sourceMultiplicity";
            String m = r.path(juntoPago).asText();
            if ((m.equals("1") || m.equals("0..1")) && "1".equals(r.path(juntoDueno).asText()))
                cambios += poner(r, juntoPago, "0..*");
        }
        return cambios;
    }

    /** Cliente [*] - [*] Cuenta junto a Titular, que ya une ambos: la directa sobra. */
    static int retirarMuchosAMuchosCubiertos(ObjectNode plan) {
        ArrayNode relaciones = (ArrayNode) plan.path("relations");
        int cambios = 0;
        for (int i = relaciones.size() - 1; i >= 0; i--) {
            JsonNode r = relaciones.get(i);
            String s = r.path("source").asText(), t = r.path("target").asText();
            if (!"ASSOCIATION".equals(r.path("kind").asText("ASSOCIATION")) || s.equals(t)) continue;
            if (!r.path("sourceMultiplicity").asText().contains("*") || !r.path("targetMultiplicity").asText().contains("*")) continue;
            // Aunque el modelo le ponga roles (titulares/cuentas), repite lo que ya dice la intermedia.
            boolean cubierta = false;
            for (JsonNode c : plan.path("classes")) {
                String intermedia = c.path("name").asText(), n = normal(intermedia);
                if (intermedia.equals(s) || intermedia.equals(t) || ASOCIATIVAS.stream().noneMatch(n::startsWith)) continue;
                if (relacionados(plan, intermedia, s) && relacionados(plan, intermedia, t)) cubierta = true;
            }
            if (!cubierta) continue;
            relaciones.remove(i);
            auditar(plan, "El backend retiro la asociacion directa " + s + "-" + t
                    + ": la clase intermedia ya representa esa relacion muchos a muchos.");
            cambios++;
        }
        return cambios;
    }

    /** Garantia con DetalleVenta y ademas con Producto: el producto ya llega por el detalle. */
    static int retirarGarantiaAlCatalogo(ObjectNode plan) {
        ArrayNode relaciones = (ArrayNode) plan.path("relations");
        int cambios = 0;
        for (JsonNode c : plan.path("classes")) {
            String garantia = c.path("name").asText();
            if (!normal(garantia).startsWith("garantia")) continue;
            Set<String> vecinas = vecinas(plan, garantia);
            String vendido = vecinas.stream().filter(v -> normal(v).contains("detalleventa")
                    || normal(v).contains("unidadvendida") || normal(v).contains("ejemplarvendido")).findFirst().orElse(null);
            if (vendido == null) continue;
            for (int i = relaciones.size() - 1; i >= 0; i--) {
                JsonNode r = relaciones.get(i);
                String s = r.path("source").asText(), t = r.path("target").asText();
                String otro = s.equals(garantia) ? t : t.equals(garantia) ? s : null;
                if (otro == null || otro.equals(vendido) || !normal(otro).startsWith("producto")
                        || !relacionados(plan, vendido, otro)) continue;
                relaciones.remove(i);
                cambios++;
            }
        }
        return cambios;
    }

    /**
     * Academico 2026-11-08: Asignatura-Asignatura sin roles junto a la clase Prerrequisito,
     * que ya une la asignatura requerida. Son dos versiones de la misma dependencia.
     */
    static int retirarRecursivaCubierta(ObjectNode plan) {
        ArrayNode relaciones = (ArrayNode) plan.path("relations");
        int cambios = 0;
        for (int i = relaciones.size() - 1; i >= 0; i--) {
            JsonNode r = relaciones.get(i);
            String c = r.path("source").asText();
            if (!c.equals(r.path("target").asText()) || !"ASSOCIATION".equals(r.path("kind").asText("ASSOCIATION"))) continue;
            // Solo si la clase de prerrequisitos ya une sus dos extremos: si no, la recursiva es la unica representacion.
            boolean cubierta = vecinas(plan, c).stream().anyMatch(v -> (normal(v).contains("prerrequisito")
                    || normal(v).contains("correlativ")) && ValidadorPlanFinal.asociativaSinDatosValida(plan, v));
            if (!cubierta) continue;
            relaciones.remove(i);
            auditar(plan, "El backend retiro la asociacion recursiva de " + c
                    + ": la clase de prerrequisitos ya representa esa dependencia.");
            cambios++;
        }
        return cambios;
    }

    /** Banco 2026-11-08: Transferencia sin importe. Un pago o una transferencia sin monto no registra la operacion. */
    static int completarImportes(ObjectNode plan, String pedido) {
        int cambios = 0;
        for (JsonNode c : plan.path("classes")) {
            String n = normal(c.path("name").asText());
            boolean operacion = (n.startsWith("pago") && pedido.contains("pago"))
                    || (n.contains("transferencia") && pedido.contains("transferencia"));
            if (!operacion || tieneAtributoParecido(plan, c.path("name").asText(), "monto", "importe", "valor")) continue;
            ((ArrayNode) c.path("attributes")).addObject().put("name", "importe").put("dataType", "decimal")
                    .put("required", true).put("unique", false).put("pk", false);
            auditar(plan, "El backend agrego el importe a " + c.path("name").asText() + ".");
            cambios++;
        }
        return cambios;
    }

    /** Banco 2026-11-08: "Cada prestamo identifica al cliente prestatario" y Prestamo quedo sin Cliente. */
    static int conectarPrestatario(ObjectNode plan, String pedido) {
        if (!pedido.contains("prestamo") || !pedido.contains("cliente")) return 0;
        String prestamo = clase(plan, n -> n.equals("prestamo")), cliente = clase(plan, n -> n.equals("cliente"));
        if (prestamo == null || cliente == null || relacionados(plan, prestamo, cliente)) return 0;
        boolean intermedio = vecinas(plan, prestamo).stream().anyMatch(v ->
                (normal(v).contains("prestatario") || normal(v).startsWith("titular")) && relacionados(plan, v, cliente));
        if (intermedio) return 0;
        ((ArrayNode) plan.path("relations")).addObject().put("source", prestamo).put("target", cliente)
                .put("kind", "ASSOCIATION").put("name", "prestatario").put("targetRole", "prestatario")
                .put("sourceMultiplicity", "0..*").put("targetMultiplicity", "1");
        auditar(plan, "El backend conecto " + prestamo + " con su cliente prestatario.");
        return 1;
    }

    /**
     * Academico 2026-11-08: AsignacionDocente unida a Periodo, Plan y Asignatura, pero no a Docente,
     * y al lado una Docente [*] - [*] PeriodoAcademico directa. El nombre de la asociativa dice a
     * quien une: la directa pasa a ser AsignacionDocente-Docente o, si no hay, se agrega.
     */
    static int conectarAsociativaConSuNombre(ObjectNode plan) {
        ArrayNode relaciones = (ArrayNode) plan.path("relations");
        int cambios = 0;
        for (JsonNode c : plan.path("classes")) {
            String asociativa = c.path("name").asText(), n = normal(asociativa);
            String prefijo = List.of("asignacion", "inscripcion", "membresia", "participacion").stream()
                    .filter(n::startsWith).findFirst().orElse(null);
            if (prefijo == null || n.length() <= prefijo.length()) continue;
            String nombrada = clase(plan, x -> x.equals(n.substring(prefijo.length())));
            if (nombrada == null || nombrada.equals(asociativa) || relacionados(plan, asociativa, nombrada)) continue;
            Set<String> vecinas = vecinas(plan, asociativa);
            ObjectNode directa = null;
            for (JsonNode r : relaciones) {
                String s = r.path("source").asText(), t = r.path("target").asText();
                String otro = s.equals(nombrada) ? t : t.equals(nombrada) ? s : null;
                if (otro != null && vecinas.contains(otro) && "ASSOCIATION".equals(r.path("kind").asText("ASSOCIATION"))
                        && r.path("sourceMultiplicity").asText().contains("*") && r.path("targetMultiplicity").asText().contains("*"))
                    directa = (ObjectNode) r;
            }
            if (directa != null) {
                boolean nombradaEsOrigen = directa.path("source").asText().equals(nombrada);
                directa.put(nombradaEsOrigen ? "target" : "source", asociativa)
                        .put(nombradaEsOrigen ? "targetMultiplicity" : "sourceMultiplicity", "0..*")
                        .put(nombradaEsOrigen ? "sourceMultiplicity" : "targetMultiplicity", "1");
                directa.remove("sourceRole");
                directa.remove("targetRole");
                directa.put("name", "relacionaCon" + nombrada);
            } else {
                relaciones.addObject().put("source", asociativa).put("target", nombrada).put("kind", "ASSOCIATION").put("name", "relacionaCon" + nombrada)
                        .put("sourceMultiplicity", "0..*").put("targetMultiplicity", "1");
            }
            auditar(plan, "El backend conecto " + asociativa + " con " + nombrada + ", la clase que su nombre indica.");
            cambios++;
        }
        return cambios;
    }

    /** Ganaderia 2026-11-08: ProductoSanitario con idProductoSanitario e id, las dos como clave primaria. */
    static int unaSolaIdentidad(ObjectNode plan) {
        int cambios = 0;
        for (JsonNode c : plan.path("classes")) {
            ArrayNode atributos = (ArrayNode) c.path("attributes");
            List<Integer> tecnicas = new ArrayList<>();
            for (int i = 0; i < atributos.size(); i++) {
                JsonNode a = atributos.get(i);
                String nombre = a.path("name").asText();
                if (a.path("pk").asBoolean() && (nombre.equalsIgnoreCase("id") || nombre.matches("^id[A-Z_].*"))) tecnicas.add(i);
            }
            if (tecnicas.size() < 2) continue;
            // Se conserva la que nombra a la clase (idProductoSanitario); si no hay, la primera.
            String propia = "id" + c.path("name").asText();
            int conservar = tecnicas.stream().filter(i -> atributos.get(i).path("name").asText().equalsIgnoreCase(propia))
                    .findFirst().orElse(tecnicas.getFirst());
            for (int k = tecnicas.size() - 1; k >= 0; k--) {
                if (tecnicas.get(k) == conservar) continue;
                atributos.remove((int) tecnicas.get(k));
                cambios++;
            }
        }
        return cambios;
    }

    /**
     * Ganaderia 2026-11-08: Animal [*] - [*] Lote junto a MovimientoAnimal, que ya guarda el
     * historial. La directa es la ubicacion actual: cada animal esta en un lote. El lugar es
     * la clase que el movimiento une dos veces (origen y destino).
     */
    static int ubicacionActualDeLoMovido(ObjectNode plan) {
        int cambios = 0;
        for (JsonNode c : plan.path("classes")) {
            String movimiento = c.path("name").asText(), n = normal(movimiento);
            if (!n.startsWith("movimiento") && !n.startsWith("traslado")) continue;
            Map<String, Integer> enlaces = new HashMap<>();
            for (JsonNode r : plan.path("relations")) {
                String s = r.path("source").asText(), t = r.path("target").asText();
                if (s.equals(movimiento) && !t.equals(movimiento)) enlaces.merge(t, 1, Integer::sum);
                else if (t.equals(movimiento) && !s.equals(movimiento)) enlaces.merge(s, 1, Integer::sum);
            }
            for (JsonNode nodo : plan.path("relations")) {
                ObjectNode r = (ObjectNode) nodo;
                String s = r.path("source").asText(), t = r.path("target").asText();
                if (s.equals(t) || !"ASSOCIATION".equals(r.path("kind").asText("ASSOCIATION"))) continue;
                if (!r.path("sourceMultiplicity").asText().contains("*") || !r.path("targetMultiplicity").asText().contains("*")) continue;
                int ls = enlaces.getOrDefault(s, 0), lt = enlaces.getOrDefault(t, 0);
                if (ls == 0 || lt == 0 || (ls >= 2) == (lt >= 2)) continue;
                boolean lugarEsOrigen = ls >= 2;
                cambios += poner(r, lugarEsOrigen ? "sourceMultiplicity" : "targetMultiplicity", "1")
                        + poner(r, lugarEsOrigen ? "targetMultiplicity" : "sourceMultiplicity", "0..*");
                auditar(plan, "El backend dejo " + (lugarEsOrigen ? t : s) + " - " + (lugarEsOrigen ? s : t)
                        + " como ubicacion actual; " + movimiento + " conserva el historial.");
            }
        }
        return cambios;
    }

    /** COMPOSITION que la propia auditoria justifica "sin borrado en cascada": es ASSOCIATION. */
    static int alinearCicloDeVidaConAuditoria(ObjectNode plan) {
        if (!(plan.path("audit") instanceof ArrayNode audit)) return 0;
        int cambios = 0;
        for (JsonNode nodo : plan.path("relations")) {
            ObjectNode r = (ObjectNode) nodo;
            String kind = r.path("kind").asText();
            if (!kind.equals("COMPOSITION") && !kind.equals("AGGREGATION")) continue;
            String s = normal(r.path("source").asText()), t = normal(r.path("target").asText());
            for (int i = audit.size() - 1; i >= 0; i--) {
                String punto = normal(audit.get(i).asText());
                boolean conserva = punto.contains("sin borrado") || punto.contains("no se borr")
                        || punto.contains("no se elimin") || punto.contains("persist");
                if (!conserva || !punto.contains(s) || !punto.contains(t)) continue;
                r.put("kind", "ASSOCIATION");
                audit.set(i, audit.textNode("El backend modelo " + r.path("source").asText() + "-"
                        + r.path("target").asText() + " como ASSOCIATION: conservar " + r.path("target").asText()
                        + " sin borrado en cascada contradice una COMPOSITION."));
                cambios++;
                break;
            }
        }
        return cambios;
    }

    /** La auditoria no puede afirmar que conecto una clase que sigue suelta. */
    static int retirarAfirmacionesFalsas(ObjectNode plan) {
        List<String> sueltas = new ArrayList<>();
        for (JsonNode c : plan.path("classes"))
            if (grado(plan, c.path("name").asText()) == 0) sueltas.add(c.path("name").asText());
        if (sueltas.isEmpty() || !(plan.path("audit") instanceof ArrayNode audit)) return 0;
        int cambios = 0;
        for (int i = audit.size() - 1; i >= 0; i--) {
            String punto = normal(audit.get(i).asText());
            boolean afirmaEnlace = punto.contains("conect") || punto.contains("vincul") || punto.contains("relacion")
                    || punto.contains("asocia");
            if (afirmaEnlace && sueltas.stream().anyMatch(c -> menciona(punto, normal(c)))) { audit.remove(i); cambios++; }
        }
        return cambios;
    }

    // --- utilidades ---

    private static boolean esRegistro(String nombre) {
        String n = normal(nombre);
        return REGISTROS.stream().anyMatch(n::startsWith);
    }

    private static int grado(JsonNode plan, String nombre) {
        int g = 0;
        for (JsonNode r : plan.path("relations"))
            if (nombre.equals(r.path("source").asText()) || nombre.equals(r.path("target").asText())) g++;
        return g;
    }

    private static Set<String> vecinas(JsonNode plan, String nombre) {
        Set<String> vecinas = new LinkedHashSet<>();
        for (JsonNode r : plan.path("relations")) {
            String s = r.path("source").asText(), t = r.path("target").asText();
            if (s.equals(nombre)) vecinas.add(t); else if (t.equals(nombre)) vecinas.add(s);
        }
        return vecinas;
    }

    private static boolean relacionados(JsonNode plan, String a, String b) {
        for (JsonNode r : plan.path("relations")) {
            String s = r.path("source").asText(), t = r.path("target").asText();
            if ((s.equals(a) && t.equals(b)) || (s.equals(b) && t.equals(a))) return true;
        }
        return false;
    }

    private static String clase(JsonNode plan, java.util.function.Predicate<String> criterio) {
        List<String> encontradas = new ArrayList<>();
        for (JsonNode c : plan.path("classes"))
            if (criterio.test(normal(c.path("name").asText()))) encontradas.add(c.path("name").asText());
        return encontradas.size() == 1 ? encontradas.getFirst() : null;
    }

    private static ObjectNode objeto(JsonNode plan, String nombre) {
        for (JsonNode c : plan.path("classes")) if (c.path("name").asText().equals(nombre)) return (ObjectNode) c;
        throw new IllegalArgumentException(nombre);
    }

    private static boolean tieneAtributo(JsonNode plan, String clase, String atributo) {
        for (JsonNode a : objeto(plan, clase).path("attributes"))
            if (a.path("name").asText().equalsIgnoreCase(atributo)) return true;
        return false;
    }

    private static boolean tieneAtributoParecido(JsonNode plan, String clase, String... patrones) {
        for (JsonNode a : objeto(plan, clase).path("attributes")) {
            String n = normal(a.path("name").asText());
            for (String p : patrones) if (n.contains(p)) return true;
        }
        return false;
    }

    private static void retirarClase(ObjectNode plan, String nombre) {
        ArrayNode clases = (ArrayNode) plan.path("classes"), relaciones = (ArrayNode) plan.path("relations");
        for (int i = clases.size() - 1; i >= 0; i--) if (clases.get(i).path("name").asText().equals(nombre)) clases.remove(i);
        for (int i = relaciones.size() - 1; i >= 0; i--) {
            JsonNode r = relaciones.get(i);
            if (nombre.equals(r.path("source").asText()) || nombre.equals(r.path("target").asText())) relaciones.remove(i);
        }
    }

    private static void quitarAuditoria(ObjectNode plan, List<String> clases) {
        if (!(plan.path("audit") instanceof ArrayNode audit)) return;
        for (int i = audit.size() - 1; i >= 0; i--) {
            String punto = normal(audit.get(i).asText());
            if (clases.stream().anyMatch(c -> menciona(punto, normal(c)))) audit.remove(i);
        }
    }

    private static void auditar(ObjectNode plan, String texto) {
        if (plan.path("audit") instanceof ArrayNode audit) audit.add(texto);
    }

    /** Menciona la clase como palabra completa: "salida" no esta en "salidas" de otra clase. */
    private static boolean menciona(String punto, String clase) {
        return java.util.regex.Pattern.compile("(?<![a-z0-9])" + java.util.regex.Pattern.quote(clase) + "(?![a-z0-9])")
                .matcher(punto).find();
    }

    private static int poner(ObjectNode r, String campo, String valor) {
        if (valor.equals(r.path(campo).asText())) return 0;
        r.put(campo, valor);
        return 1;
    }

    private static String normal(String s) {
        return Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT).replace("_", "");
    }
}
