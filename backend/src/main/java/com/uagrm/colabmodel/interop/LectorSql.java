package com.uagrm.colabmodel.interop;

import com.uagrm.colabmodel.interop.EsquemaRelacional.Columna;
import com.uagrm.colabmodel.interop.EsquemaRelacional.Referencia;
import com.uagrm.colabmodel.interop.EsquemaRelacional.Tabla;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * DDL de PostgreSQL: ingenieria inversa relacional -> conceptual (ADR-018).
 *
 * Lector propio, sin dependencias: solo entiende CREATE TABLE y
 * ALTER TABLE ... ADD (FOREIGN KEY | PRIMARY KEY | UNIQUE). Todo lo demas se
 * ignora y se cuenta en una advertencia (Regla 10). Una sentencia que no se
 * entiende no tumba el archivo: se saltea y se dice.
 *
 * Este archivo solo LEE las tablas; que clases y relaciones salen de ellas lo
 * decide EsquemaRelacional, el mismo que usa el modelo de datos de EA en XMI.
 *
 * Los nombres se toman tal cual del DDL. No se "embellecen" a PascalCase:
 * Nombres.identificador es idempotente sobre un identificador que ya produjo,
 * y eso es lo que hace que reimportar nuestro propio DDL regenere el mismo
 * esquema, incluidos los nombres truncados con hash (Regla 8).
 */
@Component
public class LectorSql implements LectorDeModelo {

    private static final Set<String> CLAVES_DE_COLUMNA = Set.of(
            "not", "null", "unique", "primary", "references", "check", "default", "collate", "generated",
            "constraint");

    @Override
    public String formato() {
        return "SQL";
    }

    @Override
    public boolean acepta(String nombreArchivo, byte[] contenido) {
        String nombre = nombreArchivo == null ? "" : nombreArchivo.toLowerCase(Locale.ROOT);
        if (nombre.endsWith(".sql") || nombre.endsWith(".ddl")) return true;
        if (nombre.endsWith(".json") || nombre.endsWith(".xmi") || nombre.endsWith(".xml")) return false;
        String inicio = new String(contenido, 0, Math.min(contenido.length, 65536), StandardCharsets.UTF_8);
        return inicio.toLowerCase(Locale.ROOT).contains("create table");
    }

    @Override
    public ModeloLeido leer(byte[] contenido) {
        String texto = new String(contenido, StandardCharsets.UTF_8);
        if (texto.isBlank()) throw new ArchivoInvalidoException("El archivo SQL esta vacio");

        List<String> advertencias = new ArrayList<>();
        Map<String, Tabla> tablas = new LinkedHashMap<>();
        List<List<Token>> alters = new ArrayList<>();
        int ignoradas = 0;

        // Dos pasadas: primero todas las tablas, despues los ALTER. Asi el orden
        // del archivo no importa, igual que en el DDL que emite el generador.
        for (List<Token> s : sentencias(texto)) {
            if (coincide(s, 0, "create") && coincide(s, 1, "table")) {
                try {
                    leerCreateTable(s, tablas, advertencias);
                } catch (RuntimeException e) {
                    advertencias.add("No se pudo interpretar un CREATE TABLE y se ignora: " + resumen(s));
                }
            } else if (coincide(s, 0, "alter") && coincide(s, 1, "table")) {
                alters.add(s);
            } else {
                ignoradas++;
            }
        }
        for (List<Token> s : alters) {
            try {
                leerAlterTable(s, tablas, advertencias);
            } catch (RuntimeException e) {
                advertencias.add("No se pudo interpretar un ALTER TABLE y se ignora: " + resumen(s));
            }
        }
        if (ignoradas > 0) {
            advertencias.add(ignoradas + (ignoradas == 1 ? " sentencia se ignoro" : " sentencias se ignoraron")
                    + ": este lector solo lee CREATE TABLE y ALTER TABLE (indices, vistas, datos y funciones"
                    + " no forman parte del modelo).");
        }
        if (tablas.isEmpty()) {
            throw new ArchivoInvalidoException("El archivo no tiene ningun CREATE TABLE que se pueda leer");
        }
        return EsquemaRelacional.reconstruir(formato(), tablas, advertencias);
    }

    // =============================================================== LECTURA

