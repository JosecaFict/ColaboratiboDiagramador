package com.uagrm.colabmodel.versiones;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.uagrm.colabmodel.collab.LockManager;
import com.uagrm.colabmodel.iam.AppUser;
import com.uagrm.colabmodel.iam.AppUserRepository;
import com.uagrm.colabmodel.modelcore.command.*;
import com.uagrm.colabmodel.modelcore.metamodel.ModelingDecisionRepository;
import com.uagrm.colabmodel.project.AccesoAProyecto;
import com.uagrm.colabmodel.project.Diagram;
import com.uagrm.colabmodel.project.DiagramRepository;
import com.uagrm.colabmodel.project.DiagramSnapshotService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * Control de versiones tipo Git sobre el log de operaciones (ADR-037).
 *
 * Todo lo que ESCRIBE el modelo --restaurar, copiar a una rama, mergear-- se
 * traduce a comandos del catalogo cerrado y entra como BATCH por el bus
 * (ADR-002, ADR-014). Lo unico que este servicio escribe por su cuenta son sus
 * propias tablas: versiones, columnas de rama y la base de merges.
 *
 * Permisos con AccesoAProyecto: los tres roles leen; OWNER y EDITOR escriben.
 */
@Service
public class ServicioDeVersiones {

    private static final Logger log = LoggerFactory.getLogger(ServicioDeVersiones.class);

    /** Lo que un BATCH puede llevar (Lote.TOPE_IMPORTACION, origen MOUSE). */
    static final int TOPE_DE_LOTE = Lote.TOPE_IMPORTACION;

    private final DiagramSnapshotService snapshots;
    private final ModelingDecisionRepository decisiones;
    private final DiagramVersionRepository versiones;
    private final DiagramMergeRepository merges;
    private final DiagramRepository diagrams;
    private final Ramas ramas;
    private final CommandBus bus;
    private final LockManager locks;
    private final AccesoAProyecto acceso;
    private final AppUserRepository users;
    private final SimpMessagingTemplate broker;
    private final ObjectMapper mapper;
    private final TransactionTemplate lecturaConsistente;

    public ServicioDeVersiones(DiagramSnapshotService snapshots, ModelingDecisionRepository decisiones,
                               DiagramVersionRepository versiones, DiagramMergeRepository merges,
                               DiagramRepository diagrams, Ramas ramas, CommandBus bus, LockManager locks,
                               AccesoAProyecto acceso, AppUserRepository users, SimpMessagingTemplate broker,
                               ObjectMapper mapper, PlatformTransactionManager tx) {
        this.snapshots = snapshots;
        this.decisiones = decisiones;
        this.versiones = versiones;
        this.merges = merges;
        this.diagrams = diagrams;
        this.ramas = ramas;
        this.bus = bus;
        this.locks = locks;
        this.acceso = acceso;
        this.users = users;
        this.broker = broker;
        this.mapper = mapper;
        // El modelo se lee con varias consultas (clases, atributos, metodos,
        // relaciones, geometria, decisiones). En REPEATABLE READ todas ven el
        // mismo instante: sin eso, un comando que entra en el medio deja una
        // foto que no corresponde a ningun seq.
        this.lecturaConsistente = new TransactionTemplate(tx);
        lecturaConsistente.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        lecturaConsistente.setReadOnly(true);
    }

    // ================================================================ DTOs

    public record VersionDto(UUID id, UUID diagramId, long seq, String name, String message, UUID authorId,
                             String authorName, UUID parentVersionId, OffsetDateTime createdAt) { }

    public record VersionConFoto(UUID id, UUID diagramId, long seq, String name, String message, UUID authorId,
                                 String authorName, UUID parentVersionId, OffsetDateTime createdAt,
                                 JsonNode snapshot) { }

    public record RamaDto(UUID diagramId, String name, String diagramName, UUID forkedFromDiagramId,
                          UUID forkedFromVersionId, String forkedFromVersionName, Long forkedFromSeq,
                          long currentSeq, OffsetDateTime createdAt) { }

    /** Lo que devuelve una escritura: en que seq quedo y cuantos cambios llevo el lote. */
    public record Aplicado(UUID diagramId, long seq, int cambios) { }

