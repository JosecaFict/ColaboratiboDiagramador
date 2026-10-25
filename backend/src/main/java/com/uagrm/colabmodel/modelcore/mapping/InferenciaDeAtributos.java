package com.uagrm.colabmodel.modelcore.mapping;

import com.uagrm.colabmodel.modelcore.metamodel.UmlClass;

import java.util.*;

/**
 * Sugiere el tipo y el ROL de un atributo a partir de su nombre.
 *
 * Funcion PURA, igual que la derivacion: entra un nombre (y el tipo si vino
 * escrito), sale una sugerencia con su motivo. Asi la usan igual el panel
 * mientras el usuario escribe, la importacion de SQL/XMI y la lectura de una
 * foto, donde la IA solo tiene que leer nombres y simbolos.
 *
 * SUGIERE, no decide (la misma doctrina que los puntos de ambiguedad): los
 * nombres son ambiguos --`codigo` puede ser numero o texto, `numero` puede ser
 * de telefono-- y por eso cada sugerencia trae su confianza y su motivo, para
 * que el usuario confirme.
 *
 * El ROL y el TIPO se deciden por separado. El rol (identidad, FK, clave
 * natural) sale del nombre aunque el tipo venga escrito: `cliente_id uuid` en
 * un SQL es una FK igual que `idCliente` sin tipo. El tipo sigue la precedencia
 * lo escrito en el modelo > el nombre > por defecto.
 */
public final class InferenciaDeAtributos {

    /** Que es el atributo, mas alla de su tipo. */
    public enum Rol {
        /** Un dato comun de la clase. */
        ATRIBUTO,
        /** La identidad de la propia clase (`idMaterial` en Material): ya la cubre el id UUID. */
        IDENTIDAD,
        /** Un codigo de negocio (`isbn`, `ci`): conviene marcarlo PK, y queda UNIQUE NOT NULL. */
        CLAVE_NATURAL,
        /** Una FK hacia otra clase: en el diagrama de clases va como relacion, no como atributo. */
        RELACION
    }

    public enum Confianza { ALTA, MEDIA, BAJA }

    /**
     * @param claseRelacionada con rol RELACION, la clase a la que apunta
     */
    public record Sugerencia(Rol rol, String dataType, String claseRelacionada, UUID claseRelacionadaId,
                             Confianza confianza, String motivo) {
        static Sugerencia atributo(String tipo, Confianza c, String motivo) {
            return new Sugerencia(Rol.ATRIBUTO, tipo, null, null, c, motivo);
        }
    }

    private InferenciaDeAtributos() { }

    // ----------------------------------------------------------- TIPO ESCRITO

    /** Como se escribe un tipo en otros lados -> el tipo de la tabla de tipos. */
    private static final Map<String, String> SINONIMOS = new HashMap<>();
    static {
        sinonimos("string", "string", "varchar", "nvarchar", "char", "character", "texto", "cadena", "str");
        sinonimos("text", "text", "clob", "longtext", "mediumtext");
        sinonimos("int", "int", "integer", "entero", "smallint", "tinyint", "int4", "serial", "short", "byte");
        sinonimos("long", "long", "bigint", "int8", "bigserial", "biginteger");
        // Dinero y medidas: decimal, nunca float (0.1 + 0.2 != 0.3).
        sinonimos("decimal", "decimal", "numeric", "number", "real", "float", "double", "money",
                "currency", "moneda", "flotante", "bigdecimal");
        sinonimos("boolean", "boolean", "bool", "bit", "logico", "booleano");
        // Los tipos de lenguaje llegan de los XMI de herramientas UML (EA exporta EAJava_LocalDate).
        sinonimos("date", "date", "fecha", "localdate");
        sinonimos("datetime", "datetime", "timestamp", "timestamptz", "fechahora", "localdatetime",
                "offsetdatetime", "instant", "datetime2", "smalldatetime");
        sinonimos("uuid", "uuid", "guid", "uniqueidentifier");
    }

    private static void sinonimos(String tipo, String... formas) {
        for (String f : formas) SINONIMOS.put(f, tipo);
    }

    // --------------------------------------------------------- PISTAS DEL NOMBRE

