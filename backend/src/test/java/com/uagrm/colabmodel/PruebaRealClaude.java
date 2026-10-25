package com.uagrm.colabmodel;

import com.uagrm.colabmodel.ai.ClienteClaude;
import com.uagrm.colabmodel.ai.ComandoPropuestoIA;
import com.uagrm.colabmodel.ai.EsquemaDeComandos;
import com.uagrm.colabmodel.ai.ProveedorClaude;
import com.uagrm.colabmodel.ai.RespuestaIA;
import com.uagrm.colabmodel.ai.SolicitudIA;
import com.uagrm.colabmodel.vision.DeteccionPropuesta;
import com.uagrm.colabmodel.vision.DeteccionPropuesta.ClaseDetectada;
import com.uagrm.colabmodel.vision.DeteccionPropuesta.RecorteDeTexto;
import com.uagrm.colabmodel.vision.DetectorGeometrico;
import com.uagrm.colabmodel.vision.EnriquecedorPorClaude;
import com.uagrm.colabmodel.vision.ImagenDeEntrada;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prueba CONTRA LA API REAL de Claude: gasta credito (unos centavos) y
 * necesita red. Por eso no se llama *Test y `mvn test` no la corre: se pide a
 * mano, y solo si ANTHROPIC_API_KEY esta definida.
 *
 * <pre>
 * mvn test -Dtest=PruebaRealClaude
 * </pre>
 *
 * Usa las mismas clases que la aplicacion, con los mismos valores que trae
 * application.yml (modelo, esfuerzo, modo rapido), asi que si esto anda, la IA
 * de la app anda.
 */
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = "sk-ant-.+")
class PruebaRealClaude {

    private static final ClienteClaude CLAUDE = new ClienteClaude(System.getenv("ANTHROPIC_API_KEY"),
            "claude-opus-5", "low", true, "https://api.anthropic.com", 60_000);

    @Test
    @DisplayName("Texto: un pedido en espanol vuelve como comandos del catalogo")
    void texto() {
        SolicitudIA pedido = new SolicitudIA(
                "Crea la clase Libro con titulo e isbn, y la clase Autor con nombre. "
                        + "Un autor escribe muchos libros.",
                List.of(), EsquemaDeComandos.herramientas());

        long inicio = System.currentTimeMillis();
        RespuestaIA r = new ProveedorClaude(CLAUDE, 60_000).generar(pedido);
        long ms = System.currentTimeMillis() - inicio;

        System.out.println("\n===== TEXTO: " + r.comandos().size() + " comandos en " + ms + " ms =====");
        for (ComandoPropuestoIA c : r.comandos()) System.out.println("  " + c.tipo() + " " + c.parametros());
        System.out.println("  nota: " + r.notaDelModelo());
        informarExtras();

        // Todo en UNA respuesta: no solo las clases, tambien lo que depende de ellas.
        assertThat(r.comandos()).extracting(ComandoPropuestoIA::tipo)
                .contains("CREATE_CLASS", "ADD_ATTRIBUTE", "CREATE_RELATION");
    }

    @Test
    @DisplayName("Imagen: Claude lee los nombres que el nivel 1 recorto")
    void imagen() throws IOException {
        ImagenDeEntrada imagen = diagrama();
        DeteccionPropuesta base = new DetectorGeometrico().detectar(imagen);

        long inicio = System.currentTimeMillis();
        DeteccionPropuesta leida = new EnriquecedorPorClaude(CLAUDE, 30_000, 80).enriquecer(base, imagen);
        long ms = System.currentTimeMillis() - inicio;

        System.out.println("\n===== IMAGEN: " + leida.clases().size() + " clases, "
                + leida.relaciones().size() + " relaciones, leidas en " + ms + " ms =====");
        for (ClaseDetectada c : leida.clases()) {
            System.out.println("  clase " + c.nombre() + " -> "
                    + c.atributos().stream().map(RecorteDeTexto::texto).toList());
        }
        leida.avisos().forEach(a -> System.out.println("  aviso: " + a));
        informarExtras();

        assertThat(leida.clases()).extracting(ClaseDetectada::nombre).contains("Libro", "Autor");
        // Los atributos privados ("- titulo: String") tambien tienen que pasar el filtro.
        assertThat(leida.clases()).flatExtracting(ClaseDetectada::atributos)
                .extracting(RecorteDeTexto::texto).doesNotContainNull();
    }

    private static void informarExtras() {
        System.out.println(!CLAUDE.extrasActivos()
                ? "  modo rapido y fallback: la cuenta no los tiene, se apagaron (anda a velocidad normal)"
                : CLAUDE.modoRapidoDisponible()
                        ? "  modo rapido y fallback: ACTIVOS"
                        : "  fallback ACTIVO; modo rapido en pausa porque estaba saturado (429)");
    }

    /** Dos clases con texto de verdad y una asociacion, como una captura de pantalla. */
    private static ImagenDeEntrada diagrama() throws IOException {
        BufferedImage img = new BufferedImage(900, 360, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 900, 360);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(Color.BLACK);
        g.setStroke(new BasicStroke(2));
        clase(g, 60, 60, "Libro", "- titulo: String", "- isbn: String");
        clase(g, 560, 60, "Autor", "- nombre: String");
        g.drawLine(340, 150, 560, 150);
        g.dispose();
        ByteArrayOutputStream salida = new ByteArrayOutputStream();
        ImageIO.write(img, "png", salida);
        return ImagenDeEntrada.desde(salida.toByteArray(), "image/png", ImagenDeEntrada.Limites.POR_DEFECTO);
    }

    private static void clase(Graphics2D g, int x, int y, String nombre, String... atributos) {
        g.drawRect(x, y, 280, 180);
        g.drawLine(x, y + 50, x + 280, y + 50);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 22));
        g.drawString(nombre, x + 100, y + 33);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 20));
        for (int i = 0; i < atributos.length; i++) g.drawString(atributos[i], x + 20, y + 85 + i * 35);
    }
}
