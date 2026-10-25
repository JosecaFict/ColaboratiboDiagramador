package com.uagrm.colabmodel.ai;

import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.beta.messages.BetaCacheControlEphemeral;
import com.anthropic.models.beta.messages.BetaContentBlock;
import com.anthropic.models.beta.messages.BetaContentBlockParam;
import com.anthropic.models.beta.messages.BetaMessage;
import com.anthropic.models.beta.messages.BetaMessageParam;
import com.anthropic.models.beta.messages.BetaStopReason;
import com.anthropic.models.beta.messages.BetaTextBlockParam;
import com.anthropic.models.beta.messages.BetaTool;
import com.anthropic.models.beta.messages.BetaToolResultBlockParam;
import com.anthropic.models.beta.messages.BetaToolUseBlock;
import com.anthropic.models.beta.messages.MessageCreateParams;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * La API de Claude con tool use, por el SDK oficial (ADR-025).
 *
 * Activo por defecto (`matchIfMissing = true`): es el proveedor con el que se
 * hace la defensa. Ollama sigue disponible con `colabmodel.ai.proveedor=ollama`
 * para el escenario sin conexion (ADR-017).
 *
 * Lo que hace cada pedido mas rapido o mas barato, y por que:
 *
 *   - CACHE DEL PROMPT. Las herramientas y el texto de sistema son identicos
 *     en todos los pedidos; solo cambia el mensaje del usuario. La marca va en
 *     el sistema, que se dibuja DESPUES de las herramientas, asi que se
 *     cachean las dos cosas y del segundo pedido en adelante se leen de cache
 *     (un decimo del precio, y menos latencia hasta el primer token).
 *   - ESFUERZO ACOTADO, no pensamiento apagado. En Opus 5 apagar el
 *     pensamiento hace que a veces escriba una llamada a herramienta como
 *     texto en vez de hacerla: el turno "sale bien" y no pasa nada.
 *   - HERRAMIENTAS ESTRICTAS (opcional): el servidor garantiza que cada
 *     llamada respete el esquema. No reemplaza la validacion de ServicioDeIA
 *     --esa corre siempre--, pero evita perder un cambio por un campo mal
 *     escrito.
 *
 * TRABAJA POR VUELTAS. Probado contra la API real: pedido "clase Libro con
 * titulo e isbn, clase Autor, un autor escribe muchos libros", Opus 5 manda
 * los dos CREATE_CLASS y se detiene a esperar que existan antes de agregarles
 * atributos, aunque el prompt le diga que no va a haber otra vuelta. Asi que
 * a cada tanda se le contesta "registrado" y se lo deja seguir, hasta
 * {@link #MAX_TURNOS}. Nada de eso escribe: todo lo junta ServicioDeIA y lo
 * valida al final, igual que antes.
 */
@Component
@ConditionalOnProperty(prefix = "colabmodel.ai", name = "proveedor", havingValue = "claude", matchIfMissing = true)
public class ProveedorClaude implements ProveedorDeIA {

    private static final Logger log = LoggerFactory.getLogger(ProveedorClaude.class);

    /**
     * Pensamiento Y respuesta comparten este tope. Con 4096, un pedido de
     * "sistema completo" podia cortarse antes de la ultima llamada.
     */
    private static final long MAX_TOKENS = 16_000;

    /**
     * Clases, despues atributos y relaciones, y un cierre: cuatro vueltas
     * alcanzan. Mas seria esperar por cambios que el tope de 8 descarta igual.
     */
    static final int MAX_TURNOS = 4;

    private static final String REGISTRADO =
            "Registrado en la propuesta. Se valida y se aplica cuando el usuario la confirme.";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ClienteClaude claude;
    private final Duration plazo;

    public ProveedorClaude(ClienteClaude claude,
                           @Value("${colabmodel.ai.claude.timeout-ms:60000}") long timeoutMs) {
        this.claude = claude;
        this.plazo = Duration.ofMillis(timeoutMs);
    }

    @Override
    public String nombre() {
        return "claude";
    }

    @Override
    public RespuestaIA generar(SolicitudIA solicitud) {
        if (!claude.configurado()) {
            throw new ProveedorIAException(
                    "Falta configurar la API key de Claude (variable de entorno ANTHROPIC_API_KEY)");
        }
        long hasta = System.nanoTime() + plazo.toNanos();
        List<BetaMessageParam> conversacion = new ArrayList<>();
        conversacion.add(BetaMessageParam.builder()
                .role(BetaMessageParam.Role.USER)
                .content(solicitud.mensajeParaElModelo())
                .build());

        List<ComandoPropuestoIA> comandos = new ArrayList<>();
        StringBuilder nota = new StringBuilder();
        for (int turno = 1; turno <= MAX_TURNOS; turno++) {
            BetaMessage r;
            try {
                r = claude.enviar(estrictas -> pedido(solicitud, conversacion, estrictas),
                        Duration.ofNanos(hasta - System.nanoTime()));
            } catch (RuntimeException e) {
                // En la primera vuelta no hay nada que mostrar: es un error.
                // Despues, lo que ya propuso sirve igual.
                if (turno == 1) throw comoErrorDeProveedor(e);
                log.warn("Claude fallo en la vuelta {}; se devuelve lo propuesto hasta ahi", turno, e);
                agregar(nota, "(Claude no llego a terminar: puede faltar algun cambio.)");
                break;
            }

            if (r.stopReason().filter(BetaStopReason.REFUSAL::equals).isPresent()) {
                String categoria = r.stopDetails()
                        .flatMap(d -> d.category())
                        .map(c -> " (" + c.asString() + ")")
                        .orElse("");
                if (turno == 1) {
                    throw new ProveedorIAException("Claude no acepto este pedido" + categoria
                            + ". Probalo con otras palabras.");
                }
                agregar(nota, "(Claude dejo de proponer cambios" + categoria + ".)");
                break;
            }

            List<BetaToolUseBlock> llamadas = new ArrayList<>();
            for (BetaContentBlock bloque : r.content()) {
                bloque.toolUse().ifPresent(t -> {
                    llamadas.add(t);
                    comandos.add(new ComandoPropuestoIA(t.name(), t._input().convert(JsonNode.class)));
                });
                bloque.text().ifPresent(t -> agregar(nota, t.text()));
            }
            if (r.stopReason().filter(BetaStopReason.MAX_TOKENS::equals).isPresent()) {
                agregar(nota, "(La respuesta se corto por largo: puede faltar el ultimo cambio.)");
                break;
            }
            if (llamadas.isEmpty() || r.stopReason().filter(BetaStopReason.TOOL_USE::equals).isEmpty()) {
                break;
            }
            conversacion.add(r.toParam());
            conversacion.add(registrado(llamadas));
        }
        return new RespuestaIA(comandos, nota.toString().strip());
    }

    private MessageCreateParams.Builder pedido(SolicitudIA solicitud, List<BetaMessageParam> conversacion,
                                               boolean estrictas) {
        MessageCreateParams.Builder p = claude.pedido(MAX_TOKENS)
                .systemOfBetaTextBlockParams(List.of(BetaTextBlockParam.builder()
                        .text(solicitud.instruccionDeSistema())
                        .cacheControl(BetaCacheControlEphemeral.builder().build())
                        .build()))
                .outputConfig(claude.salida().build())
                .messages(conversacion);
        for (HerramientaIA h : solicitud.herramientas()) {
            p.addTool(herramienta(h, estrictas));
        }
        return p;
    }

    /** Un resultado por llamada, con el mismo id: la API rechaza una vuelta con llamadas sin contestar. */
    private static BetaMessageParam registrado(List<BetaToolUseBlock> llamadas) {
        return BetaMessageParam.builder()
                .role(BetaMessageParam.Role.USER)
                .contentOfBetaContentBlockParams(llamadas.stream()
                        .map(t -> BetaContentBlockParam.ofToolResult(BetaToolResultBlockParam.builder()
                                .toolUseId(t.id())
                                .content(REGISTRADO)
                                .build()))
                        .toList())
                .build();
    }

    /**
     * El esquema de EsquemaDeComandos, cerrado: un campo que no esta en el
     * esquema no existe (`additionalProperties: false`), que es ademas lo que
     * exige el modo estricto en cada objeto, incluidos los anidados.
     */
    static BetaTool herramienta(HerramientaIA h, boolean estricta) {
        ObjectNode esquema = cerrar(h.parametros().deepCopy());

        BetaTool.InputSchema.Properties.Builder propiedades = BetaTool.InputSchema.Properties.builder();
        esquema.path("properties").fields().forEachRemaining(campo ->
                propiedades.putAdditionalProperty(campo.getKey(), comoJsonValue(campo.getValue())));
        List<String> requeridos = new ArrayList<>();
        esquema.path("required").forEach(r -> requeridos.add(r.asText()));

        BetaTool.Builder t = BetaTool.builder()
                .name(h.nombre())
                .description(h.descripcion())
                .inputSchema(BetaTool.InputSchema.builder()
                        .properties(propiedades.build())
                        .required(requeridos)
                        .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                        .build());
        if (estricta) t.strict(true);
        return t.build();
    }

    private static ObjectNode cerrar(ObjectNode esquema) {
        if ("object".equals(esquema.path("type").asText()) && !esquema.has("additionalProperties")) {
            esquema.put("additionalProperties", false);
        }
        esquema.path("properties").forEach(p -> {
            if (p instanceof ObjectNode o) cerrar(o);
        });
        if (esquema.path("items") instanceof ObjectNode items) cerrar(items);
        return esquema;
    }

    private static JsonValue comoJsonValue(JsonNode nodo) {
        return JsonValue.from(JSON.convertValue(nodo, Map.class));
    }

    private static RuntimeException comoErrorDeProveedor(RuntimeException e) {
        if (e instanceof AnthropicServiceException s) {
            return new ProveedorIAException("Claude respondio " + s.statusCode() + ": " + s.getMessage());
        }
        if (e instanceof AnthropicException || e instanceof IllegalStateException) {
            return new ProveedorIAException("No se pudo contactar a Claude: " + e.getMessage());
        }
        return e;
    }

    private static void agregar(StringBuilder nota, String texto) {
        if (texto == null || texto.isBlank()) return;
        if (!nota.isEmpty()) nota.append(' ');
        nota.append(texto.strip());
    }
}
