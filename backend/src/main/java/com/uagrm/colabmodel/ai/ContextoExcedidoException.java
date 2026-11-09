package com.uagrm.colabmodel.ai;

/** El contenido obligatorio de un pedido no entra en el contexto del modelo (T-09). */
public class ContextoExcedidoException extends RuntimeException {
    public ContextoExcedidoException(String mensaje) {
        super(mensaje);
    }
}