    public record Resolucion(UUID elementId, String campo, String eleccion) { }

    // ============================================================= COMMIT

    public VersionDto commit(UUID diagramId, UUID userId, String nombre, String mensaje) {
        exigirEscritura(diagramId, userId);
        String n = nombreValido(nombre, "La version");
        if (versiones.existsByDiagramIdAndNameIgnoreCase(diagramId, n)) {
            throw new RechazoDeVersiones(HttpStatus.CONFLICT, "Ya hay una version llamada \"" + n + "\" en este diagrama");
        }
        Foto foto = fotoActual(diagramId);
        UUID padre = versiones.findFirstByDiagramIdOrderBySeqDescCreatedAtDesc(diagramId)
                .map(DiagramVersion::getId)
                .orElseGet(() -> ramas.de(diagramId).map(Ramas.Rama::forkedFromVersionId).orElse(null));

        DiagramVersion v = new DiagramVersion();
        v.setId(UUID.randomUUID());
        v.setDiagramId(diagramId);
        v.setSeq(foto.seq);
        v.setName(n);
        v.setMessage(mensaje == null || mensaje.isBlank() ? null : mensaje.trim());
        v.setAuthorId(userId);
        v.setParentVersionId(padre);
        v.setSnapshot(foto.aJson().toString());
        v.setCreatedAt(OffsetDateTime.now());
        try {
            versiones.saveAndFlush(v);
        } catch (DataIntegrityViolationException e) {
            // Dos commits con el mismo nombre al mismo tiempo: lo decide uq_version_name.
            throw new RechazoDeVersiones(HttpStatus.CONFLICT, "Ya hay una version llamada \"" + n + "\" en este diagrama");
        }
        VersionDto dto = dto(v);
        // Solo una senal para refrescar el historial: no es parte del log de operaciones.
        broker.convertAndSend("/topic/diagram/" + diagramId + "/versions", dto);
        return dto;
    }

    public List<VersionDto> historial(UUID diagramId, UUID userId) {
        exigirLectura(diagramId, userId);
        List<DiagramVersion> lista = versiones.findByDiagramIdOrderBySeqDescCreatedAtDesc(diagramId);
        Map<UUID, String> nombres = nombresDe(lista.stream().map(DiagramVersion::getAuthorId).toList());
        return lista.stream().map(v -> dto(v, nombres.get(v.getAuthorId()))).toList();
    }

    public VersionConFoto version(UUID diagramId, UUID versionId, UUID userId) {
        exigirLectura(diagramId, userId);
        DiagramVersion v = versionDe(diagramId, versionId);
        VersionDto d = dto(v);
        return new VersionConFoto(d.id(), d.diagramId(), d.seq(), d.name(), d.message(), d.authorId(),
                d.authorName(), d.parentVersionId(), d.createdAt(), leerJson(v.getSnapshot()));
    }

    // =============================================================== DIFF

    /**
     * @param desde id de version o "actual"
     * @param hasta id de version o "actual" (null = actual)
     */
    public ObjectNode diff(UUID diagramId, String desde, String hasta, UUID userId) {
        exigirLectura(diagramId, userId);
        Extremo a = extremo(diagramId, desde);
        Extremo b = extremo(diagramId, hasta == null || hasta.isBlank() ? "actual" : hasta);
        ObjectNode r = mapper.createObjectNode();
        r.set("desde", a.aJson(mapper));
        r.set("hasta", b.aJson(mapper));
        r.setAll(Diferencias.entre(a.foto(), b.foto()));
        return r;
    }

    private record Extremo(UUID versionId, Foto foto) {
        ObjectNode aJson(ObjectMapper m) {
            ObjectNode n = m.createObjectNode();
            n.put("versionId", versionId == null ? null : versionId.toString());
            n.put("seq", foto.seq);
            return n;
        }
    }

