package com.uagrm.colabmodel.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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

    private static final Logger log = LoggerFactory.getLogger(ProveedorOllama.class);

    private static final int MAX_RONDAS_DE_HERRAMIENTAS = 8;
    private static final int MAX_LLAMADAS_ACUMULADAS = 48;
    /** Ollama recorta el principio del prompt sin avisar cuando supera num_ctx. */
    static final double UMBRAL_AVISO_CONTEXTO = 0.9;
    static final String KEEP_ALIVE_PREDETERMINADO = "10m";

    private final String baseUrl;
    private final CatalogoModelosOllama modelos;
    private final int contexto;
    private final double temperatura;
    private final Duration timeout;
    private final String keepAlive;
    private final ObjectMapper mapper;
    // Si Ollama no esta levantado, falla en segundos y no tras el timeout de inferencia.
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public ProveedorOllama(String baseUrl, int contexto, double temperatura, long timeoutMs,
                           ObjectMapper mapper, CatalogoModelosOllama modelos) {
        this(baseUrl, contexto, temperatura, timeoutMs, KEEP_ALIVE_PREDETERMINADO, mapper, modelos);
    }

    @Autowired
    public ProveedorOllama(
            @Value("${colabmodel.ai.ollama.base-url:http://localhost:11434}") String baseUrl,
            @Value("${colabmodel.ai.ollama.contexto:4096}") int contexto,
            @Value("${colabmodel.ai.ollama.temperatura:0.1}") double temperatura,
            @Value("${colabmodel.ai.ollama.timeout-ms:120000}") long timeoutMs,
            @Value("${colabmodel.ai.ollama.keep-alive:" + KEEP_ALIVE_PREDETERMINADO + "}") String keepAlive,
            ObjectMapper mapper, CatalogoModelosOllama modelos) {
        this.baseUrl = baseUrl;
        this.modelos = modelos;
        this.contexto = contexto;
        this.temperatura = temperatura;
        this.timeout = Duration.ofMillis(timeoutMs);
        this.keepAlive = keepAlive;
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
        // Cada pedido reinicia el temporizador: mientras se usa la IA el modelo
        // sigue en la GPU, y tras ese tiempo sin uso Ollama libera la VRAM solo.
        body.put("keep_alive", keepAlive);
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
            if (solicitud.herramientas().size() == 1
                    && EsquemaRecomendaciones.NOMBRE.equals(solicitud.herramientas().get(0).nombre())) {
                // Revisar exige razonar sobre el diagrama: si el modelo piensa,
                // el razonamiento consume del mismo presupuesto de salida.
                boolean piensa = modelos.usaThinking(model);
                return generarConFormato(body, solicitud.herramientas().get(0), piensa ? 6144 : 2048, piensa);
            }
            if (solicitud.herramientas().size() == 1
                    && EsquemaVerificacionRegla.NOMBRE.equals(solicitud.herramientas().get(0).nombre())) {
                boolean piensa = modelos.usaThinking(model);
                return generarConFormato(body, solicitud.herramientas().get(0), piensa ? 4096 : 768, piensa);
            }
            boolean planCompleto = solicitud.herramientas().size() == 1
                    && EsquemaPlanUml.NOMBRE.equals(solicitud.herramientas().get(0).nombre());
            if (planCompleto) {
                // Un plan de hasta 48 cambios no necesita una generacion ilimitada.
                // Acota respuestas repetitivas sin aumentar los 180 s por peticion.
                ((ObjectNode) body.path("options")).put("num_predict", 4096);
                return generarPlanCompleto(body, messages);
            }
            boolean reparacionDirigida = !solicitud.herramientas().isEmpty() && solicitud.herramientas().stream()
                    .allMatch(h -> ReparadorDirigidoPlan.OPERACIONES.contains(h.nombre()));
            if (reparacionDirigida) ((ObjectNode) body.path("options")).put("num_predict", 2048);
            if (!solicitud.herramientas().isEmpty() && !modelos.usaThinking(model) && !planCompleto && !reparacionDirigida) {
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
        String primerError = errorDeFormatoDelPlan(plan);
        if (primerError == null) return turno.respuesta();

        // A veces Qwen genera correctamente el objeto solicitado en content,
        // pero omite el envoltorio tool_call. No se descarta ese trabajo: se
        // valida con el mismo esquema antes de pedir una segunda inferencia.
        RespuestaIA planEnContenido = planDesdeContenidoJson(mapper, turno.respuesta().notaDelModelo());
        if (errorDeFormatoDelPlan(buscarPlan(planEnContenido)) == null) return planEnContenido;

        // Algunos modelos locales contestan en texto aunque Ollama les ofrezca
        // una unica herramienta. El segundo intento usa el mismo JSON Schema
        // como `format`: Ollama restringe la salida y nosotros la envolvemos
        // como la llamada de herramienta que espera el resto del backend.
        ObjectNode recuperacion = body.deepCopy();
        recuperacion.remove("tools");
        // En una salida restringida a JSON no necesitamos razonamiento visible;
        // algunos modelos dejan content vacio cuando think permanece activo.
        recuperacion.put("think", false);
        recuperacion.set("format", EsquemaPlanUml.herramienta().parametros().deepCopy());
        ArrayNode mensajesRecuperacion = (ArrayNode) recuperacion.path("messages");
        // La salida JSON no debe competir con una instruccion de sistema que
        // obliga a llamar herramientas que ya no estan disponibles.
        ((ObjectNode) mensajesRecuperacion.get(0)).put("content", instruccionPlanJson());
        ComandoPropuestoIA candidato = plan != null ? plan : buscarPlan(planEnContenido);
        if (candidato != null) mensajesRecuperacion.addObject().put("role", "assistant")
                .put("content", candidato.parametros().toString());
        mensajesRecuperacion.addObject().put("role", "user").put("content",
                "MODO DE RECUPERACION: devuelve exclusivamente el objeto JSON del plan UML completo que cumple el "
                        + "esquema solicitado. Debe contener classes, relations y audit; cada clase debe tener attributes. "
                        + "No escribas Markdown ni explicaciones fuera del JSON. Corrige el error detectado: "
                        + primerError + ". Si excede el presupuesto, reduce clases redundantes y modela roles "
                        + "como asociaciones, conservando todos los requisitos esenciales y al menos un dato propio por clase.");

        Turno estructurado = enviarTurno(recuperacion);
        RespuestaIA respuestaEstructurada = planDesdeContenidoJson(mapper, estructurado.respuesta().notaDelModelo());
        String segundoError = errorDeFormatoDelPlan(buscarPlan(respuestaEstructurada));
        if (segundoError == null) return respuestaEstructurada;
        throw new ProveedorIAException("Ollama no pudo producir un plan UML completo. Primer intento: "
                + primerError + "; recuperacion estructurada: " + segundoError);
    }

    /**
     * Salida restringida por gramatica desde el primer intento: Ollama solo
     * puede emitir JSON que cumple el esquema, asi que no hay herramienta que
     * el modelo olvide llamar. Se envuelve como la llamada que espera el servicio.
     */
    private RespuestaIA generarConFormato(ObjectNode body, HerramientaIA herramienta, int maximoTokens,
                                          boolean piensa) throws IOException, InterruptedException {
        body.remove("tools");
        body.put("think", piensa);
        body.set("format", herramienta.parametros().deepCopy());
        ((ObjectNode) body.path("options")).put("num_predict", maximoTokens);
        String contenido = enviarTurno(body).respuesta().notaDelModelo();
        try {
            JsonNode salida = mapper.readTree(extraerObjetoJson(contenido));
            if (!salida.isObject()) throw new IOException("no es un objeto");
            return new RespuestaIA(List.of(new ComandoPropuestoIA(herramienta.nombre(), salida)), "");
        } catch (IOException e) {
            throw new ProveedorIAException("Ollama devolvio una salida que no cumple el formato de "
                    + herramienta.nombre() + " (posiblemente cortada por el limite de " + maximoTokens + " tokens)");
        }
    }

    private static ComandoPropuestoIA buscarPlan(RespuestaIA respuesta) {
        return respuesta.comandos().stream().filter(c -> EsquemaPlanUml.NOMBRE.equals(c.tipo()))
                .findFirst().orElse(null);
    }

    static String instruccionPlanJson() {
        return "Sos el arquitecto UML de ColabModel. Devuelve exclusivamente un objeto JSON que cumpla el esquema format. "
                + "No hay herramientas disponibles en este modo; no emitas llamadas ni Markdown. Usa el requisito y contexto "
                + "del usuario como fuente de verdad. Incluye classes, relations y audit con el modelo completo, nunca por etapas. "
                + "Presupuesto: " + com.uagrm.colabmodel.modelcore.command.Lote.TOPE_TEXTO_O_VOZ
                + " cambios contando cada clase, atributo y relacion. Prioriza las relaciones y los datos esenciales; "
                + "las clases asociativas pueden tener solo identidad cuando sus roles y relaciones esten completos. "
                + "Usa nombres espanoles ASCII. La multiplicidad junto a una clase cuenta instancias de ESA clase. "
                + "En GENERALIZATION source es subclase y target superclase. No dupliques asociaciones con claves foraneas. "
                + "Los eventos historicos requieren fechas y sus participantes. Usa roles para origen/destino y parentesco. "
                + "La auditoria debe describir unicamente elementos realmente incluidos.";
    }

    static RespuestaIA planDesdeContenidoJson(ObjectMapper mapper, String contenido) {
        if (contenido == null || contenido.isBlank()) return new RespuestaIA(List.of(), "");
        try {
            JsonNode parametros = mapper.readTree(extraerObjetoJson(contenido));
            return new RespuestaIA(List.of(new ComandoPropuestoIA(EsquemaPlanUml.NOMBRE, parametros)), "");
        } catch (IOException e) {
            return new RespuestaIA(List.of(), contenido);
        }
    }

    private static String extraerObjetoJson(String contenido) {
        String limpio = contenido.trim();
        if (limpio.startsWith("```")) {
            int finPrimeraLinea = limpio.indexOf('\n');
            if (finPrimeraLinea >= 0) limpio = limpio.substring(finPrimeraLinea + 1);
            if (limpio.endsWith("```")) limpio = limpio.substring(0, limpio.length() - 3).trim();
        }
        int inicio = limpio.indexOf('{');
        int fin = limpio.lastIndexOf('}');
        return inicio >= 0 && fin > inicio ? limpio.substring(inicio, fin + 1) : limpio;
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
            boolean datoPropio = false;
            for (JsonNode atributo : clase.path("attributes")) {
                String nombre = atributo.path("name").asText("");
                if (!atributo.path("pk").asBoolean(false)
                        && !nombre.equalsIgnoreCase("id") && !nombre.matches("^id[_A-Z].*")) datoPropio = true;
            }
            if (!datoPropio && !ValidadorPlanFinal.asociativaSinDatosValida(p, clase.path("name").asText())
                    && !NormalizadorParentescoPlan.puedeSimplificar(p, clase)) {
                String nombre = clase.path("name").asText();
                if (nombre.matches("(?i)^(padre|madre)(animal|persona)?$")) return "la clase " + nombre
                        + " solo contiene identidad y parece un rol familiar; representa parentesco mediante asociaciones "
                        + "recursivas de la entidad Animal o Persona con roles padre/madre y descendientes, sin inventar atributos";
                return "la clase " + nombre + " solo contiene identidad; agrega al menos un dato esencial";
            }
        }
        if (!p.path("relations").isArray() || p.path("relations").isEmpty()) {
            return "relations esta vacio o ausente";
        }
        // Presupuesto minimo: crear cada clase, identidad y un dato propio,
        // ademas de conservar todas las asociaciones. Permite recortar datos
        // secundarios, pero solicita un plan compacto si ni lo esencial cabe.
        ObjectNode presupuesto = ((ObjectNode) p).deepCopy();
        NormalizadorParentescoPlan.simplificar(presupuesto);
        int minimo = presupuesto.path("relations").size();
        for (JsonNode clase : presupuesto.path("classes")) minimo += ValidadorPlanFinal.asociativaSinDatosValida(
                presupuesto, clase.path("name").asText()) ? 2 : 3;
        if (minimo > com.uagrm.colabmodel.modelcore.command.Lote.TOPE_TEXTO_O_VOZ) {
            return "el modelo necesita al menos " + minimo + " cambios esenciales; simplifica clases redundantes "
                    + "y relaciones duplicadas para caber en "
                    + com.uagrm.colabmodel.modelcore.command.Lote.TOPE_TEXTO_O_VOZ;
        }
        if (!p.path("audit").isArray() || p.path("audit").size() < 2) return "audit debe contener 2 a 5 puntos";
        return null;
    }

    /** Solo formato: la cobertura y el presupuesto se reparan en ServicioDeIA. */
    static String errorDeFormatoDelPlan(ComandoPropuestoIA plan) {
        if (plan == null) return "no llamo " + EsquemaPlanUml.NOMBRE;
        JsonNode p = plan.parametros();
        if (p == null || !p.isObject()) return "los argumentos no son un objeto";
        if (!p.path("classes").isArray() || p.path("classes").isEmpty()) return "classes esta vacio o ausente";
        for (JsonNode clase : p.path("classes")) {
            if (!clase.isObject() || clase.path("name").asText().isBlank()) return "una clase no tiene name";
            if (!clase.path("attributes").isArray() || clase.path("attributes").isEmpty()) return "una clase no tiene attributes";
            for (JsonNode a : clase.path("attributes")) if (!a.isObject() || a.path("name").asText().isBlank())
                return "un atributo no tiene name";
        }
        if (!p.path("relations").isArray() || p.path("relations").isEmpty()) return "relations esta vacio o ausente";
        for (JsonNode r : p.path("relations")) {
            if (!r.isObject() || r.path("source").asText().isBlank() || r.path("target").asText().isBlank())
                return "una relacion no identifica sus extremos";
            if (!java.util.Set.of("ASSOCIATION", "AGGREGATION", "COMPOSITION", "GENERALIZATION", "DEPENDENCY")
                    .contains(r.path("kind").asText())) return "una relacion no define un tipo valido";
        }
        if (!p.path("audit").isArray()) return "audit esta ausente";
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
        registrarTelemetria(body, resp.body());
        return parsearTurno(resp.body());
    }

    /** Lo que Ollama ya informa en cada respuesta; las duraciones vienen en nanosegundos. */
    record Telemetria(int tokensPrompt, int tokensSalida, long cargaMs, long totalMs, double tokensPorSegundo) {
        boolean cercaDelLimite(int contexto) {
            return tokensPrompt >= contexto * UMBRAL_AVISO_CONTEXTO;
        }
    }

    static Telemetria leerTelemetria(JsonNode raiz) {
        long generacionNs = raiz.path("eval_duration").asLong(0);
        int salida = raiz.path("eval_count").asInt(0);
        return new Telemetria(raiz.path("prompt_eval_count").asInt(0), salida,
                raiz.path("load_duration").asLong(0) / 1_000_000, raiz.path("total_duration").asLong(0) / 1_000_000,
                generacionNs > 0 ? salida * 1_000_000_000.0 / generacionNs : 0);
    }

    private void registrarTelemetria(ObjectNode body, String cuerpo) {
        Telemetria t;
        try {
            t = leerTelemetria(mapper.readTree(cuerpo));
        } catch (IOException e) {
            return; // parsearTurno informa el error con su propio mensaje
        }
        int contextoPedido = body.path("options").path("num_ctx").asInt(contexto);
        log.info("Ollama {}: prompt={} tokens, salida={} tokens, {} tok/s, carga={} ms, total={} ms",
                body.path("model").asText(), t.tokensPrompt(), t.tokensSalida(),
                String.format(java.util.Locale.ROOT, "%.1f", t.tokensPorSegundo()), t.cargaMs(), t.totalMs());
        if (t.cercaDelLimite(contextoPedido)) {
            log.warn("El prompt uso {} de {} tokens de contexto: Ollama pudo recortar el principio "
                    + "(instrucciones de sistema). Subir colabmodel.ai.ollama.contexto o acortar el pedido.",
                    t.tokensPrompt(), contextoPedido);
        }
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
