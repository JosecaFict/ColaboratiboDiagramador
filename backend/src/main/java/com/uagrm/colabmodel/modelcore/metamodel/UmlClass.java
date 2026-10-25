package com.uagrm.colabmodel.modelcore.metamodel;

import jakarta.persistence.*;
import org.springframework.data.domain.Persistable;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Entidad del metamodelo canonico.
 *
 * NOTA DE DISENO: usamos columnas UUID planas en lugar de @ManyToOne.
 * El metamodelo se manipula como un flujo de comandos, no como un grafo de
 * objetos que se navega. Las asociaciones JPA completas (LAZY, cascade,
 * mappedBy) se demuestran en el BACKEND GENERADO (P2), que es el artefacto
 * donde importan.
 *
 * `deletedAt` es un tombstone: evita que un elemento borrado "resucite"
 * cuando llega una operacion concurrente que lo referencia.
 */
@Entity
@Table(name = "uml_class")
public class UmlClass implements Persistable<UUID> {

    // Sin @GeneratedValue: el id lo asigna el CLIENTE (idempotencia del
    // comando, ADR-002). Combinar @GeneratedValue con un id ya asignado antes
    // de persist() produce "detached entity passed to persist" en Hibernate 6.
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    // Ver comentario de arriba: sin este flag, save() con un id ya asignado
    // se interpreta como UPDATE de una fila inexistente.
    @Transient
    private boolean nuevaEntidad = true;

    @PostLoad
    @PostPersist
    void marcarComoExistente() { this.nuevaEntidad = false; }

    @Override
    public boolean isNew() { return nuevaEntidad; }

    @Column(name = "diagram_id", nullable = false)
    private UUID diagramId;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "stereotype", length = 40)
    private String stereotype;

    @Column(name = "is_abstract", nullable = false)
    private boolean isAbstract = false;

    // --- Anotaciones ER (vista Chen) ---
    @Column(name = "is_weak", nullable = false)
    private boolean weak = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "er_kind", nullable = false, length = 12)
    private ErKind erKind = ErKind.STRONG;

    /** Solo cuenta en la raiz de una jerarquia (Regla 4). */
    @Enumerated(EnumType.STRING)
    @Column(name = "inheritance_strategy", nullable = false, length = 12)
    private InheritanceStrategy inheritanceStrategy = InheritanceStrategy.JOINED;

    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getDiagramId() { return diagramId; }
    public void setDiagramId(UUID diagramId) { this.diagramId = diagramId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getStereotype() { return stereotype; }
    public void setStereotype(String stereotype) { this.stereotype = stereotype; }

    public boolean isAbstract() { return isAbstract; }
    public void setAbstract(boolean anAbstract) { this.isAbstract = anAbstract; }

    public boolean isWeak() { return weak; }
    public void setWeak(boolean weak) { this.weak = weak; }

    public ErKind getErKind() { return erKind; }
    public void setErKind(ErKind erKind) { this.erKind = erKind; }

    public InheritanceStrategy getInheritanceStrategy() { return inheritanceStrategy; }
    public void setInheritanceStrategy(InheritanceStrategy s) { this.inheritanceStrategy = s; }

    public OffsetDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(OffsetDateTime deletedAt) { this.deletedAt = deletedAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof UmlClass other)) return false;
        return id != null && id.equals(other.id);   // Regla 3: equals SOLO por id
    }

    @Override
    public int hashCode() { return UmlClass.class.hashCode(); }
}
