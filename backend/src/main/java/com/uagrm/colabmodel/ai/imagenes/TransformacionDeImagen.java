package com.uagrm.colabmodel.ai.imagenes;

import com.uagrm.colabmodel.vision.ImagenDeEntrada;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;

/**
 * Recorte y giro sobre la imagen GUARDADA (docs/DISENO-CHAT-IMAGENES.md, 5.2 y O-2).
 *
 * Trabaja con `BufferedImage`, a la resolucion con que se guardo: no usa `ImagenDeEntrada.rotada` ni
 * `recortar`, porque esas actuan sobre la copia de trabajo ya reducida a 1600 px. El orden lo fija el
 * procesador: primero el recorte (en pixeles de la imagen que el usuario ve) y despues el giro sobre ese recorte.
 *
 * El resultado NO se guarda: va al detector y se descarta.
 */
public final class TransformacionDeImagen {

    private TransformacionDeImagen() { }

    /** Lee una imagen que ya esta guardada (ya paso la validacion de entrada) como RGB de 8 bits. */
    public static BufferedImage decodificar(byte[] bytes) {
        try {
            BufferedImage leida = ImageIO.read(new ByteArrayInputStream(bytes));
            if (leida == null) throw new ImagenDeEntrada.ImagenInvalida("No se pudo decodificar la imagen guardada");
            return aRgb(leida);
        } catch (IOException e) {
            throw new ImagenDeEntrada.ImagenInvalida("No se pudo leer la imagen guardada");
        }
    }

    /** El recorte, en pixeles de esta imagen. Quien llama ya comprobo que cabe. */
    public static BufferedImage recortar(BufferedImage imagen, int x, int y, int ancho, int alto) {
        BufferedImage copia = new BufferedImage(ancho, alto, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = copia.createGraphics();
        g.drawImage(imagen.getSubimage(x, y, ancho, alto), 0, 0, null);
        g.dispose();
        return copia;
    }

    /**
     * Gira `grados` en sentido HORARIO (como se ve en pantalla). Un giro recto (multiplo de 90) es exacto, pixel
     * por pixel; uno fino (hasta 10 grados) agranda el lienzo para no cortar las esquinas y rellena con el gris
     * mediano del borde, como hace `ImagenDeEntrada.rotada` (un diagrama oscuro no se llena de esquinas blancas).
     */
    public static BufferedImage girar(BufferedImage imagen, int grados) {
        int g = Math.floorMod(grados, 360);
        if (g == 0) return imagen;
        if (g % 90 == 0) return girarRecto(imagen, g);
        return girarFino(imagen, Math.toRadians(grados));
    }

    private static BufferedImage girarRecto(BufferedImage src, int grados) {
        int w = src.getWidth();
        int h = src.getHeight();
        boolean cuarto = grados != 180;
        BufferedImage dst = new BufferedImage(cuarto ? h : w, cuarto ? w : h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = src.getRGB(x, y);
                switch (grados) {
                    case 90 -> dst.setRGB(h - 1 - y, x, rgb);
                    case 180 -> dst.setRGB(w - 1 - x, h - 1 - y, rgb);
                    default -> dst.setRGB(y, w - 1 - x, rgb);   // 270: 90 en sentido antihorario
                }
            }
        }
        return dst;
    }

    private static BufferedImage girarFino(BufferedImage src, double rad) {
        int w = src.getWidth();
        int h = src.getHeight();
        double sin = Math.abs(Math.sin(rad));
        double cos = Math.abs(Math.cos(rad));
        int nuevoAncho = (int) Math.ceil(w * cos + h * sin);
        int nuevoAlto = (int) Math.ceil(w * sin + h * cos);
        BufferedImage dst = new BufferedImage(nuevoAncho, nuevoAlto, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        g.setColor(fondoDelBorde(src));
        g.fillRect(0, 0, nuevoAncho, nuevoAlto);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        AffineTransform t = new AffineTransform();
        t.translate(nuevoAncho / 2.0, nuevoAlto / 2.0);
        t.rotate(rad);
        t.translate(-w / 2.0, -h / 2.0);
        g.drawImage(src, t, null);
        g.dispose();
        return dst;
    }

    /** Gris mediano del perimetro: lo que rodea al diagrama es el fondo. */
    private static Color fondoDelBorde(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        int[] muestras = new int[2 * (w + h)];
        int n = 0;
        for (int x = 0; x < w; x++) {
            muestras[n++] = gris(img.getRGB(x, 0));
            muestras[n++] = gris(img.getRGB(x, h - 1));
        }
        for (int y = 0; y < h; y++) {
            muestras[n++] = gris(img.getRGB(0, y));
            muestras[n++] = gris(img.getRGB(w - 1, y));
        }
        Arrays.sort(muestras, 0, n);
        int m = muestras[n / 2];
        return new Color(m, m, m);
    }

    private static int gris(int rgb) {
        return (((rgb >> 16) & 0xFF) * 299 + ((rgb >> 8) & 0xFF) * 587 + (rgb & 0xFF) * 114) / 1000;
    }

    public static byte[] aPng(BufferedImage imagen) {
        try (ByteArrayOutputStream salida = new ByteArrayOutputStream(1 << 16)) {
            if (!ImageIO.write(imagen, "png", salida)) {
                throw new IllegalStateException("El JDK no pudo escribir el PNG");
            }
            return salida.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo serializar la imagen transformada", e);
        }
    }

    /** A RGB de 8 bits; lo transparente (si lo hubiera) sobre blanco, no negro. */
    private static BufferedImage aRgb(BufferedImage img) {
        if (img.getType() == BufferedImage.TYPE_INT_RGB) return img;
        BufferedImage rgb = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, rgb.getWidth(), rgb.getHeight());
        g.drawImage(img, 0, 0, null);
        g.dispose();
        return rgb;
    }
}
