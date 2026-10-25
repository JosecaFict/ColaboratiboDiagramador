package com.uagrm.colabmodel.modelcore.metamodel;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

import java.util.UUID;

/**
 * Un metodo del modelo conceptual.
 *
 * REGLA 6: los metodos NO se descartan. No van a la Entity ni al DDL --una
 * tabla guarda estado, no comportamiento-- pero si al Service generado, como
 * stub con la firma del UML. Descartarlos seria perder la mitad del modelo
 * orientado a objetos en la traduccion.
 */
@Entity
@Table(name = "uml_method")
public class UmlMethod implements Persistable<UUID> {

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

    @Column(name = "return_type", nullable = false, length = 60)
    private String returnType = "void";

    /**
     * Parametros como JSON: [{"name":"isbn","type":"string"}, ...].
     *
     * Van en una columna y no en su propia tabla porque no se consultan ni se
     * referencian por separado: un parametro solo existe dentro de su firma.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "params", columnDefinition = "jsonb")
    private String params = "[]";

    @Enumerated(EnumType.STRING)
    @Column(name = "visibility", nullable = false, length = 10)
    private Visibility visibility = Visibility.PUBLIC;

    @Column(name = "ordinal", nullable = false)
    private int ordinal = 0;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getClassId() { return classId; }
    public void setClassId(UUID classId) { this.classId = classId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getReturnType() { return returnType; }
    public void setReturnType(String returnType) { this.returnType = returnType; }
    public String getParams() { return params; }
    public void setParams(String params) { this.params = params; }
    public Visibility getVisibility() { return visibility; }
    public void setVisibility(Visibility visibility) { this.visibility = visibility; }
    public int getOrdinal() { return ordinal; }
    public void setOrdinal(int ordinal) { this.ordinal = ordinal; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof UmlMethod other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() { return UmlMethod.class.hashCode(); }
}
