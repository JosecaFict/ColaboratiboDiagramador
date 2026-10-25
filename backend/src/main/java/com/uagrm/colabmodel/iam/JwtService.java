package com.uagrm.colabmodel.iam;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;

/**
 * Emite y valida los JWT de sesion (Fase 1).
 *
 * El claim `sub` es el userId: es lo UNICO que el resto del sistema confia
 * de un token. La autorizacion (que puede hacer ese userId) nunca se decide
 * aqui ni se mete en el token: se resuelve siempre en el servidor contra
 * `projects.owner_id` / `project_members` (ver DiagramCommandBus).
 */
@Service
public class JwtService {

    private final SecretKey key;
    private final long expirationMinutes;

    public JwtService(@Value("${colabmodel.jwt.secret}") String secret,
                      @Value("${colabmodel.jwt.expiration-minutes}") long expirationMinutes) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationMinutes = expirationMinutes;
    }

    public String emitir(AppUser user) {
        Instant ahora = Instant.now();
        return Jwts.builder()
                .subject(user.getId().toString())
                .claim("email", user.getEmail())
                .claim("name", user.getFullName())
                .issuedAt(Date.from(ahora))
                .expiration(Date.from(ahora.plus(expirationMinutes, ChronoUnit.MINUTES)))
                .signWith(key)
                .compact();
    }

    /** @throws io.jsonwebtoken.JwtException si el token es invalido, esta vencido o la firma no coincide. */
    public UUID validarYObtenerUserId(String token) {
        Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
        return UUID.fromString(claims.getSubject());
    }
}
