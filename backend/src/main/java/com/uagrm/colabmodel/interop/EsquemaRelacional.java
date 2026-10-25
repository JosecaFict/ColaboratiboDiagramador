package com.uagrm.colabmodel.interop;

import com.uagrm.colabmodel.modelcore.mapping.Nombres;
import com.uagrm.colabmodel.modelcore.metamodel.RelationKind;

import java.util.*;

/**
 * Tablas, columnas y claves, ya leidas, y la ingenieria inversa que las
 * vuelve clases y relaciones (ADR-018).
 *
 * Vive aparte de LectorSql porque no es del SQL: el modelo de datos de
 * Enterprise Architect («table», «column», «PK», «FK») dice lo mismo en XMI.
 * Si cada lector tuviera su copia, un DDL y un XMI con las mismas tablas
 * podrian importarse distinto.
 *
 * Reconstruye solo lo estructural:
 *   - PK de una columna que ademas es FK      -> generalizacion (JOINED)
 *   - PK de exactamente dos FK, sin mas nada  -> relacion N:M (la tabla desaparece)
 *   - FK con UNIQUE / sin UNIQUE              -> 1:1 / 1:N; NOT NULL decide el "1" o "0..1"
 *   - ON DELETE CASCADE / SET NULL / otro     -> composicion / agregacion / asociacion
 * Lo demas se declara, y donde hay una senal debil se deja una pista.
 */
final class EsquemaRelacional {

    private static final Set<String> NOMBRES_DE_DISCRIMINADOR = Set.of(
            "tipo", "type", "discriminador", "discriminator", "dtype", "clase", "kind");
    /** Tipos con argumentos que son exactamente los que emite el generador: no pierden nada. */
    private static final Set<String> TIPOS_SIN_PERDIDA = Set.of("varchar(255)", "numeric(19,2)");

    private EsquemaRelacional() { }

    record Referencia(String tabla, String alBorrar) { }

    static final class Columna {
        final String nombre;
        String tipo = "";
        boolean nula = true;
        boolean unica;
        boolean pk;
        Referencia referencia;
        List<String> literales;

        Columna(String nombre) {
            this.nombre = nombre;
        }
    }

    static final class Tabla {
        final String nombre;
        final Map<String, Columna> columnas = new LinkedHashMap<>();
        int checksIgnorados;

        Tabla(String nombre) {
            this.nombre = nombre;
        }

        Optional<Columna> columna(String nombre) {
            return Optional.ofNullable(columnas.get(nombre));
        }

        void marcarPk(String nombre) {
            columna(nombre).ifPresent(c -> {
                c.pk = true;
                c.nula = false;
            });
        }

        List<Columna> pk() {
            return columnas.values().stream().filter(c -> c.pk).toList();
        }
    }

    static ModeloLeido reconstruir(String formato, Map<String, Tabla> tablas, List<String> advertencias) {
        List<ModeloLeido.ClaseLeida> clases = new ArrayList<>();
        List<ModeloLeido.RelacionLeida> relaciones = new ArrayList<>();
        agregarA(tablas, clases, relaciones, advertencias);
        return new ModeloLeido(formato, List.copyOf(clases), List.copyOf(relaciones), List.copyOf(advertencias));
    }

