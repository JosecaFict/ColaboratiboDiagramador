package com.uagrm.colabmodel.ai.imagenes;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * LA COSTURA DE LOS BYTES. Todo lo que toca `ai_images.data` y `ai_images.thumbnail` pasa por aqui; el resto
 * de la Fase 1 (permisos, tope, deduplicacion, avisos, historial) no sabe como se guardan.
 *
 * PENDIENTE a proposito: como se mapean los bytes (proyeccion sobre la misma tabla o tabla aparte) lo estan
 * midiendo Steven y Rodolfo. Cuando haya conclusion se agrega la implementacion, sin cambiar esta interfaz ni
 * ninguna de sus llamadas. Mientras no exista un bean, las rutas que necesitan bytes responden 503.
 */
public interface AlmacenDeImagenes {

    record NuevaImagen(UUID id, UUID diagramId, UUID subidaPor, String tipo, int ancho, int alto, String sha256,
                       byte[] datos, byte[] miniatura) {
        public int bytes() { return datos.length; }
    }

    /** Inserta la fila viva con sus bytes (el CHECK `chk_ai_image_bytes` los exige). Devuelve `created_at`. */
    OffsetDateTime insertar(NuevaImagen nueva);

    /** Los bytes de una imagen VIVA; vacio si no existe o fue eliminada. */
    Optional<byte[]> datos(UUID id);

    /** La miniatura (JPEG) de una imagen VIVA; vacio si no existe o fue eliminada. */
    Optional<byte[]> miniatura(UUID id);

    /** Vacia `data` y `thumbnail` y marca la fila eliminada. Devuelve `deleted_at`. */
    OffsetDateTime eliminar(UUID id, UUID quien);
}
