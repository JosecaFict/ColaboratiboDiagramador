package com.uagrm.colabmodel.vision;

import com.uagrm.colabmodel.project.AccesoAProyecto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LINEA BASE de lectura de imagenes (encargo PARALELO-FASE0-IMAGENES, Pepe).
 *
 * Mide como lee HOY el sistema, sin ningun modelo, para poder comparar las
 * fases 2 y 5 del plan contra algo. No arregla nada: si el detector se
 * equivoca, la tabla lo dice y el test sigue en verde. Lo que SI se afirma
 * (y falla si cambia) es la degradacion del controlador.
 *
 * Las 6 imagenes se dibujan aca con Java2D (texto simulado con guiones, como
 * en DetectorGeometricoTest) y se guardan en src/test/resources/vision/linea-base/
 * la primera vez, o siempre con -Dvision.regenerar=true. La tabla se escribe
 * en target/vision-linea-base.md.
 */
class LineaBaseVisionTest {

    private static final Path CARPETA = Path.of("src", "test", "resources", "vision", "linea-base");
    private static final Path TABLA = Path.of("target", "vision-linea-base.md");
    private static final int REPETICIONES = 7;

    /** Una imagen de prueba y lo que de verdad dibuja. */
    private record Caso(String archivo, String descripcion, int clasesReales, int relacionesReales,
                        BufferedImage imagen) { }

    // ------------------------------------------------------------ LA TABLA

    @Test
    @DisplayName("Linea base: nivel 1 solo y con la cadena sin modelo del controlador")
    void tabla_de_linea_base() throws IOException {
        List<Caso> casos = casos();
        guardar(casos);

        DetectorGeometrico detector = new DetectorGeometrico();
        EnriquecedorGeometrico geometrico = new EnriquecedorGeometrico();
        EnriquecedorPorPlantillas plantillas = new EnriquecedorPorPlantillas(true);

        StringBuilder md = new StringBuilder();
        md.append("| Imagen | Clases reales | Clases detectadas | Rel. reales | Rel. detectadas | ")
          .append("Nivel 1 ms (mediana de ").append(REPETICIONES).append(") | Primera vez ms | ")
          .append("Cadena sin modelo ms | Binarizacion | Enderezado | Avisos |\n");
        md.append("|---|---|---|---|---|---|---|---|---|---|---|\n");

        // Calentamiento fuera de la medicion: la primera lectura del proceso paga AWT.
        detector.detectar(entrada(casos.get(0).imagen()));

        for (Caso c : casos) {
            ImagenDeEntrada entrada = entrada(c.imagen());

            long t0 = System.nanoTime();
            DeteccionPropuesta primera = detector.detectar(entrada);
            long primeraMs = (System.nanoTime() - t0) / 1_000_000;

            long[] tiempos = new long[REPETICIONES];
            DeteccionPropuesta p = primera;
            for (int i = 0; i < REPETICIONES; i++) {
                long a = System.nanoTime();
                p = detector.detectar(entrada);
                tiempos[i] = (System.nanoTime() - a) / 1_000_000;
            }
            Arrays.sort(tiempos);

            // La cadena que corre en produccion sin modelo ni clave: geometrico + plantillas.
            long b = System.nanoTime();
            DeteccionPropuesta conSimbolos = geometrico.enriquecer(p, entrada);
            long simbolosMs = (System.nanoTime() - b) / 1_000_000;
            long b2 = System.nanoTime();
            DeteccionPropuesta cadena = plantillas.enriquecer(conSimbolos, entrada);
            long plantillasMs = (System.nanoTime() - b2) / 1_000_000;
            long cadenaMs = simbolosMs + plantillasMs;

            // Enriquecer no puede cambiar lo que vio el nivel 1.
            assertThat(cadena.clases()).hasSameSizeAs(p.clases());
            assertThat(cadena.relaciones()).hasSameSizeAs(p.relaciones());

            md.append("| ").append(c.archivo())
              .append(" | ").append(c.clasesReales())
              .append(" | ").append(p.clases().size())
              .append(" | ").append(c.relacionesReales())
              .append(" | ").append(p.relaciones().size())
              .append(" | ").append(tiempos[REPETICIONES / 2])
              .append(" | ").append(primeraMs)
              .append(" | ").append(cadenaMs).append(" (simb. ").append(simbolosMs).append(" + plant. ").append(plantillasMs).append(")")
              .append(" | ").append(p.metricas().binarizacion())
              .append(" | ").append(p.metricas().enderezado() == null ? "no"
                      : String.format("%.1f°", p.metricas().enderezado().grados()))
              .append(" | ").append(p.avisos().isEmpty() ? "0" : p.avisos().size() + ": " + String.join(" / ", p.avisos()))
              .append(" |\n");
        }

        Files.createDirectories(TABLA.getParent());
        Files.writeString(TABLA, md.toString(), StandardCharsets.UTF_8);
        System.out.println("\n" + md);
    }

