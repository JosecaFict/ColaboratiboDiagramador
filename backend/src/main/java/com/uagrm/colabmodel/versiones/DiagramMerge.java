package com.uagrm.colabmodel.versiones;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Un merge ya aplicado. Guarda el estado del diagrama de origen en ese momento:
 * es la base del proximo merge entre los dos (ver V8__versiones.sql).
 */
@Entity
@Table(name = "diagram_merges")
public class DiagramMerge {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "target_diagram_id", nullable = false)
    private UUID targetDiagramId;

    @Column(name = "from_diagram_id", nullable = false)
    private UUID fromDiagramId;

    /** Con los ids FISICOS del diagrama de origen. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "from_snapshot", nullable = false, columnDefinition = "jsonb")
    private String fromSnapshot;

    @Column(name = "from_seq", nullable = false)
    private long fromSeq;

    @Column(name = "target_seq", nullable = false)
    private long targetSeq;

    @Column(name = "author_id")
    private UUID authorId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getTargetDiagramId() { return targetDiagramId; }
    public void setTargetDiagramId(UUID v) { this.targetDiagramId = v; }
    public UUID getFromDiagramId() { return fromDiagramId; }
    public void setFromDiagramId(UUID v) { this.fromDiagramId = v; }
    public String getFromSnapshot() { return fromSnapshot; }
    public void setFromSnapshot(String v) { this.fromSnapshot = v; }
    public long getFromSeq() { return fromSeq; }
    public void setFromSeq(long v) { this.fromSeq = v; }
    public long getTargetSeq() { return targetSeq; }
    public void setTargetSeq(long v) { this.targetSeq = v; }
    public UUID getAuthorId() { return authorId; }
    public void setAuthorId(UUID v) { this.authorId = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { this.createdAt = v; }
}
