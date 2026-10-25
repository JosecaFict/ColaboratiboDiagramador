package com.uagrm.colabmodel.modelcore.mapping;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.Set;

/**
 * REGLA 8 - Identificadores.
 *
 * Convierte un nombre de clase o atributo dibujado por una persona en un
 * identificador valido de PostgreSQL. Es la regla que rompe a un generador
 * ingenuo: acentos, enies, palabras reservadas y nombres largos.
 *
 * Dos detalles que parecen manias y no lo son:
 *   - La enie se translitera a "ni" ANTES de quitar diacriticos. Si se dejara
 *     al normalizador, "Año" quedaria en "ano", que no es lo que nadie quiere
 *     leer en un esquema.
 *   - No se pluraliza NUNCA (Regla 2). Pluralizar en castellano da "pezs" y
 *     "analisiss".
 */
public final class Nombres {

    private static final int MAX_BYTES = 63;   // limite duro de PostgreSQL

    private static final Set<String> RESERVADAS = Set.of(
            "all", "alter", "analyse", "analyze", "and", "any", "array", "as", "asc", "between",
            "both", "case", "cast", "check", "collate", "column", "constraint", "create", "current_date",
            "current_time", "current_timestamp", "current_user", "default", "deferrable", "delete",
            "desc", "distinct", "do", "drop", "else", "end", "except", "exists", "false", "for",
            "foreign", "from", "grant", "group", "having", "in", "index", "initially", "inner",
            "insert", "intersect", "interval", "into", "is", "join", "key", "leading", "left",
            "like", "limit", "not", "null", "offset", "on", "only", "or", "order", "outer",
            "primary", "references", "returning", "right", "role", "select", "session", "session_user",
            "some", "table", "then", "time", "timestamp", "to", "trailing", "true", "union",
            "unique", "update", "user", "using", "values", "when", "where", "window", "with");

    private Nombres() { }

    /** Nombre de tabla: sin pluralizar (Regla 2). */
    public static String tabla(String nombreClase) {
        return identificador(nombreClase);
    }

    public static String columna(String nombreAtributo) {
        return identificador(nombreAtributo);
    }

    /** Columna FK: sale del ROL, no de la clase (Regla 3). */
    public static String columnaFk(String rolONombreClase) {
        return identificador(rolONombreClase + "_id");
    }

    public static String identificador(String crudo) {
        String limpio = normalizado(crudo);
        if (RESERVADAS.contains(limpio)) limpio = limpio + "_";
        return truncar(limpio);
    }

    /**
     * snake_case transliterado, SIN esquivar palabras reservadas ni truncar.
     * Lo usa el generador para derivar nombres Java, donde las palabras
     * reservadas son otras (`user` es valida en Java y no en PostgreSQL).
     */
    public static String normalizado(String crudo) {
        String limpio = aSnakeCase(transliterar(crudo));
        if (limpio.isEmpty()) limpio = "sin_nombre";
        if (Character.isDigit(limpio.charAt(0))) limpio = "_" + limpio;
        return limpio;
    }

    private static String transliterar(String s) {
        String conEnies = s.replace("ñ", "ni").replace("Ñ", "Ni");
        return Normalizer.normalize(conEnies, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
    }

    private static String aSnakeCase(String s) {
        String separado = s.replaceAll("([a-z0-9])([A-Z])", "$1_$2");   // camelCase -> camel_Case
        return separado.replaceAll("[^A-Za-z0-9]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_+|_+$", "")
                .toLowerCase();
    }

    /**
     * Trunca con hash: dos nombres largos que comparten prefijo tienen que
     * seguir dando columnas distintas, o el esquema no compila.
     */
    private static String truncar(String s) {
        if (s.getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES) return s;
        String hash = hashCorto(s);
        return s.substring(0, MAX_BYTES - hash.length() - 1) + "_" + hash;
    }

    private static String hashCorto(String s) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4; i++) sb.append(String.format("%02x", digest[i]));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 siempre existe en la JVM", e);
        }
    }
}
