package com.uagrm.colabmodel.project;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Rol efectivo de un usuario sobre un proyecto, y que puede hacer con el.
 *
 * Vive en UN solo lugar a proposito: la misma regla decide quien escribe
 * (DiagramCommandBus), quien lee (el snapshot), quien administra miembros y
 * quien puede suscribirse a un diagrama por WebSocket. Duplicada, tarde o
 * temprano una de las copias se queda vieja, y es justo la clase de olvido que
 * convierte EC-11 en una promesa en vez de una garantia. Ningun controlador
 * compara un rol por su cuenta: pregunta aca (ADR-026).
 *
 * El OWNER es implicito: sale de `projects.owner_id` y no tiene fila en
 * `project_members` (desde V6 la base ni siquiera lo deja).
 *
 * El rol se lee de la base EN CADA pregunta, sin cache: un cambio de rol se
 * aplica "en caliente" porque el proximo comando ya lo ve. Un cache aca
 * bastaria para que un VIEWER recien degradado siguiera escribiendo.
 */
@Service
public class AccesoAProyecto {

    public enum Rol {
        OWNER("Propietario"), EDITOR("Modelador"), VIEWER("Observador");

        private final String etiqueta;

        Rol(String etiqueta) { this.etiqueta = etiqueta; }

        public String etiqueta() { return etiqueta; }
    }

    /**
     * Lo que alguien puede querer hacer sobre un proyecto: una fila de la tabla
     * de permisos del ADR-026. Agregar una accion nueva obliga a decidir, en el
     * `switch` de `permite`, quien puede: no compila sin `default`.
     */
    public enum Accion {
        /** Ver el proyecto, sus diagramas y miembros; leer diagrama, Logica, Fisica y ambiguedades; bajar el codigo. */
        LEER("ver el proyecto"),
        /** Editar el modelo, importar, IA, vision y confirmar el esquema. */
        EDITAR_MODELO("editar el modelo"),
        /** Crear y renombrar diagramas. */
        CREAR_DIAGRAMA("crear ni renombrar diagramas"),
        ELIMINAR_DIAGRAMA("eliminar diagramas"),
        /** Renombrar o eliminar el proyecto. */
        ADMINISTRAR_PROYECTO("renombrar ni eliminar el proyecto"),
        /** Agregar miembros, cambiar su rol, quitarlos. */
        ADMINISTRAR_MIEMBROS("agregar, cambiar ni quitar miembros"),
        TRANSFERIR_PROPIEDAD("transferir la propiedad"),
        ABANDONAR("abandonar el proyecto");

        private final String frase;

        Accion(String frase) { this.frase = frase; }

        public String frase() { return frase; }
    }

    private final DiagramRepository diagrams;
    private final ProjectRepository projects;
    private final ProjectMemberRepository members;

    public AccesoAProyecto(DiagramRepository diagrams, ProjectRepository projects,
                           ProjectMemberRepository members) {
        this.diagrams = diagrams;
        this.projects = projects;
        this.members = members;
    }

    // ------------------------------------------------------------------ rol

    /** Vacio = el usuario no tiene ningun acceso (o el diagrama no existe). */
    @Transactional(readOnly = true)
    public Optional<Rol> rolSobreDiagrama(UUID diagramId, UUID userId) {
        return diagrams.findById(diagramId)
                .flatMap(d -> rolSobreProyecto(d.getProjectId(), userId));
    }

    @Transactional(readOnly = true)
    public Optional<Rol> rolSobreProyecto(UUID projectId, UUID userId) {
        Project project = projects.findById(projectId).orElse(null);
        if (project == null) return Optional.empty();
        return rolEn(project, userId);
    }

    /** El mismo calculo, para quien ya tiene el proyecto cargado (y quiza bloqueado). */
    @Transactional(readOnly = true)
    public Optional<Rol> rolEn(Project project, UUID userId) {
        if (project.getOwnerId().equals(userId)) return Optional.of(Rol.OWNER);

        return members.findByProjectIdAndUserId(project.getId(), userId)
                .map(ProjectMember::getRole)
                .flatMap(AccesoAProyecto::rolDeTexto);
    }

    /**
     * El rol del usuario en TODOS los proyectos a los que tiene acceso, para
     * listarlos sin repetir esta logica en otro lado.
     */
    @Transactional(readOnly = true)
    public Map<UUID, Rol> rolesDe(UUID userId) {
        Map<UUID, Rol> roles = new LinkedHashMap<>();
        for (Project p : projects.findByOwnerId(userId)) roles.put(p.getId(), Rol.OWNER);
        for (ProjectMember m : members.findByUserId(userId)) {
            rolDeTexto(m.getRole()).ifPresent(r -> roles.putIfAbsent(m.getProjectId(), r));
        }
        return roles;
    }

    // ------------------------------------------------------------- permisos

    /** La tabla de permisos del ADR-026. Una sola. */
    public boolean permite(Rol rol, Accion accion) {
        return switch (accion) {
            case LEER -> true;
            case EDITAR_MODELO, CREAR_DIAGRAMA -> rol == Rol.OWNER || rol == Rol.EDITOR;
            case ELIMINAR_DIAGRAMA, ADMINISTRAR_PROYECTO, ADMINISTRAR_MIEMBROS,
                 TRANSFERIR_PROPIEDAD -> rol == Rol.OWNER;
            case ABANDONAR -> rol != Rol.OWNER;
        };
    }

    /** Los tres roles pueden LEER. Solo OWNER y EDITOR pueden escribir. */
    public boolean puedeEditar(Rol rol) {
        return permite(rol, Accion.EDITAR_MODELO);
    }

    /**
     * Solo EDITOR y VIEWER se asignan. OWNER no: el propietario es uno solo y
     * cambia con una transferencia, no con un cambio de rol.
     */
    public boolean esAsignable(Rol rol) {
        return rol == Rol.EDITOR || rol == Rol.VIEWER;
    }

    /**
     * Exige que el usuario pueda hacer `accion` en el proyecto.
     *
     * @return el rol, para quien lo necesite despues de haberlo comprobado
     * @throws ResponseStatusException 403 si no es miembro (o el proyecto no
     *         existe: no se distingue, para no revelar que ids existen) o si su
     *         rol no lo permite
     */
    @Transactional(readOnly = true)
    public Rol exigir(UUID projectId, UUID userId, Accion accion) {
        Rol rol = rolSobreProyecto(projectId, userId).orElseThrow(AccesoAProyecto::noEsMiembro);
        return exigirDelRol(rol, accion);
    }

    /** Igual que `exigir`, con el proyecto ya cargado (por ejemplo, bloqueado para modificar). */
    @Transactional(readOnly = true)
    public Rol exigir(Project project, UUID userId, Accion accion) {
        Rol rol = rolEn(project, userId).orElseThrow(AccesoAProyecto::noEsMiembro);
        return exigirDelRol(rol, accion);
    }

    private Rol exigirDelRol(Rol rol, Accion accion) {
        if (permite(rol, accion)) return rol;
        String mensaje = accion == Accion.ABANDONAR
                ? "El propietario no puede abandonar el proyecto: primero transferí la propiedad"
                : "Como " + rol.etiqueta() + " no podés " + accion.frase();
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, mensaje);
    }

    public static ResponseStatusException noEsMiembro() {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, "No sos miembro de este proyecto");
    }

    // ---------------------------------------------------------------- texto

    /** Vacio = un texto que no es un rol (o una fila con un rol que ya no existe). */
    public static Optional<Rol> rolDeTexto(String texto) {
        if (texto == null) return Optional.empty();
        try {
            return Optional.of(Rol.valueOf(texto.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
