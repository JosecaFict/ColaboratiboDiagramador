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
        List<RevisorDeterministico.Hallazgo> hallazgos = RevisorDeterministico.revisar(d, requisito);
        List<String> textos = hallazgos.stream().map(RevisorDeterministico.Hallazgo::texto).toList();
        String nombreModelo = modelo == null || modelo.isBlank() ? proveedor.nombre() : modelo;
        List<ReglasDelRequisito.Regla> reglas = ReglasDelRequisito.extraer(requisito);
        if (!reglas.isEmpty()) return verificarReglas(diagramId, requisito, reglas, hallazgos, d, modelo, nombreModelo);

        RespuestaIA respuesta = proveedor.generar(new SolicitudIA(pedido(requisito, textos), ModoIA.RECOMENDAR,
                contexto(d), relacionesEnContexto(d), List.of(EsquemaRecomendaciones.herramienta()), modelo));
        JsonNode salida = respuesta.comandos().stream()
                .filter(c -> EsquemaRecomendaciones.NOMBRE.equals(c.tipo()))
                .map(ComandoPropuestoIA::parametros).findFirst()
                .orElseThrow(() -> new ProveedorIAException("El modelo no devolvio recomendaciones"
                        + (respuesta.notaDelModelo().isBlank() ? "" : ": " + respuesta.notaDelModelo())));
        List<RecomendacionesIA.Recomendacion> todas = new ArrayList<>(verificadas(hallazgos));
        todas.addAll(depurar(salida.path("recomendaciones"), d));
        return new RecomendacionesIA(diagramId, salida.path("resumen").asText(""), numerar(todas), List.of(),
                textos, nombreModelo);
    }

    /** Los hallazgos del revisor determinista, como recomendaciones aplicables y marcadas como verificadas. */
    private static List<RecomendacionesIA.Recomendacion> verificadas(List<RevisorDeterministico.Hallazgo> hallazgos) {
        return hallazgos.stream().map(h -> new RecomendacionesIA.Recomendacion(0, h.texto(), h.gravedad(),
                h.categoria(), "", h.elementos(), h.texto(), "Corregir: " + h.texto(),
                RecomendacionesIA.ORIGEN_VERIFICADO)).toList();
    }

    /** De la mas grave a la menos grave y, a igual gravedad, lo verificado antes que lo sugerido por el modelo. */
    private static List<RecomendacionesIA.Recomendacion> numerar(List<RecomendacionesIA.Recomendacion> todas) {
        List<RecomendacionesIA.Recomendacion> numeradas = new ArrayList<>();
        todas.stream().sorted(Comparator.<RecomendacionesIA.Recomendacion>comparingInt(r -> rangoDeGravedad(r.gravedad()))
                        .thenComparing(r -> !r.origen().equals(RecomendacionesIA.ORIGEN_VERIFICADO)))
                .forEach(r -> numeradas.add(new RecomendacionesIA.Recomendacion(numeradas.size() + 1, r.titulo(),
                        r.gravedad(), r.categoria(), r.regla(), r.elementos(), r.problema(), r.accion(), r.origen())));
        return List.copyOf(numeradas);
    }

    /**
     * Una pregunta pequena por regla en vez de una revision abierta. El texto
     * comun (requisito y diagrama) va primero y la regla al final: Ollama
     * reutiliza el prefijo ya procesado y cada regla extra cuesta pocos segundos.
     */
    private RecomendacionesIA verificarReglas(UUID diagramId, String requisito, List<ReglasDelRequisito.Regla> reglas,
                                              List<RevisorDeterministico.Hallazgo> hallazgos, Diagrama d,
                                              String modelo, String nombreModelo) {
        String comun = "REQUISITO DEL USUARIO (contexto):\n" + requisito.trim()
                + new SolicitudIA("", ModoIA.VERIFICAR, contexto(d), relacionesEnContexto(d), List.of())
                        .mensajeParaElModelo();
        List<RecomendacionesIA.Verificacion> verificaciones = new ArrayList<>();
        List<RecomendacionesIA.Recomendacion> recomendaciones = new ArrayList<>();
        for (ReglasDelRequisito.Regla regla : reglas) {
            String pedido = comun + "\n\nREGLA A VERIFICAR:\n" + regla.texto();
            JsonNode salida;
            try {
                salida = proveedor.generar(new SolicitudIA(pedido, ModoIA.VERIFICAR, List.of(), List.of(),
                        List.of(EsquemaVerificacionRegla.herramienta()), modelo)).comandos().stream()
                        .filter(c -> EsquemaVerificacionRegla.NOMBRE.equals(c.tipo()))
                        .map(ComandoPropuestoIA::parametros).findFirst().orElse(null);
            } catch (ProveedorIAException e) {
                salida = null; // una regla sin respuesta no tira las demas
            }
            var resultado = evaluar(regla, salida, d);
            verificaciones.add(resultado.verificacion());
            if (resultado.recomendacion() != null) recomendaciones.add(resultado.recomendacion());
        }
        recomendaciones.addAll(verificadas(hallazgos));
        long cumplen = verificaciones.stream().filter(v -> v.estado().equals("CUMPLE")).count();
        String resumen = "Se verificaron " + verificaciones.size() + " reglas del requisito: " + cumplen + " se cumplen, "
                + (verificaciones.size() - cumplen) + " no se cumplen o no se pudieron verificar. "
                + hallazgos.size() + " hallazgo(s) verificado(s) sin IA.";
        return new RecomendacionesIA(diagramId, resumen, numerar(recomendaciones), List.copyOf(verificaciones),
                hallazgos.stream().map(RevisorDeterministico.Hallazgo::texto).toList(), nombreModelo);
    }

    record Evaluacion(RecomendacionesIA.Verificacion verificacion, RecomendacionesIA.Recomendacion recomendacion) { }

    /**
     * El veredicto del modelo solo vale si su evidencia existe. Citar un
     * atributo que no esta en esa clase es exactamente la invencion que hace
     * fallar a un modelo local: esa verificacion queda NO_VERIFICABLE.
     */
    static Evaluacion evaluar(ReglasDelRequisito.Regla regla, JsonNode salida, Diagrama d) {
        String seccion = regla.seccion().name();
        if (salida == null) return new Evaluacion(new RecomendacionesIA.Verificacion(regla.numero(), regla.texto(),
                seccion, "NO_VERIFICABLE", List.of(), "El modelo no respondio esta regla."), null);
        List<String> validos = new ArrayList<>();
        List<String> inventados = new ArrayList<>();
        for (JsonNode e : salida.path("evidencia")) {
            String citado = e.asText("").trim();
            if (citado.isEmpty()) continue;
            String resuelto = resolverElemento(citado, d);
            if (resuelto != null) validos.add(resuelto); else inventados.add(citado);
        }
        String explicacion = salida.path("explicacion").asText("").trim();
        boolean cumple = salida.path("cumple").asBoolean(false);
        if (!inventados.isEmpty() && validos.isEmpty()) {
            return new Evaluacion(new RecomendacionesIA.Verificacion(regla.numero(), regla.texto(), seccion,
                    "NO_VERIFICABLE", List.of(), "El modelo cito elementos que no existen en el diagrama: "
                    + String.join(", ", inventados) + "."), null);
        }
        var verificacion = new RecomendacionesIA.Verificacion(regla.numero(), regla.texto(), seccion,
                cumple ? "CUMPLE" : "NO_CUMPLE", List.copyOf(validos), explicacion);
        String accion = salida.path("accion").asText("").trim();
        if (cumple || accion.isEmpty()) return new Evaluacion(verificacion, null);
        Set<String> clases = new LinkedHashSet<>();
        for (String v : validos) for (String parte : v.split("-")) clases.add(parte);
        return new Evaluacion(verificacion, new RecomendacionesIA.Recomendacion(0, "Regla " + regla.numero()
                + " no se cumple", regla.seccion() == ReglasDelRequisito.Seccion.NEGOCIO ? "ALTA" : "MEDIA",
                "REQUISITO", regla.texto(), List.copyOf(clases), explicacion, accion, RecomendacionesIA.ORIGEN_MODELO));
    }

    /** Clase, Clase.atributo o ClaseA-ClaseB, con el nombre exacto del diagrama; null si no existe. */
    static String resolverElemento(String citado, Diagrama d) {
        // Los modelos citan con el mismo formato del contexto que reciben:
        // "Clase X", "Clase.X.atributo" o "A [1] --COMPOSITION--> B [0..*]".
        String limpio = citado.replaceAll("\\[[^\\]]*\\]", " ").replaceAll("(?i)\\bclase\\b[\\s.:]*", "").trim();
        String[] relacion = limpio.split("\\s*(?:--[A-Za-z_]*-->|<-+|-+>?|\u2014|\u2013)\\s*");
        if (relacion.length > 2) return null;
        if (relacion.length == 2) {
            String a = resolverElemento(relacion[0], d), b = resolverElemento(relacion[1], d);
            if (a == null || b == null || a.contains(".") || b.contains(".")) return null;
            // Citar como evidencia una relacion que no existe tambien es inventar.
            boolean existe = d.relaciones().stream().anyMatch(r -> {
                String s = nombre(d, r.getSourceClassId()), t = nombre(d, r.getTargetClassId());
                return (s.equals(a) && t.equals(b)) || (s.equals(b) && t.equals(a));
            });
            return existe ? a + "-" + b : null;
        }
        String[] partes = limpio.split("\\.", 2);
        UmlClass clase = d.clases().stream().filter(c -> c.getName().equalsIgnoreCase(partes[0].trim()))
                .findFirst().orElse(null);
        if (clase == null) return null;
        if (partes.length == 1) return clase.getName();
        return d.atributos().getOrDefault(clase.getId(), List.of()).stream()
                .filter(a -> a.getName().equalsIgnoreCase(partes[1].trim())).findFirst()
                .map(a -> clase.getName() + "." + a.getName()).orElse(null);
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
                    r.path("accion").asText().trim(), RecomendacionesIA.ORIGEN_MODELO));
        }
        return List.copyOf(resultado);
    }

    private static int rangoDeGravedad(String gravedad) {
        int i = EsquemaRecomendaciones.GRAVEDADES.indexOf(gravedad.toUpperCase(Locale.ROOT));
        return i < 0 ? 1 : i;
    }
}
