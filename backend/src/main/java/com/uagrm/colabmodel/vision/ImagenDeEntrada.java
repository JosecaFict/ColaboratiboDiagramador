package com.uagrm.colabmodel.vision;

import com.uagrm.colabmodel.vision.DeteccionPropuesta.Caja;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;

/**
 * Una imagen ya validada, acotada y normalizada, lista para el detector.
 *
 * Existe separada del detector porque son dos responsabilidades distintas y
 * una de las dos es una SUPERFICIE DE ATAQUE. Esto es el unico endpoint del
 * sistema que acepta un binario arbitrario de afuera, asi que aca se decide:
 *
 *   - que formatos se aceptan (lista blanca, no lista negra);
 *   - cuanto puede pesar el binario comprimido;
 *   - cuantos PIXELES puede tener una vez descomprimido. Esto es lo que
 *     importa de verdad: un PNG de 60 KB puede declarar 40000x40000 y pedir
 *     6 GB al decodificarlo. Por eso se leen las dimensiones del encabezado
 *     ANTES de decodificar, y si no entran no se decodifica nunca;
 *   - a que tamano se trabaja. Un lado maximo acota el costo de CPU a algo
 *     predecible, que en una notebook sin GPU no es un detalle.
 *
 * La imagen NO SE PERSISTE. Ni en disco ni en la base: entra, se procesa en
 * memoria y se va. Una foto de pizarra puede tener caras, nombres o lo que
 * hubiera alrededor, y lo que no se guarda no se filtra.
 *
 * Tambien es la que sabe RECORTAR, porque el nivel 2 no recibe la imagen
 * entera: recibe pedacitos.
 */
public final class ImagenDeEntrada {

    /** Lista blanca. WebP y AVIF quedan afuera: no los decodifica el JDK sin plugins. */
    public static final Set<String> TIPOS_ACEPTADOS = Set.of("image/png", "image/jpeg");

    /**
     * @param maxBytes  del binario tal como llega
     * @param maxPixeles del binario una vez descomprimido (ancho * alto)
     * @param maxLado    a partir de aca se reduce para acotar el costo de CPU
     */
    public record Limites(long maxBytes, long maxPixeles, int maxLado) {

        public static final Limites POR_DEFECTO = new Limites(8L * 1024 * 1024, 40_000_000L, 1600);

        public Limites {
            if (maxBytes <= 0 || maxPixeles <= 0 || maxLado < 64) {
                throw new IllegalArgumentException("Limites de vision invalidos");
            }
        }
    }

    /** Entrada que no se puede procesar. El controlador la traduce a 4xx, nunca a 500. */
    public static class ImagenInvalida extends IllegalArgumentException {
        public ImagenInvalida(String mensaje) { super(mensaje); }
    }

    private final BufferedImage trabajo;
    private final byte[] gris;
    private final int ancho;
    private final int alto;
    private final int anchoOriginal;
    private final int altoOriginal;
    private final double escala;

    private ImagenDeEntrada(BufferedImage trabajo, byte[] gris,
                            int anchoOriginal, int altoOriginal, double escala) {
        this.trabajo = trabajo;
        this.gris = gris;
        this.ancho = trabajo.getWidth();
        this.alto = trabajo.getHeight();
        this.anchoOriginal = anchoOriginal;
        this.altoOriginal = altoOriginal;
        this.escala = escala;
    }

