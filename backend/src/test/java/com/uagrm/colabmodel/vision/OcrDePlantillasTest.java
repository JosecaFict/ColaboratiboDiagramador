package com.uagrm.colabmodel.vision;

import com.uagrm.colabmodel.vision.DeteccionPropuesta.RolDeTexto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Leer el texto de un recorte sin modelo, sin red y sin nada instalado.
 *
 * Los recortes se dibujan aca con la misma AWT que despues construye el atlas, y
 * eso es una fortaleza y una debilidad del test a la vez. La fortaleza: no hay
 * binarios de prueba que mantener y el test dice en su codigo que entrada tenia.
 * La debilidad: es el caso amable --misma fuente de los dos lados--, asi que estos
 * numeros son el techo y no el promedio. Contra una captura hecha en otra maquina,
 * con otro motor de texto, lo que cambia es cuantos renglones salen en null; lo
 * que NO cambia es que un renglon que no se reconoce queda vacio en vez de salir
 * inventado, y eso es lo que aca se fija.
 *
 * El tamano de 16 px no es arbitrario: es lo que mide el texto de un diagrama
 * exportado de una herramienta a tamano natural. Los casos de 11 y 12 px son la
 * captura reducida, y ahi el contrato que se verifica es mas debil a proposito
 * --null o correcto, nunca otra cosa-- porque depender de que una fuente concreta
 * siga siendo legible a 11 px haria fallar el test en otra maquina.
 */
class OcrDePlantillasTest {

    @Test
    @DisplayName("Un nombre de clase se lee exacto")
    void nombre_de_clase() {
        OcrDePlantillas.Lectura leido = leer("Estudiante", 16, RolDeTexto.NOMBRE_CLASE);

        assertThat(leido).isNotNull();
        assertThat(leido.texto()).isEqualTo("Estudiante");
        assertThat(leido.confianza()).isGreaterThan(0.6);
    }

    @Test
    @DisplayName("Un atributo se lee con su tipo")
    void atributo_con_tipo() {
        // Los dos puntos y el espacio importan: es lo que despues parte el atributo
        // en nombre y tipo cuando la propuesta aterriza en el modelo.
        assertThat(leer("nombre: String", 16, RolDeTexto.ATRIBUTO).texto())
                .isEqualTo("nombre: String");
    }

    @Test
    @DisplayName("Una firma de metodo se lee con los parentesis")
    void metodo() {
        assertThat(leer("getNombre()", 16, RolDeTexto.METODO).texto()).isEqualTo("getNombre()");
    }

    @ParameterizedTest
    @ValueSource(strings = { "0..1", "1..*" })
    @DisplayName("Una cardinalidad se lee aunque el texto sea chico")
    void cardinalidad(String cardinalidad) {
        // El alfabeto de este rol no tiene ni una letra, y por eso es el mas
        // confiable de todos: no existe la confusion del 0 con la O ni del 1 con la
        // l. Que se lea bien decide si la FK derivada sale NOT NULL o no.
        OcrDePlantillas.Lectura leido = leer(cardinalidad, 12, RolDeTexto.CARDINALIDAD);

        assertThat(leido).isNotNull();
        // Sin espacios: la multiplicidad se guarda tal cual en el modelo, y de ahi
        // salen la decision de NOT NULL y la de tabla puente.
        assertThat(leido.texto()).isEqualTo(cardinalidad);
    }

    @Test
    @DisplayName("Un screenshot en tema oscuro se lee igual que uno claro")
    void tema_oscuro() {
        BufferedImage img = render("Docente", 16, new Color(24, 24, 27), new Color(240, 240, 240));

        // La polaridad la resuelve la binarizacion del recorte: la tinta es la clase
        // MINORITARIA, no la oscura. Un tema oscuro es uno de los casos faciles y
        // seria absurdo perderlo.
        assertThat(OcrDePlantillas.leer(img, RolDeTexto.NOMBRE_CLASE).texto()).isEqualTo("Docente");
    }

