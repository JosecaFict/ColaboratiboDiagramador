package com.uagrm.colabmodel.vision;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Los glifos de una fuente, dibujados en memoria y listos para comparar.
 *
 * ES LA MITAD DEL OCR SIN MODELO. La idea completa cabe en una linea: el texto de
 * un diagrama EXPORTADO --un screenshot, un PDF, una captura de proyector-- no es
 * escritura, es una fuente tipografica renderizada, y una fuente se puede volver
 * a dibujar aca mismo y comparar contra lo que llego en la imagen. No hay nada
 * que aprender ni ningun peso que descargar: `java.awt.Font` ya viene en el JDK y
 * el resto es aritmetica sobre unos cientos de pixeles.
 *
 * POR QUE ESTO Y NO UN MODELO. Comparar con plantillas tiene un defecto grande
 * --no lee nada manuscrito-- y una virtud que en este sistema vale mas que el
 * defecto: NO INVENTA. Cuando no reconoce, el costo se dispara y la lectura se
 * descarta; un modelo chico, en cambio, contesta siempre, y su modo de falla es
 * un nombre plausible que nadie va a revisar. Para el caso que el ADR-023 declara
 * el importante --la imagen digital limpia-- esto acierta, y ademas corre en
 * milisegundos sin servidor y sin GPU.
 *
 * CADA ATLAS ES UNA FUENTE A UN TAMANO EXACTO, y eso no es un detalle de
 * implementacion: es lo que hace que la comparacion discrimine. Los glifos se
 * comparan a su tamano real en pixeles, asi que el alto de la figura --9 px para
 * una minuscula, 12 para una mayuscula-- ya separa la C de la c sin ningun rasgo
 * adicional. Por eso se prueban varios tamanos y gana el que mejor explica el
 * renglon: acertar el tamano es acertar la escala de todas las letras a la vez.
 *
 * LAS FUENTES SON LAS LOGICAS DEL JDK (SansSerif, Serif), que en cualquier
 * escritorio resuelven a Arial/Helvetica y Times. En un contenedor pelado puede no
 * haber NINGUNA fuente instalada; para ese caso alcanza con dejar un .ttf en
 * `src/main/resources/fuentes/ocr.ttf` y se usa ese. Si no hay ni fuentes ni ttf,
 * el atlas sale invalido, el enriquecedor se declara no disponible y la deteccion
 * sigue por el nivel 1 como siempre: degradar, no fallar.
 */
final class AtlasDeGlifos {

    /** Fuente propia, si alguien la deja ahi. Tiene prioridad sobre las del sistema. */
    private static final String TTF_PROPIO = "/fuentes/ocr.ttf";

    private static final String[] FAMILIAS = { Font.SANS_SERIF, Font.SERIF };
    private static final int[] ESTILOS = { Font.PLAIN, Font.BOLD };

    /**
     * Todo lo que el OCR puede llegar a reconocer.
     *
     * Los acentos y la ene estan porque el proyecto se escribe en espanol y un
     * nombre de clase puede llevarlos. Lo que NO esta es cualquier cosa que no
     * aparece en un diagrama de clases: cuanto mas corto el alfabeto, menos
     * ocasiones de confundir una letra con otra.
     */
    static final String LETRAS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    static final String ACENTOS = "áéíóúñ"
            + "ÁÉÍÓÚÑ";
    static final String DIGITOS = "0123456789";
    static final String PUNTUACION = "_:.,;()[]<>+-#~*/=&";

    /**
     * Un atlas trae SIEMPRE todo el alfabeto, y el rol filtra despues.
     *
     * Antes se construia un atlas por alfabeto --uno para nombres, otro para
     * atributos, otro para cardinalidades-- y eso triplicaba el trabajo de
     * renderizado para ahorrar un puñado de comparaciones. Dibujar noventa glifos
     * cuesta; descartar por rol un candidato ya dibujado no cuesta nada.
     */
    static final String COMPLETO = LETRAS + ACENTOS + DIGITOS + PUNTUACION;

    /** Menos glifos utiles que esto y la fuente no sirve para leer nada. */
    private static final int MIN_GLIFOS = 40;