    private Extremo extremo(UUID diagramId, String cual) {
        if (cual == null || cual.isBlank()) {
            throw new RechazoDeVersiones(HttpStatus.BAD_REQUEST, "Falta el parametro \"from\"");
        }
        if (cual.equalsIgnoreCase("actual")) return new Extremo(null, fotoActual(diagramId));
        UUID id;
        try {
            id = UUID.fromString(cual);
        } catch (IllegalArgumentException e) {
            throw new RechazoDeVersiones(HttpStatus.BAD_REQUEST, "\"" + cual + "\" no es una version ni \"actual\"");
        }
        return new Extremo(id, Foto.leer(leerJson(versionDe(diagramId, id).getSnapshot())));
    }

    // ============================================================ RESTORE

    /** Vuelve el diagrama a una version suya: un BATCH con lo que cambio desde entonces. */
    public Aplicado restaurar(UUID diagramId, UUID versionId, UUID userId) {
        exigirEscritura(diagramId, userId);
        DiagramVersion v = versionDe(diagramId, versionId);
        Foto deseada = Foto.leer(leerJson(v.getSnapshot()));
        Foto actual = fotoActual(diagramId);
        List<ObjectNode> plan = PlanDeCambios.de(actual, deseada);
        return aplicar(diagramId, userId, actual, plan, "Restaurar la version \"" + v.getName() + "\"", "restaurar");
    }

    // ============================================================= RAMAS

    /**
     * Una rama es un diagrama del mismo proyecto que nace de una version. Se
     * copia por el bus, con los ids traducidos por IdentidadLogica: en la rama
     * cada elemento tiene otro id fisico pero la misma identidad logica, y eso
     * es lo que hace posible el merge.
     */
    public RamaDto crearRama(UUID diagramId, UUID userId, String nombre, UUID desdeVersion) {
        Ramas.Rama origen = ramas.de(diagramId).orElseThrow(ServicioDeVersiones::noEsMiembro);
        exigirMiembro(diagramId, userId);
        try {
            acceso.exigir(origen.projectId(), userId, AccesoAProyecto.Accion.CREAR_DIAGRAMA);
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw new RechazoDeVersiones(HttpStatus.FORBIDDEN, e.getReason());
        }
        String n = nombreValido(nombre, "La rama");
        if (desdeVersion == null) {
            throw new RechazoDeVersiones(HttpStatus.BAD_REQUEST, "Falta \"fromVersionId\": una rama nace de una version");
        }
        DiagramVersion v = versionDe(diagramId, desdeVersion);
        if (n.equalsIgnoreCase(origen.branchName())
                || ramas.hijasDe(diagramId).stream().anyMatch(r -> r.branchName().equalsIgnoreCase(n))) {
            throw new RechazoDeVersiones(HttpStatus.CONFLICT, "Ya hay una rama llamada \"" + n + "\" en este diagrama");
        }

        Diagram d = new Diagram();
        d.setProjectId(origen.projectId());
        String nombreDiagrama = origen.diagramName() + " · " + n;
        d.setName(nombreDiagrama.length() > 160 ? nombreDiagrama.substring(0, 160) : nombreDiagrama);
        Diagram rama = diagrams.save(d);
        UUID sal = IdentidadLogica.nuevaSal();
        ramas.marcar(rama.getId(), n, diagramId, v.getId(), sal);

        try {
            Foto copia = IdentidadLogica.traducir(Foto.leer(leerJson(v.getSnapshot())), origen.idSalt(), sal);
            List<ObjectNode> plan = PlanDeCambios.de(Foto.vacia(rama.getId()), copia);
            // La rama es nueva y nadie la esta editando: se puede copiar en
            // varios lotes si el modelo no entra en uno.
            for (int i = 0; i < plan.size(); i += TOPE_DE_LOTE) {
                List<ObjectNode> parte = plan.subList(i, Math.min(plan.size(), i + TOPE_DE_LOTE));
                enviar(rama.getId(), userId, parte, "Rama \"" + n + "\" desde la version \"" + v.getName() + "\"",
                        null);
            }
        } catch (RuntimeException e) {
            // Una rama a medio copiar no sirve para mergear: se borra entera.
            log.warn("No se pudo copiar la rama {}: {}", n, e.getMessage());
            diagrams.deleteById(rama.getId());
            throw e instanceof RechazoDeVersiones r ? r
                    : new RechazoDeVersiones(HttpStatus.UNPROCESSABLE_ENTITY, "No se pudo copiar la version: " + e.getMessage());
        }
        return ramaDto(ramas.de(rama.getId()).orElseThrow());
    }

