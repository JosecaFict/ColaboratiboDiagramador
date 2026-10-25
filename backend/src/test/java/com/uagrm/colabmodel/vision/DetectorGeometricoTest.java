package com.uagrm.colabmodel.vision;

import com.uagrm.colabmodel.modelcore.metamodel.RelationKind;
import com.uagrm.colabmodel.vision.DeteccionPropuesta.ClaseDetectada;
import com.uagrm.colabmodel.vision.DeteccionPropuesta.RecorteDeTexto;
import com.uagrm.colabmodel.vision.DeteccionPropuesta.RelacionDetectada;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El nivel 1 de vision, contra diagramas dibujados aca mismo.
 *
 * Se dibujan con Graphics2D en vez de cargar imagenes de prueba desde el
 * repositorio por dos razones: el test dice en su propio codigo que forma
 * tenia la entrada --y eso es lo que hace legible un fallo-- y no hay binarios
 * que mantener. Es el mismo criterio de los 7 modelos canonicos del generador:
 * entradas conocidas, salida verificable.
 *
 * El texto se simula con guiones cortos en vez de con drawString a proposito:
 * el detector no lee letras, solo mide donde hay tinta, y depender de las
 * fuentes instaladas haria que el test pase o falle segun la maquina.
 */
class DetectorGeometricoTest {

    private final DetectorGeometrico detector = new DetectorGeometrico();

    // ------------------------------------------------------------- CAJAS

    @Test
    @DisplayName("Una caja con dos separadores sale como una clase de tres compartimentos")
    void caja_con_tres_compartimentos() {
        BufferedImage img = lienzo(800, 600);
        Graphics2D g = pincel(img);
        caja(g, 60, 60, 220, 140);
        separador(g, 60, 220, 90);
        separador(g, 60, 220, 150);
        renglon(g, 80, 70, 120);                       // nombre
        renglon(g, 80, 100, 150);                      // atributos
        renglon(g, 80, 115, 140);
        renglon(g, 80, 130, 160);
        renglon(g, 80, 160, 130);                      // metodos
        renglon(g, 80, 175, 110);
        g.dispose();

        DeteccionPropuesta propuesta = detector.detectar(entrada(img));

        assertThat(propuesta.clases()).hasSize(1);
        ClaseDetectada clase = propuesta.clases().get(0);
        assertThat(clase.compartimentos()).isEqualTo(3);
        assertThat(clase.recorteNombre()).isNotNull();
        assertThat(clase.atributos()).hasSize(3);
        assertThat(clase.metodos()).hasSize(2);
        assertThat(clase.confianza()).isGreaterThan(0.9);
    }

    @Test
    @DisplayName("Sin nivel 2 no hay ni un texto leido, y eso no es un error")
    void los_textos_quedan_pendientes() {
        BufferedImage img = lienzo(800, 600);
        Graphics2D g = pincel(img);
        caja(g, 60, 60, 220, 140);
        separador(g, 60, 220, 100);
        renglon(g, 80, 75, 120);
        renglon(g, 80, 115, 150);
        g.dispose();

        ClaseDetectada clase = detector.detectar(entrada(img)).clases().get(0);

        assertThat(clase.nombre()).isNull();
        assertThat(clase.recorteNombre().texto()).isNull();
        assertThat(clase.recorteNombre().confianza()).isZero();
        // Lo que si existe es DONDE mirar: es lo unico que el nivel 2 necesita.
        assertThat(clase.recorteNombre().caja().ancho()).isGreaterThan(0);
        assertThat(clase.atributos()).extracting(RecorteDeTexto::texto).containsOnlyNulls();
    }

    @Test
    @DisplayName("Un separador de compartimentos no se lee como una caja aparte")
    void el_separador_no_es_una_caja() {
        BufferedImage img = lienzo(600, 400);
        Graphics2D g = pincel(img);
        caja(g, 50, 50, 200, 160);
        separador(g, 50, 200, 90);
        separador(g, 50, 200, 140);
        g.dispose();

        // El borde vertical completo CUBRE el tramo [50, 90], asi que con una
        // regla de cobertura este caso daba tres cajas anidadas. Por eso la
        // vertical tiene que EMPEZAR Y TERMINAR en las horizontales.
        assertThat(detector.detectar(entrada(img)).clases()).hasSize(1);
    }

