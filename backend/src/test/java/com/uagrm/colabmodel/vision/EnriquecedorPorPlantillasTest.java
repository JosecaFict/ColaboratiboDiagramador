package com.uagrm.colabmodel.vision;

import com.uagrm.colabmodel.vision.DeteccionPropuesta.ClaseDetectada;
import com.uagrm.colabmodel.vision.DeteccionPropuesta.RecorteDeTexto;
import com.uagrm.colabmodel.vision.DeteccionPropuesta.RolDeTexto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El camino completo sin nada instalado: imagen -> geometria -> texto.
 *
 * Es el test que mide lo que el usuario nota. Antes de esta capa, una captura de un
 * diagrama daba todas las cajas en su lugar y CERO nombres: el nivel 1 le ahorraba
 * el dibujo y nada del texto, que es la mayor parte del trabajo de cargar un
 * diagrama a mano.
 */
class EnriquecedorPorPlantillasTest {

    private final DetectorGeometrico detector = new DetectorGeometrico();
    private final EnriquecedorPorPlantillas enriquecedor = new EnriquecedorPorPlantillas(true);

    @Test
    @DisplayName("Una captura de dos clases sale con los nombres leidos")
    void lee_los_nombres_de_un_diagrama() {
        BufferedImage img = lienzo(900, 420);
        Graphics2D g = pincel(img);
        clase(g, 60, 60, 260, 150, "Estudiante", List.of("nombre: String", "edad: int"));
        clase(g, 520, 60, 260, 150, "Docente", List.of("titulo: String"));
        g.drawLine(320, 135, 520, 135);
        g.dispose();
        ImagenDeEntrada imagen = entrada(img);

        DeteccionPropuesta base = detector.detectar(imagen);
        DeteccionPropuesta leida = enriquecedor.enriquecer(base, imagen);

        assertThat(base.clases()).hasSize(2);
        // Sin esta capa, esto era null en las dos.
        assertThat(leida.clases()).extracting(ClaseDetectada::nombre)
                .containsExactly("Estudiante", "Docente");
        assertThat(leida.clases().get(0).atributos()).extracting(RecorteDeTexto::texto)
                .containsExactly("nombre: String", "edad: int");
        assertThat(leida.clases().get(0).recorteNombre().confianza()).isGreaterThan(0.5);
    }

    @Test
    @DisplayName("La geometria y la topologia no se tocan: solo se llenan los textos")
    void no_cambia_la_geometria() {
        BufferedImage img = lienzo(900, 420);
        Graphics2D g = pincel(img);
        clase(g, 60, 60, 260, 150, "Libros", List.of("isbn: String"));
        clase(g, 520, 60, 260, 150, "Revistas", List.of("numero: int"));
        g.drawLine(320, 135, 520, 135);
        g.dispose();
        ImagenDeEntrada imagen = entrada(img);

        DeteccionPropuesta base = detector.detectar(imagen);
        DeteccionPropuesta leida = enriquecedor.enriquecer(base, imagen);

        // El contrato del nivel 2 es no inventar ni mover nada: las cajas, las
        // relaciones y el lienzo salen identicos, y solo cambian los textos.
        assertThat(leida.clases()).extracting(ClaseDetectada::caja)
                .isEqualTo(base.clases().stream().map(ClaseDetectada::caja).toList());
        assertThat(leida.relaciones()).hasSameSizeAs(base.relaciones());
        assertThat(leida.metricas()).isEqualTo(base.metricas());
    }

    @Test
    @DisplayName("Lo que otra capa ya leyo no se vuelve a leer ni se pisa")
    void respeta_lo_ya_leido() {
        BufferedImage img = lienzo(600, 300);
        Graphics2D g = pincel(img);
        clase(g, 60, 60, 260, 140, "Persona", List.of("ci: String"));
        g.dispose();
        ImagenDeEntrada imagen = entrada(img);

        DeteccionPropuesta base = detector.detectar(imagen);
        ClaseDetectada clase = base.clases().get(0);
        RecorteDeTexto yaLeido = new RecorteDeTexto(clase.recorteNombre().ref(),
                RolDeTexto.NOMBRE_CLASE, clase.recorteNombre().caja(), "PuestoAMano", 0.99);
        DeteccionPropuesta conTexto = new DeteccionPropuesta(
                List.of(new ClaseDetectada(clase.ref(), clase.caja(), clase.lienzo(),
                        "PuestoAMano", yaLeido, clase.atributos(), clase.metodos(),
                        clase.compartimentos(), clase.confianza())),
                base.relaciones(), base.avisos(), base.metricas());

        DeteccionPropuesta leida = enriquecedor.enriquecer(conTexto, imagen);

        // La cadena de enriquecedores se SUMA, no se sobreescribe: es el mismo
        // contrato que cumple el geometrico con los simbolos.
        assertThat(leida.clases().get(0).nombre()).isEqualTo("PuestoAMano");
        assertThat(leida.clases().get(0).recorteNombre().confianza()).isEqualTo(0.99);
        // Y el atributo, que estaba vacio, si se lee.
        assertThat(leida.clases().get(0).atributos().get(0).texto()).isEqualTo("ci: String");
    }

    @Test
    @DisplayName("El aviso dice cuantos textos se leyeron y que hay que revisarlos")
    void avisa_cuanto_leyo() {
        BufferedImage img = lienzo(600, 300);
        Graphics2D g = pincel(img);
        clase(g, 60, 60, 260, 140, "Periodicos", List.of("fecha: Date"));
        g.dispose();
        ImagenDeEntrada imagen = entrada(img);

        DeteccionPropuesta leida = enriquecedor.enriquecer(detector.detectar(imagen), imagen);

        // Que la mitad de los renglones quede en blanco es un resultado normal, pero
        // sin decirlo parece un error del sistema; y lo que si se leyo hay que
        // mirarlo antes de confirmar. Las dos mitades de la Regla 10.
        assertThat(leida.avisos()).anySatisfy(a -> assertThat(a).contains("lector local"));
    }

    @Test
    @DisplayName("Apagado por configuracion, la capa se declara no disponible")
    void se_puede_apagar() {
        // El controlador ni la llama, y la propuesta sale como antes: con los
        // recortes ubicados y los textos en null.
        assertThat(new EnriquecedorPorPlantillas(false).disponible()).isFalse();
        assertThat(enriquecedor.nombre()).contains("plantillas");
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

    private static Graphics2D pincel(BufferedImage img) {
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(Color.BLACK);
        g.setStroke(new BasicStroke(2));
        return g;
    }

    /** Una clase UML con su nombre y su compartimento de atributos, como la dibuja cualquier herramienta. */
    private static void clase(Graphics2D g, int x, int y, int ancho, int alto,
                              String nombre, List<String> atributos) {
        g.drawRect(x, y, ancho, alto);
        g.drawLine(x, y + 34, x + ancho, y + 34);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 16));
        g.drawString(nombre, x + 10, y + 24);
        int renglon = y + 34 + 22;
        for (String atributo : atributos) {
            g.drawString(atributo, x + 10, renglon);
            renglon += 24;
        }
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
