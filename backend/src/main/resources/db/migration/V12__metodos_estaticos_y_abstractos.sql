-- Contrato UML 2.5.1 v1, rebanada (b): metodos estaticos y abstractos (D-15).
-- Aditiva: los metodos existentes quedan en false, que es lo que eran.
ALTER TABLE uml_method ADD COLUMN is_static   BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE uml_method ADD COLUMN is_abstract BOOLEAN NOT NULL DEFAULT false;
-- UML no permite un miembro que sea de clase y a la vez sin cuerpo (V2).
ALTER TABLE uml_method ADD CONSTRAINT chk_method_static_abstract CHECK (NOT (is_static AND is_abstract));
