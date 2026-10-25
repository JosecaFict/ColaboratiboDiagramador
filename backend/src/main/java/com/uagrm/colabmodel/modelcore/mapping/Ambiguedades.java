package com.uagrm.colabmodel.modelcore.mapping;

import com.uagrm.colabmodel.modelcore.metamodel.RelationKind;
import com.uagrm.colabmodel.modelcore.metamodel.UmlAttribute;
import com.uagrm.colabmodel.modelcore.metamodel.UmlClass;
import com.uagrm.colabmodel.modelcore.metamodel.UmlRelationship;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.uagrm.colabmodel.modelcore.mapping.Ambiguedad.Lectura;
import static com.uagrm.colabmodel.modelcore.mapping.Ambiguedad.TipoAmbiguedad;

/**
 * Los 4 puntos de ambiguedad OO <-> Datos, detectados sobre el modelo.
 *
 * Funcion PURA, igual que `DerivadorLogico`: entra el modelo, salen las
 * preguntas. Detectar aca y no en el generador tiene una razon concreta: el
 * usuario tiene que poder contestarlas ANTES de generar, no leyendo despues
 * un README que le explica lo que ya se decidio por el.
 */
public final class Ambiguedades {

    private Ambiguedades() { }

    public static List<Ambiguedad> detectar(List<UmlClass> clases,
                                            List<UmlAttribute> atributos,
                                            List<UmlRelationship> relaciones) {
        List<UmlClass> vivas = clases.stream().filter(c -> c.getDeletedAt() == null).toList();
        Map<UUID, UmlClass> porId = vivas.stream()
                .collect(Collectors.toMap(UmlClass::getId, Function.identity()));

        List<Ambiguedad> encontradas = new ArrayList<>();

        for (UmlRelationship r : relaciones) {
            if (r.getDeletedAt() != null) continue;
            if (r.getDegree() > 2) continue;
            if (r.getKind() == RelationKind.DEPENDENCY || r.getKind() == RelationKind.GENERALIZATION) continue;
            UmlClass origen = porId.get(r.getSourceClassId());
            UmlClass destino = porId.get(r.getTargetClassId());
            if (origen == null || destino == null) continue;
            if (Enumeraciones.es(origen) || Enumeraciones.es(destino)) continue;
            // Componer una clase «embeddable» ya esta decidido en el modelo.
            if (Incrustables.es(origen) || Incrustables.es(destino)) continue;

            boolean origenMuchos = esMuchos(r.getSourceMultiplicity());
            boolean destinoMuchos = esMuchos(r.getTargetMultiplicity());

            // A-4: Estudiante -- Curso con atributos propios.
            if (origenMuchos && destinoMuchos) {
                encontradas.add(new Ambiguedad(r.getId(), TipoAmbiguedad.ASSOC_CLASS,
                        origen.getName() + " N:M " + destino.getName(),
                        "Clase asociativa con id surrogado: la tabla puente pasa a ser una entidad"
                                + " propia y el generador le emite sus cinco capas.",
                        "Tabla puente con clave primaria compuesta (" + Nombres.tabla(origen.getName())
                                + " + " + Nombres.tabla(destino.getName()) + "): entra al DDL pero no"
                                + " genera Entity.",
                        Lectura.DATA));
                continue;
            }

            if (r.getKind() != RelationKind.COMPOSITION) continue;

            // A-2: Cliente ◆— Direccion, una sola y sin identidad propia.
            if (!origenMuchos && !destinoMuchos) {
                UmlClass parte = destino;
                UmlClass todo = origen;
                encontradas.add(new Ambiguedad(r.getId(), TipoAmbiguedad.EMBEDDABLE,
                        todo.getName() + " ◆— " + parte.getName() + " (1..1)",
                        "@Embeddable: los atributos de " + parte.getName() + " se aplanan dentro de la"
                                + " tabla " + Nombres.tabla(todo.getName()) + " y " + parte.getName()
                                + " deja de tener tabla propia.",
                        parte.getName() + " mantiene su tabla con clave foranea a "
                                + Nombres.tabla(todo.getName()) + ".",
                        Lectura.DATA));
                continue;
            }

            // A-1: Factura ◆— DetalleFactura, muchos del lado de la parte.
            UmlClass parte = destinoMuchos ? destino : origen;
            UmlClass todo = destinoMuchos ? origen : destino;
            encontradas.add(new Ambiguedad(r.getId(), TipoAmbiguedad.COMPOSITION,
                    todo.getName() + " ◆— " + parte.getName(),
                    parte.getName() + " es una entidad con id surrogado y clave foranea"
                            + " ON DELETE CASCADE.",
                    parte.getName() + " es una entidad DEBIL: su clave primaria es la de "
                            + todo.getName() + " mas su clave parcial. Sin clave parcial marcada,"
                            + " la tabla no tendria como distinguir dos filas.",
                    Lectura.OO));
        }

        for (UmlAttribute a : atributos) {
            if (!a.isMultivalued()) continue;
            UmlClass duenio = porId.get(a.getClassId());
            if (duenio == null || Enumeraciones.es(duenio)) continue;   // un literal no es un atributo
            if (Incrustables.es(duenio)) continue;   // se reporta al incrustar, no como A-3
            encontradas.add(new Ambiguedad(a.getId(), TipoAmbiguedad.MULTIVALUED,
                    duenio.getName() + "." + a.getName() + " [0..*]",
                    "@ElementCollection: la tabla sigue existiendo, pero cuelga de "
                            + duenio.getName() + " como coleccion de valores, sin entidad propia.",
                    "Tabla propia con clave primaria compuesta. Es 1FN, no una opcion (Regla 12).",
                    Lectura.DATA));
        }

        // Orden estable: sin esto la lista se reordena sola entre dos lecturas
        // del mismo modelo y la pantalla parpadea.
        encontradas.sort(Comparator.comparing((Ambiguedad a) -> a.tipo().name())
                .thenComparing(Ambiguedad::titulo));
        return List.copyOf(encontradas);
    }

    private static boolean esMuchos(String multiplicidad) {
        return multiplicidad != null && multiplicidad.contains("*");
    }
}
