package com.uagrm.colabmodel.modelcore.mapping;

import java.util.List;
import java.util.UUID;

/**
 * Vista Logica: el modelo conceptual proyectado a tablas, con PK y FK.
 *
 * `advertencias` no es decoracion: la Regla 10 exige que lo no mapeable se
 * detecte, se rechace y se LISTE. Una construccion ignorada en silencio es
 * peor que un error.
 */
public record ModeloLogico(List<Tabla> tablas, List<String> advertencias) {

    /**
     * Una enumeracion del modelo conceptual (clase con estereotipo «enumeration»).
     *
     * @param nombre    el nombre de la clase en el UML
     * @param literales ya normalizados (`EN_PRESTAMO`): son a la vez el valor
     *                  que guarda la base y la constante del enum de Java
     */
    public record Enumeracion(String nombre, List<String> literales) { }

    /**
     * Limites de un atributo numerico. Cualquiera de los dos puede faltar.
     *
     * @param entero el tipo es int o long: en Java va @Min/@Max, que solo
     *               aceptan enteros; si no, @DecimalMin/@DecimalMax
     */
    public record Rango(java.math.BigDecimal minimo, java.math.BigDecimal maximo, boolean entero) {

        /** "precio >= 0 AND precio <= 1000", para el CHECK del DDL. */
        public String condicionSql(String columna) {
            List<String> partes = new java.util.ArrayList<>();
            if (minimo != null) partes.add(columna + " >= " + texto(minimo));
            if (maximo != null) partes.add(columna + " <= " + texto(maximo));
            return String.join(" AND ", partes);
        }

        public static String texto(java.math.BigDecimal n) {
            return n.stripTrailingZeros().toPlainString();
        }
    }

    public record Tabla(
            String nombre,
            /** De donde sale: el nombre de la clase, o la relacion que la genero. */
            String origen,
            List<Columna> columnas,
            /** No nulo si la tabla guarda una jerarquia entera (Regla 4, SINGLE_TABLE). */
            TablaUnica tablaUnica,
            /** Valores embebidos (Regla 5): que columnas son de que objeto de valor. */
            List<Incrustado> incrustados,
            /**
             * La clase que produjo esta tabla, o null si la tabla es derivada
             * (puente N:M, multivaluado). En SINGLE_TABLE, la raiz.
             *
             * Es trazabilidad, igual que `Columna.relationId`: la Vista Fisica
             * edita A TRAVES del modelo conceptual, y para eso tiene que saber
             * que clase renombrar o a cual agregarle un atributo sin volver a
             * adivinarlo por el nombre de la tabla.
             */
            UUID claseId
    ) {
        public Tabla(String nombre, String origen, List<Columna> columnas) {
            this(nombre, origen, columnas, null, List.of(), null);
        }
    }

    /**
     * Un objeto de valor guardado dentro de la tabla de su duenio (Regla 5).
     *
     * @param campo       como se llama en el duenio: el atributo (`direccion`)
     *                    o el rol / la clase de la composicion
     * @param clase       la clase del valor (`Direccion`): en Java, el @Embeddable
     * @param obligatorio el duenio no puede quedar sin este valor
     * @param columnas    que atributo del valor vive en que columna del duenio
     */
    public record Incrustado(String campo, String clase, boolean obligatorio,
                             List<ColumnaIncrustada> columnas) { }

    /** `calle` de Direccion vive en la columna `direccion_calle` del duenio. */
    public record ColumnaIncrustada(String atributo, String columna) { }

    /**
     * Una jerarquia de herencia guardada en UNA tabla.
     *
     * @param discriminador columna que dice de que clase es cada fila
     * @param miembros      la raiz primero; cada miembro dice que columnas son suyas
     */
    public record TablaUnica(String discriminador, List<Miembro> miembros) { }

    /**
     * @param madre        null en la raiz
     * @param valor        lo que se guarda en el discriminador; null si la clase
     *                     es abstracta, porque ninguna fila puede ser de ella
     * @param columnas     las que declara esta clase (la raiz incluye `id`)
     * @param obligatorias las que el modelo marco obligatorias: en la base
     *                     tienen que admitir NULL, asi que el NOT NULL queda
     *                     solo en el DTO
     */
    public record Miembro(String clase, String madre, String valor,
                          List<String> columnas, List<String> obligatorias) { }