    /**
     * Valida, decodifica y normaliza.
     *
     * @param contentType el declarado por el cliente. Se comprueba, pero no se
     *                    confia en el: si miente, el lector de ImageIO no
     *                    encuentra decodificador y la imagen se rechaza igual
     * @throws ImagenInvalida ante cualquier entrada que no se pueda procesar
     */
    public static ImagenDeEntrada desde(byte[] bytes, String contentType, Limites limites) {
        if (bytes == null || bytes.length == 0) {
            throw new ImagenInvalida("No llego ninguna imagen");
        }
        if (bytes.length > limites.maxBytes()) {
            throw new ImagenInvalida("La imagen pesa " + (bytes.length / 1024) + " KB; el maximo es "
                    + (limites.maxBytes() / 1024) + " KB");
        }
        String tipo = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT).trim();
        int puntoYComa = tipo.indexOf(';');
        if (puntoYComa >= 0) tipo = tipo.substring(0, puntoYComa).trim();
        if (!TIPOS_ACEPTADOS.contains(tipo)) {
            throw new ImagenInvalida("Formato no aceptado (" + (tipo.isEmpty() ? "sin tipo" : tipo)
                    + "). Se aceptan PNG y JPEG");
        }

        BufferedImage original = decodificarAcotado(bytes, limites);
        int anchoOriginal = original.getWidth();
        int altoOriginal = original.getHeight();
        if (anchoOriginal < 32 || altoOriginal < 32) {
            throw new ImagenInvalida("La imagen es demasiado chica para contener un diagrama");
        }

        double escala = 1.0;
        int ladoMayor = Math.max(anchoOriginal, altoOriginal);
        BufferedImage trabajo = original;
        if (ladoMayor > limites.maxLado()) {
            escala = (double) limites.maxLado() / ladoMayor;
            int w = Math.max(32, (int) Math.round(anchoOriginal * escala));
            int h = Math.max(32, (int) Math.round(altoOriginal * escala));
            trabajo = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = trabajo.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(original, 0, 0, w, h, null);
            g.dispose();
        }

