package com.uagrm.colabmodel.project;

import com.uagrm.colabmodel.iam.AppUser;
import com.uagrm.colabmodel.iam.AppUserRepository;
import com.uagrm.colabmodel.project.AccesoAProyecto.Rol;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Repositorios de `project` e `iam` que guardan en memoria, para probar las
 * reglas de proyectos y miembros con el estilo de `PermisoDeEscrituraTest`
 * (Mockito, sin base de datos).
 *
 * Lo que esto NO puede probar es la transaccion: que una transferencia que
 * falla a la mitad no deje nada cambiado lo hace la base, no una lista en
 * memoria. Eso lo prueba `ApiDeProyectosTest` contra PostgreSQL de verdad.
 */
public class ProyectosEnMemoria {

    public final Map<UUID, Project> proyectos = new HashMap<>();
    public final List<ProjectMember> miembros = new ArrayList<>();
    public final Map<UUID, Diagram> diagramas = new HashMap<>();
    public final Map<UUID, AppUser> usuarios = new HashMap<>();

    public final ProjectRepository projects = mock(ProjectRepository.class);
    public final ProjectMemberRepository members = mock(ProjectMemberRepository.class);
    public final DiagramRepository diagrams = mock(DiagramRepository.class);
    public final AppUserRepository users = mock(AppUserRepository.class);
    public final AccesoAProyecto acceso = new AccesoAProyecto(diagrams, projects, members);

    public ProyectosEnMemoria() {
        when(projects.findById(any())).thenAnswer(i -> Optional.ofNullable(proyectos.get(i.<UUID>getArgument(0))));
        when(projects.findParaModificar(any())).thenAnswer(i -> Optional.ofNullable(proyectos.get(i.<UUID>getArgument(0))));
        when(projects.findAllById(anyIterable())).thenAnswer(i -> {
            List<Project> r = new ArrayList<>();
            for (UUID id : i.<Iterable<UUID>>getArgument(0)) if (proyectos.containsKey(id)) r.add(proyectos.get(id));
            return r;
        });
        when(projects.findByOwnerId(any())).thenAnswer(i -> proyectos.values().stream()
                .filter(p -> p.getOwnerId().equals(i.<UUID>getArgument(0))).toList());
        when(projects.save(any(Project.class))).thenAnswer(i -> {
            Project p = i.getArgument(0);
            if (p.getId() == null) p.setId(UUID.randomUUID());
            proyectos.put(p.getId(), p);
            return p;
        });
        org.mockito.Mockito.doAnswer(i -> proyectos.remove(i.<Project>getArgument(0).getId()))
                .when(projects).delete(any(Project.class));

        when(members.findByProjectIdAndUserId(any(), any())).thenAnswer(i -> miembros.stream()
                .filter(m -> m.getProjectId().equals(i.<UUID>getArgument(0)) && m.getUserId().equals(i.<UUID>getArgument(1)))
                .findFirst());
        when(members.findByProjectId(any())).thenAnswer(i -> miembros.stream()
                .filter(m -> m.getProjectId().equals(i.<UUID>getArgument(0))).toList());
        when(members.findByUserId(any())).thenAnswer(i -> miembros.stream()
                .filter(m -> m.getUserId().equals(i.<UUID>getArgument(0))).toList());
        when(members.save(any(ProjectMember.class))).thenAnswer(i -> {
            ProjectMember nuevo = i.getArgument(0);
            miembros.removeIf(m -> m.getProjectId().equals(nuevo.getProjectId()) && m.getUserId().equals(nuevo.getUserId()));
            miembros.add(nuevo);
            return nuevo;
        });
        org.mockito.Mockito.doAnswer(i -> {
            ProjectMember fila = i.getArgument(0);
            miembros.removeIf(m -> m.getProjectId().equals(fila.getProjectId()) && m.getUserId().equals(fila.getUserId()));
            return null;
        }).when(members).delete(any(ProjectMember.class));

        when(diagrams.findById(any())).thenAnswer(i -> Optional.ofNullable(diagramas.get(i.<UUID>getArgument(0))));
        when(diagrams.findByProjectId(any())).thenAnswer(i -> diagramas.values().stream()
                .filter(d -> d.getProjectId().equals(i.<UUID>getArgument(0))).toList());
        when(diagrams.findByProjectIdOrderByCreatedAtAsc(any())).thenAnswer(i -> diagramas.values().stream()
                .filter(d -> d.getProjectId().equals(i.<UUID>getArgument(0)))
                .sorted(java.util.Comparator.comparing(Diagram::getCreatedAt)).toList());
        when(diagrams.save(any(Diagram.class))).thenAnswer(i -> {
            Diagram d = i.getArgument(0);
            if (d.getId() == null) d.setId(UUID.randomUUID());
            diagramas.put(d.getId(), d);
            return d;
        });
        org.mockito.Mockito.doAnswer(i -> diagramas.remove(i.<Diagram>getArgument(0).getId()))
                .when(diagrams).delete(any(Diagram.class));
        when(diagrams.contarPorProyecto(anyCollection())).thenAnswer(i -> {
            Collection<UUID> ids = i.getArgument(0);
            Map<UUID, Long> cuenta = new HashMap<>();
            diagramas.values().stream().filter(d -> ids.contains(d.getProjectId()))
                    .forEach(d -> cuenta.merge(d.getProjectId(), 1L, Long::sum));
            List<Object[]> filas = new ArrayList<>();
            cuenta.forEach((p, n) -> filas.add(new Object[]{p, n}));
            return filas;
        });

        when(users.findById(any())).thenAnswer(i -> Optional.ofNullable(usuarios.get(i.<UUID>getArgument(0))));
        when(users.findByEmailIgnoreCase(any())).thenAnswer(i -> usuarios.values().stream()
                .filter(u -> u.getEmail().equalsIgnoreCase(i.<String>getArgument(0))).findFirst());
        when(users.findAllById(anyIterable())).thenAnswer(i -> {
            List<AppUser> r = new ArrayList<>();
            for (UUID id : i.<Iterable<UUID>>getArgument(0)) if (usuarios.containsKey(id)) r.add(usuarios.get(id));
            return r;
        });
    }