    /**
     * Donde esta parado el diagrama y que nacio de el:
     * <pre>
     * { "actual": Rama,                       // este diagrama
     *   "origen": {diagramId, name, diagramName, forkedFromVersionId, forkedFromVersionName} | null,
     *   "ramas":  [Rama, ...] }               // las que nacieron de este
     * </pre>
     * `origen` es null en un diagrama raiz; en una rama dice de que diagrama
     * traer cambios (merge con fromDiagramId = origen.diagramId).
     */
    public ObjectNode ramas(UUID diagramId, UUID userId) {
        exigirLectura(diagramId, userId);
        Ramas.Rama esta = ramas.de(diagramId).orElseThrow(ServicioDeVersiones::noEsMiembro);
        ObjectNode r = mapper.createObjectNode();
        r.set("actual", mapper.valueToTree(ramaDto(esta)));
        r.set("origen", origenDe(esta));
        var lista = r.putArray("ramas");
        ramas.hijasDe(diagramId).forEach(h -> lista.add(mapper.valueToTree(ramaDto(h))));
        return r;
    }

    private JsonNode origenDe(Ramas.Rama esta) {
        if (esta.forkedFromDiagramId() == null) return mapper.nullNode();
        ObjectNode o = mapper.createObjectNode();
        o.put("diagramId", esta.forkedFromDiagramId().toString());
        ramas.de(esta.forkedFromDiagramId()).ifPresent(p -> {
            o.put("name", p.branchName());
            o.put("diagramName", p.diagramName());
        });
        o.put("forkedFromVersionId", esta.forkedFromVersionId() == null ? null : esta.forkedFromVersionId().toString());
        DiagramVersion v = esta.forkedFromVersionId() == null ? null
                : versiones.findById(esta.forkedFromVersionId()).orElse(null);
        o.put("forkedFromVersionName", v == null ? null : v.getName());
        return o;
    }

    /** 403 con {mensaje}, como el resto de esta API: no se distingue "no existe" de "ajeno". */
    private static RechazoDeVersiones noEsMiembro() {
        return new RechazoDeVersiones(HttpStatus.FORBIDDEN, "No sos miembro de este proyecto");
    }

    private RamaDto ramaDto(Ramas.Rama r) {
        DiagramVersion v = r.forkedFromVersionId() == null ? null
                : versiones.findById(r.forkedFromVersionId()).orElse(null);
        return new RamaDto(r.diagramId(), r.branchName(), r.diagramName(), r.forkedFromDiagramId(),
                r.forkedFromVersionId(), v == null ? null : v.getName(), v == null ? null : v.getSeq(),
                r.currentSeq(), r.createdAt());
    }

    // ============================================================= MERGE

    /** Base comun, lo que cambio cada lado, lo que se aplicaria y los conflictos. */
    public ObjectNode previaDelMerge(UUID diagramId, UUID desdeDiagrama, UUID userId) {
        exigirLectura(diagramId, userId);
        Mezcla m = mezcla(diagramId, desdeDiagrama, userId, Map.of());
        ObjectNode r = mapper.createObjectNode();
        r.set("base", m.base().aJson(mapper));
        ObjectNode nuestros = Diferencias.entre(m.base().foto(), m.nuestro());
        nuestros.set("desde", m.base().aJson(mapper));
        nuestros.set("hasta", extremoActual(m.nuestro()));
        ObjectNode suyos = Diferencias.entre(m.base().foto(), m.suyo());
        suyos.set("desde", m.base().aJson(mapper));
        suyos.set("hasta", extremoActual(m.suyo()));
        r.set("nuestros", nuestros);
        r.set("suyos", suyos);
        var conflictos = r.putArray("conflictos");
        m.resultado().conflictos().forEach(c -> conflictos.add(c.aJson()));
        // Lo que llegaria a este diagrama si todos los conflictos se resuelven "nuestro".
        r.set("aplicables", Diferencias.entre(m.nuestro(), m.resultado().combinada()));
        r.put("puedeMergear", m.resultado().conflictos().isEmpty());
        r.set("origen", origenDe(ramas.de(diagramId).orElseThrow(ServicioDeVersiones::noEsMiembro)));
        return r;
    }

