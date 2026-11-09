package com.uagrm.colabmodel.iam;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Un token cuya sesion se cerro: se rechaza hasta que vence (V10). */
@Entity
@Table(name = "revoked_tokens")
public class SesionCerrada {

    @Id
    @Column(name = "jti", nullable = false, updatable = false)
    private UUID jti;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    protected SesionCerrada() { }

    public SesionCerrada(UUID jti, OffsetDateTime expiresAt) {
        this.jti = jti;
        this.expiresAt = expiresAt;
    }

    public UUID getJti() { return jti; }

    public OffsetDateTime getExpiresAt() { return expiresAt; }
}
