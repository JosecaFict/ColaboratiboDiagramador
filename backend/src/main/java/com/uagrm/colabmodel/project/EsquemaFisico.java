package com.uagrm.colabmodel.project;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * La Vista Fisica: el esquema de PostgreSQL que sale del modelo.
 *
 * Es la TERCERA lectura de la misma derivacion, junto con la Vista Logica y el
 * generador. Por eso el `script` no se arma aca: viene tal cual de
 * `GeneradorSpringBoot.ddl()`, el unico productor de DDL del sistema. Lo que
 * se ve en pantalla es, literalmente, el archivo que va a salir en el zip.
 *
 * El resto del payload --el detalle por tabla-- existe porque un `String` de
 * mil lineas no se puede navegar. Un diagrama de 20 tablas da un script de
 * varios cientos de lineas, y la pregunta que se hace alguien mirandolo no es
 * "mostrame todo" sino "donde esta la columna fecha_prestamo" o "quien
 * referencia a cliente". Eso necesita estructura, no texto.
 *
 * Se deriva del MISMO ModeloLogico que el script, no parseandolo: parsear el
 * SQL para volver a sacar las tablas seria una segunda fuente de verdad que se
 * desincroniza al primer cambio de formato.
 *
 * Los ids de clase, atributo y relacion existen porque la vista se EDITA
 * (ADR-032), y se edita a traves del modelo conceptual: cambiar una columna es
 * mandar un comando sobre el atributo que la produjo. Sin los ids, la pantalla
 * tendria que adivinar de donde sale cada columna por su nombre.
 */
public record EsquemaFisico(
        String dialecto,
        Resumen resumen,
        List<Tabla> tablas,
        /** El DDL completo, verbatim. Es lo que se descarga y lo que se genera. */
        String script,
        /** Regla 10: lo que el modelo no pudo mapear. Se lista, no se calla. */
        List<String> advertencias,
        /**
         * SHA-256 del script. Identifica ESTE esquema: es lo que se verifica,
         * lo que se confirma y lo que el generador compara antes de entregar.
         */
        String hash,
        /** La ultima confirmacion del equipo, o null si nadie confirmo todavia. */
        Confirmacion confirmacion
) {

    /** La huella de un script: la misma funcion para la vista, la confirmacion y el zip. */
    public static String huellaDe(String script) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(script.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 siempre existe en la JVM", e);
        }
    }

    /** El mismo esquema, con lo que el equipo confirmo. */
    public EsquemaFisico conConfirmacion(Confirmacion c) {
        return new EsquemaFisico(dialecto, resumen, tablas, script, advertencias, hash, c);
    }

    /**
     * @param vigente         el esquema de hoy es exactamente el confirmado
     * @param scriptConfirmado el DDL que se confirmo, SOLO si ya no esta vigente:
     *                        es lo que permite mostrar que cambio desde entonces
     */
    public record Confirmacion(String hash, long seq, String por, OffsetDateTime en, boolean vigente,
                               String scriptConfirmado) { }

    /**
     * Los numeros de arriba. Con 20 tablas, saber cuantas columnas y cuantas
     * FK hay es lo primero que se mira.
     */
    public record Resumen(int tablas, int columnas, int clavesForaneas, int indices,
                          int restricciones) { }

    /**
     * @param origen    de donde sale la tabla: una clase, o la relacion N:M
     *                  que la genero. Es lo que conecta el esquema con el
     *                  diagrama del que vino
     * @param referidaPor tablas que le apuntan. Sale del recorrido inverso de
     *                  las FK, y es lo que permite navegar el esquema en los
     *                  dos sentidos sin leer el script entero
     * @param claseId   la clase que la produjo; null si la tabla es derivada
     *                  (puente N:M, multivaluado) y no tiene una clase propia
     *                  que editar
     */
    public record Tabla(String nombre, String origen, List<Columna> columnas,
                        List<String> clavePrimaria, List<Indice> indices,
                        List<String> referidaPor, UUID claseId) { }

    /**
     * @param referencia tabla a la que apunta, o null. Sin la columna: para
     *                   navegar alcanza con la tabla
     * @param onDelete   CASCADE, SET NULL o RESTRICT. Es donde se ve la
     *                   diferencia entre composicion y agregacion
     * @param check      la condicion del CHECK --rango o enumeracion--, ya
     *                   escrita como va al DDL
     * @param atributoId el atributo del modelo que produjo la columna, o null
     * @param relacionId la relacion que produjo la FK, o null
     * @param extremo    en una FK de asociacion, el extremo de la relacion al
     *                   que apunta ("SOURCE" o "TARGET"): su multiplicidad la
     *                   hace obligatoria y su rol le da el nombre
     */
    public record Columna(String nombre, String tipo, boolean pk, boolean nullable,
                          boolean unique, String referencia, String onDelete,
                          String restriccionFk, String check,
                          UUID atributoId, UUID relacionId, String extremo) { }

    public record Indice(String nombre, String columna) { }
}
