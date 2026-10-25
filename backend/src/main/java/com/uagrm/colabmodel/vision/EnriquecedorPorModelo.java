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
import java.util.List;

/**
 * NIVEL 2 (ADR-012): le pone texto a lo que el nivel 1 ya ubico.
 *
 * Habla con un modelo de vision LOCAL por HTTP, con la API de chat compatible
 * con OpenAI que sirven tanto Ollama como llama.cpp. No hay dependencia nueva
 * en el `pom`: `java.net.http` viene en el JDK.
 *
 * NO LE MANDA LA IMAGEN ENTERA, Y ESA ES LA DECISION QUE HACE VIABLE TODO ESTO.
 * Le manda un recorte por pregunta:
 *
 *   - "leeme esta pizarra y dame un modelo UML" sobre una foto de 1400x1000
 *     son ~1800 tokens de imagen, casi un minuto de CPU sin GPU, y un modelo
 *     chico contesta con atributos que no estaban;
 *   - veinte recortes de 200x60 son ~15 tokens cada uno: mas barato que una
 *     sola imagen entera, y cada pregunta es de las que un modelo de 2 GB si
 *     acierta, porque no tiene que inferir ninguna estructura --la estructura
 *     ya se la dio la geometria--.
 *
 * DEGRADA, NO FALLA. Tiene un presupuesto de tiempo total: cuando se agota,
 * devuelve lo que llego hasta ese momento. Una propuesta con la mitad de los
 * nombres leidos es util; una peticion que nunca vuelve, no. Y si el modelo no
 * esta levantado, `disponible()` da false y el controlador ni lo llama.
 *
 * Pide de a uno y en serie a proposito: un servidor de modelo local atiende una
 * inferencia por vez, asi que mandarle cuatro juntas no acorta nada y ademas
 * dispara la memoria, que en una maquina sin GPU es el recurso que falta.
 */
@Component
@Order(20)   // ultimo de la cadena: solo pregunta lo que las capas locales no pudieron
@ConditionalOnProperty(prefix = "colabmodel.vision.nivel2", name = "proveedor", havingValue = "local",
        matchIfMissing = true)   // con Claude seleccionado, este no se crea: no leen los dos lo mismo
public class EnriquecedorPorModelo implements EnriquecedorDeDeteccion {

    private static final Logger log = LoggerFactory.getLogger(EnriquecedorPorModelo.class);

    /** Cuanto vale una respuesta del modelo. No es alta: por eso se confirma. */
    private static final double CONFIANZA = 0.7;

    /** No se le pregunta a un recorte que no puede contener texto. */
    private static final int MIN_LADO = 6;

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http;
    private final String url;
    private final String modelo;
    private final long presupuestoMs;
    private final long timeoutMs;

    /** Ultimo resultado de la comprobacion de salud, con su instante. */
    private volatile long ultimaComprobacion = 0;
    private volatile boolean ultimaRespuesta = false;

