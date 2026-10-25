package com.uagrm.colabmodel.codegen;

import com.uagrm.colabmodel.modelcore.mapping.ModeloLogico;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Unas pocas filas por tabla, para que el backend generado no arranque vacio.
 *
 * Suena a detalle y no lo es: sin datos, la consola de prueba abre en blanco y
 * lo primero que hace quien la mira es cargar un registro a mano. Con tres
 * filas, la primera pantalla ya demuestra que el listado, el esquema y las
 * claves foraneas funcionan.
 *
 * Solo se cargan en el perfil `h2` --el generador las deja en
 * `db/demo/`, una carpeta que Flyway lee unicamente en ese perfil--, asi que
 * apuntar el mismo artefacto a PostgreSQL no ensucia nada.
 *
 * PRUDENTE A PROPOSITO. Un INSERT invalido no se rompe al usarlo: se rompe al
 * ARRANCAR, y deja el backend generado sin levantar, que es el peor resultado
 * posible. Por eso, ante cualquier tabla que no se entienda del todo --clave
 * compuesta, jerarquia en una sola tabla, un tipo desconocido en una columna
 * obligatoria-- no se inventa nada: se saltea y se dice por que.
 */
final class DatosDeEjemplo {

    /** Filas por tabla. Tres alcanzan para ver un listado y no tapan la pantalla. */
    private static final int FILAS = 3;

    /** Va aparte de `db/migration` para que solo la lea el perfil de demo. */
    static final String RUTA = "src/main/resources/db/demo/V900__datos_de_ejemplo.sql";

    private DatosDeEjemplo() { }

    /**
     * @param conEntity tablas que generaron sus 5 capas. Las que no, no se
     *                  pueden consultar por la API, asi que sembrarlas no
     *                  mostraria nada
     * @return el script, o null si no hubo ninguna tabla segura de sembrar
     */
    static String sql(ModeloLogico logico, Set<String> conEntity) {
        Map<String, ModeloLogico.Tabla> porNombre = new LinkedHashMap<>();
        for (ModeloLogico.Tabla t : logico.tablas()) porNombre.put(t.nombre(), t);

        List<ModeloLogico.Tabla> sembrables = new ArrayList<>();
        List<String> salteadas = new ArrayList<>();
        for (ModeloLogico.Tabla t : logico.tablas()) {
            if (!conEntity.contains(t.nombre())) continue;
            String motivo = porQueNoSeSiembra(t);
            if (motivo == null) sembrables.add(t);
            else salteadas.add(t.nombre() + ": " + motivo);
        }

        List<ModeloLogico.Tabla> orden = porDependencias(sembrables);
        Map<String, List<String>> idsPorTabla = new LinkedHashMap<>();
        StringBuilder sql = new StringBuilder();

        for (ModeloLogico.Tabla t : orden) {
            List<String> lineas = filasDe(t, idsPorTabla);
            if (lineas.isEmpty()) {
                salteadas.add(t.nombre() + ": depende de una tabla que no se pudo sembrar");
                idsPorTabla.remove(t.nombre());
                continue;
            }
            sql.append("-- ").append(t.nombre()).append(" (").append(t.origen()).append(")\n");
            lineas.forEach(l -> sql.append(l).append("\n"));
            sql.append("\n");
        }

        if (sql.isEmpty()) return null;

        StringBuilder encabezado = new StringBuilder("""
                -- Datos de ejemplo. Generado desde el modelo conceptual, no editar a mano.
                --
                -- Solo se aplican con el perfil `h2` (Flyway lee esta carpeta unicamente
                -- ahi). Apuntar este mismo jar a PostgreSQL no los carga.
                """);
        if (!salteadas.isEmpty()) {
            encabezado.append("--\n-- Tablas sin datos de ejemplo:\n");
            for (String s : salteadas) encabezado.append("--   - ").append(s).append("\n");
        }
        return encabezado.append("\n").append(sql).toString();
    }

