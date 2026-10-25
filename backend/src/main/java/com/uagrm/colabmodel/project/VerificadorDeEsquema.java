package com.uagrm.colabmodel.project;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ejecuta el DDL en PostgreSQL de verdad, y lo deshace (ADR-032).
 *
 * "El script esta bien" es una afirmacion; "PostgreSQL lo acepto" es
 * evidencia. Es el criterio del ADR-010 --compilar lo generado en vez de
 * prometer que compila-- llevado a la base: el backend generado corre este
 * mismo script con Flyway al arrancar, y si PostgreSQL lo rechaza ahi, el
 * error aparece recien en la demo.
 *
 * Funciona porque PostgreSQL tiene DDL TRANSACCIONAL: CREATE TABLE, ALTER
 * TABLE y CREATE INDEX se deshacen con ROLLBACK igual que un INSERT. En una
 * conexion propia se crea un esquema temporal, se ejecuta ahi el script
 * sentencia por sentencia, se cuenta lo que quedo segun el CATALOGO de
 * PostgreSQL --no segun el script--, y se deshace todo. La base de la
 * herramienta termina exactamente como estaba.
 *
 * Solo ejecuta lo que produce `GeneradorSpringBoot.ddl()`, nunca texto que
 * venga de afuera: los identificadores ya pasaron por la Regla 8 y los
 * literales de los CHECK por la normalizacion de las enumeraciones. Por eso
 * cortar en sentencias puede ser tan simple como "`;` al final de la linea":
 * esto no es un parser de SQL.
 */
@Service
public class VerificadorDeEsquema {

    private static final Logger log = LoggerFactory.getLogger(VerificadorDeEsquema.class);

    /** PostgreSQL dice DONDE fallo como posicion dentro de la sentencia. */
    private static final Pattern POSICION = Pattern.compile("\\s*Position: (\\d+)");

    private final DataSource dataSource;

    public VerificadorDeEsquema(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * @param sentencias cuantas sentencias se ejecutaron sin error
     * @param error      el mensaje de PostgreSQL; la posicion ya va en `linea`
     * @param linea      linea del script donde fallo, contando desde 1
     * @param sentencia  la sentencia que fallo, entera
     */
    public record Verificacion(boolean ok, int sentencias, int tablas, int clavesForaneas, int indices,
                               int restricciones, long milisegundos,
                               String error, Integer linea, String sentencia) {

        static Verificacion fallo(int ejecutadas, long ms, String error, Integer linea, String sentencia) {
            return new Verificacion(false, ejecutadas, 0, 0, 0, 0, ms, error, linea, sentencia);
        }
    }

    /** Una sentencia del script, sin el `;`, y la linea donde empieza. */
    record Sentencia(int linea, String sql) { }

    public Verificacion verificar(String ddl) {
        List<Sentencia> sentencias = sentencias(ddl);
        long inicio = System.nanoTime();
        String esquema = "verificacion_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

        try (Connection c = dataSource.getConnection()) {
            boolean autoCommit = c.getAutoCommit();
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute("SET LOCAL statement_timeout = '15s'");
                st.execute("CREATE SCHEMA " + esquema);
                // SOLO el esquema temporal: una tabla que se llame como una de la
                // herramienta (users, projects) se crea ahi y no choca con la real.
                st.execute("SET LOCAL search_path TO " + esquema);

                int ejecutadas = 0;
                for (Sentencia s : sentencias) {
                    try {
                        st.execute(s.sql());
                        ejecutadas++;
                    } catch (SQLException e) {
                        return Verificacion.fallo(ejecutadas, ms(inicio), sinPosicion(e.getMessage()),
                                lineaDelError(s, e.getMessage()), s.sql());
                    }
                }
                return contar(c, esquema, ejecutadas, inicio);
            } finally {
                // Siempre, haya fallado o no: verificar no puede dejar nada.
                c.rollback();
                c.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            log.warn("No se pudo verificar el esquema contra PostgreSQL", e);
            return Verificacion.fallo(0, ms(inicio),
                    "No se pudo usar PostgreSQL para verificar: " + e.getMessage(), null, null);
        }
    }

    /**
     * Lo que quedo creado, preguntado al catalogo. Los indices que cuenta son
     * los que no sostienen una restriccion: los de las FK, que son los que el
     * script crea a mano (PostgreSQL no indexa las FK solo).
     */
    private static Verificacion contar(Connection c, String esquema, int ejecutadas, long inicio)
            throws SQLException {
        String sql = """
                SELECT
                  (SELECT count(*) FROM pg_tables WHERE schemaname = ?),
                  (SELECT count(*) FROM pg_constraint k JOIN pg_namespace n ON n.oid = k.connamespace
                     WHERE n.nspname = ? AND k.contype = 'f'),
                  (SELECT count(*) FROM pg_index i JOIN pg_class x ON x.oid = i.indexrelid
                     JOIN pg_namespace n ON n.oid = x.relnamespace
                     WHERE n.nspname = ?
                       AND NOT EXISTS (SELECT 1 FROM pg_constraint k WHERE k.conindid = i.indexrelid)),
                  (SELECT count(*) FROM pg_constraint k JOIN pg_namespace n ON n.oid = k.connamespace
                     WHERE n.nspname = ? AND k.contype IN ('c', 'u'))
                """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 1; i <= 4; i++) ps.setString(i, esquema);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return new Verificacion(true, ejecutadas, rs.getInt(1), rs.getInt(2), rs.getInt(3),
                        rs.getInt(4), ms(inicio), null, null, null);
            }
        }
    }

    /**
     * El script cortado en sentencias, cada una con la linea donde empieza.
     *
     * Se ejecutan de a una para poder decir CUAL fallo: mandado entero, el
     * error de PostgreSQL trae una posicion dentro de un texto de cientos de
     * lineas y nadie la encuentra.
     */
    static List<Sentencia> sentencias(String ddl) {
        List<Sentencia> resultado = new ArrayList<>();
        StringBuilder actual = new StringBuilder();
        int inicio = 0;
        String[] lineas = ddl.split("\n", -1);
        for (int i = 0; i < lineas.length; i++) {
            String linea = lineas[i];
            String recortada = linea.strip();
            if (actual.isEmpty() && (recortada.isEmpty() || recortada.startsWith("--"))) continue;
            if (actual.isEmpty()) inicio = i + 1;
            else actual.append('\n');
            actual.append(linea);
            if (recortada.endsWith(";")) {
                String sql = actual.toString().strip();
                resultado.add(new Sentencia(inicio, sql.substring(0, sql.length() - 1)));
                actual.setLength(0);
            }
        }
        if (!actual.isEmpty()) resultado.add(new Sentencia(inicio, actual.toString().strip()));
        return resultado;
    }

    /** La linea del SCRIPT donde fallo: la de la sentencia mas los saltos hasta la posicion. */
    static Integer lineaDelError(Sentencia s, String mensaje) {
        if (mensaje == null) return s.linea();
        Matcher m = POSICION.matcher(mensaje);
        if (!m.find()) return s.linea();
        int posicion = Math.min(Integer.parseInt(m.group(1)), s.sql().length());
        long saltos = s.sql().substring(0, Math.max(0, posicion - 1)).chars().filter(ch -> ch == '\n').count();
        return s.linea() + (int) saltos;
    }

    static String sinPosicion(String mensaje) {
        return mensaje == null ? "Error sin mensaje" : POSICION.matcher(mensaje).replaceAll("").strip();
    }

    private static long ms(long inicioNanos) {
        return (System.nanoTime() - inicioNanos) / 1_000_000;
    }
}