    // ------------------------------------------------------ LA DEGRADACION

    @Test
    @DisplayName("Un enriquecedor que devuelve null no pierde la deteccion")
    void enriquecedor_que_devuelve_null() {
        for (Caso c : casos()) {
            DeteccionPropuesta sola = new DetectorGeometrico().detectar(entrada(c.imagen()));
            DeteccionPropuesta conNull = llamar(List.of(new Doble("null", true, null, null)), c);

            assertThat(conNull.clases()).hasSameSizeAs(sola.clases());
            assertThat(conNull.relaciones()).hasSameSizeAs(sola.relaciones());
        }
    }

    @Test
    @DisplayName("Un enriquecedor que lanza excepcion no pierde la deteccion")
    void enriquecedor_que_lanza() {
        for (Caso c : casos()) {
            DeteccionPropuesta sola = new DetectorGeometrico().detectar(entrada(c.imagen()));
            DeteccionPropuesta conFallo = llamar(
                    List.of(new Doble("falla", true, null, new IllegalStateException("modelo caido"))), c);

            assertThat(conFallo.clases()).hasSameSizeAs(sola.clases());
            assertThat(conFallo.relaciones()).hasSameSizeAs(sola.relaciones());
        }
    }

    @Test
    @DisplayName("Un enriquecedor no disponible ni se llama")
    void enriquecedor_no_disponible() {
        Doble caido = new Doble("caido", false, null, new IllegalStateException("no deberia llamarse"));
        llamar(List.of(caido), casos().get(0));
        assertThat(caido.llamadas).isZero();
    }

    @Test
    @DisplayName("Una capa que falla no anula a la que sigue: se encadena con lo que habia")
    void capa_que_falla_en_medio_de_la_cadena() {
        Caso c = casos().get(1);
        DeteccionPropuesta sola = new DetectorGeometrico().detectar(entrada(c.imagen()));
        Doble despues = new Doble("despues", true, null, null);
        // Esta devuelve SU propia propuesta distinta (sin clases): la cadena la acepta tal cual.
        DeteccionPropuesta recortada = new DeteccionPropuesta(List.of(), List.of(), List.of("recortada"),
                sola.metricas());
        Doble recorta = new Doble("recorta", true, recortada, null);

        DeteccionPropuesta resultado = llamar(List.of(
                new Doble("falla", true, null, new IllegalStateException("x")), recorta, despues), c);

        // Hallazgo: el controlador NO valida que el enriquecedor respete el contrato
        // ("no inventar ni quitar clases"): una capa que devuelve menos clases las pierde.
        assertThat(resultado.clases()).isEmpty();
        assertThat(despues.llamadas).isEqualTo(1);
        assertThat(sola.clases()).isNotEmpty();
    }

    @Test
    @DisplayName("Un VIEWER recibe 403 y el detector ni se ejecuta")
    void viewer_no_ejecuta_nada() {
        DetectorContador detector = new DetectorContador();
        VisionRestController controlador = controlador(detector, List.of(), AccesoAProyecto.Rol.VIEWER);

        org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () -> controlador.detectar(UUID.randomUUID(), archivo(casos().get(0)), principal()));

