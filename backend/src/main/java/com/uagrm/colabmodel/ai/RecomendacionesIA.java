package com.uagrm.colabmodel.ai;

import java.util.List;
import java.util.UUID;

/**
 * Lo que devuelve el modo RECOMENDAR: solo lectura, como una propuesta sin
 * aplicar. Aplicar una recomendacion es pedir una propuesta MODIFICAR con su
 * `accion`, que pasa por la misma validacion y confirmacion que cualquier otra.
 *
 * @param hallazgosEstructurales lo que el backend verifico sin IA; se le pasa
 *                               al modelo y se muestra tal cual al usuario
 */
public record RecomendacionesIA(UUID diagramId, String resumen, List<Recomendacion> recomendaciones,
                                List<String> hallazgosEstructurales, String modelo) {

    /**
     * @param elementos solo los que existen en el diagrama: sirven para
     *                  resaltarlos en el lienzo, y un nombre inventado no resalta nada
     */
    public record Recomendacion(int id, String titulo, String gravedad, String categoria, String regla,
                                List<String> elementos, String problema, String accion) { }
}
