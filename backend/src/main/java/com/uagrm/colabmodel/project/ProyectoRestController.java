package com.uagrm.colabmodel.project;

import com.uagrm.colabmodel.project.GestionDeMiembros.Miembro;
import com.uagrm.colabmodel.project.GestionDeProyectos.DiagramaResumen;
import com.uagrm.colabmodel.project.GestionDeProyectos.ProyectoDetalle;
import com.uagrm.colabmodel.project.GestionDeProyectos.ProyectoResumen;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.UUID;

/**
 * Proyectos, diagramas y miembros (ADR-026): el plano de CONTROL de quien
 * trabaja con que.
 *
 * Este controlador no decide nada: traduce HTTP a `GestionDeProyectos` y
 * `GestionDeMiembros`, y estos le preguntan a `AccesoAProyecto` quien puede
 * hacer cada cosa. No compara ningun rol.
 *
 * Codigos: 401 sin sesion (lo pone la seguridad, no este controlador), 403 sin
 * permiso o al no miembro, 404 lo que no existe dentro del proyecto, 409 lo que
 * choca con el estado (ya es miembro, es el propietario), 400 entrada invalida.
 */
@RestController
@RequestMapping("/api/v1")
public class ProyectoRestController {

    public record NombreRequest(
            @NotBlank(message = "El nombre es obligatorio")
            @Size(max = 160, message = "El nombre no puede pasar de 160 caracteres") String name) { }

    public record AltaDeMiembro(
            @NotBlank(message = "El email es obligatorio") String email,
            @NotBlank(message = "El rol es obligatorio") String role) { }

    public record CambioDeRol(@NotBlank(message = "El rol es obligatorio") String role) { }

    public record Transferencia(@NotNull(message = "Indicá a quién se transfiere la propiedad") UUID userId) { }

    private final GestionDeProyectos proyectos;
    private final GestionDeMiembros miembros;

    public ProyectoRestController(GestionDeProyectos proyectos, GestionDeMiembros miembros) {
        this.proyectos = proyectos;
        this.miembros = miembros;
    }

    // ------------------------------------------------------------- proyectos

    @GetMapping("/projects")
    public List<ProyectoResumen> misProyectos(Principal principal) {
        return proyectos.misProyectos(yo(principal));
    }

    @PostMapping("/projects")
    public ResponseEntity<ProyectoDetalle> crear(@Valid @RequestBody NombreRequest req, Principal principal) {
        return ResponseEntity.status(HttpStatus.CREATED).body(proyectos.crear(yo(principal), req.name()));
    }

    @GetMapping("/projects/{id}")
    public ProyectoDetalle detalle(@PathVariable UUID id, Principal principal) {
        return proyectos.detalle(id, yo(principal));
    }

    @PatchMapping("/projects/{id}")
    public ProyectoDetalle renombrar(@PathVariable UUID id, @Valid @RequestBody NombreRequest req,
                                     Principal principal) {
        return proyectos.renombrar(id, yo(principal), req.name());
    }

    @DeleteMapping("/projects/{id}")
    public ResponseEntity<Void> eliminar(@PathVariable UUID id, Principal principal) {
        proyectos.eliminar(id, yo(principal));
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------- diagramas

    @PostMapping("/projects/{id}/diagrams")
    public ResponseEntity<DiagramaResumen> crearDiagrama(@PathVariable UUID id,
                                                         @Valid @RequestBody NombreRequest req,
                                                         Principal principal) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(proyectos.crearDiagrama(id, yo(principal), req.name()));
    }

    @PatchMapping("/diagrams/{id}")
    public DiagramaResumen renombrarDiagrama(@PathVariable UUID id, @Valid @RequestBody NombreRequest req,
                                             Principal principal) {
        return proyectos.renombrarDiagrama(id, yo(principal), req.name());
    }

    @DeleteMapping("/diagrams/{id}")
    public ResponseEntity<Void> eliminarDiagrama(@PathVariable UUID id, Principal principal) {
        proyectos.eliminarDiagrama(id, yo(principal));
        return ResponseEntity.noContent().build();
    }

    // -------------------------------------------------------------- miembros

    @GetMapping("/projects/{id}/members")
    public List<Miembro> listarMiembros(@PathVariable UUID id, Principal principal) {
        return miembros.listar(id, yo(principal));
    }

    @PostMapping("/projects/{id}/members")
    public ResponseEntity<Miembro> agregarMiembro(@PathVariable UUID id, @Valid @RequestBody AltaDeMiembro req,
                                                  Principal principal) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(miembros.agregar(id, yo(principal), req.email(), req.role()));
    }

    @PatchMapping("/projects/{id}/members/{userId}")
    public Miembro cambiarRol(@PathVariable UUID id, @PathVariable UUID userId,
                              @Valid @RequestBody CambioDeRol req, Principal principal) {
        return miembros.cambiarRol(id, yo(principal), userId, req.role());
    }

    /** El propietario quita a otro, o cada uno se quita a si mismo (abandona). */
    @DeleteMapping("/projects/{id}/members/{userId}")
    public ResponseEntity<Void> quitarMiembro(@PathVariable UUID id, @PathVariable UUID userId,
                                              Principal principal) {
        miembros.quitar(id, yo(principal), userId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/projects/{id}/transfer")
    public ResponseEntity<Void> transferir(@PathVariable UUID id, @Valid @RequestBody Transferencia req,
                                           Principal principal) {
        miembros.transferir(id, yo(principal), req.userId());
        return ResponseEntity.noContent().build();
    }

    /** La identidad sale del JWT ya validado por el filtro, nunca del cuerpo. */
    private static UUID yo(Principal principal) {
        return UUID.fromString(principal.getName());
    }
}
