package com.uagrm.colabmodel.codegen;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.uagrm.colabmodel.codegen.GeneradorSpringBoot.Asociacion;
import com.uagrm.colabmodel.codegen.GeneradorSpringBoot.Campo;
import com.uagrm.colabmodel.codegen.GeneradorSpringBoot.Coleccion;
import com.uagrm.colabmodel.codegen.GeneradorSpringBoot.ColeccionDeValores;
import com.uagrm.colabmodel.codegen.GeneradorSpringBoot.Meta;
import com.uagrm.colabmodel.codegen.GeneradorSpringBoot.Unidad;
import com.uagrm.colabmodel.modelcore.mapping.ModeloDocumental;
import com.uagrm.colabmodel.modelcore.mapping.ModeloLogico;
import com.uagrm.colabmodel.modelcore.metamodel.RelationKind;
import com.uagrm.colabmodel.modelcore.metamodel.UmlRelationship;

import java.util.*;

/**
 * Como se guarda cada entidad en MongoDB (ADR-035). Las reglas, en el orden
 * de DISENO-FASE-2.md §A:
 *
 *   - Composicion: la parte va INCRUSTADA en el documento del todo. Si la
 *     parte tambien la referencia otra clase, va en su propia coleccion y se
 *     avisa: algo incrustado no se puede apuntar desde afuera.
 *   - Asociacion / agregacion 1:N o N:1, y 1:1: referencia por id en el lado
 *     que tiene la FK en la Vista Logica. Es la misma columna, con el mismo
 *     nombre que viaja en el DTO (`clienteId`).
 *   - N:M: arreglo de ids en el primer extremo de la relacion.
 *   - Herencia: una sola coleccion para la jerarquia, discriminada por `_tipo`.
 *   - Clase asociativa: coleccion propia con sus dos referencias.
 *
 * Trabaja sobre las mismas {@link Unidad} que el generador ya armo, asi que
 * los nombres de campo son los del DTO y la vista no puede contar otra cosa
 * que lo que el zip guarda.
 */
final class MapeoDocumental {

    /** Donde va la vista dentro del zip MongoDB. */
    static final String RUTA = "docs/modelo-documental.json";

    /** Una parte incrustada en su todo. */
    record Incrustacion(Unidad parte, Unidad todo, String arreglo, String campoPadre, boolean multiple) { }

    /** Una N:M guardada como arreglo de ids en `duenia`. */
    record ArregloDeIds(Unidad duenia, Unidad destino, String campo) { }

    private final Map<String, Unidad> porClase = new LinkedHashMap<>();
    private final Map<String, Incrustacion> incrustadas = new LinkedHashMap<>();
    private final List<ArregloDeIds> arreglos = new ArrayList<>();
    private final List<String> advertencias = new ArrayList<>();
    private ModeloDocumental vista;

    private MapeoDocumental() { }

    static MapeoDocumental de(ModeloLogico logico, List<Unidad> unidades, Map<UUID, UmlRelationship> porRelacion,
                              Map<String, String> claseDeTabla, Map<String, List<Campo>> camposDeValores,
                              Meta meta) {
        MapeoDocumental m = new MapeoDocumental();
        unidades.forEach(u -> m.porClase.put(u.clase(), u));
        m.decidirIncrustaciones(logico);
        m.decidirArreglos(logico, porRelacion, claseDeTabla);
        m.vista = m.armarVista(meta);
        return m;
    }

    // ------------------------------------------------------------ CONSULTAS

    ModeloDocumental vista() { return vista; }

    /** La incrustacion de esta clase, si es una parte que vive dentro de otro documento. */
    Optional<Incrustacion> incrustacionDe(String clase) { return Optional.ofNullable(incrustadas.get(clase)); }

    /** Las partes que viven dentro de esta clase. */
    List<Incrustacion> partesDe(String clase) {
        return incrustadas.values().stream().filter(i -> i.todo().clase().equals(clase)).toList();
    }

    List<ArregloDeIds> arreglosDe(String clase) {
        return arreglos.stream().filter(a -> a.duenia().clase().equals(clase)).toList();
    }

    List<ArregloDeIds> arreglos() { return arreglos; }