    /**
     * Cada letra entra DOS VECES: dibujada al tamano final y dibujada al triple.
     *
     * Son dos rasterizados distintos de la misma letra y ninguno de los dos gana
     * siempre, porque depende de con que se hizo la captura:
     *
     *   - al TAMANO FINAL, la fuente pasa por el mismo hinting que aplica cualquier
     *     motor al dibujar texto chico --los trazos se corren al pixel entero-- asi
     *     que se parece mucho a un screenshot hecho en la misma maquina;
     *   - AL TRIPLE y reducido promediando, se obtiene la forma ideal de la letra
     *     con medios tonos suaves, que es lo que se parece a un PDF exportado o a
     *     una captura escalada.
     *
     * Estan los dos y gana el que menos cueste. Medido sobre texto de 12 a 14 px
     * --el tamano de un diagrama real-- con solo el triple habia confusiones
     * sistematicas de la t por la f y de la o por el cero.
     */
    private static final int[] SOBREMUESTREOS = { 1, 3 };

    private static final Map<String, AtlasDeGlifos> CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Escala> ESCALAS = new ConcurrentHashMap<>();

    /** Sin fuentes no hay OCR, y eso se decide una sola vez por proceso. */
    private static volatile Boolean hayFuentes = null;

    /**
     * Un caracter ya dibujado, medido y recortado a su propia caja.
     *
     * @param forma    ocupacion de tinta 0..1, de `ancho` x `alto`, a sobremuestreo
     * @param ancho    lo que mide la figura EN PIXELES al tamano de este atlas
     * @param descenso cuantos pixeles baja de la linea base. Vale 0 para casi
     *                 todo y 2 o 3 para las que tienen cola: es lo que separa la p
     *                 de la P y la q del 9, que por silueta son casi iguales
     */
    record Glifo(char caracter, float[] forma, int paso, int ancho, int alto, int descenso) { }

    private final String nombre;
    private final int tamano;
    private final List<Glifo> glifos;

    private AtlasDeGlifos(String nombre, int tamano, List<Glifo> glifos) {
        this.nombre = nombre;
        this.tamano = tamano;
        this.glifos = glifos;
    }

    /** Como se llama esta fuente, para poder decirlo en un diagnostico. */
    String nombre() {
        return nombre + " " + tamano + "px";
    }

    List<Glifo> glifos() {
        return glifos;
    }

    /**
     * Las fuentes de este tamano que pueden explicar un renglon de `alturaRenglon`.
     *
     * EL FILTRO DE ESCALA SE APLICA ANTES DE CONSTRUIR NADA, y eso es lo que hace
     * usable el barrido de tamanos. Medir el alto de la H y de la x de una fuente
     * son dos glifos; construir su atlas son ciento treinta. Filtrando despues, una
     * sola imagen renderizaba mas de cincuenta atlas y se comia el presupuesto de
     * tiempo en calentamiento: en una prueba de dos clases, la segunda llegaba sin
     * texto porque el reloj ya se habia agotado leyendo la primera.
     *
     * Se admiten los DOS casos posibles, y hay que admitir los dos: o el glifo mas
     * alto del renglon es una mayuscula --o una ascendente, que mide casi lo
     * mismo--, o el renglon no tiene ninguna de las dos y entonces lo mas alto es
     * una minuscula redonda, como en "nose".
     *
     * ES UN FILTRO Y NO UNA PENALIZACION a proposito. Sin el, elegir la fuente por
     * costo tiene una degeneracion fea: la t de un texto de 14 px calza mejor con la
     * b de una fuente de otro cuerpo que con su propia t, porque las dos son
     * ascendentes del mismo alto. Medido, eso leia "Estudiante" como "Esbudianbe".
     * Exigir que la escala global cierre saca de la comparacion todas las fuentes
     * con las que ese cambalache es posible.
     */
    static List<AtlasDeGlifos> para(int tamano, int alturaRenglon) {
        if (!disponible()) return List.of();
        List<AtlasDeGlifos> atlas = new ArrayList<>(FAMILIAS.length * ESTILOS.length);
        for (String familia : FAMILIAS) {
            for (int estilo : ESTILOS) {
                Escala escala = escalaDe(familia, estilo, tamano);
                if (escala == null || !escala.explica(alturaRenglon)) continue;
                AtlasDeGlifos uno = de(familia, estilo, tamano);
                if (uno != null) atlas.add(uno);
            }
        }
        return atlas;
    }