    private void leerCreateTable(List<Token> s, Map<String, Tabla> tablas, List<String> advertencias) {
        int p = 2;
        if (coincide(s, p, "if") && coincide(s, p + 1, "not") && coincide(s, p + 2, "exists")) p += 3;
        String nombre = identificador(s.get(p).texto);
        p++;
        if (p >= s.size() || !s.get(p).es("(")) {
            advertencias.add("CREATE TABLE " + nombre + " no tiene lista de columnas y se ignora.");
            return;
        }
        Tabla tabla = new Tabla(nombre);
        if (tablas.putIfAbsent(nombre, tabla) != null) {
            advertencias.add("La tabla " + nombre + " esta creada dos veces: se usa la primera.");
            return;
        }

        // Las restricciones de tabla se aplican al final: pueden aparecer antes
        // que la columna a la que se refieren.
        List<String> pk = new ArrayList<>();
        List<String> unicas = new ArrayList<>();
        Map<String, Referencia> fks = new LinkedHashMap<>();
        int cierre = cierre(s, p);
        for (List<Token> f : porComas(s, p + 1, cierre)) {
            if (f.isEmpty()) continue;
            int q = coincide(f, 0, "constraint") ? 2 : 0;
            if (coincide(f, q, "primary")) {
                pk.addAll(nombresEntreParentesis(f, q));
            } else if (coincide(f, q, "unique")) {
                List<String> cols = nombresEntreParentesis(f, q);
                if (cols.size() == 1) unicas.add(cols.get(0));
                else advertencias.add("Tabla " + nombre + ": el UNIQUE compuesto " + cols
                        + " no se importa (un atributo solo puede ser unico por si mismo).");
            } else if (coincide(f, q, "foreign")) {
                agregarFk(f, q, nombre, fks, advertencias);
            } else if (coincide(f, q, "check")) {
                leerCheck(f, q + 1, null, tabla);
            } else {
                leerColumna(f, tabla);
            }
        }
        pk.forEach(tabla::marcarPk);
        unicas.forEach(c -> tabla.columna(c).ifPresent(x -> x.unica = true));
        fks.forEach((c, r) -> tabla.columna(c).ifPresent(x -> x.referencia = r));
    }

    private void leerColumna(List<Token> f, Tabla tabla) {
        Columna c = new Columna(identificador(f.get(0).texto));
        int p = 1;
        StringBuilder tipo = new StringBuilder();
        while (p < f.size() && !esClaveDeColumna(f.get(p))) {
            Token t = f.get(p);
            boolean pegado = t.es("(") || t.es(")") || t.es(",") || tipo.isEmpty()
                    || tipo.charAt(tipo.length() - 1) == '(' || tipo.charAt(tipo.length() - 1) == ',';
            if (!pegado) tipo.append(' ');
            tipo.append(t.texto.toLowerCase(Locale.ROOT));
            p++;
        }
        c.tipo = normalizarTipo(tipo.toString());
        tabla.columnas.put(c.nombre, c);

        while (p < f.size()) {
            Token t = f.get(p);
            if (t.palabra("not") && coincide(f, p + 1, "null")) {
                c.nula = false;
                p += 2;
            } else if (t.palabra("null")) {
                p++;
            } else if (t.palabra("unique")) {
                c.unica = true;
                p++;
            } else if (t.palabra("primary")) {
                c.pk = true;
                c.nula = false;
                p += 2;
            } else if (t.palabra("references")) {
                Leida r = referencia(f, p);
                c.referencia = r.referencia();
                p = r.fin();
            } else if (t.palabra("check")) {
                p = leerCheck(f, p + 1, c, tabla);
            } else {
                // DEFAULT, COLLATE, GENERATED, CONSTRAINT <nombre> y lo que no se
                // conozca: se consume hasta la proxima clausula reconocible.
                p++;
                while (p < f.size() && !esClaveDeColumna(f.get(p))) p++;
            }
        }
    }