    /** La raiz de la jerarquia de esta clase (ella misma si no hereda). */
    Unidad raiz(String clase) {
        Unidad u = porClase.get(clase);
        Set<String> vistas = new HashSet<>();
        while (u != null && u.herencia().madre() != null && vistas.add(u.clase())) {
            Unidad madre = porClase.get(u.herencia().madre());
            if (madre == null) break;
            u = madre;
        }
        return u;
    }

    /** La clase y todas sus descendientes: los `_tipo` que su repositorio tiene que ver. */
    List<String> conDescendientes(String clase) {
        List<String> salida = new ArrayList<>(List.of(clase));
        for (int i = 0; i < salida.size(); i++) {
            String actual = salida.get(i);
            for (Unidad u : porClase.values()) {
                if (actual.equals(u.herencia().madre()) && !salida.contains(u.clase())) salida.add(u.clase());
            }
        }
        return salida;
    }

    /** La clase y sus ancestros: quien apunte a cualquiera de ellos apunta tambien a esta fila. */
    List<String> conAncestros(String clase) {
        List<String> salida = new ArrayList<>();
        Unidad u = porClase.get(clase);
        while (u != null && !salida.contains(u.clase())) {
            salida.add(u.clase());
            u = u.herencia().madre() == null ? null : porClase.get(u.herencia().madre());
        }
        return salida;
    }

    boolean enJerarquia(String clase) {
        Unidad u = porClase.get(clase);
        if (u == null) return false;
        return u.herencia().madre() != null
                || porClase.values().stream().anyMatch(o -> clase.equals(o.herencia().madre()));
    }

    /** El nombre de la coleccion donde viven los documentos de esta clase. */
    String coleccionDe(String clase) {
        Unidad r = raiz(clase);
        return r == null ? plural(clase.toLowerCase(Locale.ROOT)) : plural(r.tabla());
    }

    // ----------------------------------------------------------- DECISIONES

    private void decidirIncrustaciones(ModeloLogico logico) {
        Map<String, Incrustacion> candidatas = new LinkedHashMap<>();
        for (Unidad parte : porClase.values()) {
            List<Asociacion> composiciones = parte.asociacionesPropias().stream()
                    .filter(a -> a.kind() == RelationKind.COMPOSITION && !a.claseDestino().equals(parte.clase()))
                    .toList();
            if (composiciones.isEmpty()) continue;
            Asociacion a = composiciones.get(0);
            Unidad todo = porClase.get(a.claseDestino());
            String motivo = null;
            if (composiciones.size() > 1) motivo = "es parte de mas de un todo";
            else if (todo == null) motivo = "su todo no genera coleccion";
            else if (enJerarquia(parte.clase()) || enJerarquia(todo.clase())) motivo = "participa de una herencia";
            else if (parte.campos().stream().anyMatch(c -> !c.esId() && c.unico()))
                motivo = "tiene atributos unicos que requieren un indice propio";
            else if (parte.asociacionesPropias().stream().anyMatch(otra -> otra != a && otra.unoAUno()))
                motivo = "tiene referencias uno a uno que requieren un indice unico propio";
            else if (referenciadaPorOtros(parte, a, logico)) motivo = "otra clase tambien la referencia";
            if (motivo != null) {
                advertencias.add("La composicion " + a.claseDestino() + " -> " + parte.clase() + " no se incrusta: "
                        + parte.clase() + " " + motivo + ", asi que va en su propia coleccion con la referencia "
                        + a.campo() + "Id. Borrar un " + a.claseDestino() + " que todavia tiene partes responde 409:"
                        + " primero hay que eliminar sus partes (sin transacciones no se borran en cascada).");
                continue;
            }
            String arreglo = todo.colecciones().stream()
                    .filter(c -> c.claseElemento().equals(parte.clase()) && c.mappedBy().equals(a.campo()))
                    .map(Coleccion::campo).findFirst()
                    .orElse(NombresJava.campo(parte.clase()) + (a.unoAUno() ? "" : "s"));
            candidatas.put(parte.clase(), new Incrustacion(parte, todo, arreglo, a.campo() + "Id", !a.unoAUno()));
        }
        // Un solo nivel: si un todo es a la vez parte de otro, se queda en su
        // coleccion (y sus partes se incrustan en el). Anidar dos niveles
        // multiplica lo que hay que reescribir en cada cambio.
        for (Incrustacion i : candidatas.values()) {
            if (candidatas.containsKey(i.todo().clase())) continue;
            incrustadas.put(i.parte().clase(), i);
        }
        for (Incrustacion i : incrustadas.values()) {
            advertencias.add("La parte " + i.parte().clase() + " va incrustada en " + i.todo().clase()
                    + ": no se puede trasladar a otro " + i.todo().clase() + " (responde 409 sin escribir nada);"
                    + " hay que borrarla y crearla de nuevo en el destino.");
        }
        for (Incrustacion i : candidatas.values()) {
            if (!incrustadas.containsKey(i.parte().clase())) {
                advertencias.add("La composicion " + i.todo().clase() + " -> " + i.parte().clase()
                        + " no se incrusta: " + i.parte().clase() + " ya tiene partes incrustadas y no se anidan"
                        + " dos niveles, asi que va en su propia coleccion.");
            }
        }
    }

