package com.uagrm.colabmodel.vision;

import com.uagrm.colabmodel.modelcore.metamodel.RelationKind;
import com.uagrm.colabmodel.vision.DeteccionPropuesta.Caja;

import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Que figura hay en el extremo de una linea, mirando solo la geometria.
 *
 * Las cuatro posibilidades de UML se distinguen por dos rasgos que se miden sin
 * reconocer nada:
 *
 *   - si la figura encierra un HUECO --un rombo relleno no, uno hueco si--;
 *   - donde esta su parte mas ANCHA a lo largo de la linea: un rombo es mas
 *     ancho en el medio y un triangulo en la base.
 *
 * El segundo rasgo no depende de para que lado apunta la linea, que es lo que
 * lo hace util: el rombo es simetrico y el triangulo no, y eso se ve igual
 * mirando el recorte al derecho o al reves.
 *
 * POR QUE VALE LA PENA aunque exista el nivel 2 por modelo: esto no necesita
 * nada instalado. En una maquina sin modelo --que es el caso por defecto-- es
 * lo unico que puede distinguir una composicion de una agregacion, y esa
 * diferencia no es cosmetica: decide si la FK sale con ON DELETE CASCADE o con
 * SET NULL en el esquema derivado.
 *
 * SE MIDE LA FIGURA SOLA, NO EL RECORTE. Lo primero que se hace es quedarse con
 * el trazo CONEXO que pasa por el centro del recorte y borrar todo lo demas. El
 * recorte es un cuadrado ciego alrededor del extremo, asi que ahi adentro suele
 * caer tinta que no es la figura: la cardinalidad de al lado, el borde de la
 * caja, otro conector que pasa cerca. Medir con eso adentro no era un error
 * chico:
 *
 *   - el test del HUECO se inunda desde el borde del recorte, asi que un trazo
 *     ajeno que cruce el cuadrado puede cerrar una region y convertir un rombo
 *     RELLENO en uno HUECO. Eso es exactamente la diferencia entre composicion
 *     y agregacion, o sea entre ON DELETE CASCADE y SET NULL;
 *   - los momentos de segundo orden --de donde sale el eje-- se los lleva la
 *     tinta ajena, asi que el perfil se mide a lo largo del eje equivocado y la
 *     figura sale sin clasificar.
 *
 * Lo unico que NO se puede separar asi es la propia linea del conector, que es
 * el mismo trazo conexo que la figura. Esta bien: los umbrales de abajo estan
 * calibrados contando con ella.
 *
 * ANTE LA DUDA DEVUELVE null. Una relacion sin clasificar queda en ASSOCIATION,
 * que no impone ningun borrado en cascada; una mal clasificada se lleva datos
 * puestos. Los umbrales estan puestos para equivocarse hacia el null.
 */
final class ClasificadorDeSimbolo {

    /** Menos tinta que esto en el recorte es ruido; mas, es un recorte invalido. */
    private static final double MIN_TINTA = 0.02;
    private static final double MAX_TINTA = 0.60;

    /** Un hueco de menos que esto es un pixel perdido del antialiasing. */
    private static final int MIN_HUECO = 6;

    /** Menos pixeles que esto en el trazo conexo no alcanzan para medir nada. */
    private static final int MIN_PIXELES = 20;

    /**
     * Que fraccion del recorte tiene que ocupar la parte mas gruesa.
     *
     * El recorte se hace del tamano del simbolo, asi que un rombo lo cruza de
     * lado a lado y una linea sola mide dos o tres pixeles. Comparar contra el
     * LADO y no contra el grosor del trazo es lo que funciona: la primera
     * version comparaba la parte mas gruesa con la mediana del perfil, y con la
     * figura sola en el recorte la mediana ya es grande --la mitad del rombo
     * es ancha-- asi que ningun simbolo llegaba al umbral y todos daban null.
     */
    private static final double FRACCION_MINIMA = 0.20;
    private static final int MIN_ANCHO_ABSOLUTO = 6;

    private ClasificadorDeSimbolo() { }