    /** null = se puede sembrar. Cualquier otra cosa es el motivo por el que no. */
    private static String porQueNoSeSiembra(ModeloLogico.Tabla t) {
        if (t.tablaUnica() != null) {
            return "es una jerarquia en una sola tabla y habria que elegir el discriminador";
        }
        List<ModeloLogico.Columna> pk = t.columnas().stream().filter(ModeloLogico.Columna::pk).toList();
        if (pk.size() != 1 || !"id".equals(pk.get(0).nombre())) {
            return "su clave primaria no es un `id` simple";
        }
        if (pk.get(0).referencia() != null) {
            return "su `id` es tambien clave foranea (subclase JOINED)";
        }
        for (ModeloLogico.Columna c : t.columnas()) {
            if (c.pk() || c.nullable() || c.referencia() != null) continue;
            if (literalDe(c, 0) == null) {
                return "la columna obligatoria `" + c.nombre() + "` es de un tipo que no se sabe generar ("
                        + c.tipo() + ")";
            }
        }
        return null;
    }

    /**
     * Las tablas referenciadas van primero: un INSERT con una FK a una fila que
     * todavia no existe falla al arrancar.
     *
     * Orden topologico simple. Si quedara un ciclo --dos tablas que se apuntan
     * y ninguna admite NULL-- las restantes se agregan igual y el que falla es
     * el arranque, asi que ese caso se corta antes: una FK obligatoria hacia
     * una tabla no sembrada descarta la tabla en `filasDe`.
     */
    private static List<ModeloLogico.Tabla> porDependencias(List<ModeloLogico.Tabla> tablas) {
        Set<String> nombres = new LinkedHashSet<>();
        tablas.forEach(t -> nombres.add(t.nombre()));

        List<ModeloLogico.Tabla> orden = new ArrayList<>();
        Set<String> puestas = new LinkedHashSet<>();
        boolean avanzo = true;
        while (avanzo && orden.size() < tablas.size()) {
            avanzo = false;
            for (ModeloLogico.Tabla t : tablas) {
                if (puestas.contains(t.nombre())) continue;
                boolean listo = true;
                for (ModeloLogico.Columna c : t.columnas()) {
                    String destino = tablaReferenciada(c);
                    if (destino == null || destino.equals(t.nombre())) continue;
                    if (nombres.contains(destino) && !puestas.contains(destino)) listo = false;
                }
                if (!listo) continue;
                orden.add(t);
                puestas.add(t.nombre());
                avanzo = true;
            }
        }
        for (ModeloLogico.Tabla t : tablas) {
            if (!puestas.contains(t.nombre())) orden.add(t);
        }
        return orden;
    }

    private static String tablaReferenciada(ModeloLogico.Columna c) {
        if (c.referencia() == null) return null;
        int parentesis = c.referencia().indexOf('(');
        return parentesis < 0 ? c.referencia() : c.referencia().substring(0, parentesis);
    }

    /** Vacio si alguna FK obligatoria apunta a una tabla que no se sembro. */
    private static List<String> filasDe(ModeloLogico.Tabla t, Map<String, List<String>> idsPorTabla) {
        List<ModeloLogico.Columna> columnas = t.columnas();
        List<String> ids = new ArrayList<>();
        List<String> lineas = new ArrayList<>();

        for (int fila = 0; fila < FILAS; fila++) {
            List<String> valores = new ArrayList<>();
            for (ModeloLogico.Columna c : columnas) {
                if (c.pk()) {
                    String id = uuidDe(t.nombre(), fila);
                    ids.add(id);
                    valores.add("'" + id + "'");
                    continue;
                }
                String destino = tablaReferenciada(c);
                if (destino != null) {
                    List<String> ajenos = t.nombre().equals(destino) ? ids : idsPorTabla.get(destino);
                    if (ajenos == null || ajenos.isEmpty()) {
                        // Reflexiva: la primera fila no tiene a quien apuntar.
                        if (!c.nullable()) return List.of();
                        valores.add("NULL");
                        continue;
                    }
                    valores.add("'" + ajenos.get(fila % ajenos.size()) + "'");
                    continue;
                }
                String literal = literalDe(c, fila);
                if (literal == null) {
                    if (!c.nullable()) return List.of();
                    literal = "NULL";
                }
                valores.add(literal);
            }
            lineas.add("INSERT INTO " + t.nombre() + " ("
                    + String.join(", ", columnas.stream().map(ModeloLogico.Columna::nombre).toList())
                    + ") VALUES (" + String.join(", ", valores) + ");");
        }
        idsPorTabla.put(t.nombre(), ids);
        return lineas;
    }

