package com.uagrm.colabmodel.vision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.uagrm.colabmodel.modelcore.metamodel.RelationKind;
import com.uagrm.colabmodel.vision.DeteccionPropuesta.Caja;
import com.uagrm.colabmodel.vision.DeteccionPropuesta.ClaseDetectada;
import com.uagrm.colabmodel.vision.DeteccionPropuesta.RecorteDeSimbolo;
import com.uagrm.colabmodel.vision.DeteccionPropuesta.RecorteDeTexto;
import com.uagrm.colabmodel.vision.DeteccionPropuesta.RelacionDetectada;
import com.uagrm.colabmodel.vision.DeteccionPropuesta.RolDeTexto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * NIVEL 2 contra Gemini: los MISMOS recortes, pero TODOS en UNA sola peticion.
 *
 * Es una de las tres implementaciones de {@link EnriquecedorDeDeteccion},
 * junto a {@link EnriquecedorPorModelo} y {@link EnriquecedorPorClaude}: se
 * elige UNA con `colabmodel.vision.nivel2.proveedor`, porque dos leyendo los
 * mismos recortes es pagar dos veces lo mismo. La estrategia del nivel 1 no cambia --la
 * geometria sigue dando la estructura y el modelo solo transcribe recortes,
 * ADR-023--, pero el transporte si, y por un motivo que no es de estilo:
 *
 *   - contra un modelo LOCAL, una pregunta por recorte es gratis: el servidor
 *     atiende una inferencia por vez y mandarle veinte seguidas no cuesta nada
 *     mas que tiempo de CPU;
 *   - contra una API en la nube, esas mismas veinte preguntas son veinte
 *     peticiones, y el plan gratuito de AI Studio limita las peticiones POR
 *     MINUTO. Una foto con seis clases pasa las treinta: la mitad volveria con
 *     429 y la propuesta saldria a medio leer por una razon que no tiene nada
 *     que ver con la foto.
 *
 * Por eso aca los recortes van numerados en un solo pedido, y la respuesta
 * viene como un JSON con un renglon por numero (`responseSchema`, salida
 * estructurada). Una peticion por foto entra comoda en cualquier cuota, y el
 * modelo sigue sin ver nunca la imagen entera: no puede inventar una clase que
 * la geometria no vio, porque no sabe que esta mirando un diagrama completo.
 *
 * TODO LO DEMAS SE RESPETA IGUAL. No pregunta por lo que otra capa ya resolvio
 * (un simbolo que el {@link EnriquecedorGeometrico} ya clasifico, que corre
 * siempre y antes que esta capa), cada respuesta pasa por el mismo filtro
 * {@link RespuestaDeModelo} --que es lo que impide que "I cannot read this
 * image" termine de nombre de una clase-- y si algo falla se devuelve lo que
 * habia con un aviso, nunca una excepcion: el nivel 1 solo, sin textos, sigue
 * siendo una propuesta util.
 */
@Component
@Order(20)   // despues del geometrico: no vuelve a preguntar lo que ese ya resolvio
@ConditionalOnProperty(prefix = "colabmodel.vision.nivel2", name = "proveedor", havingValue = "gemini")
public class EnriquecedorPorGemini implements EnriquecedorDeDeteccion {

    private static final Logger log = LoggerFactory.getLogger(EnriquecedorPorGemini.class);

    /**
     * Mas alta que la del modelo local (0.7) porque un modelo grande leyendo un
     * recorte chico acierta mas, pero deliberadamente lejos de 1: esto se
     * confirma en el panel antes de escribir nada, como todo lo del nivel 2.
     */
    private static final double CONFIANZA = 0.8;

    /** No se le pregunta a un recorte que no puede contener texto. */
    private static final int MIN_LADO = 6;

    private static final String INSTRUCCIONES =
            "Sos un OCR de diagramas UML dibujados a mano o en pizarra. Vas a recibir varios "
          + "RECORTES numerados de una misma imagen. Cada recorte viene precedido por su numero "
          + "y por lo que se espera encontrar adentro.\n"
          + "Para cada recorte de TEXTO: transcribi EXACTAMENTE lo que ves, sin comillas, sin "
          + "traducir, sin corregir la ortografia y sin explicar nada. Si no hay texto legible, "
          + "responde exactamente VACIO.\n"
          + "Para cada recorte de SIMBOLO: responde UNA sola palabra, una de ROMBO_RELLENO, "
          + "ROMBO_HUECO, TRIANGULO o NINGUNA.\n"
          + "No agregues recortes que no esten, no juntes dos en uno y no cambies el orden: "
          + "contesta exactamente un renglon por numero recibido.";

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http;
    private final String apiKey;
    private final String modelo;
    private final String baseUrl;
    private final int maxRecortes;
    private final long timeoutMs;

