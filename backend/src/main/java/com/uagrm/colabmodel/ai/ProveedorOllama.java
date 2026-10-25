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
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Ollama local (`/api/chat` con `tools`), para el escenario sin conexion o
 * sin credenciales el dia de la defensa.
 *
 * Mismo contrato ProveedorDeIA que ProveedorClaude: ServicioDeIA no distingue
 * cual de los dos esta activo (ADR-017). Se activa con
 * `colabmodel.ai.proveedor=ollama`.
 */
@Component
@ConditionalOnProperty(prefix = "colabmodel.ai", name = "proveedor", havingValue = "ollama")
public class ProveedorOllama implements ProveedorDeIA {

    private static final int MAX_RONDAS_DE_HERRAMIENTAS = 8;
    private static final int MAX_LLAMADAS_ACUMULADAS = 32;

    private final String baseUrl;
    private final CatalogoModelosOllama modelos;
    private final int contexto;
    private final double temperatura;
    private final Duration timeout;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newHttpClient();

    public ProveedorOllama(
            @Value("${colabmodel.ai.ollama.base-url:http://localhost:11434}") String baseUrl,
            @Value("${colabmodel.ai.ollama.contexto:4096}") int contexto,
            @Value("${colabmodel.ai.ollama.temperatura:0.1}") double temperatura,
            @Value("${colabmodel.ai.ollama.timeout-ms:120000}") long timeoutMs,
            ObjectMapper mapper, CatalogoModelosOllama modelos) {
        this.baseUrl = baseUrl;
        this.modelos = modelos;
        this.contexto = contexto;
        this.temperatura = temperatura;
        this.timeout = Duration.ofMillis(timeoutMs);
        this.mapper = mapper;
    }

    @Override
    public String nombre() {
        return "ollama";
    }

