package com.uagrm.colabmodel.project;

import com.uagrm.colabmodel.modelcore.command.CommandType;
import com.uagrm.colabmodel.modelcore.command.Origin;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Registro de operaciones (event sourcing ligero).
 *
 * Sirve para tres cosas a la vez:
 *   - orden global por diagrama (uq: diagram_id + seq)
 *   - catch-up de un cliente que reconecta (?sinceSeq=N)
 *   - auditoria: quien hizo que, cuando y DESDE QUE CANAL (origin)
 */
@Entity
@Table(name = "diagram_operations")
public class DiagramOperation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "diagram_id", nullable = false)
    private UUID diagramId;

    @Column(name = "seq", nullable = false)
    private long seq;

    /** UUID que genera el cliente. Da idempotencia ante reintentos. */
    @Column(name = "op_id", nullable = false)
    private UUID opId;

    @Column(name = "user_id")
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "op_type", nullable = false, length = 32)
    private CommandType opType;

    @Enumerated(EnumType.STRING)
    @Column(name = "origin", nullable = false, length = 16)
    private Origin origin;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public UUID getDiagramId() { return diagramId; }
    public void setDiagramId(UUID diagramId) { this.diagramId = diagramId; }
    public long getSeq() { return seq; }
    public void setSeq(long seq) { this.seq = seq; }
    public UUID getOpId() { return opId; }
    public void setOpId(UUID opId) { this.opId = opId; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public CommandType getOpType() { return opType; }
    public void setOpType(CommandType opType) { this.opType = opType; }
    public Origin getOrigin() { return origin; }
    public void setOrigin(Origin origin) { this.origin = origin; }
    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