    /**
     * Si alguna columna, fuera de la propia composicion y de sus colecciones
     * de valores, apunta a la tabla de la parte. Incluye las tablas puente de
     * las N:M y las referencias a si misma.
     */
    private static boolean referenciadaPorOtros(Unidad parte, Asociacion composicion, ModeloLogico logico) {
        Set<String> propiasDeValores = new HashSet<>();
        for (ColeccionDeValores v : parte.valoresPropios()) propiasDeValores.add(v.tabla());
        for (ModeloLogico.Tabla t : logico.tablas()) {
            if (propiasDeValores.contains(t.nombre())) continue;
            for (ModeloLogico.Columna c : t.columnas()) {
                if (c.referencia() == null) continue;
                String apuntada = c.referencia().substring(0, c.referencia().indexOf('('));
                if (!apuntada.equals(parte.tablaFisica())) continue;
                boolean esLaComposicion = t.nombre().equals(parte.tablaFisica())
                        && c.nombre().equals(composicion.columna());
                if (!esLaComposicion && !(c.pk() && t.nombre().equals(parte.tablaFisica()))) return true;
            }
        }
        return false;
    }

    /**
     * Las N:M quedaron como tabla puente sin Entity (o como clase asociativa,
     * que ya es una unidad con sus dos referencias). La puente se guarda como
     * arreglo de ids en el primer extremo.
     */
    private void decidirArreglos(ModeloLogico logico, Map<UUID, UmlRelationship> porRelacion,
                                 Map<String, String> claseDeTabla) {
        Set<String> tablasConUnidad = new HashSet<>();
        Set<String> tablasDeValores = new HashSet<>();
        for (Unidad u : porClase.values()) {
            tablasConUnidad.add(u.tablaFisica());
            u.valoresPropios().forEach(v -> tablasDeValores.add(v.tabla()));
        }
        Set<UUID> vistas = new HashSet<>();
        for (ModeloLogico.Tabla t : logico.tablas()) {
            if (tablasConUnidad.contains(t.nombre()) || tablasDeValores.contains(t.nombre())) continue;
            for (ModeloLogico.Columna c : t.columnas()) {
                if (c.relationId() == null || !vistas.add(c.relationId())) continue;
                UmlRelationship r = porRelacion.get(c.relationId());
                if (r == null || r.getKind() == RelationKind.GENERALIZATION) continue;
                List<String> extremos = t.columnas().stream()
                        .filter(x -> c.relationId().equals(x.relationId()) && x.referencia() != null)
                        .map(x -> x.referencia().substring(0, x.referencia().indexOf('(')))
                        .toList();
                if (extremos.size() != 2) continue;
                Unidad primero = porClase.get(claseDeTabla.get(extremos.get(0)));
                Unidad segundo = porClase.get(claseDeTabla.get(extremos.get(1)));
                if (primero == null || segundo == null) continue;
                String campo = NombresJava.campo(segundo.clase()) + "Ids";
                arreglos.add(new ArregloDeIds(primero, segundo, campo));
                advertencias.add("La N:M " + primero.clase() + " - " + segundo.clase() + " se guarda como arreglo de"
                        + " ids `" + campo + "` en " + primero.clase() + ". La API todavia no lo expone, igual que"
                        + " en PostgreSQL, donde su tabla puente no genera Entity.");
            }
        }
    }

