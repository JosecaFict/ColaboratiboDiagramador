package com.uagrm.colabmodel.ai;

import com.uagrm.colabmodel.modelcore.metamodel.UmlAttribute;
import com.uagrm.colabmodel.modelcore.metamodel.UmlClass;
import com.uagrm.colabmodel.modelcore.metamodel.UmlRelationship;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Lo que se puede comprobar sobre un diagrama sin IA y sin conocer el dominio.
 * Cada hallazgo es un hecho del diagrama (no una opinion de un modelo), asi
 * que la interfaz lo muestra como verificado.
 *
 * Las comprobaciones que dependen del requisito solo miran palabras
 * genericas de conservacion o historial, nunca nombres de un dominio.
 */
final class RevisorDeterministico {

    record Hallazgo(String texto, String categoria, String gravedad, List<String> elementos) { }

    private static final Set<String> CON_MULTIPLICIDAD = Set.of("ASSOCIATION", "AGGREGATION", "COMPOSITION");
    private static final Pattern PASCAL = Pattern.compile("^[A-Z][A-Za-z0-9]*$");
    private static final Pattern CAMEL = Pattern.compile("^[a-z][A-Za-z0-9]*$");
    private static final Pattern PIDE_CONSERVAR = Pattern.compile(
            "conserv|histori|version|sin (?:eliminar|borrar|perder)|no (?:elimin|borr)|dado de baja|dar de baja");
    private static final Set<String> RESERVADAS_JAVA = Set.of("abstract", "assert", "boolean", "break", "byte",
            "case", "catch", "char", "class", "const", "continue", "default", "do", "double", "else", "enum",
            "extends", "final", "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int",
            "interface", "long", "native", "new", "package", "private", "protected", "public", "return", "short",
            "static", "strictfp", "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try",
            "void", "volatile", "while", "record", "var", "yield", "true", "false", "null");

    private RevisorDeterministico() { }

    static List<Hallazgo> revisar(ServicioDeRecomendaciones.Diagrama d, String requisito) {
        List<Hallazgo> hallazgos = new ArrayList<>();
        estructura(d, hallazgos);
        grupos(d, hallazgos);
        relaciones(d, hallazgos);
        rolesDuplicados(d, hallazgos);
        identificadores(d, hallazgos);
        nombres(d, hallazgos);
        if (requisito != null && PIDE_CONSERVAR.matcher(normal(requisito)).find()) composiciones(d, hallazgos);
        return List.copyOf(hallazgos);
    }

