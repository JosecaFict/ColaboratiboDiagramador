package com.uagrm.colabmodel.iam;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final SesionesCerradas cerradas;

    /** Sin registro de sesiones cerradas: para las pruebas que no lo usan. */
    public JwtService(String secret, long expirationMinutes) {
        this(secret, expirationMinutes, SesionesCerradas.NINGUNA);
    }

    @Autowired
    public JwtService(@Value("${colabmodel.jwt.secret}") String secret,
                      @Value("${colabmodel.jwt.expiration-minutes}") long expirationMinutes,
                      SesionesCerradas cerradas) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationMinutes = expirationMinutes;
        this.cerradas = cerradas;
    }

    public String emitir(AppUser user) {
        Instant ahora = Instant.now();
        return Jwts.builder()
                // El id del token: es lo que se recuerda al cerrar la sesion.
                .id(UUID.randomUUID().toString())
                .subject(user.getId().toString())
                .claim("email", user.getEmail())
                .claim("name", user.getFullName())
                .issuedAt(Date.from(ahora))
                .expiration(Date.from(ahora.plus(expirationMinutes, ChronoUnit.MINUTES)))
                .signWith(key)
                .compact();
    }

    /**
     * @throws io.jsonwebtoken.JwtException si el token es invalido, esta vencido,
     *         la firma no coincide o su sesion se cerro.
     */
    public UUID validarYObtenerUserId(String token) {
        Claims claims = leer(token);
        UUID jti = jtiDe(claims);
        if (jti != null && cerradas.estaCerrada(jti)) {
            throw new JwtException("La sesion de este token se cerro");
        }
        return UUID.fromString(claims.getSubject());
    }

    /**
     * Cierra la sesion de este token: desde ahora el servidor lo rechaza, en
     * REST y en WebSocket, aunque todavia no haya vencido. Un token invalido,
     * vencido o sin id no tiene nada que cerrar.
     *
     * @return si habia una sesion que cerrar
     */
    public boolean cerrarSesion(String token) {
        Claims claims;
        try {
            claims = leer(token);
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
        UUID jti = jtiDe(claims);
        if (jti == null) return false;
        cerradas.cerrar(jti, claims.getExpiration().toInstant());
        return true;
    }

    private Claims leer(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }

    /** Los tokens emitidos antes de V10 no tienen id: no se pueden cerrar uno por uno, vencen solos. */
    private static UUID jtiDe(Claims claims) {
        String id = claims.getId();
        if (id == null) return null;
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
