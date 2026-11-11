-- Chat de la IA con imagenes (D1 a D7; docs/DISENO-CHAT-IMAGENES.md, seccion 1).
-- Aditiva: las interacciones existentes quedan sin imagen.

CREATE TABLE ai_images (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    diagram_id    UUID NOT NULL REFERENCES diagrams(id) ON DELETE CASCADE,
    uploaded_by   UUID REFERENCES users(id) ON DELETE SET NULL,
    content_type  VARCHAR(16)  NOT NULL,
    size_bytes    INTEGER      NOT NULL,          -- bytes de `data`: lo que cuenta para el tope
    width         INTEGER      NOT NULL,
    height        INTEGER      NOT NULL,
    sha256        VARCHAR(64)  NOT NULL,          -- de los bytes RECIBIDOS, en minuscula
    data          BYTEA,                           -- null solo si fue eliminada
    thumbnail     BYTEA,                           -- JPEG de 320 px de lado mayor; null solo si fue eliminada
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted_at    TIMESTAMPTZ,
    deleted_by    UUID REFERENCES users(id) ON DELETE SET NULL,
    CONSTRAINT chk_ai_image_type CHECK (content_type IN ('image/png', 'image/jpeg')),
    CONSTRAINT chk_ai_image_size CHECK (size_bytes > 0 AND width > 0 AND height > 0),
    CONSTRAINT chk_ai_image_sha  CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    -- Viva: tiene sus bytes. Eliminada: no tiene ninguno (la fila queda para que el turno diga "imagen eliminada").
    CONSTRAINT chk_ai_image_bytes CHECK (
        (deleted_at IS NULL     AND data IS NOT NULL AND thumbnail IS NOT NULL) OR
        (deleted_at IS NOT NULL AND data IS NULL     AND thumbnail IS NULL))
);

-- D7: una sola fila VIVA por (diagrama, sha256). Parcial a proposito (O-1 del diseno).
CREATE UNIQUE INDEX uq_ai_images_diagram_sha ON ai_images(diagram_id, sha256) WHERE deleted_at IS NULL;
-- La galeria: las del diagrama, mas nuevas primero.
CREATE INDEX idx_ai_images_diagram_created ON ai_images(diagram_id, created_at DESC, id);

-- El turno que trabaja sobre una imagen. SET NULL como pide el plan: solo se dispara si la fila de
-- `ai_images` se borra de verdad (al borrar el diagrama, que arrastra tambien sus turnos).
ALTER TABLE ai_interactions ADD COLUMN image_id UUID REFERENCES ai_images(id) ON DELETE SET NULL;
CREATE INDEX idx_ai_interactions_image ON ai_interactions(image_id) WHERE image_id IS NOT NULL;

-- Modo nuevo. RECOMENDAR y VERIFICAR no se guardan como turno (V7 tampoco los listaba), asi que no se agregan.
ALTER TABLE ai_interactions DROP CONSTRAINT chk_ai_interaction_mode;
ALTER TABLE ai_interactions ADD CONSTRAINT chk_ai_interaction_mode
    CHECK (mode IN ('CONSULTAR', 'REVISAR', 'DISENAR', 'MODIFICAR', 'IMAGEN'));
