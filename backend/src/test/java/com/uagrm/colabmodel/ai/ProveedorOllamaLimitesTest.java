package com.uagrm.colabmodel.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.assertThat;

class ProveedorOllamaLimitesTest {
    @Test
    void limita_la_generacion_y_recupera_json_legible_sin_una_segunda_inferencia() throws Exception {
        var mapper = new ObjectMapper();
        var recibido = new AtomicReference<com.fasterxml.jackson.databind.JsonNode>();
        var servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String plan = """
                {"classes":[{"name":"Proyecto","attributes":[{"name":"titulo","dataType":"string"}]}],
                "relations":[{"source":"Proyecto","target":"Proyecto","kind":"ASSOCIATION"}],
                "audit":["supuesto","restriccion"]}
                """;
        servidor.createContext("/api/chat", peticion -> {
            recibido.set(mapper.readTree(peticion.getRequestBody()));
            var respuesta = mapper.createObjectNode();
            respuesta.putObject("message").put("role", "assistant").put("content", plan);
            byte[] datos = respuesta.toString().getBytes(StandardCharsets.UTF_8);
            peticion.sendResponseHeaders(200, datos.length);
            try (var salida = peticion.getResponseBody()) { salida.write(datos); }
        });
        servidor.start();
        try {
            var proveedor = new ProveedorOllama("http://127.0.0.1:" + servidor.getAddress().getPort(),
                    8192, 0.1, 5000, mapper, new CatalogoModelosOllama("qwen3.5:9b-q4_K_M"));
            var respuesta = proveedor.generar(new SolicitudIA("Disena un proyecto", ModoIA.DISENAR,
                    List.of(), List.of(), List.of(EsquemaPlanUml.herramienta()), "qwen3.5:9b-q4_K_M"));
            assertThat(respuesta.comandos()).hasSize(1);
            assertThat(recibido.get().path("options").path("num_predict").asInt()).isEqualTo(4096);
            assertThat(recibido.get().path("options").path("num_ctx").asInt()).isEqualTo(8192);
            assertThat(recibido.get().path("think").asBoolean()).isFalse();
        } finally { servidor.stop(0); }
    }
}