        assertThat(detector.llamadas).isZero();
    }

    // ------------------------------------------------------------- DIBUJO

    private static List<Caso> casos() {
        List<Caso> l = new ArrayList<>();

        BufferedImage a = lienzo(800, 400);
        Graphics2D ga = pincel(a);
        clase(ga, 60, 80);
        clase(ga, 440, 80);
        ga.dispose();
        l.add(new Caso("01-dos-clases-sin-relaciones.png", "2 clases, sin relaciones", 2, 0, a));

        BufferedImage b = lienzo(800, 400);
        Graphics2D gb = pincel(b);
        clase(gb, 60, 80);
        clase(gb, 440, 80);
        gb.drawLine(260, 140, 440, 140);
        gb.dispose();
        l.add(new Caso("02-dos-clases-con-relacion.png", "2 clases, 1 relacion", 2, 1, b));

        l.add(new Caso("03-cuatro-clases-con-relaciones.png", "4 clases, 3 relaciones", 4, 3, cuatro()));

        BufferedImage d = lienzo(1500, 600);
        Graphics2D gd = pincel(d);
        for (int fila = 0; fila < 2; fila++) {
            for (int col = 0; col < 4; col++) clase(gd, 40 + col * 360, 60 + fila * 280);
            for (int col = 0; col < 3; col++) gd.drawLine(240 + col * 360, 120 + fila * 280, 400 + col * 360, 120 + fila * 280);
        }
        gd.dispose();
        l.add(new Caso("04-ocho-clases-con-relaciones.png", "8 clases, 6 relaciones", 8, 6, d));

        l.add(new Caso("05-cuatro-clases-girada-5.png", "4 clases, 3 relaciones, girada 5 grados", 4, 3,
                girar(cuatro(), 5)));

        l.add(new Caso("06-cuatro-clases-con-ruido.png", "4 clases, 3 relaciones, ruido sal y pimienta 1.5 %", 4, 3,
                ruido(cuatro(), 0.015, 42)));
        return l;
    }

    /** 4 clases en una fila de dos y otra de dos, con tres lineas: dos horizontales y una vertical. */
    private static BufferedImage cuatro() {
        BufferedImage img = lienzo(900, 600);
        Graphics2D g = pincel(img);
        clase(g, 60, 60);
        clase(g, 520, 60);
        clase(g, 60, 340);
        clase(g, 520, 340);
        g.drawLine(260, 120, 520, 120);          // c1 - c2
        g.drawLine(260, 400, 520, 400);          // c3 - c4
        g.drawLine(160, 200, 160, 340);          // c1 - c3
        g.dispose();
        return img;
    }

    /** Una clase de 200x140 con tres compartimentos y texto simulado. */
    private static void clase(Graphics2D g, int x, int y) {
        g.drawRect(x, y, 200, 140);
        g.drawLine(x, y + 30, x + 200, y + 30);
        g.drawLine(x, y + 90, x + 200, y + 90);
        renglon(g, x + 20, y + 10, 120);
        renglon(g, x + 20, y + 42, 140);
        renglon(g, x + 20, y + 57, 110);
        renglon(g, x + 20, y + 72, 130);
        renglon(g, x + 20, y + 102, 120);
        renglon(g, x + 20, y + 117, 100);
    }

    private static BufferedImage girar(BufferedImage origen, double grados) {
        BufferedImage destino = lienzo(origen.getWidth(), origen.getHeight());
        Graphics2D g = destino.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        AffineTransform t = AffineTransform.getRotateInstance(Math.toRadians(grados),
                origen.getWidth() / 2.0, origen.getHeight() / 2.0);
        g.drawImage(origen, t, null);
        g.dispose();
        return destino;
    }

    private static BufferedImage ruido(BufferedImage origen, double fraccion, long semilla) {
        BufferedImage img = lienzo(origen.getWidth(), origen.getHeight());
        Graphics2D g = img.createGraphics();
        g.drawImage(origen, 0, 0, null);
        g.dispose();
        Random r = new Random(semilla);
        int n = (int) (img.getWidth() * img.getHeight() * fraccion);
        for (int i = 0; i < n; i++) {
            int x = r.nextInt(img.getWidth());
            int y = r.nextInt(img.getHeight());
            img.setRGB(x, y, r.nextBoolean() ? Color.BLACK.getRGB() : Color.WHITE.getRGB());
        }
        return img;
    }

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
        g.setColor(Color.BLACK);
        g.setStroke(new BasicStroke(2));
        return g;
    }

    private static void renglon(Graphics2D g, int x, int y, int ancho) {
        for (int i = 0; i < ancho; i += 12) g.fillRect(x + i, y, 8, 4);
    }

    private static byte[] png(BufferedImage img) {
        try {
            ByteArrayOutputStream salida = new ByteArrayOutputStream();
            ImageIO.write(img, "png", salida);
            return salida.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static ImagenDeEntrada entrada(BufferedImage img) {
        return ImagenDeEntrada.desde(png(img), "image/png", ImagenDeEntrada.Limites.POR_DEFECTO);
    }

    private static void guardar(List<Caso> casos) throws IOException {
        boolean regenerar = Boolean.getBoolean("vision.regenerar");
        Files.createDirectories(CARPETA);
        for (Caso c : casos) {
            Path destino = CARPETA.resolve(c.archivo());
            if (regenerar || !Files.exists(destino)) Files.write(destino, png(c.imagen()));
        }
    }

    // ------------------------------------------------ CONTROLADOR Y DOBLES

    private static DeteccionPropuesta llamar(List<EnriquecedorDeDeteccion> cadena, Caso c) {
        VisionRestController controlador = controlador(new DetectorGeometrico(), cadena, AccesoAProyecto.Rol.EDITOR);
        return controlador.detectar(UUID.randomUUID(), archivo(c), principal());
    }

    private static MockMultipartFile archivo(Caso c) {
        return new MockMultipartFile("imagen", c.archivo(), "image/png", png(c.imagen()));
    }

    private static Principal principal() {
        UUID usuario = UUID.randomUUID();
        return usuario::toString;
    }

    private static VisionRestController controlador(DetectorDeDiagrama detector,
                                                    List<EnriquecedorDeDeteccion> cadena,
                                                    AccesoAProyecto.Rol rol) {
        AccesoAProyecto acceso = new AccesoAProyecto(null, null, null) {
            @Override
            public Optional<Rol> rolSobreDiagrama(UUID diagramId, UUID userId) {
                return Optional.of(rol);
            }
        };
        return new VisionRestController(detector, cadena, acceso, 8L * 1024 * 1024, 40_000_000L, 1600);
    }

    private static final class Doble implements EnriquecedorDeDeteccion {
        private final String nombre;
        private final boolean disponible;
        private final DeteccionPropuesta devuelve;
        private final RuntimeException lanza;
        int llamadas;

        Doble(String nombre, boolean disponible, DeteccionPropuesta devuelve, RuntimeException lanza) {
            this.nombre = nombre;
            this.disponible = disponible;
            this.devuelve = devuelve;
            this.lanza = lanza;
        }

        @Override
        public DeteccionPropuesta enriquecer(DeteccionPropuesta base, ImagenDeEntrada imagen) {
            llamadas++;
            if (lanza != null) throw lanza;
            return devuelve;
        }

        @Override public String nombre() { return nombre; }

        @Override public boolean disponible() { return disponible; }
    }

    private static final class DetectorContador implements DetectorDeDiagrama {
        int llamadas;

        @Override
        public DeteccionPropuesta detectar(ImagenDeEntrada imagen) {
            llamadas++;
            return DeteccionPropuesta.vacia(null, List.of());
        }

        @Override public String nombre() { return "contador"; }
    }
}

