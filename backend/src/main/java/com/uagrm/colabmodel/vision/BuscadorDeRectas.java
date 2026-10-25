package com.uagrm.colabmodel.vision;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Busca rectas en la tinta que el analisis de corridas NO pudo explicar.
 *
 * POR QUE EXISTE. El nivel 1 lee corridas de tinta por fila y por columna, y
 * eso deja ciegos exactamente dos casos, los dos frecuentes:
 *
 *   - la linea DIAGONAL, que no produce ninguna corrida larga: es una escalera
 *     de trocitos, cada uno mas corto que el minimo;
 *   - la linea PUNTEADA, que es la unica forma de dibujar una dependencia, y
 *     que tampoco produce corridas largas ni siquiera cuando es horizontal.
 *
 * Antes las dos se AVISABAN y se cargaban a mano. El aviso era correcto --la
 * Regla 10 prohibe callar-- pero un aviso no es una lectura: el usuario tenia
 * que dibujar de nuevo una relacion que estaba ahi. Y la punteada no se podia
 * ni siquiera adivinar, asi que DEPENDENCY era un tipo de relacion al que la
 * vision no tenia ningun camino.
 *
 * COMO. Transformada de Hough sobre los pixeles que quedaron SIN EXPLICAR, que
 * es lo que la hace barata y lo que la hace fiable:
 *
 *   - barata, porque los bordes de las cajas, su interior y todos los trazos
 *     rectos ya estan marcados: lo que queda son unos pocos miles de pixeles en
 *     vez del millon de la imagen;
 *   - fiable, porque una linea que el nivel 1 ya leyo no puede aparecer aca de
 *     nuevo como una segunda relacion fantasma.
 *
 * NO INVENTA RELACIONES. Devuelve rectas; quien decide si una recta es una
 * relacion es el detector, y solo si sus dos puntas llegan a dos cajas. Una
 * recta que no llega a ninguna parte se descarta y la tinta sigue contando como
 * no explicada, asi que el aviso de siempre se emite igual.
 *
 * TRES FILTROS, y cada uno esta por un falso positivo concreto:
 *
 *   - GROSOR. Un renglon de texto fuera de las cajas --un titulo, una etiqueta,
 *     una cardinalidad-- es una recta horizontal perfecta de 12 px de alto. Una
 *     linea de diagrama tiene 1 a 3. Se exige delgadez.
 *   - COBERTURA. Que fraccion del recorrido tiene tinta. Una punteada ronda el
 *     0.5, una continua el 1.0, y una nube de puntos alineados por casualidad
 *     no llega al minimo.
 *   - LARGO. Mas corto que el ancho minimo de una caja no es un conector.
 */
final class BuscadorDeRectas {

    private BuscadorDeRectas() { }

    /** Angulos probados: uno por grado. Medio grado no cambia nada y duplica el costo. */
    private static final int ANGULOS = 180;

    /** Cuanto se aparta de la recta un pixel que igual es parte de ella. */
    private static final int TOLERANCIA = 2;

    /**
     * Hasta donde se mira para MEDIR el grosor, que es mas lejos que la
     * pertenencia.
     *
     * Con los mismos 2 px de la pertenencia, el grosor saturaba en 5 y el filtro
     * de delgadez no podia distinguir una linea de 2 px de un renglon de texto
     * de 12: los dos daban "5 o mas". Hay que mirar mas ancho que lo que se
     * quiere descartar.
     */
    private static final int RADIO_GROSOR = 5;

    /** Tope de pixeles que se votan. Por encima se submuestrea. */
    private static final int MAX_PUNTOS = 30_000;

    /**
     * Si la tinta sin explicar pasa de esta fraccion de la imagen, no se busca
     * nada.
     *
     * No es un limite de rendimiento sino de sentido: con tanta tinta suelta la
     * imagen no es un diagrama de lineas --es una foto con ruido, un sombreado
     * o una captura llena de texto--, y buscar rectas ahi produce pura basura
     * plausible. El aviso de tinta no interpretada dice la verdad mejor.
     */
    private static final double MAX_FRACCION_SUELTA = 0.15;

