package com.uagrm.colabmodel.project;

import java.util.List;
import java.util.UUID;

/**
 * Se elimino un proyecto entero o uno de sus diagramas (ADR-026).
 *
 * Es un evento por la misma razon que `CambioDeAcceso`: `project` no puede
 * depender de `collab`. Quien esta mirando ese diagrama tiene que enterarse, y
 * sus locks y suscripciones tienen que soltarse: el diagrama ya no existe.
 *
 * @param diagramaEliminado el diagrama que se fue, o `null` si se fue el proyecto entero
 * @param diagramas         todos los diagramas que dejaron de existir
 * @param miembros          a quienes hay que avisarles: el propietario y los miembros
 */
public record RecursoEliminado(UUID proyectoId, String proyectoNombre, UUID diagramaEliminado,
                               List<UUID> diagramas, List<UUID> miembros) {

    public boolean fueElProyecto() {
        return diagramaEliminado == null;
    }
}
