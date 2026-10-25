package com.uagrm.colabmodel.project;

import jakarta.persistence.*;
import org.springframework.data.domain.Persistable;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * La base que el equipo confirmo antes de generar el backend (ADR-032).
 *
 * Una por diagrama: confirmar otra vez la reemplaza. Lo que se confirma es el
 * DDL, identificado por su hash, porque es lo unico que la base del backend
 * generado va a recibir: mover una caja o agregar un metodo no la invalida,
 * cambiar una columna si.
 */
@Entity
@Table(name = "schema_confirmations")
public class ConfirmacionDeEsquema implements Persistable<UUID> {

    @Id
    @Column(name = "diagram_id", nullable = false, updatable = false)
    private UUID diagramId;

    @Transient
    private boolean nuevaEntidad = true;

    @PostLoad
    @PostPersist
    void marcarComoExistente() { this.nuevaEntidad = false; }

    @Override
    public UUID getId() { return diagramId; }

    @Override
    public boolean isNew() { return nuevaEntidad; }

    @Column(name = "ddl_hash", nullable = false, length = 64)
    private String ddlHash;

    /** El texto confirmado: sin el, "cambio desde la confirmacion" no puede decir QUE cambio. */
    @Column(name = "ddl", nullable = false, columnDefinition = "text")
    private String ddl;

    /** El seq de la operacion CONFIRM_SCHEMA que la produjo. */
    @Column(name = "seq", nullable = false)
    private long seq;

    @Column(name = "confirmed_by")
    private UUID confirmedBy;

    @Column(name = "confirmed_at", nullable = false)
    private OffsetDateTime confirmedAt = OffsetDateTime.now();

    public UUID getDiagramId() { return diagramId; }
    public void setDiagramId(UUID diagramId) { this.diagramId = diagramId; }
    public String getDdlHash() { return ddlHash; }
    public void setDdlHash(String ddlHash) { this.ddlHash = ddlHash; }
    public String getDdl() { return ddl; }
    public void setDdl(String ddl) { this.ddl = ddl; }
    public long getSeq() { return seq; }
    public void setSeq(long seq) { this.seq = seq; }
    public UUID getConfirmedBy() { return confirmedBy; }
    public void setConfirmedBy(UUID confirmedBy) { this.confirmedBy = confirmedBy; }
    public OffsetDateTime getConfirmedAt() { return confirmedAt; }
    public void setConfirmedAt(OffsetDateTime confirmedAt) { this.confirmedAt = confirmedAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ConfirmacionDeEsquema other)) return false;
        return diagramId != null && diagramId.equals(other.diagramId);
    }

    @Override
    public int hashCode() { return ConfirmacionDeEsquema.class.hashCode(); }
}
