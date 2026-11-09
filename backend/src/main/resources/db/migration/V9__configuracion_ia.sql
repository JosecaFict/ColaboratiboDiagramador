-- T-09: como se comporta la IA en un proyecto. Una fila por proyecto; si no
-- hay fila, el servidor usa los valores por defecto (MENTOR, NORMAL, sin
-- instrucciones) sin crearla. Solo el propietario la cambia.
CREATE TABLE ai_settings (
    project_id    UUID PRIMARY KEY REFERENCES projects(id) ON DELETE CASCADE,
    rol           VARCHAR(10)  NOT NULL,
    detalle       VARCHAR(10)  NOT NULL,
    instrucciones TEXT         NOT NULL DEFAULT '',
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by    UUID REFERENCES users(id) ON DELETE SET NULL,
    CONSTRAINT chk_ai_settings_rol CHECK (rol IN ('MENTOR', 'SENIOR', 'REVISOR')),
    CONSTRAINT chk_ai_settings_detalle CHECK (detalle IN ('BREVE', 'NORMAL', 'DETALLADO')),
    CONSTRAINT chk_ai_settings_instrucciones CHECK (char_length(instrucciones) <= 1000)
);
