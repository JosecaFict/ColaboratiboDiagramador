-- Control de versiones tipo Git sobre el log de operaciones (ADR-037).
--
-- Un commit es un `seq` con nombre, mensaje, autor y la foto del modelo en ese
-- seq. La foto es la forma de GET /diagrams/{id} mas las decisiones de
-- modelado: con eso alcanza para mostrar la version, compararla y volver a ella.
--
-- Migracion nueva y no V1 editado: Flyway guarda el checksum de lo ya aplicado.
CREATE TABLE diagram_versions (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    diagram_id        UUID NOT NULL REFERENCES diagrams(id) ON DELETE CASCADE,
    seq               BIGINT NOT NULL,
    name              VARCHAR(120) NOT NULL,
    message           TEXT,
    author_id         UUID REFERENCES users(id) ON DELETE SET NULL,
    -- La version anterior de la misma linea. El primer commit de una rama
    -- apunta a la version de la que nacio (que es de otro diagrama).
    parent_version_id UUID REFERENCES diagram_versions(id) ON DELETE SET NULL,
    snapshot          JSONB NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_version_name UNIQUE (diagram_id, name),
    CONSTRAINT chk_version_seq CHECK (seq >= 0)
);
CREATE INDEX idx_versions_diagram ON diagram_versions(diagram_id, seq DESC, created_at DESC);

-- Una rama ES un diagrama del mismo proyecto que nace de una version.
--
-- id_salt: los ids de uml_class, uml_attribute, uml_method y uml_relationship
-- son PK globales, asi que una rama no puede repetirlos. Lo que se conserva es
-- la IDENTIDAD LOGICA: id fisico en la rama = id logico XOR sal. Un diagrama
-- raiz no tiene sal (fisico = logico). La sal tiene en cero los bits de version
-- y de variante del UUID, asi el XOR no los toca y los ids siguen siendo v4.
ALTER TABLE diagrams
    ADD COLUMN branch_name            VARCHAR(120) NOT NULL DEFAULT 'main',
    ADD COLUMN forked_from_diagram_id UUID REFERENCES diagrams(id) ON DELETE SET NULL,
    ADD COLUMN forked_from_version_id UUID REFERENCES diagram_versions(id) ON DELETE SET NULL,
    ADD COLUMN id_salt                UUID;
CREATE INDEX idx_diagrams_forked_from ON diagrams(forked_from_diagram_id);

-- La base del proximo merge entre dos diagramas.
--
-- Despues de mergear B en A, el estado de B en ese momento es ancestro comun
-- de los dos (como el segundo padre de un merge commit en git). Sin guardarlo,
-- la base seguiria siendo la version de nacimiento de la rama y cada merge
-- volveria a preguntar por conflictos que el equipo ya resolvio.
CREATE TABLE diagram_merges (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    target_diagram_id UUID NOT NULL REFERENCES diagrams(id) ON DELETE CASCADE,
    from_diagram_id   UUID NOT NULL REFERENCES diagrams(id) ON DELETE CASCADE,
    -- Estado del diagrama de origen al mergear, con los ids FISICOS de ese diagrama.
    from_snapshot     JSONB NOT NULL,
    from_seq          BIGINT NOT NULL,
    target_seq        BIGINT NOT NULL,
    author_id         UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_merges_pair ON diagram_merges(target_diagram_id, from_diagram_id, created_at DESC);
