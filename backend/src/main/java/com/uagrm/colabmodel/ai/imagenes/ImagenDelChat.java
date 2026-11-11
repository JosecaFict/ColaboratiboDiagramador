package com.uagrm.colabmodel.ai.imagenes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Los METADATOS de una imagen del chat (tabla `ai_images`). No mapea `data` ni `thumbnail`: listar, contar y
 * armar el historial nunca arrastran bytes (docs/DISENO-CHAT-IMAGENES.md, seccion 1).
 *
 * Es de solo lectura para JPA: las filas las escribe {@link AlmacenDeImagenes}, porque el CHECK
 * `chk_ai_image_bytes` exige que una fila viva nazca con sus bytes. Como se mapean los bytes (proyeccion o
 * tabla aparte) lo decide la implementacion del almacen, no esta entidad.
 */
@Entity
@Immutable
@Table(name = "ai_images")
public class ImagenDelChat {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "diagram_id", nullable = false, updatable = false)
    private UUID diagramId;

    @Column(name = "uploaded_by")
    private UUID uploadedBy;

    @Column(name = "content_type", nullable = false, length = 16)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private int sizeBytes;

    @Column(name = "width", nullable = false)
    private int width;

    @Column(name = "height", nullable = false)
    private int height;

    @Column(name = "sha256", nullable = false, length = 64)
    private String sha256;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    @Column(name = "deleted_by")
    private UUID deletedBy;

    protected ImagenDelChat() { }

    public ImagenDelChat(UUID id, UUID diagramId, UUID uploadedBy, String contentType, int sizeBytes, int width,
                         int height, String sha256, OffsetDateTime createdAt, OffsetDateTime deletedAt,
                         UUID deletedBy) {
        this.id = id;
        this.diagramId = diagramId;
        this.uploadedBy = uploadedBy;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.width = width;
        this.height = height;
        this.sha256 = sha256;
        this.createdAt = createdAt;
        this.deletedAt = deletedAt;
        this.deletedBy = deletedBy;
    }

    public UUID getId() { return id; }
    public UUID getDiagramId() { return diagramId; }
    public UUID getUploadedBy() { return uploadedBy; }
    public String getContentType() { return contentType; }
    public int getSizeBytes() { return sizeBytes; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public String getSha256() { return sha256; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getDeletedAt() { return deletedAt; }
    public UUID getDeletedBy() { return deletedBy; }
    public boolean estaEliminada() { return deletedAt != null; }
}
