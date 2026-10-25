package com.uagrm.colabmodel.modelcore.metamodel;

import jakarta.persistence.*;
import org.springframework.data.domain.Persistable;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "uml_attribute")
public class UmlAttribute implements Persistable<UUID> {

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

    @Column(name = "class_id", nullable = false)
    private UUID classId;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "data_type", nullable = false, length = 40)
    private String dataType = "string";

    /**
     * Visibilidad UML de la Property (UML 2.5.1, seccion 9.5.4).
     *
     * PRIVATE por defecto, que es la convencion habitual para atributos y lo
     * que el canvas ya venia dibujando. Es NOTACION: no llega al DDL ni cambia
     * el codigo generado --la base guarda estado, y el de un atributo privado
     * es el mismo que el de uno publico--. Se guarda para que ir y volver de
     * Chen no la pierda (ADR-003).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "visibility", nullable = false, length = 10)
    private Visibility visibility = Visibility.PRIVATE;

    @Column(name = "length")
    private Integer length;

    @Column(name = "is_pk", nullable = false)
    private boolean pk = false;

    @Column(name = "is_required", nullable = false)
    private boolean required = false;

    @Column(name = "is_unique", nullable = false)
    private boolean unique = false;

    @Column(name = "default_value", length = 255)
    private String defaultValue;

    @Column(name = "ordinal", nullable = false)
    private int ordinal = 0;

    // --- Anotaciones ER ---
    /** Doble ovalo en Chen. Regla 12: se extrae a tabla propia (1FN). */
    @Column(name = "is_multivalued", nullable = false)
    private boolean multivalued = false;

    /** Ovalo punteado en Chen, /atributo en UML. No se persiste en el DDL generado. */
    @Column(name = "is_derived", nullable = false)
    private boolean derived = false;

    /** Discriminador de entidad debil. Forma parte de la PK compuesta (Regla 11). */
    @Column(name = "is_partial_key", nullable = false)
    private boolean partialKey = false;

    /** Atributo compuesto: apunta al ovalo padre. */
    @Column(name = "parent_attribute_id")
    private UUID parentAttributeId;

    /** Regla 9: rango de un atributo numerico. Null = sin limite de ese lado. */
    @Column(name = "min_value", precision = 19, scale = 4)
    private BigDecimal minValue;

    @Column(name = "max_value", precision = 19, scale = 4)
    private BigDecimal maxValue;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getClassId() { return classId; }
    public void setClassId(UUID classId) { this.classId = classId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDataType() { return dataType; }
    public void setDataType(String dataType) { this.dataType = dataType; }
    public Integer getLength() { return length; }
    public void setLength(Integer length) { this.length = length; }
    public Visibility getVisibility() { return visibility; }
    public void setVisibility(Visibility visibility) { this.visibility = visibility; }
    public boolean isPk() { return pk; }
    public void setPk(boolean pk) { this.pk = pk; }
    public boolean isRequired() { return required; }
    public void setRequired(boolean required) { this.required = required; }
    public boolean isUnique() { return unique; }
    public void setUnique(boolean unique) { this.unique = unique; }
    public String getDefaultValue() { return defaultValue; }
    public void setDefaultValue(String defaultValue) { this.defaultValue = defaultValue; }
    public int getOrdinal() { return ordinal; }
    public void setOrdinal(int ordinal) { this.ordinal = ordinal; }
    public boolean isMultivalued() { return multivalued; }
    public void setMultivalued(boolean multivalued) { this.multivalued = multivalued; }
    public boolean isDerived() { return derived; }
    public void setDerived(boolean derived) { this.derived = derived; }
    public boolean isPartialKey() { return partialKey; }
    public void setPartialKey(boolean partialKey) { this.partialKey = partialKey; }
    public UUID getParentAttributeId() { return parentAttributeId; }
    public void setParentAttributeId(UUID parentAttributeId) { this.parentAttributeId = parentAttributeId; }
    public BigDecimal getMinValue() { return minValue; }
    public void setMinValue(BigDecimal minValue) { this.minValue = minValue; }
    public BigDecimal getMaxValue() { return maxValue; }
    public void setMaxValue(BigDecimal maxValue) { this.maxValue = maxValue; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof UmlAttribute other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() { return UmlAttribute.class.hashCode(); }
}
