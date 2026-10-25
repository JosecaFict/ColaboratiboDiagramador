package com.uagrm.colabmodel.ai;

/** El proveedor no se pudo contactar, o devolvio algo que no se puede leer. */
public class ProveedorIAException extends RuntimeException {
    public ProveedorIAException(String message) {
        super(message);
    }
}
