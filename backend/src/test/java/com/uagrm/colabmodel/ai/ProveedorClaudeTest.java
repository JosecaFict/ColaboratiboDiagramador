package com.uagrm.colabmodel.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ProveedorClaude contra una API de Claude simulada.
 *
 * Igual que EnriquecedorPorModeloTest: un HTTP de verdad (el del JDK) en vez
 * de simular el SDK, para que lo que se pruebe sea el JSON que viaja. No hace
 * falta API key ni gasta credito, y el dia de la demo lo unico que cambia es
 * que las respuestas dejan de ser de mentira.
 */
class ProveedorClaudeTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private HttpServer servidor;
    private final List<Peticion> peticiones = new CopyOnWriteArrayList<>();
    private Function<Peticion, Respuesta> responder = ProveedorClaudeTest::normal;

    private record Peticion(JsonNode cuerpo, String betas, String clave) {
        boolean conExtras() {
            return cuerpo.has("speed") || cuerpo.has("fallbacks");
        }

        int mensajes() {
            return cuerpo.path("messages").size();
        }

        /** El primer pedido de un generar(); los demas son vueltas de la misma conversacion. */
        boolean inicio() {
            return mensajes() == 1;
        }
    }

    /** Como la API real: llamadas en la primera vuelta, y al recibir "registrado", termina. */
    private static Respuesta normal(Peticion p) {
        return Respuesta.ok(p.inicio() ? llamadas() : mensaje("end_turn", "[]", ""));
    }

    private List<Peticion> inicios() {
        return peticiones.stream().filter(Peticion::inicio).toList();
    }

    private record Respuesta(int estado, String cuerpo) {
        static Respuesta ok(String cuerpo) {
            return new Respuesta(200, cuerpo);
        }
    }

    @BeforeEach
    void levantarApiFalsa() throws IOException {
        servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servidor.createContext("/v1/messages", intercambio -> {
            String entrada = new String(intercambio.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Peticion p = new Peticion(JSON.readTree(entrada),
                    // El SDK manda un header por beta; HTTP los trata como una lista con comas.
                    String.join(",", intercambio.getRequestHeaders()
                            .getOrDefault("anthropic-beta", List.of())),
                    intercambio.getRequestHeaders().getFirst("x-api-key"));
            peticiones.add(p);
            Respuesta r = responder.apply(p);
            byte[] cuerpo = r.cuerpo().getBytes(StandardCharsets.UTF_8);
            intercambio.getResponseHeaders().add("Content-Type", "application/json");
            intercambio.sendResponseHeaders(r.estado(), cuerpo.length);
            intercambio.getResponseBody().write(cuerpo);
            intercambio.close();
        });
        servidor.start();
    }

    @AfterEach
    void bajarApiFalsa() {
        servidor.stop(0);
    }

    private ClienteClaude cliente(String clave, boolean modoRapido) {
        return new ClienteClaude(clave, "claude-opus-5", "low", modoRapido,
                "http://127.0.0.1:" + servidor.getAddress().getPort(), 10_000);
    }

    private ProveedorClaude proveedor() {
        return new ProveedorClaude(cliente("clave-de-prueba", true), 10_000);
    }

    private static SolicitudIA solicitud() {
        return new SolicitudIA("Crea la clase Libro con un titulo",
                List.of(new SolicitudIA.ClaseEnContexto("Autor", List.of("nombre"))),
                EsquemaDeComandos.herramientas());
    }

    // ------------------------------------------------------------ PEDIDO

    @Test
    @DisplayName("El pedido lleva modelo, esfuerzo, cache, herramientas cerradas y los extras")
    void arma_el_pedido() {
        proveedor().generar(solicitud());

        assertThat(inicios()).hasSize(1);
        Peticion p = peticiones.get(0);
        JsonNode c = p.cuerpo();
        assertThat(p.clave()).isEqualTo("clave-de-prueba");
        assertThat(c.path("model").asText()).isEqualTo("claude-opus-5");
        assertThat(c.path("thinking").path("type").asText()).isEqualTo("adaptive");
        assertThat(c.path("output_config").path("effort").asText()).isEqualTo("low");
        // La marca de cache en el sistema cubre tambien las herramientas, que van antes.
        assertThat(c.path("system").path(0).path("cache_control").path("type").asText()).isEqualTo("ephemeral");
        assertThat(c.path("messages").path(0).path("content").asText())
                .contains("Crea la clase Libro").contains("Autor (nombre)");

        assertThat(c.path("tools")).hasSize(EsquemaDeComandos.herramientas().size());
        for (JsonNode tool : c.path("tools")) {
            assertThat(tool.path("strict").asBoolean()).as(tool.path("name").asText()).isTrue();
            assertThat(tool.path("input_schema").path("additionalProperties").asBoolean(true)).isFalse();
        }
        JsonNode metodo = herramienta(c, "ADD_METHOD");
        assertThat(metodo.path("input_schema").path("properties").path("params").path("items")
                .path("additionalProperties").asBoolean(true))
                .as("el modo estricto exige cerrar tambien los objetos anidados").isFalse();

        assertThat(c.path("speed").asText()).isEqualTo("fast");
        assertThat(c.path("fallbacks").asText()).isEqualTo("default");
        assertThat(p.betas()).contains("fast-mode-2026-02-01").contains("server-side-fallback-2026-07-01");
    }

    @Test
    @DisplayName("Con el modo rapido apagado por configuracion, no se pide")
    void sin_modo_rapido() {
        new ProveedorClaude(cliente("clave-de-prueba", false), 10_000).generar(solicitud());

        JsonNode c = peticiones.get(0).cuerpo();
        assertThat(c.has("speed")).isFalse();
        assertThat(c.path("fallbacks").asText()).isEqualTo("default");
        assertThat(peticiones.get(0).betas()).doesNotContain("fast-mode");
    }

    // ---------------------------------------------------------- RESPUESTA

    @Test
    @DisplayName("Las llamadas a herramienta vuelven en orden y el texto como nota")
    void lee_las_llamadas() {
        RespuestaIA r = proveedor().generar(solicitud());

        assertThat(r.comandos()).extracting(ComandoPropuestoIA::tipo)
                .containsExactly("CREATE_CLASS", "ADD_ATTRIBUTE");
        assertThat(r.comandos().get(0).parametros().path("name").asText()).isEqualTo("Libro");
        assertThat(r.comandos().get(1).parametros().path("clase").asText()).isEqualTo("Libro");
        assertThat(r.comandos().get(1).parametros().path("dataType").asText()).isEqualTo("string");
        assertThat(r.notaDelModelo()).isEqualTo("Creo la clase y su atributo.");
    }

    @Test
    @DisplayName("Si un clasificador rechaza el pedido, se avisa en vez de devolver una propuesta vacia")
    void rechazo() {
        responder = p -> Respuesta.ok(mensaje("refusal", "[]",
                ",\"stop_details\":{\"type\":\"refusal\",\"category\":\"cyber\",\"explanation\":null}"));

        assertThatThrownBy(() -> proveedor().generar(solicitud()))
                .isInstanceOf(ProveedorIAException.class)
                .hasMessageContaining("no acepto")
                .hasMessageContaining("cyber");
    }

    @Test
    @DisplayName("Sin API key no se intenta nada y el mensaje dice que variable falta")
    void sin_clave() {
        ProveedorClaude sinClave = new ProveedorClaude(cliente("", true), 10_000);

        assertThatThrownBy(() -> sinClave.generar(solicitud()))
                .isInstanceOf(ProveedorIAException.class)
                .hasMessageContaining("ANTHROPIC_API_KEY");
        assertThat(peticiones).isEmpty();
    }

    // ------------------------------------------------------------ VUELTAS

    @Test
    @DisplayName("Si crea las clases y espera, se le contesta 'registrado' y sigue con atributos y relaciones")
    void sigue_por_vueltas() {
        // Lo que hizo Opus 5 contra la API real: primero las clases, y a esperar.
        responder = p -> switch (p.mensajes()) {
            case 1 -> Respuesta.ok(mensaje("tool_use", """
                    [{"type": "text", "text": "Creo las dos clases."},
                     {"type": "tool_use", "id": "toolu_1", "name": "CREATE_CLASS", "input": {"name": "Libro"}},
                     {"type": "tool_use", "id": "toolu_2", "name": "CREATE_CLASS", "input": {"name": "Autor"}}]""", ""));
            case 3 -> Respuesta.ok(mensaje("tool_use", """
                    [{"type": "tool_use", "id": "toolu_3", "name": "ADD_ATTRIBUTE",
                      "input": {"clase": "Libro", "name": "titulo", "dataType": "string"}},
                     {"type": "tool_use", "id": "toolu_4", "name": "CREATE_RELATION",
                      "input": {"claseOrigen": "Autor", "claseDestino": "Libro", "kind": "ASSOCIATION"}}]""", ""));
            default -> Respuesta.ok(mensaje("end_turn", "[{\"type\": \"text\", \"text\": \"Listo.\"}]", ""));
        };

        RespuestaIA r = proveedor().generar(solicitud());

        assertThat(r.comandos()).extracting(ComandoPropuestoIA::tipo)
                .containsExactly("CREATE_CLASS", "CREATE_CLASS", "ADD_ATTRIBUTE", "CREATE_RELATION");
        assertThat(r.notaDelModelo()).isEqualTo("Creo las dos clases. Listo.");
        assertThat(peticiones).hasSize(3);

        // La segunda vuelta repite lo que dijo el modelo y contesta cada llamada por su id.
        JsonNode mensajes = peticiones.get(1).cuerpo().path("messages");
        assertThat(mensajes.path(1).path("role").asText()).isEqualTo("assistant");
        assertThat(mensajes.path(2).path("role").asText()).isEqualTo("user");
        assertThat(mensajes.path(2).path("content")).extracting(b -> b.path("type").asText())
                .containsOnly("tool_result");
        assertThat(mensajes.path(2).path("content")).extracting(b -> b.path("tool_use_id").asText())
                .containsExactly("toolu_1", "toolu_2");
    }

    @Test
    @DisplayName("Si nunca termina, se corta a las MAX_TURNOS vueltas con lo que propuso")
    void tope_de_vueltas() {
        responder = p -> Respuesta.ok(mensaje("tool_use",
                "[{\"type\": \"tool_use\", \"id\": \"toolu_" + p.mensajes() + "\", \"name\": \"CREATE_CLASS\","
                        + " \"input\": {\"name\": \"Clase" + p.mensajes() + "\"}}]", ""));

        RespuestaIA r = proveedor().generar(solicitud());

        assertThat(peticiones).hasSize(ProveedorClaude.MAX_TURNOS);
        assertThat(r.comandos()).hasSize(ProveedorClaude.MAX_TURNOS);
    }

    @Test
    @DisplayName("Si una vuelta posterior falla, se devuelve lo ya propuesto con un aviso")
    void falla_en_una_vuelta_posterior() {
        responder = p -> p.inicio()
                ? Respuesta.ok(llamadas())
                : new Respuesta(500, error("api_error", "caido"));

        RespuestaIA r = proveedor().generar(solicitud());

        assertThat(r.comandos()).extracting(ComandoPropuestoIA::tipo)
                .containsExactly("CREATE_CLASS", "ADD_ATTRIBUTE");
        assertThat(r.notaDelModelo()).contains("no llego a terminar");
    }

    // ------------------------------------------------------- DEGRADACION

    @Test
    @DisplayName("Si la cuenta no tiene los extras, se sigue sin ellos y no se vuelven a pedir")
    void extras_no_habilitados() {
        responder = p -> p.conExtras()
                ? new Respuesta(400, error("invalid_request_error", "speed: fast mode is not available"))
                : normal(p);
        ProveedorClaude proveedor = proveedor();

        RespuestaIA primera = proveedor.generar(solicitud());
        RespuestaIA segunda = proveedor.generar(solicitud());

        assertThat(primera.comandos()).hasSize(2);
        assertThat(segunda.comandos()).hasSize(2);
        assertThat(inicios()).extracting(Peticion::conExtras).containsExactly(true, false, false);
        assertThat(peticiones.subList(1, peticiones.size())).noneMatch(Peticion::conExtras);
        JsonNode sinExtras = peticiones.get(1).cuerpo();
        assertThat(sinExtras.path("tools").path(0).has("strict")).isFalse();
        // Lo estable se queda: el pedido sin extras sigue cacheando y con esfuerzo acotado.
        assertThat(sinExtras.path("system").path(0).path("cache_control").path("type").asText())
                .isEqualTo("ephemeral");
        assertThat(sinExtras.path("output_config").path("effort").asText()).isEqualTo("low");
    }

    @Test
    @DisplayName("Si falla igual sin extras, la culpa no era de ellos: se vuelven a pedir")
    void error_que_no_es_de_los_extras() {
        responder = p -> new Respuesta(400, error("invalid_request_error", "messages: vacio"));
        ProveedorClaude proveedor = proveedor();

        assertThatThrownBy(() -> proveedor.generar(solicitud()))
                .isInstanceOf(ProveedorIAException.class)
                .hasMessageContaining("400");

        responder = ProveedorClaudeTest::normal;
        proveedor.generar(solicitud());
        assertThat(peticiones.get(peticiones.size() - 1).conExtras()).isTrue();
    }

    @Test
    @DisplayName("Si el modo rapido esta saturado, se sigue a velocidad normal y se lo pausa sin apagar lo demas")
    void modo_rapido_saturado() {
        responder = p -> p.conExtras()
                ? new Respuesta(429, error("rate_limit_error", "fast mode rate limit"))
                : normal(p);
        ProveedorClaude proveedor = proveedor();

        assertThat(proveedor.generar(solicitud()).comandos()).hasSize(2);
        assertThat(inicios()).extracting(Peticion::conExtras).containsExactly(true, false);

        responder = ProveedorClaudeTest::normal;
        proveedor.generar(solicitud());
        JsonNode siguiente = inicios().get(2).cuerpo();
        assertThat(siguiente.has("speed")).as("el modo rapido queda en pausa: no se paga otro 429").isFalse();
        assertThat(siguiente.path("fallbacks").asText()).as("un 429 no apaga los demas extras").isEqualTo("default");
        assertThat(inicios().get(2).betas()).doesNotContain("fast-mode");
    }

    @Test
    @DisplayName("Una clave invalida no se reintenta sin extras: fallaria igual")
    void clave_invalida() {
        responder = p -> new Respuesta(401, error("authentication_error", "invalid x-api-key"));

        assertThatThrownBy(() -> proveedor().generar(solicitud()))
                .isInstanceOf(ProveedorIAException.class)
                .hasMessageContaining("401");
        assertThat(peticiones).hasSize(1);
    }

    // ------------------------------------------------------------- UTILES

    private static JsonNode herramienta(JsonNode cuerpo, String nombre) {
        for (JsonNode t : cuerpo.path("tools")) {
            if (nombre.equals(t.path("name").asText())) return t;
        }
        throw new AssertionError("no se mando la herramienta " + nombre);
    }

    /** Lo que contesta Opus 5: el bloque de pensamiento (vacio), texto y dos llamadas. */
    private static String llamadas() {
        return mensaje("tool_use", """
                [
                  {"type": "thinking", "thinking": "", "signature": "sig"},
                  {"type": "text", "text": "Creo la clase y su atributo."},
                  {"type": "tool_use", "id": "toolu_1", "name": "CREATE_CLASS", "input": {"name": "Libro"}},
                  {"type": "tool_use", "id": "toolu_2", "name": "ADD_ATTRIBUTE",
                   "input": {"clase": "Libro", "name": "titulo", "dataType": "string"}}
                ]""", "");
    }

    private static String mensaje(String stopReason, String contenido, String extra) {
        return "{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-opus-5\","
                + "\"content\":" + contenido + ",\"stop_reason\":\"" + stopReason + "\",\"stop_sequence\":null,"
                + "\"usage\":{\"input_tokens\":120,\"output_tokens\":80,"
                + "\"cache_creation_input_tokens\":0,\"cache_read_input_tokens\":2400}" + extra + "}";
    }

    private static String error(String tipo, String mensaje) {
        return "{\"type\":\"error\",\"error\":{\"type\":\"" + tipo + "\",\"message\":\"" + mensaje + "\"}}";
    }
}