    private void leerAlterTable(List<Token> s, Map<String, Tabla> tablas, List<String> advertencias) {
        int p = 2;
        if (coincide(s, p, "if") && coincide(s, p + 1, "exists")) p += 2;
        if (coincide(s, p, "only")) p++;
        String nombre = identificador(s.get(p).texto);
        Tabla tabla = tablas.get(nombre);
        if (tabla == null) {
            advertencias.add("ALTER TABLE " + nombre + ": no hay un CREATE TABLE para esa tabla, se ignora.");
            return;
        }
        boolean entendida = false;
        for (List<Token> cl : porComas(s, p + 1, s.size())) {
            if (!coincide(cl, 0, "add")) continue;
            int q = coincide(cl, 1, "constraint") ? 3 : 1;
            if (coincide(cl, q, "foreign")) {
                Map<String, Referencia> fks = new LinkedHashMap<>();
                agregarFk(cl, q, nombre, fks, advertencias);
                fks.forEach((c, r) -> tabla.columna(c).ifPresent(x -> x.referencia = r));
                entendida = true;
            } else if (coincide(cl, q, "primary")) {
                nombresEntreParentesis(cl, q).forEach(tabla::marcarPk);
                entendida = true;
            } else if (coincide(cl, q, "unique")) {
                List<String> cols = nombresEntreParentesis(cl, q);
                if (cols.size() == 1) tabla.columna(cols.get(0)).ifPresent(x -> x.unica = true);
                entendida = true;
            }
        }
        if (!entendida) {
            advertencias.add("ALTER TABLE " + nombre + " no agrega una clave (FOREIGN KEY, PRIMARY KEY o UNIQUE)"
                    + " y se ignora: " + resumen(s));
        }
    }

    private void agregarFk(List<Token> f, int desde, String tabla, Map<String, Referencia> fks,
                           List<String> advertencias) {
        List<String> locales = nombresEntreParentesis(f, desde);
        Leida r = referencia(f, desde);
        if (r == null) return;
        if (locales.size() != 1) {
            advertencias.add("Tabla " + tabla + ": la FK compuesta " + locales + " hacia " + r.referencia().tabla()
                    + " no se importa (solo claves foraneas de una columna).");
            return;
        }
        fks.put(locales.get(0), r.referencia());
    }

    private record Leida(Referencia referencia, int fin) { }

    /** `REFERENCES tabla [(col)] [ON DELETE accion] [ON UPDATE accion] ...`, buscando desde `desde`. */
    private Leida referencia(List<Token> f, int desde) {
        int p = desde;
        while (p < f.size() && !f.get(p).palabra("references")) p++;
        if (p + 1 >= f.size()) return null;
        String tabla = identificador(f.get(p + 1).texto);
        p += 2;
        if (p < f.size() && f.get(p).es("(")) p = cierre(f, p) + 1;
        String alBorrar = null;
        while (coincide(f, p, "on") && p + 2 < f.size()) {
            boolean esBorrado = f.get(p + 1).palabra("delete");
            p += 2;
            StringBuilder accion = new StringBuilder(f.get(p).texto.toUpperCase(Locale.ROOT));
            p++;
            if ((accion.toString().equals("SET") || accion.toString().equals("NO")) && p < f.size()) {
                accion.append(' ').append(f.get(p).texto.toUpperCase(Locale.ROOT));
                p++;
            }
            if (esBorrado) alBorrar = accion.toString();
        }
        while (p < f.size() && !esClaveDeColumna(f.get(p))) p++;   // MATCH, DEFERRABLE...
        return new Leida(new Referencia(tabla, alBorrar), p);
    }

    /**
     * Un CHECK no se importa (rangos y enumeraciones no viajan por el BATCH),
     * pero `col IN ('A', 'B')` se recuerda para dejar una pista: puede ser una
     * enumeracion o el discriminador de un SINGLE_TABLE.
     *
     * @param columna la columna si el CHECK esta en su linea; null si es de tabla
     * @return el indice siguiente al CHECK
     */
    private int leerCheck(List<Token> f, int abre, Columna columna, Tabla tabla) {
        if (abre >= f.size() || !f.get(abre).es("(")) return abre;
        int fin = cierre(f, abre);
        List<Token> expr = f.subList(abre + 1, Math.min(fin, f.size()));
        int in = -1;
        for (int i = 0; i < expr.size(); i++) if (expr.get(i).palabra("in")) { in = i; break; }
        if (in == 1 && in + 1 < expr.size() && expr.get(in + 1).es("(")) {
            List<String> literales = new ArrayList<>();
            for (Token t : expr.subList(in + 2, expr.size())) if (t.cadena) literales.add(t.texto);
            Columna objetivo = columna != null ? columna
                    : tabla.columna(identificador(expr.get(0).texto)).orElse(null);
            if (objetivo != null && !literales.isEmpty()) {
                objetivo.literales = literales;
                return fin + 1;
            }
        }
        tabla.checksIgnorados++;
        return fin + 1;
    }

    // ================================================================ LEXICO

    private record Token(String texto, boolean cadena) {
        boolean es(String simbolo) {
            return !cadena && texto.equals(simbolo);
        }

        boolean palabra(String w) {
            return !cadena && texto.equalsIgnoreCase(w);
        }
    }