    @Test
    @DisplayName("Dos clases apiladas del mismo ancho no se funden en una sola caja")
    void dos_cajas_apiladas_no_se_funden() {
        BufferedImage img = lienzo(600, 500);
        Graphics2D g = pincel(img);
        caja(g, 60, 60, 200, 100);
        caja(g, 60, 200, 200, 100);
        g.dispose();

        DeteccionPropuesta propuesta = detector.detectar(entrada(img));

        assertThat(propuesta.clases()).hasSize(2);
        assertThat(propuesta.clases()).allSatisfy(c -> assertThat(c.caja().alto()).isLessThan(140));
    }

    // --------------------------------------------------------- RELACIONES

    @Test
    @DisplayName("Dos cajas unidas por una linea dan una relacion entre ellas")
    void una_linea_recta_es_una_relacion() {
        BufferedImage img = lienzo(800, 400);
        Graphics2D g = pincel(img);
        caja(g, 60, 60, 200, 120);
        caja(g, 420, 60, 200, 120);
        g.drawLine(260, 120, 420, 120);
        g.dispose();

        DeteccionPropuesta propuesta = detector.detectar(entrada(img));

        assertThat(propuesta.clases()).hasSize(2);
        assertThat(propuesta.relaciones()).hasSize(1);
        RelacionDetectada relacion = propuesta.relaciones().get(0);
        assertThat(relacion.origenRef()).isNotEqualTo(relacion.destinoRef());
        // El extremo no se clasifico: la lectura conservadora es asociacion.
        assertThat(relacion.kind()).isEqualTo(com.uagrm.colabmodel.modelcore.metamodel.RelationKind.ASSOCIATION);
        assertThat(relacion.simboloOrigen().figura()).isNull();
        assertThat(relacion.simboloOrigen().caja().ancho()).isGreaterThan(10);
        assertThat(relacion.cardinalidadDestino()).isNotNull();
    }

    @Test
    @DisplayName("Una linea quebrada en L sigue siendo UNA relacion, no tres lineas sueltas")
    void una_linea_quebrada_es_una_sola_relacion() {
        BufferedImage img = lienzo(800, 500);
        Graphics2D g = pincel(img);
        caja(g, 60, 60, 200, 120);
        caja(g, 420, 280, 200, 120);
        g.drawLine(260, 120, 340, 120);
        g.drawLine(340, 120, 340, 340);
        g.drawLine(340, 340, 420, 340);
        g.dispose();

        DeteccionPropuesta propuesta = detector.detectar(entrada(img));

        assertThat(propuesta.clases()).hasSize(2);
        assertThat(propuesta.relaciones()).hasSize(1);
    }

    @Test
    @DisplayName("Una clase relacionada consigo misma se propone reflexiva")
    void relacion_reflexiva() {
        BufferedImage img = lienzo(600, 400);
        Graphics2D g = pincel(img);
        caja(g, 60, 60, 200, 120);
        g.drawLine(260, 90, 340, 90);
        g.drawLine(340, 90, 340, 150);
        g.drawLine(340, 150, 260, 150);
        g.dispose();

        DeteccionPropuesta propuesta = detector.detectar(entrada(img));

        assertThat(propuesta.relaciones()).hasSize(1);
        RelacionDetectada relacion = propuesta.relaciones().get(0);
        assertThat(relacion.origenRef()).isEqualTo(relacion.destinoRef());
    }

    @Test
    @DisplayName("Una linea que no llega a ninguna clase se avisa y no se propone")
    void linea_suelta_no_inventa_relacion() {
        BufferedImage img = lienzo(600, 400);
        Graphics2D g = pincel(img);
        caja(g, 60, 60, 160, 100);
        g.drawLine(350, 300, 500, 300);
        g.dispose();

        DeteccionPropuesta propuesta = detector.detectar(entrada(img));

        assertThat(propuesta.relaciones()).isEmpty();
        assertThat(propuesta.avisos()).anySatisfy(a -> assertThat(a).contains("no llegan a ninguna clase"));
    }

