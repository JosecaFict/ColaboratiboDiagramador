package com.uagrm.colabmodel.project;

import jakarta.persistence.*;
import org.springframework.data.domain.Persistable;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * Rol de un usuario dentro de un proyecto (EDITOR | VIEWER).
 *
 * El OWNER de un proyecto vive en `projects.owner_id` y NUNCA tiene fila aqui:
 * esta tabla es para los miembros INVITADOS, y desde V6 el CHECK de la base
 * lo impide. El rol efectivo lo decide `AccesoAProyecto`, no esta clase.
 */
@Entity
@Table(name = "project_members")
@IdClass(ProjectMember.Pk.class)
public class ProjectMember implements Persistable<ProjectMember.Pk> {

    @Id
    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Id
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "role", nullable = false, length = 10)
    private String role;

    @Column(name = "added_at", nullable = false)
    private OffsetDateTime addedAt = OffsetDateTime.now();

    @Transient
    private boolean nuevaEntidad = true;

    @PostLoad
    @PostPersist
    void marcarComoExistente() { this.nuevaEntidad = false; }

    @Override
    public Pk getId() { return new Pk(projectId, userId); }

    @Override
    public boolean isNew() { return nuevaEntidad; }

    public UUID getProjectId() { return projectId; }
    public void setProjectId(UUID projectId) { this.projectId = projectId; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public OffsetDateTime getAddedAt() { return addedAt; }
    public void setAddedAt(OffsetDateTime addedAt) { this.addedAt = addedAt; }

    public static class Pk implements Serializable {
        private UUID projectId;
        private UUID userId;

        public Pk() { }
        public Pk(UUID projectId, UUID userId) {
            this.projectId = projectId;
            this.userId = userId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Pk other)) return false;
            return Objects.equals(projectId, other.projectId) && Objects.equals(userId, other.userId);
        }

        @Override
        public int hashCode() { return Objects.hash(projectId, userId); }
    }
}
