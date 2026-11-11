package com.uagrm.colabmodel.ai.imagenes;

/** El diagrama llego a su tope de imagenes (cantidad o bytes): es un 413 con el motivo explicado. */
public class TopeDeImagenesExcedido extends RuntimeException {
    public TopeDeImagenesExcedido(String mensaje) { super(mensaje); }
}