    /** Tokens de cada sentencia, sin comentarios. Un `;` dentro de una cadena no corta. */
    static List<List<Token>> sentencias(String sql) {
        List<List<Token>> sentencias = new ArrayList<>();
        List<Token> actual = new ArrayList<>();
        int i = 0;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                while (i < n && sql.charAt(i) != '\n') i++;
            } else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                int fin = sql.indexOf("*/", i + 2);
                i = fin < 0 ? n : fin + 2;
            } else if (c == '\'' || c == '"') {
                StringBuilder sb = new StringBuilder();
                int j = i + 1;
                while (j < n) {
                    if (sql.charAt(j) == c) {
                        if (j + 1 < n && sql.charAt(j + 1) == c) {   // '' o "" escapados
                            sb.append(c);
                            j += 2;
                            continue;
                        }
                        break;
                    }
                    sb.append(sql.charAt(j++));
                }
                actual.add(new Token(sb.toString(), c == '\''));
                i = j + 1;
            } else if (c == ';') {
                if (!actual.isEmpty()) sentencias.add(actual);
                actual = new ArrayList<>();
                i++;
            } else if (c == '(' || c == ')' || c == ',') {
                actual.add(new Token(String.valueOf(c), false));
                i++;
            } else {
                int j = i;
                while (j < n && !Character.isWhitespace(sql.charAt(j)) && "(),;'\"".indexOf(sql.charAt(j)) < 0) j++;
                actual.add(new Token(sql.substring(i, j), false));
                i = j;
            }
        }
        if (!actual.isEmpty()) sentencias.add(actual);
        return sentencias;
    }

    private static boolean coincide(List<Token> f, int i, String palabra) {
        return i >= 0 && i < f.size() && f.get(i).palabra(palabra);
    }

    private static boolean esClaveDeColumna(Token t) {
        return !t.cadena() && CLAVES_DE_COLUMNA.contains(t.texto().toLowerCase(Locale.ROOT));
    }

    private static int cierre(List<Token> f, int abre) {
        int profundidad = 0;
        for (int i = abre; i < f.size(); i++) {
            if (f.get(i).es("(")) profundidad++;
            else if (f.get(i).es(")") && --profundidad == 0) return i;
        }
        return f.size();
    }

    private static List<List<Token>> porComas(List<Token> s, int desde, int hasta) {
        List<List<Token>> partes = new ArrayList<>();
        List<Token> actual = new ArrayList<>();
        int profundidad = 0;
        for (int i = desde; i < Math.min(hasta, s.size()); i++) {
            Token t = s.get(i);
            if (t.es("(")) profundidad++;
            if (t.es(")")) profundidad--;
            if (t.es(",") && profundidad == 0) {
                partes.add(actual);
                actual = new ArrayList<>();
            } else {
                actual.add(t);
            }
        }
        if (!actual.isEmpty()) partes.add(actual);
        return partes;
    }

    /** Los nombres del primer `( ... )` a partir de `desde`. */
    private static List<String> nombresEntreParentesis(List<Token> f, int desde) {
        int abre = desde;
        while (abre < f.size() && !f.get(abre).es("(")) abre++;
        List<String> nombres = new ArrayList<>();
        for (int i = abre + 1; i < Math.min(cierre(f, abre), f.size()); i++) {
            if (!f.get(i).es(",")) nombres.add(identificador(f.get(i).texto));
        }
        return nombres;
    }

    /** Sin esquema (`public.cliente` -> `cliente`) y en minusculas, como los pliega PostgreSQL. */
    private static String identificador(String crudo) {
        int punto = crudo.lastIndexOf('.');
        return (punto >= 0 ? crudo.substring(punto + 1) : crudo).toLowerCase(Locale.ROOT);
    }

    /**
     * Las formas de varias palabras se llevan a la corta, que es la que conoce
     * InferenciaDeAtributos: `character varying(50)` -> `varchar(50)`.
     */
    private static String normalizarTipo(String tipo) {
        return tipo.replace("character varying", "varchar")
                .replace("double precision", "double")
                .replace("timestamp with time zone", "timestamptz")
                .replace("timestamp without time zone", "timestamp")
                .replace("time with time zone", "time")
                .replace("time without time zone", "time");
    }

    private static String resumen(List<Token> s) {
        StringBuilder sb = new StringBuilder();
        for (Token t : s) {
            if (sb.length() > 60) return sb.append("...").toString();
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(t.texto());
        }
        return sb.toString();
    }
}