    /** Numeros que en realidad son identificadores: llevan ceros adelante o guiones. */
    private static final Set<String> NUMEROS_QUE_SON_TEXTO = Set.of(
            "telefono", "celular", "movil", "documento", "ci", "nit", "cuenta", "tarjeta", "serie",
            "pasaporte", "licencia", "placa", "postal", "cp", "dni", "rut", "ruc");
    private static final Set<String> BOOLEANOS_AL_INICIO = Set.of(
            "es", "esta", "tiene", "is", "has", "puede", "permite");
    private static final Set<String> BOOLEANOS = Set.of(
            "activo", "activa", "habilitado", "habilitada", "vigente", "eliminado", "borrado",
            "visible", "disponible", "pagado", "aprobado", "confirmado", "bloqueado");
    private static final Set<String> FECHAS = Set.of("fecha", "fec", "nacimiento", "vencimiento", "date");
    private static final Set<String> DINERO = Set.of(
            "precio", "monto", "costo", "saldo", "importe", "sueldo", "salario", "pago", "tarifa",
            "descuento", "impuesto", "iva", "subtotal", "deuda", "multa", "cuota");
    private static final Set<String> DECIMALES = Set.of(
            "total", "porcentaje", "peso", "altura", "promedio", "nota", "calificacion", "latitud",
            "longitud", "area", "volumen", "temperatura", "tasa");
    private static final Set<String> ENTEROS = Set.of(
            "cantidad", "cant", "stock", "edad", "nro", "num", "numero", "anio", "year", "orden",
            "posicion", "puntos", "capacidad", "paginas", "dias", "horas", "minutos", "unidades",
            "existencias", "piso", "cupo", "cupos", "prioridad", "nivel", "semestre", "creditos");
    private static final Set<String> TEXTOS_LARGOS = Set.of(
            "descripcion", "observacion", "observaciones", "detalle", "comentario", "comentarios",
            "contenido", "resumen", "biografia", "notas");
    private static final Set<String> TEXTOS = Set.of(
            "nombre", "nombres", "apellido", "apellidos", "email", "correo", "direccion", "telefono",
            "celular", "titulo", "url", "usuario", "password", "contrasenia", "genero", "sexo", "ciudad",
            "pais", "codigo", "cod", "ci", "nit", "isbn", "sku", "placa", "marca", "modelo", "color");
    private static final Set<String> CLAVES_NATURALES = Set.of(
            "isbn", "ci", "nit", "sku", "dni", "rut", "ruc", "matricula", "placa", "codigo", "cod",
            "pasaporte");