    /** Celdas del acumulador que se miran, de mayor a menor votacion. */
    private static final int MAX_CANDIDATAS = 240;

    /**
     * Una recta encontrada, ya medida.
     *
     * @param cobertura fraccion del recorrido que tiene tinta: ~1.0 continua,
     *                  ~0.5 punteada
     * @param grosor    ancho medio en pixeles medido perpendicular a la recta
     */
    record Recta(int x1, int y1, int x2, int y2, double largo, double cobertura, int grosor) {

        /**
         * Una linea con tanto hueco como trazo esta punteada, y en UML eso
         * significa dependencia.
         *
         * El corte no es 0.99 a proposito: una continua real pierde cobertura
         * en los bordes por el antialiasing y por el redondeo del recorrido.
         */
        boolean punteada() {
            return cobertura < 0.8;
        }
    }

    /**
     * @param mapa       la tinta de la imagen ya enderezada
     * @param explicado  que pixeles ya tienen dueno. NO SE MODIFICA: se trabaja
     *                   sobre una copia, porque encontrar una recta no es
     *                   haberla leido --todavia falta que sus puntas lleguen a
     *                   dos cajas-- y dar por explicada una recta que despues se
     *                   descarta es la peor variante posible: la linea no entra
     *                   a la propuesta Y ademas deja de avisarse. Marcar es
     *                   tarea de quien acepta la relacion, con {@link #marcar}
     * @param largoMinimo por debajo de esto no es un conector
     * @param huecoMaximo hueco tolerado dentro de una misma recta: es lo que
     *                    permite leer una punteada como una sola linea
     * @param grosorMaximo mas gruesa que esto no es una linea, es texto o una barra
     * @param maxRectas   tope de resultados
     */
    static List<Recta> buscar(Binarizacion.Mapa mapa, boolean[] explicado, int largoMinimo,
                              int huecoMaximo, int grosorMaximo, int maxRectas) {
        int w = mapa.ancho();
        int h = mapa.alto();

        // La copia es para la supresion de picos: en cuanto una recta se acepta,
        // su tinta deja de estar disponible y los otros picos del acumulador que
        // caian sobre la misma linea se caen solos, sin ningun desempate por
        // angulo ni por distancia.
        boolean[] tomado = explicado.clone();

        int candidatos = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (mapa.en(x, y) && !tomado[y * w + x]) candidatos++;
            }
        }
        if (candidatos < largoMinimo / 2) return List.of();
        if (candidatos > MAX_FRACCION_SUELTA * w * h) return List.of();

        int paso = Math.max(1, candidatos / MAX_PUNTOS);
        double[] cos = new double[ANGULOS];
        double[] sin = new double[ANGULOS];
        for (int t = 0; t < ANGULOS; t++) {
            double rad = Math.toRadians(t);
            cos[t] = Math.cos(rad);
            sin[t] = Math.sin(rad);
        }

        int diagonal = (int) Math.ceil(Math.hypot(w, h));
        int rhos = 2 * diagonal + 1;
        int[] acumulador = new int[ANGULOS * rhos];
        int vistos = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (!mapa.en(x, y) || tomado[y * w + x]) continue;
                if (vistos++ % paso != 0) continue;
                for (int t = 0; t < ANGULOS; t++) {
                    int rho = (int) Math.round(x * cos[t] + y * sin[t]) + diagonal;
                    acumulador[t * rhos + rho]++;
                }
            }
        }

        // Votos que hacen falta para que valga la pena verificar una celda. Con
        // submuestreo los votos bajan en la misma proporcion, asi que el umbral
        // se divide por el paso o no se encontraria nada en una imagen grande.
        int minVotos = Math.max(6, (int) (largoMinimo * 0.30 / paso));
        // Y ademas relativo al pico mas votado: en una imagen con tinta dispersa
        // hay cientos de miles de celdas que pasan el minimo absoluto, y
        // ordenarlas todas cuesta mas que la deteccion entera.
        int maximo = 0;
        for (int v : acumulador) if (v > maximo) maximo = v;
        minVotos = Math.max(minVotos, maximo / 6);
        List<int[]> celdas = new ArrayList<>();
        for (int t = 0; t < ANGULOS; t++) {
            for (int rho = 0; rho < rhos; rho++) {
                int votos = acumulador[t * rhos + rho];
                if (votos >= minVotos) celdas.add(new int[]{votos, t, rho});
            }
        }
        if (celdas.isEmpty()) return List.of();
        celdas.sort(Comparator.comparingInt((int[] c) -> -c[0]));
        if (celdas.size() > MAX_CANDIDATAS) celdas = celdas.subList(0, MAX_CANDIDATAS);

        List<Recta> rectas = new ArrayList<>();
        for (int[] celda : celdas) {
            if (rectas.size() >= maxRectas) break;
            Recta recta = medir(mapa, tomado, celda[1], celda[2] - diagonal,
                    cos[celda[1]], sin[celda[1]], largoMinimo, huecoMaximo, grosorMaximo);
            if (recta == null) continue;
            rectas.add(recta);
            marcar(tomado, w, h, recta);
        }
        return List.copyOf(rectas);
    }

    /**
     * Recorre la recta de punta a punta y devuelve el tramo con tinta mas largo.
     *
     * Se camina la recta en vez de volver a mirar todos los pixeles: el costo
     * es el largo de la recta, no el tamano de la imagen, y de paso salen la
     * cobertura, el grosor y los extremos de una sola pasada.
     *
     * El tramo se corta cuando el hueco supera el tolerado. Eso es lo que
     * distingue "una punteada" de "dos lineas distintas que casualmente estan
     * alineadas", que en un diagrama con dos relaciones paralelas pasa siempre.
     */
    private static Recta medir(Binarizacion.Mapa mapa, boolean[] explicado, int grados, int rho,
                               double cos, double sin, int largoMinimo, int huecoMaximo,
                               int grosorMaximo) {
        int w = mapa.ancho();
        int h = mapa.alto();
        int alcance = (int) Math.ceil(Math.hypot(w, h));

        int mejorInicio = 0;
        int mejorFin = -1;
        int mejorConTinta = 0;
        int[] mejorGrosores = new int[2 * RADIO_GROSOR + 2];

        int inicio = Integer.MIN_VALUE;
        int ultimo = Integer.MIN_VALUE;
        int conTinta = 0;
        int[] grosores = new int[2 * RADIO_GROSOR + 2];

        for (int t = -alcance; t <= alcance; t++) {
            int x = (int) Math.round(rho * cos - t * sin);
            int y = (int) Math.round(rho * sin + t * cos);
            if (x < 0 || y < 0 || x >= w || y >= h) continue;

            if (hayTinta(mapa, explicado, x, y, cos, sin)) {
                if (inicio == Integer.MIN_VALUE) inicio = t;
                ultimo = t;
                conTinta++;
                grosores[grosorEn(mapa, explicado, x, y, cos, sin)]++;
            } else if (inicio != Integer.MIN_VALUE && t - ultimo > huecoMaximo) {
                if (ultimo - inicio > mejorFin - mejorInicio) {
                    mejorInicio = inicio;
                    mejorFin = ultimo;
                    mejorConTinta = conTinta;
                    mejorGrosores = grosores;
                }
                inicio = Integer.MIN_VALUE;
                conTinta = 0;
                grosores = new int[2 * RADIO_GROSOR + 2];
            }
        }
        if (inicio != Integer.MIN_VALUE && ultimo - inicio > mejorFin - mejorInicio) {
            mejorInicio = inicio;
            mejorFin = ultimo;
            mejorConTinta = conTinta;
            mejorGrosores = grosores;
        }

        int largo = mejorFin - mejorInicio;
        if (largo < largoMinimo || mejorConTinta == 0) return null;

        // La MEDIANA y no el promedio: un rombo o un triangulo en la punta son
        // veinte posiciones muy gruesas sobre una linea fina, y con el promedio
        // una relacion con figura terminaba descartada por "demasiado gruesa".
        int grosor = mediana(mejorGrosores, mejorConTinta);
        if (grosor > grosorMaximo) return null;

        double cobertura = mejorConTinta / (double) (largo + 1);
        if (cobertura < 0.30) return null;

        int x1 = (int) Math.round(rho * cos - mejorInicio * sin);
        int y1 = (int) Math.round(rho * sin + mejorInicio * cos);
        int x2 = (int) Math.round(rho * cos - mejorFin * sin);
        int y2 = (int) Math.round(rho * sin + mejorFin * cos);
        return new Recta(x1, y1, x2, y2, largo, Math.min(1.0, cobertura), grosor);
    }

    /** Si la recta pasa por tinta sin explicar en este punto, con su tolerancia. */
    private static boolean hayTinta(Binarizacion.Mapa mapa, boolean[] explicado, int x, int y,
                                    double cos, double sin) {
        return contar(mapa, explicado, x, y, cos, sin, TOLERANCIA) > 0;
    }

    /**
     * Cuantos pixeles de tinta sin explicar hay cruzando la recta en este punto.
     *
     * Medir el ancho y no un si/no es lo que permite el filtro de delgadez: un
     * renglon de texto da 10 o 12 aca y una linea de diagrama da 1 a 3.
     */
    private static int grosorEn(Binarizacion.Mapa mapa, boolean[] explicado, int x, int y,
                                double cos, double sin) {
        return contar(mapa, explicado, x, y, cos, sin, RADIO_GROSOR);
    }

    private static int contar(Binarizacion.Mapa mapa, boolean[] explicado, int x, int y,
                              double cos, double sin, int radio) {
        int w = mapa.ancho();
        int cuenta = 0;
        for (int d = -radio; d <= radio; d++) {
            int px = (int) Math.round(x + d * cos);
            int py = (int) Math.round(y + d * sin);
            if (!mapa.en(px, py)) continue;
            if (explicado[py * w + px]) continue;
            cuenta++;
        }
        return cuenta;
    }

    /** Mediana sobre el histograma de grosores, que tiene doce celdas y nada mas. */
    private static int mediana(int[] histograma, int total) {
        int mitad = total / 2;
        int acumulado = 0;
        for (int g = 0; g < histograma.length; g++) {
            acumulado += histograma[g];
            if (acumulado > mitad) return g;
        }
        return histograma.length - 1;
    }

    /**
     * La banda de la recta pasa a tener dueno: ya esta interpretada.
     *
     * Lo llama el detector cuando la recta llego a ser una relacion, y el propio
     * buscador sobre su copia para no devolver dos veces la misma linea.
     */
    static void marcar(boolean[] explicado, int w, int h, Recta recta) {
        double dx = recta.x2() - recta.x1();
        double dy = recta.y2() - recta.y1();
        double largo = Math.max(1, Math.hypot(dx, dy));
        dx /= largo;
        dy /= largo;
        int margen = TOLERANCIA + 1;
        for (double t = 0; t <= largo; t += 0.5) {
            int cx = (int) Math.round(recta.x1() + dx * t);
            int cy = (int) Math.round(recta.y1() + dy * t);
            for (int y = cy - margen; y <= cy + margen; y++) {
                for (int x = cx - margen; x <= cx + margen; x++) {
                    if (x < 0 || y < 0 || x >= w || y >= h) continue;
                    explicado[y * w + x] = true;
                }
            }
        }
    }
}
