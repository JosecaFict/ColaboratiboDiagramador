package com.uagrm.colabmodel.ai.imagenes;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Los metadatos de una imagen del chat. NUNCA lleva bytes ni URL ni nombre de archivo
 * (docs/DISENO-CHAT-IMAGENES.md, seccion 4). Con `non_null` los campos nulos no salen en el JSON.
 *
 * @param subidaPorId falta si la cuenta se borro (`subidaPorNombre` es entonces "Usuario eliminado")
 * @param eliminadaEn solo si `eliminada`; el tipo, el ancho, el alto y los bytes se conservan
 */
public record ImagenMeta(UUID id, UUID subidaPorId, String subidaPorNombre, String tipo, int ancho, int alto,
                         int bytes, OffsetDateTime creadaEn, boolean eliminada, OffsetDateTime eliminadaEn) {

    public static final String USUARIO_ELIMINADO = "Usuario eliminado";

    static ImagenMeta de(ImagenDelChat i, String nombreDeQuienLaSubio) {
        return new ImagenMeta(i.getId(), i.getUploadedBy(),
                nombreDeQuienLaSubio == null ? USUARIO_ELIMINADO : nombreDeQuienLaSubio, i.getContentType(),
                i.getWidth(), i.getHeight(), i.getSizeBytes(), i.getCreatedAt(), i.estaEliminada(),
                i.getDeletedAt());
    }
}