    public EnriquecedorPorGemini(
            @Value("${colabmodel.vision.nivel2.gemini.api-key:}") String apiKey,
            @Value("${colabmodel.vision.nivel2.gemini.modelo:gemini-3.8-flash}") String modelo,
            @Value("${colabmodel.vision.nivel2.gemini.base-url:https://generativelanguage.googleapis.com/v1beta}") String baseUrl,
            @Value("${colabmodel.vision.nivel2.gemini.max-recortes:80}") int maxRecortes,
            @Value("${colabmodel.vision.nivel2.gemini.timeout-ms:60000}") long timeoutMs) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.modelo = modelo;
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
        this.maxRecortes = maxRecortes;
        this.timeoutMs = timeoutMs;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public String nombre() {
        return apiKey.isEmpty() ? "gemini (sin clave)" : "gemini " + modelo;
    }

    /**
     * Alcanza con que haya clave: no se hace una comprobacion de salud previa.
     *
     * El modelo local se cae, se queda sin memoria o lo apagan, y por eso
     * {@link EnriquecedorPorModelo} lo sondea en cada peticion. Google no se
     * apaga; lo que puede fallar es la clave o la cuota, y eso lo dice la unica
     * peticion que se hace igual. Sondear seria gastar media cuota en preguntar
     * si queda cuota.
     */
    @Override
    public boolean disponible() {
        return !apiKey.isEmpty();
    }

    @Override
    public DeteccionPropuesta enriquecer(DeteccionPropuesta base, ImagenDeEntrada imagen) {
        List<Pendiente> pendientes = recolectar(base);
        if (pendientes.isEmpty()) return base;

        List<String> avisos = new ArrayList<>(base.avisos());
        boolean truncado = pendientes.size() > maxRecortes;
        if (truncado) {
            pendientes = pendientes.subList(0, maxRecortes);
            avisos.add("La imagen tiene mas de " + maxRecortes + " recortes de texto: se leyeron los "
                    + maxRecortes + " primeros y el resto quedo para completar a mano.");
        }

        Map<String, String> porRef = preguntar(pendientes, imagen, avisos);
        if (porRef.isEmpty()) {
            return new DeteccionPropuesta(base.clases(), base.relaciones(), List.copyOf(avisos), base.metricas());
        }

        List<ClaseDetectada> clases = new ArrayList<>(base.clases().size());
        for (ClaseDetectada c : base.clases()) {
            RecorteDeTexto nombre = leido(c.recorteNombre(), porRef);
            clases.add(new ClaseDetectada(c.ref(), c.caja(), c.lienzo(),
                    // Si el recorte sigue sin leerse, se conserva el nombre que
                    // haya puesto una capa anterior en vez de borrarlo.
                    nombre != null && nombre.texto() != null ? nombre.texto() : c.nombre(),
                    nombre, leidos(c.atributos(), porRef), leidos(c.metodos(), porRef),
                    c.compartimentos(), c.confianza()));
        }

        List<RelacionDetectada> relaciones = new ArrayList<>(base.relaciones().size());
        for (RelacionDetectada r : base.relaciones()) {
            RecorteDeSimbolo origen = clasificado(r.simboloOrigen(), porRef);
            RecorteDeSimbolo destino = clasificado(r.simboloDestino(), porRef);
            relaciones.add(new RelacionDetectada(r.ref(), r.origenRef(), r.destinoRef(),
                    kindDe(origen, destino, r.kind()), origen, destino,
                    leido(r.cardinalidadOrigen(), porRef), leido(r.cardinalidadDestino(), porRef),
                    r.confianza()));
        }
        return new DeteccionPropuesta(clases, relaciones, List.copyOf(avisos), base.metricas());
    }

    // ------------------------------------------------------------ QUE FALTA

    /** Un recorte que todavia nadie leyo, con lo que se espera encontrar adentro. */
    private record Pendiente(String ref, Caja caja, String consigna, boolean simbolo) { }

    private List<Pendiente> recolectar(DeteccionPropuesta base) {
        List<Pendiente> pendientes = new ArrayList<>();
        for (ClaseDetectada c : base.clases()) {
            agregar(pendientes, c.recorteNombre());
            for (RecorteDeTexto a : c.atributos()) agregar(pendientes, a);
            for (RecorteDeTexto m : c.metodos()) agregar(pendientes, m);
        }
        for (RelacionDetectada r : base.relaciones()) {
            agregar(pendientes, r.simboloOrigen());
            agregar(pendientes, r.simboloDestino());
            agregar(pendientes, r.cardinalidadOrigen());
            agregar(pendientes, r.cardinalidadDestino());
        }
        return pendientes;
    }

