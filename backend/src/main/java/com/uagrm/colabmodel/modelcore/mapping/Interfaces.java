package com.uagrm.colabmodel.modelcore.mapping;

import com.uagrm.colabmodel.modelcore.metamodel.UmlClass;

/**
 * Una interfaz UML se guarda como estereotipo, igual que la enumeracion
 * ({@link Enumeraciones}): sin columna nueva y con el mismo recorrido por todas
 * las capas (D-15, opcion A).
 *
 * Una interfaz es un contrato, no una entidad: no genera tabla. Las clases que
 * la cumplen se unen a ella con una relacion REALIZATION.
 */
public final class Interfaces {

    public static final String ESTEREOTIPO = "interface";

    private Interfaces() { }

    public static boolean es(UmlClass c) {
        return c != null && esEstereotipo(c.getStereotype());
    }

    /** El texto del estereotipo es libre: se compara sin distinguir mayusculas ni espacios. */
    public static boolean esEstereotipo(String estereotipo) {
        return estereotipo != null && ESTEREOTIPO.equalsIgnoreCase(estereotipo.trim());
    }
}
