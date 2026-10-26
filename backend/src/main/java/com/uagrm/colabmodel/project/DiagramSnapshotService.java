package com.uagrm.colabmodel.project;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uagrm.colabmodel.codegen.GeneradorSpringBoot;
import com.uagrm.colabmodel.codegen.ProyectoGenerado;
import com.uagrm.colabmodel.iam.AppUser;
import com.uagrm.colabmodel.iam.AppUserRepository;
import com.uagrm.colabmodel.modelcore.mapping.Ambiguedad;
import com.uagrm.colabmodel.modelcore.mapping.Ambiguedades;
import com.uagrm.colabmodel.modelcore.mapping.Decisiones;
import com.uagrm.colabmodel.modelcore.mapping.DerivadorLogico;
import com.uagrm.colabmodel.modelcore.mapping.InferenciaDeAtributos;
import com.uagrm.colabmodel.modelcore.mapping.ModeloLogico;
import com.uagrm.colabmodel.modelcore.metamodel.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.text.Normalizer;

/**
 * Arma el estado actual del diagrama leyendo el metamodelo normalizado.
 *
 * Sin esto, un cliente que entra tiene que pedir TODO el log de operaciones y
 * re-aplicarlo: el arranque se vuelve mas lento con cada operacion que se
 * agrega, para siempre. Con el snapshot, arrancar cuesta lo que mide el
 * modelo, no lo que mide su historia.
 */
@Service
public class DiagramSnapshotService {

    private static final Logger log = LoggerFactory.getLogger(DiagramSnapshotService.class);

    private final DiagramRepository diagrams;
    private final UmlClassRepository classes;
    private final UmlAttributeRepository attributes;
    private final UmlMethodRepository methods;
    private final UmlRelationshipRepository relationships;
    private final ModelingDecisionRepository decisions;
    private final DiagramLayoutRepository layouts;
    private final ConfirmacionDeEsquemaRepository confirmaciones;
    private final AppUserRepository users;
    private final ObjectMapper mapper = new ObjectMapper();

