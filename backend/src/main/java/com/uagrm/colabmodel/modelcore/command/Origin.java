package com.uagrm.colabmodel.modelcore.command;

/**
 * Productor de un comando (ADR-002).
 *
 * Las cinco modalidades de entrada convergen en el mismo bus. Gracias a esto,
 * la exclusion mutua, la auditoria y el undo se resuelven UNA sola vez.
 */
public enum Origin {
    MOUSE,
    TEXT,
    VOICE,
    IMAGE,
    /** Importacion de un archivo: XMI, SQL o el JSON de los modelos canonicos. */
    XMI_IMPORT
}