    // -------------------------------------------------------- ENDEREZADO

    @ParameterizedTest(name = "foto inclinada {0} grados")
    @ValueSource(doubles = {1.0, 1.5, 2.0, 3.0, 6.0, -4.5})
    @DisplayName("Una foto torcida se endereza sola y se detecta igual")
    void se_endereza_antes_de_detectar(double grados) {
        BufferedImage img = lienzo(800, 400);
        Graphics2D g = pincel(img);
        g.rotate(Math.toRadians(grados), 400, 200);
        caja(g, 60, 60, 200, 120);
        caja(g, 420, 60, 200, 120);
        g.drawLine(260, 120, 420, 120);
        g.dispose();

        DeteccionPropuesta propuesta = detector.detectar(entrada(img));

        // Sin enderezar, UN grado ya daba cero clases: los bordes se astillan
        // en escalones mas cortos que el minimo y desaparecen enteros.
        assertThat(propuesta.clases()).hasSize(2);
        assertThat(propuesta.relaciones()).hasSize(1);
        assertThat(propuesta.metricas().enderezado()).isNotNull();
        assertThat(propuesta.metricas().enderezado().grados())
                .isCloseTo(-grados, org.assertj.core.data.Offset.offset(0.5));
    }

    @Test
    @DisplayName("Un diagrama derecho no se rota: no se paga el borroneo por nada")
    void no_endereza_lo_que_ya_esta_derecho() {
        BufferedImage img = lienzo(800, 400);
        Graphics2D g = pincel(img);
        caja(g, 60, 60, 200, 120);
        caja(g, 420, 60, 200, 120);
        g.drawLine(260, 120, 420, 120);
        g.dispose();

        DeteccionPropuesta propuesta = detector.detectar(entrada(img));

        assertThat(propuesta.metricas().enderezado()).isNull();
        assertThat(propuesta.metricas().anchoTrabajo()).isEqualTo(800);
        assertThat(propuesta.clases()).hasSize(2);
        assertThat(propuesta.avisos()).isEmpty();
    }

    // ------------------------------------------ DIAGONALES Y PUNTEADAS

    @Test
    @DisplayName("Un conector diagonal se lee ajustando una recta sobre la tinta suelta")
    void conector_diagonal_se_lee() {
        BufferedImage img = lienzo(800, 500);
        Graphics2D g = pincel(img);
        caja(g, 60, 60, 200, 120);
        caja(g, 460, 300, 200, 120);
        g.drawLine(260, 180, 460, 300);
        g.dispose();

        DeteccionPropuesta propuesta = detector.detectar(entrada(img));

        // El analisis de corridas no la ve --una diagonal es una escalera de
        // trocitos mas cortos que el minimo-- asi que esto daba dos clases y
        // CERO relaciones. Se avisaba, que era lo correcto, pero un aviso no es
        // una lectura: habia que dibujar de nuevo una relacion que estaba ahi.
        assertThat(propuesta.clases()).hasSize(2);
        assertThat(propuesta.relaciones()).hasSize(1);
        assertThat(propuesta.relaciones().get(0).kind()).isEqualTo(RelationKind.ASSOCIATION);
        assertThat(propuesta.metricas().rectasSueltas()).isEqualTo(1);
        // Y lo que se leyo ya no se denuncia: el mapa de tinta explicada es el mismo.
        assertThat(propuesta.avisos()).noneSatisfy(a -> assertThat(a).contains("no pude interpretar"));
    }

    @Test
    @DisplayName("Un conector punteado se lee, y punteado quiere decir dependencia")
    void conector_punteado_es_dependencia() {
        BufferedImage img = lienzo(800, 400);
        Graphics2D g = pincel(img);
        caja(g, 60, 60, 200, 120);
        caja(g, 420, 60, 200, 120);
        g.setStroke(new BasicStroke(2, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10,
                new float[]{8, 6}, 0));
        g.drawLine(260, 120, 420, 120);
        g.dispose();

        DeteccionPropuesta propuesta = detector.detectar(entrada(img));

        assertThat(propuesta.clases()).hasSize(2);
        assertThat(propuesta.relaciones()).hasSize(1);
        // El ESTILO del trazo alcanza para afirmarlo: no hace falta ninguna
        // figura en el extremo. Y antes de esto, DEPENDENCY era un tipo de
        // relacion al que la lectura de imagenes no tenia ningun camino.
        assertThat(propuesta.relaciones().get(0).kind()).isEqualTo(RelationKind.DEPENDENCY);
        assertThat(propuesta.metricas().rectasSueltas()).isEqualTo(1);
    }