    public DiagramSnapshotService(DiagramRepository diagrams, UmlClassRepository classes,
                                  UmlAttributeRepository attributes,
                                  UmlMethodRepository methods,
                                  UmlRelationshipRepository relationships,
                                  ModelingDecisionRepository decisions,
                                  DiagramLayoutRepository layouts,
                                  ConfirmacionDeEsquemaRepository confirmaciones,
                                  AppUserRepository users) {
        this.diagrams = diagrams;
        this.classes = classes;
        this.attributes = attributes;
        this.methods = methods;
        this.relationships = relationships;
        this.decisions = decisions;
        this.layouts = layouts;
        this.confirmaciones = confirmaciones;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public Optional<DiagramSnapshot> de(UUID diagramId) {
        return diagrams.findById(diagramId).map(d -> armar(d, leer(d.getId())));
    }

    /**
     * Vista Logica: la MISMA derivacion que despues usara `codegen` para
     * emitir el DDL, asi lo que se ve en pantalla es lo que se entrega.
     */
    @Transactional(readOnly = true)
    public Optional<ModeloLogico> logicaDe(UUID diagramId) {
        return diagrams.findById(diagramId)
                .map(d -> leer(d.getId()))
                .map(m -> DerivadorLogico.derivar(m.clases(), m.atributos(), m.relaciones(),
                        decisionesDe(diagramId)));
    }

    /**
     * Los 4 puntos de ambiguedad OO <-> Datos de este diagrama, con lo que el
     * equipo ya contesto.
     *
     * Se detectan sobre el mismo modelo que se deriva: una ambiguedad que la
     * pantalla no muestre es una decision que el generador va a tomar sola.
     */
    @Transactional(readOnly = true)
    public Optional<List<AmbiguedadResuelta>> ambiguedadesDe(UUID diagramId) {
        return diagrams.findById(diagramId).map(d -> {
            Modelo m = leer(d.getId());
            Map<Object, Ambiguedad.Lectura> elegidas = new HashMap<>();
            for (ModelingDecision dec : decisions.findByDiagramId(diagramId)) {
                elegidas.put(List.of(dec.getAmbiguityKind(), dec.getElementId()), dec.getChosen());
            }
            return Ambiguedades.detectar(m.clases(), m.atributos(), m.relaciones()).stream()
                    .map(a -> {
                        Ambiguedad.Lectura elegida = elegidas.get(List.of(a.tipo(), a.elementId()));
                        return new AmbiguedadResuelta(a.elementId(), a.tipo(), a.titulo(),
                                a.lecturaOO(), a.lecturaDatos(), a.porDefecto(),
                                elegida == null ? a.porDefecto() : elegida, elegida != null);
                    })
                    .toList();
        });
    }

    /**
     * @param decidida false = nadie contesto todavia y manda `porDefecto`. Es
     *                 lo que le permite a la pantalla distinguir "elegimos
     *                 esto" de "esto es lo que pasa si no eligen nada".
     */
    public record AmbiguedadResuelta(UUID elementId, Ambiguedad.TipoAmbiguedad tipo, String titulo,
                                     String lecturaOO, String lecturaDatos,
                                     Ambiguedad.Lectura porDefecto, Ambiguedad.Lectura elegida,
                                     boolean decidida) { }

    /**
     * Que parece ser un atributo por su nombre (tipo y rol), contra las clases
     * de ESTE diagrama: asi `idMaterial` se reconoce como FK solo si Material
     * existe. Lectura pura: no escribe nada.
     */
    @Transactional(readOnly = true)
    public Optional<InferenciaDeAtributos.Sugerencia> sugerirAtributo(UUID diagramId, UUID claseId,
                                                                      String nombre, String tipoEscrito) {
        return diagrams.findById(diagramId).map(d -> {
            List<UmlClass> clases = classes.findByDiagramIdAndDeletedAtIsNullOrderByNameAsc(diagramId);
            UmlClass duenio = claseId == null ? null
                    : clases.stream().filter(c -> c.getId().equals(claseId)).findFirst().orElse(null);
            return InferenciaDeAtributos.sugerir(nombre, tipoEscrito, duenio, clases);
        });
    }

    /** Las decisiones guardadas, en la forma que entiende la derivacion. */
    private Decisiones decisionesDe(UUID diagramId) {
        List<ModelingDecision> guardadas = decisions.findByDiagramId(diagramId);
        if (guardadas.isEmpty()) return Decisiones.ninguna();
        Decisiones.Builder b = Decisiones.constructor();
        guardadas.forEach(d -> b.con(d.getAmbiguityKind(), d.getElementId(), d.getChosen()));
        return b.construir();
    }

    /**
     * El backend Spring Boot generado a partir del diagrama.
     *
     * Sale del MISMO `leer()` que el snapshot y que la Vista Logica, y de la
     * misma derivacion: lo que el usuario vio en la pestana Logico es,
     * literalmente, lo que se lleva en el zip. Si el generador leyera por su
     * cuenta, las dos cosas podrian divergir sin que nadie se entere.
     */
    @Transactional(readOnly = true)
    public Optional<ProyectoGenerado> codigoDe(UUID diagramId) {
        return diagrams.findById(diagramId).map(d -> {
            Modelo m = leer(d.getId());
            return GeneradorSpringBoot.generar(
                    GeneradorSpringBoot.Opciones.por(d.getName()),
                    m.clases(), m.atributos(), m.metodos(), m.relaciones(),
                    decisionesDe(diagramId));
        });
    }

    /**
     * Reparaciones deterministas y semánticamente seguras detectadas antes de
     * generar. Se proponen, nunca se escriben solas: el usuario conserva la
     * confirmación de toda modificación del modelo.
     */
    @Transactional(readOnly = true)
    public Optional<List<CorreccionPreGeneracion>> correccionesPreGeneracionDe(UUID diagramId) {
        return diagrams.findById(diagramId).map(d -> {
            Modelo m = leer(d.getId());
            Map<UUID, String> nombres = m.clases().stream()
                    .collect(Collectors.toMap(UmlClass::getId, UmlClass::getName));
            List<CorreccionPreGeneracion> resultado = new ArrayList<>();
            for (UmlRelationship r : m.relaciones()) {
                String sugerido = nombreSugerido(r.getName(), r.getKind().name(),
                        nombres.get(r.getTargetClassId()), r.getTargetMultiplicity(), r.getTargetRole());
                if (sugerido == null) continue;
                resultado.add(new CorreccionPreGeneracion(r.getId(), "UPDATE_RELATION", sugerido,
                        "Renombrar la relación \"" + (r.getName() == null || r.getName().isBlank()
                                ? "sin nombre" : r.getName()) + "\" a \"" + sugerido + "\"."));
            }
            return List.copyOf(resultado);
        });
    }

    public record CorreccionPreGeneracion(UUID relationId, String type, String name, String descripcion) { }

    /**
     * Nombre propuesto para una relación sin nombre o con uno de relleno
     * ("association", "relatesTo", "relacionaConX"...), o null si el nombre
     * actual ya dice algo. No conoce ningún dominio: usa el rol del destino
     * si lo hay, y si no "tiene" + el destino, en plural cuando son muchos.
     */
    static String nombreSugerido(String actual, String kind, String destino, String multiplicidadDestino,
                                 String rolDestino) {
        if ("GENERALIZATION".equals(kind) || "DEPENDENCY".equals(kind)) return null;
        if (!nombreDeRelleno(actual) || destino == null || destino.isBlank()) return null;
        String sugerido;
        if (rolDestino != null && !rolDestino.isBlank()) {
            sugerido = camel(rolDestino, false);
        } else {
            boolean muchos = multiplicidadDestino != null && multiplicidadDestino.contains("*");
            sugerido = "tiene" + (muchos ? plural(camel(destino, true)) : camel(destino, true));
        }
        return sugerido.equals(actual) ? null : sugerido;
    }

    private static boolean nombreDeRelleno(String nombre) {
        if (nombre == null || nombre.isBlank()) return true;
        String n = normal(nombre);
        return n.equals("association") || n.equals("asociacion") || n.equals("relation")
                || n.equals("relacion") || n.equals("relationship") || n.equals("relatesto")
                || n.equals("belongsto") || n.equals("hasto") || n.startsWith("relacionacon");
    }

    private static String camel(String valor, boolean pascal) {
        String[] partes = Normalizer.normalize(valor, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .replaceAll("[^A-Za-z0-9]+", " ").trim().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < partes.length; i++) {
            if (partes[i].isEmpty()) continue;
            boolean mayuscula = pascal || i > 0;
            sb.append(mayuscula ? Character.toUpperCase(partes[i].charAt(0)) : Character.toLowerCase(partes[i].charAt(0)))
                    .append(partes[i].substring(1));
        }
        return sb.toString();
    }

    /** Plural regular del español: Cita -> Citas, Autor -> Autores, Luz -> Luces. */
    private static String plural(String nombre) {
        if (nombre.isEmpty()) return nombre;
        char ultima = Character.toLowerCase(nombre.charAt(nombre.length() - 1));
        if (ultima == 's') return nombre;
        if (ultima == 'z') return nombre.substring(0, nombre.length() - 1) + "ces";
        return nombre + ("aeiou".indexOf(ultima) >= 0 ? "s" : "es");
    }

    private static String normal(String valor) {
        return Normalizer.normalize(valor == null ? "" : valor, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").replaceAll("[^A-Za-z0-9]", "")
                .toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * La Vista Fisica: el esquema de PostgreSQL, navegable.
     *
     * Sale de la MISMA derivacion que la Vista Logica y que el generador. El
     * `script` no se arma aca sino en `GeneradorSpringBoot.ddl()`, que es el
     * unico productor de DDL: lo que se muestra es, literalmente, el archivo
     * que va a salir en el zip.
     */
    /**
     * Cache de la Vista Fisica, una entrada por diagrama, valida para un `seq`.
     *
     * Derivar el esquema recorre el modelo entero y vuelve a generar el DDL:
     * medido sobre un diagrama de 17 tablas, ~60 ms por pedido. Mientras el
     * `seq` no se mueva el resultado es identico, asi que repetir el pedido
     * --cambiar de pestana, volver, dos personas mirando-- sale gratis.
     *
     * El `seq` es una llave suficiente porque TODA escritura pasa por el bus y
     * lo incrementa (ADR-002), incluidas las decisiones de ambiguedad. No hay
     * forma de cambiar el esquema sin cambiar el `seq`.
     */
    private final Map<UUID, EsquemaCacheado> cacheFisica = new ConcurrentHashMap<>();

    private record EsquemaCacheado(long seq, EsquemaFisico esquema) { }

    /**
     * El esquema y su version, en UNA sola transaccion.
     *
     * Separarlo en dos metodos transaccionales --uno para la version del ETag y
     * otro para el esquema-- costaba una transaccion y una conexion extra por
     * pedido, y eso se medía: el camino "con cache" salia mas lento que el
     * original sin cache. Las dos cosas se resuelven de la misma lectura.
     */
    @Transactional(readOnly = true)
    public Optional<EsquemaConVersion> fisicaConVersion(UUID diagramId) {
        Diagram diagrama = diagrams.findById(diagramId).orElse(null);
        if (diagrama == null) return Optional.empty();

        long seq = diagrama.getCurrentSeq();
        EsquemaCacheado guardado = cacheFisica.get(diagramId);
        if (guardado != null && guardado.seq() == seq) {
            log.debug("Vista Fisica: cache HIT en seq={}", seq);
            return Optional.of(new EsquemaConVersion(seq, guardado.esquema()));
        }

        log.debug("Vista Fisica: cache MISS en seq={}, derivando", seq);
        return logicaDe(diagramId)
                .map(DiagramSnapshotService::aEsquemaFisico)
                // La confirmacion entra al cache junto con el esquema: solo cambia
                // por CONFIRM_SCHEMA, que pasa por el bus y mueve el seq como
                // cualquier otra escritura.
                .map(e -> e.conConfirmacion(confirmacionDe(diagramId, e)))
                .map(e -> {
                    cacheFisica.put(diagramId, new EsquemaCacheado(seq, e));
                    return new EsquemaConVersion(seq, e);
                });
    }

    /**
     * Lo que el equipo confirmo, comparado con el esquema de hoy (ADR-032).
     *
     * El DDL confirmado viaja solo si ya no coincide: es lo que permite mostrar
     * QUE cambio desde la confirmacion, y cuando coincide seria el mismo texto
     * dos veces.
     */
    private EsquemaFisico.Confirmacion confirmacionDe(UUID diagramId, EsquemaFisico actual) {
        return confirmaciones.findById(diagramId).map(c -> {
            boolean vigente = c.getDdlHash().equals(actual.hash());
            String por = c.getConfirmedBy() == null ? null
                    : users.findById(c.getConfirmedBy()).map(AppUser::getFullName).orElse(null);
            return new EsquemaFisico.Confirmacion(c.getDdlHash(), c.getSeq(), por, c.getConfirmedAt(),
                    vigente, vigente ? null : c.getDdl());
        }).orElse(null);
    }

    public record EsquemaConVersion(long seq, EsquemaFisico esquema) { }

    @Transactional(readOnly = true)
    public Optional<EsquemaFisico> fisicaDe(UUID diagramId) {
        return fisicaConVersion(diagramId).map(EsquemaConVersion::esquema);
    }

    /** Visible para el test que comprueba que el detalle y el script no divergen. */
    static EsquemaFisico aEsquemaFisico(ModeloLogico logico) {
        // Quien apunta a quien, calculado UNA vez: con 20 tablas, recorrer
        // todas por cada una para armar el inverso es cuadratico y se nota.
        Map<String, List<String>> referidaPor = new LinkedHashMap<>();
        for (ModeloLogico.Tabla t : logico.tablas()) {
            for (ModeloLogico.Columna c : t.columnas()) {
                if (c.referencia() == null) continue;
                String destino = c.referencia().substring(0, Math.max(0, c.referencia().indexOf('(')));
                referidaPor.computeIfAbsent(destino, k -> new ArrayList<>());
                if (!referidaPor.get(destino).contains(t.nombre())) {
                    referidaPor.get(destino).add(t.nombre());
                }
            }
        }

        List<EsquemaFisico.Tabla> tablas = new ArrayList<>(logico.tablas().size());
        int columnas = 0;
        int fks = 0;
        int indices = 0;
        int restricciones = 0;

        for (ModeloLogico.Tabla t : logico.tablas()) {
            List<EsquemaFisico.Columna> cols = new ArrayList<>(t.columnas().size());
            List<EsquemaFisico.Indice> susIndices = new ArrayList<>();

            for (ModeloLogico.Columna c : t.columnas()) {
                String destino = null;
                String nombreFk = null;
                if (c.referencia() != null) {
                    int parentesis = c.referencia().indexOf('(');
                    destino = parentesis < 0 ? c.referencia() : c.referencia().substring(0, parentesis);
                    nombreFk = GeneradorSpringBoot.nombreDeFk(t, c);
                    fks++;
                }
                if (GeneradorSpringBoot.necesitaIndice(t, c)) {
                    susIndices.add(new EsquemaFisico.Indice(
                            GeneradorSpringBoot.nombreDeIndice(t, c), c.nombre()));
                    indices++;
                }
                String check = checkDe(c);
                if (check != null) restricciones++;
                if (c.unique()) restricciones++;

                cols.add(new EsquemaFisico.Columna(c.nombre(), c.tipo(), c.pk(), c.nullable(),
                        c.unique(), destino, c.onDelete(), nombreFk, check,
                        c.atributoId(), c.relationId(), c.extremo()));
                columnas++;
            }

            List<String> pk = t.columnas().stream().filter(ModeloLogico.Columna::pk)
                    .map(ModeloLogico.Columna::nombre).toList();
            tablas.add(new EsquemaFisico.Tabla(t.nombre(), t.origen(), List.copyOf(cols),
                    pk, List.copyOf(susIndices),
                    List.copyOf(referidaPor.getOrDefault(t.nombre(), List.of())), t.claseId()));
        }

        String script = GeneradorSpringBoot.ddl(logico);

        return new EsquemaFisico(
                "PostgreSQL",
                new EsquemaFisico.Resumen(tablas.size(), columnas, fks, indices, restricciones),
                List.copyOf(tablas),
                script,
                logico.advertencias(),
                EsquemaFisico.huellaDe(script),
                null);
    }

    /** La condicion del CHECK tal como va al DDL, o null si la columna no lleva. */
    private static String checkDe(ModeloLogico.Columna c) {
        if (c.rango() != null) return c.rango().condicionSql(c.nombre());
        if (c.enumeracion() != null) {
            return c.nombre() + " IN ("
                    + c.enumeracion().literales().stream().map(l -> "'" + l + "'")
                            .collect(Collectors.joining(", "))
                    + ")";
        }
        return null;
    }

    /** Lee el modelo vivo una sola vez: las tres proyecciones parten de aca. */
    private Modelo leer(UUID diagramId) {
        List<UmlClass> vivas = classes.findByDiagramIdAndDeletedAtIsNullOrderByNameAsc(diagramId);
        List<UUID> ids = vivas.stream().map(UmlClass::getId).toList();
        // Una sola consulta para todos los atributos: sin esto seria un N+1.
        List<UmlAttribute> atributos = ids.isEmpty()
                ? List.of()
                : attributes.findByClassIdInOrderByOrdinalAsc(ids);
        List<UmlMethod> metodos = ids.isEmpty()
                ? List.of()
                : methods.findByClassIdInOrderByOrdinalAsc(ids);
        return new Modelo(vivas, atributos, metodos,
                relationships.findByDiagramIdAndDeletedAtIsNull(diagramId));
    }

    private record Modelo(List<UmlClass> clases, List<UmlAttribute> atributos,
                          List<UmlMethod> metodos, List<UmlRelationship> relaciones) { }

    private DiagramSnapshot armar(Diagram d, Modelo m) {
        Map<UUID, List<DiagramSnapshot.Atributo>> porClase = m.atributos().stream()
                .collect(Collectors.groupingBy(UmlAttribute::getClassId,
                        Collectors.mapping(DiagramSnapshotService::aDto, Collectors.toList())));
        Map<UUID, List<DiagramSnapshot.Metodo>> metodosPorClase = m.metodos().stream()
                .collect(Collectors.groupingBy(UmlMethod::getClassId,
                        Collectors.mapping(this::aDto, Collectors.toList())));

        return new DiagramSnapshot(
                d.getId(),
                d.getName(),
                d.getCurrentSeq(),
                d.getDefaultView(),
                m.clases().stream().map(c -> aDto(c,
                        porClase.getOrDefault(c.getId(), List.of()),
                        metodosPorClase.getOrDefault(c.getId(), List.of()))).toList(),
                m.relaciones().stream().map(DiagramSnapshotService::aDto).toList(),
                layouts.findByDiagramId(d.getId()).stream().map(this::aDto).toList());
    }

    private static DiagramSnapshot.Clase aDto(UmlClass c, List<DiagramSnapshot.Atributo> atributos,
                                              List<DiagramSnapshot.Metodo> metodos) {
        return new DiagramSnapshot.Clase(c.getId(), c.getDiagramId(), c.getName(), c.getStereotype(),
                c.isAbstract(), c.isWeak(), c.getErKind(), c.getInheritanceStrategy(), atributos, metodos);
    }

    /** Los parametros viajan tipados, igual que los quiebres: el cliente no parsea JSON crudo. */
    private DiagramSnapshot.Metodo aDto(UmlMethod m) {
        return new DiagramSnapshot.Metodo(m.getId(), m.getClassId(), m.getName(), m.getReturnType(),
                parametrosDe(m.getParams()), m.getVisibility(), m.getOrdinal());
    }

    private List<DiagramSnapshot.Parametro> parametrosDe(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<DiagramSnapshot.Parametro> params = new ArrayList<>();
            for (JsonNode p : mapper.readTree(json)) {
                params.add(new DiagramSnapshot.Parametro(
                        p.path("name").asText(""), p.path("type").asText("string")));
            }
            return params;
        } catch (JsonProcessingException e) {
            // Una firma corrupta no puede tumbar el diagrama: va sin parametros.
            return List.of();
        }
    }

    private static DiagramSnapshot.Atributo aDto(UmlAttribute a) {
        return new DiagramSnapshot.Atributo(a.getId(), a.getClassId(), a.getName(), a.getDataType(),
                a.getVisibility(), a.getLength(), a.isPk(), a.isRequired(), a.isUnique(), a.getOrdinal(),
                a.isMultivalued(), a.isDerived(), a.isPartialKey(), a.getParentAttributeId(),
                a.getDefaultValue(), a.getMinValue(), a.getMaxValue());
    }

    private static DiagramSnapshot.Relacion aDto(UmlRelationship r) {
        return new DiagramSnapshot.Relacion(r.getId(), r.getDiagramId(), r.getSourceClassId(),
                r.getTargetClassId(), r.getKind(), r.getName(), r.getSourceRole(), r.getTargetRole(),
                r.getSourceMultiplicity(), r.getTargetMultiplicity(), r.isIdentifying(),
                r.getSourceParticipation(), r.getTargetParticipation(), r.getDegree());
    }

    private DiagramSnapshot.Layout aDto(DiagramLayout l) {
        return new DiagramSnapshot.Layout(l.getElementId(), l.getX(), l.getY(), l.getWidth(),
                l.getHeight(), puntosDe(l.getWaypoints()));
    }

    /** Los quiebres viajan tipados, no como JSON crudo que el cliente tenga que parsear. */
    private List<DiagramSnapshot.Punto> puntosDe(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            JsonNode raiz = mapper.readTree(json);
            List<DiagramSnapshot.Punto> puntos = new ArrayList<>();
            for (JsonNode p : raiz) {
                puntos.add(new DiagramSnapshot.Punto(p.path("x").asDouble(), p.path("y").asDouble()));
            }
            return puntos;
        } catch (JsonProcessingException e) {
            // Una fila corrupta no puede tumbar el diagrama entero: va recta.
            return List.of();
        }
    }
}