    /** Lo mismo que reconstruir, sumando a listas que ya tienen otras clases (un XMI puede mezclar perfiles). */
    static void agregarA(Map<String, Tabla> tablas, List<ModeloLeido.ClaseLeida> clases,
                         List<ModeloLeido.RelacionLeida> relaciones, List<String> advertencias) {
        Set<String> puentes = new HashSet<>();

        for (Tabla t : tablas.values()) {
            List<Columna> pk = t.pk();
            if (pk.size() == 2 && t.columnas.size() == 2 && pk.stream().allMatch(c -> resuelve(c, tablas))) {
                puentes.add(t.nombre);
                relaciones.add(relacionMuchosAMuchos(t, pk.get(0), pk.get(1), advertencias));
            }
        }

        for (Tabla t : tablas.values()) {
            if (puentes.contains(t.nombre)) continue;
            List<Columna> pk = t.pk();

            if (pk.size() == 1 && pk.get(0).referencia != null) {
                Columna id = pk.get(0);
                if (resuelve(id, tablas)) {
                    relaciones.add(new ModeloLeido.RelacionLeida(t.nombre, id.referencia.tabla(),
                            RelationKind.GENERALIZATION, null, null, null, null, null, false));
                    advertencias.add(t.nombre + " se leyo como subclase de " + id.referencia.tabla()
                            + " (herencia JOINED): su clave primaria es a la vez una FK hacia " + id.referencia.tabla()
                            + ". El mismo esquema puede ser una relacion 1:1 con clave compartida; si es eso, cambiala"
                            + " antes de confirmar. Tampoco se sabe si " + id.referencia.tabla() + " es abstracta.");
                } else {
                    advertencias.add(t.nombre + "." + id.nombre + " es clave primaria y referencia a "
                            + id.referencia.tabla() + ", que no esta en el archivo: se ignora esa referencia.");
                }
            } else if (pk.size() > 1) {
                declararClaveCompuesta(t, pk, advertencias);
            }

            clases.add(clase(t, pk, advertencias));

            boolean debil = pk.size() > 1 && pk.stream().anyMatch(c -> c.referencia == null);
            for (Columna c : t.columnas.values()) {
                if (c.referencia == null || (pk.size() == 1 && c.pk)) continue;
                if (!resuelve(c, tablas)) {
                    advertencias.add(t.nombre + "." + c.nombre + " referencia a " + c.referencia.tabla()
                            + ", que no esta en el archivo: no se crea esa relacion.");
                    continue;
                }
                relaciones.add(relacionDeFk(t, c, debil && c.pk));
            }
            if (t.checksIgnorados > 0) {
                advertencias.add("Tabla " + t.nombre + ": " + t.checksIgnorados + (t.checksIgnorados == 1
                        ? " restriccion CHECK no se importa" : " restricciones CHECK no se importan")
                        + " (los rangos se vuelven a cargar en el panel, Regla 9).");
            }
        }
    }

    private static ModeloLeido.ClaseLeida clase(Tabla t, List<Columna> pk, List<String> advertencias) {
        List<ModeloLeido.AtributoLeido> atributos = new ArrayList<>();
        List<String> perdidas = new ArrayList<>();
        boolean compuesta = pk.size() > 1;
        for (Columna c : t.columnas.values()) {
            // Regla 3: una FK es una relacion, nunca un atributo.
            if (c.referencia != null) continue;
            // Regla 1: el id UUID es la identidad que la herramienta ya pone sola.
            if (c.pk && !compuesta && c.nombre.equalsIgnoreCase("id")
                    && c.tipo.toLowerCase(Locale.ROOT).startsWith("uuid")) continue;

            boolean multivaluado = c.tipo.endsWith("[]");
            String tipo = multivaluado ? c.tipo.substring(0, c.tipo.length() - 2) : c.tipo;
            if (tipo.contains("(") && !TIPOS_SIN_PERDIDA.contains(tipo.toLowerCase(Locale.ROOT))) {
                perdidas.add(c.nombre + " " + tipo);
            }
            // En una PK compuesta, la parte propia es una clave parcial (Regla 11),
            // no una clave natural: no es unica por si sola.
            atributos.add(new ModeloLeido.AtributoLeido(c.nombre, tipo.isEmpty() ? null : tipo, c.pk && !compuesta,
                    !c.nula, c.unica, multivaluado, false, c.pk && compuesta));
            pista(t, c, advertencias);
        }
        if (!perdidas.isEmpty()) {
            advertencias.add("Tabla " + t.nombre + ": el largo o la precision de " + String.join(", ", perdidas)
                    + " no se importa; el atributo toma el tipo con su tamano por defecto.");
        }
        pistaDeEmbebido(t, advertencias);
        return new ModeloLeido.ClaseLeida(t.nombre, t.nombre, null, false, List.copyOf(atributos), List.of());
    }

    /**
     * FK de `t` hacia O. La relacion se arma O -> t con la multiplicidad de O
     * diciendo si la FK admite NULL, y la de t diciendo si es UNIQUE: es la
     * forma en que la Regla 3 vuelve a poner la misma FK en la misma tabla.
     */
    private static ModeloLeido.RelacionLeida relacionDeFk(Tabla t, Columna c, boolean identificadora) {
        String destino = c.referencia.tabla();
        String rol = Nombres.columnaFk(destino).equalsIgnoreCase(c.nombre) ? null : rolDe(c.nombre);
        return new ModeloLeido.RelacionLeida(destino, t.nombre, tipoSegunBorrado(c.referencia.alBorrar()), null,
                c.nula ? "0..1" : "1", c.unica ? "1" : "0..*", rol, null, identificadora);
    }

