package com.uagrm.colabmodel.versiones;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * La identidad de un elemento a traves de las ramas (ADR-037).
 *
 * Los ids de clases, atributos, metodos y relaciones son PK globales: una rama
 * (otro diagrama) no puede repetirlos. Lo que se conserva es la identidad
 * LOGICA:
 *
 * <pre>
 *   fisico = logico XOR sal(diagrama)        logico = fisico XOR sal(diagrama)
 * </pre>
 *
 * Un diagrama raiz no tiene sal: ahi fisico = logico. El XOR es su propio
 * inverso, asi que no hace falta una tabla de mapeo, la traduccion es atomica
 * con cualquier escritura, y una rama de una rama vuelve sola al espacio de ids
 * de la raiz.
 *
 * La sal tiene en CERO el nibble de version (bits 48-51) y los dos bits de
 * variante (62-63 del lado menos significativo): el XOR deja intactos los bits
 * v4 del id logico, y un id fisico de rama sigue siendo un UUID v4 valido.
 *
 * Es la UNICA clase que traduce ids: todo lo demas trabaja con lo que devuelve.
 */
public final class IdentidadLogica {

    /** Version: nibble alto del tercer grupo (0x000000000000F000 de la mitad alta). */
    private static final long MASCARA_VERSION = 0x000000000000F000L;
    /** Variante: los dos bits altos de la mitad baja. */
    private static final long MASCARA_VARIANTE = 0xC000000000000000L;

    private static final SecureRandom AZAR = new SecureRandom();

    private IdentidadLogica() { }

    /** Una sal nueva para una rama: aleatoria, con los bits de version y variante en cero. */
    public static UUID nuevaSal() {
        UUID sal;
        do {
            sal = new UUID(AZAR.nextLong() & ~MASCARA_VERSION, AZAR.nextLong() & ~MASCARA_VARIANTE);
        } while (sal.getMostSignificantBits() == 0 && sal.getLeastSignificantBits() == 0);
        return sal;
    }

    /** Si una sal respeta la regla: no toca version ni variante. */
    public static boolean esSalValida(UUID sal) {
        return sal == null
                || ((sal.getMostSignificantBits() & MASCARA_VERSION) == 0
                && (sal.getLeastSignificantBits() & MASCARA_VARIANTE) == 0);
    }

    /** XOR con la sal. null = diagrama raiz, el id no cambia. */
    static UUID xor(UUID id, UUID sal) {
        if (id == null || sal == null) return id;
        return new UUID(id.getMostSignificantBits() ^ sal.getMostSignificantBits(),
                id.getLeastSignificantBits() ^ sal.getLeastSignificantBits());
    }

    public static UUID aLogico(UUID fisico, UUID salDelDiagrama) {
        return xor(fisico, salDelDiagrama);
    }

    public static UUID aFisico(UUID logico, UUID salDelDiagrama) {
        return xor(logico, salDelDiagrama);
    }

    /**
     * El id que tiene en el diagrama destino un elemento que se conoce por su
     * id en el diagrama origen. Pasa por el id logico sin exponerlo.
     */
    public static UUID traducir(UUID fisicoEnOrigen, UUID salOrigen, UUID salDestino) {
        return aFisico(aLogico(fisicoEnOrigen, salOrigen), salDestino);
    }

    /** Toda una foto pasada de los ids de un diagrama a los de otro. */
    static Foto traducir(Foto foto, UUID salOrigen, UUID salDestino) {
        if (java.util.Objects.equals(salOrigen, salDestino)) return foto;
        return foto.conIds(id -> traducir(id, salOrigen, salDestino));
    }
}
