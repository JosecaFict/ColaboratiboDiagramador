package com.uagrm.colabmodel.vision;

import com.uagrm.colabmodel.modelcore.metamodel.RelationKind;

import java.util.Locale;
import java.util.Set;

/**
 * Lo que devuelve un modelo de vision, antes de dejarlo entrar.
 *
 * ES LA PARTE QUE MAS IMPORTA DEL NIVEL 2, y no es el prompt: es esto. Un
 * modelo chico contesta lo que se le pidio la mayoria de las veces, y el
 * resto contesta "El texto dice: Libro.", o "I cannot read this image", o un
 * parrafo entero. Sin un filtro, cualquiera de esas tres se convierte en el
 * nombre de una clase del modelo.
 *
 * El criterio es el mismo de todo el modulo: ANTE LA DUDA, null. Un null es
 * una celda que el usuario completa en el panel; un texto inventado es un
 * defecto que nadie ve hasta que el generador emite una tabla con ese nombre.
 *
 * Funcion pura, sin red ni estado, para poder probarla con las respuestas
 * reales que se vean en la practica.
 */
final class RespuestaDeModelo {

    /** Un nombre de clase o de atributo no es una frase. */
    private static final int MAX_LARGO = 60;

    /**
     * Lo que puede aparecer en un nombre UML, sus tipos y su multiplicidad.
     * Todo lo demas es senal de que el modelo contesto otra cosa.
     */
    private static final String PERMITIDOS = "_-.,:;()[]{}<>*/+#~«»'\"&%$@!?=|\\ ";

    /**
     * Como empieza una negativa. No se buscan en cualquier posicion: "no" o
     * "sin" pueden ser parte de un nombre legitimo.
     */
    private static final Set<String> NEGATIVAS = Set.of(
            "vacio", "vacia", "none", "empty", "null", "n/a", "na", "-", "?",
            "no puedo", "no se", "no hay", "lo siento", "disculpa",
            "i cannot", "i can't", "i'm sorry", "sorry", "unable", "unclear",
            "the image", "la imagen", "esta imagen", "this image", "it appears",
            "as an ai", "como modelo");

    private RespuestaDeModelo() { }

    /**
     * Texto utilizable, o null.
     *
     * @param crudo lo que contesto el modelo, tal cual
     */
    static String texto(String crudo) {
        if (crudo == null) return null;

        // Un modelo que se explaya manda varias lineas; la util es la primera.
        String limpio = crudo.strip();
        int salto = limpio.indexOf('\n');
        if (salto >= 0) limpio = limpio.substring(0, salto).strip();

        limpio = sinEnvoltorio(limpio);
        if (limpio.isEmpty()) return null;

        // "El texto dice: Libro" -> "Libro". Solo si lo de la izquierda parece
        // una introduccion y no parte del dato: un atributo es "titulo: string"
        // y ahi los dos lados importan.
        limpio = sinIntroduccion(limpio);
        limpio = limpio.strip();
        if (limpio.isEmpty() || limpio.length() > MAX_LARGO) return null;

        String comparable = limpio.toLowerCase(Locale.ROOT);
        for (String negativa : NEGATIVAS) {
            if (empiezaCon(comparable, negativa)) return null;
        }
        if (!soloCaracteresEsperados(limpio)) return null;
        // Sin una sola letra ni digito no hay nada que leer: "---", "...".
        if (limpio.chars().noneMatch(Character::isLetterOrDigit)) return null;
        return limpio;
    }

    /**
     * La negativa tiene que ser una PALABRA completa al principio.
     *
     * Con `startsWith(negativa + " ")` se escapaba "I'm sorry, the image..."
     * --lo que sigue es una coma, no un espacio-- y una disculpa entera
     * terminaba de nombre de clase. Exigir que el corte no caiga en mitad de
     * una palabra tambien evita el falso positivo contrario: "Nave" no empieza
     * con la negativa "na".
     */
    private static boolean empiezaCon(String texto, String negativa) {
        if (!texto.startsWith(negativa)) return false;
        if (texto.length() == negativa.length()) return true;
        // "-" o "?" SOLOS son una negativa; al principio de un renglon son otra
        // cosa: "- titulo: String" es un atributo privado, la visibilidad mas
        // comun de UML. Una negativa sin letras solo cuenta si es toda la respuesta.
        if (negativa.chars().noneMatch(Character::isLetterOrDigit)) return false;
        return !Character.isLetterOrDigit(texto.charAt(negativa.length()));
    }

    /** Comillas, backticks o asteriscos alrededor de la respuesta. */
    private static String sinEnvoltorio(String s) {
        String r = s;
        boolean saco = true;
        while (saco && r.length() >= 2) {
            saco = false;
            char primero = r.charAt(0);
            char ultimo = r.charAt(r.length() - 1);
            if ((primero == '"' && ultimo == '"') || (primero == '\'' && ultimo == '\'')
                    || (primero == '`' && ultimo == '`') || (primero == '*' && ultimo == '*')) {
                r = r.substring(1, r.length() - 1).strip();
                saco = true;
            }
        }
        // Un punto final es puntuacion de frase, no parte del nombre.
        while (r.endsWith(".") && !r.endsWith("..")) r = r.substring(0, r.length() - 1).strip();
        return r;
    }

    private static String sinIntroduccion(String s) {
        int dosPuntos = s.indexOf(':');
        if (dosPuntos <= 0 || dosPuntos == s.length() - 1) return s;
        String izquierda = s.substring(0, dosPuntos).toLowerCase(Locale.ROOT);
        // Varias palabras a la izquierda = es una frase, no "campo: tipo".
        if (izquierda.strip().split("\\s+").length < 3) return s;
        return s.substring(dosPuntos + 1);
    }

    private static boolean soloCaracteresEsperados(String s) {
        for (char c : s.toCharArray()) {
            if (Character.isLetterOrDigit(c)) continue;
            if (PERMITIDOS.indexOf(c) >= 0) continue;
            return false;
        }
        return true;
    }

    /**
     * La figura del extremo de una linea, o null si el modelo no fue claro.
     *
     * Se le pide una de cuatro palabras y se acepta solo eso. Si contesta
     * cualquier otra cosa, la relacion se queda en ASSOCIATION, que es la
     * lectura conservadora: no impone ON DELETE CASCADE ni PK compartida.
     */
    static RelationKind figura(String crudo) {
        if (crudo == null) return null;
        String s = crudo.strip().toUpperCase(Locale.ROOT).replace('-', '_');
        if (s.contains("ROMBO_RELLENO") || s.contains("ROMBO RELLENO")) return RelationKind.COMPOSITION;
        if (s.contains("ROMBO_HUECO") || s.contains("ROMBO HUECO")) return RelationKind.AGGREGATION;
        if (s.contains("TRIANGULO") || s.contains("TRIÁNGULO")) return RelationKind.GENERALIZATION;
        return null;   // incluye NINGUNA: no hay figura que anotar
    }
}
