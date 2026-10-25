package com.uagrm.colabmodel.vision;

import java.util.Arrays;

/**
 * Cuanto esta torcida la imagen, y cuanto hay que rotarla para enderezarla.
 *
 * POR QUE EXISTE. El detector lee corridas de tinta por FILA y por COLUMNA, asi
 * que una linea inclinada no se ve mas debil: se astilla en una escalera de
 * trocitos, cada uno mas corto que el minimo, y desaparece entera. Medido sobre
 * el mismo diagrama: alineado da 9 segmentos y 2 clases; a 1 grado da 16
 * segmentos y CERO clases; a 6 grados, 138 segmentos y cero clases. No es una
 * degradacion suave, es un acantilado --y nadie saca una foto a 0.0 grados--.
 * Para una captura de proyector o un screenshot esto no hace falta; es lo que
 * separa "sirve para pantallas" de "sirve para pizarras".
 *
 * COMO. Perfil de proyeccion. Se prueban angulos y se elige el que CONCENTRA
 * mas la tinta al proyectarla sobre los dos ejes: cuando las lineas quedan
 * rectas caen todas en unas pocas filas y unas pocas columnas, y la suma de los
 * cuadrados del histograma se dispara. Se proyectan los puntos con seno y
 * coseno en vez de rotar la imagen en cada prueba, que seria decenas de
 * rotaciones completas en una CPU sin GPU.
 *
 * Se mide sobre el mapa de tinta, no sobre la imagen: es geometria pura, y eso
 * la hace verificable con un bitmap dibujado a mano en un test.
 */
public final class Enderezador {

    /** Mas alla de esto no es una foto torcida, es otra cosa. */
    public static final double MAX_GRADOS = 10.0;

    private static final double PASO_GRUESO = 0.5;
    private static final double PASO_FINO = 0.1;

    /** Tope de puntos a muestrear: el barrido es lineal en esta cantidad. */
    private static final int MAX_PUNTOS = 40_000;
    private static final int MIN_PUNTOS = 200;

    /** Por debajo de esto, rotar no compensa el borroneo que introduce. */
    private static final double GRADOS_MINIMOS = 0.2;

    /** Mejora minima sobre no rotar, para no torcer un diagrama que ya estaba derecho. */
    private static final double GANANCIA_MINIMA = 0.03;

    private Enderezador() { }

    /**
     * @param grados   cuanto hay que rotar para enderezar. Mismo signo y mismo
     *                 sentido que {@link ImagenDeEntrada#rotada(double)}
     * @param ganancia cuanto mejor concentra la tinta ese angulo frente a no
     *                 rotar. 0.30 = un 30 % mejor
     */
    public record Inclinacion(double grados, double ganancia) {

        public static final Inclinacion NINGUNA = new Inclinacion(0, 0);

        /**
         * Rotar cuesta: interpola, borronea los trazos finos y agranda el
         * lienzo. Solo vale la pena si el angulo es apreciable Y si concentrar
         * la tinta mejora de verdad. Sin el segundo control, una imagen con
         * mucho contenido diagonal legitimo --o casi sin lineas rectas-- se
         * terminaba torciendo para "arreglar" algo que no estaba roto.
         */
        public boolean valeLaPena() {
            return Math.abs(grados) >= GRADOS_MINIMOS && ganancia >= GANANCIA_MINIMA;
        }
    }

    public static Inclinacion estimar(ImagenDeEntrada imagen) {
        return estimar(Binarizacion.de(imagen));
    }

    static Inclinacion estimar(Binarizacion.Mapa mapa) {
        int[] puntos = muestrear(mapa);
        if (puntos.length / 2 < MIN_PUNTOS) return Inclinacion.NINGUNA;

        int bins = 2 * (mapa.ancho() + mapa.alto()) + 4;
        int[] acumulador = new int[bins];
        double base = energia(puntos, 0, mapa, acumulador);
        if (base <= 0) return Inclinacion.NINGUNA;

        double mejorAngulo = 0;
        double mejorEnergia = base;
        for (double a = -MAX_GRADOS; a <= MAX_GRADOS + 1e-9; a += PASO_GRUESO) {
            double e = energia(puntos, a, mapa, acumulador);
            if (e > mejorEnergia) {
                mejorEnergia = e;
                mejorAngulo = a;
            }
        }
        // Afinado alrededor del mejor grueso: medio grado de error todavia
        // astilla los bordes de una caja grande.
        double desde = mejorAngulo - PASO_GRUESO;
        double hasta = mejorAngulo + PASO_GRUESO;
        for (double a = desde; a <= hasta + 1e-9; a += PASO_FINO) {
            double e = energia(puntos, a, mapa, acumulador);
            if (e > mejorEnergia) {
                mejorEnergia = e;
                mejorAngulo = a;
            }
        }

        double ganancia = mejorEnergia / base - 1.0;
        return new Inclinacion(redondear(mejorAngulo), ganancia);
    }

    /**
     * Suma de cuadrados de los dos histogramas de proyeccion.
     *
     * Se usan los DOS ejes porque un diagrama de clases tiene tantas lineas
     * verticales como horizontales: con un solo eje, un diagrama de cajas altas
     * y angostas daba un maximo poco marcado y el angulo bailaba.
     */
    private static double energia(int[] puntos, double grados,
                                  Binarizacion.Mapa mapa, int[] acumulador) {
        double rad = Math.toRadians(grados);
        double sin = Math.sin(rad);
        double cos = Math.cos(rad);
        int corrimiento = mapa.ancho() + mapa.alto();

        Arrays.fill(acumulador, 0);
        double suma = 0;
        // Horizontal: y' = x*sin + y*cos, el mismo mapeo que aplica la rotacion.
        for (int i = 0; i < puntos.length; i += 2) {
            int idx = (int) (puntos[i] * sin + puntos[i + 1] * cos) + corrimiento;
            acumulador[idx]++;
        }
        for (int c : acumulador) suma += (double) c * c;

        Arrays.fill(acumulador, 0);
        // Vertical: x' = x*cos - y*sin.
        for (int i = 0; i < puntos.length; i += 2) {
            int idx = (int) (puntos[i] * cos - puntos[i + 1] * sin) + corrimiento;
            acumulador[idx]++;
        }
        for (int c : acumulador) suma += (double) c * c;

        return suma;
    }

    /** Coordenadas de la tinta, submuestreadas con paso fijo para acotar el costo. */
    private static int[] muestrear(Binarizacion.Mapa mapa) {
        long conTinta = mapa.pixelesConTinta();
        if (conTinta == 0) return new int[0];
        int paso = (int) Math.max(1, conTinta / MAX_PUNTOS);

        int[] puntos = new int[(int) Math.min(conTinta / paso + 1, MAX_PUNTOS + 1) * 2];
        int n = 0;
        long vistos = 0;
        for (int y = 0; y < mapa.alto() && n + 1 < puntos.length; y++) {
            for (int x = 0; x < mapa.ancho() && n + 1 < puntos.length; x++) {
                if (!mapa.en(x, y)) continue;
                if (vistos++ % paso != 0) continue;
                puntos[n++] = x;
                puntos[n++] = y;
            }
        }
        return Arrays.copyOf(puntos, n);
    }

    private static double redondear(double grados) {
        return Math.round(grados * 100.0) / 100.0;
    }
}
