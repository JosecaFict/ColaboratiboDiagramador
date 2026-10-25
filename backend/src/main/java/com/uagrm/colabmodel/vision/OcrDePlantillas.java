package com.uagrm.colabmodel.vision;

import com.uagrm.colabmodel.vision.DeteccionPropuesta.RolDeTexto;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * Lee el texto de un recorte comparandolo contra glifos dibujados en memoria.
 *
 * ES EL NIVEL 2 SIN MODELO Y SIN RED. El texto de un diagrama exportado no es
 * escritura: es una fuente tipografica renderizada. Entonces no hay que
 * reconocerlo, hay que volver a dibujarlo y comparar --que es aritmetica sobre
 * unos cientos de pixeles, el mismo criterio con el que el nivel 1 resuelve la
 * geometria en vez de traer OpenCV--. La lectura sale DETERMINISTICA: la misma
 * imagen da siempre el mismo texto, y ese texto se puede explicar.
 *
 * QUE LEE Y QUE NO:
 *   - texto renderizado --screenshot, PDF exportado, captura de proyector--: si;
 *   - manuscrito: NO, y no hay forma de que si. Para eso esta el nivel 2b por
 *     modelo, que corre despues y se queda con lo que aca no salio.
 *
 * LOS TRES PASOS, y el orden importa:
 *
 *   1. SEGMENTAR. Se proyecta la tinta sobre el eje x: cada grupo de columnas
 *      contiguas con tinta es un glifo, y un hueco ancho es un espacio. Funciona
 *      porque el recorte que da el nivel 1 ya es UN renglon --no hay que
 *      encontrar lineas, ni bloques, ni columnas--, que es justo el trabajo que
 *      la geometria le ahorra al reconocimiento.
 *   2. ACERTAR EL TAMANO. Se barren tamanos de fuente sobre unos pocos glifos de
 *      sonda y gana el que mejor explica el renglon. No es un detalle: los glifos
 *      se comparan a su tamano real, asi que acertar el tamano pone en escala
 *      todas las letras a la vez, y el alto en pixeles --9 para una minuscula, 12
 *      para una mayuscula-- ya separa la C de la c sin ningun rasgo adicional.
 *   3. CLASIFICAR cada glifo con esa fuente, y promediar las confianzas.
 *
 * ANTE LA DUDA, null. Si la confianza media no llega al minimo, o si UN glifo
 * quedo irreconocible, no se devuelve nada. Es la misma doctrina que en el
 * clasificador de simbolos y por la misma razon: un recorte sin leer es una celda
 * que el usuario completa en el panel, pero "Estudlante" con una ele es un nombre
 * de tabla que nadie va a mirar de nuevo. El costo de un null se ve; el de una
 * letra cambiada, no.
 *
 * LO QUE SE CONFUNDE igual: I contra l contra 1, y O contra 0. En casi cualquier
 * fuente sin serifas son el mismo dibujo, asi que ningun metodo --ni este ni un
 * modelo-- puede separarlas sin entender la palabra. El alfabeto por rol acota el
 * dano: en una cardinalidad no existen las letras.
 */
final class OcrDePlantillas {

    private OcrDePlantillas() { }

    /** @param confianza 0..1, promedio de lo seguro que estuvo cada glifo */
    record Lectura(String texto, double confianza) { }

    /** Por debajo de esto no se devuelve texto: se prefiere el hueco. */
    private static final double MIN_CONFIANZA = 0.55;

    /** Un solo glifo tan malo como esto invalida el renglon entero. */
    private static final double MIN_CONFIANZA_GLIFO = 0.30;

    /** Menos alto que esto en la imagen de trabajo no es texto, es ruido. */
    private static final int MIN_ALTO = 6;

    /** Tope de glifos por recorte: un renglon de un diagrama no tiene mas. */
    private static final int MAX_GLIFOS = 60;

    /** Glifos que se usan para elegir la fuente antes de leer todo el renglon. */
    private static final int GLIFOS_DE_SONDA = 5;

