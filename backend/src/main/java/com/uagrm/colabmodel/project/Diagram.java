package com.uagrm.colabmodel.project;

import com.uagrm.colabmodel.modelcore.metamodel.NotationView;
import jakarta.persistence.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Un diagrama es UN modelo canonico con N proyecciones (ADR-003).
 * `defaultView` es solo la pestana con la que abre, no un tipo de diagrama.
 *
 * `currentSeq` es el contador del secuenciador (ADR-005).
 */
@Entity
@Table(name = "diagrams")
public class Diagram {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "name", nullable = false, length = 160)
    private String name;

    @Column(name = "current_seq", nullable = false)
    private long currentSeq = 0L;

    @Enumerated(EnumType.STRING)
    @Column(name = "default_view", nullable = false, length = 10)
    private NotationView defaultView = NotationView.CLASS;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getProjectId() { return projectId; }
    public void setProjectId(UUID projectId) { this.projectId = projectId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public long getCurrentSeq() { return currentSeq; }
    public void setCurrentSeq(long currentSeq) { this.currentSeq = currentSeq; }
    public NotationView getDefaultView() { return defaultView; }
    public void setDefaultView(NotationView defaultView) { this.defaultView = defaultView; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
