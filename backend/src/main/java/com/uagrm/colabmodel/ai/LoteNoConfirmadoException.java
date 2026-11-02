package com.uagrm.colabmodel.ai;

/**
 * Se pidió marcar una propuesta como aplicada, pero el log del diagrama no tiene
 * un BATCH aceptado que la aplique. La marca no se pone (T-04): "aplicada"
 * quiere decir que el bus la aceptó, no que alguien apretó el botón.
 */
public class LoteNoConfirmadoException extends RuntimeException {
    public LoteNoConfirmadoException(String mensaje) {
        super(mensaje);
    }
}
