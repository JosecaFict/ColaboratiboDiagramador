package com.uagrm.colabmodel.ai;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.RequestOptions;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.beta.AnthropicBeta;
import com.anthropic.models.beta.messages.BetaFallbacksParam;
import com.anthropic.models.beta.messages.BetaMessage;
import com.anthropic.models.beta.messages.BetaOutputConfig;
import com.anthropic.models.beta.messages.BetaThinkingConfigAdaptive;
import com.anthropic.models.beta.messages.MessageCreateParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * La conexion con la API de Claude, UNA para todo el backend (ADR-025).
 *
 * La usan las dos funciones que dependen de un modelo: el prompt de texto
 * (ProveedorClaude, item 4.4) y la lectura de los recortes de una imagen
 * (vision/EnriquecedorPorClaude, nivel 2b de ADR-023). Una sola API key, un
 * solo modelo y una sola politica de velocidad: cambiar de modelo el dia de
 * la demo es una linea de application.yml y las dos cambian juntas.
 *
 * LO QUE ES BETA SE PRUEBA Y, SI FALLA, SE APAGA SOLO. Cada pedido se arma en
 * dos capas:
 *
 *   - la base, con funciones estables: el modelo, pensamiento adaptativo y un
 *     esfuerzo acotado --en Opus 5 es la palanca principal de latencia--, mas
 *     lo que agregue quien llama (cache del prompt, formato de salida);
 *   - los extras: modo rapido (hasta ~2,5x mas tokens por segundo, al doble
 *     de precio), el fallback del servidor cuando un clasificador de
 *     seguridad rechaza el pedido, y lo que quien llama marque como opcional
 *     (las herramientas estrictas).
 *
 * Si un pedido CON extras vuelve 4xx y el mismo pedido SIN extras sale bien,
 * los extras quedan apagados hasta reiniciar el backend, con un WARN en el
 * log. Una cuenta recien creada puede no tener habilitado el modo rapido, y la
 * demo no puede depender de eso: lo peor que puede pasar es que ande a
 * velocidad normal. Si el pedido sin extras TAMBIEN falla, el problema no eran
 * los extras y quedan como estaban.
 */
@Component
public class ClienteClaude {

    private static final Logger log = LoggerFactory.getLogger(ClienteClaude.class);

    private static final Set<String> ESFUERZOS = Set.of("low", "medium", "high", "xhigh", "max");

    /** Errores que un extra puede provocar: parametro desconocido, beta no habilitada. */
    private static final Set<Integer> CULPA_DE_UN_EXTRA = Set.of(400, 403, 404, 422);

    /**
     * Tras un 429 del modo rapido, cuanto se deja de pedirlo. Medido contra la
     * API real con una cuenta nueva: su limite propio se satura enseguida, y
     * sin la pausa CADA pedido pagaba un viaje de ida y vuelta rechazado antes
     * de salir a velocidad normal.
     */
    private static final Duration PAUSA_DEL_MODO_RAPIDO = Duration.ofMinutes(10);

    private final String modelo;
    private final BetaOutputConfig.Effort esfuerzo;
    private final boolean modoRapido;
    private final AtomicBoolean extras = new AtomicBoolean(true);
    private volatile long modoRapidoPausadoHasta = System.nanoTime();

    /** null mientras no haya API key: el backend arranca igual (ADR-012). */
    private final AnthropicClient cliente;

    /**
     * El intento con extras no reintenta: si el modo rapido esta saturado
     * (429) se pasa directo a velocidad normal en vez de esperar el backoff.
     */
    private final AnthropicClient clienteSinReintentos;

    public ClienteClaude(
            @Value("${colabmodel.ai.claude.api-key:}") String apiKey,
            @Value("${colabmodel.ai.claude.model:claude-opus-5}") String modelo,
            @Value("${colabmodel.ai.claude.esfuerzo:low}") String esfuerzo,
            @Value("${colabmodel.ai.claude.modo-rapido:true}") boolean modoRapido,
            @Value("${colabmodel.ai.claude.base-url:https://api.anthropic.com}") String baseUrl,
            @Value("${colabmodel.ai.claude.timeout-ms:60000}") long timeoutMs) {
        this.modelo = modelo.strip();
        this.esfuerzo = esfuerzoDe(esfuerzo);
        this.modoRapido = modoRapido;

        String clave = apiKey == null ? "" : apiKey.strip();
        if (clave.isEmpty()) {
            this.cliente = null;
            this.clienteSinReintentos = null;
            return;
        }
        // Hasta ahora la propiedad traia el endpoint entero; el SDK quiere la raiz.
        String raiz = baseUrl.strip().replaceAll("/+$", "").replaceFirst("/v1/messages$", "");
        this.cliente = AnthropicOkHttpClient.builder()
                .apiKey(clave)
                .baseUrl(raiz)
                .timeout(Duration.ofMillis(timeoutMs))
                // Un reintento para lo transitorio (429, 5xx, red). Mas que eso
                // es una pantalla congelada frente al docente.
                .maxRetries(1)
                .build();
        this.clienteSinReintentos = cliente.withOptions(o -> o.maxRetries(0));
    }

    /** Si hay API key. Sin ella, texto e imagen degradan como sin modelo. */
    public boolean configurado() {
        return cliente != null;
    }

