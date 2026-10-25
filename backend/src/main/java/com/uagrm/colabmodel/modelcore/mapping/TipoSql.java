package com.uagrm.colabmodel.modelcore.mapping;

import java.util.Map;

/** Tabla de mapeo de tipos UML -> PostgreSQL (docs/REGLAS-MAPEO.md). */
public final class TipoSql {

    private static final Map<String, String> TIPOS = Map.ofEntries(
            Map.entry("string", "varchar"),
            Map.entry("text", "text"),
            Map.entry("int", "integer"),
            Map.entry("integer", "integer"),
            Map.entry("long", "bigint"),
            Map.entry("decimal", "numeric(19,2)"),
            Map.entry("boolean", "boolean"),
            Map.entry("bool", "boolean"),
            Map.entry("date", "date"),
            // 'timestamp' y no 'timestamptz': el tipo de Java es LocalDateTime, que
            // no lleva zona. Ademas 'timestamptz' es un alias propio de PostgreSQL
            // que H2 no entiende ni en MODE=PostgreSQL, y el artefacto generado
            // arranca contra H2 por defecto.
            Map.entry("datetime", "timestamp"),
            Map.entry("uuid", "uuid"));

    private TipoSql() { }

    public static String de(String tipoUml, Integer longitud) {
        String base = TIPOS.getOrDefault(tipoUml == null ? "" : tipoUml.toLowerCase(), "varchar");
        if ("varchar".equals(base) && longitud != null && longitud > 0) {
            return "varchar(" + longitud + ")";
        }
        return "varchar".equals(base) ? "varchar(255)" : base;
    }

    /** true si el tipo no esta en la tabla y se cayo al default. */
    public static boolean esDesconocido(String tipoUml) {
        return tipoUml != null && !TIPOS.containsKey(tipoUml.toLowerCase());
    }
}
