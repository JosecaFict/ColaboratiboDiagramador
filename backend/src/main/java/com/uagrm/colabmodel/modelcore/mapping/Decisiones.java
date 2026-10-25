package com.uagrm.colabmodel.modelcore.mapping;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static com.uagrm.colabmodel.modelcore.mapping.Ambiguedad.Lectura;
import static com.uagrm.colabmodel.modelcore.mapping.Ambiguedad.TipoAmbiguedad;

/**
 * Lo que el equipo ya contesto sobre los puntos de ambiguedad.
 *
 * Se pasa a la derivacion como un valor, no como una consulta a la base: eso
 * mantiene a `DerivadorLogico` puro y verificable contra los 7 modelos
 * canonicos. Un mapa vacio significa "nadie decidio todavia", y entonces cada
 * ambiguedad usa su `porDefecto`, que es el comportamiento historico.
 */
public final class Decisiones {

    private static final Decisiones VACIAS = new Decisiones(Map.of());

    private final Map<Clave, Lectura> elegidas;

    private Decisiones(Map<Clave, Lectura> elegidas) {
        this.elegidas = elegidas;
    }

    /** Nadie decidio nada: manda el valor por defecto de cada regla. */
    public static Decisiones ninguna() { return VACIAS; }

    public static Builder constructor() { return new Builder(); }

    /** La lectura elegida para ese elemento, o `porDefecto` si nadie contesto. */
    public Lectura para(TipoAmbiguedad tipo, UUID elementId, Lectura porDefecto) {
        return elegidas.getOrDefault(new Clave(tipo, elementId), porDefecto);
    }

    public boolean esOO(TipoAmbiguedad tipo, UUID elementId, Lectura porDefecto) {
        return para(tipo, elementId, porDefecto) == Lectura.OO;
    }

    public boolean vacio() { return elegidas.isEmpty(); }

    private record Clave(TipoAmbiguedad tipo, UUID elementId) { }

    public static final class Builder {
        private final Map<Clave, Lectura> mapa = new HashMap<>();

        public Builder con(TipoAmbiguedad tipo, UUID elementId, Lectura lectura) {
            mapa.put(new Clave(tipo, elementId), lectura);
            return this;
        }

        public Decisiones construir() { return new Decisiones(Map.copyOf(mapa)); }
    }
}
