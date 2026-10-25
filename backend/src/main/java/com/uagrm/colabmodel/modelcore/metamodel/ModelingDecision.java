package com.uagrm.colabmodel.modelcore.metamodel;

import com.uagrm.colabmodel.modelcore.mapping.Ambiguedad;
import jakarta.persistence.*;
import org.springframework.data.domain.Persistable;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * La respuesta del equipo a un punto de ambiguedad OO <-> Datos.
 *
 * Se persiste, y no se vuelve a preguntar, por una razon de fondo: las dos
 * lecturas son correctas, asi que la eleccion es una decision de DISENO del
 * equipo. Si la herramienta volviera a preguntar en cada generacion, el
 * esquema podria cambiar entre dos entregas sin que nadie tocara el modelo.
 *
 * La clave real es (diagram_id, element_id, ambiguity_kind): hay un indice
 * unico sobre esas tres columnas, asi que un elemento tiene a lo sumo una
 * decision por tipo de ambiguedad.
 */
@Entity
@Table(name = "modeling_decisions")
public class ModelingDecision implements Persistable<UUID> {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Transient
    private boolean nuevaEntidad = true;

    @PostLoad
    @PostPersist
    void marcarComoExistente() { this.nuevaEntidad = false; }

    @Override
    public boolean isNew() { return nuevaEntidad; }

    @Column(name = "diagram_id", nullable = false)
    private UUID diagramId;

    /** La relacion o el atributo que produce la ambiguedad. */
    @Column(name = "element_id", nullable = false)
    private UUID elementId;

    @Enumerated(EnumType.STRING)
    @Column(name = "ambiguity_kind", nullable = false, length = 24)
    private Ambiguedad.TipoAmbiguedad ambiguityKind;

    @Enumerated(EnumType.STRING)
    @Column(name = "chosen", nullable = false, length = 8)
    private Ambiguedad.Lectura chosen;

    /** Quien decidio. Queda para la defensa: las decisiones tienen autor. */
    @Column(name = "decided_by")
    private UUID decidedBy;

    @Column(name = "decided_at", nullable = false)
    private OffsetDateTime decidedAt = OffsetDateTime.now();

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getDiagramId() { return diagramId; }
    public void setDiagramId(UUID diagramId) { this.diagramId = diagramId; }
    public UUID getElementId() { return elementId; }
    public void setElementId(UUID elementId) { this.elementId = elementId; }
    public Ambiguedad.TipoAmbiguedad getAmbiguityKind() { return ambiguityKind; }
    public void setAmbiguityKind(Ambiguedad.TipoAmbiguedad ambiguityKind) { this.ambiguityKind = ambiguityKind; }
    public Ambiguedad.Lectura getChosen() { return chosen; }
    public void setChosen(Ambiguedad.Lectura chosen) { this.chosen = chosen; }
    public UUID getDecidedBy() { return decidedBy; }
    public void setDecidedBy(UUID decidedBy) { this.decidedBy = decidedBy; }
    public OffsetDateTime getDecidedAt() { return decidedAt; }
    public void setDecidedAt(OffsetDateTime decidedAt) { this.decidedAt = decidedAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ModelingDecision other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() { return ModelingDecision.class.hashCode(); }
}
