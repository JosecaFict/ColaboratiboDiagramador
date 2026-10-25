package com.uagrm.colabmodel.vision;

import java.awt.image.BufferedImage;

/**
 * Separar la tinta del fondo. Un solo lugar, porque lo necesitan dos etapas.
 *
 * El {@link Enderezador} binariza para medir la inclinacion y el
 * {@link DetectorGeometrico} binariza de nuevo la imagen ya enderezada. Tenerlo
 * duplicado significaba que un ajuste del umbral arreglara una etapa y dejara
 * la otra vieja, que es la clase de divergencia silenciosa que esta arquitectura
 * evita en todos lados (una sola derivacion, un solo camino de escritura).
 *
 * Se binariza DOS VECES a proposito: rotar interpola, asi que los grises de la
 * imagen enderezada no son los de la original y el umbral tiene que recalcularse
 * sobre lo que el detector realmente va a mirar.
 *
 * HAY DOS METODOS, Y LA IMAGEN ELIGE CUAL:
 *
 *   - un umbral GLOBAL de Otsu para la imagen digital limpia, que es el caso
 *     que mas importa: un screenshot tiene dos grises y nada en el medio, y un
 *     solo numero los separa perfecto;
 *   - un umbral ADAPTATIVO (Sauvola) cuando la imagen tiene mucho gris
 *     intermedio, que es la firma de una foto con iluminacion despareja. Ahi un
 *     umbral global es lo peor posible: el lado iluminado de la pizarra queda
 *     todo fondo y el lado en sombra queda todo tinta, asi que se pierde medio
 *     diagrama. Antes eso solo se AVISABA; ahora se intenta leer.
 *
 * El metodo no se elige por configuracion sino midiendo la imagen, porque el
 * usuario no sabe --ni tiene por que saber-- si su foto tiene sombra.
 */
final class Binarizacion {

    private Binarizacion() { }

    /**
     * La tinta de un RECORTE, en ocupacion 0..1 y con la polaridad ya resuelta.
     *
     * Existe aparte del mapa de la imagen entera porque hay dos consumidores que
     * miran pedacitos: {@link ClasificadorDeSimbolo}, que mide una figura de 40x40,
     * y {@link OcrDePlantillas}, que compara un glifo de 10x12. En los dos casos el
     * contraste LOCAL es otro que el de la imagen, asi que el umbral se recalcula
     * sobre el recorte.
     *
     * Es en GRISES y no en blanco y negro porque el OCR necesita los medios tonos
     * --a 12 px de alto, el borde suavizado de una letra es la mitad de su
     * informacion-- y quien quiera una mascara nada mas usa {@link #tinta}.
     */
    record Recorte(float[] ocupacion, int ancho, int alto) {

        boolean tinta(int x, int y) {
            return x >= 0 && y >= 0 && x < ancho && y < alto
                    && ocupacion[y * ancho + x] >= 0.5f;
        }
    }

    /**
     * Otsu sobre el recorte, con la tinta identificada como la clase MINORITARIA.
     *
     * "Lo oscuro es tinta" no sirve aca: un screenshot en tema oscuro trae letras
     * claras sobre fondo negro, y es justo uno de los casos faciles. Lo que si se
     * cumple siempre es que la tinta ocupa menos lugar que el fondo --un renglon
     * de texto o un rombo nunca llenan su recorte--, y eso no depende del tema.
     */
    static Recorte deRecorte(BufferedImage imagen) {
        int w = imagen.getWidth();
        int h = imagen.getHeight();
        int[] gris = new int[w * h];
        int[] histograma = new int[256];
        int[] fila = new int[w];
        for (int y = 0; y < h; y++) {
            imagen.getRGB(0, y, w, 1, fila, 0, w);
            for (int x = 0; x < w; x++) {
                int rgb = fila[x];
                int g = (((rgb >> 16) & 0xFF) * 299 + ((rgb >> 8) & 0xFF) * 587
                        + (rgb & 0xFF) * 114) / 1000;
                gris[y * w + x] = g;
                histograma[g]++;
            }
        }

        int umbral = otsu(histograma, (long) w * h);
        long oscuros = 0;
        double sumaOscuros = 0;
        double sumaClaros = 0;
        for (int g = 0; g <= umbral; g++) {
            oscuros += histograma[g];
            sumaOscuros += (double) g * histograma[g];
        }
        for (int g = umbral + 1; g < 256; g++) sumaClaros += (double) g * histograma[g];
        long claros = (long) w * h - oscuros;
        if (oscuros == 0 || claros == 0) return new Recorte(new float[w * h], w, h);

        double mediaOscura = sumaOscuros / oscuros;
        double mediaClara = sumaClaros / claros;
        // La minoritaria es la tinta; la otra es el fondo.
        double nivelTinta = oscuros <= claros ? mediaOscura : mediaClara;
        double nivelFondo = oscuros <= claros ? mediaClara : mediaOscura;

        float[] ocupacion = new float[w * h];
        double rango = nivelTinta - nivelFondo;
        if (Math.abs(rango) < 1e-6) return new Recorte(ocupacion, w, h);
        for (int i = 0; i < gris.length; i++) {
            double v = (gris[i] - nivelFondo) / rango;
            ocupacion[i] = (float) Math.max(0, Math.min(1, v));
        }
        return new Recorte(ocupacion, w, h);
    }

