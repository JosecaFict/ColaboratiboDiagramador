package com.uagrm.colabmodel.vision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El nivel 2 contra un servidor de modelo simulado.
 *
 * Se levanta un HTTP de verdad --el del JDK, sin dependencias-- en vez de
 * simular el cliente: asi lo que se prueba incluye el JSON que se manda, que es
 * justo donde se rompe la integracion con Ollama o llama.cpp.
 *
 * No hace falta ningun modelo para correr esto, y esa es la idea: el dia que
 * haya uno, lo unico que cambia es que las respuestas dejan de ser de mentira.
 */
class EnriquecedorPorModeloTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private HttpServer servidor;
    private final List<String> peticiones = new CopyOnWriteArrayList<>();
    private Function<String, String> responder = cuerpo -> "Libro";
    private int estado = 200;
    private long demoraMs = 0;

    @BeforeEach
    void levantarModeloFalso() throws IOException {
        servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

        servidor.createContext("/v1/models", intercambio -> {
            byte[] cuerpo = "{\"data\":[]}".getBytes(StandardCharsets.UTF_8);
            intercambio.sendResponseHeaders(200, cuerpo.length);
            intercambio.getResponseBody().write(cuerpo);
            intercambio.close();
        });

        servidor.createContext("/v1/chat/completions", intercambio -> {
            String entrada = new String(intercambio.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            peticiones.add(entrada);
            if (demoraMs > 0) {
                try {
                    Thread.sleep(demoraMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] cuerpo = respuestaChat(responder.apply(entrada)).getBytes(StandardCharsets.UTF_8);
            intercambio.sendResponseHeaders(estado, cuerpo.length);
            intercambio.getResponseBody().write(cuerpo);
            intercambio.close();
        });

        servidor.start();
    }

    @AfterEach
    void bajarModeloFalso() {
        servidor.stop(0);
    }

    private String url() {
        return "http://127.0.0.1:" + servidor.getAddress().getPort();
    }

    private EnriquecedorPorModelo enriquecedor() {
        return new EnriquecedorPorModelo(url(), "modelo-de-prueba", 20_000, 5_000);
    }

    // --------------------------------------------------------- DISPONIBLE

    @Test
    @DisplayName("Sin URL configurada, el nivel 2 no existe y no se intenta nada")
    void sin_configurar() {
        EnriquecedorPorModelo sinUrl = new EnriquecedorPorModelo("", "m", 1000, 1000);

        assertThat(sinUrl.disponible()).isFalse();
        assertThat(sinUrl.nombre()).contains("sin configurar");
    }

    @Test
    @DisplayName("Con el modelo levantado, disponible() da true")
    void disponible() {
        assertThat(enriquecedor().disponible()).isTrue();
    }

    @Test
    @DisplayName("Si el modelo no responde, disponible() da false y no tira")
    void caido() {
        EnriquecedorPorModelo aPuertoMuerto =
                new EnriquecedorPorModelo("http://127.0.0.1:1", "m", 1000, 500);

        assertThat(aPuertoMuerto.disponible()).isFalse();
    }

    // ------------------------------------------------------------ LECTURA

    @Test
    @DisplayName("Lo que lee el modelo llena el nombre de la clase")
    void llena_los_nombres() {
        DeteccionPropuesta base = detectar();
        assertThat(base.clases().get(0).nombre()).as("el nivel 1 no lee texto").isNull();

        DeteccionPropuesta leida = enriquecedor().enriquecer(base, imagen());

        assertThat(leida.clases().get(0).nombre()).isEqualTo("Libro");
        assertThat(leida.clases().get(0).recorteNombre().texto()).isEqualTo("Libro");
        assertThat(leida.clases().get(0).recorteNombre().confianza()).isGreaterThan(0);
        // La geometria no se toca: eso ya lo decidio el nivel 1.
        assertThat(leida.clases().get(0).caja()).isEqualTo(base.clases().get(0).caja());
    }

    @Test
    @DisplayName("NUNCA manda la imagen entera: solo el recorte")
    void solo_recortes() throws IOException {
        // Es LA decision de diseño del nivel 2. Con la imagen entera, un modelo
        // chico tarda minutos y contesta cualquier cosa; con recortes de 200x60
        // contesta rapido y acierta. Si esto se rompe, el nivel 2 deja de ser
        // viable en una maquina sin GPU y nadie se entera hasta probarlo.
        ImagenDeEntrada entrada = imagen();
        enriquecedor().enriquecer(detectar(), entrada);

        assertThat(peticiones).isNotEmpty();
        for (String peticion : peticiones) {
            BufferedImage enviada = imagenDe(peticion);
            assertThat(enviada.getWidth()).isLessThan(entrada.ancho());
            assertThat(enviada.getHeight()).isLessThan(entrada.alto());
            // Un recorte de texto o de simbolo es chico de verdad.
            assertThat((long) enviada.getWidth() * enviada.getHeight())
                    .isLessThan((long) entrada.ancho() * entrada.alto() / 4);
        }
    }

    @Test
    @DisplayName("Pide determinismo: temperatura 0 y respuesta corta")
    void pregunta_acotada() throws IOException {
        enriquecedor().enriquecer(detectar(), imagen());

        JsonNode peticion = JSON.readTree(peticiones.get(0));
        // La misma imagen tiene que leerse igual dos veces, o el usuario no
        // puede confiar en lo que confirmo.
        assertThat(peticion.get("temperature").asInt()).isZero();
        // Un nombre de clase no son 500 tokens: el tope corta al modelo que se
        // pone a explicar, que es el modo de falla mas comun de los chicos.
        assertThat(peticion.get("max_tokens").asInt()).isLessThanOrEqualTo(64);
        assertThat(peticion.get("model").asText()).isEqualTo("modelo-de-prueba");
    }

    @Test
    @DisplayName("Le dice al modelo QUE espera encontrar en cada recorte")
    void la_consigna_dice_el_rol() throws IOException {
        enriquecedor().enriquecer(detectar(), imagen());

        String consigna = JSON.readTree(peticiones.get(0))
                .path("messages").path(0).path("content").path(0).path("text").asText();
        assertThat(consigna).contains("nombre de una clase");
    }

    // ---------------------------------------------------------- DEGRADAR

    @Test
    @DisplayName("Una respuesta que no es una lectura no entra al modelo")
    void filtra_la_basura() {
        responder = c -> "I'm sorry, I cannot read this image.";

        DeteccionPropuesta leida = enriquecedor().enriquecer(detectar(), imagen());

        // Mejor sin nombre que con una disculpa de nombre.
        assertThat(leida.clases().get(0).nombre()).isNull();
    }

    @Test
    @DisplayName("Si el modelo devuelve error, la propuesta sale igual sin texto")
    void error_del_modelo() {
        estado = 500;

        DeteccionPropuesta base = detectar();
        DeteccionPropuesta leida = enriquecedor().enriquecer(base, imagen());

        assertThat(leida.clases()).hasSameSizeAs(base.clases());
        assertThat(leida.clases().get(0).nombre()).isNull();
    }

    @Test
    @DisplayName("Al agotarse el presupuesto devuelve lo leido y lo avisa")
    void presupuesto() {
        demoraMs = 120;
        // Alcanza para una o dos preguntas, no para todas.
        EnriquecedorPorModelo apurado = new EnriquecedorPorModelo(url(), "m", 250, 5_000);

        DeteccionPropuesta leida = apurado.enriquecer(detectar(), imagen());

        // Media propuesta es util; una peticion que no vuelve, no.
        assertThat(leida.avisos()).anySatisfy(a -> assertThat(a).contains("no llego a leer"));
        assertThat(leida.clases()).isNotEmpty();
    }

    @Test
    @DisplayName("Un triangulo leido convierte la relacion en generalizacion")
    void clasifica_el_extremo() {
        responder = cuerpo -> cuerpo.contains("ROMBO_RELLENO") ? "TRIANGULO" : "Libro";

        DeteccionPropuesta leida = enriquecedor().enriquecer(detectar(), imagen());

        assertThat(leida.relaciones()).isNotEmpty();
        assertThat(leida.relaciones().get(0).kind())
                .isEqualTo(com.uagrm.colabmodel.modelcore.metamodel.RelationKind.GENERALIZATION);
    }

    // ------------------------------------------------------------- UTILES

    /** El PNG que viajo en la peticion, decodificado. */
    private static BufferedImage imagenDe(String peticion) throws IOException {
        JsonNode url = JSON.readTree(peticion)
                .path("messages").path(0).path("content").path(1).path("image_url").path("url");
        String base64 = url.asText().substring("data:image/png;base64,".length());
        return ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(base64)));
    }

    private static String respuestaChat(String contenido) {
        return "{\"choices\":[{\"message\":{\"content\":"
                + JSON.getNodeFactory().textNode(contenido) + "}}]}";
    }

    private DeteccionPropuesta detectar() {
        return new DetectorGeometrico().detectar(imagen());
    }

    /** Dos cajas con compartimento y un conector: lo que deja una captura. */
    private static ImagenDeEntrada imagen() {
        BufferedImage img = new BufferedImage(800, 400, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 800, 400);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        g.setColor(Color.BLACK);
        g.setStroke(new BasicStroke(2));
        g.drawRect(60, 60, 200, 120);
        g.drawLine(60, 95, 260, 95);
        for (int i = 0; i < 120; i += 12) g.fillRect(80 + i, 70, 8, 4);
        for (int i = 0; i < 100; i += 12) g.fillRect(80 + i, 110, 8, 4);
        g.drawRect(420, 60, 200, 120);
        g.drawLine(420, 95, 620, 95);
        g.drawLine(260, 120, 420, 120);
        g.dispose();
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
