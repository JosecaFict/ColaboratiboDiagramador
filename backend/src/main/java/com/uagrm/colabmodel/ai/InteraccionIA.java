package com.uagrm.colabmodel.ai;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "ai_interactions")
class InteraccionIA {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "diagram_id", nullable = false)
    private UUID diagramId;

    @Column(name = "user_id")
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "mode", nullable = false, length = 12)
    private ModoIA mode;

    @Column(name = "model", length = 160)
    private String model;

    @Column(name = "prompt", nullable = false, columnDefinition = "text")
    private String prompt;

    @Column(name = "response", columnDefinition = "text")
    private String response;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "proposal", nullable = false, columnDefinition = "jsonb")
    private String proposal;

    @Column(name = "applied_at")
    private OffsetDateTime appliedAt;

    @Column(name = "applied_by")
    private UUID appliedBy;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getDiagramId() { return diagramId; }
    public void setDiagramId(UUID diagramId) { this.diagramId = diagramId; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public ModoIA getMode() { return mode; }
    public void setMode(ModoIA mode) { this.mode = mode; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; }
    public String getResponse() { return response; }
    public void setResponse(String response) { this.response = response; }
    public String getProposal() { return proposal; }
    public void setProposal(String proposal) { this.proposal = proposal; }
    public OffsetDateTime getAppliedAt() { return appliedAt; }
    public void setAppliedAt(OffsetDateTime appliedAt) { this.appliedAt = appliedAt; }
    public UUID getAppliedBy() { return appliedBy; }
    public void setAppliedBy(UUID appliedBy) { this.appliedBy = appliedBy; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}

