package com.uagrm.colabmodel.collab;

import java.util.UUID;

/**
 * EXCLUSION MUTUA - NIVEL 4 (presencia).
 *
 * Canal EFIMERO. Nunca toca el modelo ni el registro de operaciones:
 * 20 cursores a 20 fps son 400 mensajes por segundo; en el log matarian
 * el sistema.
 */
public record PresenceMessage(
        UUID userId,
        String userName,
        double x,
        double y,
        UUID selectedElementId
) {}