    /**
     * El alto de la H y de la x de una fuente, sin dibujar el alfabeto entero.
     *
     * Es la medida que decide si vale la pena construir el atlas, asi que tiene que
     * costar dos glifos y no ciento treinta. Se cachea igual: el barrido de tamanos
     * la pide varias veces por recorte.
     */
    private static Escala escalaDe(String familia, int estilo, int tamano) {
        return ESCALAS.computeIfAbsent(familia + "/" + estilo + "/" + tamano, clave -> {
            Font fuente = base(familia).deriveFont(estilo, (float) tamano);
            Forma mayuscula = dibujar(fuente, 'H');
            Forma equis = dibujar(fuente, 'x');
            return mayuscula == null || equis == null
                    ? Escala.NINGUNA
                    : new Escala(mayuscula.alto, equis.alto);
        }).valida();
    }

    /** Las dos alturas de referencia de una fuente a un tamano. */
    private record Escala(int mayuscula, int equis) {

        static final Escala NINGUNA = new Escala(0, 0);

        Escala valida() {
            return mayuscula <= 0 || equis <= 0 ? null : this;
        }

        boolean explica(int alto) {
            return Math.abs(mayuscula - alto) <= 1 || Math.abs(equis - alto) <= 1;
        }
    }

    /** Si este proceso puede dibujar texto. En un contenedor sin fuentes, no. */
    static boolean disponible() {
        Boolean cache = hayFuentes;
        if (cache != null) return cache;
        boolean puede;
        try {
            // Alcanza con poder medir una fuente: si AWT no dibuja ni una H, no hay
            // OCR posible, y construir un atlas completo para averiguarlo seria
            // pagar el calentamiento antes de saber si sirve para algo.
            puede = escalaDe(Font.SANS_SERIF, Font.PLAIN, 16) != null;
        } catch (RuntimeException | LinkageError e) {
            puede = false;
        }
        hayFuentes = puede;
        return puede;
    }

    private static AtlasDeGlifos de(String familia, int estilo, int tamano) {
        String clave = familia + "/" + estilo + "/" + tamano;
        AtlasDeGlifos cacheado = CACHE.get(clave);
        if (cacheado != null) return cacheado;

        AtlasDeGlifos nuevo = construir(familia, estilo, tamano);
        if (nuevo == null) return null;
        CACHE.put(clave, nuevo);
        return nuevo;
    }

    private static AtlasDeGlifos construir(String familia, int estilo, int tamano) {
        List<Glifo> glifos = new ArrayList<>(COMPLETO.length() * SOBREMUESTREOS.length);
        for (int sobremuestreo : SOBREMUESTREOS) {
            agregarGlifos(base(familia).deriveFont(estilo, (float) tamano * sobremuestreo),
                    sobremuestreo, COMPLETO, glifos);
        }
        if (glifos.size() < MIN_GLIFOS) return null;
        return new AtlasDeGlifos(nombreDe(familia, estilo), tamano, List.copyOf(glifos));
    }

    private static void agregarGlifos(Font fuente, int sobremuestreo, String alfabeto,
                                      List<Glifo> glifos) {
        // La H fija la linea base y la altura de mayuscula de esta fuente. Sin esa
        // referencia, el descenso de cada glifo no se puede comparar con nada.
        Forma mayuscula = dibujar(fuente, 'H');
        if (mayuscula == null) return;
        int base = mayuscula.y + mayuscula.alto;

        for (int i = 0; i < alfabeto.length(); i++) {
            char c = alfabeto.charAt(i);
            Forma forma = dibujar(fuente, c);
            if (forma == null) continue;   // el espacio no tiene tinta y no se compara

            // De sobremuestreo a pixeles reales: es el tamano con el que se va a
            // comparar contra lo que se leyo de la imagen.
            int ancho = Math.max(1, (int) Math.round(forma.ancho / (double) sobremuestreo));
            int alto = Math.max(1, (int) Math.round(forma.alto / (double) sobremuestreo));
            int descenso = (int) Math.round((forma.y + forma.alto - base) / (double) sobremuestreo);
            glifos.add(new Glifo(c, forma.recorte(), forma.ancho, ancho, alto, descenso));
        }
    }

