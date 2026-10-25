package com.uagrm.colabmodel.project;

import com.uagrm.colabmodel.iam.AppUser;
import com.uagrm.colabmodel.iam.AppUserRepository;
import com.uagrm.colabmodel.project.AccesoAProyecto.Accion;
import com.uagrm.colabmodel.project.AccesoAProyecto.Rol;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Proyectos y diagramas: crearlos, verlos, renombrarlos, eliminarlos (ADR-026).
 *
 * Quien puede hacer cada cosa NO se decide aca: se le pregunta a
 * `AccesoAProyecto`. Este servicio solo dice que accion es.
 *
 * Un proyecto nace con su primer diagrama: un proyecto vacio no se puede abrir
 * en ningun lado, y obligaria a la interfaz a explicar un estado que casi nadie
 * quiere. Despues se puede eliminar el ultimo diagrama; eso si es una decision
 * del propietario.
 */
@Service
public class GestionDeProyectos {

    public static final String PRIMER_DIAGRAMA = "Diagrama principal";
    private static final int LARGO_MAXIMO_NOMBRE = 160;

    public record Persona(UUID userId, String email, String fullName) { }

    public record ProyectoResumen(UUID id, String name, Rol myRole, Persona owner,
                                  int diagramCount, OffsetDateTime createdAt) { }

    public record DiagramaResumen(UUID id, String name, long currentSeq,
                                  OffsetDateTime createdAt, OffsetDateTime updatedAt) { }

    public record ProyectoDetalle(UUID id, String name, Rol myRole, Persona owner,
                                  List<DiagramaResumen> diagrams, OffsetDateTime createdAt) { }

    private final ProjectRepository projects;
    private final ProjectMemberRepository members;
    private final DiagramRepository diagrams;
    private final AppUserRepository users;
    private final AccesoAProyecto acceso;
    private final ApplicationEventPublisher eventos;

    public GestionDeProyectos(ProjectRepository projects, ProjectMemberRepository members,
                              DiagramRepository diagrams, AppUserRepository users,
                              AccesoAProyecto acceso, ApplicationEventPublisher eventos) {
        this.projects = projects;
        this.members = members;
        this.diagrams = diagrams;
        this.users = users;
        this.acceso = acceso;
        this.eventos = eventos;
    }

    // ------------------------------------------------------------- proyectos