    /** Lado de la ventana de Sauvola, relativo al lado mayor y siempre impar. */
    private static final int VENTANA_MINIMA = 15;
    private static final int VENTANA_MAXIMA = 61;

    /**
     * Cuanto pesa la desviacion local en el umbral de Sauvola.
     *
     * Con k grande el umbral baja mucho en las zonas de poco contraste y los
     * trazos finos se adelgazan hasta cortarse, que es exactamente lo que el
     * detector no tolera --una linea cortada deja de ser un borde--. 0.18 es el
     * lado conservador: preserva el trazo a costa de dejar pasar algo de ruido,
     * y el ruido lo filtra despues el largo minimo de los segmentos.
     */
    private static final double K = 0.18;

    /** Rango dinamico de referencia de Sauvola: la mitad de la escala de gris. */
    private static final double R = 128.0;

    /**
     * Cuanto mas oscuro que su vecindad tiene que ser un pixel para ser tinta,
     * ademas de pasar el umbral de Sauvola.
     *
     * Es el control que hace usable a Sauvola aca, y el lugar donde la primera
     * version se equivoco. Lo intuitivo es decir "si la vecindad es lisa, que
     * mande el umbral global", pero el umbral global es EXACTAMENTE lo que falla
     * en una foto con sombra: en el lado oscuro, el fondo liso cae del lado de
     * la tinta y media imagen sale como una mancha. La formula de Sauvola ya se
     * porta bien en una zona lisa --pide ser un 18 % mas oscuro que la media,
     * asi que el fondo nunca se marca-- y lo unico que le falta es un piso
     * ABSOLUTO: en una zona muy oscura, ese 18 % son tres o cuatro niveles de
     * gris y el ruido del sensor los pasa.
     */
    private static final int MARGEN_MINIMO = 8;

    /**
     * @param tinta      true donde hay trazo
     * @param umbral     el de Otsu sobre la imagen que se binarizo. Con metodo
     *                   adaptativo sigue siendo el global: es el que se informa
     *                   en las metricas y el que gobierna las zonas lisas
     * @param invertida  true si la tinta resulto ser la clara (tema oscuro)
     * @param adaptativa true si se binarizo con Sauvola y no con el umbral global
     */
    record Mapa(boolean[] tinta, int umbral, boolean invertida, boolean adaptativa,
                int ancho, int alto) {

        boolean en(int x, int y) {
            return x >= 0 && y >= 0 && x < ancho && y < alto && tinta[y * ancho + x];
        }

        long pixelesConTinta() {
            long n = 0;
            for (boolean t : tinta) if (t) n++;
            return n;
        }
    }

