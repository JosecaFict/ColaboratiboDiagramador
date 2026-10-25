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
        Map<String, String> clases = new HashMap<>();
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
            if (attrs.isEmpty()) {
                avisos.add("La clase " + nombre + " quedaria sin atributos; confirma que tenga identidad o responsabilidad propia.");
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
            long conexiones = relaciones.stream().filter(r -> id.equals(r.path("sourceClassId").asText())
                    || id.equals(r.path("targetClassId").asText())).count();
            if (conexiones == 0 && creadas.size() > 1) {
                avisos.add("La clase " + nombre + " queda desconectada del resto del modelo.");
            } else if (esHistorica(nombre, attrs) && conexiones < 2) {
                avisos.add("La entidad historica " + nombre + " solo tiene " + conexiones
                        + " relacion; verifica que conecte tanto al actor como al objeto del evento.");
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

            if (mOrigen.isBlank() || mDestino.isBlank()) {
                avisos.add("La relacion " + origen + " - " + destino
                        + " no define multiplicidad en ambos extremos.");
            } else if (mOrigen.equals("1") && mDestino.equals("1")) {
                avisos.add("La relacion " + origen + " [1] - [1] " + destino
                        + " es uno a uno; verifica que no deba conservar multiples instancias historicas.");
            }

            boolean origenHistorico = esHistorica(origen, atributos.getOrDefault(origenId, List.of()));
            boolean destinoHistorico = esHistorica(destino, atributos.getOrDefault(destinoId, List.of()));
            boolean historica = origenHistorico || destinoHistorico;
            if (origenHistorico && !mOrigen.contains("*")) {
                avisos.add("La multiplicidad junto a la entidad historica " + origen + " es " + mOrigen
                        + "; podria limitar el historial. Normalmente debe permitir 0..* registros.");
            }
            if (destinoHistorico && !mDestino.contains("*")) {
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
        return List.copyOf(new java.util.LinkedHashSet<>(avisos));
    }

    private static void advertirReferenciaDuplicada(List<String> avisos, List<Atributo> atributos,
                                                     String clase, String relacionada) {
        String esperada = normal(relacionada) + "id";
        atributos.stream().filter(a -> normal(a.nombre()).equals(esperada)).findFirst().ifPresent(a ->
                avisos.add(clase + "." + a.nombre() + " duplica la asociacion con " + relacionada
                        + " en un modelo conceptual."));
    }

    private static boolean esHistorica(String clase, List<Atributo> atributos) {
        String nombre = normal(clase);
        if (nombre.contains("prestamo") || nombre.contains("reserva") || nombre.contains("historial")
                || nombre.contains("transaccion") || nombre.contains("movimiento")) return true;
        return atributos.stream().map(a -> normal(a.nombre())).anyMatch(a ->
                a.contains("fechadevolucion") || a.contains("fechavencimiento") || a.equals("inicio")
                        || a.equals("fin") || a.contains("fechacierre"));
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