    /**
     * Rango de tamanos de fuente que se prueba, en proporcion al alto del renglon.
     *
     * El alto de un renglon depende de que letras tenga --"nose" es mas bajo que
     * "Nombre: String"-- asi que no hay cuenta exacta entre uno y otro. Se barren
     * tamanos enteros y gana el que mejor explica la tinta; el que no acierta no
     * necesita descartarse a mano, porque sus glifos no pasan el filtro de tamano.
     */
    private static final double PROPORCION_MINIMA = 1.00;
    private static final double PROPORCION_MAXIMA = 2.10;
    private static final int MAX_TAMANOS = 14;

    /** Tolerancias en PIXELES para que un glifo del atlas sea candidato. */
    private static final int TOLERANCIA_ALTO = 1;
    private static final int TOLERANCIA_DESCENSO = 1;

    /** De costo a confianza. Un buen calce ronda 0.10; uno de otra letra, 0.35. */
    private static final double COSTO_MALO = 0.60;

    /**
     * @param recorte el pedacito que publico el nivel 1, ya recortado al renglon
     * @param rol     que se espera encontrar. Decide el alfabeto, y eso es media
     *                precision: en una cardinalidad no hay letras, y en un nombre
     *                de clase no hay parentesis
     * @return la lectura, o null si no hay nada legible o no alcanza la confianza
     */
    static Lectura leer(BufferedImage recorte, RolDeTexto rol) {
        if (recorte == null || !AtlasDeGlifos.disponible()) return null;

        Binarizacion.Recorte tinta = Binarizacion.deRecorte(recorte);
        if (tinta.ancho() < 3 || tinta.alto() < MIN_ALTO) return null;

        List<Figura> figuras = segmentar(tinta);
        if (figuras.isEmpty() || figuras.size() > MAX_GLIFOS) return null;

        int lineaBase = lineaBaseDe(figuras);
        int alturaMayuscula = alturaDeReferencia(figuras, lineaBase);
        if (alturaMayuscula < MIN_ALTO) return null;

        String alfabeto = alfabetoDe(rol);
        AtlasDeGlifos atlas = elegirFuente(figuras, alturaMayuscula, lineaBase, alfabeto);
        if (atlas == null) return null;

        StringBuilder texto = new StringBuilder();
        double suma = 0;
        double minima = 1;
        int anterior = -1;
        for (Figura f : figuras) {
            // El espacio se decide por el hueco, no comparando: no hay glifo que
            // dibujar para el.
            if (anterior >= 0 && f.x - anterior >= alturaMayuscula * 0.30) texto.append(' ');
            Calce calce = clasificar(f, atlas, lineaBase, alfabeto);
            if (calce == null) return null;
            texto.append(calce.caracter());
            suma += calce.confianza();
            minima = Math.min(minima, calce.confianza());
            anterior = f.x + f.ancho;
        }

        double confianza = suma / figuras.size();
        if (confianza < MIN_CONFIANZA || minima < MIN_CONFIANZA_GLIFO) return null;

        String limpio = texto.toString().trim().replaceAll("\\s{2,}", " ");
        // Una cardinalidad no lleva espacios, y el hueco entre el 1 y los dos
        // puntos de "1..*" es mas ancho que el de dos letras seguidas, asi que la
        // segmentacion lo lee como uno. Dejarlo pasar ensuciaria la multiplicidad,
        // que es la que decide si la FK sale NOT NULL y si hace falta tabla puente.
        if (rol == RolDeTexto.CARDINALIDAD) limpio = limpio.replace(" ", "");
        return limpio.isEmpty() ? null : new Lectura(limpio, confianza);
    }

    // ------------------------------------------------------- SEGMENTACION

    /**
     * Columnas contiguas con tinta: cada grupo es un glifo candidato.
     *
     * No se tolera ningun hueco interno a proposito. Ninguna letra latina tiene
     * una columna vacia en el medio, asi que tolerar huecos solo serviria para
     * pegar dos letras distintas en un glifo que despues no calza con nada.
     */
    private static List<Figura> segmentar(Binarizacion.Recorte tinta) {
        List<Figura> figuras = new ArrayList<>();
        int inicio = -1;
        for (int x = 0; x < tinta.ancho(); x++) {
            boolean hay = false;
            for (int y = 0; y < tinta.alto() && !hay; y++) hay = tinta.tinta(x, y);
            if (hay && inicio < 0) {
                inicio = x;
            } else if (!hay && inicio >= 0) {
                agregar(figuras, tinta, inicio, x - 1);
                inicio = -1;
                if (figuras.size() > MAX_GLIFOS) return figuras;
            }
        }
        if (inicio >= 0) agregar(figuras, tinta, inicio, tinta.ancho() - 1);
        return figuras;
    }