    /**
     * Un valor SQL para la columna, o null si no se sabe generar uno.
     *
     * El numero de fila entra en el valor para que una columna UNIQUE no
     * choque consigo misma.
     */
    private static String literalDe(ModeloLogico.Columna c, int fila) {
        if (c.enumeracion() != null) {
            List<String> literales = c.enumeracion().literales();
            if (literales.isEmpty()) return null;
            return "'" + escapar(literales.get(fila % literales.size())) + "'";
        }
        String tipo = c.tipo().toLowerCase(Locale.ROOT);
        if (tipo.startsWith("uuid")) return "'" + uuidDe(c.nombre(), fila) + "'";
        if (tipo.startsWith("boolean") || tipo.startsWith("bool")) return fila % 2 == 0 ? "TRUE" : "FALSE";
        if (tipo.startsWith("date")) return "DATE '2026-03-" + String.format("%02d", 10 + fila) + "'";
        if (tipo.startsWith("timestamp")) {
            return "TIMESTAMP '2026-03-" + String.format("%02d", 10 + fila) + " 09:00:00'";
        }
        if (tipo.startsWith("int") || tipo.startsWith("smallint") || tipo.startsWith("bigint")) {
            return String.valueOf(enteroEnRango(c.rango(), fila));
        }
        if (tipo.startsWith("numeric") || tipo.startsWith("decimal")) {
            return decimalEnRango(c.rango(), fila);
        }
        if (tipo.startsWith("varchar") || tipo.startsWith("char") || tipo.startsWith("text")) {
            return "'" + escapar(recortar(texto(c.nombre(), fila), largoDe(tipo))) + "'";
        }
        return null;
    }

    /** Un valor que respeta el CHECK de la Regla 9; si no hay rango, 1, 2, 3. */
    private static long enteroEnRango(ModeloLogico.Rango rango, int fila) {
        long base = fila + 1L;
        if (rango == null) return base;
        long minimo = rango.minimo() == null ? base : rango.minimo().longValue();
        long maximo = rango.maximo() == null ? Long.MAX_VALUE : rango.maximo().longValue();
        return Math.min(Math.max(minimo + fila, minimo), maximo);
    }

    private static String decimalEnRango(ModeloLogico.Rango rango, int fila) {
        BigDecimal base = BigDecimal.valueOf(10L * (fila + 1));
        if (rango == null) return base.toPlainString();
        BigDecimal valor = rango.minimo() == null ? base : rango.minimo().add(BigDecimal.valueOf(fila));
        if (rango.maximo() != null && valor.compareTo(rango.maximo()) > 0) valor = rango.maximo();
        return valor.toPlainString();
    }

    /** UUID estable: el mismo modelo genera siempre los mismos datos. */
    private static String uuidDe(String semilla, int fila) {
        return UUID.nameUUIDFromBytes((semilla + "#" + fila).getBytes()).toString();
    }

    private static String texto(String columna, int fila) {
        String legible = columna.replace('_', ' ').trim();
        if (legible.isEmpty()) legible = "dato";
        return Character.toUpperCase(legible.charAt(0)) + legible.substring(1) + " " + (fila + 1);
    }

    private static Integer largoDe(String tipo) {
        int abre = tipo.indexOf('(');
        int cierra = tipo.indexOf(')');
        if (abre < 0 || cierra < abre) return null;
        try {
            return Integer.parseInt(tipo.substring(abre + 1, cierra).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String recortar(String valor, Integer largo) {
        if (largo == null || largo <= 0 || valor.length() <= largo) return valor;
        return valor.substring(0, largo);
    }

    private static String escapar(String valor) {
        return valor.replace("'", "''");
    }
}