    /**
     * @param nombre       el nombre del atributo, como venga (`idMaterial`, `fecha_nac`)
     * @param tipoEscrito  el tipo si el modelo lo trae escrito (`real`, `VARCHAR`), o null
     * @param duenio       la clase a la que pertenece, o null
     * @param clases       las clases del diagrama: para reconocer FKs y tipos del modelo
     */
    public static Sugerencia sugerir(String nombre, String tipoEscrito, UmlClass duenio, List<UmlClass> clases) {
        List<String> t = tokens(nombre);
        List<UmlClass> vivas = clases.stream().filter(c -> c.getDeletedAt() == null).toList();
        boolean hayTipoEscrito = tipoEscrito != null && !tipoEscrito.isBlank();

        // 1. El rol por identificador va primero, aunque el tipo venga escrito.
        Sugerencia porId = t.isEmpty() ? null : porIdentificador(t, duenio, vivas);
        if (porId != null && (porId.rol() != Rol.ATRIBUTO || !hayTipoEscrito)) return porId;

        // 2. Lo escrito en el modelo manda sobre el tipo.
        if (hayTipoEscrito) {
            String escrito = tipoEscrito.trim();
            UmlClass delModelo = porNombre(escrito, vivas);
            if (delModelo != null && (Enumeraciones.es(delModelo) || Incrustables.es(delModelo))) {
                return Sugerencia.atributo(delModelo.getName(), Confianza.ALTA,
                        "el tipo " + delModelo.getName() + " esta escrito en el modelo");
            }
            String tipo = SINONIMOS.get(escrito.toLowerCase(Locale.ROOT).replaceAll("\\(.*\\)", "").trim());
            if (tipo != null && esClaveNatural(t, duenio)) {
                return new Sugerencia(Rol.CLAVE_NATURAL, tipo, null, null, Confianza.ALTA,
                        "\"" + nombre + "\" parece un codigo de negocio: marcado como PK queda UNIQUE NOT NULL"
                                + " y la identidad sigue siendo el id UUID (Regla 1)");
            }
            if (tipo != null) {
                return Sugerencia.atributo(tipo, Confianza.ALTA, "\"" + escrito + "\" escrito en el modelo es "
                        + tipo + (tipo.equals("decimal") && !escrito.equalsIgnoreCase("decimal")
                        ? " (decimal y no float: con float, 0.1 + 0.2 no da 0.3)" : ""));
            }
        }
        if (t.isEmpty()) return Sugerencia.atributo("string", Confianza.BAJA, "sin nombre");
        if (porId != null) return porId;

        // 3. El nombre coincide con una enumeracion o un valor embebido del modelo.
        String unido = String.join("_", t);
        for (UmlClass c : vivas) {
            if ((Enumeraciones.es(c) || Incrustables.es(c)) && mismoNombre(unido, Nombres.normalizado(c.getName()))) {
                return Sugerencia.atributo(c.getName(), Confianza.MEDIA, "se llama como "
                        + (Enumeraciones.es(c) ? "la enumeracion " : "el valor embebido ") + c.getName());
            }
        }

        // 4. Codigos de negocio: clave natural.
        if (esClaveNatural(t, duenio)) {
            return new Sugerencia(Rol.CLAVE_NATURAL, "string", null, null, Confianza.MEDIA,
                    "\"" + nombre + "\" parece un codigo de negocio: marcado como PK queda UNIQUE NOT NULL"
                            + " y la identidad sigue siendo el id UUID (Regla 1). Es texto: puede llevar"
                            + " ceros adelante");
        }

        // 5. Por el nombre, de lo mas especifico a lo mas general.
        if (t.stream().anyMatch(ENTEROS::contains) && t.stream().anyMatch(NUMEROS_QUE_SON_TEXTO::contains)) {
            return Sugerencia.atributo("string", Confianza.ALTA,
                    "un numero de " + primero(t, NUMEROS_QUE_SON_TEXTO) + " es texto: lleva ceros adelante"
                            + " y nunca se suma");
        }
        if (BOOLEANOS_AL_INICIO.contains(t.get(0)) && t.size() > 1 || t.size() == 1 && BOOLEANOS.contains(t.get(0))) {
            return Sugerencia.atributo("boolean", Confianza.ALTA, "por el nombre (\"" + t.get(0) + "\")");
        }
        if (t.contains("fecha") && t.contains("hora") || t.get(t.size() - 1).equals("at") || t.contains("timestamp")) {
            return Sugerencia.atributo("datetime", Confianza.ALTA, "por el nombre: fecha con hora");
        }
        if (t.stream().anyMatch(FECHAS::contains)) {
            return Sugerencia.atributo("date", Confianza.ALTA, "por el nombre (\"" + primero(t, FECHAS) + "\")");
        }
        if (t.contains("hora")) {
            return Sugerencia.atributo("datetime", Confianza.MEDIA, "por el nombre (\"hora\")");
        }
        if (t.stream().anyMatch(DINERO::contains)) {
            return Sugerencia.atributo("decimal", Confianza.ALTA, "es dinero (\"" + primero(t, DINERO)
                    + "\"): decimal y no float, que redondea mal");
        }
        if (t.stream().anyMatch(DECIMALES::contains)) {
            return Sugerencia.atributo("decimal", Confianza.MEDIA, "por el nombre (\"" + primero(t, DECIMALES)
                    + "\"): puede llevar decimales");
        }
        if (t.stream().anyMatch(ENTEROS::contains)) {
            return Sugerencia.atributo("int", Confianza.ALTA, "por el nombre (\"" + primero(t, ENTEROS) + "\")");
        }
        if (t.stream().anyMatch(TEXTOS_LARGOS::contains)) {
            return Sugerencia.atributo("text", Confianza.MEDIA, "texto largo (\"" + primero(t, TEXTOS_LARGOS) + "\")");
        }
        if (t.stream().anyMatch(TEXTOS::contains)) {
            return Sugerencia.atributo("string", Confianza.ALTA, "por el nombre (\"" + primero(t, TEXTOS) + "\")");
        }
        return Sugerencia.atributo("string", Confianza.BAJA, "el nombre no da pistas: string por defecto");
    }

