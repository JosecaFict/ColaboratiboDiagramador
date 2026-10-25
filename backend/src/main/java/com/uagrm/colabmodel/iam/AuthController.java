package com.uagrm.colabmodel.iam;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Registro e inicio de sesion. Unica puerta de entrada que emite un JWT (el
 * atajo /dev/bootstrap tambien emite uno, pero solo existe con el perfil `dev`).
 *
 * Un login fallido responde 401, igual que un token vencido: la interfaz
 * distingue los dos porque el login no lleva token, y un 401 con token es lo
 * que cierra la sesion (ver frontend/src/auth/cliente.ts).
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AppUserRepository users;
    private final PasswordEncoder encoder;
    private final JwtService jwt;

    public AuthController(AppUserRepository users, PasswordEncoder encoder, JwtService jwt) {
        this.users = users;
        this.encoder = encoder;
        this.jwt = jwt;
    }

    public record RegistroRequest(
            @NotBlank(message = "El email es obligatorio")
            @Email(message = "El email no es válido") String email,
            @NotBlank(message = "La contraseña es obligatoria")
            @Size(min = 8, message = "La contraseña debe tener al menos 8 caracteres") String password,
            @NotBlank(message = "El nombre es obligatorio")
            @Size(max = 160, message = "El nombre no puede pasar de 160 caracteres") String fullName) {
    }

    public record LoginRequest(
            @NotBlank(message = "El email es obligatorio") String email,
            @NotBlank(message = "La contraseña es obligatoria") String password) {
    }

    public record TokenResponse(String token, UUID userId, String fullName) {
    }

    @PostMapping("/register")
    public ResponseEntity<?> registrar(@Valid @RequestBody RegistroRequest req) {
        if (users.existsByEmailIgnoreCase(req.email().trim())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("mensaje", "Ese email ya está registrado"));
        }
        AppUser u = new AppUser();
        u.setEmail(req.email().trim().toLowerCase(Locale.ROOT));
        u.setPasswordHash(encoder.encode(req.password()));
        u.setFullName(req.fullName().trim());
        users.save(u);
        return ResponseEntity.ok(new TokenResponse(jwt.emitir(u), u.getId(), u.getFullName()));
    }

    @PostMapping("/login")
    public ResponseEntity<?> iniciarSesion(@Valid @RequestBody LoginRequest req) {
        AppUser u = users.findByEmailIgnoreCase(req.email().trim()).orElse(null);
        if (u == null || !encoder.matches(req.password(), u.getPasswordHash())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("mensaje", "El email o la contraseña no son correctos"));
        }
        return ResponseEntity.ok(new TokenResponse(jwt.emitir(u), u.getId(), u.getFullName()));
    }
}
