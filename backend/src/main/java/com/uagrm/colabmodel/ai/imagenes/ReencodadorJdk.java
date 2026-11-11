package com.uagrm.colabmodel.ai.imagenes;

import com.uagrm.colabmodel.vision.ImagenDeEntrada.ImagenInvalida;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;

/**
 * El reencodado de la seccion 7 con solo el JDK. Recibe bytes que el servicio ya valido con
 * `ImagenDeEntrada.desde` (mismos limites y mensajes) y devuelve la imagen COMPLETA (no la copia reducida a
 * 1600 px) sin EXIF ni otros metadatos, derecha segun la orientacion, en RGB de 8 bits y con el alfa sobre
 * blanco; mas su miniatura JPEG de 320 px.
 */
@Component
public class ReencodadorJdk implements ReencodadorDeImagen {

    static final int LADO_MINIATURA = 320;
    static final float CALIDAD_JPEG = 0.92f;
    static final float CALIDAD_MINIATURA = 0.80f;

    private final long maxPixeles;

    public ReencodadorJdk(@Value("${colabmodel.vision.max-pixeles:40000000}") long maxPixeles) {
        this.maxPixeles = maxPixeles;
    }

    @Override
    public Reencodada reencodar(byte[] original, String contentType) {
        boolean jpeg = esJpeg(original);
        if (jpeg && componentesDelJpeg(original) == 4) {
            throw new ImagenInvalida("El JPEG usa el espacio de color CMYK, que no se puede leer. "
                    + "Exportalo como RGB o como PNG");
        }
        BufferedImage completa = decodificar(original);
        BufferedImage rgb = aRgbSobreBlanco(completa);
        completa.flush();
        BufferedImage derecha = aplicarOrientacion(rgb, jpeg ? orientacionExif(original) : 1);
        byte[] datos = jpeg ? escribirJpeg(derecha, CALIDAD_JPEG) : escribirPng(derecha);
        byte[] miniatura = escribirJpeg(reducir(derecha, LADO_MINIATURA), CALIDAD_MINIATURA);
        return new Reencodada(datos, jpeg ? "image/jpeg" : "image/png", derecha.getWidth(), derecha.getHeight(),
                miniatura);
    }

    // ------------------------------------------------------------ decodificar

    private BufferedImage decodificar(byte[] bytes) {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> lectores = ImageIO.getImageReaders(in);
            if (!lectores.hasNext()) throw new ImagenInvalida("El archivo no es una imagen PNG o JPEG valida");
            ImageReader lector = lectores.next();
            try {
                lector.setInput(in, true, true);
                long pixeles = (long) lector.getWidth(0) * lector.getHeight(0);
                if (pixeles > maxPixeles) {
                    throw new ImagenInvalida("La imagen declara " + pixeles + " pixeles; el maximo es " + maxPixeles);
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
            throw new ImagenInvalida("No se pudo leer la imagen: " + e.getClass().getSimpleName());
        }
    }

    /** RGB de 8 bits; si trae alfa, se compone SOBRE BLANCO (sin rellenar quedaria negro). */
    static BufferedImage aRgbSobreBlanco(BufferedImage o) {
        BufferedImage d = new BufferedImage(o.getWidth(), o.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = d.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, d.getWidth(), d.getHeight());
            g.drawImage(o, 0, 0, null);
        } finally {
            g.dispose();
        }
        return d;
    }

    // ----------------------------------------------------------------- EXIF

    private static boolean esJpeg(byte[] b) {
        return b.length > 2 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8;
    }

    /** Cuantos componentes de color declara el JPEG en su marcador SOF (4 = CMYK o YCCK); 0 si no lo halla. */
    static int componentesDelJpeg(byte[] j) {
        int p = 2;
        while (p + 4 <= j.length && (j[p] & 0xFF) == 0xFF) {
            int m = j[p + 1] & 0xFF;
            if (m == 0xDA || m == 0xD9) break;
            int largo = ((j[p + 2] & 0xFF) << 8) | (j[p + 3] & 0xFF);
            boolean sof = m >= 0xC0 && m <= 0xCF && m != 0xC4 && m != 0xC8 && m != 0xCC;
            if (sof && p + 9 < j.length) return j[p + 9] & 0xFF;
            if (largo < 2) break;
            p += 2 + largo;
        }
        return 0;
    }