    private static void estructura(ServicioDeRecomendaciones.Diagrama d, List<Hallazgo> hallazgos) {
        Map<UUID, Set<UUID>> vecinas = vecinas(d);
        Map<UUID, Integer> asociaciones = new HashMap<>();
        for (UmlRelationship r : d.relaciones()) {
            if (!CON_MULTIPLICIDAD.contains(r.getKind().name())) continue;
            asociaciones.merge(r.getSourceClassId(), 1, Integer::sum);
            asociaciones.merge(r.getTargetClassId(), 1, Integer::sum);
        }
        for (UmlClass c : d.clases()) {
            String n = c.getName();
            List<UmlAttribute> atributos = d.atributos().getOrDefault(c.getId(), List.of());
            if (atributos.isEmpty()) hallazgos.add(new Hallazgo(n + " no tiene atributos.", "ATRIBUTO", "MEDIA", List.of(n)));
            List<String> pks = atributos.stream().filter(UmlAttribute::isPk).map(UmlAttribute::getName).toList();
            if (!atributos.isEmpty() && pks.isEmpty())
                hallazgos.add(new Hallazgo(n + " no tiene clave primaria.", "CLAVE", "MEDIA", List.of(n)));
            if (pks.size() > 1) hallazgos.add(new Hallazgo(n + " tiene " + pks.size() + " atributos marcados como "
                    + "clave primaria (" + String.join(", ", pks) + "); si no es una clave compuesta intencional, deja "
                    + "uno como pk y los demas como unique.", "CLAVE", "MEDIA", List.of(n)));
            if (d.clases().size() > 1 && !vecinas.containsKey(c.getId()))
                hallazgos.add(new Hallazgo(n + " esta desconectada.", "RELACION", "ALTA", List.of(n)));
            boolean soloIdentidad = !atributos.isEmpty() && atributos.stream().allMatch(UmlAttribute::isPk);
            if (soloIdentidad && asociaciones.getOrDefault(c.getId(), 0) < 2)
                hallazgos.add(new Hallazgo(n + " solo tiene su identificador y no conecta dos conceptos: no tiene "
                        + "datos propios ni funcion asociativa.", "ATRIBUTO", "MEDIA", List.of(n)));
            for (UmlAttribute a : atributos) {
                String an = a.getName().toLowerCase(Locale.ROOT);
                if (a.isPk() || !(an.endsWith("id") && an.length() > 2)) continue;
                String base = an.endsWith("_id") ? an.substring(0, an.length() - 3) : an.substring(0, an.length() - 2);
                vecinas.getOrDefault(c.getId(), Set.of()).stream().map(id -> nombre(d, id))
                        .filter(v -> v.toLowerCase(Locale.ROOT).equals(base)).findFirst()
                        .ifPresent(v -> hallazgos.add(new Hallazgo(n + "." + a.getName() + " duplica la asociacion con "
                                + v + "; en un modelo conceptual la referencia ya la expresa la asociacion.",
                                "ATRIBUTO", "MEDIA", List.of(n + "." + a.getName(), v))));
            }
        }
    }

    private static void relaciones(ServicioDeRecomendaciones.Diagrama d, List<Hallazgo> hallazgos) {
        Set<String> vistas = new HashSet<>();
        for (UmlRelationship r : d.relaciones()) {
            String s = nombre(d, r.getSourceClassId()), t = nombre(d, r.getTargetClassId());
            String kind = r.getKind().name();
            if (CON_MULTIPLICIDAD.contains(kind) && (vacio(r.getSourceMultiplicity()) || vacio(r.getTargetMultiplicity())))
                hallazgos.add(new Hallazgo("La relacion " + s + " - " + t + " no tiene multiplicidad en ambos extremos.",
                        "MULTIPLICIDAD", "MEDIA", List.of(s, t)));
            String clave = (s.compareTo(t) <= 0 ? s + "|" + t + "|" + texto(r.getSourceRole()) + "|" + texto(r.getTargetRole())
                    : t + "|" + s + "|" + texto(r.getTargetRole()) + "|" + texto(r.getSourceRole())) + "|" + kind;
            if (!vistas.add(clave)) hallazgos.add(new Hallazgo("Hay relaciones " + kind + " repetidas entre " + s + " y "
                    + t + " sin roles que las distingan.", "RELACION", "MEDIA", List.of(s, t)));
            boolean recursiva = r.getSourceClassId().equals(r.getTargetClassId());
            if (CON_MULTIPLICIDAD.contains(kind) && muchos(r.getSourceMultiplicity()) && muchos(r.getTargetMultiplicity())
                    && !recursiva)
                hallazgos.add(new Hallazgo("La relacion " + s + " - " + t + " es muchos a muchos; si la participacion "
                        + "tiene datos propios (cantidad, precio, orden, fecha), conviene una clase asociativa.",
                        "RELACION", "BAJA", List.of(s, t)));
            if (recursiva && CON_MULTIPLICIDAD.contains(kind) && (vacio(r.getSourceRole()) || vacio(r.getTargetRole())))
                hallazgos.add(new Hallazgo("La asociacion recursiva de " + s + " no nombra los roles de ambos extremos; "
                        + "sin ellos no se distingue cual es cual (por ejemplo previa/siguiente o madre/descendiente).",
                        "RELACION", "MEDIA", List.of(s)));
            if (CON_MULTIPLICIDAD.contains(kind) && !recursiva && "1".equals(r.getSourceMultiplicity())
                    && "1".equals(r.getTargetMultiplicity()))
                hallazgos.add(new Hallazgo("La relacion " + s + " [1] - [1] " + t + " es uno a uno obligatorio; "
                        + "verifica que ninguno de los dos extremos deba admitir varias instancias o ninguna.",
                        "MULTIPLICIDAD", "BAJA", List.of(s, t)));
        }
    }