    private void agregar(List<Pendiente> pendientes, RecorteDeTexto recorte) {
        // Con texto ya leido no se vuelve a preguntar: las capas se suman.
        if (recorte == null || recorte.texto() != null || !vale(recorte.caja())) return;
        pendientes.add(new Pendiente(recorte.ref(), recorte.caja(), queEs(recorte.rol()), false));
    }

    private void agregar(List<Pendiente> pendientes, RecorteDeSimbolo recorte) {
        if (recorte == null || recorte.figura() != null || !vale(recorte.caja())) return;
        pendientes.add(new Pendiente(recorte.ref(), recorte.caja(),
                "SIMBOLO: el extremo de una linea de un diagrama UML", true));
    }

    private static boolean vale(Caja caja) {
        return caja != null && caja.ancho() >= MIN_LADO && caja.alto() >= MIN_LADO;
    }

    /** Se le dice QUE espera encontrar: un mismo recorte no se transcribe igual como nombre que como cardinalidad. */
    private static String queEs(RolDeTexto rol) {
        return "TEXTO: " + switch (rol) {
            case NOMBRE_CLASE -> "el nombre de una clase";
            case ESTEREOTIPO -> "un estereotipo UML";
            case ATRIBUTO -> "un atributo, con su tipo si lo tiene";
            case METODO -> "la firma de un metodo";
            case CARDINALIDAD -> "una cardinalidad UML como 1, 0..* o 1..*";
            case ROL -> "el nombre de un rol";
            case NOMBRE_RELACION -> "el nombre de una relacion";
        };
    }

    // ------------------------------------------------------- LO QUE VOLVIO

    private List<RecorteDeTexto> leidos(List<RecorteDeTexto> recortes, Map<String, String> porRef) {
        List<RecorteDeTexto> salida = new ArrayList<>(recortes.size());
        for (RecorteDeTexto r : recortes) salida.add(leido(r, porRef));
        return List.copyOf(salida);
    }

    private RecorteDeTexto leido(RecorteDeTexto recorte, Map<String, String> porRef) {
        if (recorte == null) return null;
        String crudo = porRef.get(recorte.ref());
        String texto = RespuestaDeModelo.texto(crudo);
        if (texto == null) return recorte;
        return new RecorteDeTexto(recorte.ref(), recorte.rol(), recorte.caja(), texto, CONFIANZA);
    }

    private RecorteDeSimbolo clasificado(RecorteDeSimbolo recorte, Map<String, String> porRef) {
        if (recorte == null) return null;
        RelationKind figura = RespuestaDeModelo.figura(porRef.get(recorte.ref()));
        if (figura == null) return recorte;
        return new RecorteDeSimbolo(recorte.ref(), recorte.extremo(), recorte.caja(), figura, CONFIANZA);
    }

    /** Un solo extremo reconocido alcanza; si los dos dicen cosas distintas, gana el origen. */
    private static RelationKind kindDe(RecorteDeSimbolo origen, RecorteDeSimbolo destino,
                                       RelationKind porDefecto) {
        if (origen != null && origen.figura() != null) return origen.figura();
        if (destino != null && destino.figura() != null) return destino.figura();
        return porDefecto;
    }

    // --------------------------------------------------------------- HTTP