    private static void agregar(List<Figura> figuras, Binarizacion.Recorte tinta, int x0, int x1) {
        int y0 = Integer.MAX_VALUE;
        int y1 = -1;
        for (int y = 0; y < tinta.alto(); y++) {
            for (int x = x0; x <= x1; x++) {
                if (!tinta.tinta(x, y)) continue;
                if (y < y0) y0 = y;
                if (y > y1) y1 = y;
            }
        }
        if (y1 < y0) return;
        int ancho = x1 - x0 + 1;
        int alto = y1 - y0 + 1;
        figuras.add(new Figura(x0, y0, ancho, alto,
                Rejilla.de(tinta.ocupacion(), tinta.ancho(), x0, y0, ancho, alto, ancho, alto)));
    }

    /**
     * El alto que se toma como referencia de escala del renglon.
     *
     * Es el glifo mas alto de los que SE APOYAN EN LA LINEA BASE, y no el mas alto
     * de todos. El parentesis es la razon: sube mas que una mayuscula y ademas baja
     * de la base, asi que tomandolo como referencia un renglon como "getNombre()"
     * quedaba con una escala dos puntos mas grande que la real, ninguna fuente
     * cerraba y el renglon entero se devolvia sin leer. La cola de una g o de una p
     * hace lo mismo por abajo.
     */
    private static int alturaDeReferencia(List<Figura> figuras, int lineaBase) {
        int apoyadas = 0;
        int todas = 0;
        for (Figura f : figuras) {
            todas = Math.max(todas, f.alto);
            if (Math.abs(f.y + f.alto - lineaBase) <= 1) apoyadas = Math.max(apoyadas, f.alto);
        }
        // Si NADA se apoya en la base --"pg", que son dos colas-- no queda mas que
        // el maximo de todos: es peor referencia, pero es la unica que hay.
        return apoyadas > 0 ? apoyadas : todas;
    }

    /**
     * La linea base es la MEDIANA de los bordes de abajo.
     *
     * Casi todos los glifos se apoyan en ella y solo las colas la pasan, asi que
     * la mediana la encuentra sin saber que letras hay. Tomar el borde inferior
     * del renglon no serviria: en "grupo" ese borde es la cola de la g y de la p,
     * y entonces ninguna letra pareceria apoyada en la base.
     */
    private static int lineaBaseDe(List<Figura> figuras) {
        int[] bases = figuras.stream().mapToInt(f -> f.y + f.alto).sorted().toArray();
        return bases[bases.length / 2];
    }

    // -------------------------------------------------------- COMPARACION

    /**
     * Barre tamanos y estilos sobre unos pocos glifos, y elige el mejor atlas.
     *
     * La sonda son los primeros glifos y no todos porque el costo es lineal en
     * glifos por combinacion: con treinta combinaciones, sondear cinco cuesta como
     * leer el renglon una vez mas, y sondear veinte cuesta como leerlo cuatro.
     *
     * Una combinacion que no le encuentra candidato a la mitad de la sonda queda
     * descartada: si el tamano esta equivocado, los altos en pixeles no coinciden
     * con ninguna letra y eso se nota antes de comparar ninguna forma.
     */
    private static AtlasDeGlifos elegirFuente(List<Figura> figuras, int alturaMayuscula,
                                              int lineaBase, String alfabeto) {
        List<Figura> sonda = figuras.subList(0, Math.min(GLIFOS_DE_SONDA, figuras.size()));
        int desde = Math.max(MIN_ALTO, (int) Math.round(alturaMayuscula * PROPORCION_MINIMA));
        int hasta = Math.min(desde + MAX_TAMANOS - 1,
                (int) Math.round(alturaMayuscula * PROPORCION_MAXIMA));

        AtlasDeGlifos mejor = null;
        double mejorCosto = Double.MAX_VALUE;
        for (int tamano = desde; tamano <= hasta; tamano++) {
            for (AtlasDeGlifos atlas : AtlasDeGlifos.para(tamano, alturaMayuscula)) {
                double suma = 0;
                int leidos = 0;
                for (Figura f : sonda) {
                    Calce calce = clasificar(f, atlas, lineaBase, alfabeto);
                    if (calce == null) continue;
                    suma += calce.costo();
                    leidos++;
                }
                if (leidos * 2 < sonda.size()) continue;
                // Los no leidos se pagan como si fueran el peor calce posible: una
                // fuente que explica tres glifos de cinco no puede ganarle a una
                // que explica los cinco un poco peor.
                double costo = (suma + (sonda.size() - leidos) * COSTO_MALO) / sonda.size();
                if (costo < mejorCosto) {
                    mejorCosto = costo;
                    mejor = atlas;
                }
            }
        }
        return mejor;
    }

