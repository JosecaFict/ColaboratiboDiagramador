package com.uagrm.colabmodel.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.uagrm.colabmodel.modelcore.command.CommandType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.text.Normalizer;

/**
 * Controles conservadores sobre una propuesta ya traducida. No pretende
 * decidir el dominio: avisa sobre estructuras sospechosas para que la persona
 * las vea antes de aplicar el BATCH.
 */
final class AuditorDePropuestaIA {

    private record Atributo(String nombre, boolean pk, boolean unique) { }

    private AuditorDePropuestaIA() { }

    static List<String> auditar(List<PropuestaDeIA.ComandoAceptado> comandos) {
        return auditar(comandos, Map.of());
    }

    /**
     * @param existentes id -> nombre de las clases que ya estan en el diagrama: una propuesta
     *                   MODIFICAR relaciona clases existentes, y sin sus nombres el aviso decia
     *                   "clase origen [1] - [1] clase destino" (banco, 2026-11-08).
     */
    static List<String> auditar(List<PropuestaDeIA.ComandoAceptado> comandos, Map<String, String> existentes) {
        Map<String, String> clases = new HashMap<>(existentes);
        Map<String, List<Atributo>> atributos = new HashMap<>();
        Set<String> creadas = new HashSet<>();
        List<JsonNode> relaciones = new ArrayList<>();

        for (PropuestaDeIA.ComandoAceptado comando : comandos) {
            JsonNode p = comando.payload();
            if (comando.tipo() == CommandType.CREATE_CLASS) {
                String id = p.path("classId").asText();
                clases.put(id, p.path("name").asText());
                creadas.add(id);
            } else if (comando.tipo() == CommandType.ADD_ATTRIBUTE) {
                atributos.computeIfAbsent(p.path("classId").asText(), k -> new ArrayList<>())
                        .add(new Atributo(p.path("name").asText(), p.path("pk").asBoolean(false),
                                p.path("unique").asBoolean(false)));
            } else if (comando.tipo() == CommandType.CREATE_RELATION) {
                relaciones.add(p);
            }
        }

        List<String> avisos = new ArrayList<>();
        for (String id : creadas) {
            String nombre = clases.get(id);
            List<Atributo> attrs = atributos.getOrDefault(id, List.of());
            long conexiones = relaciones.stream().filter(r -> id.equals(r.path("sourceClassId").asText())
                    || id.equals(r.path("targetClassId").asText())).count();
            if (attrs.isEmpty()) {
                avisos.add("La clase " + nombre + " quedaria sin atributos; confirma que tenga identidad o responsabilidad propia.");
            } else if (attrs.stream().allMatch(a -> a.pk() || esIdSurrogado(a.nombre()))
                    && !(esClaseAsociativa(nombre, attrs) && conexiones >= 2)) {
                avisos.add("La clase " + nombre + " solo contiene identificadores; faltan datos de negocio esenciales.");
            }
            String normal = normal(nombre);
            if ((normal.equals("status") || normal.equals("estado")) && attrs.size() <= 1) {
                avisos.add("La clase " + nombre + " parece una lista cerrada de valores; considera modelarla como enumeracion.");
            }
            if (!identificadorTecnico(nombre)) {
                avisos.add("La clase \"" + nombre + "\" contiene tildes o caracteres no recomendados para codigo; "
                        + "usa un identificador ASCII como \"" + sinDiacriticos(nombre) + "\".");
            }
            for (Atributo atributo : attrs) {
                if (!identificadorTecnico(atributo.nombre())) {
                    avisos.add("El atributo " + nombre + "." + atributo.nombre()
                            + " contiene tildes o caracteres no recomendados para codigo.");
                }
                if (atributo.pk() && !esIdSurrogado(atributo.nombre())) {
                    avisos.add(nombre + "." + atributo.nombre() + " es una clave natural; confirma que sea inmutable "
                            + "o usa id: uuid como PK y conserva este campo como unique.");
                }
            }
            if (conexiones == 0 && creadas.size() > 1) {
                avisos.add("La clase " + nombre + " queda desconectada del resto del modelo.");
            } else if (esClaseAsociativa(nombre, attrs) && conexiones < 2) {
                avisos.add("La posible clase asociativa " + nombre + " solo tiene " + conexiones
                        + " relacion; debe conectar los dos conceptos cuya relacion describe.");
            }
        }

        for (JsonNode relacion : relaciones) {
            String origenId = relacion.path("sourceClassId").asText();
            String destinoId = relacion.path("targetClassId").asText();
            String origen = clases.getOrDefault(origenId, "clase origen");
            String destino = clases.getOrDefault(destinoId, "clase destino");
            String mOrigen = relacion.path("sourceMultiplicity").asText("");
            String mDestino = relacion.path("targetMultiplicity").asText("");
            String tipo = relacion.path("kind").asText("ASSOCIATION");
            if (tipo.equals("GENERALIZATION") || tipo.equals("DEPENDENCY")) continue;

            if (mOrigen.isBlank() || mDestino.isBlank()) {
                avisos.add("La relacion " + origen + " - " + destino
                        + " no define multiplicidad en ambos extremos.");
            } else if (mOrigen.equals("1") && mDestino.equals("1")
                    && !esUnoAUnoDeEstadoActual(origen, destino)) {
                avisos.add("La relacion " + origen + " [1] - [1] " + destino
                        + " es uno a uno; verifica que no deba conservar multiples instancias historicas.");
            }

            boolean origenHistorico = esHistorica(origen, atributos.getOrDefault(origenId, List.of()));
            boolean destinoHistorico = esHistorica(destino, atributos.getOrDefault(destinoId, List.of()));
            boolean historica = origenHistorico || destinoHistorico;
            // Reserva [1] - [1..*] Participante: la reserva es la duena de sus viajeros, no un historial limitado.
            boolean patronEspecial = esPatronEspecial(origen, destino)
                    || (origenHistorico && mDestino.contains("*") && esDependiente(destino))
                    || (destinoHistorico && mOrigen.contains("*") && esDependiente(origen));
            if (origenHistorico && !destinoHistorico && !patronEspecial && !mOrigen.equals("0..*")) {
                avisos.add("La multiplicidad junto a la entidad historica " + origen + " es " + mOrigen
                        + "; podria limitar el historial. Normalmente debe permitir 0..* registros.");
            }
            if (destinoHistorico && !origenHistorico && !patronEspecial && !mDestino.equals("0..*")) {
                avisos.add("La multiplicidad junto a la entidad historica " + destino + " es " + mDestino
                        + "; podria limitar el historial. Normalmente debe permitir 0..* registros.");
            }
            if (historica && tipo.equals("COMPOSITION")) {
                avisos.add("La composicion " + origen + " - " + destino + " involucra una entidad historica; "
                        + "usa ASSOCIATION para que retirar el otro extremo no implique borrar el historial.");
            } else if (historica && tipo.equals("AGGREGATION")) {
                avisos.add("La agregacion " + origen + " - " + destino + " no expresa baja logica ni conservacion "
                        + "historica; normalmente corresponde ASSOCIATION y una regla de negocio separada.");
            }

            advertirReferenciaDuplicada(avisos, atributos.getOrDefault(origenId, List.of()), origen, destino);
            advertirReferenciaDuplicada(avisos, atributos.getOrDefault(destinoId, List.of()), destino, origen);
        }
        for (int i = 0; i < relaciones.size(); i++) {
            JsonNode a = relaciones.get(i);
            for (int j = i + 1; j < relaciones.size(); j++) {
                JsonNode b = relaciones.get(j);
                String tipoA = a.path("kind").asText("ASSOCIATION");
                if (!tipoA.equals(b.path("kind").asText("ASSOCIATION"))
                        || tipoA.equals("GENERALIZATION") || tipoA.equals("DEPENDENCY")) continue;
                boolean misma = a.path("sourceClassId").equals(b.path("sourceClassId"))
                        && a.path("targetClassId").equals(b.path("targetClassId"));
                boolean inversa = a.path("sourceClassId").equals(b.path("targetClassId"))
                        && a.path("targetClassId").equals(b.path("sourceClassId"));
                if (!(misma || inversa)) continue;
                String rolA = a.path("sourceRole").asText() + "|" + a.path("targetRole").asText();
                String rolB = misma ? b.path("sourceRole").asText() + "|" + b.path("targetRole").asText()
                        : b.path("targetRole").asText() + "|" + b.path("sourceRole").asText();
                if (rolA.equals(rolB)) avisos.add("Hay relaciones repetidas entre "
                        + clases.getOrDefault(a.path("sourceClassId").asText(), "origen") + " y "
                        + clases.getOrDefault(a.path("targetClassId").asText(), "destino")
                        + "; verifica duplicados o define roles distintos en ambos extremos.");
            }
        }
        return List.copyOf(new java.util.LinkedHashSet<>(avisos));
    }