    @Test
    @DisplayName("Unas manchas que no son letras no se leen como texto")
    void no_inventa_sobre_manchas() {
        BufferedImage img = new BufferedImage(120, 24, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 120, 24);
        g.setColor(Color.BLACK);
        g.fillOval(6, 4, 14, 17);
        g.fillOval(30, 9, 9, 6);
        g.fillOval(48, 2, 20, 20);
        g.fillOval(80, 7, 6, 14);
        g.dispose();

        // Es la propiedad que justifica el metodo entero. Un modelo chico contesta
        // siempre --y contesta algo plausible-- mientras esto contesta null, que es
        // una celda vacia que el usuario ve y completa.
        assertThat(OcrDePlantillas.leer(img, RolDeTexto.NOMBRE_CLASE)).isNull();
    }

    @Test
    @DisplayName("Un recorte demasiado chico se descarta antes de comparar nada")
    void recorte_minusculo() {
        assertThat(OcrDePlantillas.leer(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB),
                RolDeTexto.NOMBRE_CLASE)).isNull();
    }

    @ParameterizedTest
    @ValueSource(ints = { 9, 10, 11, 12, 14, 18, 22 })
    @DisplayName("A cualquier tamano, o lee bien o no lee: nunca devuelve otra cosa")
    void nunca_devuelve_algo_distinto(int tamano) {
        OcrDePlantillas.Lectura leido = leer("Persona", tamano, RolDeTexto.NOMBRE_CLASE);

        // El contrato no es "lee siempre" --a 9 px no hay con que-- sino que lo que
        // devuelve es correcto. Una lectura equivocada con confianza alta es el
        // unico resultado inaceptable: nadie la revisa.
        assertThat(leido == null || "Persona".equals(leido.texto()))
                .as("leyo %s", leido == null ? "null" : leido.texto())
                .isTrue();
    }

    @Test
    @DisplayName("La misma imagen da siempre la misma lectura")
    void es_deterministico() {
        // No es una obviedad: es la diferencia con un modelo, y es lo que permite
        // que un fallo de lectura se pueda reproducir y arreglar en vez de discutir.
        OcrDePlantillas.Lectura una = leer("Libros", 16, RolDeTexto.NOMBRE_CLASE);
        OcrDePlantillas.Lectura otra = leer("Libros", 16, RolDeTexto.NOMBRE_CLASE);

        assertThat(una.texto()).isEqualTo(otra.texto());
        assertThat(una.confianza()).isEqualTo(otra.confianza());
    }

    // ------------------------------------------------------------- UTILES

    private static OcrDePlantillas.Lectura leer(String texto, int tamano, RolDeTexto rol) {
        return OcrDePlantillas.leer(render(texto, tamano, Color.WHITE, Color.BLACK), rol);
    }

    /** Un renglon como el que recorta el nivel 1: el texto justo, con 3 px de aire. */
    private static BufferedImage render(String texto, int tamano, Color fondo, Color tinta) {
        Font fuente = new Font(Font.SANS_SERIF, Font.PLAIN, tamano);
        BufferedImage medida = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        Graphics2D gm = medida.createGraphics();
        gm.setFont(fuente);
        int ancho = gm.getFontMetrics().stringWidth(texto) + 6;
        int alto = gm.getFontMetrics().getHeight() + 4;
        int linea = alto - 4 - gm.getFontMetrics().getDescent();
        gm.dispose();

        BufferedImage img = new BufferedImage(ancho, alto, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(fondo);
        g.fillRect(0, 0, ancho, alto);
        // Con antialiasing porque asi viene cualquier captura real; sin el, el test
        // seria mas facil que el caso que tiene que representar.
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(fuente);
        g.setColor(tinta);
        g.drawString(texto, 3, linea);
        g.dispose();
        return img;
    }
}
