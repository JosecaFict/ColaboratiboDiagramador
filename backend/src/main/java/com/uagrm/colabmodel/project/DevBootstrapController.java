package com.uagrm.colabmodel.project;

import com.uagrm.colabmodel.iam.AppUser;
import com.uagrm.colabmodel.iam.AppUserRepository;
import com.uagrm.colabmodel.iam.JwtService;
import com.uagrm.colabmodel.project.AccesoAProyecto.Rol;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;
import java.util.Map;

/**
 * ATAJO DE DESARROLLO. Solo existe con el perfil `dev` (ADR-026).
 *
 * Emite un JWT real para probar sin registrarse: el flujo de la seccion 5 del
 * README (EC-11 desde `curl`). La interfaz ya NO lo usa: entra por login. Fuera
 * del perfil `dev` este controlador no se instancia y `SecurityConfig` tampoco
 * abre la ruta, asi que en produccion no hay forma de pedir un token sin
 * contrasena.
 *
 * `?role=EDITOR|VIEWER` devuelve un usuario DISTINTO del propietario, ya con su
 * fila en project_members. Sin el parametro devuelve al usuario demo, que es el
 * propietario del "Proyecto Demo" mientras nadie se lo haya transferido.
 *
 * Reutiliza el proyecto demo al que el usuario demo tenga acceso, sea propio o
 * transferido: si no, tras entregarle el proyecto a una cuenta real este atajo
 * crearia un segundo "Proyecto Demo" vacio cada vez.
 */
@Profile("dev")
@RestController
@RequestMapping("/api/v1/dev")
public class DevBootstrapController {

    private static final String EMAIL_DEMO = "demo@uagrm.edu.bo";

    private final AppUserRepository users;
    private final ProjectRepository projects;
    private final ProjectMemberRepository members;
    private final DiagramRepository diagrams;
    private final AccesoAProyecto acceso;
    private final JwtService jwt;

    public DevBootstrapController(AppUserRepository users, ProjectRepository projects,
                                  ProjectMemberRepository members, DiagramRepository diagrams,
                                  AccesoAProyecto acceso, JwtService jwt) {
        this.users = users;
        this.projects = projects;
        this.members = members;
        this.diagrams = diagrams;
        this.acceso = acceso;
        this.jwt = jwt;
    }

    @PostMapping("/bootstrap")
    public Map<String, Object> bootstrap(@RequestParam(name = "role", required = false) String role) {
        AppUser owner = users.findByEmail(EMAIL_DEMO).orElseGet(() -> {
            AppUser u = new AppUser();
            u.setEmail(EMAIL_DEMO);
            u.setPasswordHash("{noop}demo");
            u.setFullName("Usuario Demo");
            return users.save(u);
        });

        Project project = acceso.rolesDe(owner.getId()).keySet().stream().findFirst()
                .flatMap(projects::findById)
                .orElseGet(() -> {
                    Project p = new Project();
                    p.setName("Proyecto Demo");
                    p.setOwnerId(owner.getId());
                    return projects.save(p);
                });

        Diagram diagram = diagrams.findByProjectId(project.getId()).stream().findFirst().orElseGet(() -> {
            Diagram d = new Diagram();
            d.setProjectId(project.getId());
            d.setName("Diagrama Demo");
            return diagrams.save(d);
        });

        AppUser user = owner;
        Rol pedido = role == null ? Rol.OWNER : AccesoAProyecto.rolDeTexto(role).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.BAD_REQUEST, "role debe ser OWNER, EDITOR o VIEWER"));

        if (acceso.esAsignable(pedido)) {
            String email = "demo-" + pedido.name().toLowerCase(Locale.ROOT) + "@uagrm.edu.bo";
            user = users.findByEmail(email).orElseGet(() -> {
                AppUser u = new AppUser();
                u.setEmail(email);
                u.setPasswordHash("{noop}demo");
                u.setFullName("Usuario Demo (" + pedido.name() + ")");
                return users.save(u);
            });
            if (members.findByProjectIdAndUserId(project.getId(), user.getId()).isEmpty()
                    && !project.getOwnerId().equals(user.getId())) {
                ProjectMember m = new ProjectMember();
                m.setProjectId(project.getId());
                m.setUserId(user.getId());
                m.setRole(pedido.name());
                members.save(m);
            }
        }

        return Map.of(
                "userId",    user.getId(),
                "projectId", project.getId(),
                "diagramId", diagram.getId(),
                "currentSeq", diagram.getCurrentSeq(),
                "token",     jwt.emitir(user),
                "ws",        "/ws",
                "topic",     "/topic/diagram/" + diagram.getId() + "/ops"
        );
    }
}