    private static void advertirReferenciaDuplicada(List<String> avisos, List<Atributo> atributos,
                                                     String clase, String relacionada) {
        String relacionadaNormal = normal(relacionada);
        // La pk es la identidad propia (idAnimal en Animal), no una referencia: en una
        // asociacion recursiva Animal-Animal se confundia con una clave foranea.
        atributos.stream().filter(a -> !a.pk() && esReferenciaA(normal(a.nombre()), relacionadaNormal)).findFirst().ifPresent(a ->
                avisos.add(clase + "." + a.nombre() + " duplica la asociacion con " + relacionada
                        + " en un modelo conceptual."));
    }

    private static boolean esReferenciaA(String atributo, String relacionada) {
        if (atributo.equals(relacionada) || atributo.equals(relacionada + "id")
                || atributo.equals("id" + relacionada)) return true;
        String base = atributo.endsWith("id") ? atributo.substring(0, atributo.length() - 2) : atributo;
        return base.length() >= 4 && relacionada.startsWith(base);
    }

    private static boolean esHistorica(String clase, List<Atributo> atributos) {
        String nombre = normal(clase);
        if (nombre.contains("prestamo") || nombre.contains("reserva") || nombre.contains("historial")
                || nombre.contains("transaccion") || nombre.contains("movimiento") || nombre.contains("evento")
                || nombre.contains("transferencia") || nombre.contains("traslado")
                || nombre.contains("cancelacion")
                || nombre.contains("cita") || nombre.contains("consulta") || nombre.contains("diagnostico")
                || nombre.contains("receta") || nombre.contains("pedido") || nombre.contains("pago")
                || nombre.contains("aplicacionproducto") || nombre.contains("aplicacionmedicamento")
                || nombre.contains("version") || nombre.contains("asignacion")) return true;
        return atributos.stream().map(a -> normal(a.nombre())).anyMatch(a ->
                a.contains("fechadevolucion") || a.contains("fechavencimiento") || a.equals("inicio")
                        || a.equals("fin") || a.contains("fechacierre"));
    }

