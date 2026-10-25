package com.uagrm.colabmodel.ai;

/**
 * Una llamada a herramienta puntual no se puede traducir a un Command real:
 * referencia una clase/atributo/relacion inexistente, o su forma no alcanza.
 *
 * No aborta la propuesta entera (a diferencia de CommandRejectedException en
 * un BATCH ya confirmado): el resto de los cambios sigue evaluandose, y este
 * se reporta como rechazado con su motivo.
 */
class ComandoIAInvalidoException extends RuntimeException {
    ComandoIAInvalidoException(String message) {
        super(message);
    }
}
