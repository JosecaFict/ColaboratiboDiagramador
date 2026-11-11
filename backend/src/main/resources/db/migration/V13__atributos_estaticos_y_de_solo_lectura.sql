-- Contrato UML 2.5.1 v1, rebanada (c): atributos estaticos y de solo lectura (D-15).
-- Aditiva: los atributos existentes quedan en false, que es lo que eran.
ALTER TABLE uml_attribute ADD COLUMN is_static    BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE uml_attribute ADD COLUMN is_read_only BOOLEAN NOT NULL DEFAULT false;
