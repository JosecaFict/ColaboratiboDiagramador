package com.uagrm.colabmodel.modelcore.metamodel;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * Geometria de entidades y rombos (ADR-006).
 *
 * Solo clases y relaciones tienen fila aqui. Los atributos NO: su posicion se
 * calcula en el cliente con layout radial y nunca se persiste.
 */
@Entity
@Table(name = "diagram_layout")
@IdClass(DiagramLayout.Pk.class)
public class DiagramLayout implements Persistable<DiagramLayout.Pk> {

    @Id
    @Column(name = "diagram_id", nullable = false)
    private UUID diagramId;

    @Id
    @Column(name = "element_id", nullable = false)
    private UUID elementId;

    // Clave compuesta asignada a mano (diagramId + elementId ya existen antes
    // de guardar): sin este flag, save() la trata como UPDATE de una fila
    // inexistente. Ver UmlClass para el detalle.
    @Transient
    private boolean nuevaEntidad = true;

    @PostLoad
    @PostPersist
    void marcarComoExistente() { this.nuevaEntidad = false; }

    @Override
    public Pk getId() { return new Pk(diagramId, elementId); }

    @Override
    public boolean isNew() { return nuevaEntidad; }

    @Column(name = "element_kind", nullable = false, length = 10)
    private String elementKind = "CLASS";

    @Column(nullable = false)
    private double x;

    @Column(nullable = false)
    private double y;

    @Column(nullable = false)
    private double width = 170;

    @Column(nullable = false)
    private double height = 60;

    @Column(name = "z_index", nullable = false)
    private int zIndex = 0;

    /**
     * Quiebres de una linea de relacion, como JSON: [{"x":1,"y":2},...].
     * Null = la linea va recta. Solo aplica a element_kind = RELATION.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "waypoints", columnDefinition = "jsonb")
    private String waypoints;

    public UUID getDiagramId() { return diagramId; }
    public void setDiagramId(UUID diagramId) { this.diagramId = diagramId; }
    public UUID getElementId() { return elementId; }
    public void setElementId(UUID elementId) { this.elementId = elementId; }
    public String getElementKind() { return elementKind; }
    public void setElementKind(String elementKind) { this.elementKind = elementKind; }
    public double getX() { return x; }
    public void setX(double x) { this.x = x; }
    public double getY() { return y; }
    public void setY(double y) { this.y = y; }
    public double getWidth() { return width; }
    public void setWidth(double width) { this.width = width; }
    public double getHeight() { return height; }
    public void setHeight(double height) { this.height = height; }
    public int getZIndex() { return zIndex; }
    public void setZIndex(int zIndex) { this.zIndex = zIndex; }
    public String getWaypoints() { return waypoints; }
    public void setWaypoints(String waypoints) { this.waypoints = waypoints; }

    public static class Pk implements Serializable {
        private UUID diagramId;
        private UUID elementId;

        public Pk() { }
        public Pk(UUID diagramId, UUID elementId) {
            this.diagramId = diagramId;
            this.elementId = elementId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Pk other)) return false;
            return Objects.equals(diagramId, other.diagramId) && Objects.equals(elementId, other.elementId);
        }

        @Override
        public int hashCode() { return Objects.hash(diagramId, elementId); }
    }
}