    public Aplicado mergear(UUID diagramId, UUID desdeDiagrama, List<Resolucion> resoluciones, UUID userId) {
        exigirEscritura(diagramId, userId);
        Map<String, Fusion.Lado> elegidas = new HashMap<>();
        for (Resolucion res : resoluciones == null ? List.<Resolucion>of() : resoluciones) {
            if (res == null || res.elementId() == null || res.campo() == null) {
                throw new RechazoDeVersiones(HttpStatus.BAD_REQUEST, "Cada resolucion necesita elementId, campo y eleccion");
            }
            Fusion.Lado lado = switch (res.eleccion() == null ? "" : res.eleccion()) {
                case "nuestro" -> Fusion.Lado.NUESTRO;
                case "suyo" -> Fusion.Lado.SUYO;
                default -> throw new RechazoDeVersiones(HttpStatus.BAD_REQUEST,
                        "eleccion tiene que ser \"nuestro\" o \"suyo\", no \"" + res.eleccion() + "\"");
            };
            elegidas.put(Fusion.clave(res.elementId(), res.campo()), lado);
        }

        Mezcla m = mezcla(diagramId, desdeDiagrama, userId, elegidas);
        if (!m.resultado().sinResolver().isEmpty()) {
            int n = m.resultado().sinResolver().size();
            throw new RechazoDeVersiones(HttpStatus.CONFLICT,
                    n == 1 ? "Falta resolver 1 conflicto" : "Faltan resolver " + n + " conflictos",
                    Map.of("conflictos", m.resultado().sinResolver().stream().map(Fusion.Conflicto::aJson).toList()));
        }
        List<ObjectNode> plan = PlanDeCambios.de(m.nuestro(), m.resultado().combinada());
        Ramas.Rama desde = ramas.de(desdeDiagrama).orElseThrow();
        Aplicado aplicado = aplicar(diagramId, userId, m.nuestro(), plan,
                "Merge de \"" + desde.branchName() + "\" (" + desde.diagramName() + ")", "mergear");

        // El estado del otro lado ahora es ancestro comun de los dos: la base
        // del proximo merge entre ellos.
        DiagramMerge registro = new DiagramMerge();
        registro.setId(UUID.randomUUID());
        registro.setTargetDiagramId(diagramId);
        registro.setFromDiagramId(desdeDiagrama);
        registro.setFromSnapshot(m.suyoOriginal().aJson().toString());
        registro.setFromSeq(m.suyoOriginal().seq);
        registro.setTargetSeq(aplicado.seq());
        registro.setAuthorId(userId);
        registro.setCreatedAt(OffsetDateTime.now());
        merges.save(registro);
        return aplicado;
    }

    /** Las tres fotos con los ids del DESTINO, y lo que sale de combinarlas. */
    private record Mezcla(BaseComun base, Foto nuestro, Foto suyo, Foto suyoOriginal, Fusion.Resultado resultado) { }

    private record BaseComun(UUID versionId, UUID diagramId, String tipo, Foto foto) {
        ObjectNode aJson(ObjectMapper m) {
            ObjectNode n = m.createObjectNode();
            n.put("versionId", versionId == null ? null : versionId.toString());
            n.put("seq", foto.seq);
            n.put("diagramId", diagramId.toString());
            // "version" = la version de la que nacio la rama; "merge" = el ultimo merge entre los dos.
            n.put("tipo", tipo);
            return n;
        }
    }

