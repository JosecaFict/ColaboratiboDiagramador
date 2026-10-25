package com.uagrm.colabmodel.vision;

/**
 * Lee una imagen y propone un diagrama.
 *
 * Se llama DetectorDeDiagrama y no DetectorDePizarra porque la pizarra es un
 * CASO, no la fuente: el contrato `Origin` dice IMAGE desde la Fase 0, y una
 * captura de proyector, un screenshot o un PDF exportado entran por el mismo
 * camino. No es solo cosmetico --esas fuentes son ademas las FACILES, porque
 * no tienen perspectiva, ni brillo, ni letra manuscrita--, asi que atar el
 * nombre al peor caso habria escondido el mejor.
 *
 * Es una interfaz por el ADR-015: agregar una implementacion nueva --OpenCV
 * con correccion de perspectiva, o un modelo de vision-- no debe tocar ni el
 * bus, ni los locks, ni el controlador. Se registra otro bean y listo.
 *
 * Un detector NO ESCRIBE. Recibe pixeles y devuelve candidatos. Todo lo que
 * llegue al modelo pasa despues por el panel de confirmacion y por el bus
 * (ADR-002): no hay un segundo camino de escritura, y este no es una excepcion.
 */
public interface DetectorDeDiagrama {

    /**
     * @return siempre una propuesta, nunca null. Si no reconocio nada, una
     *         propuesta vacia con su aviso: que no encuentre nada es un
     *         resultado, no un error
     */
    DeteccionPropuesta detectar(ImagenDeEntrada imagen);

    /** Como se llama este detector en el panel y en las metricas. */
    String nombre();
}
