package com.uagrm.colabmodel.vision;

import com.uagrm.colabmodel.modelcore.metamodel.RelationKind;
import com.uagrm.colabmodel.vision.DeteccionPropuesta.Caja;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Distinguir el rombo del triangulo sin ningun modelo cargado.
 *
 * Importa mas de lo que parece: es lo unico que separa una COMPOSICION de una
 * AGREGACION cuando no hay modelo, y esa diferencia decide si la FK derivada
 * sale con ON DELETE CASCADE o con SET NULL. Equivocarla se lleva datos
 * puestos, asi que los umbrales estan corridos hacia el null.
 *
 * Los simbolos se dibujan aca con las medidas de UML --rombo el doble de largo
 * que de ancho, triangulo isosceles-- en vez de cargar imagenes de prueba: el
 * test dice en su propio codigo que forma tenia la entrada.
 */
class ClasificadorDeSimboloTest {

    private static final int LADO = 56;
    private static final Caja RECORTE = new Caja(0, 0, LADO, LADO);

    @Test
    @DisplayName("Rombo relleno -> composicion")
    void rombo_relleno() {
        assertThat(clasificar(g -> rombo(g, true)))
                .isEqualTo(RelationKind.COMPOSITION);
    }

    @Test
    @DisplayName("Rombo hueco -> agregacion")
    void rombo_hueco() {
        assertThat(clasificar(g -> rombo(g, false)))
                .isEqualTo(RelationKind.AGGREGATION);
    }

    @Test
    @DisplayName("Triangulo hueco -> generalizacion")
    void triangulo() {
        assertThat(clasificar(ClasificadorDeSimboloTest::triangulo))
                .isEqualTo(RelationKind.GENERALIZATION);
    }

    @Test
    @DisplayName("Una linea sola no es ninguna figura")
    void solo_la_linea() {
        // El caso mas comun de todos: una asociacion simple. Inventarle un
        // rombo seria imponerle un ON DELETE CASCADE que nadie pidio.
        assertThat(clasificar(g -> g.drawLine(0, LADO / 2, LADO, LADO / 2))).isNull();
    }

    @Test
    @DisplayName("Un recorte vacio no da figura")
    void vacio() {
        assertThat(clasificar(g -> { })).isNull();
    }

    @Test
    @DisplayName("Un recorte demasiado chico se descarta antes de medir")
    void muy_chico() {
        assertThat(ClasificadorDeSimbolo.de(imagenCon(g -> rombo(g, true)), new Caja(0, 0, 8, 8)))
                .isNull();
    }

    @Test
    @DisplayName("El rombo se reconoce igual con la linea entrando por cualquier lado")
    void da_igual_la_orientacion() {
        // El perfil de ancho es simetrico, asi que no hace falta saber para
        // donde apunta la linea. Es lo que hace robusta la medida.
        assertThat(clasificar(g -> { rombo(g, true); g.drawLine(LADO / 2, LADO / 2, LADO, LADO / 2); }))
                .isEqualTo(RelationKind.COMPOSITION);
        assertThat(clasificar(g -> { rombo(g, true); g.drawLine(0, LADO / 2, LADO / 2, LADO / 2); }))
                .isEqualTo(RelationKind.COMPOSITION);
    }

    /**
     * El recorte es un cuadrado ciego alrededor del extremo, asi que ahi adentro
     * cae tinta que no es la figura: el borde de la caja vecina, una
     * cardinalidad, otro conector que pasa cerca. Los dos casos de abajo son la
     * misma barra ajena arriba del simbolo, y muestran que el dano depende de
     * cuanta masa tenga la figura frente a la intrusa.
     */
    @Test
    @DisplayName("Una barra ajena arriba del rombo no le cambia la figura")
    void tinta_ajena_no_tapa_el_rombo() {
        // El rombo relleno aguanta por masa: tiene con que defender su propio
        // eje. Va igual, porque lo que se quiere fijar es que la medida se hace
        // sobre la figura y no sobre el cuadrado entero.
        assertThat(clasificar(g -> {
            rombo(g, true);
            g.fillRect(1, 0, 40, 12);
        })).isEqualTo(RelationKind.COMPOSITION);
    }

