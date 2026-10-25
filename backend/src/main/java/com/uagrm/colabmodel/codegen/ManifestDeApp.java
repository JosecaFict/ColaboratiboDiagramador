package com.uagrm.colabmodel.codegen;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.*;

/**
 * CONTRATO P2 -> P3 (ADR-011, ADR-021): `app-manifest.json`.
 *
 * ESPEJO de `mobile/lib/domain/app_manifest.dart`. Si cambia una clave aca,
 * cambia alli en el mismo commit; `ManifestDeAppTest` compara lo que sale del
 * generador contra los manifiestos que usan las pruebas del motor de
 * intenciones, asi que una clave renombrada de un solo lado rompe una prueba.
 *
 * Se escribe pensando en quien lo consume: un motor de intenciones que no
 * conoce el dominio. Por eso cada campo dice todo lo que hace falta para
 * RECHAZAR un valor antes de pegarle a la API (tipo, obligatorio, largo,
 * literales, rango) y con que palabras lo nombra una persona (sinonimos).
 *
 * Los datos salen de los mismos campos con que el generador escribe el DTO:
 * el manifiesto no puede aceptar algo que la API rechaza, ni al reves.
 */
public final class ManifestDeApp {

    /** Sube solo si un cliente viejo leeria MAL el archivo nuevo. Agregar claves no la sube. */
    public static final int VERSION = 1;

    /** Spring Boot sirve `static/` en la raiz: el movil lo baja de `GET /app-manifest.json`. */
    public static final String RUTA = "src/main/resources/static/app-manifest.json";

    /**
     * @param nombre      la clave JSON del DTO (`titulo`, `clienteId`)
     * @param etiqueta    como lo escribio la persona en el UML (`título`, `cliente`)
     * @param tipo        vocabulario del contrato, no de Java: ver {@link #tipoDe}
     * @param largoMaximo `varchar(n)`; null si no tiene tope
     * @param literales   solo `enum`: los valores que acepta la API
     * @param destino     `reference`: la entidad apuntada; `embedded`: la clase de valor
     * @param elementos   solo `list`: el tipo de cada elemento
     * @param extras      sinonimos que no salen del nombre: en una referencia, el
     *                    nombre de la entidad apuntada
     */
    public record Campo(String nombre, String etiqueta, String tipo, boolean obligatorio, boolean unico,
                        Integer largoMaximo, List<String> literales, BigDecimal minimo, BigDecimal maximo,
                        String destino, String elementos, List<String> extras) { }

    /**
     * Una relacion navegable desde la entidad.
     *
     * @param campo     del lado duenio: la clave JSON que lleva el id (`clienteId`)
     * @param inversaDe del lado inverso: el campo de `destino` que apunta aca.
     *                  La API no tiene ruta anidada: "los pedidos de un cliente"
     *                  es `GET /api/pedido` filtrado por `clienteId`.
     */
    public record Relacion(String nombre, String destino, boolean muchos, String campo, String inversaDe) { }

    public record Entidad(String nombre, String etiqueta, String ruta, boolean abstracta, String madre,
                          List<Campo> campos, List<Relacion> relaciones) { }

    private ManifestDeApp() { }

    /** Del tipo Java del DTO al vocabulario del contrato. */
    static String tipoDe(String tipoJava, boolean enumerado) {
        if (enumerado) return "enum";
        return switch (tipoJava) {
            case "Integer" -> "integer";
            case "Long" -> "long";
            case "BigDecimal" -> "decimal";
            case "Boolean" -> "boolean";
            case "LocalDate" -> "date";
            case "LocalDateTime" -> "datetime";
            case "UUID" -> "uuid";
            default -> "string";
        };
    }

    /**
     * Sinonimos derivados del nombre: el nombre tal como se escribio y
     * separado en palabras (`fechaPrestamo` -> "fecha prestamo").
     *
     * Aca va el VOCABULARIO del dominio, no la morfologia: plural, genero y
     * tildes los resuelve el motor al comparar, sobre estas palabras y sobre
     * las que se agreguen a mano el dia de la demo (ADR-011).
     */
    static List<String> sinonimos(String etiqueta, List<String> extras) {
        LinkedHashSet<String> s = new LinkedHashSet<>();
        for (String crudo : concat(List.of(etiqueta), extras)) {
            if (crudo == null || crudo.isBlank()) continue;
            String separado = crudo.replaceAll("([\\p{Ll}0-9])(\\p{Lu})", "$1 $2")
                    .replace('_', ' ').replace('-', ' ')
                    .replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
            s.add(separado);
            String junto = crudo.trim().toLowerCase(Locale.ROOT);
            if (!junto.contains(" ")) s.add(junto);
        }
        return List.copyOf(s);
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> r = new ArrayList<>(a);
        r.addAll(b);
        return r;
    }

    static String json(String app, List<Entidad> entidades) {
        Map<String, Object> raiz = new LinkedHashMap<>();
        raiz.put("manifestVersion", VERSION);
        raiz.put("app", app);
        List<Object> lista = new ArrayList<>();
        for (Entidad e : entidades) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", e.nombre());
            m.put("label", e.etiqueta());
            m.put("path", e.ruta());
            m.put("abstract", e.abstracta());
            if (e.madre() != null) m.put("parent", e.madre());
            m.put("actions", e.abstracta()
                    ? List.of("list", "get", "delete")
                    : List.of("list", "get", "create", "update", "delete"));
            m.put("synonyms", sinonimos(e.etiqueta(), List.of()));
            List<Object> campos = new ArrayList<>();
            for (Campo c : e.campos()) campos.add(campo(c));
            m.put("fields", campos);
            List<Object> relaciones = new ArrayList<>();
            for (Relacion r : e.relaciones()) {
                Map<String, Object> rm = new LinkedHashMap<>();
                rm.put("name", r.nombre());
                rm.put("target", r.destino());
                rm.put("many", r.muchos());
                if (r.campo() != null) rm.put("field", r.campo());
                if (r.inversaDe() != null) rm.put("inverseOf", r.inversaDe());
                relaciones.add(rm);
            }
            m.put("relations", relaciones);
            lista.add(m);
        }
        raiz.put("entities", lista);

        DefaultIndenter indentador = new DefaultIndenter("  ", "\n");   // \n fijo: mismo archivo en Windows y en CI
        DefaultPrettyPrinter formato = new DefaultPrettyPrinter()
                .withObjectIndenter(indentador).withArrayIndenter(indentador);
        try {
            return new ObjectMapper().writer(formato).writeValueAsString(raiz) + "\n";
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Un mapa de textos y numeros no puede fallar al serializar", e);
        }
    }

    private static Map<String, Object> campo(Campo c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", c.nombre());
        m.put("label", c.etiqueta());
        m.put("type", c.tipo());
        m.put("required", c.obligatorio());
        m.put("unique", c.unico());
        if (c.largoMaximo() != null) m.put("maxLength", c.largoMaximo());
        if (c.literales() != null) m.put("literals", c.literales());
        if (c.minimo() != null) m.put("min", new BigDecimal(c.minimo().stripTrailingZeros().toPlainString()));
        if (c.maximo() != null) m.put("max", new BigDecimal(c.maximo().stripTrailingZeros().toPlainString()));
        if (c.destino() != null) m.put("target", c.destino());
        if (c.elementos() != null) m.put("items", c.elementos());
        m.put("synonyms", sinonimos(c.etiqueta(), c.extras()));
        return m;
    }
}