    public EnriquecedorPorModelo(
            @Value("${colabmodel.vision.nivel2.url:}") String url,
            @Value("${colabmodel.vision.nivel2.modelo:qwen2.5vl:3b}") String modelo,
            @Value("${colabmodel.vision.nivel2.presupuesto-ms:20000}") long presupuestoMs,
            @Value("${colabmodel.vision.nivel2.timeout-ms:8000}") long timeoutMs) {
        this.url = url == null ? "" : url.trim().replaceAll("/+$", "");
        this.modelo = modelo;
        this.presupuestoMs = presupuestoMs;
        this.timeoutMs = timeoutMs;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.min(timeoutMs, 3000)))
                .build();
    }

    @Override
    public String nombre() {
        return url.isEmpty() ? "modelo (sin configurar)" : "modelo " + modelo;
    }

    /**
     * Se consulta en cada peticion, no al arrancar.
     *
     * Un modelo local se cae, se queda sin memoria o lo apagan para hacer lugar
     * --en una maquina que ademas corre la base, el backend y el frontend, eso
     * pasa--. Se cachea unos segundos para no gastar un viaje por recorte.
     */
    @Override
    public boolean disponible() {
        if (url.isEmpty()) return false;
        long ahora = System.currentTimeMillis();
        if (ahora - ultimaComprobacion < 10_000) return ultimaRespuesta;
        ultimaComprobacion = ahora;
        try {
            HttpResponse<Void> r = http.send(
                    HttpRequest.newBuilder(URI.create(url + "/v1/models"))
                            .timeout(Duration.ofMillis(Math.min(timeoutMs, 2000)))
                            .GET().build(),
                    HttpResponse.BodyHandlers.discarding());
            ultimaRespuesta = r.statusCode() >= 200 && r.statusCode() < 500;
        } catch (Exception e) {
            ultimaRespuesta = false;
        }
        return ultimaRespuesta;
    }

    @Override
    public DeteccionPropuesta enriquecer(DeteccionPropuesta base, ImagenDeEntrada imagen) {
        Presupuesto reloj = new Presupuesto(presupuestoMs);

        List<ClaseDetectada> clases = new ArrayList<>(base.clases().size());
        for (ClaseDetectada c : base.clases()) {
            RecorteDeTexto nombre = leer(c.recorteNombre(), imagen, reloj);
            List<RecorteDeTexto> atributos = leerTodos(c.atributos(), imagen, reloj);
            List<RecorteDeTexto> metodos = leerTodos(c.metodos(), imagen, reloj);
            clases.add(new ClaseDetectada(c.ref(), c.caja(), c.lienzo(),
                    nombre == null ? null : nombre.texto(),
                    nombre, atributos, metodos, c.compartimentos(), c.confianza()));
        }

        List<RelacionDetectada> relaciones = new ArrayList<>(base.relaciones().size());
        for (RelacionDetectada r : base.relaciones()) {
            RecorteDeSimbolo origen = clasificar(r.simboloOrigen(), imagen, reloj);
            RecorteDeSimbolo destino = clasificar(r.simboloDestino(), imagen, reloj);
            relaciones.add(new RelacionDetectada(r.ref(), r.origenRef(), r.destinoRef(),
                    // Si ninguno de los dos extremos se reconocio, queda la
                    // lectura conservadora con la que vino.
                    kindDe(origen, destino, r.kind()),
                    origen, destino,
                    leer(r.cardinalidadOrigen(), imagen, reloj),
                    leer(r.cardinalidadDestino(), imagen, reloj),
                    r.confianza()));
        }

        List<String> avisos = new ArrayList<>(base.avisos());
        if (reloj.agotado()) {
            avisos.add("El modelo no llego a leer todos los recortes en " + presupuestoMs / 1000
                    + " s: lo que falta quedo sin texto y se puede completar a mano.");
        }
        return new DeteccionPropuesta(clases, relaciones, List.copyOf(avisos), base.metricas());
    }

    // ----------------------------------------------------------- PREGUNTAS

    private List<RecorteDeTexto> leerTodos(List<RecorteDeTexto> recortes, ImagenDeEntrada imagen,
                                           Presupuesto reloj) {
        List<RecorteDeTexto> leidos = new ArrayList<>(recortes.size());
        for (RecorteDeTexto r : recortes) leidos.add(leer(r, imagen, reloj));
        return List.copyOf(leidos);
    }

    private RecorteDeTexto leer(RecorteDeTexto recorte, ImagenDeEntrada imagen, Presupuesto reloj) {
        if (recorte == null || !vale(recorte.caja()) || reloj.agotado()) return recorte;
        // Lo que otra capa ya leyo no se vuelve a preguntar. Antes si se
        // preguntaba, y eso gastaba el presupuesto de tiempo del modelo en
        // recortes resueltos mientras los que nadie pudo leer se quedaban sin
        // turno --justo al reves de para que existe la cadena--.
        if (recorte.texto() != null) return recorte;
        String crudo = preguntar(consignaDe(recorte.rol()), recorte.caja(), imagen, reloj);
        String texto = RespuestaDeModelo.texto(crudo);
        if (texto == null) return recorte;
        return new RecorteDeTexto(recorte.ref(), recorte.rol(), recorte.caja(), texto, CONFIANZA);
    }

    private RecorteDeSimbolo clasificar(RecorteDeSimbolo recorte, ImagenDeEntrada imagen,
                                        Presupuesto reloj) {
        if (recorte == null || recorte.figura() != null || !vale(recorte.caja()) || reloj.agotado()) {
            return recorte;
        }
        RelationKind figura = RespuestaDeModelo.figura(
                preguntar(CONSIGNA_SIMBOLO, recorte.caja(), imagen, reloj));
        if (figura == null) return recorte;
        return new RecorteDeSimbolo(recorte.ref(), recorte.extremo(), recorte.caja(), figura, CONFIANZA);
    }

    private static boolean vale(Caja caja) {
        return caja != null && caja.ancho() >= MIN_LADO && caja.alto() >= MIN_LADO;
    }

    /**
     * Si alguno de los dos extremos trae figura, manda esa.
     *
     * Una generalizacion se dibuja con el triangulo en UN solo extremo, asi que
     * basta reconocerlo de un lado. Si los dos dicen algo distinto, gana el
     * origen: elegir siempre el mismo es preferible a inventar una regla de
     * desempate que nadie pidio.
     */
    private static RelationKind kindDe(RecorteDeSimbolo origen, RecorteDeSimbolo destino,
                                       RelationKind porDefecto) {
        if (origen != null && origen.figura() != null) return origen.figura();
        if (destino != null && destino.figura() != null) return destino.figura();
        return porDefecto;
    }

    private static final String CONSIGNA_SIMBOLO =
            "En esta imagen esta el extremo de una linea de un diagrama UML. "
            + "Responde UNA sola palabra, sin explicar: ROMBO_RELLENO, ROMBO_HUECO, "
            + "TRIANGULO o NINGUNA.";

    private static String consignaDe(RolDeTexto rol) {
        String que = switch (rol) {
            case NOMBRE_CLASE -> "el nombre de una clase";
            case ESTEREOTIPO -> "un estereotipo UML";
            case ATRIBUTO -> "un atributo, con su tipo si lo tiene";
            case METODO -> "la firma de un metodo";
            case CARDINALIDAD -> "una cardinalidad UML como 1, 0..* o 1..*";
            case ROL -> "el nombre de un rol";
            case NOMBRE_RELACION -> "el nombre de una relacion";
        };
        // Se le dice QUE espera encontrar: la misma imagen leida como "nombre
        // de clase" o como "cardinalidad" no se transcribe igual.
        return "Este recorte de un diagrama UML contiene " + que + ". "
                + "Transcribi EXACTAMENTE el texto que ves, sin comillas, sin traducir y sin "
                + "explicar nada. Si no hay texto legible, responde exactamente: VACIO";
    }

    // --------------------------------------------------------------- HTTP

    private String preguntar(String consigna, Caja caja, ImagenDeEntrada imagen, Presupuesto reloj) {
        long resto = reloj.restante();
        if (resto <= 0) return null;
        try {
            String png = Base64.getEncoder().encodeToString(imagen.recortarComoPng(caja));
            HttpRequest peticion = HttpRequest.newBuilder(URI.create(url + "/v1/chat/completions"))
                    .timeout(Duration.ofMillis(Math.min(timeoutMs, resto)))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(cuerpo(consigna, png)))
                    .build();

            HttpResponse<String> r = http.send(peticion, HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) {
                log.debug("El modelo respondio {} a un recorte", r.statusCode());
                return null;
            }
            JsonNode raiz = json.readTree(r.body());
            JsonNode contenido = raiz.path("choices").path(0).path("message").path("content");
            return contenido.isTextual() ? contenido.asText() : null;

        } catch (Exception e) {
            // Un recorte que falla no puede tumbar la lectura entera: el resto
            // de la propuesta sigue siendo util.
            log.debug("No se pudo leer un recorte: {}", e.toString());
            return null;
        }
    }

    /** Formato de chat con imagen, el que aceptan Ollama y llama.cpp. */
    private String cuerpo(String consigna, String pngEnBase64) {
        ObjectNode raiz = json.createObjectNode();
        raiz.put("model", modelo);
        // Determinista: la misma imagen tiene que dar la misma lectura dos
        // veces seguidas, o el usuario no puede confiar en lo que confirmo.
        raiz.put("temperature", 0);
        // Un nombre de clase no son 500 tokens. El tope corta al modelo que se
        // pone a explicar, que es el modo de falla mas comun de los chicos.
        raiz.put("max_tokens", 32);
        raiz.put("stream", false);

        ArrayNode mensajes = raiz.putArray("messages");
        ObjectNode mensaje = mensajes.addObject();
        mensaje.put("role", "user");
        ArrayNode contenido = mensaje.putArray("content");
        contenido.addObject().put("type", "text").put("text", consigna);
        ObjectNode imagen = contenido.addObject();
        imagen.put("type", "image_url");
        imagen.putObject("image_url").put("url", "data:image/png;base64," + pngEnBase64);

        return raiz.toString();
    }

    /** Cuanto tiempo queda para toda la lectura, no para una pregunta. */
    private static final class Presupuesto {
        private final long hasta;

        Presupuesto(long ms) {
            this.hasta = System.currentTimeMillis() + ms;
        }

        long restante() {
            return hasta - System.currentTimeMillis();
        }

        boolean agotado() {
            return restante() <= 0;
        }
    }
}