    /** La etiqueta 0x0112 del EXIF de un JPEG (1 si no hay o esta mal formada). Solo java.nio. */
    static int orientacionExif(byte[] j) {
        try {
            if (!esJpeg(j)) return 1;
            int p = 2;
            while (p + 4 <= j.length && (j[p] & 0xFF) == 0xFF) {
                int marcador = j[p + 1] & 0xFF;
                if (marcador == 0xDA || marcador == 0xD9) break;
                int largo = ((j[p + 2] & 0xFF) << 8) | (j[p + 3] & 0xFF);
                if (marcador == 0xE1 && largo >= 16
                        && new String(j, p + 4, 6, StandardCharsets.ISO_8859_1).equals("Exif\0\0")) {
                    int tiff = p + 10;
                    ByteBuffer b = ByteBuffer.wrap(j).order(j[tiff] == 'I' ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
                    int ifd = tiff + b.getInt(tiff + 4);
                    for (int i = 0, n = b.getShort(ifd) & 0xFFFF; i < n; i++) {
                        int e = ifd + 2 + 12 * i;
                        if ((b.getShort(e) & 0xFFFF) == 0x0112) {
                            int v = b.getShort(e + 8) & 0xFFFF;
                            return v >= 1 && v <= 8 ? v : 1;
                        }
                    }
                    return 1;
                }
                if (largo < 2) break;
                p += 2 + largo;
            }
        } catch (RuntimeException e) {
            // EXIF mal formado: se ignora la orientacion y la foto se guarda tal cual.
        }
        return 1;
    }

    /** Gira o espeja la imagen para dejarla derecha segun la orientacion EXIF 1..8. */
    static BufferedImage aplicarOrientacion(BufferedImage s, int o) {
        if (o < 2 || o > 8) return s;
        int w = s.getWidth();
        int h = s.getHeight();
        AffineTransform t = switch (o) {
            case 2 -> new AffineTransform(-1, 0, 0, 1, w, 0);
            case 3 -> new AffineTransform(-1, 0, 0, -1, w, h);
            case 4 -> new AffineTransform(1, 0, 0, -1, 0, h);
            case 5 -> new AffineTransform(0, 1, 1, 0, 0, 0);
            case 6 -> new AffineTransform(0, 1, -1, 0, h, 0);
            case 7 -> new AffineTransform(0, -1, -1, 0, h, w);
            default -> new AffineTransform(0, -1, 1, 0, 0, w);
        };
        boolean gira = o >= 5;
        BufferedImage d = new BufferedImage(gira ? h : w, gira ? w : h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = d.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.drawImage(s, t, null);
        } finally {
            g.dispose();
        }
        return d;
    }

    // ------------------------------------------------------------ escribir

    private static byte[] escribirJpeg(BufferedImage img, float calidad) {
        ImageWriter w = ImageIO.getImageWritersByFormatName("jpeg").next();
        try {
            ImageWriteParam p = w.getDefaultWriteParam();
            p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            p.setCompressionQuality(calidad);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (ImageOutputStream os = ImageIO.createImageOutputStream(bos)) {
                w.setOutput(os);
                w.write(null, new IIOImage(img, null, null), p);
            }
            if (bos.size() == 0) throw new ImagenInvalida("No se pudo recodificar la imagen");
            return bos.toByteArray();
        } catch (IOException e) {
            throw new ImagenInvalida("No se pudo recodificar la imagen");
        } finally {
            w.dispose();
        }
    }

    private static byte[] escribirPng(BufferedImage img) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            // ImageIO.write devuelve false (no lanza) si no hay escritor: sin mirarlo se guardaria un archivo vacio.
            if (!ImageIO.write(img, "png", bos) || bos.size() == 0) {
                throw new ImagenInvalida("No se pudo recodificar la imagen");
            }
            return bos.toByteArray();
        } catch (IOException e) {
            throw new ImagenInvalida("No se pudo recodificar la imagen");
        }
    }

    // ------------------------------------------------------------ miniatura

    /** Lado mayor = `lado`, reduciendo a la mitad cada vez para que no salga dentada. Nunca agranda. */
    static BufferedImage reducir(BufferedImage o, int lado) {
        int mayor = Math.max(o.getWidth(), o.getHeight());
        if (mayor <= lado) return o;
        double f = lado / (double) mayor;
        int anchoFinal = Math.max(1, (int) Math.round(o.getWidth() * f));
        int altoFinal = Math.max(1, (int) Math.round(o.getHeight() * f));
        BufferedImage a = o;
        while (Math.max(a.getWidth(), a.getHeight()) / 2 >= lado) {
            a = escalar(a, Math.max(1, a.getWidth() / 2), Math.max(1, a.getHeight() / 2));
        }
        return escalar(a, anchoFinal, altoFinal);
    }

    private static BufferedImage escalar(BufferedImage o, int ancho, int alto) {
        BufferedImage d = new BufferedImage(ancho, alto, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = d.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(o, 0, 0, ancho, alto, null);
        } finally {
            g.dispose();
        }
        return d;
    }
}
