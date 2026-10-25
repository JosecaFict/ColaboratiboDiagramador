package com.uagrm.colabmodel.vision;

/**
 * Remuestrea una figura de tinta a una caja de otro tamano, y mide parecidos.
 *
 * Es el unico punto de contacto entre las dos mitades del OCR: el glifo dibujado
 * de una fuente y el glifo que llego en la imagen. Los dos terminan en una caja
 * del MISMO tamano en pixeles y se comparan celda por celda.
 *
 * SE COMPARA AL TAMANO DEL GLIFO LEIDO, no en una rejilla fija.
 *
 * La primera version normalizaba todo a 16x16 y no alcanzaba, por una razon que
 * se ve en los numeros: entre una E y una F la unica diferencia es la barra de
 * abajo, y estirando los dos glifos a 16x16 esa barra queda del mismo orden que
 * el ruido del antialiasing. Medido sobre "Estudiante" en varios tamanos, el
 * costo del acierto llegaba a 0.31 y el de un glifo que NO era ninguna letra
 * bajaba a 0.17: no habia ningun umbral que separara una cosa de la otra.
 * Comparando al tamano real, la diferencia estructural pesa lo que tiene que
 * pesar y el ruido de borde se queda chico.
 *
 * LA OCUPACION ES EN GRISES. El texto de un diagrama mide 12 px de alto y viene
 * suavizado: el borde de cada letra son medios tonos, y redondearlos a tinta o a
 * fondo tira justo lo que separa una letra de otra.
 */
final class Rejilla {

    private Rejilla() { }

    /**
     * Lleva la figura que hay en `[x, x+ancho) x [y, y+alto)` a una caja de
     * `anchoDestino x altoDestino`.
     *
     * @param tinta ocupacion por pixel, 0..1, sobre una imagen de `paso` de ancho
     */
    static float[] de(float[] tinta, int paso, int x, int y, int ancho, int alto,
                      int anchoDestino, int altoDestino) {
        float[] destino = new float[anchoDestino * altoDestino];
        if (ancho <= 0 || alto <= 0 || anchoDestino <= 0 || altoDestino <= 0) return destino;

        for (int fila = 0; fila < altoDestino; fila++) {
            double v0 = fila * alto / (double) altoDestino;
            double v1 = (fila + 1) * alto / (double) altoDestino;
            for (int columna = 0; columna < anchoDestino; columna++) {
                double u0 = columna * ancho / (double) anchoDestino;
                double u1 = (columna + 1) * ancho / (double) anchoDestino;
                destino[fila * anchoDestino + columna] =
                        promedio(tinta, paso, x, y, ancho, alto, u0, u1, v0, v1);
            }
        }
        return destino;
    }

    /**
     * Promedia la region de la figura original que le toca a una celda.
     *
     * Hay dos casos y los dos importan: al REDUCIR --el glifo de la fuente, que
     * se dibuja tres veces mas grande a proposito-- la region tiene varios
     * pixeles y hay que promediarlos, o se pierden trazos enteros por
     * submuestreo; al AMPLIAR, la region no llega a contener ningun centro de
     * pixel y hay que caer al mas cercano, o la caja sale con agujeros.
     */
    private static float promedio(float[] tinta, int paso, int x, int y, int ancho, int alto,
                                  double u0, double u1, double v0, double v1) {
        int desde = (int) Math.floor(u0);
        int hasta = (int) Math.ceil(u1) - 1;
        int arriba = (int) Math.floor(v0);
        int abajo = (int) Math.ceil(v1) - 1;

        double suma = 0;
        int cuenta = 0;
        for (int py = Math.max(0, arriba); py <= Math.min(alto - 1, abajo); py++) {
            for (int px = Math.max(0, desde); px <= Math.min(ancho - 1, hasta); px++) {
                double centroU = px + 0.5;
                double centroV = py + 0.5;
                if (centroU < u0 || centroU >= u1 || centroV < v0 || centroV >= v1) continue;
                suma += tinta[(y + py) * paso + (x + px)];
                cuenta++;
            }
        }
        if (cuenta > 0) return (float) (suma / cuenta);

        int px = Math.max(0, Math.min(ancho - 1, (int) Math.floor((u0 + u1) / 2)));
        int py = Math.max(0, Math.min(alto - 1, (int) Math.floor((v0 + v1) / 2)));
        return tinta[(y + py) * paso + (x + px)];
    }

    /** Cuanto cuesta correr la comparacion un pixel para hacerla calzar. */
    private static final double COSTO_DEL_CORRIMIENTO = 0.015;

    /**
     * Cuanto se parecen dos cajas del mismo tamano: 0 identicas, 1 sin nada en comun.
     *
     * ES LA TINTA COMPARTIDA SOBRE LA TINTA TOTAL --Jaccard difuso-- y no la
     * diferencia sobre la suma. La primera version usaba lo segundo y tenia un
     * SESGO que se veia en las lecturas: al dividir por la tinta de los dos, un
     * candidato con mas tinta agrandaba el denominador y bajaba su propio costo,
     * asi que las letras gordas le ganaban a las finas. Medido, eso leia la t como
     * b, la t como f y un par de parentesis como Q. Con la tinta compartida, lo que
     * el candidato tiene de mas tambien cuenta en contra.
     *
     * Se prueban corrimientos de un pixel en las dos direcciones y gana el mejor,
     * pero corrigado cuesta un poco: el borde del glifo leido se define por un
     * umbral y puede quedar corrido respecto del dibujado --sin tolerancia, ese
     * pixel cuesta mas que la diferencia entre dos letras parecidas--, pero en un
     * glifo de 6 px de ancho un corrimiento libre alinea cualquier cosa con
     * cualquier cosa.
     */
    static double distancia(float[] a, float[] b, int ancho, int alto) {
        double mejor = Double.MAX_VALUE;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                double costo = distanciaCon(a, b, ancho, alto, dx, dy)
                        + COSTO_DEL_CORRIMIENTO * (Math.abs(dx) + Math.abs(dy));
                mejor = Math.min(mejor, costo);
            }
        }
        return mejor;
    }

    private static double distanciaCon(float[] a, float[] b, int ancho, int alto, int dx, int dy) {
        double compartida = 0;
        double total = 0;
        for (int y = 0; y < alto; y++) {
            for (int x = 0; x < ancho; x++) {
                float va = a[y * ancho + x];
                int bx = x + dx;
                int by = y + dy;
                float vb = bx < 0 || by < 0 || bx >= ancho || by >= alto
                        ? 0 : b[by * ancho + bx];
                compartida += Math.min(va, vb);
                total += Math.max(va, vb);
            }
        }
        return total <= 0 ? 1 : 1 - compartida / total;
    }
}
