package com.uagrm.colabmodel.ai;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Una fila de `ai_settings` (V9): la configuracion de la IA de un proyecto. */
@Entity
@Table(name = "ai_settings")
class ConfiguracionIAFila {

    @Id
    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Enumerated(EnumType.STRING)
    @Column(name = "rol", nullable = false, length = 10)
    private ConfiguracionDeIA.Rol rol;

    @Enumerated(EnumType.STRING)
    @Column(name = "detalle", nullable = false, length = 10)
    private ConfiguracionDeIA.Detalle detalle;

    @Column(name = "instrucciones", nullable = false, columnDefinition = "text")
    private String instrucciones;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    @Column(name = "updated_by")
    private UUID updatedBy;

    UUID getProjectId() { return projectId; }
    void setProjectId(UUID projectId) { this.projectId = projectId; }
    void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
    void setUpdatedBy(UUID updatedBy) { this.updatedBy = updatedBy; }

    ConfiguracionDeIA comoConfiguracion() {
        return new ConfiguracionDeIA(rol, detalle, instrucciones);
    }

    void tomar(ConfiguracionDeIA c) {
        this.rol = c.rol();
        this.detalle = c.detalle();
        this.instrucciones = c.instrucciones();
    }
}
