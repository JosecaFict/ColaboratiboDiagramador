package com.uagrm.colabmodel.modelcore.mapping;

import java.util.Map;

/**
 * Tabla de mapeo de tipos UML -> Java (docs/REGLAS-MAPEO.md).
 *
 * Vive al lado de TipoSql porque son dos columnas de la MISMA tabla de
 * reglas: si una cambia, la otra casi siempre tambien.
 */
public final class TipoJava {

    private static final Map<String, String> TIPOS = Map.ofEntries(
            Map.entry("string", "String"),
            Map.entry("text", "String"),
            Map.entry("int", "Integer"),
            Map.entry("integer", "Integer"),
            Map.entry("long", "Long"),
            Map.entry("decimal", "BigDecimal"),
            Map.entry("boolean", "Boolean"),
            Map.entry("bool", "Boolean"),
            Map.entry("date", "LocalDate"),
            Map.entry("datetime", "LocalDateTime"),
            Map.entry("uuid", "UUID"));

    /** Import que hay que agregar para cada tipo que no es del java.lang. */
    private static final Map<String, String> IMPORTS = Map.of(
            "BigDecimal", "java.math.BigDecimal",
            "LocalDate", "java.time.LocalDate",
            "LocalDateTime", "java.time.LocalDateTime",
            "UUID", "java.util.UUID");

    private TipoJava() { }

    public static String de(String tipoUml) {
        return TIPOS.getOrDefault(tipoUml == null ? "" : tipoUml.toLowerCase(), "String");
    }

    /** null si el tipo no necesita import. */
    public static String importDe(String tipoJava) {
        return IMPORTS.get(tipoJava);
    }
}
