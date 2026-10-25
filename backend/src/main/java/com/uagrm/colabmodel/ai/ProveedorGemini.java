package com.uagrm.colabmodel.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

/**
 * Gemini (Google AI Studio) por la API `generateContent` con function calling.
 *
 * Tercera implementacion del mismo contrato ProveedorDeIA, y la razon por la
 * que ADR-017 puso una interfaz en el medio: agregar un proveedor es esta
 * clase mas tres lineas de `application.yml`, sin tocar ServicioDeIA, ni la
 * validacion, ni el tope de 8 (ADR-014). Se activa con
 * `colabmodel.ai.proveedor=gemini`.
 *
 * Existe por un motivo concreto de la defensa: el plan GRATUITO de AI Studio
 * da una clave sin tarjeta, asi que se puede probar el item 4.4 de punta a
 * punta sin credenciales pagas y sin depender de que la maquina aguante un
 * modelo local (que es lo que resuelve ProveedorOllama, pero a costa de RAM y
 * de calidad en la llamada a herramienta).
 *
 * Tres diferencias con la API de Anthropic que quedan resueltas aca adentro y
 * no se filtran al resto del modulo:
 *
 *   - la clave va en la cabecera `x-goog-api-key`, NO en `?key=` como muestra
 *     buena parte de la documentacion: una clave en la URL queda escrita en
 *     los logs de acceso, en el historial de cualquier proxy y en los
 *     mensajes de error que se muestran en pantalla;
 *   - los modelos 3.x devuelven ademas partes de razonamiento
 *     (`"thought": true`), que no son la respuesta y no se muestran al
 *     usuario;
 *   - una `functionCall` sin argumentos viene SIN el campo `args`, mientras
 *     que Anthropic siempre manda `input: {}`. Se normaliza a objeto vacio
 *     para que ResolutorDeReferencias reciba siempre la misma forma.
 */
@Component
@ConditionalOnProperty(prefix = "colabmodel.ai", name = "proveedor", havingValue = "gemini")
public class ProveedorGemini implements ProveedorDeIA {

    private static final int MAX_TOKENS = 4096;

    private final String apiKey;
    private final String model;
    private final String baseUrl;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newHttpClient();

    public ProveedorGemini(
            @Value("${colabmodel.ai.gemini.api-key:}") String apiKey,
            @Value("${colabmodel.ai.gemini.model:gemini-3.8-flash}") String model,
            @Value("${colabmodel.ai.gemini.base-url:https://generativelanguage.googleapis.com/v1beta}") String baseUrl,
            ObjectMapper mapper) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model;
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
        this.mapper = mapper;
    }

    @Override
    public String nombre() {
        return "gemini";
    }

    @Override
    public RespuestaIA generar(SolicitudIA solicitud) {
        if (apiKey.isBlank()) {
            throw new ProveedorIAException("Falta configurar colabmodel.ai.gemini.api-key "
                    + "(variable de entorno GEMINI_API_KEY)");
        }

        ObjectNode body = mapper.createObjectNode();
        body.putObject("systemInstruction").putArray("parts").addObject().put("text", solicitud.instruccionDeSistema());

        ObjectNode turno = body.putArray("contents").addObject();
        turno.put("role", "user");
        turno.putArray("parts").addObject().put("text", solicitud.mensajeParaElModelo());

        // El MISMO JSON Schema que ya arma EsquemaDeComandos para Anthropic:
        // Gemini acepta el subconjunto de OpenAPI que ese catalogo usa (type,
        // description, properties, required, enum, items) sin traducirlo.
        if (!solicitud.herramientas().isEmpty()) {
            ArrayNode declaraciones = body.putArray("tools").addObject().putArray("functionDeclarations");
            for (HerramientaIA h : solicitud.herramientas()) {
                ObjectNode f = declaraciones.addObject();
                f.put("name", h.nombre());
                f.put("description", h.descripcion());
                f.set("parameters", h.parametros());
            }
            body.putObject("toolConfig").putObject("functionCallingConfig").put("mode", "AUTO");
        }
        body.putObject("generationConfig").put("maxOutputTokens", MAX_TOKENS);

        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/models/" + model + ":generateContent"))
                .header("x-goog-api-key", apiKey)
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        try {
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                // El 429 del plan gratuito es el error esperable en la demo, y
                // el cuerpo de Google dice cual cuota se agoto: se pasa tal cual.
                throw new ProveedorIAException("Gemini respondio " + resp.statusCode() + ": " + resp.body());
            }
            return parsear(resp.body());
        } catch (IOException e) {
            throw new ProveedorIAException("No se pudo contactar a Gemini: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProveedorIAException("Interrumpido esperando a Gemini");
        }
    }

    private RespuestaIA parsear(String cuerpo) {
        JsonNode raiz;
        try {
            raiz = mapper.readTree(cuerpo);
        } catch (IOException e) {
            throw new ProveedorIAException("Respuesta de Gemini no es JSON valido: " + e.getMessage());
        }

        // Un 200 con el pedido bloqueado por los filtros de contenido: sin
        // esto, el usuario veria "la IA no propuso nada" sin saber por que.
        String bloqueo = raiz.path("promptFeedback").path("blockReason").asText("");
        if (!bloqueo.isEmpty()) {
            throw new ProveedorIAException("Gemini bloqueo el pedido (" + bloqueo + ")");
        }

        JsonNode candidato = raiz.path("candidates").path(0);
        List<ComandoPropuestoIA> comandos = new ArrayList<>();
        StringBuilder nota = new StringBuilder();
        for (JsonNode parte : candidato.path("content").path("parts")) {
            if (parte.path("thought").asBoolean(false)) {
                continue;   // razonamiento interno del modelo, no es la respuesta
            }
            JsonNode llamada = parte.get("functionCall");
            if (llamada != null && !llamada.isNull()) {
                JsonNode args = llamada.get("args");
                comandos.add(new ComandoPropuestoIA(llamada.path("name").asText(),
                        args == null || args.isNull() ? mapper.createObjectNode() : args));
            } else if (parte.hasNonNull("text")) {
                nota.append(parte.path("text").asText());
            }
        }

        // Degradar, no fallar (ADR-017): si el modelo se quedo sin tokens en
        // la mitad de la lista, los cambios que si mando valen; lo que no
        // puede pasar es que el usuario no se entere de que falta el resto.
        String fin = candidato.path("finishReason").asText("");
        if (!fin.isEmpty() && !"STOP".equals(fin)) {
            if (comandos.isEmpty() && nota.isEmpty()) {
                throw new ProveedorIAException("Gemini no devolvio ningun cambio (finishReason " + fin + ")");
            }
            if (nota.length() > 0) {
                nota.append("\n\n");
            }
            nota.append("La respuesta del modelo se corto (").append(fin)
                .append("): puede faltar parte de lo pedido.");
        }
        return new RespuestaIA(comandos, nota.toString());
    }
}
