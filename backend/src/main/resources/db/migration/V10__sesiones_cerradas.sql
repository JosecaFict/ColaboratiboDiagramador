-- Cerrar sesion en el servidor. Un JWT no se puede borrar: el servidor lo
-- rechaza. Cada token lleva su id (claim `jti`); al cerrar sesion ese id queda
-- aca hasta que el token vence, y despues ya no hace falta guardarlo.
CREATE TABLE revoked_tokens (
    jti        UUID PRIMARY KEY,
    expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_revoked_tokens_expires ON revoked_tokens(expires_at);
