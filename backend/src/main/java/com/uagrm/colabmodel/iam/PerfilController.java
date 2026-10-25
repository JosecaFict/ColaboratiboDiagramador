package com.uagrm.colabmodel.iam;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.util.UUID;

/**
 * La cuenta de quien esta conectado (ADR-026).
 *
 * No hay recuperacion de contrasena ni refresh tokens (fuera de alcance):
 * cambiar la contrasena exige la actual, y los tokens ya emitidos siguen
 * valiendo hasta que venzan.
 */
@RestController
@RequestMapping("/api/v1/me")
public class PerfilController {

    public record Perfil(UUID userId, String email, String fullName) { }

    public record CambioDeNombre(
            @NotBlank(message = "El nombre es obligatorio")
            @Size(max = 160, message = "El nombre no puede pasar de 160 caracteres") String fullName) { }

    public record CambioDeContrasena(
            @NotBlank(message = "Escribí tu contraseña actual") String actual,
            @NotBlank(message = "Escribí la contraseña nueva")
            @Size(min = 8, message = "La contraseña nueva debe tener al menos 8 caracteres") String nueva) { }

    private final AppUserRepository users;
    private final PasswordEncoder encoder;

    public PerfilController(AppUserRepository users, PasswordEncoder encoder) {
        this.users = users;
        this.encoder = encoder;
    }

    @GetMapping
    public Perfil yo(Principal principal) {
        return perfilDe(usuario(principal));
    }

    @PatchMapping
    public Perfil cambiarNombre(@Valid @RequestBody CambioDeNombre cambio, Principal principal) {
        AppUser u = usuario(principal);
        u.setFullName(cambio.fullName().trim());
        return perfilDe(users.save(u));
    }

    /**
     * Responde 400 y NO 401 cuando la contrasena actual no coincide: un 401 con
     * token es "tu sesion murio" y la interfaz cierra la sesion. Equivocarse al
     * escribir la contrasena no es eso.
     */
    @PostMapping("/password")
    public ResponseEntity<Void> cambiarContrasena(@Valid @RequestBody CambioDeContrasena cambio,
                                                  Principal principal) {
        AppUser u = usuario(principal);
        if (!encoder.matches(cambio.actual(), u.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La contraseña actual no es correcta");
        }
        u.setPasswordHash(encoder.encode(cambio.nueva()));
        users.save(u);
        return ResponseEntity.noContent().build();
    }

    /** Un token valido de un usuario que ya no existe es una sesion muerta: 401. */
    private AppUser usuario(Principal principal) {
        return users.findById(UUID.fromString(principal.getName()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "La cuenta de esta sesión ya no existe"));
    }

    private static Perfil perfilDe(AppUser u) {
        return new Perfil(u.getId(), u.getEmail(), u.getFullName());
    }
}
