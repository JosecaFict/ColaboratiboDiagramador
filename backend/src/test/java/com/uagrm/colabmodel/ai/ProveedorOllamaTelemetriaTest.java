package com.uagrm.colabmodel.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class ProveedorOllamaTelemetriaTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void cada_pedido_mantiene_el_modelo_en_la_gpu_el_tiempo_configurado() throws Exception {
        assertThat(cuerpoEnviado("15m").path("keep_alive").asText()).isEqualTo("15m");
    }

    @Test
    void sin_configuracion_explicita_usa_diez_minutos() throws Exception {
        assertThat(cuerpoEnviado(null).path("keep_alive").asText())
                .isEqualTo(ProveedorOllama.KEEP_ALIVE_PREDETERMINADO);
    }

    @Test
    void lee_tokens_duraciones_y_velocidad_de_la_respuesta_de_ollama() throws Exception {
        JsonNode raiz = mapper.readTree("""
                {"prompt_eval_count": 3000, "eval_count": 400,
                 "eval_duration": 8000000000, "load_duration": 2500000000, "total_duration": 12000000000}
                """);
        var t = ProveedorOllama.leerTelemetria(raiz);
        assertThat(t.tokensPrompt()).isEqualTo(3000);
        assertThat(t.tokensSalida()).isEqualTo(400);
        assertThat(t.cargaMs()).isEqualTo(2500);
        assertThat(t.totalMs()).isEqualTo(12000);
        assertThat(t.tokensPorSegundo()).isCloseTo(50.0, within(0.01));
    }

    @Test
    void una_respuesta_sin_metricas_no_rompe_la_lectura() throws Exception {
        var t = ProveedorOllama.leerTelemetria(mapper.readTree("{\"message\":{}}"));
        assertThat(t.tokensPrompt()).isZero();
        assertThat(t.tokensPorSegundo()).isZero();
    }

    @Test
    void avisa_cuando_el_prompt_se_acerca_al_contexto() throws Exception {
        assertThat(ProveedorOllama.leerTelemetria(mapper.readTree("{\"prompt_eval_count\": 7400}"))
                .cercaDelLimite(8192)).isTrue();
        assertThat(ProveedorOllama.leerTelemetria(mapper.readTree("{\"prompt_eval_count\": 5000}"))
                .cercaDelLimite(8192)).isFalse();
    }

    private JsonNode cuerpoEnviado(String keepAlive) throws Exception {
        var recibido = new AtomicReference<JsonNode>();
        var servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servidor.createContext("/api/chat", peticion -> {
            recibido.set(mapper.readTree(peticion.getRequestBody()));
            var respuesta = mapper.createObjectNode();
            respuesta.putObject("message").put("role", "assistant").put("content", "Modelo explicado");
            respuesta.put("prompt_eval_count", 120).put("eval_count", 30).put("eval_duration", 1_000_000_000L);
            byte[] datos = respuesta.toString().getBytes(StandardCharsets.UTF_8);
            peticion.sendResponseHeaders(200, datos.length);
            try (var salida = peticion.getResponseBody()) { salida.write(datos); }
        });
        servidor.start();
        try {
            String url = "http://127.0.0.1:" + servidor.getAddress().getPort();
            var catalogo = new CatalogoModelosOllama("qwen3.5:9b-q4_K_M");
            var proveedor = keepAlive == null
                    ? new ProveedorOllama(url, 8192, 0.1, 5000, mapper, catalogo)
                    : new ProveedorOllama(url, 8192, 0.1, 5000, keepAlive, mapper, catalogo);
            proveedor.generar(new SolicitudIA("Explica el modelo", ModoIA.CONSULTAR,
                    List.of(), List.of(), List.of(), "qwen3.5:9b-q4_K_M"));
            return recibido.get();
        } finally {
            servidor.stop(0);
        }
    }
}