    /**
     * @return el tipo de relacion que implica la figura, o null si no se
     *         reconocio ninguna con seguridad
     */
    static RelationKind de(ImagenDeEntrada imagen, Caja caja) {
        if (caja == null || caja.ancho() < 12 || caja.alto() < 12) return null;

        int w = Math.min(caja.ancho(), imagen.ancho() - caja.x());
        int h = Math.min(caja.alto(), imagen.alto() - caja.y());
        if (w < 12 || h < 12) return null;

        boolean[] tinta = trazoDelCentro(mascaraDe(imagen.recortar(caja)), w, h);
        if (tinta == null) return null;

        long conTinta = 0;
        for (boolean t : tinta) if (t) conTinta++;
        double fraccion = conTinta / (double) (w * h);
        if (fraccion < MIN_TINTA || fraccion > MAX_TINTA) return null;

        Perfil perfil = perfilDe(tinta, w, h);
        int minAncho = Math.max(MIN_ANCHO_ABSOLUTO, (int) (Math.min(w, h) * FRACCION_MINIMA));
        // Una linea sola mide el grosor del trazo: no llega ni cerca.
        if (perfil == null || perfil.anchoMaximo < minAncho) return null;

        boolean hueco = tieneHueco(tinta, w, h);
        boolean anchoEnElMedio = perfil.picoRelativo > 0.30 && perfil.picoRelativo < 0.70;

        if (hueco) {
            return anchoEnElMedio ? RelationKind.AGGREGATION : RelationKind.GENERALIZATION;
        }
        // Sin hueco y ancho en el medio: rombo relleno. Sin hueco y ancho en un
        // extremo seria un triangulo relleno, que UML no usa para nada de esto.
        return anchoEnElMedio ? RelationKind.COMPOSITION : null;
    }

