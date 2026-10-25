package com.uagrm.colabmodel.project;

import com.uagrm.colabmodel.modelcore.metamodel.ErKind;
import com.uagrm.colabmodel.modelcore.metamodel.InheritanceStrategy;
import com.uagrm.colabmodel.modelcore.metamodel.NotationView;
import com.uagrm.colabmodel.modelcore.metamodel.Participation;
import com.uagrm.colabmodel.modelcore.metamodel.RelationKind;
import com.uagrm.colabmodel.modelcore.metamodel.Visibility;

import java.util.List;
import java.util.UUID;

/**
 * Todo lo que un cliente necesita para dibujar el diagrama SIN re-aplicar el
 * historial completo de operaciones.
 *
 * Es un CONTRATO de lectura: su forma es la de los tipos de
 * `frontend/src/contracts/command.ts`. No se serializan las entidades JPA a
 * proposito: asi agregar una columna no cambia la API sin querer, y no se
 * filtran campos internos como `deleted_at`.
 *
 * ADR-008: la verdad son las tablas normalizadas. La tabla `diagram_snapshots`
 * (jsonb) es solo cache de lectura y todavia no hace falta: este endpoint lee
 * directo del metamodelo.
 */
public record DiagramSnapshot(
        UUID id,
        String name,
        long currentSeq,
        NotationView defaultView,
        List<Clase> classes,
        List<Relacion> relationships,
        List<Layout> layout
) {

    public record Clase(
            UUID id,
            UUID diagramId,
            String name,
            String stereotype,
            boolean isAbstract,
            boolean weak,
            ErKind erKind,
            InheritanceStrategy inheritanceStrategy,
            List<Atributo> attributes,
            /** Regla 6: viajan al cliente y al Service generado, nunca al DDL. */
            List<Metodo> methods
    ) {}

    public record Metodo(
            UUID id,
            UUID classId,
            String name,
            String returnType,
            List<Parametro> params,
            Visibility visibility,
            int ordinal
    ) {}

    public record Parametro(String name, String type) {}

    public record Atributo(
            UUID id,
            UUID classId,
            String name,
            String dataType,
            /** UML 2.5.1: una Property lleva visibilidad. Sin esto el canvas
             *  dibujaba "undefined" delante de cada atributo del snapshot. */
            Visibility visibility,
            Integer length,
            boolean pk,
            boolean required,
            boolean unique,
            int ordinal,
            boolean multivalued,
            boolean derived,
            boolean partialKey,
            UUID parentAttributeId,
            String defaultValue,
            java.math.BigDecimal minValue,
            java.math.BigDecimal maxValue
    ) {}

    public record Relacion(
            UUID id,
            UUID diagramId,
            UUID sourceClassId,
            UUID targetClassId,
            RelationKind kind,
            String name,
            String sourceRole,
            String targetRole,
            String sourceMultiplicity,
            String targetMultiplicity,
            boolean identifying,
            Participation sourceParticipation,
            Participation targetParticipation,
            int degree
    ) {}

    /** Solo entidades y relaciones. Los atributos se calculan en el cliente (ADR-006). */
    public record Layout(
            UUID elementId,
            double x,
            double y,
            double width,
            double height,
            /** Quiebres de la linea. Vacio = va recta. */
            List<Punto> waypoints
    ) {}

    public record Punto(double x, double y) {}
}