    private Mezcla mezcla(UUID diagramId, UUID desdeDiagrama, UUID userId, Map<String, Fusion.Lado> resoluciones) {
        if (desdeDiagrama == null) {
            throw new RechazoDeVersiones(HttpStatus.BAD_REQUEST, "Falta \"fromDiagramId\"");
        }
        if (desdeDiagrama.equals(diagramId)) {
            throw new RechazoDeVersiones(HttpStatus.BAD_REQUEST, "Un diagrama no se mergea consigo mismo");
        }
        exigirLectura(desdeDiagrama, userId);
        Ramas.Rama destino = ramas.de(diagramId).orElseThrow(ServicioDeVersiones::noEsMiembro);
        Ramas.Rama desde = ramas.de(desdeDiagrama).orElseThrow(ServicioDeVersiones::noEsMiembro);
        if (!destino.projectId().equals(desde.projectId())) {
            throw new RechazoDeVersiones(HttpStatus.UNPROCESSABLE_ENTITY, "Los dos diagramas son de proyectos distintos");
        }

        BaseComun base = baseComun(destino, desde);
        Foto nuestro = fotoActual(diagramId);
        Foto suyoOriginal = fotoActual(desdeDiagrama);
        Foto suyo = IdentidadLogica.traducir(suyoOriginal, desde.idSalt(), destino.idSalt());
        return new Mezcla(base, nuestro, suyo, suyoOriginal,
                Fusion.combinar(base.foto(), nuestro, suyo, resoluciones));
    }

    /**
     * El ancestro comun de dos diagramas, ya con los ids del destino.
     *
     * Si ya se mergearon entre si, la base es el estado del origen en el
     * ultimo merge (como el segundo padre de un merge commit). Si no, se sube
     * por las ramas hasta el primer diagrama comun; ahi la base es la version
     * MAS VIEJA de las dos por las que se llega, porque la historia de un
     * diagrama es lineal (el seq solo crece).
     */
    private BaseComun baseComun(Ramas.Rama destino, Ramas.Rama desde) {
        List<DiagramMerge> previos = merges.entre(destino.diagramId(), desde.diagramId());
        if (!previos.isEmpty()) {
            DiagramMerge ultimo = previos.get(0);
            UUID salDelGuardado = ramas.de(ultimo.getFromDiagramId()).map(Ramas.Rama::idSalt).orElse(null);
            Foto f = IdentidadLogica.traducir(Foto.leer(leerJson(ultimo.getFromSnapshot())),
                    salDelGuardado, destino.idSalt());
            return new BaseComun(null, ultimo.getFromDiagramId(), "merge", f);
        }

        List<Map.Entry<UUID, UUID>> subidaDestino = subida(destino);
        List<Map.Entry<UUID, UUID>> subidaDesde = subida(desde);
        for (Map.Entry<UUID, UUID> a : subidaDestino) {
            for (Map.Entry<UUID, UUID> b : subidaDesde) {
                if (!a.getKey().equals(b.getKey())) continue;
                DiagramVersion va = a.getValue() == null ? null : versiones.findById(a.getValue()).orElse(null);
                DiagramVersion vb = b.getValue() == null ? null : versiones.findById(b.getValue()).orElse(null);
                DiagramVersion elegida = va == null ? vb : vb == null ? va : (va.getSeq() <= vb.getSeq() ? va : vb);
                if (elegida == null) break;
                UUID sal = ramas.de(elegida.getDiagramId()).map(Ramas.Rama::idSalt).orElse(null);
                Foto f = IdentidadLogica.traducir(Foto.leer(leerJson(elegida.getSnapshot())), sal, destino.idSalt());
                return new BaseComun(elegida.getId(), elegida.getDiagramId(), "version", f);
            }
        }
        throw new RechazoDeVersiones(HttpStatus.UNPROCESSABLE_ENTITY,
                "Estos diagramas no comparten historia: ninguno nacio del otro ni de un mismo diagrama");
    }

    /** [(el diagrama, null), (su padre, version de la que nacio), (el abuelo, ...), ...]. */
    private List<Map.Entry<UUID, UUID>> subida(Ramas.Rama desde) {
        List<Map.Entry<UUID, UUID>> camino = new ArrayList<>();
        camino.add(new AbstractMap.SimpleEntry<>(desde.diagramId(), null));
        Ramas.Rama actual = desde;
        Set<UUID> vistos = new HashSet<>(Set.of(desde.diagramId()));
        while (actual.forkedFromDiagramId() != null && vistos.add(actual.forkedFromDiagramId())) {
            camino.add(new AbstractMap.SimpleEntry<>(actual.forkedFromDiagramId(), actual.forkedFromVersionId()));
            actual = ramas.de(actual.forkedFromDiagramId()).orElse(null);
            if (actual == null) break;
        }
        return camino;
    }