    /**
     * Clases sueltas ya se informan una por una; aca se detecta el caso en que
     * el diagrama queda partido en islas de varias clases cada una.
     */
    private static void grupos(ServicioDeRecomendaciones.Diagrama d, List<Hallazgo> hallazgos) {
        Map<UUID, Set<UUID>> vecinas = vecinas(d);
        Set<UUID> visitadas = new HashSet<>();
        List<List<String>> islas = new ArrayList<>();
        for (UmlClass c : d.clases()) {
            if (!vecinas.containsKey(c.getId()) || visitadas.contains(c.getId())) continue;
            List<String> isla = new ArrayList<>();
            Deque<UUID> pendientes = new ArrayDeque<>(List.of(c.getId()));
            while (!pendientes.isEmpty()) {
                UUID actual = pendientes.removeFirst();
                if (!visitadas.add(actual)) continue;
                isla.add(nombre(d, actual));
                pendientes.addAll(vecinas.getOrDefault(actual, Set.of()));
            }
            islas.add(isla);
        }
        if (islas.size() < 2) return;
        List<String> elementos = islas.stream().flatMap(List::stream).toList();
        hallazgos.add(new Hallazgo("El diagrama esta partido en " + islas.size() + " grupos sin relacion entre si: "
                + islas.stream().map(i -> "[" + String.join(", ", i) + "]").reduce((a, b) -> a + " y " + b).orElse("")
                + ". Revisa que asociacion del requisito los une (por ejemplo, quien participa en cada uno).",
                "RELACION", "ALTA", elementos));
    }

    /**
     * Una clase cuyo nombre es otra clase con un prefijo (PadreAnimal, MadreAnimal)
     * cuando esa otra ya tiene una asociacion recursiva: el rol se expresa en la
     * asociacion, no con una clase que duplica la entidad.
     */
    private static void rolesDuplicados(ServicioDeRecomendaciones.Diagrama d, List<Hallazgo> hallazgos) {
        Set<UUID> conRecursiva = new HashSet<>();
        for (UmlRelationship r : d.relaciones())
            if (r.getSourceClassId().equals(r.getTargetClassId())) conRecursiva.add(r.getSourceClassId());
        for (UmlClass base : d.clases()) {
            if (!conRecursiva.contains(base.getId())) continue;
            String b = base.getName();
            for (UmlClass c : d.clases()) {
                String n = c.getName();
                if (c.getId().equals(base.getId()) || n.length() <= b.length() || !n.endsWith(b)) continue;
                hallazgos.add(new Hallazgo(n + " parece duplicar " + b + " como un rol, y " + b + " ya tiene una "
                        + "asociacion recursiva que expresa ese rol. Elimina " + n + " y nombra el rol en la asociacion.",
                        "RELACION", "MEDIA", List.of(n, b)));
            }
        }
    }