    /** `isbn`, `ci`, `codigo`, o `codigo<Clase propia>`. */
    private static boolean esClaveNatural(List<String> t, UmlClass duenio) {
        return t.size() == 1 && CLAVES_NATURALES.contains(t.get(0))
                || t.size() == 2 && CLAVES_NATURALES.contains(t.get(0)) && duenio != null
                   && mismoNombre(t.get(1), Nombres.normalizado(duenio.getName()));
    }

    /** `id`, `idMaterial`, `material_id`, `fk_material`. */
    private static Sugerencia porIdentificador(List<String> t, UmlClass duenio, List<UmlClass> clases) {
        boolean marca = t.contains("id") || t.contains("fk");
        if (!marca) return null;
        List<String> resto = t.stream().filter(x -> !x.equals("id") && !x.equals("fk")).toList();
        if (resto.isEmpty()) {
            return new Sugerencia(Rol.IDENTIDAD, "uuid", null, null, Confianza.ALTA,
                    "es la identidad: la herramienta ya crea el id UUID de cada clase (Regla 1), no hace"
                            + " falta como atributo");
        }
        String objetivo = String.join("_", resto);
        if (duenio != null && mismoNombre(objetivo, Nombres.normalizado(duenio.getName()))) {
            return new Sugerencia(Rol.IDENTIDAD, "uuid", null, null, Confianza.ALTA,
                    "es la identidad de " + duenio.getName() + ": ya la cubre el id UUID (Regla 1)."
                            + " Como atributo la duplicaria");
        }
        for (UmlClass c : clases) {
            if (c.equals(duenio) || Enumeraciones.es(c) || Incrustables.es(c)) continue;
            if (mismoNombre(objetivo, Nombres.normalizado(c.getName()))) {
                return new Sugerencia(Rol.RELACION, "uuid", c.getName(), c.getId(), Confianza.ALTA,
                        "apunta a " + c.getName() + ": en el diagrama de clases una FK es una RELACION, no un"
                                + " atributo. La columna " + Nombres.columnaFk(c.getName())
                                + " la deriva la Regla 3; como atributo quedaria duplicada y sin integridad");
            }
        }
        return Sugerencia.atributo("uuid", Confianza.MEDIA, "parece una FK hacia \"" + objetivo
                + "\", pero no hay ninguna clase con ese nombre en el diagrama. Si falta la clase,"
                + " conviene crearla y relacionarla");
    }

    /** "idMaterial", "ID_Material", "fecha-nac" -> [id, material], [fecha, nac]. */
    static List<String> tokens(String nombre) {
        if (nombre == null || nombre.isBlank()) return List.of();
        // "IDMaterial": la sigla pegada a la palabra siguiente tambien se separa.
        String separado = nombre.trim().replaceAll("([A-Z]+)([A-Z][a-z])", "$1_$2");
        String normal = Nombres.normalizado(separado);
        return Arrays.stream(normal.split("_")).filter(x -> !x.isBlank()).toList();
    }

    /** "libro" y "libros", "material" y "materiales": singular y plural cuentan igual. */
    private static boolean mismoNombre(String a, String b) {
        return a.equals(b) || (a + "s").equals(b) || (a + "es").equals(b)
                || (b + "s").equals(a) || (b + "es").equals(a);
    }

    private static UmlClass porNombre(String nombre, List<UmlClass> clases) {
        return clases.stream().filter(c -> c.getName().equalsIgnoreCase(nombre)).findFirst().orElse(null);
    }

    private static String primero(List<String> tokens, Set<String> conjunto) {
        return tokens.stream().filter(conjunto::contains).findFirst().orElse("");
    }
}
