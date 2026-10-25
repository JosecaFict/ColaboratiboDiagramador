package com.uagrm.colabmodel.project;

import com.uagrm.colabmodel.iam.AppUser;
import com.uagrm.colabmodel.iam.AppUserRepository;
import com.uagrm.colabmodel.project.AccesoAProyecto.Accion;
import com.uagrm.colabmodel.project.AccesoAProyecto.Rol;
import com.uagrm.colabmodel.project.CambioDeAcceso.Motivo;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Quien es miembro de un proyecto y con que rol (ADR-026).
 *
 * Los invariantes viven aca, no en los controladores ni en la interfaz:
 *
 *  - Siempre hay exactamente UN propietario, y nunca tiene fila en
 *    `project_members`. Nadie lo asigna por el endpoint de roles, ni lo quita,
 *    ni lo degrada; cambia solo con `transferir`.
 *  - Quien puede hacer que lo decide `AccesoAProyecto`. Aca no se compara ningun
 *    rol: se pregunta.
 *  - Toda operacion bloquea la fila del proyecto (`findParaModificar`), asi dos
 *    pedidos simultaneos --transferir y quitar, por ejemplo-- se serializan en
 *    vez de leer los dos el estado viejo.
 *
 * Cada cambio publica un `CambioDeAcceso` que se entrega despues del commit: la
 * capa de WebSocket lo usa para aplicarlo en caliente (soltar locks, avisar,
 * cortar suscripciones) sin que `project` dependa de `collab`.
 */
@Service
public class GestionDeMiembros {

    /** El propietario tambien aparece en la lista, aunque no tenga fila. */
    public record Miembro(UUID userId, String email, String fullName, Rol role, OffsetDateTime addedAt) { }

    private final ProjectRepository projects;
    private final ProjectMemberRepository members;
    private final DiagramRepository diagrams;
    private final AppUserRepository users;
    private final AccesoAProyecto acceso;
    private final ApplicationEventPublisher eventos;

    public GestionDeMiembros(ProjectRepository projects, ProjectMemberRepository members,
                             DiagramRepository diagrams, AppUserRepository users,
                             AccesoAProyecto acceso, ApplicationEventPublisher eventos) {
        this.projects = projects;
        this.members = members;
        this.diagrams = diagrams;
        this.users = users;
        this.acceso = acceso;
        this.eventos = eventos;
    }

    /** Los tres roles ven la lista: el propietario primero, despues por antiguedad. */
    @Transactional(readOnly = true)
    public List<Miembro> listar(UUID projectId, UUID quien) {
        acceso.exigir(projectId, quien, Accion.LEER);
        Project project = projects.findById(projectId).orElseThrow(AccesoAProyecto::noEsMiembro);
        List<ProjectMember> filas = members.findByProjectId(projectId);

        List<UUID> ids = new ArrayList<>();
        ids.add(project.getOwnerId());
        filas.forEach(f -> ids.add(f.getUserId()));
        Map<UUID, AppUser> personas = users.findAllById(ids).stream()
                .collect(Collectors.toMap(AppUser::getId, Function.identity()));

        List<Miembro> lista = new ArrayList<>();
        AppUser duenio = personas.get(project.getOwnerId());
        lista.add(new Miembro(project.getOwnerId(), duenio.getEmail(), duenio.getFullName(),
                Rol.OWNER, project.getCreatedAt()));

        filas.stream()
                .sorted(Comparator.comparing(ProjectMember::getAddedAt))
                .forEach(f -> AccesoAProyecto.rolDeTexto(f.getRole()).ifPresent(rol -> {
                    AppUser u = personas.get(f.getUserId());
                    lista.add(new Miembro(u.getId(), u.getEmail(), u.getFullName(), rol, f.getAddedAt()));
                }));
        return lista;
    }

    /** Solo usuarios ya registrados: no hay invitaciones por correo (fuera de alcance). */
    @Transactional
    public Miembro agregar(UUID projectId, UUID quien, String email, String rolTexto) {
        Project project = bloquear(projectId, quien, Accion.ADMINISTRAR_MIEMBROS);
        Rol rol = rolAsignable(rolTexto);

        AppUser nuevo = users.findByEmailIgnoreCase(email == null ? "" : email.trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No hay ningún usuario registrado con ese email"));

        boolean yaEsMiembro = project.getOwnerId().equals(nuevo.getId())
                || members.findByProjectIdAndUserId(projectId, nuevo.getId()).isPresent();
        if (yaEsMiembro) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ese usuario ya es miembro del proyecto");
        }

        ProjectMember fila = new ProjectMember();
        fila.setProjectId(projectId);
        fila.setUserId(nuevo.getId());
        fila.setRole(rol.name());
        fila.setAddedAt(OffsetDateTime.now());
        members.save(fila);

        publicar(project, nuevo.getId(), Motivo.AGREGADO, rol);
        return new Miembro(nuevo.getId(), nuevo.getEmail(), nuevo.getFullName(), rol, fila.getAddedAt());
    }

