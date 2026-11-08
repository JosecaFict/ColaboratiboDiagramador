package com.uagrm.colabmodel.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.uagrm.colabmodel.modelcore.command.Origin;
import com.uagrm.colabmodel.modelcore.metamodel.*;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * El asistente que aconseja: lee el diagrama REAL (lo ya aplicado), detecta
 * problemas y devuelve recomendaciones que el usuario elige aplicar.
 *
 * No escribe nada. Aplicar es pedir una propuesta MODIFICAR con las acciones
 * elegidas ({@link #proponerAplicacion}), que pasa por la misma validacion,
 * vista previa y confirmacion que cualquier otro cambio de la IA (ADR-002).
 *
 * Sin @Transactional a proposito: la inferencia local tarda decenas de
 * segundos y no debe retener una conexion de la base mientras tanto.
 */
@Service
public class ServicioDeRecomendaciones {

    private static final Set<String> CON_MULTIPLICIDAD = Set.of("ASSOCIATION", "AGGREGATION", "COMPOSITION");

    private final ProveedorDeIA proveedor;
    private final UmlClassRepository classes;
    private final UmlAttributeRepository attributes;
    private final UmlRelationshipRepository relationships;
    private final ServicioDeIA servicioDeIA;

    public ServicioDeRecomendaciones(ProveedorDeIA proveedor, UmlClassRepository classes,
                                     UmlAttributeRepository attributes, UmlRelationshipRepository relationships,
                                     ServicioDeIA servicioDeIA) {
        this.proveedor = proveedor;
        this.classes = classes;
        this.attributes = attributes;
        this.relationships = relationships;
        this.servicioDeIA = servicioDeIA;
    }

    /** El diagrama tal como lo ve la revision: clases con sus atributos y relaciones con nombres de clase. */
    record Diagrama(List<UmlClass> clases, Map<UUID, List<UmlAttribute>> atributos,
                    List<UmlRelationship> relaciones, Map<UUID, String> nombres) { }

    /**
     * @param requisito opcional: el texto con las reglas de negocio (por ejemplo,
     *                  el mismo pedido que se uso para Disenar). Con el, cada
     *                  regla se verifica contra el diagrama.
     */
    public RecomendacionesIA recomendar(UUID diagramId, String requisito, String modelo) {
        Diagrama d = cargar(diagramId);
        if (d.clases().isEmpty()) {
            throw new IllegalArgumentException("El diagrama esta vacio: primero aplica un diseno o agrega clases");
        }
        List<String> hallazgos = hallazgosEstructurales(d);
        RespuestaIA respuesta = proveedor.generar(new SolicitudIA(pedido(requisito, hallazgos), ModoIA.RECOMENDAR,
                contexto(d), relacionesEnContexto(d), List.of(EsquemaRecomendaciones.herramienta()), modelo));
        JsonNode salida = respuesta.comandos().stream()
                .filter(c -> EsquemaRecomendaciones.NOMBRE.equals(c.tipo()))
                .map(ComandoPropuestoIA::parametros).findFirst()
                .orElseThrow(() -> new ProveedorIAException("El modelo no devolvio recomendaciones"
                        + (respuesta.notaDelModelo().isBlank() ? "" : ": " + respuesta.notaDelModelo())));
        return new RecomendacionesIA(diagramId, salida.path("resumen").asText(""),
                depurar(salida.path("recomendaciones"), d), hallazgos,
                modelo == null || modelo.isBlank() ? proveedor.nombre() : modelo);
    }

    /** Las recomendaciones elegidas, convertidas en UNA propuesta MODIFICAR para revisar y confirmar. */
    public PropuestaDeIA proponerAplicacion(UUID diagramId, List<RecomendacionesIA.Recomendacion> elegidas,
                                            Origin origin, String modelo) {
        if (elegidas == null || elegidas.isEmpty()) {
            throw new IllegalArgumentException("Elegi al menos una recomendacion para aplicar");
        }
        return servicioDeIA.generar(diagramId, pedidoDeAplicacion(elegidas), origin, ModoIA.MODIFICAR, modelo);
    }

    static String pedidoDeAplicacion(List<RecomendacionesIA.Recomendacion> elegidas) {
        StringBuilder sb = new StringBuilder("Aplica en el diagrama actual exactamente estas correcciones aprobadas "
                + "por el usuario, y ninguna otra:\n");
        int i = 1;
        for (RecomendacionesIA.Recomendacion r : elegidas) {
            if (r.accion() == null || r.accion().isBlank()) continue;
            sb.append(i++).append(". ").append(r.accion().trim());
            if (r.problema() != null && !r.problema().isBlank()) sb.append(" (motivo: ").append(r.problema().trim()).append(')');
            sb.append('\n');
        }
        if (i == 1) throw new IllegalArgumentException("Las recomendaciones elegidas no tienen una accion para aplicar");
        sb.append("Reglas de edicion: para mover un atributo usa REMOVE_ATTRIBUTE en la clase de origen y ADD_ATTRIBUTE "
                + "en la clase de destino con el mismo nombre y tipo. Para cambiar el tipo, el nombre o las "
                + "multiplicidades de una relacion existente usa UPDATE_RELATION sobre esa relacion, sin borrarla y "
                + "recrearla. Para cambiar la marca de clave primaria de un atributo, quitalo con REMOVE_ATTRIBUTE y "
                + "vuelve a agregarlo con ADD_ATTRIBUTE con las banderas correctas. No dupliques clases, atributos ni "
                + "relaciones que ya existen.");
        return sb.toString();
    }

    private Diagrama cargar(UUID diagramId) {
        List<UmlClass> existentes = classes.findByDiagramIdAndDeletedAtIsNullOrderByNameAsc(diagramId);
        List<UUID> ids = existentes.stream().map(UmlClass::getId).toList();
        Map<UUID, List<UmlAttribute>> atributosPorClase = ids.isEmpty() ? Map.of()
                : attributes.findByClassIdInOrderByOrdinalAsc(ids).stream()
                        .collect(Collectors.groupingBy(UmlAttribute::getClassId));
        Map<UUID, String> nombres = existentes.stream().collect(Collectors.toMap(UmlClass::getId, UmlClass::getName));
        return new Diagrama(existentes, atributosPorClase,
                relationships.findByDiagramIdAndDeletedAtIsNull(diagramId), nombres);
    }

    /** A diferencia de los otros modos, incluye las banderas: sin ellas no se pueden revisar las claves. */
    private static List<SolicitudIA.ClaseEnContexto> contexto(Diagrama d) {
        return d.clases().stream().map(c -> new SolicitudIA.ClaseEnContexto(c.getName(),
                d.atributos().getOrDefault(c.getId(), List.of()).stream()
                        .map(ServicioDeRecomendaciones::describir).toList(),
                List.of(), c.getStereotype(), c.isAbstract())).toList();
    }

    private static String describir(UmlAttribute a) {
        List<String> banderas = new ArrayList<>();
        if (a.isPk()) banderas.add("pk");
        if (a.isRequired()) banderas.add("required");
        if (a.isUnique()) banderas.add("unique");
        return a.getName() + ": " + a.getDataType() + (banderas.isEmpty() ? "" : " [" + String.join(", ", banderas) + "]");
    }

    private static List<SolicitudIA.RelacionEnContexto> relacionesEnContexto(Diagrama d) {
        return d.relaciones().stream().map(r -> new SolicitudIA.RelacionEnContexto(
                nombre(d, r.getSourceClassId()), nombre(d, r.getTargetClassId()), r.getKind().name(), r.getName(),
                r.getSourceMultiplicity(), r.getTargetMultiplicity())).toList();
    }

    private static String nombre(Diagrama d, UUID id) {
        return d.nombres().getOrDefault(id, id.toString());
    }

    static String pedido(String requisito, List<String> hallazgos) {
        StringBuilder sb = new StringBuilder();
        if (requisito != null && !requisito.isBlank()) {
            sb.append("REQUISITO DEL USUARIO (verifica cada regla de negocio contra el diagrama):\n")
                    .append(requisito.trim()).append("\n\n");
        }
        sb.append("HALLAZGOS ESTRUCTURALES DEL BACKEND:\n")
                .append(hallazgos.isEmpty() ? "Ninguno." : "- " + String.join("\n- ", hallazgos))
                .append("\n\nRevisa el diagrama actual y entrega tus recomendaciones de mejora.");
        return sb.toString();
    }

    /**
     * Comprobaciones que no necesitan IA y valen para cualquier dominio. Son
     * ciertas siempre, asi que el modelo las recibe como hechos y no como sospechas.
     */
    static List<String> hallazgosEstructurales(Diagrama d) {
        List<String> hallazgos = new ArrayList<>();
        Map<UUID, Set<UUID>> vecinas = new HashMap<>();
        for (UmlRelationship r : d.relaciones()) {
            vecinas.computeIfAbsent(r.getSourceClassId(), k -> new HashSet<>()).add(r.getTargetClassId());
            vecinas.computeIfAbsent(r.getTargetClassId(), k -> new HashSet<>()).add(r.getSourceClassId());
        }
        for (UmlClass c : d.clases()) {
            List<UmlAttribute> atributos = d.atributos().getOrDefault(c.getId(), List.of());
            if (atributos.isEmpty()) hallazgos.add(c.getName() + " no tiene atributos.");
            List<String> pks = atributos.stream().filter(UmlAttribute::isPk).map(UmlAttribute::getName).toList();
            if (!atributos.isEmpty() && pks.isEmpty()) hallazgos.add(c.getName() + " no tiene clave primaria.");
            if (pks.size() > 1) hallazgos.add(c.getName() + " tiene " + pks.size() + " atributos marcados como clave "
                    + "primaria (" + String.join(", ", pks) + "); si no es una clave compuesta intencional, deja uno "
                    + "como pk y los demas como unique.");
            if (d.clases().size() > 1 && !vecinas.containsKey(c.getId())) hallazgos.add(c.getName() + " esta desconectada.");
            for (UmlAttribute a : atributos) {
                String n = a.getName().toLowerCase(Locale.ROOT);
                if (a.isPk() || !(n.endsWith("id") && n.length() > 2)) continue;
                String base = n.endsWith("_id") ? n.substring(0, n.length() - 3) : n.substring(0, n.length() - 2);
                vecinas.getOrDefault(c.getId(), Set.of()).stream().map(id -> nombre(d, id))
                        .filter(v -> v.toLowerCase(Locale.ROOT).equals(base)).findFirst()
                        .ifPresent(v -> hallazgos.add(c.getName() + "." + a.getName() + " duplica la asociacion con "
                                + v + "; en un modelo conceptual la referencia ya la expresa la asociacion."));
            }
        }
        Set<String> vistas = new HashSet<>();
        for (UmlRelationship r : d.relaciones()) {
            String s = nombre(d, r.getSourceClassId()), t = nombre(d, r.getTargetClassId());
            String kind = r.getKind().name();
            if (CON_MULTIPLICIDAD.contains(kind) && (vacio(r.getSourceMultiplicity()) || vacio(r.getTargetMultiplicity())))
                hallazgos.add("La relacion " + s + " - " + t + " no tiene multiplicidad en ambos extremos.");
            String clave = (s.compareTo(t) <= 0 ? s + "|" + t + "|" + texto(r.getSourceRole()) + "|" + texto(r.getTargetRole())
                    : t + "|" + s + "|" + texto(r.getTargetRole()) + "|" + texto(r.getSourceRole())) + "|" + kind;
            if (!vistas.add(clave)) hallazgos.add("Hay relaciones " + kind + " repetidas entre " + s + " y " + t
                    + " sin roles que las distingan.");
            if (CON_MULTIPLICIDAD.contains(kind) && muchos(r.getSourceMultiplicity()) && muchos(r.getTargetMultiplicity()))
                hallazgos.add("La relacion " + s + " - " + t + " es muchos a muchos; si la participacion tiene datos "
                        + "propios, conviene una clase asociativa.");
        }
        return List.copyOf(hallazgos);
    }

    private static boolean vacio(String s) { return s == null || s.isBlank(); }

    private static String texto(String s) { return s == null ? "" : s; }

    private static boolean muchos(String m) {
        return m != null && (m.equals("*") || m.endsWith("..*"));
    }

    /**
     * La salida del modelo es tan sospechosa como un archivo importado: se
     * normalizan gravedad y categoria, se descartan las vacias y repetidas,
     * y solo quedan como elementos los nombres que existen en el diagrama.
     */
    static List<RecomendacionesIA.Recomendacion> depurar(JsonNode lista, Diagrama d) {
        Map<String, String> clasesPorNombre = new HashMap<>();
        for (UmlClass c : d.clases()) clasesPorNombre.put(c.getName().toLowerCase(Locale.ROOT), c.getName());
        Map<String, Map<String, String>> atributosPorClase = new HashMap<>();
        for (UmlClass c : d.clases()) {
            Map<String, String> propios = new HashMap<>();
            for (UmlAttribute a : d.atributos().getOrDefault(c.getId(), List.of()))
                propios.put(a.getName().toLowerCase(Locale.ROOT), a.getName());
            atributosPorClase.put(c.getName(), propios);
        }

        List<JsonNode> candidatas = new ArrayList<>();
        Set<String> acciones = new HashSet<>();
        for (JsonNode r : lista) {
            String accion = r.path("accion").asText("").trim();
            if (r.path("titulo").asText("").isBlank() || accion.isEmpty()) continue;
            if (!acciones.add(accion.toLowerCase(Locale.ROOT))) continue;
            candidatas.add(r);
        }
        candidatas.sort(Comparator.comparingInt(r -> rangoDeGravedad(r.path("gravedad").asText())));

        List<RecomendacionesIA.Recomendacion> resultado = new ArrayList<>();
        for (JsonNode r : candidatas) {
            if (resultado.size() >= EsquemaRecomendaciones.MAXIMO) break;
            Set<String> elementos = new LinkedHashSet<>();
            for (JsonNode e : r.path("elementos")) {
                String[] partes = e.asText("").trim().split("\\.", 2);
                String clase = clasesPorNombre.get(partes[0].toLowerCase(Locale.ROOT));
                if (clase == null) continue;
                if (partes.length == 1) { elementos.add(clase); continue; }
                String atributo = atributosPorClase.get(clase).get(partes[1].toLowerCase(Locale.ROOT));
                elementos.add(atributo == null ? clase : clase + "." + atributo);
            }
            String gravedad = r.path("gravedad").asText("").toUpperCase(Locale.ROOT);
            String categoria = r.path("categoria").asText("").toUpperCase(Locale.ROOT);
            resultado.add(new RecomendacionesIA.Recomendacion(resultado.size() + 1, r.path("titulo").asText().trim(),
                    EsquemaRecomendaciones.GRAVEDADES.contains(gravedad) ? gravedad : "MEDIA",
                    EsquemaRecomendaciones.CATEGORIAS.contains(categoria) ? categoria : "OTRO",
                    r.path("regla").asText("").trim(), List.copyOf(elementos), r.path("problema").asText("").trim(),
                    r.path("accion").asText().trim()));
        }
        return List.copyOf(resultado);
    }

    private static int rangoDeGravedad(String gravedad) {
        int i = EsquemaRecomendaciones.GRAVEDADES.indexOf(gravedad.toUpperCase(Locale.ROOT));
        return i < 0 ? 1 : i;
    }
}
