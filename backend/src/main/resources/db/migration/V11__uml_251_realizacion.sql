-- Contrato UML 2.5.1 v1, rebanada (a): la relacion REALIZATION (D-15).
-- Aditiva: no reescribe filas ni cambia los valores existentes. La interfaz no
-- necesita columna: es un estereotipo ('interface') de uml_class, como 'enumeration'.
ALTER TABLE uml_relationship DROP CONSTRAINT chk_rel_kind;
ALTER TABLE uml_relationship ADD CONSTRAINT chk_rel_kind CHECK (kind IN
    ('ASSOCIATION','AGGREGATION','COMPOSITION','GENERALIZATION','DEPENDENCY','REALIZATION'));
