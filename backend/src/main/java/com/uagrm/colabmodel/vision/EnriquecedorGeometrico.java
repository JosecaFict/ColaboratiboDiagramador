package com.uagrm.colabmodel.vision;

import com.uagrm.colabmodel.modelcore.metamodel.RelationKind;
import com.uagrm.colabmodel.vision.DeteccionPropuesta.RecorteDeSimbolo;
import com.uagrm.colabmodel.vision.DeteccionPropuesta.RelacionDetectada;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * La mitad del nivel 2 que NO necesita ningun modelo.
 *
 * Solo hace una cosa: clasificar la figura del extremo de cada linea --rombo
 * hueco, rombo relleno o triangulo-- midiendo su geometria. No lee texto: eso
 * no se resuelve sin un modelo.
 *
 * Esta SIEMPRE disponible, y por eso va primero en la cadena: en una maquina
 * sin modelo, que es el caso por defecto, es lo unico que distingue una
 * composicion de una agregacion. La diferencia no es cosmetica: decide si la FK
 * derivada sale con ON DELETE CASCADE o con SET NULL.
 *
 * Si despues corre el nivel 2 por modelo, respeta lo que este ya clasifico
 * --{@code EnriquecedorPorModelo} no vuelve a preguntar por un simbolo que ya
 * tiene figura--, asi que las dos capas se suman en vez de pisarse.
 */
@Component
@Order(10)
public class EnriquecedorGeometrico implements EnriquecedorDeDeteccion {

    /**
     * Mas baja que la del modelo a proposito: esto mide formas, no las
     * reconoce, y una foto con trazo grueso lo confunde. Igual se confirma en
     * el panel antes de escribir nada.
     */
    private static final double CONFIANZA = 0.6;

    @Override
    public String nombre() {
        return "geometrico (simbolos)";
    }

    /** Aritmetica sobre pixeles: no hay nada que pueda estar caido. */
    @Override
    public boolean disponible() {
        return true;
    }

    @Override
    public DeteccionPropuesta enriquecer(DeteccionPropuesta base, ImagenDeEntrada imagen) {
        if (base.relaciones().isEmpty()) return base;

        List<RelacionDetectada> relaciones = new ArrayList<>(base.relaciones().size());
        for (RelacionDetectada r : base.relaciones()) {
            RecorteDeSimbolo origen = clasificar(r.simboloOrigen(), imagen);
            RecorteDeSimbolo destino = clasificar(r.simboloDestino(), imagen);
            relaciones.add(new RelacionDetectada(r.ref(), r.origenRef(), r.destinoRef(),
                    kindDe(origen, destino, r.kind()), origen, destino,
                    r.cardinalidadOrigen(), r.cardinalidadDestino(), r.confianza()));
        }
        return new DeteccionPropuesta(base.clases(), List.copyOf(relaciones),
                base.avisos(), base.metricas());
    }

    private RecorteDeSimbolo clasificar(RecorteDeSimbolo recorte, ImagenDeEntrada imagen) {
        if (recorte == null || recorte.figura() != null) return recorte;
        RelationKind figura = ClasificadorDeSimbolo.de(imagen, recorte.caja());
        if (figura == null) return recorte;
        return new RecorteDeSimbolo(recorte.ref(), recorte.extremo(), recorte.caja(),
                figura, CONFIANZA);
    }

    /**
     * Una generalizacion lleva el triangulo en UN solo extremo, asi que alcanza
     * con reconocerlo de un lado. Si los dos dicen cosas distintas gana el
     * origen: elegir siempre el mismo es mejor que inventar un desempate.
     */
    private static RelationKind kindDe(RecorteDeSimbolo origen, RecorteDeSimbolo destino,
                                       RelationKind porDefecto) {
        if (origen != null && origen.figura() != null) return origen.figura();
        if (destino != null && destino.figura() != null) return destino.figura();
        return porDefecto;
    }
}
