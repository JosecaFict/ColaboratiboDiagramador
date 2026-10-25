-- Conversacion compartida y persistente de la IA por diagrama.
-- Una fila representa el turno completo: pedido humano + respuesta/propuesta.
CREATE TABLE ai_interactions (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    diagram_id  UUID NOT NULL REFERENCES diagrams(id) ON DELETE CASCADE,
    user_id     UUID REFERENCES users(id) ON DELETE SET NULL,
    mode        VARCHAR(12) NOT NULL,
    model       VARCHAR(160),
    prompt      TEXT NOT NULL,
    response    TEXT,
    proposal    JSONB NOT NULL,
    applied_at  TIMESTAMPTZ,
    applied_by  UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_ai_interaction_mode CHECK (mode IN ('CONSULTAR','REVISAR','DISENAR','MODIFICAR'))
);
CREATE INDEX idx_ai_interactions_diagram_created
    ON ai_interactions(diagram_id, created_at, id);