    private ObjectNode extremoActual(Foto f) {
        ObjectNode n = mapper.createObjectNode();
        n.putNull("versionId");
        n.put("seq", f.seq);
        return n;
    }

    // ===================================================== APLICAR AL BUS

    /**
     * Manda el plan como UN BATCH por el bus. Antes revisa los locks de TODO lo
     * que el lote toca --tambien lo que el bus deja pasar sin lease, como
     * borrar una clase--: restaurar o mergear encima de una clase que alguien
     * tiene abierta le borraria el trabajo en la cara.
     */
    private Aplicado aplicar(UUID diagramId, UUID userId, Foto actual, List<ObjectNode> plan,
                             String descripcion, String verbo) {
        long seqActual = actual.seq;
        if (plan.isEmpty()) return new Aplicado(diagramId, seqActual, 0);
        if (plan.size() > TOPE_DE_LOTE) {
            throw new RechazoDeVersiones(HttpStatus.UNPROCESSABLE_ENTITY, "Hacen falta " + plan.size()
                    + " cambios y un lote admite " + TOPE_DE_LOTE + ": no se puede " + verbo + " de una vez");
        }
        verificarLocks(diagramId, actual, plan, verbo);
        // El plan se calculo contra `actual.seq`: el bus lo rechaza si el
        // diagrama avanzo antes de aplicarlo (ver CommandApplier, seqEsperado).
        AppliedOperation op = enviar(diagramId, userId, plan, descripcion, seqActual);
        return new Aplicado(diagramId, op.seq(), plan.size());
    }