    // ------------------------------------------------------------- VISTA

    private ModeloDocumental armarVista(Meta meta) {
        List<ModeloDocumental.Coleccion> colecciones = new ArrayList<>();
        for (Unidad u : porClase.values()) {
            if (incrustadas.containsKey(u.clase()) || u.herencia().madre() != null) continue;
            List<String> miembros = conDescendientes(u.clase());

            List<ModeloDocumental.Campo> campos = new ArrayList<>();
            campos.add(new ModeloDocumental.Campo("id", "uuid", true));
            List<ModeloDocumental.Incrustado> incrustados = new ArrayList<>();
            List<ModeloDocumental.Referencia> referencias = new ArrayList<>();
            for (String miembro : miembros) {
                Unidad m = porClase.get(miembro);
                for (Campo c : m.propios()) {
                    if (c.esId()) continue;
                    campos.add(new ModeloDocumental.Campo(c.nombreJava(), tipoDe(c), !c.nullable() || c.obligatorio()));
                }
                for (ColeccionDeValores v : m.valoresPropios()) {
                    campos.add(new ModeloDocumental.Campo(v.campo(), tipoDeJava(v.tipoElemento(), v.enumerado()) + "[]",
                            false));
                }
                for (ModeloLogico.Incrustado i : m.incrustadosPropios()) {
                    incrustados.add(new ModeloDocumental.Incrustado(NombresJava.campo(i.campo()),
                            NombresJava.clase(i.clase()), false));
                }
                for (Incrustacion i : partesDe(miembro)) {
                    incrustados.add(new ModeloDocumental.Incrustado(i.arreglo(), i.parte().clase(), i.multiple()));
                }
                for (Asociacion a : m.asociacionesPropias()) {
                    referencias.add(new ModeloDocumental.Referencia(a.campo() + "Id", coleccionDe(a.claseDestino()),
                            false));
                }
                for (ArregloDeIds a : arreglosDe(miembro)) {
                    referencias.add(new ModeloDocumental.Referencia(a.campo(), coleccionDe(a.destino().clase()), true));
                }
            }
            campos.add(new ModeloDocumental.Campo(meta.version(), "long", true));
            campos.add(new ModeloDocumental.Campo(meta.creado(), "datetime", true));
            campos.add(new ModeloDocumental.Campo(meta.actualizado(), "datetime", true));
            colecciones.add(new ModeloDocumental.Coleccion(coleccionDe(u.clase()), u.clase(), campos, incrustados,
                    referencias, miembros.size() > 1 ? "_tipo" : null));
        }
        return new ModeloDocumental(List.copyOf(colecciones), List.copyOf(advertencias));
    }

    String json() {
        try {
            return new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValueAsString(vista) + "\n";
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("No se pudo escribir la Vista Documental", e);
        }
    }

    private static String tipoDe(Campo c) {
        return tipoDeJava(c.tipoJava(), c.enumerado());
    }

    /** Del tipo Java al del modelo, que es el que lee quien mira la vista. */
    static String tipoDeJava(String tipoJava, boolean enumerado) {
        if (enumerado) return tipoJava;
        return switch (tipoJava) {
            case "Integer" -> "int";
            case "Long" -> "long";
            case "BigDecimal" -> "decimal";
            case "Boolean" -> "boolean";
            case "LocalDate" -> "date";
            case "LocalDateTime" -> "datetime";
            case "UUID" -> "uuid";
            default -> "string";
        };
    }

    /** Plural regular del espanol sobre el nombre de tabla: `pedido` -> `pedidos`, `autor` -> `autores`. */
    static String plural(String tabla) {
        String base = tabla.replaceAll("_+$", "");
        if (base.isEmpty()) return "registros";
        char ultima = Character.toLowerCase(base.charAt(base.length() - 1));
        if (ultima == 's') return base;
        if (ultima == 'z') return base.substring(0, base.length() - 1) + "ces";
        return base + ("aeiou".indexOf(ultima) >= 0 ? "s" : "es");
    }

    List<String> advertencias() { return advertencias; }
}