    private static boolean esClaseAsociativa(String clase, List<Atributo> atributos) {
        String nombre = normal(clase);
        boolean asociativa = nombre.contains("detalle") || nombre.contains("linea") || nombre.contains("item")
                || nombre.contains("asignacion") || nombre.contains("inscripcion")
                || nombre.contains("membresia") || nombre.contains("indicacion")
                || nombre.contains("titular") || nombre.contains("prerrequisito");
        if (nombre.startsWith("titular") && ValidadorPlanFinal.titularEsPersona(
                atributos.stream().map(Atributo::nombre).toList())) return false;
        if (!nombre.contains("participante")) return asociativa;
        boolean rol = atributos.stream().map(a -> normal(a.nombre()))
                .anyMatch(a -> a.contains("rol") || a.contains("permiso"));
        boolean persona = atributos.stream().map(a -> normal(a.nombre()))
                .anyMatch(a -> a.contains("nombre") || a.contains("documento") || a.contains("correo"));
        return rol && !persona;
    }

    private static boolean esUnoAUnoDeEstadoActual(String origen, String destino) {
        String a = normal(origen);
        String b = normal(destino);
        return (a.startsWith("garantia") && b.contains("detalleventa"))
                || (b.startsWith("garantia") && a.contains("detalleventa"))
                || (a.contains("inventario") && (b.contains("producto") || b.contains("articulo") || b.contains("insumo")))
                || (b.contains("inventario") && (a.contains("producto") || a.contains("articulo") || a.contains("insumo")));
    }

    private static boolean esDependiente(String clase) {
        String n = normal(clase);
        return n.startsWith("participante") || n.startsWith("viajero") || n.startsWith("pasajero")
                || n.startsWith("huesped") || n.startsWith("acompanante") || n.startsWith("cuota");
    }

    private static boolean esPatronEspecial(String origen, String destino) {
        String a = normal(origen);
        String b = normal(destino);
        return (esCabecera(a) && esDetalle(b)) || (esDetalle(a) && esCabecera(b));
    }

    private static boolean esCabecera(String nombre) {
        return nombre.contains("reserva") || nombre.contains("paquete") || nombre.contains("receta") || nombre.contains("pedido") || nombre.contains("factura")
                || nombre.contains("orden") || nombre.contains("venta") || nombre.contains("compra");
    }

    private static boolean esDetalle(String nombre) {
        return nombre.contains("detalle") || nombre.contains("linea") || nombre.contains("item")
                || nombre.contains("medicamentoindicado") || nombre.contains("indicacionmedicamento");
    }

    private static boolean identificadorTecnico(String texto) {
        return texto != null && texto.matches("[A-Za-z_][A-Za-z0-9_]*");
    }

    private static boolean esIdSurrogado(String nombre) {
        if (nombre == null) return false;
        String n = nombre.trim();
        return n.equalsIgnoreCase("id") || n.matches("^id[A-Z].*")
                || n.matches("(?i)^id[_-].+");
    }

    private static String sinDiacriticos(String texto) {
        return Normalizer.normalize(texto, Normalizer.Form.NFD).replaceAll("\\p{M}", "").replace("ñ", "n");
    }

    private static String normal(String texto) {
        return texto == null ? "" : sinDiacriticos(texto).replace("_", "").trim().toLowerCase(Locale.ROOT);
    }
}
