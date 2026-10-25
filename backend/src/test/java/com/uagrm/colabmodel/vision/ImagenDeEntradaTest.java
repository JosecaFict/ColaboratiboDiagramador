package com.uagrm.colabmodel.vision;

import com.uagrm.colabmodel.vision.DeteccionPropuesta.Caja;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * La puerta de entrada de binarios de afuera.
 *
 * Es el unico punto del sistema donde alguien nos manda un archivo que
 * elegimos decodificar, asi que los limites no son una formalidad: son lo que
 * separa "una foto mal sacada" de "un proceso sin memoria".
 */
class ImagenDeEntradaTest {

    private static final ImagenDeEntrada.Limites LIMITES = ImagenDeEntrada.Limites.POR_DEFECTO;

    @Test
    @DisplayName("Solo PNG y JPEG: lista blanca, no lista negra")
    void formato_no_aceptado() {
        byte[] png = png(100, 100);

        assertThatThrownBy(() -> ImagenDeEntrada.desde(png, "image/gif", LIMITES))
                .isInstanceOf(ImagenDeEntrada.ImagenInvalida.class)
                .hasMessageContaining("Formato no aceptado");
    }

    @Test
    @DisplayName("Un content-type mentido no alcanza: tambien tiene que decodificar")
    void content_type_mentido() {
        byte[] basura = "esto no es una imagen, pero dice que si".getBytes();

        assertThatThrownBy(() -> ImagenDeEntrada.desde(basura, "image/png", LIMITES))
                .isInstanceOf(ImagenDeEntrada.ImagenInvalida.class);
    }

    @Test
    @DisplayName("Sin archivo, o vacio, es 400 y no una excepcion cualquiera")
    void vacio() {
        assertThatThrownBy(() -> ImagenDeEntrada.desde(new byte[0], "image/png", LIMITES))
                .isInstanceOf(ImagenDeEntrada.ImagenInvalida.class);
        assertThatThrownBy(() -> ImagenDeEntrada.desde(null, "image/png", LIMITES))
                .isInstanceOf(ImagenDeEntrada.ImagenInvalida.class);
    }

    @Test
    @DisplayName("El binario comprimido tiene tope")
    void demasiados_bytes() {
        byte[] png = png(400, 400);
        ImagenDeEntrada.Limites apretado = new ImagenDeEntrada.Limites(64, 40_000_000L, 1600);

        assertThatThrownBy(() -> ImagenDeEntrada.desde(png, "image/png", apretado))
                .isInstanceOf(ImagenDeEntrada.ImagenInvalida.class)
                .hasMessageContaining("el maximo es");
    }

    @Test
    @DisplayName("Los PIXELES tienen su propio tope, y se miden ANTES de decodificar")
    void demasiados_pixeles() {
        // 900x900 son 810.000 pixeles en un PNG de unos pocos KB: la relacion
        // entre lo que pesa y lo que ocupa en memoria es justo el problema.
        byte[] png = png(900, 900);
        ImagenDeEntrada.Limites apretado = new ImagenDeEntrada.Limites(8L * 1024 * 1024, 100_000L, 1600);

        assertThatThrownBy(() -> ImagenDeEntrada.desde(png, "image/png", apretado))
                .isInstanceOf(ImagenDeEntrada.ImagenInvalida.class)
                .hasMessageContaining("pixeles");
    }

    @Test
    @DisplayName("Una imagen minuscula no puede contener un diagrama")
    void demasiado_chica() {
        assertThatThrownBy(() -> ImagenDeEntrada.desde(png(20, 20), "image/png", LIMITES))
                .isInstanceOf(ImagenDeEntrada.ImagenInvalida.class)
                .hasMessageContaining("demasiado chica");
    }

    @Test
    @DisplayName("Una foto grande se reduce para acotar el costo de CPU")
    void se_reduce_al_lado_maximo() {
        ImagenDeEntrada.Limites limites = new ImagenDeEntrada.Limites(8L * 1024 * 1024, 40_000_000L, 500);

        ImagenDeEntrada entrada = ImagenDeEntrada.desde(png(2000, 1000), "image/png", limites);

        assertThat(entrada.ancho()).isEqualTo(500);
        assertThat(entrada.alto()).isEqualTo(250);
        assertThat(entrada.anchoOriginal()).isEqualTo(2000);
        assertThat(entrada.escala()).isEqualTo(0.25);
    }

    @Test
    @DisplayName("Una imagen que ya entra en el lado maximo no se toca")
    void no_se_reduce_si_no_hace_falta() {
        ImagenDeEntrada entrada = ImagenDeEntrada.desde(png(300, 200), "image/png", LIMITES);

        assertThat(entrada.ancho()).isEqualTo(300);
        assertThat(entrada.escala()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Un recorte pedido medio afuera se acota, no explota")
    void recorte_fuera_de_borde() {
        ImagenDeEntrada entrada = ImagenDeEntrada.desde(png(200, 200), "image/png", LIMITES);

        // Pasa siempre que el diagrama toca el margen de la foto.
        assertThat(entrada.recortar(new Caja(180, 180, 100, 100)).getWidth()).isEqualTo(20);
        assertThat(entrada.recortar(new Caja(-50, -50, 30, 30)).getHeight()).isPositive();
        assertThat(entrada.recortarComoPng(new Caja(0, 0, 50, 50))).isNotEmpty();
    }

    @Test
    @DisplayName("El gris se lee sin signo: a partir de 128 el byte crudo de Java viene negativo")
    void gris_sin_signo() {
        BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 64, 64);
        g.dispose();

        ImagenDeEntrada entrada = ImagenDeEntrada.desde(aPng(img), "image/png", LIMITES);

        assertThat(entrada.gris(10, 10)).isBetween(250, 255);
    }

    // ------------------------------------------------------------- UTILES

    private static byte[] png(int ancho, int alto) {
        BufferedImage img = new BufferedImage(ancho, alto, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, ancho, alto);
        g.setColor(Color.BLACK);
        g.drawRect(5, 5, Math.max(2, ancho - 10), Math.max(2, alto - 10));
        g.dispose();
        return aPng(img);
    }

    private static byte[] aPng(BufferedImage img) {
        try {
            ByteArrayOutputStream salida = new ByteArrayOutputStream();
            ImageIO.write(img, "png", salida);
            return salida.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
