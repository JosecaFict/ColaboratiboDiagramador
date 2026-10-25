-- REGLA 4: la herencia sale JOINED por defecto; SINGLE_TABLE es una opcion
-- que se elige POR JERARQUIA, sobre la clase raiz.
--
-- Va en una migracion nueva y no editando V1: Flyway guarda el checksum de lo
-- ya aplicado, y cambiar V1 rompe el arranque de toda base existente (ADR-009).
ALTER TABLE uml_class
    ADD COLUMN inheritance_strategy VARCHAR(12) NOT NULL DEFAULT 'JOINED';

ALTER TABLE uml_class
    ADD CONSTRAINT chk_inheritance_strategy
    CHECK (inheritance_strategy IN ('JOINED', 'SINGLE_TABLE'));