        return new ImagenDeEntrada(trabajo, aGris(trabajo), anchoOriginal, altoOriginal, escala);
    }

    /**
     * Lee ancho y alto del ENCABEZADO y solo entonces decide decodificar.
     *
     * Sin esto, un archivo chico que declara un lienzo enorme reserva toda la
     * memoria del proceso antes de que nadie pueda comprobar nada.
     */
    private static BufferedImage decodificarAcotado(byte[] bytes, Limites limites) {
        try (ImageInputStream entrada = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (entrada == null) throw new ImagenInvalida("No se pudo leer la imagen");
            Iterator<ImageReader> lectores = ImageIO.getImageReaders(entrada);
            if (!lectores.hasNext()) {
                throw new ImagenInvalida("El archivo no es una imagen PNG o JPEG valida");
            }
            ImageReader lector = lectores.next();
            try {
                lector.setInput(entrada, true, true);
                long pixeles = (long) lector.getWidth(0) * lector.getHeight(0);
                if (pixeles > limites.maxPixeles()) {
                    throw new ImagenInvalida("La imagen declara " + pixeles
                            + " pixeles; el maximo es " + limites.maxPixeles());
                }
                BufferedImage img = lector.read(0);
                if (img == null) throw new ImagenInvalida("No se pudo decodificar la imagen");
                return img;
            } finally {
                lector.dispose();
            }
        } catch (ImagenInvalida e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            // OutOfMemoryError no se atrapa a proposito: si igual pasa, es un
            // problema del proceso entero y no algo que este endpoint deba fingir manejar.
            throw new ImagenInvalida("No se pudo leer la imagen: " + e.getClass().getSimpleName());
        }
    }

    /**
     * La misma imagen rotada, para enderezar una foto torcida.
     *
     * El lienzo se agranda a la caja envolvente para no recortar las esquinas,
     * y lo que queda vacio se rellena con el COLOR DE FONDO de la imagen, no
     * con blanco. Rellenar con blanco funcionaba en un diagrama oscuro sobre
     * papel y rompia el caso contrario: en un screenshot de tema oscuro, las
     * cuatro esquinas blancas quedaban del lado de la tinta al invertirse la
     * mascara y aparecian como manchas enormes de trazo.
     *
     * `escala`, `anchoOriginal` y `altoOriginal` se conservan: la rotacion pasa
     * DESPUES del reescalado y no cambia de que imagen venimos.
     */
    public ImagenDeEntrada rotada(double grados) {
        double rad = Math.toRadians(grados);
        double sin = Math.abs(Math.sin(rad));
        double cos = Math.abs(Math.cos(rad));
        int nuevoAncho = (int) Math.ceil(ancho * cos + alto * sin);
        int nuevoAlto = (int) Math.ceil(ancho * sin + alto * cos);

        BufferedImage destino = new BufferedImage(nuevoAncho, nuevoAlto, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = destino.createGraphics();
        g.setColor(colorDeFondo());
        g.fillRect(0, 0, nuevoAncho, nuevoAlto);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        AffineTransform transformacion = new AffineTransform();
        transformacion.translate(nuevoAncho / 2.0, nuevoAlto / 2.0);
        transformacion.rotate(rad);
        transformacion.translate(-ancho / 2.0, -alto / 2.0);
        g.drawImage(trabajo, transformacion, null);
        g.dispose();

        return new ImagenDeEntrada(destino, aGris(destino), anchoOriginal, altoOriginal, escala);
    }

    /** Gris mediano del perimetro: lo que rodea al diagrama es el fondo. */
    private Color colorDeFondo() {
        int[] muestras = new int[2 * (ancho + alto)];
        int n = 0;
        for (int x = 0; x < ancho; x++) {
            muestras[n++] = gris(x, 0);
            muestras[n++] = gris(x, alto - 1);
        }
        for (int y = 0; y < alto; y++) {
            muestras[n++] = gris(0, y);
            muestras[n++] = gris(ancho - 1, y);
        }
        int[] usadas = Arrays.copyOf(muestras, n);
        Arrays.sort(usadas);
        int mediana = usadas[n / 2];
        return new Color(mediana, mediana, mediana);
    }

    /** Luminancia ITU-R BT.601. Un byte por pixel, que es lo unico que el detector mira. */
    private static byte[] aGris(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        byte[] gris = new byte[w * h];
        int[] fila = new int[w];
        for (int y = 0; y < h; y++) {
            img.getRGB(0, y, w, 1, fila, 0, w);
            int base = y * w;
            for (int x = 0; x < w; x++) {
                int rgb = fila[x];
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                gris[base + x] = (byte) ((r * 299 + g * 587 + b * 114) / 1000);
            }
        }
        return gris;
    }

    /** 0..255. Sin signo: el byte crudo de Java llega negativo a partir de 128. */
    public int gris(int x, int y) {
        return gris[y * ancho + x] & 0xFF;
    }

    public int ancho() { return ancho; }

    public int alto() { return alto; }

    public int anchoOriginal() { return anchoOriginal; }

    public int altoOriginal() { return altoOriginal; }

    /**
     * Factor aplicado al ORIGINAL para obtener la de trabajo: 0.25 significa
     * que se redujo a un cuarto, y 1.0 que no se toco.
     *
     * El panel dibuja sobre la imagen original, asi que para llevar una caja
     * de la propuesta a esas coordenadas hay que DIVIDIR por este valor.
     */
    public double escala() { return escala; }

    /**
     * Un pedacito de la imagen de trabajo, para preguntarle al nivel 2.
     *
     * Recorta contra los bordes en vez de fallar: un recorte pedido medio
     * afuera es lo normal cuando el diagrama toca el margen de la foto.
     */
    public BufferedImage recortar(Caja caja) {
        int x = Math.max(0, Math.min(caja.x(), ancho - 1));
        int y = Math.max(0, Math.min(caja.y(), alto - 1));
        int w = Math.max(1, Math.min(caja.ancho(), ancho - x));
        int h = Math.max(1, Math.min(caja.alto(), alto - y));
        return trabajo.getSubimage(x, y, w, h);
    }

    /** El mismo recorte ya en PNG, que es lo que come un modelo de vision. */
    public byte[] recortarComoPng(Caja caja) {
        try {
            ByteArrayOutputStream salida = new ByteArrayOutputStream(4096);
            ImageIO.write(recortar(caja), "png", salida);
            return salida.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo serializar el recorte", e);
        }
    }
}
