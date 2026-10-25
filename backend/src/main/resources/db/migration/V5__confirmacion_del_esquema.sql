-- La base CONFIRMADA antes de generar el backend (ADR-032).
--
-- Una fila por diagrama: la ultima confirmacion. El historial ya existe en el
-- log de operaciones --cada CONFIRM_SCHEMA queda ahi con su seq, su autor y el
-- hash que se confirmo--, asi que repetirlo aca seria una segunda copia.
--
-- Se guarda el DDL confirmado ademas de su hash: el hash alcanza para saber SI
-- el esquema cambio desde la confirmacion, pero para mostrar QUE cambio hace
-- falta el texto, y despues de editar el modelo ya no se puede volver a derivar.
--
-- Migracion nueva y no V1 editado: Flyway guarda el checksum de lo ya aplicado.
CREATE TABLE schema_confirmations (
    diagram_id   UUID PRIMARY KEY REFERENCES diagrams(id) ON DELETE CASCADE,
    ddl_hash     VARCHAR(64) NOT NULL,
    ddl          TEXT        NOT NULL,
    seq          BIGINT      NOT NULL,
    confirmed_by UUID REFERENCES users(id) ON DELETE SET NULL,
    confirmed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
