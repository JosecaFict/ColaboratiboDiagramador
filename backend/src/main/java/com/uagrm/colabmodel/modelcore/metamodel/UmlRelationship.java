package com.uagrm.colabmodel.modelcore.metamodel;

import jakarta.persistence.*;
import org.springframework.data.domain.Persistable;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Relacion del metamodelo.
 *
 * `sourceRole` y `targetRole` NO son decorativos: de ahi sale el nombre de la
 * columna FK en el codigo generado (Regla 3). Sin ellos, dos relaciones entre
 * las mismas dos clases (Vuelo -> origen/destino : Aeropuerto) producen una
 * columna duplicada y el backend generado no compila.
 *
 * `degree > 2` = relacion n-aria. Regla 10: se detecta y se RECHAZA con un
 * mensaje que sugiere reificarla, nunca se ignora en silencio.
 */
@Entity
@Table(name = "uml_relationship")
public class UmlRelationship implements Persistable<UUID> {

    // Sin @GeneratedValue: el id lo asigna el CLIENTE. Ver UmlClass.
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

    @Column(name = "source_class_id", nullable = false)
    private UUID sourceClassId;

    @Column(name = "target_class_id", nullable = false)
    private UUID targetClassId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 16)
    private RelationKind kind = RelationKind.ASSOCIATION;

    @Column(name = "name", length = 120)
    private String name;

    @Column(name = "source_role", length = 120)
    private String sourceRole;

    @Column(name = "target_role", length = 120)
    private String targetRole;

    @Column(name = "source_multiplicity", length = 12)
    private String sourceMultiplicity;

    @Column(name = "target_multiplicity", length = 12)
    private String targetMultiplicity;

    // --- Anotaciones ER ---
    @Column(name = "is_identifying", nullable = false)
    private boolean identifying = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_participation", length = 8)
    private Participation sourceParticipation;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_participation", length = 8)
    private Participation targetParticipation;

    @Column(name = "degree", nullable = false)
    private int degree = 2;

    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getDiagramId() { return diagramId; }
    public void setDiagramId(UUID diagramId) { this.diagramId = diagramId; }
    public UUID getSourceClassId() { return sourceClassId; }
    public void setSourceClassId(UUID sourceClassId) { this.sourceClassId = sourceClassId; }
    public UUID getTargetClassId() { return targetClassId; }
    public void setTargetClassId(UUID targetClassId) { this.targetClassId = targetClassId; }
    public RelationKind getKind() { return kind; }
    public void setKind(RelationKind kind) { this.kind = kind; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getSourceRole() { return sourceRole; }
    public void setSourceRole(String sourceRole) { this.sourceRole = sourceRole; }
    public String getTargetRole() { return targetRole; }
    public void setTargetRole(String targetRole) { this.targetRole = targetRole; }
    public String getSourceMultiplicity() { return sourceMultiplicity; }
    public void setSourceMultiplicity(String m) { this.sourceMultiplicity = m; }
    public String getTargetMultiplicity() { return targetMultiplicity; }
    public void setTargetMultiplicity(String m) { this.targetMultiplicity = m; }
    public boolean isIdentifying() { return identifying; }
    public void setIdentifying(boolean identifying) { this.identifying = identifying; }
    public Participation getSourceParticipation() { return sourceParticipation; }
    public void setSourceParticipation(Participation p) { this.sourceParticipation = p; }
    public Participation getTargetParticipation() { return targetParticipation; }
    public void setTargetParticipation(Participation p) { this.targetParticipation = p; }
    public int getDegree() { return degree; }
    public void setDegree(int degree) { this.degree = degree; }
    public OffsetDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(OffsetDateTime deletedAt) { this.deletedAt = deletedAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof UmlRelationship other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() { return UmlRelationship.class.hashCode(); }
}
