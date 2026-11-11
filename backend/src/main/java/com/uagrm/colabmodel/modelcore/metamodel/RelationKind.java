package com.uagrm.colabmodel.modelcore.metamodel;

public enum RelationKind {
    ASSOCIATION, AGGREGATION, COMPOSITION, GENERALIZATION, DEPENDENCY,
    /** UML 2.5.1 InterfaceRealization: una clase cumple el contrato de una interfaz. Va de la clase a la interfaz. */
    REALIZATION
}