    @Test
    @DisplayName("Una diagonal que no llega a dos clases no se propone, y se avisa")
    void diagonal_suelta_no_se_propone() {
        BufferedImage img = lienzo(800, 500);
        Graphics2D g = pincel(img);
        caja(g, 60, 60, 200, 120);
        caja(g, 60, 300, 200, 120);
        // Una flecha, una llave o un garabato lejos de las dos cajas.
        g.drawLine(500, 120, 700, 300);
        g.dispose();

        DeteccionPropuesta propuesta = detector.detectar(entrada(img));

        // La recta esta y se encuentra, pero una recta no es una relacion
        // mientras no toque dos cajas. Inventar la relacion seria peor que no
        // leerla: nadie revisa una propuesta que ya viene "completa".
        assertThat(propuesta.clases()).hasSize(2);
        assertThat(propuesta.relaciones()).isEmpty();
        assertThat(propuesta.metricas().rectasSueltas()).isZero();
        assertThat(propuesta.avisos()).anySatisfy(a -> assertThat(a).contains("sin interpretar"));
    }

    @Test
    @DisplayName("Un renglon de texto entre dos clases no se lee como relacion")
    void texto_en_el_corredor_no_es_relacion() {
        BufferedImage img = lienzo(800, 400);
        Graphics2D g = pincel(img);
        caja(g, 60, 60, 200, 120);
        caja(g, 500, 60, 200, 120);
        // Una palabra suelta en el corredor: doce letras de 8x10, que alineadas
        // son una recta horizontal perfecta de punta a punta.
        for (int i = 0; i < 20; i++) g.fillRect(262 + i * 12, 115, 8, 10);
        g.dispose();

        DeteccionPropuesta propuesta = detector.detectar(entrada(img));

        // Lo que la descarta es el GROSOR: una linea de diagrama mide 1 a 3 px
        // cruzada, un renglon de texto mide 10 o 12. Sin ese filtro, cualquier
        // etiqueta entre dos clases entraba como relacion.
        assertThat(propuesta.clases()).hasSize(2);
        assertThat(propuesta.relaciones()).isEmpty();
        // Pero tampoco se calla, que es la otra mitad de la Regla 10.
        assertThat(propuesta.avisos()).anySatisfy(a -> assertThat(a).contains("c1"));
    }

    // ------------------------------------------------- ILUMINACION

