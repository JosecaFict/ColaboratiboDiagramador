package com.uagrm.colabmodel.modelcore.mapping;

import com.uagrm.colabmodel.modelcore.metamodel.UmlClass;

/**
 * Clases de VALOR (Regla 5): no tienen identidad propia, viven dentro de otra.
 *
 * Una `Direccion` no existe sola: es parte de un `Cliente`. No genera tabla;
 * sus atributos se guardan como columnas de la tabla del duenio, prefijadas
 * (`direccion_calle`), y en Java es una clase `@Embeddable` que el duenio
 * lleva como `@Embedded`.
 *
 * Se llega por dos caminos, como pide el documento:
 *   - EXPLICITO: la clase tiene el estereotipo «embeddable» y otra la usa como
 *     tipo de un atributo, o la compone 1..1. Ya esta decidido: no se pregunta.
 *   - SUGERIDO: una composicion 1..1 sobre una clase comun. Es el punto de
 *     ambiguedad A-2, y solo se aplana si el equipo eligio la lectura OO.
 */
public final class Incrustables {

    public static final String ESTEREOTIPO = "embeddable";

    private Incrustables() { }

    public static boolean es(UmlClass c) {
        return c != null && ESTEREOTIPO.equalsIgnoreCase(c.getStereotype());
    }
}