    private static ModeloLeido.RelacionLeida relacionMuchosAMuchos(Tabla t, Columna a, Columna b,
                                                                  List<String> advertencias) {
        boolean reflexiva = a.referencia.tabla().equals(b.referencia.tabla());
        // La Regla 3 nombra la tabla puente "origen_destino": se elige el orden
        // que la vuelve a nombrar igual, si alguno lo hace.
        if (!reflexiva && t.nombre.equals(b.referencia.tabla() + "_" + a.referencia.tabla())) {
            Columna x = a;
            a = b;
            b = x;
        }
        String rolA = reflexiva ? rolDe(a.nombre) : null;
        String rolB = reflexiva ? rolDe(b.nombre) : null;
        String regenerada = reflexiva
                ? Nombres.identificador(a.referencia.tabla()) + "_" + Nombres.identificador(rolB)
                : a.referencia.tabla() + "_" + b.referencia.tabla();
        advertencias.add("La tabla " + t.nombre + " se leyo como una relacion muchos a muchos entre "
                + a.referencia.tabla() + " y " + b.referencia.tabla() + ": su clave primaria son esas dos FK y no"
                + " tiene columnas propias."
                + (regenerada.equals(t.nombre) ? "" : " Al generar se va a llamar " + regenerada + "."));
        return new ModeloLeido.RelacionLeida(a.referencia.tabla(), b.referencia.tabla(), RelationKind.ASSOCIATION,
                null, "0..*", "0..*", rolA, rolB, false);
    }

    private static void declararClaveCompuesta(Tabla t, List<Columna> pk, List<String> advertencias) {
        List<String> nombres = pk.stream().map(c -> c.nombre).toList();
        boolean todasFk = pk.stream().allMatch(c -> c.referencia != null);
        advertencias.add(todasFk
                ? "La tabla " + t.nombre + " tiene clave primaria compuesta " + nombres + " y columnas propias:"
                  + " se lee como clase asociativa con id propio. Se pierde que el par no se pueda repetir"
                  + " (punto de ambiguedad A-4)."
                : "La tabla " + t.nombre + " tiene clave primaria compuesta " + nombres + " con parte propia:"
                  + " parece una entidad debil. Se importa con la clave parcial y la relacion identificadora"
                  + " marcadas, pero la clase entra como fuerte; marcala debil para recuperar la PK compuesta"
                  + " (Regla 11).");
    }

    /** Senales debiles: se avisan, no se aplican. Sugerir y confirmar, nunca decidir solo. */
    private static void pista(Tabla t, Columna c, List<String> advertencias) {
        if (c.literales == null) return;
        String valores = String.join(", ", c.literales);
        advertencias.add(NOMBRES_DE_DISCRIMINADOR.contains(c.nombre.toLowerCase(Locale.ROOT))
                ? t.nombre + "." + c.nombre + " solo admite (" + valores + "): puede ser el discriminador de una"
                  + " jerarquia guardada en una sola tabla (SINGLE_TABLE). El esquema no dice que columnas son de"
                  + " que subclase, asi que se importa como una clase; si es una jerarquia, armala y marcala en la raiz."
                : t.nombre + "." + c.nombre + " solo admite (" + valores + "): puede ser una enumeracion. Se importa"
                  + " como texto; para recuperar el CHECK, crea la «enumeration» y usala como tipo.");
    }

    private static void pistaDeEmbebido(Tabla t, List<String> advertencias) {
        Map<String, List<String>> porPrefijo = new LinkedHashMap<>();
        for (Columna c : t.columnas.values()) {
            if (c.referencia != null || c.pk) continue;
            int guion = c.nombre.indexOf('_');
            if (guion <= 0 || guion == c.nombre.length() - 1) continue;
            porPrefijo.computeIfAbsent(c.nombre.substring(0, guion), k -> new ArrayList<>()).add(c.nombre);
        }
        porPrefijo.forEach((prefijo, cols) -> {
            if (cols.size() < 2) return;
            advertencias.add("Tabla " + t.nombre + ": " + String.join(", ", cols) + " comparten el prefijo "
                    + prefijo + "_. Si son un valor embebido, crea la clase «embeddable» y usala como tipo de un"
                    + " atributo " + prefijo + " (Regla 5).");
        });
    }

    static RelationKind tipoSegunBorrado(String alBorrar) {
        if (alBorrar == null) return RelationKind.ASSOCIATION;
        return switch (alBorrar.trim().toUpperCase(Locale.ROOT)) {
            case "CASCADE" -> RelationKind.COMPOSITION;
            case "SET NULL" -> RelationKind.AGGREGATION;
            default -> RelationKind.ASSOCIATION;
        };
    }

    private static String rolDe(String columna) {
        return columna.toLowerCase(Locale.ROOT).endsWith("_id") && columna.length() > 3
                ? columna.substring(0, columna.length() - 3) : columna;
    }

    private static boolean resuelve(Columna c, Map<String, Tabla> tablas) {
        return c.referencia != null && tablas.containsKey(c.referencia.tabla());
    }
}
