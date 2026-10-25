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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * El proveedor Gemini contra un Google simulado.
 *
 * Igual que EnriquecedorPorModeloTest, se levanta un HTTP de verdad --el del
 * JDK, sin dependencias-- en vez de simular el cliente: lo que se rompe al
 * cambiar de proveedor NO es la logica, es el JSON que viaja. Estas pruebas
 * corren sin clave y sin internet, asi que el dia que haya una clave de AI
 * Studio lo unico nuevo a verificar es que la cuenta responde.
 */
class ProveedorGeminiTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private HttpServer servidor;
    private final List<String> cuerpos = new CopyOnWriteArrayList<>();
    private final List<String> uris = new CopyOnWriteArrayList<>();
    private final List<String> claves = new CopyOnWriteArrayList<>();
    private String respuesta = "{\"candidates\":[{\"content\":{\"parts\":[]},\"finishReason\":\"STOP\"}]}";
    private int estado = 200;

    @BeforeEach
    void levantarGoogleFalso() throws IOException {
        servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servidor.createContext("/", intercambio -> {
            cuerpos.add(new String(intercambio.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            uris.add(intercambio.getRequestURI().toString());
            String clave = intercambio.getRequestHeaders().getFirst("x-goog-api-key");
            claves.add(clave == null ? "" : clave);
            byte[] cuerpo = respuesta.getBytes(StandardCharsets.UTF_8);
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

    private ProveedorGemini proveedor() {
        return new ProveedorGemini("clave-de-prueba", "gemini-de-prueba",
                "http://127.0.0.1:" + servidor.getAddress().getPort() + "/v1beta", JSON);
    }

    private SolicitudIA solicitud() {
        return new SolicitudIA("agrega la clase Libro con su titulo",
                List.of(new SolicitudIA.ClaseEnContexto("Persona", List.of("nombre"))),
                EsquemaDeComandos.herramientas());
    }

    // ------------------------------------------------------------ LA PETICION

    @Test
    @DisplayName("Sin clave configurada no se sale a la red: se explica que falta")
    void sin_clave() {
        ProveedorGemini sinClave = new ProveedorGemini("  ", "gemini-de-prueba",
                "http://127.0.0.1:" + servidor.getAddress().getPort() + "/v1beta", JSON);

        assertThatThrownBy(() -> sinClave.generar(solicitud()))
                .isInstanceOf(ProveedorIAException.class)
                .hasMessageContaining("GEMINI_API_KEY");
        assertThat(cuerpos).isEmpty();
    }

    @Test
    @DisplayName("La clave viaja en la cabecera, NUNCA en la URL")
    void la_clave_no_va_en_la_url() {
        proveedor().generar(solicitud());

        assertThat(claves).containsExactly("clave-de-prueba");
        assertThat(uris).containsExactly("/v1beta/models/gemini-de-prueba:generateContent");
        assertThat(uris.get(0)).doesNotContain("clave-de-prueba");
    }

    @Test
    @DisplayName("El cuerpo lleva el catalogo cerrado como functionDeclarations, sin traducir el esquema")
    void el_cuerpo_lleva_las_herramientas() throws IOException {
        proveedor().generar(solicitud());
        JsonNode enviado = JSON.readTree(cuerpos.get(0));

        JsonNode declaraciones = enviado.path("tools").path(0).path("functionDeclarations");
        assertThat(declaraciones.size()).isEqualTo(EsquemaDeComandos.herramientas().size());
        List<String> nombres = new ArrayList<>();
        declaraciones.forEach(d -> nombres.add(d.path("name").asText()));
        assertThat(nombres).contains("CREATE_CLASS", "ADD_ATTRIBUTE")
                .doesNotContain("MOVE_ELEMENT", "BATCH");

        JsonNode crear = declaraciones.path(0);
        assertThat(crear.path("name").asText()).isEqualTo("CREATE_CLASS");
        assertThat(crear.path("parameters").path("properties").path("name").path("type").asText())
                .isEqualTo("string");

        assertThat(enviado.path("systemInstruction").path("parts").path(0).path("text").asText())
                .contains("UN cambio atomico");
        assertThat(enviado.path("contents").path(0).path("parts").path(0).path("text").asText())
                .contains("agrega la clase Libro")
                .contains("Persona");
        assertThat(enviado.path("toolConfig").path("functionCallingConfig").path("mode").asText())
                .isEqualTo("AUTO");
    }

    // ------------------------------------------------------------ LA RESPUESTA

    @Test
    @DisplayName("Las llamadas a herramienta salen en orden y el texto queda como nota")
    void llamadas_en_orden() {
        respuesta = """
                {"candidates":[{"content":{"parts":[
                  {"text":"Agrego la clase y su titulo."},
                  {"functionCall":{"name":"CREATE_CLASS","args":{"name":"Libro"}}},
                  {"functionCall":{"name":"ADD_ATTRIBUTE","args":{"clase":"Libro","name":"titulo"}}}
                ]},"finishReason":"STOP"}]}""";

        RespuestaIA r = proveedor().generar(solicitud());

        assertThat(r.comandos()).extracting(ComandoPropuestoIA::tipo)
                .containsExactly("CREATE_CLASS", "ADD_ATTRIBUTE");
        assertThat(r.comandos().get(1).parametros().path("clase").asText()).isEqualTo("Libro");
        assertThat(r.notaDelModelo()).isEqualTo("Agrego la clase y su titulo.");
    }

    @Test
    @DisplayName("El razonamiento interno de los modelos 3.x no se muestra al usuario")
    void el_pensamiento_no_es_la_respuesta() {
        respuesta = """
                {"candidates":[{"content":{"parts":[
                  {"text":"El usuario quiere una biblioteca, voy a empezar por...","thought":true},
                  {"functionCall":{"name":"CREATE_CLASS","args":{"name":"Libro"}}}
                ]},"finishReason":"STOP"}]}""";

        RespuestaIA r = proveedor().generar(solicitud());

        assertThat(r.comandos()).hasSize(1);
        assertThat(r.notaDelModelo()).isEmpty();
    }

    @Test
    @DisplayName("Una llamada sin args llega como objeto vacio, no como null")
    void llamada_sin_args() {
        respuesta = """
                {"candidates":[{"content":{"parts":[
                  {"functionCall":{"name":"CREATE_CLASS"}}
                ]},"finishReason":"STOP"}]}""";

        RespuestaIA r = proveedor().generar(solicitud());

        assertThat(r.comandos()).hasSize(1);
        assertThat(r.comandos().get(0).parametros().isObject()).isTrue();
        assertThat(r.comandos().get(0).parametros().path("name").asText("")).isEmpty();
    }

    @Test
    @DisplayName("Una respuesta cortada conserva lo que llego y lo avisa (degradar, no fallar)")
    void respuesta_cortada() {
        respuesta = """
                {"candidates":[{"content":{"parts":[
                  {"functionCall":{"name":"CREATE_CLASS","args":{"name":"Libro"}}}
                ]},"finishReason":"MAX_TOKENS"}]}""";

        RespuestaIA r = proveedor().generar(solicitud());

        assertThat(r.comandos()).hasSize(1);
        assertThat(r.notaDelModelo()).contains("MAX_TOKENS").contains("puede faltar");
    }

    @Test
    @DisplayName("Sin nada que devolver, el motivo del corte es el error")
    void respuesta_vacia() {
        respuesta = "{\"candidates\":[{\"content\":{\"parts\":[]},\"finishReason\":\"SAFETY\"}]}";

        assertThatThrownBy(() -> proveedor().generar(solicitud()))
                .isInstanceOf(ProveedorIAException.class)
                .hasMessageContaining("SAFETY");
    }

    @Test
    @DisplayName("Un pedido bloqueado por los filtros se dice, no se confunde con 'no propuso nada'")
    void pedido_bloqueado() {
        respuesta = "{\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}";

        assertThatThrownBy(() -> proveedor().generar(solicitud()))
                .isInstanceOf(ProveedorIAException.class)
                .hasMessageContaining("bloqueo el pedido");
    }

    @Test
    @DisplayName("El 429 del plan gratuito llega con el motivo de Google, no como 'fallo la IA'")
    void cuota_agotada() {
        estado = 429;
        respuesta = "{\"error\":{\"message\":\"Quota exceeded for quota metric 'Generate requests'\"}}";

        assertThatThrownBy(() -> proveedor().generar(solicitud()))
                .isInstanceOf(ProveedorIAException.class)
                .hasMessageContaining("429")
                .hasMessageContaining("Quota exceeded");
    }
}