    static Mapa de(ImagenDeEntrada imagen) {
        int umbral = otsu(imagen);
        int w = imagen.ancho();
        int h = imagen.alto();

        // La POLARIDAD se decide siempre con el umbral global, antes de elegir
        // el metodo: es una propiedad de la imagen entera --tinta oscura sobre
        // fondo claro o al reves-- y una ventana de 30 px no la puede saber.
        long oscuros = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (imagen.gris(x, y) <= umbral) oscuros++;
            }
        }
        boolean invertida = oscuros * 2 > (long) w * h;

        if (pareceFoto(imagen, umbral)) {
            return new Mapa(sauvola(imagen, invertida), umbral, invertida, true, w, h);
        }
        return new Mapa(global(imagen, umbral, invertida), umbral, invertida, false, w, h);
    }

    /**
     * Un solo numero para toda la imagen.
     *
     * Oscuro = tinta, salvo que la mayoria de la imagen sea oscura: ahi la tinta
     * es la clara. Sin eso, un screenshot en tema oscuro --que es justo el caso
     * mas facil de todos-- sale como una mancha completa.
     */
    private static boolean[] global(ImagenDeEntrada imagen, int umbral, boolean invertida) {
        int w = imagen.ancho();
        int h = imagen.alto();
        boolean[] mapa = new boolean[w * h];
        for (int y = 0; y < h; y++) {
            int base = y * w;
            for (int x = 0; x < w; x++) {
                // <= y no <: la clase de fondo de Otsu es [0..umbral], incluido.
                boolean oscuro = imagen.gris(x, y) <= umbral;
                mapa[base + x] = invertida != oscuro;
            }
        }
        return mapa;
    }

    /**
     * Un umbral por pixel, calculado con la media y la desviacion de su vecindad.
     *
     * t = m * (1 + k * (s / R - 1)): donde hay contraste local el umbral se
     * acerca a la media --y el trazo aparece aunque la zona entera este en
     * sombra--; donde no hay contraste el umbral se hunde y no se inventa nada.
     *
     * Las dos integrales acumuladas son lo que lo hace viable: la media y la
     * desviacion de CUALQUIER ventana salen en cuatro accesos, asi que el costo
     * no depende del tamano de la ventana. Sin ellas, una ventana de 31 px sobre
     * una imagen de 1600 de lado son mil millones de sumas.
     *
     * Trabaja sobre el valor ya orientado --si la tinta era la clara, se
     * invierte el gris primero-- para tener un solo camino en vez de dos
     * comparaciones simetricas que despues se desincronizan.
     */
    private static boolean[] sauvola(ImagenDeEntrada imagen, boolean invertida) {
        int w = imagen.ancho();
        int h = imagen.alto();
        int radio = ventanaPara(Math.max(w, h)) / 2;

        // (w+1) x (h+1) para no tener que preguntar por los bordes en el bucle.
        long[] suma = new long[(w + 1) * (h + 1)];
        long[] sumaCuadrados = new long[(w + 1) * (h + 1)];
        for (int y = 0; y < h; y++) {
            long filaSuma = 0;
            long filaCuadrados = 0;
            int arriba = y * (w + 1);
            int actual = (y + 1) * (w + 1);
            for (int x = 0; x < w; x++) {
                int v = valor(imagen, x, y, invertida);
                filaSuma += v;
                filaCuadrados += (long) v * v;
                suma[actual + x + 1] = suma[arriba + x + 1] + filaSuma;
                sumaCuadrados[actual + x + 1] = sumaCuadrados[arriba + x + 1] + filaCuadrados;
            }
        }

        boolean[] mapa = new boolean[w * h];
        for (int y = 0; y < h; y++) {
            int y0 = Math.max(0, y - radio);
            int y1 = Math.min(h - 1, y + radio);
            for (int x = 0; x < w; x++) {
                int x0 = Math.max(0, x - radio);
                int x1 = Math.min(w - 1, x + radio);
                long n = (long) (x1 - x0 + 1) * (y1 - y0 + 1);
                long total = rectangulo(suma, w, x0, y0, x1, y1);
                long totalCuadrados = rectangulo(sumaCuadrados, w, x0, y0, x1, y1);

                double media = total / (double) n;
                double varianza = totalCuadrados / (double) n - media * media;
                double desviacion = varianza <= 0 ? 0 : Math.sqrt(varianza);

                double umbral = media * (1 + K * (desviacion / R - 1));
                int v = valor(imagen, x, y, invertida);
                mapa[y * w + x] = v <= umbral && v <= media - MARGEN_MINIMO;
            }
        }
        return mapa;
    }

    /** El gris, ya orientado para que la tinta sea siempre la oscura. */
    private static int valor(ImagenDeEntrada imagen, int x, int y, boolean invertida) {
        int g = imagen.gris(x, y);
        return invertida ? 255 - g : g;
    }

    /** Suma de un rectangulo cerrado [x0,x1] x [y0,y1] sobre una integral acumulada. */
    private static long rectangulo(long[] integral, int w, int x0, int y0, int x1, int y1) {
        int paso = w + 1;
        return integral[(y1 + 1) * paso + x1 + 1]
                - integral[y0 * paso + x1 + 1]
                - integral[(y1 + 1) * paso + x0]
                + integral[y0 * paso + x0];
    }

    /**
     * La ventana tiene que ser mas grande que un trazo y mas chica que una caja.
     *
     * Demasiado chica y el interior de una linea gruesa queda "sin contraste
     * local" y se vacia; demasiado grande y vuelve a ser un umbral global, que
     * es justo lo que se esta evitando.
     */
    private static int ventanaPara(int ladoMayor) {
        int lado = Math.max(VENTANA_MINIMA, Math.min(VENTANA_MAXIMA, ladoMayor / 16));
        return lado % 2 == 0 ? lado + 1 : lado;
    }

    /**
     * Umbral de Otsu, quedandose con el PUNTO MEDIO de los umbrales empatados.
     *
     * Un diagrama tiene dos grises y nada en el medio, asi que la varianza
     * entre clases es identica para todo umbral entre el color de la tinta y
     * el del fondo. Quedandose con el primero, lineas negras sobre blanco daban
     * umbral 0 y la mascara de tinta salia VACIA: no se detectaba nada. El
     * punto medio ademas deja del lado correcto los grises del antialiasing,
     * que en una captura real son el borde de cada linea.
     */
    static int otsu(ImagenDeEntrada imagen) {
        int[] histograma = new int[256];
        for (int y = 0; y < imagen.alto(); y++) {
            for (int x = 0; x < imagen.ancho(); x++) histograma[imagen.gris(x, y)]++;
        }
        return otsu(histograma, (long) imagen.ancho() * imagen.alto());
    }

    /** El mismo Otsu sobre un histograma ya contado, para no recorrer dos veces. */
    private static int otsu(int[] histograma, long total) {
        double sumaTotal = 0;
        for (int i = 0; i < 256; i++) sumaTotal += (double) i * histograma[i];

        double sumaFondo = 0;
        long pesoFondo = 0;
        double mejorVarianza = -1;
        int primero = 128;
        int ultimo = 128;
        for (int t = 0; t < 256; t++) {
            pesoFondo += histograma[t];
            if (pesoFondo == 0) continue;
            long pesoFrente = total - pesoFondo;
            if (pesoFrente == 0) break;
            sumaFondo += (double) t * histograma[t];
            double mediaFondo = sumaFondo / pesoFondo;
            double mediaFrente = (sumaTotal - sumaFondo) / pesoFrente;
            double varianza = (double) pesoFondo * pesoFrente
                    * (mediaFondo - mediaFrente) * (mediaFondo - mediaFrente);
            if (varianza > mejorVarianza * (1 + 1e-9)) {
                mejorVarianza = varianza;
                primero = t;
                ultimo = t;
            } else if (varianza >= mejorVarianza * (1 - 1e-9)) {
                ultimo = t;
            }
        }
        return (primero + ultimo) / 2;
    }

    /** Proporcion de pixeles que no son ni claramente fondo ni claramente tinta. */
    static boolean pareceFoto(ImagenDeEntrada imagen, int umbral) {
        int bajo = Math.max(0, umbral - 40);
        int alto = Math.min(255, umbral + 40);
        long intermedios = 0;
        for (int y = 0; y < imagen.alto(); y++) {
            for (int x = 0; x < imagen.ancho(); x++) {
                int g = imagen.gris(x, y);
                if (g > bajo && g < alto) intermedios++;
            }
        }
        return intermedios > (long) (0.25 * imagen.ancho() * imagen.alto());
    }
}