    private static String nombreDe(String familia, int estilo) {
        return familia + (estilo == Font.BOLD ? " negrita" : "");
    }

    /**
     * La fuente con la que se dibuja el atlas.
     *
     * Primero un .ttf propio si el despliegue dejo uno --un contenedor pelado no
     * trae ninguna fuente instalada y ahi las logicas del JDK no alcanzan--, y si
     * no, la logica, que en cualquier escritorio resuelve a la de siempre.
     */
    private static Font base(String familia) {
        try (InputStream propia = AtlasDeGlifos.class.getResourceAsStream(TTF_PROPIO)) {
            if (propia != null) return Font.createFont(Font.TRUETYPE_FONT, propia);
        } catch (IOException | FontFormatException e) {
            // Un ttf ilegible no puede tumbar la deteccion: se sigue con la logica.
        }
        return new Font(familia, Font.PLAIN, 16);
    }

    /**
     * Dibuja un caracter y devuelve su figura recortada a lo que ocupa.
     *
     * El lienzo es el doble del cuerpo de la fuente y el texto se apoya en el
     * primer cuarto. Se probo apretarlo a una vez y media para recorrer menos
     * pixeles y no conviene: mover el origen cambia el redondeo subpixel del
     * rasterizado, o sea que cambia la forma del glifo, y las lecturas de los
     * casos justos se dieron vuelta. El OCR por plantillas es sensible a eso por
     * construccion, asi que esta medida no se toca por unos milisegundos.
     */
    private static Forma dibujar(Font fuente, char caracter) {
        int lienzo = Math.max(16, fuente.getSize() * 2);
        BufferedImage img = new BufferedImage(lienzo, lienzo, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, lienzo, lienzo);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(fuente);
        g.setColor(Color.BLACK);
        g.drawString(String.valueOf(caracter), lienzo / 4, lienzo * 3 / 4);
        g.dispose();

        // El buffer directo y no getSample(): son cien mil consultas por glifo y
        // ciento treinta glifos por atlas, asi que la diferencia entre leer un
        // byte[] y pasar por el raster es la diferencia entre medio segundo y cinco
        // en la primera lectura de una imagen. Los valores son los mismos.
        byte[] pixeles = ((DataBufferByte) img.getRaster().getDataBuffer()).getData();

        int x0 = lienzo;
        int y0 = lienzo;
        int x1 = -1;
        int y1 = -1;
        float[] tinta = new float[lienzo * lienzo];
        for (int y = 0; y < lienzo; y++) {
            for (int x = 0; x < lienzo; x++) {
                int gris = pixeles[y * lienzo + x] & 0xFF;
                if (gris >= 250) continue;
                tinta[y * lienzo + x] = (250 - gris) / 250f;
                if (x < x0) x0 = x;
                if (y < y0) y0 = y;
                if (x > x1) x1 = x;
                if (y > y1) y1 = y;
            }
        }
        if (x1 < x0 || y1 < y0) return null;
        return new Forma(tinta, lienzo, x0, y0, x1 - x0 + 1, y1 - y0 + 1);
    }

    /** Una figura de tinta con su caja dentro del lienzo donde se dibujo. */
    private record Forma(float[] tinta, int paso, int x, int y, int ancho, int alto) {

        /** La figura sola, sin el lienzo alrededor, para no arrastrar ceros. */
        float[] recorte() {
            float[] recorte = new float[ancho * alto];
            for (int fila = 0; fila < alto; fila++) {
                System.arraycopy(tinta, (y + fila) * paso + x, recorte, fila * ancho, ancho);
            }
            return recorte;
        }
    }
}
