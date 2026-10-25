package com.uagrm.colabmodel.modelcore.mapping;

import java.util.UUID;

/**
 * Un punto donde el modelo OO y el modelo de datos NO dicen lo mismo.
 *
 * Es el *impedance mismatch* hecho explicito (docs/REGLAS-MAPEO.md). Las dos
 * lecturas son correctas; lo que no existe es una respuesta universal. Por eso
 * la herramienta **pregunta una vez y persiste la decision**, en lugar de
 * elegir en silencio y dejar al usuario descubriendolo en el codigo generado.
 *
 * @param elementId  la relacion o el atributo que la produce
 * @param porDefecto lo que la herramienta hace si nadie decide: es SIEMPRE el
 *                   comportamiento actual, para que agregar esta pantalla no
 *                   cambie por si sola ningun esquema ya derivado
 */
public record Ambiguedad(
        UUID elementId,
        TipoAmbiguedad tipo,
        /** Como nombrarla en pantalla: "Factura ◆— DetalleFactura". */
        String titulo,
        String lecturaOO,
        String lecturaDatos,
        Lectura porDefecto
) {
    /** Las cuatro del documento. Los nombres son los del CHECK de `modeling_decisions`. */
    public enum TipoAmbiguedad { COMPOSITION, EMBEDDABLE, MULTIVALUED, ASSOC_CLASS }

    /** Los dos valores del CHECK `chosen`. */
    public enum Lectura { OO, DATA }
}
