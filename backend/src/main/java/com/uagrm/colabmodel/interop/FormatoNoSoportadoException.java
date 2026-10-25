package com.uagrm.colabmodel.interop;

/** Ningun lector se hace cargo del archivo. El mensaje lista los formatos que si. */
public class FormatoNoSoportadoException extends RuntimeException {

    public FormatoNoSoportadoException(String mensaje) {
        super(mensaje);
    }
}
