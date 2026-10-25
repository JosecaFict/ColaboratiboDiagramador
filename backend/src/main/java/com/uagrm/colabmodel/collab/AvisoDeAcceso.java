package com.uagrm.colabmodel.collab;

import java.util.UUID;

/**
 * Lo que recibe, por su cola privada `/user/queue/membresia`, alguien cuyo
 * acceso a un proyecto cambio (ADR-026). La interfaz lo usa para adaptarse sin
 * recargar: pasar a solo lectura, volver a "Mis proyectos", refrescar la lista.
 *
 * Llega a TODAS las pestanas del usuario, a diferencia de un rechazo de
 * comando (`/user/queue/errors`), que solo le llega a la que lo provoco.
 *
 * @param reason    `AGREGADO`, `ROL_CAMBIADO`, `PROPIEDAD_RECIBIDA`,
 *                  `PROPIEDAD_CEDIDA`, `QUITADO`, `ABANDONO`,
 *                  `PROYECTO_ELIMINADO` o `DIAGRAMA_ELIMINADO`
 * @param role      el rol que tiene AHORA; ausente = ya no tiene acceso
 * @param diagramId solo con `DIAGRAMA_ELIMINADO`: cual se fue
 */
public record AvisoDeAcceso(UUID projectId, String projectName, String reason, String role, UUID diagramId) { }
