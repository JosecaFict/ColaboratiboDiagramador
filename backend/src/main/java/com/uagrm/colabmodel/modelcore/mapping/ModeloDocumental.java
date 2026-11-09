package com.uagrm.colabmodel.modelcore.mapping;

import java.util.List;

/**
 * Vista Documental (ADR-035): el modelo conceptual proyectado a colecciones
 * de MongoDB, igual que {@link ModeloLogico} lo proyecta a tablas.
 *
 * Es de solo lectura y sale del mismo analisis que el backend MongoDB
 * generado: lo que se ve aca es lo que guarda el zip. La forma JSON es el
 * contrato de `GET /diagrams/{id}/documental` (DISENO-FASE-2.md §A).
 */
public record ModeloDocumental(List<Coleccion> colecciones, List<String> advertencias) {

    /**
     * @param nombre        la coleccion (`pedidos`)
     * @param entidad       la clase que la origina; en una jerarquia, la raiz
     * @param discriminador `_tipo` si guarda una jerarquia entera, si no null
     */
    public record Coleccion(String nombre, String entidad, List<Campo> campos, List<Incrustado> incrustados,
                            List<Referencia> referencias, String discriminador) { }

    /** @param tipo el tipo del modelo (`string`, `date`, `decimal`...) o el nombre de la enumeracion */
    public record Campo(String nombre, String tipo, boolean requerido) { }

    /**
     * Algo que vive DENTRO del documento: la parte de una composicion o un
     * objeto de valor (Regla 5).
     */
    public record Incrustado(String campo, String entidad, boolean multiple) { }

    /** Un id que apunta a otra coleccion; `multiple` es un arreglo de ids (N:M). */
    public record Referencia(String campo, String coleccion, boolean multiple) { }
}