    /** @return respuesta cruda por ref de recorte; vacio si no se pudo preguntar */
    private Map<String, String> preguntar(List<Pendiente> pendientes, ImagenDeEntrada imagen,
                                          List<String> avisos) {
        String cuerpo;
        try {
            cuerpo = cuerpo(pendientes, imagen);
        } catch (RuntimeException e) {
            log.warn("No se pudieron recortar las imagenes para Gemini: {}", e.toString());
            avisos.add("No se pudieron preparar los recortes para leerlos: la propuesta sale sin textos.");
            return Map.of();
        }

        try {
            HttpRequest peticion = HttpRequest.newBuilder(
                        URI.create(baseUrl + "/models/" + modelo + ":generateContent"))
                    .timeout(Duration.ofMillis(timeoutMs))
                    // La clave va en la cabecera y NUNCA en ?key=: en la URL
                    // queda escrita en logs de acceso y en cualquier proxy.
                    .header("x-goog-api-key", apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(cuerpo))
                    .build();

            HttpResponse<String> r = http.send(peticion, HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) {
                log.warn("Gemini respondio {} al leer la imagen: {}", r.statusCode(), r.body());
                avisos.add(r.statusCode() == 429
                        ? "Se agoto la cuota gratuita de Gemini: las cajas salen sin texto y se pueden "
                          + "completar a mano."
                        : "Gemini respondio " + r.statusCode() + " al leer la imagen: las cajas salen "
                          + "sin texto y se pueden completar a mano.");
                return Map.of();
            }
            return interpretar(r.body(), pendientes, avisos);

        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            // Que se caiga la lectura no puede llevarse la deteccion entera.
            log.warn("No se pudo leer la imagen con Gemini: {}", e.toString());
            avisos.add("No se pudo consultar a Gemini para leer los textos: la propuesta sale con las "
                    + "cajas ubicadas pero sin nombres.");
            return Map.of();
        }
    }

    private String cuerpo(List<Pendiente> pendientes, ImagenDeEntrada imagen) {
        ObjectNode raiz = json.createObjectNode();
        raiz.putObject("systemInstruction").putArray("parts").addObject().put("text", INSTRUCCIONES);

        ObjectNode turno = raiz.putArray("contents").addObject();
        turno.put("role", "user");
        ArrayNode partes = turno.putArray("parts");
        for (int i = 0; i < pendientes.size(); i++) {
            Pendiente p = pendientes.get(i);
            partes.addObject().put("text", "#" + (i + 1) + " " + p.consigna());
            ObjectNode dato = partes.addObject().putObject("inlineData");
            dato.put("mimeType", "image/png");
            dato.put("data", Base64.getEncoder().encodeToString(imagen.recortarComoPng(p.caja())));
        }

        ObjectNode config = raiz.putObject("generationConfig");
        // Determinista: la misma foto tiene que dar la misma lectura dos veces
        // seguidas, o el usuario no puede confiar en lo que confirmo.
        config.put("temperature", 0);
        // Salida estructurada: sin esto hay que adivinar donde empieza el JSON
        // entre el "Aca tenes las transcripciones:" que le sale solo al modelo.
        config.put("responseMimeType", "application/json");
        config.set("responseSchema", esquemaDeRespuesta());
        // Un nombre de clase no son 500 tokens; se acota por recorte, con aire.
        config.put("maxOutputTokens", Math.min(8192, 256 + 40 * pendientes.size()));
        return raiz.toString();
    }

    private ObjectNode esquemaDeRespuesta() {
        ObjectNode lista = json.createObjectNode();
        lista.put("type", "array");
        ObjectNode item = lista.putObject("items");
        item.put("type", "object");
        ObjectNode props = item.putObject("properties");
        props.putObject("n").put("type", "integer");
        props.putObject("texto").put("type", "string");
        ArrayNode req = item.putArray("required");
        req.add("n");
        req.add("texto");
        return lista;
    }

    private Map<String, String> interpretar(String respuesta, List<Pendiente> pendientes,
                                            List<String> avisos) {
        Map<String, String> porRef = new HashMap<>();
        try {
            JsonNode raiz = json.readTree(respuesta);
            JsonNode candidato = raiz.path("candidates").path(0);

            StringBuilder texto = new StringBuilder();
            for (JsonNode parte : candidato.path("content").path("parts")) {
                if (parte.path("thought").asBoolean(false)) continue;   // razonamiento, no respuesta
                if (parte.hasNonNull("text")) texto.append(parte.path("text").asText());
            }
            if (texto.isEmpty()) {
                avisos.add("Gemini no devolvio ninguna transcripcion ("
                        + candidato.path("finishReason").asText("sin motivo") + ").");
                return Map.of();
            }

            for (JsonNode renglon : json.readTree(texto.toString())) {
                int n = renglon.path("n").asInt(0);
                if (n < 1 || n > pendientes.size()) continue;   // numero inventado: se ignora
                porRef.put(pendientes.get(n - 1).ref(), renglon.path("texto").asText(null));
            }
            if (porRef.size() < pendientes.size()) {
                avisos.add("Gemini leyo " + porRef.size() + " de " + pendientes.size()
                        + " recortes: lo que falta se puede completar a mano.");
            }
            return porRef;

        } catch (Exception e) {
            log.warn("Respuesta de Gemini ilegible: {}", e.toString());
            avisos.add("La respuesta de Gemini no se pudo interpretar: la propuesta sale sin textos.");
            return Map.of();
        }
    }
}