    @Override
    public RespuestaIA generar(SolicitudIA solicitud) {
        String model = modelos.resolver(solicitud.modelo());
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("stream", false);
        body.put("think", modelos.usaThinking(model));
        body.putObject("options").put("num_ctx", contexto).put("temperature", temperatura);
        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", solicitud.instruccionDeSistema());
        messages.addObject().put("role", "user").put("content", solicitud.mensajeParaElModelo());
        if (!solicitud.herramientas().isEmpty()) {
            ArrayNode tools = body.putArray("tools");
            for (HerramientaIA h : solicitud.herramientas()) {
                ObjectNode t = tools.addObject();
                t.put("type", "function");
                ObjectNode fn = t.putObject("function");
                fn.put("name", h.nombre());
                fn.put("description", h.descripcion());
                fn.set("parameters", h.parametros());
            }
        }

        try {
            boolean planCompleto = solicitud.herramientas().size() == 1
                    && EsquemaPlanUml.NOMBRE.equals(solicitud.herramientas().get(0).nombre());
            if (planCompleto) {
                return generarPlanCompleto(body, messages);
            }
            if (!solicitud.herramientas().isEmpty() && !modelos.usaThinking(model) && !planCompleto) {
                return generarPorRondas(body, messages);
            }
            RespuestaIA primera = enviar(body);
            if (!solicitud.herramientas().isEmpty() && primera.comandos().isEmpty()) {
                messages.addObject().put("role", "assistant").put("content", primera.notaDelModelo());
                messages.addObject().put("role", "user").put("content",
                        "La respuesta anterior no llamo ninguna herramienta. Reintenta ahora: no expliques el modelo; "
                        + "realiza las llamadas de herramienta necesarias para cumplir el pedido.");
                return enviar(body);
            }
            return primera;
        } catch (HttpTimeoutException e) {
            throw new ProveedorIAException("Ollama tardo mas de " + timeout.toSeconds()
                    + " segundos en responder. El modelo sigue disponible; intenta un pedido mas corto.");
        } catch (IOException e) {
            throw new ProveedorIAException("No se pudo contactar a Ollama en " + baseUrl + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProveedorIAException("Interrumpido esperando a Ollama");
        }
    }

    private RespuestaIA generarPlanCompleto(ObjectNode body, ArrayNode messages)
            throws IOException, InterruptedException {
        Turno turno = enviarTurno(body);
        ComandoPropuestoIA plan = buscarPlan(turno.respuesta());
        String primerError = errorEstructuralDelPlan(plan);
        if (primerError == null) return turno.respuesta();

        // Algunos modelos locales contestan en texto aunque Ollama les ofrezca
        // una unica herramienta. El segundo intento usa el mismo JSON Schema
        // como `format`: Ollama restringe la salida y nosotros la envolvemos
        // como la llamada de herramienta que espera el resto del backend.
        ObjectNode recuperacion = body.deepCopy();
        recuperacion.remove("tools");
        recuperacion.set("format", EsquemaPlanUml.herramienta().parametros().deepCopy());
        ArrayNode mensajesRecuperacion = (ArrayNode) recuperacion.path("messages");
        mensajesRecuperacion.addObject().put("role", "user").put("content",
                "MODO DE RECUPERACION: devuelve exclusivamente el objeto JSON del plan UML completo que cumple el "
                        + "esquema solicitado. Debe contener classes, relations y audit; cada clase debe tener attributes. "
                        + "No escribas Markdown ni explicaciones fuera del JSON.");

        Turno estructurado = enviarTurno(recuperacion);
        RespuestaIA respuestaEstructurada = planDesdeContenidoJson(mapper, estructurado.respuesta().notaDelModelo());
        String segundoError = errorEstructuralDelPlan(buscarPlan(respuestaEstructurada));
        if (segundoError == null) return respuestaEstructurada;
        throw new ProveedorIAException("Ollama no pudo producir un plan UML completo. Primer intento: "
                + primerError + "; recuperacion estructurada: " + segundoError);
    }

    private static ComandoPropuestoIA buscarPlan(RespuestaIA respuesta) {
        return respuesta.comandos().stream().filter(c -> EsquemaPlanUml.NOMBRE.equals(c.tipo()))
                .findFirst().orElse(null);
    }

    static RespuestaIA planDesdeContenidoJson(ObjectMapper mapper, String contenido) {
        if (contenido == null || contenido.isBlank()) return new RespuestaIA(List.of(), "");
        try {
            JsonNode parametros = mapper.readTree(contenido);
            return new RespuestaIA(List.of(new ComandoPropuestoIA(EsquemaPlanUml.NOMBRE, parametros)), "");
        } catch (IOException e) {
            return new RespuestaIA(List.of(), contenido);
        }
    }

    static String errorEstructuralDelPlan(ComandoPropuestoIA plan) {
        if (plan == null) return "no llamo " + EsquemaPlanUml.NOMBRE;
        JsonNode p = plan.parametros();
        if (p == null || !p.isObject()) return "los argumentos no son un objeto";
        if (!p.path("classes").isArray() || p.path("classes").isEmpty()) return "classes esta vacio o ausente";
        for (JsonNode clase : p.path("classes")) {
            if (clase.path("name").asText("").isBlank()) return "una clase no tiene name";
            if (!clase.path("attributes").isArray() || clase.path("attributes").isEmpty()) {
                return "la clase " + clase.path("name").asText("sin nombre") + " no tiene attributes";
            }
        }
        if (!p.path("relations").isArray() || p.path("relations").isEmpty()) {
            return "relations esta vacio o ausente";
        }
        if (!p.path("audit").isArray() || p.path("audit").size() < 2) return "audit debe contener 2 a 5 puntos";
        return null;
    }

    /**
     * Qwen 3.5 suele planificar por etapas: primero crea clases y espera el
     * resultado de esas herramientas antes de proponer atributos y relaciones.
     * Los resultados son virtuales: se acumula una propuesta, nunca se escribe
     * en el diagrama durante este ciclo.
     */
    private RespuestaIA generarPorRondas(ObjectNode body, ArrayNode messages)
            throws IOException, InterruptedException {
        List<ComandoPropuestoIA> acumulados = new ArrayList<>();
        List<String> notas = new ArrayList<>();
        int pausasSinHerramientas = 0;

        for (int ronda = 1; ronda <= MAX_RONDAS_DE_HERRAMIENTAS; ronda++) {
            Turno turno = enviarTurno(body);
            RespuestaIA respuesta = turno.respuesta();
            if (!respuesta.notaDelModelo().isBlank()) notas.add(respuesta.notaDelModelo());

            if (respuesta.comandos().isEmpty()) {
                String notaNormalizada = respuesta.notaDelModelo().trim().toUpperCase(java.util.Locale.ROOT);
                if (notaNormalizada.equals("FINALIZADO") || notaNormalizada.endsWith("FINALIZADO.")) {
                    return new RespuestaIA(List.copyOf(acumulados), String.join("\n\n", notas));
                }
                if (acumulados.isEmpty() && ronda == 1) {
                    messages.add(turno.mensaje().deepCopy());
                    messages.addObject().put("role", "user").put("content",
                            "No llamaste herramientas. Genera ahora los cambios aplicables del pedido original.");
                    continue;
                }
                pausasSinHerramientas++;
                if (!acumulados.isEmpty() && pausasSinHerramientas <= 2
                        && ronda < MAX_RONDAS_DE_HERRAMIENTAS) {
                    messages.add(turno.mensaje().deepCopy());
                    messages.addObject().put("role", "user").put("content",
                            "Comprueba el pedido original contra el plan virtual acumulado. Si faltan atributos o "
                            + "relaciones fundamentales, llama ahora las herramientas necesarias. Si el plan ya esta "
                            + "completo, responde solamente FINALIZADO sin herramientas.");
                    continue;
                }
                return new RespuestaIA(List.copyOf(acumulados), String.join("\n\n", notas));
            }

            pausasSinHerramientas = 0;

            messages.add(turno.mensaje().deepCopy());
            for (ComandoPropuestoIA comando : respuesta.comandos()) {
                if (acumulados.size() >= MAX_LLAMADAS_ACUMULADAS) break;
                acumulados.add(comando);
                messages.addObject()
                        .put("role", "tool")
                        .put("tool_name", comando.tipo())
                        .put("content", "Cambio agregado correctamente al plan virtual. Continua con los cambios "
                                + "que falten del pedido original; todavia no se aplico al diagrama.");
            }
            if (acumulados.size() >= MAX_LLAMADAS_ACUMULADAS) {
                notas.add("El plan alcanzo el limite seguro de " + MAX_LLAMADAS_ACUMULADAS + " cambios.");
                return new RespuestaIA(List.copyOf(acumulados), String.join("\n\n", notas));
            }
        }
        notas.add("La propuesta se detuvo tras " + MAX_RONDAS_DE_HERRAMIENTAS
                + " rondas internas para evitar un ciclo indefinido.");
        return new RespuestaIA(List.copyOf(acumulados), String.join("\n\n", notas));
    }

    private RespuestaIA enviar(ObjectNode body) throws IOException, InterruptedException {
        return enviarTurno(body).respuesta();
    }

    private record Turno(RespuestaIA respuesta, ObjectNode mensaje) { }

    private Turno enviarTurno(ObjectNode body) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/api/chat"))
                .timeout(timeout)
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new ProveedorIAException("Ollama respondio " + resp.statusCode() + ": " + resp.body());
        }
        return parsearTurno(resp.body());
    }

    private RespuestaIA parsear(String cuerpo) {
        return parsearTurno(cuerpo).respuesta();
    }

    private Turno parsearTurno(String cuerpo) {
        JsonNode raiz;
        try {
            raiz = mapper.readTree(cuerpo);
        } catch (IOException e) {
            throw new ProveedorIAException("Respuesta de Ollama no es JSON valido: " + e.getMessage());
        }
        List<ComandoPropuestoIA> comandos = new ArrayList<>();
        JsonNode mensajeCrudo = raiz.path("message");
        if (!mensajeCrudo.isObject()) {
            throw new ProveedorIAException("Ollama no devolvio un objeto message");
        }
        for (JsonNode llamada : mensajeCrudo.path("tool_calls")) {
            JsonNode fn = llamada.path("function");
            JsonNode args = fn.path("arguments");
            if (args.isTextual()) {
                try {
                    args = mapper.readTree(args.asText());
                } catch (IOException e) {
                    throw new ProveedorIAException("Ollama devolvio argumentos que no son JSON valido: "
                            + e.getMessage());
                }
            }
            comandos.add(new ComandoPropuestoIA(fn.path("name").asText(), args));
        }
        String nota = mensajeCrudo.path("content").asText("");
        return new Turno(new RespuestaIA(comandos, nota), (ObjectNode) mensajeCrudo.deepCopy());
    }
}