    @Test
    @DisplayName("Una foto con sombra de un lado se binariza por ventanas y se lee igual")
    void foto_con_iluminacion_despareja() {
        BufferedImage img = new BufferedImage(800, 400, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        // El fondo va de casi blanco a casi negro: es la foto de pizarra con la
        // ventana a un costado, y es el caso que un umbral unico no puede.
        g.setPaint(new GradientPaint(0, 0, new Color(250, 250, 250),
                800, 0, new Color(70, 70, 70)));
        g.fillRect(0, 0, 800, 400);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        g.setColor(Color.BLACK);
        g.setStroke(new BasicStroke(2));
        caja(g, 60, 60, 200, 120);
        caja(g, 500, 60, 200, 120);
        g.drawLine(260, 120, 500, 120);
        g.dispose();

        DeteccionPropuesta propuesta = detector.detectar(entrada(img));

        // Con el umbral global de Otsu, el lado en sombra del FONDO cae del lado
        // de la tinta y la imagen sale como una mancha: cero clases.
        assertThat(propuesta.metricas().binarizacion()).isEqualTo("adaptativa");
        assertThat(propuesta.clases()).hasSize(2);
        assertThat(propuesta.relaciones()).hasSize(1);
        assertThat(propuesta.metricas().pareceFoto()).isTrue();
    }

    // ------------------------------------------------------------ BORDES

    @Test
    @DisplayName("Una imagen sin diagrama no falla: devuelve vacio con su aviso")
    void imagen_en_blanco() {
        DeteccionPropuesta propuesta = detector.detectar(entrada(lienzo(400, 300)));

        assertThat(propuesta.clases()).isEmpty();
        assertThat(propuesta.relaciones()).isEmpty();
        assertThat(propuesta.avisos()).anySatisfy(a -> assertThat(a).contains("No se reconocio ninguna caja"));
        assertThat(propuesta.metricas().detector()).isEqualTo(DetectorGeometrico.NOMBRE);
    }

    @Test
    @DisplayName("Un screenshot en tema oscuro se detecta igual que uno claro")
    void tema_oscuro() {
        BufferedImage img = new BufferedImage(600, 400, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = pincel(img);
        g.setColor(new Color(24, 24, 27));
        g.fillRect(0, 0, 600, 400);
        g.setColor(new Color(240, 240, 240));
        caja(g, 60, 60, 200, 120);
        separador(g, 60, 200, 100);
        g.dispose();

        DeteccionPropuesta propuesta = detector.detectar(entrada(img));

        assertThat(propuesta.clases()).hasSize(1);
        assertThat(propuesta.clases().get(0).compartimentos()).isEqualTo(2);
    }

    @Test
    @DisplayName("El lienzo conserva la disposicion que tenian las cajas en la imagen")
    void el_lienzo_conserva_la_disposicion() {
        BufferedImage img = lienzo(800, 400);
        Graphics2D g = pincel(img);
        caja(g, 60, 60, 200, 120);
        caja(g, 500, 220, 200, 120);
        g.dispose();

        DeteccionPropuesta propuesta = detector.detectar(entrada(img));

        assertThat(propuesta.clases()).hasSize(2);
        ClaseDetectada izquierda = propuesta.clases().get(0);
        ClaseDetectada derecha = propuesta.clases().get(1);
        assertThat(derecha.lienzo().x()).isGreaterThan(izquierda.lienzo().x());
        assertThat(derecha.lienzo().y()).isGreaterThan(izquierda.lienzo().y());
        // Sin esto el diagrama aterriza apilado en el origen y hay que
        // re-acomodarlo entero a mano, que era el trabajo que se queria ahorrar.
        assertThat(izquierda.lienzo().ancho()).isBetween(120, 260);
    }

    @Test
    @DisplayName("Las metricas dicen en que condiciones se leyo")
    void metricas() {
        BufferedImage img = lienzo(800, 600);
        Graphics2D g = pincel(img);
        caja(g, 60, 60, 200, 120);
        g.dispose();

        DeteccionPropuesta.Metricas metricas = detector.detectar(entrada(img)).metricas();

        assertThat(metricas.anchoOriginal()).isEqualTo(800);
        assertThat(metricas.anchoTrabajo()).isEqualTo(800);
        assertThat(metricas.escala()).isEqualTo(1.0);
        assertThat(metricas.segmentos()).isPositive();
        assertThat(metricas.pareceFoto()).isFalse();
    }

    // ------------------------------------------------------------- UTILES

    private static BufferedImage lienzo(int ancho, int alto) {
        BufferedImage img = new BufferedImage(ancho, alto, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, ancho, alto);
        g.dispose();
        return img;
    }

    /** Sin antialiasing: un diagrama exportado tampoco lo tiene en los bordes rectos. */
    private static Graphics2D pincel(BufferedImage img) {
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        g.setColor(Color.BLACK);
        g.setStroke(new BasicStroke(2));
        return g;
    }

    private static void caja(Graphics2D g, int x, int y, int ancho, int alto) {
        g.drawRect(x, y, ancho, alto);
    }

    private static void separador(Graphics2D g, int x, int ancho, int y) {
        g.drawLine(x, y, x + ancho, y);
    }

    /** Un renglon de texto simulado: guiones cortos, demasiado cortos para ser un borde. */
    private static void renglon(Graphics2D g, int x, int y, int ancho) {
        for (int i = 0; i < ancho; i += 12) g.fillRect(x + i, y, 8, 4);
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