    public String modelo() {
        return modelo;
    }

    /** false si el modo rapido y el fallback se apagaron porque la cuenta no los tiene. */
    public boolean extrasActivos() {
        return extras.get();
    }

    /** Si el proximo pedido va a pedir modo rapido: configurado, habilitado y no en pausa por un 429. */
    public boolean modoRapidoDisponible() {
        return modoRapido && extras.get() && System.nanoTime() - modoRapidoPausadoHasta >= 0;
    }

    /**
     * La base de todo pedido. Opus 5 ya piensa por defecto; se lo pide igual,
     * explicito, para que cambiar de modelo en la configuracion no lo apague
     * en silencio. `maxTokens` cubre pensamiento Y respuesta.
     */
    public MessageCreateParams.Builder pedido(long maxTokens) {
        return MessageCreateParams.builder()
                .model(modelo)
                .maxTokens(maxTokens)
                .thinking(BetaThinkingConfigAdaptive.builder().build());
    }

    /** El esfuerzo configurado; quien llama le suma el formato de salida si lo usa. */
    public BetaOutputConfig.Builder salida() {
        return BetaOutputConfig.builder().effort(esfuerzo);
    }

    /**
     * Manda el pedido: primero con extras, y sin ellos si hace falta.
     *
     * @param armar arma el pedido desde {@link #pedido}; recibe true cuando
     *              puede incluir funciones opcionales propias
     * @param plazo tiempo total para contestar, contando el segundo intento
     * @throws AnthropicServiceException si la API contesta con error
     * @throws com.anthropic.errors.AnthropicException si no se la pudo contactar
     * @throws IllegalStateException si no hay API key o se agoto el plazo
     */
    public BetaMessage enviar(Function<Boolean, MessageCreateParams.Builder> armar, Duration plazo) {
        if (cliente == null) {
            throw new IllegalStateException("Falta la API key de Claude");
        }
        long hasta = System.nanoTime() + plazo.toNanos();

        String motivo = null;
        if (extras.get()) {
            boolean rapido = modoRapidoDisponible();
            MessageCreateParams.Builder conExtras = armar.apply(true)
                    .fallbacks(BetaFallbacksParam.ofDefault())
                    .addBeta(AnthropicBeta.SERVER_SIDE_FALLBACK_2026_07_01);
            if (rapido) {
                conExtras.speed(MessageCreateParams.Speed.FAST).addBeta(AnthropicBeta.FAST_MODE_2026_02_01);
            }
            try {
                return registrar(clienteSinReintentos.beta().messages().create(conExtras.build(), opciones(hasta)),
                        true);
            } catch (AnthropicServiceException e) {
                // 401: la clave esta mal, sin extras falla igual. 429 y 5xx:
                // saturacion; se sigue sin extras pero no se los culpa.
                if (e.statusCode() == 401) throw e;
                if (CULPA_DE_UN_EXTRA.contains(e.statusCode())) motivo = e.statusCode() + " " + e.getMessage();
                if (e.statusCode() == 429 && rapido) {
                    modoRapidoPausadoHasta = System.nanoTime() + PAUSA_DEL_MODO_RAPIDO.toNanos();
                    log.info("Claude: modo rapido saturado (429); se deja de pedir por {} min",
                            PAUSA_DEL_MODO_RAPIDO.toMinutes());
                }
                log.debug("Claude con extras respondio {}; se reintenta sin extras", e.statusCode());
            }
        }

        BetaMessage respuesta = cliente.beta().messages().create(armar.apply(false).build(), opciones(hasta));
        if (motivo != null && extras.compareAndSet(true, false)) {
            log.warn("Claude: modo rapido, fallback y herramientas estrictas quedan APAGADOS hasta reiniciar: "
                    + "el pedido con ellos fallo ({}) y sin ellos anduvo", motivo);
        }
        return registrar(respuesta, false);
    }

    private static RequestOptions opciones(long hasta) {
        long restante = hasta - System.nanoTime();
        if (restante <= 0) {
            throw new IllegalStateException("Se agoto el plazo para esperar a Claude");
        }
        return RequestOptions.builder().timeout(Duration.ofNanos(restante)).build();
    }

    /** Para ver en el log si la cache y el modo rapido estan rindiendo. */
    private static BetaMessage registrar(BetaMessage r, boolean conExtras) {
        if (log.isDebugEnabled()) {
            log.debug("Claude ({}{}): {} tokens de entrada, {} leidos de cache, {} de salida",
                    r.model().asString(), conExtras ? ", con extras" : "",
                    r.usage().inputTokens(), r.usage().cacheReadInputTokens().orElse(0L),
                    r.usage().outputTokens());
        }
        return r;
    }

    private static BetaOutputConfig.Effort esfuerzoDe(String valor) {
        String v = valor == null ? "" : valor.strip().toLowerCase(Locale.ROOT);
        if (ESFUERZOS.contains(v)) return BetaOutputConfig.Effort.of(v);
        // Un error de tipeo en la configuracion no puede tumbar el backend.
        log.warn("colabmodel.ai.claude.esfuerzo=\"{}\" no es valido ({}); se usa low", valor, ESFUERZOS);
        return BetaOutputConfig.Effort.LOW;
    }
}