    /** Los propios y aquellos donde soy miembro, con mi rol, el propietario y cuantos diagramas tienen. */
    @Transactional(readOnly = true)
    public List<ProyectoResumen> misProyectos(UUID yo) {
        Map<UUID, Rol> roles = acceso.rolesDe(yo);
        if (roles.isEmpty()) return List.of();

        List<Project> mios = projects.findAllById(roles.keySet());
        Map<UUID, Persona> duenios = personas(mios.stream().map(Project::getOwnerId).distinct().toList());
        Map<UUID, Integer> cantidades = new HashMap<>();
        for (Object[] fila : diagrams.contarPorProyecto(roles.keySet())) {
            cantidades.put((UUID) fila[0], ((Number) fila[1]).intValue());
        }

        return mios.stream()
                .sorted(Comparator.comparing(Project::getName, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(Project::getCreatedAt))
                .map(p -> new ProyectoResumen(p.getId(), p.getName(), roles.get(p.getId()),
                        duenios.get(p.getOwnerId()), cantidades.getOrDefault(p.getId(), 0), p.getCreatedAt()))
                .toList();
    }

    @Transactional
    public ProyectoDetalle crear(UUID yo, String nombre) {
        Project p = new Project();
        p.setName(nombreValido(nombre));
        p.setOwnerId(yo);
        p = projects.save(p);

        Diagram primero = new Diagram();
        primero.setProjectId(p.getId());
        primero.setName(PRIMER_DIAGRAMA);
        diagrams.save(primero);

        return detalleDe(p, Rol.OWNER);
    }

    @Transactional(readOnly = true)
    public ProyectoDetalle detalle(UUID projectId, UUID yo) {
        Rol rol = acceso.exigir(projectId, yo, Accion.LEER);
        return detalleDe(proyecto(projectId), rol);
    }

    @Transactional
    public ProyectoDetalle renombrar(UUID projectId, UUID yo, String nombre) {
        Rol rol = acceso.exigir(projectId, yo, Accion.ADMINISTRAR_PROYECTO);
        Project p = proyecto(projectId);
        p.setName(nombreValido(nombre));
        return detalleDe(projects.save(p), rol);
    }

    /**
     * Borra el proyecto y, por las FK en cascada de la base, sus diagramas y
     * todo lo que cuelga de ellos. Es lo unico que no se puede deshacer, por eso
     * la interfaz pide escribir el nombre. Los que lo tenian abierto se enteran
     * por el evento.
     */
    @Transactional
    public void eliminar(UUID projectId, UUID yo) {
        acceso.exigir(projectId, yo, Accion.ADMINISTRAR_PROYECTO);
        Project p = proyecto(projectId);

        List<UUID> ids = diagrams.findByProjectId(projectId).stream().map(Diagram::getId).toList();
        List<UUID> quienes = new ArrayList<>();
        quienes.add(p.getOwnerId());
        members.findByProjectId(projectId).forEach(m -> quienes.add(m.getUserId()));

        projects.delete(p);
        eventos.publishEvent(new RecursoEliminado(projectId, p.getName(), null, ids, quienes));
    }

    // ------------------------------------------------------------ diagramas

    @Transactional
    public DiagramaResumen crearDiagrama(UUID projectId, UUID yo, String nombre) {
        acceso.exigir(projectId, yo, Accion.CREAR_DIAGRAMA);
        Diagram d = new Diagram();
        d.setProjectId(projectId);
        d.setName(nombreValido(nombre));
        return resumenDe(diagrams.save(d));
    }

    @Transactional
    public DiagramaResumen renombrarDiagrama(UUID diagramId, UUID yo, String nombre) {
        Diagram d = diagramaDe(diagramId);
        acceso.exigir(d.getProjectId(), yo, Accion.CREAR_DIAGRAMA);
        d.setName(nombreValido(nombre));
        d.setUpdatedAt(OffsetDateTime.now());
        return resumenDe(diagrams.save(d));
    }

    @Transactional
    public void eliminarDiagrama(UUID diagramId, UUID yo) {
        Diagram d = diagramaDe(diagramId);
        acceso.exigir(d.getProjectId(), yo, Accion.ELIMINAR_DIAGRAMA);
        Project p = proyecto(d.getProjectId());

        List<UUID> quienes = new ArrayList<>();
        quienes.add(p.getOwnerId());
        members.findByProjectId(p.getId()).forEach(m -> quienes.add(m.getUserId()));

        diagrams.delete(d);
        eventos.publishEvent(new RecursoEliminado(p.getId(), p.getName(), diagramId, List.of(diagramId), quienes));
    }

    // -------------------------------------------------------------- interno

    /** Un diagrama que no existe se trata como uno ajeno (403), igual que GET /diagrams/{id}. */
    private Diagram diagramaDe(UUID diagramId) {
        return diagrams.findById(diagramId).orElseThrow(AccesoAProyecto::noEsMiembro);
    }

    private Project proyecto(UUID projectId) {
        return projects.findById(projectId).orElseThrow(AccesoAProyecto::noEsMiembro);
    }

    private ProyectoDetalle detalleDe(Project p, Rol miRol) {
        Persona duenio = personas(List.of(p.getOwnerId())).get(p.getOwnerId());
        List<DiagramaResumen> lista = diagrams.findByProjectIdOrderByCreatedAtAsc(p.getId()).stream()
                .map(GestionDeProyectos::resumenDe).toList();
        return new ProyectoDetalle(p.getId(), p.getName(), miRol, duenio, lista, p.getCreatedAt());
    }

    private Map<UUID, Persona> personas(List<UUID> ids) {
        return users.findAllById(ids).stream()
                .collect(Collectors.toMap(AppUser::getId,
                        u -> new Persona(u.getId(), u.getEmail(), u.getFullName()), (a, b) -> a));
    }

    private static DiagramaResumen resumenDe(Diagram d) {
        return new DiagramaResumen(d.getId(), d.getName(), d.getCurrentSeq(), d.getCreatedAt(), d.getUpdatedAt());
    }

    private static String nombreValido(String nombre) {
        String limpio = nombre == null ? "" : nombre.trim();
        if (limpio.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El nombre es obligatorio");
        }
        if (limpio.length() > LARGO_MAXIMO_NOMBRE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El nombre no puede pasar de " + LARGO_MAXIMO_NOMBRE + " caracteres");
        }
        return limpio;
    }
}