    /** Si la mayoria de las clases usa una forma de identificador, las que usan otra son inconsistentes. */
    private static void identificadores(ServicioDeRecomendaciones.Diagrama d, List<Hallazgo> hallazgos) {
        Map<String, String> pkUnica = new LinkedHashMap<>();
        for (UmlClass c : d.clases()) {
            List<UmlAttribute> pks = d.atributos().getOrDefault(c.getId(), List.of()).stream()
                    .filter(UmlAttribute::isPk).toList();
            if (pks.size() == 1) pkUnica.put(c.getName(), pks.getFirst().getName());
        }
        long conId = pkUnica.values().stream().filter("id"::equals).count();
        if (conId * 2 <= pkUnica.size()) return;
        List<String> distintas = pkUnica.entrySet().stream().filter(e -> !e.getValue().equals("id"))
                .map(e -> e.getKey() + "." + e.getValue()).toList();
        if (distintas.isEmpty()) return;
        hallazgos.add(new Hallazgo("Identificadores inconsistentes: " + String.join(", ", distintas) + " mientras "
                + conId + " clases usan id. Renombrar a id simplifica las tablas y el backend generado.",
                "NOMBRE", "BAJA", distintas));
    }

    /** Lo que el generador de base de datos y backend necesita: identificadores ASCII, sin palabras reservadas. */
    private static void nombres(ServicioDeRecomendaciones.Diagrama d, List<Hallazgo> hallazgos) {
        for (UmlClass c : d.clases()) {
            String n = c.getName();
            if (!PASCAL.matcher(n).matches())
                hallazgos.add(new Hallazgo("El nombre de clase " + n + " no es PascalCase ASCII (sin espacios, tildes "
                        + "ni enie).", "NOMBRE", "MEDIA", List.of(n)));
            for (UmlAttribute a : d.atributos().getOrDefault(c.getId(), List.of())) {
                String an = a.getName();
                if (!CAMEL.matcher(an).matches())
                    hallazgos.add(new Hallazgo("El atributo " + n + "." + an + " no es camelCase ASCII.", "NOMBRE",
                            "MEDIA", List.of(n + "." + an)));
                else if (RESERVADAS_JAVA.contains(an))
                    hallazgos.add(new Hallazgo("El atributo " + n + "." + an + " es una palabra reservada de Java y "
                            + "rompe el backend generado.", "NOMBRE", "ALTA", List.of(n + "." + an)));
            }
        }
    }

    /**
     * Una COMPOSITION borra la parte junto con el todo. Si el requisito pide
     * conservar historial, versiones o registros dados de baja, cada
     * COMPOSITION merece una decision explicita del usuario.
     */
    private static void composiciones(ServicioDeRecomendaciones.Diagrama d, List<Hallazgo> hallazgos) {
        for (UmlRelationship r : d.relaciones()) {
            if (!r.getKind().name().equals("COMPOSITION")) continue;
            String todo = nombre(d, r.getSourceClassId()), parte = nombre(d, r.getTargetClassId());
            hallazgos.add(new Hallazgo("La COMPOSITION " + todo + " - " + parte + " implica que cada " + parte
                    + " se borra con su " + todo + ", y el requisito pide conservar historial o registros. Usa "
                    + "ASSOCIATION salvo que " + parte + " no tenga sentido sin " + todo + ".", "RELACION", "MEDIA",
                    List.of(todo, parte)));
        }
    }

    private static Map<UUID, Set<UUID>> vecinas(ServicioDeRecomendaciones.Diagrama d) {
        Map<UUID, Set<UUID>> vecinas = new HashMap<>();
        for (UmlRelationship r : d.relaciones()) {
            vecinas.computeIfAbsent(r.getSourceClassId(), k -> new HashSet<>()).add(r.getTargetClassId());
            vecinas.computeIfAbsent(r.getTargetClassId(), k -> new HashSet<>()).add(r.getSourceClassId());
        }
        return vecinas;
    }

    private static String nombre(ServicioDeRecomendaciones.Diagrama d, UUID id) {
        return d.nombres().getOrDefault(id, id.toString());
    }

    private static boolean vacio(String s) { return s == null || s.isBlank(); }

    private static String texto(String s) { return s == null ? "" : s; }

    private static boolean muchos(String m) {
        return m != null && (m.equals("*") || m.endsWith("..*"));
    }

    private static String normal(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }
}
