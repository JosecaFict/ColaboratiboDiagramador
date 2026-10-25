-- REGLA 9, tercera capa: un atributo numerico puede declarar su rango
-- (precio >= 0, nota entre 0 y 100). Sale como CHECK en el DDL generado y como
-- @Min/@Max en el DTO.
--
-- Migracion nueva y no V1 editado: Flyway guarda el checksum de lo ya aplicado.
ALTER TABLE uml_attribute
    ADD COLUMN min_value NUMERIC(19, 4),
    ADD COLUMN max_value NUMERIC(19, 4);

-- El metamodelo tampoco acepta un rango imposible: un CHECK generado con
-- minimo mayor que maximo no dejaria insertar ninguna fila.
ALTER TABLE uml_attribute
    ADD CONSTRAINT chk_rango_valido
    CHECK (min_value IS NULL OR max_value IS NULL OR min_value <= max_value);
