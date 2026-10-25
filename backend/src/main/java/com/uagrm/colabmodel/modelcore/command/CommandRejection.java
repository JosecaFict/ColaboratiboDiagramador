package com.uagrm.colabmodel.modelcore.command;

import java.util.UUID;

/**
 * Rechazo de un comando. Viaja por /user/queue/errors: es una cola PRIVADA
 * del usuario. Los demas no necesitan enterarse de que alguien fallo.
 */
public record CommandRejection(
        UUID opId,
        RejectionReason reason,
        String message
) {
}
