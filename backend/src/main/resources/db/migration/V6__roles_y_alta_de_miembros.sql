-- Gestion de proyectos, miembros y roles (ADR-026).
--
-- El OWNER es implicito: vive en projects.owner_id y NUNCA tiene fila en
-- project_members. V1 lo permitia en el CHECK ('OWNER','EDITOR','VIEWER'), asi
-- que un proyecto podia tener dos propietarios --uno real y uno en la tabla--
-- y AccesoAProyecto no habria sabido cual manda. Se cierra la puerta en la base
-- y no solo en el codigo: un INSERT a mano tampoco puede crear un segundo OWNER.
--
-- Antes de apretar el CHECK se verifica que no haya ninguna fila OWNER. No se
-- las borra ni se las convierte en silencio: si aparecen, la migracion se
-- detiene y dice cuales, porque decidir que hacer con un segundo propietario
-- no es una decision de una migracion.
DO $$
DECLARE
    n integer;
BEGIN
    SELECT count(*) INTO n FROM project_members WHERE role = 'OWNER';
    IF n > 0 THEN
        RAISE EXCEPTION
            'V6: hay % fila(s) OWNER en project_members. El propietario vive en projects.owner_id; revisalas a mano antes de migrar.', n;
    END IF;
END $$;

ALTER TABLE project_members DROP CONSTRAINT chk_member_role;
ALTER TABLE project_members
    ADD CONSTRAINT chk_member_role CHECK (role IN ('EDITOR', 'VIEWER'));

-- Desde cuando es miembro: la lista de miembros lo muestra. Las filas que ya
-- existen quedan con la fecha de esta migracion (no se conoce la real).
ALTER TABLE project_members
    ADD COLUMN added_at TIMESTAMPTZ NOT NULL DEFAULT now();