    @Transactional
    public Miembro cambiarRol(UUID projectId, UUID quien, UUID objetivo, String rolTexto) {
        Project project = bloquear(projectId, quien, Accion.ADMINISTRAR_MIEMBROS);
        Rol rol = rolAsignable(rolTexto);

        if (project.getOwnerId().equals(objetivo)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "El propietario no se puede degradar: para cambiarlo, transferí la propiedad");
        }
        ProjectMember fila = members.findByProjectIdAndUserId(projectId, objetivo)
                .orElseThrow(GestionDeMiembros::noEsMiembroElObjetivo);

        boolean cambia = AccesoAProyecto.rolDeTexto(fila.getRole()).orElse(null) != rol;
        if (cambia) {
            fila.setRole(rol.name());
            members.save(fila);
            publicar(project, objetivo, Motivo.ROL_CAMBIADO, rol);
        }

        AppUser u = users.findById(objetivo).orElseThrow(GestionDeMiembros::noEsMiembroElObjetivo);
        return new Miembro(u.getId(), u.getEmail(), u.getFullName(), rol, fila.getAddedAt());
    }

    /**
     * El propietario quita a otro, o cada uno se quita a si mismo (abandona).
     * El propietario no puede abandonar: primero transfiere.
     */
    @Transactional
    public void quitar(UUID projectId, UUID quien, UUID objetivo) {
        Project project = projects.findParaModificar(projectId).orElseThrow(AccesoAProyecto::noEsMiembro);
        boolean abandona = quien.equals(objetivo);
        acceso.exigir(project, quien, abandona ? Accion.ABANDONAR : Accion.ADMINISTRAR_MIEMBROS);

        ProjectMember fila = members.findByProjectIdAndUserId(projectId, objetivo)
                .orElseThrow(GestionDeMiembros::noEsMiembroElObjetivo);
        members.delete(fila);

        publicar(project, objetivo, abandona ? Motivo.ABANDONO : Motivo.QUITADO, null);
    }

    /**
     * Una sola transaccion: `owner_id` pasa al destinatario (que ya tiene que
     * ser miembro), se borra su fila, y el propietario anterior queda como
     * EDITOR. Si cualquier paso falla no cambia nada: nunca hay dos
     * propietarios, ni ninguno, ni un propietario que ademas tenga fila.
     */
    @Transactional
    public void transferir(UUID projectId, UUID quien, UUID destinatario) {
        Project project = bloquear(projectId, quien, Accion.TRANSFERIR_PROPIEDAD);

        if (project.getOwnerId().equals(destinatario)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ya sos el propietario de este proyecto");
        }
        ProjectMember filaDelDestinatario = members.findByProjectIdAndUserId(projectId, destinatario)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Ese usuario no es miembro del proyecto: agregalo antes de transferirle la propiedad"));

        UUID anterior = project.getOwnerId();

        members.delete(filaDelDestinatario);
        project.setOwnerId(destinatario);
        projects.save(project);

        ProjectMember filaDelAnterior = new ProjectMember();
        filaDelAnterior.setProjectId(projectId);
        filaDelAnterior.setUserId(anterior);
        filaDelAnterior.setRole(Rol.EDITOR.name());
        filaDelAnterior.setAddedAt(OffsetDateTime.now());
        members.save(filaDelAnterior);

        publicar(project, destinatario, Motivo.PROPIEDAD_RECIBIDA, Rol.OWNER);
        publicar(project, anterior, Motivo.PROPIEDAD_CEDIDA, Rol.EDITOR);
    }

    // ------------------------------------------------------------ interno

    /** El proyecto quieto (fila bloqueada) y la accion ya autorizada sobre ese estado. */
    private Project bloquear(UUID projectId, UUID quien, Accion accion) {
        Project project = projects.findParaModificar(projectId).orElseThrow(AccesoAProyecto::noEsMiembro);
        acceso.exigir(project, quien, accion);
        return project;
    }

    private Rol rolAsignable(String texto) {
        Rol rol = AccesoAProyecto.rolDeTexto(texto).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "El rol tiene que ser EDITOR o VIEWER"));
        if (!acceso.esAsignable(rol)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "La propiedad no se asigna con un rol: usá «Transferir propiedad»");
        }
        return rol;
    }

    private void publicar(Project project, UUID usuario, Motivo motivo, Rol rol) {
        List<UUID> ids = diagrams.findByProjectId(project.getId()).stream().map(Diagram::getId).toList();
        eventos.publishEvent(new CambioDeAcceso(project.getId(), project.getName(), ids, usuario, motivo, rol));
    }

    private static ResponseStatusException noEsMiembroElObjetivo() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Ese usuario no es miembro del proyecto");
    }
}
