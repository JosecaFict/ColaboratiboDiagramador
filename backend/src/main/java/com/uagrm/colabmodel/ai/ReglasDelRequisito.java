package com.uagrm.colabmodel.ai;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extrae las reglas verificables de un requisito escrito por el usuario: las
 * vinetas bajo un titulo de reglas de negocio o de verificacion. No conoce
 * ningun dominio; solo la forma del texto (titulos en mayusculas y vinetas).
 *
 * Las reglas de modelado no se extraen: son genericas y las cubren los
 * hallazgos estructurales del backend.
 */
final class ReglasDelRequisito {

    static final int MAXIMO = 20;
    private static final Pattern VINETA = Pattern.compile("^\\s*(?:[-*•]|\\d+[.)])\\s+(.+)$");

    enum Seccion { NEGOCIO, VERIFICACION }

    record Regla(int numero, String texto, Seccion seccion) { }

    private ReglasDelRequisito() { }

    static List<Regla> extraer(String requisito) {
        List<Regla> reglas = new ArrayList<>();
        if (requisito == null || requisito.isBlank()) return reglas;
        Seccion actual = null;
        for (String linea : requisito.split("\\R")) {
            Matcher vineta = VINETA.matcher(linea);
            if (vineta.matches()) {
                if (actual == null || reglas.size() >= MAXIMO) continue;
                String texto = vineta.group(1).trim().replaceAll("[;.,:]+$", "").trim();
                if (!texto.isEmpty()) reglas.add(new Regla(reglas.size() + 1, texto, actual));
            } else if (esTitulo(linea)) {
                actual = seccionDe(linea);
            }
        }
        return List.copyOf(reglas);
    }

    private static boolean esTitulo(String linea) {
        String t = linea.trim();
        if (t.isEmpty() || t.length() > 60) return false;
        long letras = t.chars().filter(Character::isLetter).count();
        long mayusculas = t.chars().filter(Character::isUpperCase).count();
        return letras >= 4 && mayusculas >= letras * 0.8;
    }

    private static Seccion seccionDe(String titulo) {
        String t = Normalizer.normalize(titulo, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toUpperCase(Locale.ROOT);
        if (t.contains("VERIFIC")) return Seccion.VERIFICACION;
        if (t.contains("REGLA") && !t.contains("MODELADO")) return Seccion.NEGOCIO;
        return null;
    }
}