    @Test
    @DisplayName("La misma barra ajena arriba del triangulo lo hacia desaparecer")
    void tinta_ajena_no_tapa_el_triangulo() {
        // Un triangulo HUECO son cuatro trazos finos, asi que la barra pesa mas
        // que la figura: se lleva los momentos de segundo orden --de donde sale
        // el eje sobre el que se mide el perfil-- y el ancho termina medido a lo
        // largo del eje equivocado. Sin aislar la figura, esto devuelve null, y
        // un null no es gratis: deja la relacion en ASSOCIATION y la
        // generalizacion desaparece de la propuesta sin que nadie lo note.
        assertThat(clasificar(g -> {
            triangulo(g);
            g.fillRect(1, 0, 40, 12);
        })).isEqualTo(RelationKind.GENERALIZATION);
    }

    @Test
    @DisplayName("Sobre un diagrama real, el extremo del conector no inventa figura")
    void sobre_una_deteccion_real() {
        BufferedImage img = new BufferedImage(800, 400, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = pincel(img, 800, 400);
        g.drawRect(60, 60, 200, 120);
        g.drawRect(420, 60, 200, 120);
        g.drawLine(260, 120, 420, 120);
        g.dispose();
        ImagenDeEntrada imagen = aEntrada(img);

        DeteccionPropuesta propuesta = new DetectorGeometrico().detectar(imagen);
        DeteccionPropuesta leida = new EnriquecedorGeometrico().enriquecer(propuesta, imagen);

        assertThat(leida.relaciones()).hasSize(1);
        // No hay rombo ni triangulo dibujado: la relacion se queda como vino.
        assertThat(leida.relaciones().get(0).kind()).isEqualTo(RelationKind.ASSOCIATION);
        assertThat(leida.relaciones().get(0).simboloOrigen().figura()).isNull();
    }

    @Test
    @DisplayName("El enriquecedor geometrico nunca esta caido")
    void siempre_disponible() {
        assertThat(new EnriquecedorGeometrico().disponible()).isTrue();
    }

    // ------------------------------------------------------------- UTILES

    private interface Dibujo { void en(Graphics2D g); }

    private static RelationKind clasificar(Dibujo dibujo) {
        return ClasificadorDeSimbolo.de(imagenCon(dibujo), RECORTE);
    }

    /** El simbolo ocupa el recorte entero, como lo recorta el detector. */
    private static ImagenDeEntrada imagenCon(Dibujo dibujo) {
        BufferedImage img = new BufferedImage(LADO, LADO, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = pincel(img, LADO, LADO);
        dibujo.en(g);
        g.dispose();
        return aEntrada(img);
    }

    /** Rombo UML: el doble de largo que de ancho, con la linea entrando por un vertice. */
    private static void rombo(Graphics2D g, boolean relleno) {
        int medio = LADO / 2;
        Polygon p = new Polygon(
                new int[]{ 6, medio, LADO - 6, medio },
                new int[]{ medio, medio - 11, medio, medio + 11 }, 4);
        if (relleno) g.fillPolygon(p); else g.drawPolygon(p);
    }

    /** Triangulo isosceles de generalizacion: base a un lado, vertice al otro. */
    private static void triangulo(Graphics2D g) {
        int medio = LADO / 2;
        g.drawPolygon(
                new int[]{ 8, LADO - 8, LADO - 8 },
                new int[]{ medio, medio - 15, medio + 15 }, 3);
    }

    private static Graphics2D pincel(BufferedImage img, int w, int h) {
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        g.setColor(Color.BLACK);
        g.setStroke(new BasicStroke(2));
        return g;
    }

    private static ImagenDeEntrada aEntrada(BufferedImage img) {
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
