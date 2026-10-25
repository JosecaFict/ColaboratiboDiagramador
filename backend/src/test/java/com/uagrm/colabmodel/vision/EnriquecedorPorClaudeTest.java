package com.uagrm.colabmodel.vision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import com.uagrm.colabmodel.ai.ClienteClaude;
import com.uagrm.colabmodel.modelcore.metamodel.RelationKind;
import com.uagrm.colabmodel.vision.DeteccionPropuesta.Caja;
import com.uagrm.colabmodel.vision.DeteccionPropuesta.ClaseDetectada;
import com.uagrm.colabmodel.vision.DeteccionPropuesta.RecorteDeTexto;
import com.uagrm.colabmodel.vision.DeteccionPropuesta.RolDeTexto;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El nivel 2b por la API de Claude, contra una API simulada.
 *
 * La API falsa hace lo que haria la real con structured outputs: lee los
 * identificadores que vienen antes de cada imagen y devuelve una entrada por
 * cada uno, en el JSON del esquema. Lo que se contesta para cada recorte lo
 * decide cada test mirando la consigna ("el nombre de una clase", ...).
 */
class EnriquecedorPorClaudeTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern ID = Pattern.compile("^\\[([ts]\\d+)]$");

    /** Chica a proposito: asi un diagrama de prueba ya se parte en varias tandas. */
    private static final int TANDA = 20;

    private HttpServer servidor;
    private final List<JsonNode> peticiones = new CopyOnWriteArrayList<>();
    private Function<String, String> leerTexto = consigna -> "Libro";
    private String figura = "NINGUNA";
    private int estado = 200;

    @BeforeEach
    void levantarApiFalsa() throws IOException {
        servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servidor.createContext("/v1/messages", intercambio -> {
            JsonNode pedido = JSON.readTree(intercambio.getRequestBody().readAllBytes());
            peticiones.add(pedido);
            byte[] cuerpo = (estado == 200
                    ? respuesta(pedido)
                    : "{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"caido\"}}")
                    .getBytes(StandardCharsets.UTF_8);
            intercambio.getResponseHeaders().add("Content-Type", "application/json");
            intercambio.sendResponseHeaders(estado, cuerpo.length);
            intercambio.getResponseBody().write(cuerpo);
            intercambio.close();
        });
        servidor.start();
    }

    @AfterEach
    void bajarApiFalsa() {
        servidor.stop(0);
    }

    private EnriquecedorPorClaude enriquecedor() {
        return new EnriquecedorPorClaude(new ClienteClaude("clave-de-prueba", "claude-opus-5", "low", true,
                "http://127.0.0.1:" + servidor.getAddress().getPort(), 10_000), 10_000, TANDA);
    }

    // ---------------------------------------------------------- DISPONIBLE

    @Test
    @DisplayName("Sin API key el nivel 2b no esta disponible y no se intenta nada")
    void sin_clave() {
        EnriquecedorPorClaude sinClave = new EnriquecedorPorClaude(
                new ClienteClaude("", "claude-opus-5", "low", true, "http://127.0.0.1:1", 1000), 1000, TANDA);

        assertThat(sinClave.disponible()).isFalse();
        assertThat(sinClave.nombre()).contains("claude-opus-5");
        assertThat(enriquecedor().disponible()).isTrue();
    }

    // ------------------------------------------------------------ LECTURA

    @Test
    @DisplayName("Todos los recortes viajan en UN pedido y lo leido llena los nombres")
    void un_solo_pedido() throws IOException {
        DeteccionPropuesta base = detectar();
        assertThat(base.clases().get(0).nombre()).as("el nivel 1 no lee texto").isNull();

        DeteccionPropuesta leida = enriquecedor().enriquecer(base, imagen());

        assertThat(peticiones).hasSize(1);
        assertThat(leida.clases().get(0).nombre()).isEqualTo("Libro");
        assertThat(leida.clases().get(0).recorteNombre().confianza()).isBetween(0.5, 0.99);
        // La geometria no se toca: eso ya lo decidio el nivel 1.
        assertThat(leida.clases().get(0).caja()).isEqualTo(base.clases().get(0).caja());

        JsonNode pedido = peticiones.get(0);
        assertThat(pedido.path("output_config").path("format").path("type").asText()).isEqualTo("json_schema");
        assertThat(pedido.path("thinking").path("type").asText())
                .as("transcribir no necesita pensar, y los tokens de salida son los caros").isEqualTo("disabled");
        List<BufferedImage> imagenes = imagenesDe(pedido);
        assertThat(imagenes).hasSize(identificadoresDe(pedido).size());
        // La consigna va una vez por grupo, no una vez por recorte: eran la mitad de los tokens.
        long consignas = identificadoresDe(pedido).stream().distinct().count();
        assertThat(pedido.path("messages").path(0).path("content")).filteredOn(
                        b -> b.path("text").asText().startsWith("Cada uno de estos recortes"))
                .hasSize((int) consignas);
        for (BufferedImage recorte : imagenes) {
            assertThat(recorte.getWidth()).as("nunca la imagen entera, solo el recorte").isLessThan(800);
        }
    }

    @Test
    @DisplayName("Cada recorte dice que se espera encontrar en el")
    void la_consigna_dice_el_rol() {
        enriquecedor().enriquecer(detectar(), imagen());

        assertThat(identificadoresDe(peticiones.get(0)))
                .anyMatch(c -> c.contains("el nombre de una clase"))
                .anyMatch(c -> c.contains("un atributo"));
    }

    @Test
    @DisplayName("Una disculpa no se convierte en el nombre de una clase, aunque venga en el JSON")
    void filtra_la_basura() {
        leerTexto = consigna -> "Lo siento, no puedo leer esta imagen con claridad";

        DeteccionPropuesta leida = enriquecedor().enriquecer(detectar(), imagen());

        assertThat(leida.clases()).extracting(ClaseDetectada::nombre).containsOnlyNulls();
    }

    @Test
    @DisplayName("Una cadena vacia es 'ilegible': el recorte queda como vino")
    void ilegible() {
        leerTexto = consigna -> "";

        DeteccionPropuesta leida = enriquecedor().enriquecer(detectar(), imagen());

        assertThat(leida.clases().get(0).recorteNombre().texto()).isNull();
        assertThat(leida.clases().get(0).recorteNombre().confianza()).isZero();
    }

    @Test
    @DisplayName("Un triangulo leido convierte la relacion en generalizacion")
    void clasifica_el_extremo() {
        figura = "TRIANGULO";

        DeteccionPropuesta leida = enriquecedor().enriquecer(detectar(), imagen());

        assertThat(leida.relaciones()).isNotEmpty();
        assertThat(leida.relaciones().get(0).kind()).isEqualTo(RelationKind.GENERALIZATION);
    }

    @Test
    @DisplayName("Si la API falla, la propuesta sale como vino, con un aviso")
    void error_de_la_api() {
        estado = 500;
        DeteccionPropuesta base = detectar();

        DeteccionPropuesta leida = enriquecedor().enriquecer(base, imagen());

        assertThat(leida.clases()).isEqualTo(base.clases());
        assertThat(leida.relaciones()).isEqualTo(base.relaciones());
        assertThat(leida.avisos()).anyMatch(a -> a.contains("Claude"));
    }

    @Test
    @DisplayName("Un diagrama con mas recortes de los que entran en un pedido se parte en tandas")
    void tandas() {
        DeteccionPropuesta base = detectar();
        List<RecorteDeTexto> atributos = new ArrayList<>();
        for (int i = 0; i < 2 * TANDA; i++) {
            atributos.add(RecorteDeTexto.vacio("c1-a" + i, RolDeTexto.ATRIBUTO, new Caja(80, 100, 150, 20)));
        }
        ClaseDetectada c = base.clases().get(0);
        DeteccionPropuesta grande = new DeteccionPropuesta(
                List.of(new ClaseDetectada(c.ref(), c.caja(), c.lienzo(), null, c.recorteNombre(),
                        atributos, List.of(), c.compartimentos(), c.confianza())),
                List.of(), List.of(), base.metricas());
        leerTexto = consigna -> consigna.contains("atributo") ? "titulo: String" : "Libro";

        DeteccionPropuesta leida = enriquecedor().enriquecer(grande, imagen());

        assertThat(peticiones).hasSize(3);
        assertThat(leida.clases().get(0).atributos()).extracting(RecorteDeTexto::texto)
                .hasSize(2 * TANDA)
                .containsOnly("titulo: String");
        assertThat(leida.clases().get(0).nombre()).isEqualTo("Libro");
    }

    @Test
    @DisplayName("La respuesta compacta id|lectura se corta en el primer '|' e ignora lo mal formado")
    void lecturas_compactas() {
        var leido = EnriquecedorPorClaude.lecturas(
                "{\"lecturas\": [\"t1|Libro\", \"t2|estado: A|B\", \"t3|\", \"s4|TRIANGULO\", \"sin id\", \"|x\"]}");

        assertThat(leido).containsOnlyKeys("t1", "t2", "t3", "s4");
        assertThat(leido.get("t2")).as("la lectura puede tener '|'").isEqualTo("estado: A|B");
        assertThat(leido.get("t3")).isEmpty();
    }

    @Test
    @DisplayName("Un recorte chiquito se agranda antes de mandarlo")
    void agranda_lo_chico() throws IOException {
        String png = EnriquecedorPorClaude.pngLegible(imagen(), new Caja(80, 70, 20, 10));

        BufferedImage mandado = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(png)));
        assertThat(mandado.getHeight()).isGreaterThanOrEqualTo(40);
        assertThat(mandado.getWidth()).isEqualTo(4 * 20);
    }

    // ------------------------------------------------------------- UTILES

    /** Lo que contestaria Claude con el esquema: una entrada por identificador. */
    private String respuesta(JsonNode pedido) {
        ObjectNode lectura = JSON.createObjectNode();
        ArrayNode lecturas = lectura.putArray("lecturas");
        consignasPorId(pedido).forEach((id, consigna) ->
                lecturas.add(id + "|" + (id.startsWith("t") ? leerTexto.apply(consigna) : figura)));
        ObjectNode mensaje = JSON.createObjectNode()
                .put("id", "msg_1").put("type", "message").put("role", "assistant")
                .put("model", "claude-opus-5").put("stop_reason", "end_turn");
        mensaje.putNull("stop_sequence");
        mensaje.putArray("content").addObject().put("type", "text").put("text", lectura.toString());
        mensaje.putObject("usage").put("input_tokens", 900).put("output_tokens", 120);
        return mensaje.toString();
    }

    private static List<String> identificadoresDe(JsonNode pedido) {
        return new ArrayList<>(consignasPorId(pedido).values());
    }

    /**
     * id -> la consigna de su grupo. La consigna va una vez, como texto suelto,
     * y despues cada recorte lleva solo "[id]" antes de su imagen.
     */
    private static Map<String, String> consignasPorId(JsonNode pedido) {
        Map<String, String> porId = new LinkedHashMap<>();
        String consigna = "";
        for (JsonNode bloque : pedido.path("messages").path(0).path("content")) {
            if (!"text".equals(bloque.path("type").asText())) continue;
            String texto = bloque.path("text").asText();
            Matcher m = ID.matcher(texto);
            if (m.matches()) porId.put(m.group(1), consigna);
            else consigna = texto;
        }
        return porId;
    }

    private static List<BufferedImage> imagenesDe(JsonNode pedido) throws IOException {
        List<BufferedImage> imagenes = new ArrayList<>();
        for (JsonNode bloque : pedido.path("messages").path(0).path("content")) {
            if (!"image".equals(bloque.path("type").asText())) continue;
            assertThat(bloque.path("source").path("media_type").asText()).isEqualTo("image/png");
            byte[] png = Base64.getDecoder().decode(bloque.path("source").path("data").asText());
            imagenes.add(ImageIO.read(new ByteArrayInputStream(png)));
        }
        return imagenes;
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