    public record Columna(
            String nombre,
            String tipo,
            boolean pk,
            boolean nullable,
            boolean unique,
            /** "tabla(columna)" si es clave foranea; null si no lo es. */
            String referencia,
            /**
             * Que le pasa a esta fila cuando se borra la que referencia:
             * CASCADE, SET NULL o RESTRICT. Null si no es clave foranea.
             *
             * Es donde se nota la diferencia entre composicion y agregacion:
             * en la composicion la parte no vive sin el todo, en la agregacion
             * sobrevive. Sin esto, las dos generaban exactamente la misma FK.
             */
            String onDelete,

            /**
             * Relacion del modelo conceptual que produjo esta columna, o null.
             *
             * Es trazabilidad, no adorno: `codegen` la necesita para saber si
             * emitir `@ManyToOne` con `LAZY` y que cascade corresponde (Regla
             * 7). Sin esto tendria que volver a decidir por su cuenta de que
             * lado va la FK, y esa segunda copia de la regla se desincroniza.
             */
            UUID relationId,

            /**
             * Si la columna guarda un valor de una enumeracion del modelo: sus
             * literales. Null en cualquier otra columna.
             *
             * Es lo que produce el CHECK en el DDL y el @Enumerated en la Entity.
             * Viajan juntos a proposito: si la base aceptara un literal que el
             * enum de Java no conoce, leer esa fila tiraria una excepcion.
             */
            Enumeracion enumeracion,

            /** Limites de una columna numerica (Regla 9). Null si no declara ninguno. */
            Rango rango,

            /**
             * Atributo del modelo conceptual que produjo esta columna, o null
             * (el `id` surrogado, una FK, el discriminador).
             *
             * Trazabilidad, igual que `relationId`: editar la columna en la
             * Vista Fisica es editar ESTE atributo. Sin el id habria que
             * reconocerlo por el nombre de la columna, que no alcanza: la
             * desambiguacion (`_2`), los prefijos de un valor embebido y las
             * subclases fundidas en SINGLE_TABLE lo cambian.
             */
            UUID atributoId,

            /**
             * En una FK de asociacion, el extremo de la relacion al que APUNTA:
             * "SOURCE" o "TARGET". Null en cualquier otra columna.
             *
             * Es lo que la Regla 3 decidio y la Vista Fisica no puede volver a
             * decidir por su cuenta: la multiplicidad de ese extremo es la que
             * hace a la FK obligatoria u opcional, y su rol es el que le da el
             * nombre (`prestatario_id`).
             */
            String extremo
    ) {
        public Columna(String nombre, String tipo, boolean pk, boolean nullable, boolean unique,
                       String referencia, String onDelete, UUID relationId) {
            this(nombre, tipo, pk, nullable, unique, referencia, onDelete, relationId, null, null, null, null);
        }

        /** La misma columna, restringida a los literales de una enumeracion. */
        public Columna conEnumeracion(Enumeracion e) {
            return new Columna(nombre, tipo, pk, nullable, unique, referencia, onDelete, relationId, e, rango,
                    atributoId, extremo);
        }

        /** La misma columna, limitada a un rango. */
        public Columna conRango(Rango r) {
            return new Columna(nombre, tipo, pk, nullable, unique, referencia, onDelete, relationId, enumeracion, r,
                    atributoId, extremo);
        }

        /** La misma columna, sabiendo de que atributo del modelo sale. */
        public Columna delAtributo(UUID id) {
            return new Columna(nombre, tipo, pk, nullable, unique, referencia, onDelete, relationId, enumeracion,
                    rango, id, extremo);
        }

        /** La misma FK, sabiendo a que extremo de su relacion apunta. */
        public Columna haciaExtremo(String e) {
            return new Columna(nombre, tipo, pk, nullable, unique, referencia, onDelete, relationId, enumeracion,
                    rango, atributoId, e);
        }

        /** La misma columna con otro nombre y otra nulabilidad, sin perder sus restricciones. */
        public Columna como(String otroNombre, boolean esPk, boolean admiteNull) {
            return new Columna(otroNombre, tipo, esPk, admiteNull, unique, referencia, onDelete, relationId,
                    enumeracion, rango, atributoId, extremo);
        }

        /** FK con la regla de borrado que corresponde al tipo de relacion. */
        public static Columna fk(String nombre, boolean nullable, boolean unique,
                                 String referencia, String onDelete, UUID relationId) {
            return new Columna(nombre, "uuid", false, nullable, unique, referencia, onDelete, relationId);
        }

        public static Columna simple(String nombre, String tipo, boolean pk, boolean nullable,
                                     boolean unique) {
            return new Columna(nombre, tipo, pk, nullable, unique, null, null, null);
        }
    }
}
