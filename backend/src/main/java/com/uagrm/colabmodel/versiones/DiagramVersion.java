package com.uagrm.colabmodel.versiones;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Un commit (ADR-037): un `seq` con nombre, mensaje, autor y la foto del modelo en ese seq. */
@Entity
@Table(name = "diagram_versions")
public class DiagramVersion {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "diagram_id", nullable = false)
    private UUID diagramId;

    @Column(name = "seq", nullable = false)
    private long seq;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "message")
    private String message;

    @Column(name = "author_id")
    private UUID authorId;

    @Column(name = "parent_version_id")
    private UUID parentVersionId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "snapshot", nullable = false, columnDefinition = "jsonb")
    private String snapshot;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getDiagramId() { return diagramId; }
    public void setDiagramId(UUID diagramId) { this.diagramId = diagramId; }
    public long getSeq() { return seq; }
    public void setSeq(long seq) { this.seq = seq; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public UUID getAuthorId() { return authorId; }
    public void setAuthorId(UUID authorId) { this.authorId = authorId; }
    public UUID getParentVersionId() { return parentVersionId; }
    public void setParentVersionId(UUID parentVersionId) { this.parentVersionId = parentVersionId; }
    public String getSnapshot() { return snapshot; }
    public void setSnapshot(String snapshot) { this.snapshot = snapshot; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
