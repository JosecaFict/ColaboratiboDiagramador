package com.uagrm.colabmodel.modelcore.command;

public enum RejectionReason {
    /** Otro usuario tiene el lease sobre ese elemento (exclusion mutua, nivel 2). */
    LOCKED_BY_OTHER,
    /** El cliente opero sobre una version demasiado vieja. */
    STALE,
    /** Viola un invariante del metamodelo. */
    INVALID,
    /** El rol del usuario no permite escribir. */
    FORBIDDEN,
    /** El elemento referenciado no existe o esta con tombstone. */
    NOT_FOUND
}
