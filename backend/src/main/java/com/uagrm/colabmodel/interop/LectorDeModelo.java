package com.uagrm.colabmodel.interop;

/**
 * Un formato de archivo que la herramienta sabe leer.
 *
 * Solo LEE: nunca escribe en el diagrama. La escritura ocurre despues de que
 * el usuario revisa la propuesta, y entra por el bus como un BATCH (ADR-002).
 * Agregar un formato nuevo es agregar una implementacion; nada mas cambia.
 */
public interface LectorDeModelo {

    /** Nombre corto para mostrar: "JSON", "XMI", "SQL". */
    String formato();

    /** Si este lector se hace cargo del archivo, por su nombre y su contenido. */
    boolean acepta(String nombreArchivo, byte[] contenido);

    /**
     * @throws ArchivoInvalidoException si el archivo no tiene la forma esperada;
     *         el mensaje se le muestra al usuario tal cual
     */
    ModeloLeido leer(byte[] contenido);
}