    /**
     * El glifo del atlas que mejor explica esta figura.
     *
     * El filtro por tamano en PIXELES es lo que hace barato y preciso todo esto:
     * baja los candidatos de noventa a unos pocos --sin eso, leer un diagrama de
     * veinte clases pasaria de milisegundos a segundos-- y ademas resuelve solo la
     * ambiguedad entre mayuscula y minuscula, que a igual silueta es una simple
     * diferencia de alto.
     */
    private static Calce clasificar(Figura figura, AtlasDeGlifos atlas, int lineaBase,
                                    String alfabeto) {
        int descenso = figura.y + figura.alto - lineaBase;
        // Un solo pixel: con el tamano de fuente acertado, el ancho de la letra
        // tiene que dar. Con una tolerancia proporcional --dos pixeles sobre seis--
        // entraban letras de otro ancho y la t se leia como b.
        int toleranciaAncho = 1;

        char mejor = 0;
        double mejorCosto = Double.MAX_VALUE;
        for (AtlasDeGlifos.Glifo candidato : atlas.glifos()) {
            // El atlas trae todo el alfabeto; el rol elige que parte cuenta. Es la
            // misma idea que la consigna por rol del nivel 2b, y es gratis.
            if (alfabeto.indexOf(candidato.caracter()) < 0) continue;
            if (Math.abs(candidato.alto() - figura.alto) > TOLERANCIA_ALTO) continue;
            if (Math.abs(candidato.descenso() - descenso) > TOLERANCIA_DESCENSO) continue;
            if (Math.abs(candidato.ancho() - figura.ancho) > toleranciaAncho) continue;

            int anchoFuente = candidato.paso();
            int altoFuente = candidato.forma().length / anchoFuente;
            float[] comparable = Rejilla.de(candidato.forma(), anchoFuente, 0, 0,
                    anchoFuente, altoFuente, figura.ancho, figura.alto);
            double costo = Rejilla.distancia(figura.forma, comparable, figura.ancho, figura.alto);
            if (costo < mejorCosto) {
                mejorCosto = costo;
                mejor = candidato.caracter();
            }
        }
        if (mejor == 0) return null;
        double confianza = Math.max(0, Math.min(1, 1 - mejorCosto / COSTO_MALO));
        return new Calce(mejor, mejorCosto, confianza);
    }

    /**
     * El alfabeto que puede aparecer segun lo que el nivel 1 espera ahi.
     *
     * Es la misma idea que la consigna distinta por rol del nivel 2b: la imagen no
     * cambia, pero lo que puede decir si. Y cada caracter que se saca del alfabeto
     * es una confusion menos.
     */
    private static String alfabetoDe(RolDeTexto rol) {
        return switch (rol) {
            // Un "0..1" o un "1..*" no tiene ni una letra, y sacarlas evita la peor
            // confusion de todas: el 0 contra la O y el 1 contra la l.
            case CARDINALIDAD -> AtlasDeGlifos.DIGITOS + ".*";
            case NOMBRE_CLASE, ROL, NOMBRE_RELACION ->
                    AtlasDeGlifos.LETRAS + AtlasDeGlifos.ACENTOS + AtlasDeGlifos.DIGITOS + "_";
            default -> AtlasDeGlifos.LETRAS + AtlasDeGlifos.ACENTOS + AtlasDeGlifos.DIGITOS
                    + AtlasDeGlifos.PUNTUACION;
        };
    }

    /** Un glifo encontrado en el recorte, con su caja y su tinta. */
    private record Figura(int x, int y, int ancho, int alto, float[] forma) { }

    private record Calce(char caracter, double costo, double confianza) { }
}
