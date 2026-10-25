package com.uagrm.colabmodel.modelcore.metamodel;

/**
 * Visibilidad UML de un metodo.
 *
 * No llega al DDL --la base guarda estado, no comportamiento-- pero si al
 * Service generado (Regla 6): un metodo privado del UML no tiene por que
 * quedar expuesto como operacion publica del servicio.
 */
public enum Visibility {
    PUBLIC("+"), PRIVATE("-"), PROTECTED("#"), PACKAGE("~");

    private final String simbolo;

    Visibility(String simbolo) { this.simbolo = simbolo; }

    /** El signo que se dibuja delante de la firma en la caja UML. */
    public String simbolo() { return simbolo; }
}
