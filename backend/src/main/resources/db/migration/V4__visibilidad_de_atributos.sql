-- UML 2.5.1: una Property tiene visibilidad, igual que una Operation.
--
-- Faltaba: uml_method la guardaba desde V1 y uml_attribute no, asi que el
-- canvas dibujaba "-" fijo delante de todo atributo. Un modelo con un atributo
-- publico o protegido no se podia ni expresar, y la vista mentia sobre el.
--
-- PRIVATE por defecto, que es lo que el canvas venia dibujando: la migracion
-- no cambia como se ve ningun diagrama que ya exista.
--
-- Es NOTACION, no esquema: no toca el DDL generado ni las 12 reglas. La base
-- guarda estado, y el estado de un atributo privado es el mismo que el de uno
-- publico. Se guarda porque el ADR-003 pide que cambiar de vista nunca pierda
-- informacion, y perderla al ir y volver de Chen seria exactamente eso.
ALTER TABLE uml_attribute
    ADD COLUMN visibility VARCHAR(10) NOT NULL DEFAULT 'PRIVATE';

ALTER TABLE uml_attribute
    ADD CONSTRAINT chk_attr_visibility
    CHECK (visibility IN ('PUBLIC', 'PRIVATE', 'PROTECTED', 'PACKAGE'));
