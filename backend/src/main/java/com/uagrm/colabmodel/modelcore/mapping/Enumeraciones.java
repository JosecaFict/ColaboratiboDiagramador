package com.uagrm.colabmodel.modelcore.mapping;

import com.uagrm.colabmodel.modelcore.metamodel.UmlAttribute;
import com.uagrm.colabmodel.modelcore.metamodel.UmlClass;

import java.util.*;

/**
 * Las enumeraciones del modelo conceptual.
 *
 * En UML una enumeracion es un clasificador, no un tipo primitivo: una clase
 * con el estereotipo «enumeration» cuyos "atributos" son los literales. Otra
 * clase la usa poniendola como tipo de un atributo (`estado: EstadoPrestamo`).
 *
 * No genera tabla. Genera (tabla de tipos de docs/REGLAS-MAPEO.md):
 *   - en PostgreSQL, `varchar` con `CHECK (col IN (...))`;
 *   - en Java, un `enum` y `@Enumerated(EnumType.STRING)`.
 *
 * STRING y no ORDINAL a proposito: con ORDINAL la base guarda la POSICION del
 * literal, y reordenar o insertar uno en el medio cambia en silencio el
 * significado de todas las filas ya guardadas.
 */
public final class Enumeraciones {

    public static final String ESTEREOTIPO = "enumeration";

    /** Un literal mas largo que esto agranda la columna; mas corto, no la achica. */
    private static final int LARGO_MINIMO = 50;

    private Enumeraciones() { }

    public static boolean es(UmlClass c) {
        return c != null && ESTEREOTIPO.equalsIgnoreCase(c.getStereotype());
    }

    /**
     * Nombre UML de cada enumeracion -> sus literales normalizados.
     *
     * Una enumeracion sin literales NO entra al mapa: no hay CHECK posible ni
     * enum de Java que compile, y los atributos que la usan caen a `varchar`
     * con su advertencia.
     */
    public static Map<String, ModeloLogico.Enumeracion> de(List<UmlClass> clases,
                                                           List<UmlAttribute> atributos,
                                                           List<String> advertencias) {
        Map<String, ModeloLogico.Enumeracion> resultado = new LinkedHashMap<>();
        for (UmlClass c : clases) {
            if (c.getDeletedAt() != null || !es(c)) continue;

            LinkedHashSet<String> literales = new LinkedHashSet<>();
            atributos.stream()
                    .filter(a -> a.getClassId().equals(c.getId()))
                    .sorted(Comparator.comparingInt(UmlAttribute::getOrdinal))
                    .forEach(a -> {
                        String literal = literal(a.getName());
                        // "Activo" y "ACTIVO" dan la misma constante: dos veces
                        // la misma en un enum de Java no compila.
                        if (!literales.add(literal)) {
                            advertencias.add("La enumeracion " + c.getName() + " repite el literal "
                                    + literal + " (despues de normalizar \"" + a.getName()
                                    + "\"): se dejo uno solo (Regla 8).");
                        }
                    });

            if (literales.isEmpty()) {
                advertencias.add("La enumeracion " + c.getName() + " no tiene literales: los atributos"
                        + " que la usan quedan como varchar sin restriccion (Regla 10).");
                continue;
            }
            resultado.put(c.getName(), new ModeloLogico.Enumeracion(c.getName(), List.copyOf(literales)));
        }
        return resultado;
    }

    /** "En préstamo" -> "EN_PRESTAMO": valor en la base y constante en Java a la vez. */
    public static String literal(String crudo) {
        return Nombres.normalizado(crudo).toUpperCase(Locale.ROOT);
    }

    /** `varchar(n)` donde entra el literal mas largo. */
    public static String tipoSql(ModeloLogico.Enumeracion e) {
        int largo = e.literales().stream().mapToInt(String::length).max().orElse(0);
        return "varchar(" + Math.max(LARGO_MINIMO, largo) + ")";
    }
}