    private AppliedOperation enviar(UUID diagramId, UUID userId, List<ObjectNode> plan, String descripcion,
                                    Long seqEsperado) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("descripcion", descripcion);
        if (seqEsperado != null) payload.put("seqEsperado", seqEsperado);
        payload.putArray("comandos").addAll(plan);
        long base = diagrams.findById(diagramId).map(Diagram::getCurrentSeq).orElse(0L);
        Command batch = new Command(UUID.randomUUID(), diagramId, base, CommandType.BATCH, Origin.MOUSE, payload);
        try {
            // Sin sesion: un pedido REST no tiene locks propios, asi que solo
            // escribe sobre lo que nadie tiene tomado (ver Autor).
            return bus.submit(batch, new Autor(userId, null));
        } catch (CommandRejectedException e) {
            HttpStatus estado = switch (e.getReason()) {
                case LOCKED_BY_OTHER, STALE -> HttpStatus.CONFLICT;
                case FORBIDDEN -> HttpStatus.FORBIDDEN;
                case INVALID, NOT_FOUND -> HttpStatus.UNPROCESSABLE_ENTITY;
            };
            throw new RechazoDeVersiones(estado, e.getMessage());
        }
    }

    private void verificarLocks(UUID diagramId, Foto actual, List<ObjectNode> plan, String verbo) {
        Map<UUID, LockManager.Lease> tomados = locks.snapshot(diagramId);
        if (tomados.isEmpty()) return;
        for (ObjectNode c : plan) {
            JsonNode p = c.path("payload");
            UUID entidad = switch (c.path("type").asText()) {
                case "MOVE_ELEMENT", "RESIZE_ELEMENT", "SET_MODELING_DECISION" -> null;   // nivel 3 / del diagrama
                case "UPDATE_ATTRIBUTE", "REMOVE_ATTRIBUTE" -> claseDe(actual.atributos, p.path("attributeId"));
                case "UPDATE_METHOD", "REMOVE_METHOD" -> claseDe(actual.metodos, p.path("methodId"));
                case "SET_ER_FLAG" -> {
                    UUID t = uuid(p.path("targetId"));
                    yield "ATTRIBUTE".equals(p.path("targetType").asText()) ? claseDe(actual.atributos, p.path("targetId")) : t;
                }
                default -> p.has("relationId") ? uuid(p.path("relationId")) : uuid(p.path("classId"));
            };
            LockManager.Lease lease = entidad == null ? null : tomados.get(entidad);
            if (lease == null) continue;
            String nombre = Optional.ofNullable(actual.clases.get(entidad)).map(e -> e.texto("name"))
                    .or(() -> Optional.ofNullable(actual.relaciones.get(entidad)).map(e -> Diferencias.nombreDe(e, actual)))
                    .orElse("un elemento");
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("elementId", entidad.toString());
            extra.put("usuario", lease.userName());
            throw new RechazoDeVersiones(HttpStatus.CONFLICT, "No se puede " + verbo + ": \"" + nombre
                    + "\" lo esta editando " + lease.userName() + ". Cuando lo suelte, proba de nuevo.", extra);
        }
    }

    private static UUID claseDe(Map<UUID, Foto.Elemento> elementos, JsonNode id) {
        UUID e = uuid(id);
        Foto.Elemento el = e == null ? null : elementos.get(e);
        return el == null ? null : el.ref("classId");
    }

    private static UUID uuid(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) return null;
        try {
            return UUID.fromString(n.asText());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ============================================================ LECTURA

    /** El modelo vivo en un solo instante consistente, con su seq. */
    Foto fotoActual(UUID diagramId) {
        return lecturaConsistente.execute(s -> snapshots.de(diagramId)
                .map(snap -> Foto.de(snap, decisiones.findByDiagramId(diagramId)))
                .orElseThrow(ServicioDeVersiones::noEsMiembro));
    }

    private DiagramVersion versionDe(UUID diagramId, UUID versionId) {
        return versiones.findById(versionId)
                .filter(v -> v.getDiagramId().equals(diagramId))
                .orElseThrow(() -> new RechazoDeVersiones(HttpStatus.NOT_FOUND, "Esa version no es de este diagrama"));
    }

    private JsonNode leerJson(String texto) {
        try {
            return mapper.readTree(texto);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Foto guardada corrupta", e);
        }
    }

    private VersionDto dto(DiagramVersion v) {
        return dto(v, v.getAuthorId() == null ? null
                : users.findById(v.getAuthorId()).map(AppUser::getFullName).orElse(null));
    }

    private static VersionDto dto(DiagramVersion v, String autor) {
        return new VersionDto(v.getId(), v.getDiagramId(), v.getSeq(), v.getName(), v.getMessage(),
                v.getAuthorId(), autor, v.getParentVersionId(), v.getCreatedAt());
    }

    private Map<UUID, String> nombresDe(Collection<UUID> ids) {
        Map<UUID, String> r = new HashMap<>();
        users.findAllById(ids.stream().filter(Objects::nonNull).distinct().toList())
                .forEach(u -> r.put(u.getId(), u.getFullName()));
        return r;
    }

    private static String nombreValido(String nombre, String que) {
        if (nombre == null || nombre.isBlank()) {
            throw new RechazoDeVersiones(HttpStatus.BAD_REQUEST, que + " necesita un nombre");
        }
        String n = nombre.trim();
        if (n.length() > 120) {
            throw new RechazoDeVersiones(HttpStatus.BAD_REQUEST, que + " puede tener hasta 120 caracteres de nombre");
        }
        return n;
    }

    // ============================================================ PERMISOS

    private AccesoAProyecto.Rol exigirMiembro(UUID diagramId, UUID userId) {
        return acceso.rolSobreDiagrama(diagramId, userId)
                .orElseThrow(() -> new RechazoDeVersiones(HttpStatus.FORBIDDEN, "No sos miembro de este proyecto"));
    }

    private void exigirLectura(UUID diagramId, UUID userId) {
        exigirMiembro(diagramId, userId);
    }

    private void exigirEscritura(UUID diagramId, UUID userId) {
        AccesoAProyecto.Rol rol = exigirMiembro(diagramId, userId);
        if (!acceso.puedeEditar(rol)) {
            throw new RechazoDeVersiones(HttpStatus.FORBIDDEN, "Como " + rol.etiqueta() + " no podés editar el modelo");
        }
    }
}