    public AppUser usuario(String nombre) {
        AppUser u = new AppUser();
        u.setId(UUID.randomUUID());
        u.setEmail(nombre.toLowerCase() + "@uagrm.edu.bo");
        u.setFullName(nombre);
        u.setPasswordHash("x");
        usuarios.put(u.getId(), u);
        return u;
    }

    /** Un proyecto de `duenio` con un diagrama. */
    public Project proyecto(AppUser duenio, String nombre) {
        Project p = new Project();
        p.setId(UUID.randomUUID());
        p.setName(nombre);
        p.setOwnerId(duenio.getId());
        proyectos.put(p.getId(), p);
        diagrama(p, "Diagrama 1");
        return p;
    }

    public Diagram diagrama(Project p, String nombre) {
        Diagram d = new Diagram();
        d.setId(UUID.randomUUID());
        d.setProjectId(p.getId());
        d.setName(nombre);
        d.setCreatedAt(OffsetDateTime.now().plusNanos(diagramas.size()));
        diagramas.put(d.getId(), d);
        return d;
    }

    /** Un miembro invitado, sin pasar por el servicio. */
    public AppUser miembro(Project p, String nombre, Rol rol) {
        AppUser u = usuario(nombre);
        ProjectMember m = new ProjectMember();
        m.setProjectId(p.getId());
        m.setUserId(u.getId());
        m.setRole(rol.name());
        miembros.add(m);
        return u;
    }

    public Optional<Rol> rolDe(Project p, AppUser u) {
        return acceso.rolSobreProyecto(p.getId(), u.getId());
    }

    /** Cuantos propietarios tiene el proyecto: el invariante es que siempre es uno. */
    public long propietarios(Project p) {
        long comoOwner = proyectos.containsKey(p.getId()) ? 1 : 0;
        long comoFila = miembros.stream()
                .filter(m -> m.getProjectId().equals(p.getId()) && Rol.OWNER.name().equals(m.getRole())).count();
        return comoOwner + comoFila;
    }
}
