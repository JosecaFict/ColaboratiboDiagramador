package com.uagrm.colabmodel.interop;

/** El archivo no se pudo leer. El mensaje es para el usuario: dice que esta mal y donde. */
public class ArchivoInvalidoException extends RuntimeException {

    public ArchivoInvalidoException(String mensaje) {
        super(mensaje);
    }

    public ArchivoInvalidoException(String mensaje, Throwable causa) {
        super(mensaje, causa);
    }
}
