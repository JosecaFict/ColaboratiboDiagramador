package com.uagrm.colabmodel.vision;

/**
 * NIVEL 2 (ADR-012). Le pone texto a lo que el nivel 1 ya ubico.
 *
 * Hoy NO HAY NINGUNA IMPLEMENTACION, y eso es deliberado: es la costura donde
 * enchufar un modelo de vision local --o un OCR clasico-- sin tocar nada mas.
 * Mientras no exista, el controlador inyecta un Optional vacio y la propuesta
 * sale con los textos en null. El nivel 1 no depende de esto para funcionar.
 *
 * LA FORMA DE ESTA INTERFAZ ES LA DECISION IMPORTANTE. No recibe la imagen
 * para que la interprete: recibe la propuesta del nivel 1, con cada recorte ya
 * ubicado y ya etiquetado con lo que se espera encontrar dentro. La diferencia
 * no es de estilo, es de viabilidad:
 *
 *   - Pregunta grande: "leeme esta pizarra y dame un modelo UML". Una imagen
 *     de 1400x1000 son ~1800 tokens de imagen, casi un minuto de CPU sin GPU,
 *     y un modelo chico contesta con atributos que no estaban.
 *
 *   - Preguntas chicas: veinte recortes de 200x60 son ~15 tokens cada uno.
 *     Mas barato que una sola imagen entera, y cada pregunta es de las que un
 *     modelo de 2 GB SI acierta, porque no tiene que inferir ninguna
 *     estructura: la estructura ya se la dio la geometria.
 *
 * El enriquecedor puede devolver texto equivocado. Esta bien: no escribe nada.
 * Todo pasa despues por el panel de confirmacion, que es lo que convierte la
 * falta de fiabilidad de un modelo en un paso de interfaz en vez de en un
 * defecto de correccion.
 *
 * Contrato para quien la implemente:
 *   - no modificar la geometria ni la topologia: eso ya lo decidio el nivel 1;
 *     solo rellenar `texto`, `figura`, `nombre` y sus confianzas;
 *   - ante la duda, dejar null. Un null es una celda que el usuario completa;
 *     un texto inventado es un defecto que nadie ve;
 *   - no inventar clases ni relaciones que la geometria no vio;
 *   - respetar el presupuesto de tiempo: si no llega, devolver lo que tenga.
 */
public interface EnriquecedorDeDeteccion {

    /**
     * @param base    lo que vio el nivel 1. Nunca null
     * @param imagen  para pedir los recortes con {@code imagen.recortarComoPng(caja)}
     * @return la misma propuesta con los huecos que haya podido llenar. Ante
     *         cualquier fallo, devolver `base` tal cual: degradar es correcto,
     *         romper la deteccion entera no
     */
    DeteccionPropuesta enriquecer(DeteccionPropuesta base, ImagenDeEntrada imagen);

    /** Como se llama en el panel y en las metricas. */
    String nombre();

    /**
     * Si el modelo esta cargado y responde. Se consulta en cada peticion, no al
     * arrancar: un modelo local se cae, se queda sin memoria o lo apagan para
     * hacer lugar, y el sistema tiene que seguir contestando por el nivel 1.
     */
    boolean disponible();
}
