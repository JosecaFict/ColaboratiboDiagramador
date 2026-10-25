-- ============================================================================
-- ColabModel - Esquema inicial
-- Metamodelo canonico (ADR-003): UML extendido + anotaciones ER.
-- Normalizado (ADR-008); el snapshot jsonb es solo cache de lectura.
-- ============================================================================

-- ---------------------------------------------------------------- IDENTIDAD
CREATE TABLE users (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email         VARCHAR(160) NOT NULL UNIQUE,
    password_hash VARCHAR(120) NOT NULL,
    full_name     VARCHAR(160) NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE projects (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name       VARCHAR(160) NOT NULL,
    owner_id   UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_projects_owner ON projects(owner_id);

CREATE TABLE project_members (
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    user_id    UUID NOT NULL REFERENCES users(id)    ON DELETE CASCADE,
    role       VARCHAR(10) NOT NULL,
    PRIMARY KEY (project_id, user_id),
    CONSTRAINT chk_member_role CHECK (role IN ('OWNER','EDITOR','VIEWER'))
);
CREATE INDEX idx_members_user ON project_members(user_id);

-- ------------------------------------------------- METAMODELO (la verdad)
CREATE TABLE diagrams (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id   UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    name         VARCHAR(160) NOT NULL,
    current_seq  BIGINT NOT NULL DEFAULT 0,
    default_view VARCHAR(10) NOT NULL DEFAULT 'CLASS',
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_default_view CHECK (default_view IN ('CLASS','CHEN','LOGICAL','PHYSICAL'))
);
CREATE INDEX idx_diagrams_project ON diagrams(project_id);

CREATE TABLE uml_class (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    diagram_id  UUID NOT NULL REFERENCES diagrams(id) ON DELETE CASCADE,
    name        VARCHAR(120) NOT NULL,
    stereotype  VARCHAR(40),
    is_abstract BOOLEAN NOT NULL DEFAULT FALSE,
    -- Anotaciones ER (vista Chen)
    is_weak     BOOLEAN NOT NULL DEFAULT FALSE,
    er_kind     VARCHAR(12) NOT NULL DEFAULT 'STRONG',
    deleted_at  TIMESTAMPTZ,                          -- tombstone (add-wins)
    CONSTRAINT chk_er_kind CHECK (er_kind IN ('STRONG','WEAK','ASSOCIATIVE'))
);
CREATE INDEX idx_class_diagram ON uml_class(diagram_id);
CREATE UNIQUE INDEX uq_class_name_per_diagram
    ON uml_class(diagram_id, lower(name)) WHERE deleted_at IS NULL;

CREATE TABLE uml_attribute (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    class_id            UUID NOT NULL REFERENCES uml_class(id) ON DELETE CASCADE,
    name                VARCHAR(120) NOT NULL,
    data_type           VARCHAR(40)  NOT NULL DEFAULT 'string',
    length              INTEGER,
    is_pk               BOOLEAN NOT NULL DEFAULT FALSE,
    is_required         BOOLEAN NOT NULL DEFAULT FALSE,
    is_unique           BOOLEAN NOT NULL DEFAULT FALSE,
    default_value       VARCHAR(255),
    ordinal             INTEGER NOT NULL DEFAULT 0,
    -- Anotaciones ER
    is_multivalued      BOOLEAN NOT NULL DEFAULT FALSE,   -- doble ovalo -> 1FN (Regla 12)
    is_derived          BOOLEAN NOT NULL DEFAULT FALSE,   -- ovalo punteado
    is_partial_key      BOOLEAN NOT NULL DEFAULT FALSE,   -- discriminador de entidad debil
    parent_attribute_id UUID REFERENCES uml_attribute(id) ON DELETE CASCADE  -- atributo compuesto
);
CREATE INDEX idx_attr_class  ON uml_attribute(class_id);
CREATE INDEX idx_attr_parent ON uml_attribute(parent_attribute_id);
CREATE UNIQUE INDEX uq_attr_name_per_class ON uml_attribute(class_id, lower(name));

CREATE TABLE uml_method (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    class_id    UUID NOT NULL REFERENCES uml_class(id) ON DELETE CASCADE,
    name        VARCHAR(120) NOT NULL,
    return_type VARCHAR(60) NOT NULL DEFAULT 'void',
    params      JSONB NOT NULL DEFAULT '[]'::jsonb,
    visibility  VARCHAR(10) NOT NULL DEFAULT 'PUBLIC',
    ordinal     INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT chk_visibility CHECK (visibility IN ('PUBLIC','PRIVATE','PROTECTED','PACKAGE'))
);
CREATE INDEX idx_method_class ON uml_method(class_id);

CREATE TABLE uml_relationship (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    diagram_id           UUID NOT NULL REFERENCES diagrams(id)  ON DELETE CASCADE,
    source_class_id      UUID NOT NULL REFERENCES uml_class(id) ON DELETE CASCADE,
    target_class_id      UUID NOT NULL REFERENCES uml_class(id) ON DELETE CASCADE,
    kind                 VARCHAR(16) NOT NULL,
    name                 VARCHAR(120),
    source_role          VARCHAR(120),    -- de aqui sale el nombre de columna FK (Regla 3)
    target_role          VARCHAR(120),
    source_multiplicity  VARCHAR(12),
    target_multiplicity  VARCHAR(12),
    -- Anotaciones ER
    is_identifying       BOOLEAN NOT NULL DEFAULT FALSE,   -- rombo doble
    source_participation VARCHAR(8),                       -- TOTAL | PARTIAL
    target_participation VARCHAR(8),
    degree               INTEGER NOT NULL DEFAULT 2,       -- >2 = n-aria (Regla 10: rechazar)
    deleted_at           TIMESTAMPTZ,
    CONSTRAINT chk_rel_kind CHECK (kind IN
        ('ASSOCIATION','AGGREGATION','COMPOSITION','GENERALIZATION','DEPENDENCY')),
    CONSTRAINT chk_src_part CHECK (source_participation IS NULL OR source_participation IN ('TOTAL','PARTIAL')),
    CONSTRAINT chk_tgt_part CHECK (target_participation IS NULL OR target_participation IN ('TOTAL','PARTIAL'))
);
CREATE INDEX idx_rel_diagram ON uml_relationship(diagram_id);
CREATE INDEX idx_rel_source  ON uml_relationship(source_class_id);
CREATE INDEX idx_rel_target  ON uml_relationship(target_class_id);

-- ------------------------------------------- GEOMETRIA (separada a proposito)
-- Solo entidades y rombos. Los atributos NO tienen fila aqui: su posicion
-- se calcula en el cliente con layout radial (ADR-006).
CREATE TABLE diagram_layout (
    diagram_id   UUID NOT NULL REFERENCES diagrams(id) ON DELETE CASCADE,
    element_id   UUID NOT NULL,
    element_kind VARCHAR(10) NOT NULL,
    x            DOUBLE PRECISION NOT NULL DEFAULT 0,
    y            DOUBLE PRECISION NOT NULL DEFAULT 0,
    width        DOUBLE PRECISION NOT NULL DEFAULT 180,
    height       DOUBLE PRECISION NOT NULL DEFAULT 90,
    z_index      INTEGER NOT NULL DEFAULT 0,
    waypoints    JSONB,
    PRIMARY KEY (diagram_id, element_id),
    CONSTRAINT chk_element_kind CHECK (element_kind IN ('CLASS','RELATION'))
);

-- -------------------------------------------------------------- COLABORACION
CREATE TABLE diagram_operations (
    id         BIGSERIAL PRIMARY KEY,
    diagram_id UUID   NOT NULL REFERENCES diagrams(id) ON DELETE CASCADE,
    seq        BIGINT NOT NULL,
    op_id      UUID   NOT NULL,                 -- idempotencia (viene del cliente)
    user_id    UUID   REFERENCES users(id) ON DELETE SET NULL,
    op_type    VARCHAR(32) NOT NULL,
    origin     VARCHAR(16) NOT NULL,            -- MOUSE|TEXT|VOICE|IMAGE|XMI_IMPORT
    payload    JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_op_seq CHECK (seq >= 0)
);
CREATE UNIQUE INDEX uq_ops_diagram_seq ON diagram_operations(diagram_id, seq);
CREATE UNIQUE INDEX uq_ops_op_id       ON diagram_operations(op_id);
CREATE INDEX        idx_ops_diagram    ON diagram_operations(diagram_id);

CREATE TABLE diagram_snapshots (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    diagram_id UUID   NOT NULL REFERENCES diagrams(id) ON DELETE CASCADE,
    seq        BIGINT NOT NULL,
    model      JSONB  NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_snapshots_diagram ON diagram_snapshots(diagram_id, seq DESC);

CREATE TABLE element_locks (
    diagram_id  UUID NOT NULL REFERENCES diagrams(id) ON DELETE CASCADE,
    element_id  UUID NOT NULL,
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    acquired_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at  TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (diagram_id, element_id)
);
CREATE INDEX idx_locks_expires ON element_locks(expires_at);

-- ------------------------------------------------------ IA Y GENERACION
CREATE TABLE ai_commands (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    diagram_id      UUID NOT NULL REFERENCES diagrams(id) ON DELETE CASCADE,
    user_id         UUID REFERENCES users(id) ON DELETE SET NULL,
    channel         VARCHAR(10) NOT NULL,
    raw_input       TEXT,
    transcript      TEXT,
    parsed_commands JSONB,
    status          VARCHAR(12) NOT NULL DEFAULT 'PENDING',
    error_msg       TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_channel    CHECK (channel IN ('VOICE','TEXT','IMAGE')),
    CONSTRAINT chk_ai_status  CHECK (status  IN ('PENDING','OK','REJECTED','FAILED'))
);
CREATE INDEX idx_ai_diagram ON ai_commands(diagram_id);

CREATE TABLE modeling_decisions (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    diagram_id     UUID NOT NULL REFERENCES diagrams(id) ON DELETE CASCADE,
    element_id     UUID NOT NULL,
    ambiguity_kind VARCHAR(24) NOT NULL,
    chosen         VARCHAR(8)  NOT NULL,
    decided_by     UUID REFERENCES users(id) ON DELETE SET NULL,
    decided_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_ambiguity CHECK (ambiguity_kind IN
        ('COMPOSITION','EMBEDDABLE','MULTIVALUED','ASSOC_CLASS')),
    CONSTRAINT chk_chosen    CHECK (chosen IN ('OO','DATA'))
);
CREATE UNIQUE INDEX uq_decision_element ON modeling_decisions(diagram_id, element_id, ambiguity_kind);

CREATE TABLE generation_jobs (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    diagram_id    UUID NOT NULL REFERENCES diagrams(id) ON DELETE CASCADE,
    user_id       UUID REFERENCES users(id) ON DELETE SET NULL,
    status        VARCHAR(12) NOT NULL DEFAULT 'QUEUED',
    options       JSONB,
    artifact_path VARCHAR(500),
    build_log     TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at   TIMESTAMPTZ,
    CONSTRAINT chk_job_status CHECK (status IN ('QUEUED','RUNNING','COMPILING','OK','FAILED'))
);
CREATE INDEX idx_jobs_diagram ON generation_jobs(diagram_id);
