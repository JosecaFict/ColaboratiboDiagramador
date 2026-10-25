package com.uagrm.colabmodel.vision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import com.uagrm.colabmodel.modelcore.metamodel.RelationKind;
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
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El nivel 2 contra Gemini, con un Google simulado.
 *
 * Misma tactica que EnriquecedorPorModeloTest --un HTTP de verdad, el del JDK,
 * para que lo que se prueba incluya el JSON que viaja--, pero lo que se vigila
 * aca es lo contrario: que TODOS los recortes vayan en UNA sola peticion. Esa
 * es la decision que hace que la lectura de una foto entre en el plan gratuito,
 * y si se rompe nadie se entera hasta que el dia de la demo la mitad de los
 * recortes vuelva con 429.
 *
 * Corre sin clave real y sin internet.
 */
class EnriquecedorPorGeminiTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private HttpServer servidor;
    private final List<String> peticiones = new CopyOnWriteArrayList<>();
    private int estado = 200;

    /** Que contesta el modelo para un recorte, segun la consigna que recibio. */
    private Function<String, String> responder = consigna -> {
        if (consigna.contains("SIMBOLO")) return "TRIANGULO";
        if (consigna.contains("nombre de una clase")) return "Libro";
        return "titulo";
    };

    /** Con que cuerpo contesta Google; por defecto, el JSON que se le pidio. */
    private Function<String, String> respuestaCruda = this::transcripcionDe;

    @BeforeEach
    void levantarGoogleFalso() throws IOException {
        servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servidor.createContext("/", intercambio -> {
            String entrada = new String(intercambio.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            peticiones.add(entrada);
            byte[] cuerpo = respuestaCruda.apply(entrada).getBytes(StandardCharsets.UTF_8);
            intercambio.getResponseHeaders().add("content-type", "application/json");
            intercambio.sendResponseHeaders(estado, cuerpo.length);
            intercambio.getResponseBody().write(cuerpo);
            intercambio.close();
        });
        servidor.start();
    }

    @AfterEach
    void bajarGoogleFalso() {
        servidor.stop(0);
    }

    private EnriquecedorPorGemini enriquecedor() {
        return new EnriquecedorPorGemini("clave-de-prueba", "gemini-de-prueba",
                "http://127.0.0.1:" + servidor.getAddress().getPort() + "/v1beta", 80, 10_000);
    }

    // -------------------------------------------------------- DISPONIBLE

    @Test
    @DisplayName("Sin clave no existe esta capa, y el nivel 1 sigue contestando")
    void sin_clave() {
        EnriquecedorPorGemini sinClave = new EnriquecedorPorGemini("", "m", "http://127.0.0.1:1", 80, 1000);

        assertThat(sinClave.disponible()).isFalse();
        assertThat(sinClave.nombre()).contains("sin clave");
    }

    @Test
    @DisplayName("Con clave esta disponible sin sondear: sondear seria gastar cuota en preguntar si queda cuota")
    void disponible_sin_sondeo() {
        assertThat(enriquecedor().disponible()).isTrue();
        assertThat(peticiones).isEmpty();
    }

    // ------------------------------------------------------------ LECTURA

    @Test
    @DisplayName("TODOS los recortes de la foto viajan en UNA sola peticion")
    void una_sola_peticion() throws IOException {
        DeteccionPropuesta base = detectar();

        enriquecedor().enriquecer(base, imagen());

        assertThat(peticiones).hasSize(1);
        // Y esa unica peticion lleva varios recortes, no uno.
        assertThat(recortesDe(peticiones.get(0))).hasSizeGreaterThan(1);
    }

    @Test
    @DisplayName("NUNCA manda la imagen entera: solo los recortes")
    void solo_recortes() throws IOException {
        ImagenDeEntrada entrada = imagen();

        enriquecedor().enriquecer(detectar(), entrada);

        List<BufferedImage> recortes = recortesDe(peticiones.get(0));
        assertThat(recortes).isNotEmpty();
        for (BufferedImage enviada : recortes) {
            assertThat(enviada.getWidth()).isLessThan(entrada.ancho());
            assertThat(enviada.getHeight()).isLessThan(entrada.alto());
            assertThat((long) enviada.getWidth() * enviada.getHeight())
                    .isLessThan((long) entrada.ancho() * entrada.alto() / 4);
        }
    }

    @Test
    @DisplayName("Lo que lee el modelo llena el nombre de la clase, sin tocar la geometria")
    void llena_los_nombres() {
        DeteccionPropuesta base = detectar();
        assertThat(base.clases().get(0).nombre()).as("el nivel 1 no lee texto").isNull();

        DeteccionPropuesta leida = enriquecedor().enriquecer(base, imagen());

        assertThat(leida.clases().get(0).nombre()).isEqualTo("Libro");
        assertThat(leida.clases().get(0).recorteNombre().confianza()).isGreaterThan(0);
        assertThat(leida.clases().get(0).caja()).isEqualTo(base.clases().get(0).caja());
    }

    @Test
    @DisplayName("Pide determinismo y salida estructurada, y le dice QUE espera en cada recorte")
    void pregunta_acotada() throws IOException {
        enriquecedor().enriquecer(detectar(), imagen());
        JsonNode peticion = JSON.readTree(peticiones.get(0));

        JsonNode config = peticion.path("generationConfig");
        assertThat(config.path("temperature").asInt()).isZero();
        assertThat(config.path("responseMimeType").asText()).isEqualTo("application/json");
        assertThat(config.path("responseSchema").path("type").asText()).isEqualTo("array");

        assertThat(consignasDe(peticiones.get(0)))
                .anySatisfy(c -> assertThat(c).contains("nombre de una clase"))
                .anySatisfy(c -> assertThat(c).contains("SIMBOLO"));
    }

    @Test
    @DisplayName("Un triangulo leido convierte la relacion en generalizacion")
    void clasifica_el_extremo() {
        DeteccionPropuesta leida = enriquecedor().enriquecer(detectar(), imagen());

        assertThat(leida.relaciones()).isNotEmpty();
        assertThat(leida.relaciones().get(0).kind()).isEqualTo(RelationKind.GENERALIZATION);
    }

    @Test
    @DisplayName("No vuelve a preguntar por lo que ya leyo otra capa: las capas se suman")
    void no_repregunta() {
        DeteccionPropuesta leida = enriquecedor().enriquecer(detectar(), imagen());
        peticiones.clear();

        DeteccionPropuesta otraVez = enriquecedor().enriquecer(leida, imagen());

        assertThat(peticiones).as("ya no queda nada sin leer").isEmpty();
        assertThat(otraVez.clases().get(0).nombre()).isEqualTo("Libro");
    }

    // ---------------------------------------------------------- DEGRADAR

    @Test
    @DisplayName("Una respuesta que no es una lectura no entra al modelo")
    void filtra_la_basura() {
        responder = consigna -> "I'm sorry, I cannot read this image.";

        DeteccionPropuesta leida = enriquecedor().enriquecer(detectar(), imagen());

        assertThat(leida.clases().get(0).nombre()).isNull();
    }

    @Test
    @DisplayName("Sin cuota gratuita, la propuesta sale igual y el aviso dice que fue la cuota")
    void cuota_agotada() {
        estado = 429;
        respuestaCruda = peticion -> "{\"error\":{\"message\":\"Quota exceeded\"}}";

        DeteccionPropuesta base = detectar();
        DeteccionPropuesta leida = enriquecedor().enriquecer(base, imagen());

        assertThat(leida.clases()).hasSameSizeAs(base.clases());
        assertThat(leida.clases().get(0).nombre()).isNull();
        assertThat(leida.avisos()).anySatisfy(a -> assertThat(a).contains("cuota"));
    }

    @Test
    @DisplayName("Una respuesta ilegible no tumba la deteccion")
    void respuesta_ilegible() {
        respuestaCruda = peticion ->
                "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"aca tenes las lecturas\"}]}}]}";

        DeteccionPropuesta base = detectar();
        DeteccionPropuesta leida = enriquecedor().enriquecer(base, imagen());

        assertThat(leida.clases()).hasSameSizeAs(base.clases());
        assertThat(leida.avisos()).anySatisfy(a -> assertThat(a).contains("no se pudo interpretar"));
    }

    @Test
    @DisplayName("Un numero que no se pregunto se ignora en vez de romper el orden")
    void numero_inventado() {
        respuestaCruda = peticion -> respuesta("[{\"n\":999,\"texto\":\"Fantasma\"}]");

        DeteccionPropuesta leida = enriquecedor().enriquecer(detectar(), imagen());

        assertThat(leida.clases().get(0).nombre()).isNull();
        assertThat(leida.avisos()).anySatisfy(a -> assertThat(a).contains("de "));
    }

    @Test
    @DisplayName("Si Google no responde, la propuesta sale con las cajas ubicadas y sin nombres")
    void sin_red() {
        EnriquecedorPorGemini aPuertoMuerto =
                new EnriquecedorPorGemini("clave", "m", "http://127.0.0.1:1", 80, 500);

        DeteccionPropuesta base = detectar();
        DeteccionPropuesta leida = aPuertoMuerto.enriquecer(base, imagen());

        assertThat(leida.clases()).hasSameSizeAs(base.clases());
        assertThat(leida.avisos()).anySatisfy(a -> assertThat(a).contains("No se pudo consultar"));
    }

    @Test
    @DisplayName("Con mas recortes que el tope, se leen los primeros y se avisa")
    void tope_de_recortes() {
        EnriquecedorPorGemini conTope = new EnriquecedorPorGemini("clave-de-prueba", "gemini-de-prueba",
                "http://127.0.0.1:" + servidor.getAddress().getPort() + "/v1beta", 2, 10_000);

        DeteccionPropuesta leida = conTope.enriquecer(detectar(), imagen());

        assertThat(leida.avisos()).anySatisfy(a -> assertThat(a).contains("mas de 2 recortes"));
    }

    // ------------------------------------------------------------- UTILES

    /** La respuesta que daria el modelo obediente: un renglon por recorte recibido. */
    private String transcripcionDe(String peticion) {
        List<String> consignas;
        try {
            consignas = consignasDe(peticion);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        ArrayNode lista = JSON.createArrayNode();
        for (int i = 0; i < consignas.size(); i++) {
            ObjectNode renglon = lista.addObject();
            renglon.put("n", i + 1);
            renglon.put("texto", responder.apply(consignas.get(i)));
        }
        return respuesta(lista.toString());
    }

    /** El sobre de Gemini alrededor del JSON que devuelve el modelo. */
    private static String respuesta(String jsonDelModelo) {
        ObjectNode raiz = JSON.createObjectNode();
        ObjectNode candidato = raiz.putArray("candidates").addObject();
        candidato.put("finishReason", "STOP");
        candidato.putObject("content").putArray("parts").addObject().put("text", jsonDelModelo);
        return raiz.toString();
    }

    private static List<String> consignasDe(String peticion) throws IOException {
        List<String> consignas = new ArrayList<>();
        for (JsonNode parte : JSON.readTree(peticion).path("contents").path(0).path("parts")) {
            if (parte.hasNonNull("text")) consignas.add(parte.path("text").asText());
        }
        return consignas;
    }

    private static List<BufferedImage> recortesDe(String peticion) throws IOException {
        List<BufferedImage> recortes = new ArrayList<>();
        for (JsonNode parte : JSON.readTree(peticion).path("contents").path(0).path("parts")) {
            JsonNode dato = parte.get("inlineData");
            if (dato == null) continue;
            assertThat(dato.path("mimeType").asText()).isEqualTo("image/png");
            recortes.add(ImageIO.read(new ByteArrayInputStream(
                    Base64.getDecoder().decode(dato.path("data").asText()))));
        }
        return recortes;
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