    /**
     * Se queda con el trazo CONEXO que pasa por el centro del recorte.
     *
     * El centro es el mejor ancla disponible: el recorte se construye corrido
     * hacia adentro de la linea desde su extremo, asi que en el medio siempre
     * cae la linea, la figura, o las dos. Se busca la tinta mas cercana al
     * centro y se inunda desde ahi en las ocho direcciones --ocho y no cuatro,
     * porque el lado de un rombo es una diagonal y en cuatro direcciones se
     * corta en escalones--.
     *
     * @return la mascara del trazo, o null si no hay tinta suficiente
     */
    private static boolean[] trazoDelCentro(boolean[] tinta, int w, int h) {
        int cx = w / 2;
        int cy = h / 2;
        int semilla = -1;
        long mejor = Long.MAX_VALUE;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (!tinta[y * w + x]) continue;
                long d = (long) (x - cx) * (x - cx) + (long) (y - cy) * (y - cy);
                if (d < mejor) {
                    mejor = d;
                    semilla = y * w + x;
                }
            }
        }
        if (semilla < 0) return null;

        boolean[] trazo = new boolean[w * h];
        Deque<Integer> pendientes = new ArrayDeque<>();
        trazo[semilla] = true;
        pendientes.push(semilla);
        int cuenta = 0;
        while (!pendientes.isEmpty()) {
            int p = pendientes.pop();
            cuenta++;
            int x = p % w;
            int y = p / w;
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    if (dx == 0 && dy == 0) continue;
                    int nx = x + dx;
                    int ny = y + dy;
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
                    int i = ny * w + nx;
                    if (!tinta[i] || trazo[i]) continue;
                    trazo[i] = true;
                    pendientes.push(i);
                }
            }
        }
        return cuenta < MIN_PIXELES ? null : trazo;
    }

    // ------------------------------------------------------------- MEDIDAS

    /**
     * Ancho de la figura a lo largo de su eje principal.
     *
     * El eje sale de los momentos de segundo orden de la tinta: es la
     * direccion en la que la figura se estira, que en un extremo de linea es
     * la direccion de la linea.
     */
    private static Perfil perfilDe(boolean[] tinta, int w, int h) {
        double sumaX = 0;
        double sumaY = 0;
        long n = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (!tinta[y * w + x]) continue;
                sumaX += x;
                sumaY += y;
                n++;
            }
        }
        if (n < 20) return null;
        double cx = sumaX / n;
        double cy = sumaY / n;

        double mxx = 0;
        double myy = 0;
        double mxy = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (!tinta[y * w + x]) continue;
                double dx = x - cx;
                double dy = y - cy;
                mxx += dx * dx;
                myy += dy * dy;
                mxy += dx * dy;
            }
        }
        // Angulo del eje mayor de la elipse de inercia.
        double angulo = 0.5 * Math.atan2(2 * mxy, mxx - myy);
        double cos = Math.cos(angulo);
        double sin = Math.sin(angulo);

        int largo = w + h;
        int[] minPerp = new int[2 * largo];
        int[] maxPerp = new int[2 * largo];
        boolean[] usado = new boolean[2 * largo];
        for (int i = 0; i < usado.length; i++) {
            minPerp[i] = Integer.MAX_VALUE;
            maxPerp[i] = Integer.MIN_VALUE;
        }

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (!tinta[y * w + x]) continue;
                double dx = x - cx;
                double dy = y - cy;
                int t = (int) Math.round(dx * cos + dy * sin) + largo;
                int s = (int) Math.round(-dx * sin + dy * cos);
                if (t < 0 || t >= usado.length) continue;
                usado[t] = true;
                if (s < minPerp[t]) minPerp[t] = s;
                if (s > maxPerp[t]) maxPerp[t] = s;
            }
        }

        int primero = -1;
        int ultimo = -1;
        for (int i = 0; i < usado.length; i++) {
            if (!usado[i]) continue;
            if (primero < 0) primero = i;
            ultimo = i;
        }
        if (primero < 0 || ultimo - primero < 6) return null;

        int anchoMaximo = 0;
        int pico = primero;
        int[] anchos = new int[ultimo - primero + 1];
        for (int i = primero; i <= ultimo; i++) {
            int ancho = usado[i] ? maxPerp[i] - minPerp[i] + 1 : 0;
            anchos[i - primero] = ancho;
            if (ancho > anchoMaximo) {
                anchoMaximo = ancho;
                pico = i;
            }
        }

        double picoRelativo = (pico - primero) / (double) (ultimo - primero);
        return new Perfil(anchoMaximo, picoRelativo);
    }

    /**
     * Si queda fondo encerrado por la tinta.
     *
     * Se inunda desde el borde del recorte: lo que el agua no alcanza esta
     * adentro de la figura. Es lo que separa un rombo hueco de uno relleno.
     */
    private static boolean tieneHueco(boolean[] tinta, int w, int h) {
        boolean[] alcanzado = new boolean[w * h];
        Deque<Integer> pendientes = new ArrayDeque<>();
        for (int x = 0; x < w; x++) {
            encolar(tinta, alcanzado, pendientes, x, 0, w, h);
            encolar(tinta, alcanzado, pendientes, x, h - 1, w, h);
        }
        for (int y = 0; y < h; y++) {
            encolar(tinta, alcanzado, pendientes, 0, y, w, h);
            encolar(tinta, alcanzado, pendientes, w - 1, y, w, h);
        }
        while (!pendientes.isEmpty()) {
            int p = pendientes.pop();
            int x = p % w;
            int y = p / w;
            encolar(tinta, alcanzado, pendientes, x - 1, y, w, h);
            encolar(tinta, alcanzado, pendientes, x + 1, y, w, h);
            encolar(tinta, alcanzado, pendientes, x, y - 1, w, h);
            encolar(tinta, alcanzado, pendientes, x, y + 1, w, h);
        }

        int encerrado = 0;
        for (int i = 0; i < tinta.length; i++) {
            if (!tinta[i] && !alcanzado[i]) encerrado++;
        }
        return encerrado >= MIN_HUECO;
    }

    private static void encolar(boolean[] tinta, boolean[] alcanzado, Deque<Integer> cola,
                                int x, int y, int w, int h) {
        if (x < 0 || y < 0 || x >= w || y >= h) return;
        int i = y * w + x;
        if (tinta[i] || alcanzado[i]) return;
        alcanzado[i] = true;
        cola.push(i);
    }

    /**
     * La mascara de tinta del recorte, con el umbral que calcula Binarizacion.
     *
     * El umbral de un recorte se calcula sobre el recorte --el contraste local no es
     * el de la imagen entera-- pero ese calculo no vive aca: lo comparte con el OCR
     * por plantillas, que necesita exactamente lo mismo. Tener dos Otsu habria
     * significado que ajustar uno arregle una etapa y deje la otra vieja, que es la
     * divergencia silenciosa que esta arquitectura evita en todos lados.
     *
     * Aca alcanza con blanco y negro; el OCR usa los medios tonos de la misma
     * medicion.
     */
    private static boolean[] mascaraDe(BufferedImage recorte) {
        Binarizacion.Recorte medido = Binarizacion.deRecorte(recorte);
        boolean[] tinta = new boolean[medido.ancho() * medido.alto()];
        for (int y = 0; y < medido.alto(); y++) {
            for (int x = 0; x < medido.ancho(); x++) {
                tinta[y * medido.ancho() + x] = medido.tinta(x, y);
            }
        }
        return tinta;
    }

    /**
     * @param anchoMaximo  el ancho de la parte mas gruesa
     * @param picoRelativo 0 = el ensanchamiento esta en un extremo, 0.5 = en el medio
     */
    private record Perfil(int anchoMaximo, double picoRelativo) { }
}
