package com.uagrm.colabmodel.ai;

import com.fasterxml.jackson.databind.JsonNode;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

/** Herencia dirigida: source es subclase, target es superclase. */
final class RevisorHerenciaPlan {
    private RevisorHerenciaPlan() { }

    static List<String> revisar(JsonNode plan, String requisito) {
        Map<String, Set<String>> padres = new LinkedHashMap<>();
        List<String> errores = new ArrayList<>();
        StringBuilder declaraciones = new StringBuilder(normal(requisito));
        for (JsonNode punto : plan.path("audit")) declaraciones.append('\n').append(normal(punto.asText()));
        for (JsonNode r : plan.path("relations")) {
            if (!r.path("kind").asText().equals("GENERALIZATION")) continue;
            String hija = r.path("source").asText(), padre = r.path("target").asText();
            padres.computeIfAbsent(hija, k -> new LinkedHashSet<>()).add(padre);
            if (hija.equals(padre)) errores.add("Herencia invalida: " + hija + " no puede heredar de si misma.");
            String inversa = "(?<![a-z0-9_])" + Pattern.quote(normal(padre))
                    + "\\s+(?:es\\s+(?:una?\\s+)?)?(?:subclase\\s+de|subtipo\\s+de|hereda\\s+de|extiende)\\s+"
                    + Pattern.quote(normal(hija)) + "(?![a-z0-9_])";
            if (Pattern.compile(inversa).matcher(declaraciones).find()) errores.add("Herencia invertida entre "
                    + hija + " y " + padre + ": el requisito o la auditoria declara que " + padre
                    + " es subclase de " + hija + "; en GENERALIZATION source debe ser la subclase y target la superclase.");
        }
        for (String hija : padres.keySet()) {
            Deque<String> pendientes = new ArrayDeque<>(padres.get(hija));
            Set<String> vistas = new HashSet<>();
            while (!pendientes.isEmpty()) {
                String actual = pendientes.removeFirst();
                if (actual.equals(hija)) { errores.add("Ciclo de herencia que incluye " + hija + "."); break; }
                if (vistas.add(actual)) pendientes.addAll(padres.getOrDefault(actual, Set.of()));
            }
        }
        return List.copyOf(new LinkedHashSet<>(errores));
    }

    private static String normal(String s) {
        return Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT);
    }
}
