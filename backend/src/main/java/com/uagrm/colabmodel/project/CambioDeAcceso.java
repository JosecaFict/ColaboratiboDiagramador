package com.uagrm.colabmodel.project;

import java.util.List;
import java.util.UUID;

/**
 * El acceso de UN usuario a un proyecto cambio (ADR-026).
 *
 * `project` no puede avisarle a las sesiones WebSocket: `collab` ya depende de
 * `project`, y lo contrario cerraria un ciclo entre modulos (EC-12). Por eso
 * esto es un evento: `project` lo publica cuando termina su transaccion y
 * `collab.PropagadorDeAcceso` lo escucha. Se entrega DESPUES del commit: si la
 * transaccion se deshace --una transferencia que falla a la mitad--, nadie se
 * entera de un cambio que no ocurrio.
 *
 * @param diagramas los diagramas del proyecto: es donde hay que soltar locks y
 *                  cancelar suscripciones
 * @param rol       el rol que el usuario tiene AHORA; `null` = ya no tiene acceso
 */
public record CambioDeAcceso(UUID proyectoId, String proyectoNombre, List<UUID> diagramas,
                             UUID usuarioId, Motivo motivo, AccesoAProyecto.Rol rol) {

    public enum Motivo {
        /** Lo agregaron al proyecto. */
        AGREGADO,
        /** Le cambiaron el rol. */
        ROL_CAMBIADO,
        /** Recibio la propiedad: ahora es OWNER. */
        PROPIEDAD_RECIBIDA,
        /** Cedio la propiedad: quedo como EDITOR. */
        PROPIEDAD_CEDIDA,
        /** El propietario lo quito. */
        QUITADO,
        /** Se fue por su cuenta. */
        ABANDONO
    }
}
