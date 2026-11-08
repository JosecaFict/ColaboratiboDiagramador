package com.uagrm.colabmodel.ai;

import java.util.List;
import java.util.UUID;

/**
 * Lo que devuelve el modo RECOMENDAR: solo lectura, como una propuesta sin
 * aplicar. Aplicar una recomendacion es pedir una propuesta MODIFICAR con su
 * `accion`, que pasa por la misma validacion y confirmacion que cualquier otra.
 *
 * @param verificaciones         una por regla del requisito, cumpla o no: es la
 *                               trazabilidad requisito -> diagrama. Vacia si no
 *                               se adjunto un requisito con reglas
 * @param hallazgosEstructurales lo que el backend verifico sin IA
 */
public record RecomendacionesIA(UUID diagramId, String resumen, List<Recomendacion> recomendaciones,
                                List<Verificacion> verificaciones, List<String> hallazgosEstructurales,
                                String modelo) {

    /**
     * @param elementos solo los que existen en el diagrama: sirven para
     *                  resaltarlos en el lienzo, y un nombre inventado no resalta nada
     */
    public record Recomendacion(int id, String titulo, String gravedad, String categoria, String regla,
                                List<String> elementos, String problema, String accion) { }

    /** @param estado CUMPLE, NO_CUMPLE o NO_VERIFICABLE (el modelo cito elementos que no existen) */
    public record Verificacion(int numero, String regla, String seccion, String estado,
                               List<String> evidencia, String explicacion) { }
}
