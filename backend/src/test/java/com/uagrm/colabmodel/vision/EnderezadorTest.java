package com.uagrm.colabmodel.vision;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La estimacion del angulo, aislada del detector.
 *
 * Importa medirla sola porque es la etapa de la que depende todo lo demas
 * cuando la entrada es una foto: si el angulo sale mal, el detector recibe una
 * imagen peor que la original y no hay forma de darse cuenta mirando el
 * resultado final.
 */
class EnderezadorTest {

    @ParameterizedTest(name = "inclinada {0} grados")
    @ValueSource(doubles = {1.0, 2.0, 4.0, 6.0, -3.5, 8.0})
    @DisplayName("Recupera el angulo con menos de medio grado de error")
    void recupera_el_angulo(double inclinacion) {
        ImagenDeEntrada imagen = diagrama(inclinacion);

        Enderezador.Inclinacion medida = Enderezador.estimar(imagen);

        // Para enderezar hay que rotar en sentido contrario al que se inclino.
        assertThat(medida.grados()).isCloseTo(-inclinacion, org.assertj.core.data.Offset.offset(0.5));
        assertThat(medida.valeLaPena()).isTrue();
    }

    @Test
    @DisplayName("Un diagrama ya derecho no se toca")
    void no_rota_lo_que_ya_esta_derecho() {
        Enderezador.Inclinacion medida = Enderezador.estimar(diagrama(0));

        assertThat(medida.valeLaPena()).isFalse();
    }

    @Test
    @DisplayName("Una imagen casi vacia no da un angulo inventado")
    void sin_tinta_suficiente() {
        BufferedImage img = new BufferedImage(400, 300, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 400, 300);
        g.dispose();

        assertThat(Enderezador.estimar(entrada(img))).isEqualTo(Enderezador.Inclinacion.NINGUNA);
    }

    @Test
    @DisplayName("Rotar conserva todo el diagrama: el lienzo crece a la caja envolvente")
    void rotar_no_recorta() {
        ImagenDeEntrada original = diagrama(0);

        ImagenDeEntrada rotada = original.rotada(8);

        assertThat(rotada.ancho()).isGreaterThan(original.ancho());
        assertThat(rotada.alto()).isGreaterThan(original.alto());
        // La rotacion pasa despues del reescalado: de donde venimos no cambia.
        assertThat(rotada.anchoOriginal()).isEqualTo(original.anchoOriginal());
        assertThat(rotada.escala()).isEqualTo(original.escala());
    }

    @Test
    @DisplayName("Al rotar, las esquinas nuevas toman el color de fondo y no blanco")
    void las_esquinas_toman_el_fondo() {
        // En tema oscuro, rellenar de blanco metia cuatro manchas enormes que
        // al invertirse la mascara quedaban del lado de la tinta.
        BufferedImage oscura = new BufferedImage(400, 300, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = oscura.createGraphics();
        g.setColor(new Color(24, 24, 27));
        g.fillRect(0, 0, 400, 300);
        g.setColor(new Color(240, 240, 240));
        g.setStroke(new BasicStroke(2));
        g.drawRect(60, 60, 200, 120);
        g.dispose();

        ImagenDeEntrada rotada = entrada(oscura).rotada(6);

        assertThat(rotada.gris(2, 2)).isLessThan(60);
    }

    // ------------------------------------------------------------- UTILES

    /** Dos cajas y un conector, inclinados los grados que se pidan. */
    private static ImagenDeEntrada diagrama(double grados) {
        BufferedImage img = new BufferedImage(800, 400, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 800, 400);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        g.setColor(Color.BLACK);
        g.setStroke(new BasicStroke(2));
        g.rotate(Math.toRadians(grados), 400, 200);
        g.drawRect(60, 60, 200, 120);
        g.drawRect(420, 60, 200, 120);
        g.drawLine(260, 120, 420, 120);
        g.dispose();
        return entrada(img);
    }

    private static ImagenDeEntrada entrada(BufferedImage img) {
        try {
            ByteArrayOutputStream salida = new ByteArrayOutputStream();
            ImageIO.write(img, "png", salida);
            return ImagenDeEntrada.desde(salida.toByteArray(), "image/png",
                    ImagenDeEntrada.Limites.POR_DEFECTO);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
