package com.uagrm.colabmodel.ai.imagenes;

/**
 * LA COSTURA DEL REENCODADO (D4). Recibe los bytes ya validados y devuelve lo que se guarda: la imagen sin
 * EXIF (con la orientacion aplicada, el alfa aplanado sobre blanco, etc.) y su miniatura de 320 px.
 *
 * PENDIENTE a proposito: el detalle (seccion 7 del diseno) lo estan midiendo Steven y Rodolfo. Mientras no
 * exista un bean, subir una imagen responde 503; el resto de la Fase 1 no depende de como se reencoda.
 */
public interface ReencodadorDeImagen {

    /**
     * @param tipo      `image/png` o `image/jpeg`: el que se GUARDA, no el que mando el cliente
     * @param miniatura siempre JPEG
     */
    record Reencodada(byte[] datos, String tipo, int ancho, int alto, byte[] miniatura) { }

    /**
     * @throws com.uagrm.colabmodel.vision.ImagenDeEntrada.ImagenInvalida si no se puede reencodar (p. ej. CMYK)
     */
    Reencodada reencodar(byte[] original, String contentType);
}
