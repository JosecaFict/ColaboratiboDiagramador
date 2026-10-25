package com.uagrm.colabmodel.vision;

import com.anthropic.core.JsonValue;
import com.anthropic.models.beta.messages.BetaBase64ImageSource;
import com.anthropic.models.beta.messages.BetaContentBlockParam;
import com.anthropic.models.beta.messages.BetaImageBlockParam;
import com.anthropic.models.beta.messages.BetaJsonOutputFormat;
import com.anthropic.models.beta.messages.BetaMessage;
import com.anthropic.models.beta.messages.BetaStopReason;
import com.anthropic.models.beta.messages.BetaThinkingConfigDisabled;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uagrm.colabmodel.ai.ClienteClaude;
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

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * NIVEL 2b (ADR-023) con la API de Claude en vez de un modelo local (ADR-025).
 *
 * Misma doctrina que {@link EnriquecedorPorModelo}: NO se le manda la imagen
 * entera --un modelo de vision que ve la pizarra completa "completa" atributos
 * plausibles que no estaban, y ese modo de falla no se ve--. Se le mandan los
 * recortes que el nivel 1 ya ubico, cada uno con lo que se espera encontrar.
 *
 * Lo que cambia es COMO se pregunta. Un modelo local atiende una inferencia
 * por vez, asi que alla van de a una y en serie. Contra una API en la nube eso
 * seria lo peor: veinte viajes de red, veinte veces la latencia y el limite de
 * pedidos por minuto agotado en una sola foto. Aca van TODOS los recortes en
 * UN pedido, cada imagen precedida por su identificador, y la respuesta vuelve
 * como JSON con un esquema que el servidor garantiza (structured outputs):
 * nada de parsear "El texto dice: Libro".
 *
 * Aun asi, cada texto pasa por {@link RespuestaDeModelo} antes de entrar: el
 * esquema garantiza la FORMA, no que el contenido sea un nombre y no una
 * disculpa. Y la confianza no es 1: todo se confirma en el panel.
 *
 * DEGRADA, NO FALLA: sin API key no esta disponible; si Claude no contesta a
 * tiempo o contesta con error, la propuesta sale como vino, con un aviso.
 */
@Component
@Order(20)   // despues del geometrico: no vuelve a clasificar lo que ese ya resolvio
@ConditionalOnProperty(prefix = "colabmodel.vision.nivel2", name = "proveedor", havingValue = "claude")
public class EnriquecedorPorClaude implements EnriquecedorDeDeteccion {

    private static final Logger log = LoggerFactory.getLogger(EnriquecedorPorClaude.class);

    /** Mas que la de un modelo local de 2 GB, menos que 1: se confirma igual. */
    private static final double CONFIANZA = 0.85;

    /** No se le pregunta a un recorte que no puede contener texto. */
    private static final int MIN_LADO = 6;

    /**
     * Recortes por pedido, por defecto; las tandas viajan en paralelo. Es una
     * perilla entre tokens y espera, medida contra la API real con una captura
     * de 6 clases y 40 recortes:
     *
     *   - 80 (un solo pedido): 1868 tokens de entrada, 444 de salida, 13,5 s;
     *   - 15 (cuatro en paralelo): 3680 de entrada, 477 de salida, 10,1 s.
     *
     * En paralelo se espera menos porque la respuesta se genera token a token y
     * cada pedido escribe su parte a la vez, pero cada uno repite el texto de
     * sistema y el esquema: cuesta ~50 % mas por imagen. Por defecto, tokens.
     * La API acepta hasta 100 imagenes por pedido.
     */
    static final int RECORTES_POR_PEDIDO = 80;

    /** Un "1" de cardinalidad son 10x14 pixeles: se agranda antes de mandarlo. */
    private static final int LADO_LEGIBLE = 40;

    /** Una lectura por recorte, en JSON: no hace falta mas. */
    private static final long MAX_TOKENS = 8_000;

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String SISTEMA =
            "Transcribis recortes de un diagrama de clases UML que un detector geometrico ya ubico. "
          + "Vienen agrupados por lo que se espera encontrar en ellos, y cada imagen va precedida por su "
          + "identificador entre corchetes. Para los textos: copia EXACTAMENTE lo que se ve, sin traducir, sin "
          + "corregir la ortografia y sin completar lo que no se ve; si no hay texto legible, deja la "
          + "lectura vacia. Para los extremos de linea: la figura que se ve, o NINGUNA. Ante la duda, "
          + "vacia o NINGUNA: un hueco lo completa el usuario, un dato inventado nadie lo ve. Devolve "
          + "una entrada por identificador, con la forma id|lectura: por ejemplo t3|- titulo: String, "
          + "s7|TRIANGULO, o t9| si no se lee.";

    private static final BetaJsonOutputFormat FORMATO = formato();

    private final ClienteClaude claude;
    private final long presupuestoMs;
    private final int recortesPorPedido;

    public EnriquecedorPorClaude(ClienteClaude claude,
                                 @Value("${colabmodel.vision.nivel2.presupuesto-ms:20000}") long presupuestoMs,
                                 @Value("${colabmodel.vision.nivel2.recortes-por-pedido:" + RECORTES_POR_PEDIDO + "}")
                                 int recortesPorPedido) {
        this.claude = claude;
        this.presupuestoMs = presupuestoMs;
        this.recortesPorPedido = Math.max(1, Math.min(100, recortesPorPedido));
    }

    @Override
    public String nombre() {
        return "Claude (" + claude.modelo() + ")";
    }

    /** No se consulta la red: sin clave no hay nada que probar, y con clave se degrada si falla. */
    @Override
    public boolean disponible() {
        return claude.configurado();
    }

    @Override
    public DeteccionPropuesta enriquecer(DeteccionPropuesta base, ImagenDeEntrada imagen) {
        Preguntas preguntas = new Preguntas();
        for (ClaseDetectada c : base.clases()) {
            preguntas.texto(c.recorteNombre());
            c.atributos().forEach(preguntas::texto);
            c.metodos().forEach(preguntas::texto);
        }
        for (RelacionDetectada r : base.relaciones()) {
            preguntas.simbolo(r.simboloOrigen());
            preguntas.simbolo(r.simboloDestino());
            preguntas.texto(r.cardinalidadOrigen());
            preguntas.texto(r.cardinalidadDestino());
        }
        if (preguntas.lista.isEmpty()) return base;

        Map<String, String> respuestas = new HashMap<>();
        List<String> avisos = new ArrayList<>(base.avisos());
        leer(preguntas.lista, imagen, respuestas, avisos);

        List<ClaseDetectada> clases = new ArrayList<>(base.clases().size());
        for (ClaseDetectada c : base.clases()) {
            RecorteDeTexto nombre = preguntas.aplicar(c.recorteNombre(), respuestas);
            clases.add(new ClaseDetectada(c.ref(), c.caja(), c.lienzo(),
                    nombre != null && nombre.texto() != null ? nombre.texto() : c.nombre(),
                    nombre,
                    c.atributos().stream().map(a -> preguntas.aplicar(a, respuestas)).toList(),
                    c.metodos().stream().map(m -> preguntas.aplicar(m, respuestas)).toList(),
                    c.compartimentos(), c.confianza()));
        }

        List<RelacionDetectada> relaciones = new ArrayList<>(base.relaciones().size());
        for (RelacionDetectada r : base.relaciones()) {
            RecorteDeSimbolo origen = preguntas.aplicar(r.simboloOrigen(), respuestas);
            RecorteDeSimbolo destino = preguntas.aplicar(r.simboloDestino(), respuestas);
            relaciones.add(new RelacionDetectada(r.ref(), r.origenRef(), r.destinoRef(),
                    kindDe(origen, destino, r.kind()), origen, destino,
                    preguntas.aplicar(r.cardinalidadOrigen(), respuestas),
                    preguntas.aplicar(r.cardinalidadDestino(), respuestas),
                    r.confianza()));
        }
        return new DeteccionPropuesta(clases, relaciones, List.copyOf(avisos), base.metricas());
    }

    // ------------------------------------------------------------- PEDIDOS

    /** Una tanda si alcanza; si no, varias en paralelo. Una que falla no tumba a las demas. */
    private void leer(List<Pregunta> todas, ImagenDeEntrada imagen, Map<String, String> respuestas,
                      List<String> avisos) {
        List<List<Pregunta>> tandas = new ArrayList<>();
        for (int i = 0; i < todas.size(); i += recortesPorPedido) {
            tandas.add(todas.subList(i, Math.min(todas.size(), i + recortesPorPedido)));
        }
        long inicio = System.nanoTime();
        int fallidas = 0;
        boolean sinTiempo = false;
        try (ExecutorService hilos = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Map<String, String>>> pendientes = new ArrayList<>();
            for (List<Pregunta> tanda : tandas) pendientes.add(hilos.submit(() -> leerTanda(tanda, imagen)));
            for (Future<Map<String, String>> f : pendientes) {
                try {
                    respuestas.putAll(f.get());
                } catch (ExecutionException e) {
                    fallidas++;
                    sinTiempo |= Duration.ofNanos(System.nanoTime() - inicio).toMillis() >= presupuestoMs;
                    log.warn("Claude no pudo leer una tanda de recortes; quedan sin texto", e.getCause());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    fallidas++;
                }
            }
        }
        if (fallidas > 0) {
            avisos.add(sinTiempo
                    ? "Claude no llego a leer los textos en " + presupuestoMs / 1000
                      + " s: lo que falta quedo sin texto y se puede completar a mano."
                    : "Claude no pudo leer los textos de la imagen: quedaron sin completar y se pueden "
                      + "escribir a mano.");
        }
    }

    /**
     * La consigna va UNA vez por grupo y cada recorte lleva solo su id.
     * Medido con count_tokens sobre una captura de 6 clases y 40 recortes: la
     * consigna repetida en cada uno eran 999 de 1889 tokens de entrada, mas
     * que todas las imagenes juntas.
     */
    private Map<String, String> leerTanda(List<Pregunta> tanda, ImagenDeEntrada imagen) {
        Map<String, List<Pregunta>> porConsigna = new LinkedHashMap<>();
        for (Pregunta p : tanda) porConsigna.computeIfAbsent(p.consigna(), k -> new ArrayList<>()).add(p);

        List<BetaContentBlockParam> contenido = new ArrayList<>(tanda.size() * 2 + porConsigna.size() + 1);
        porConsigna.forEach((consigna, grupo) -> {
            contenido.add(BetaContentBlockParam.ofText(consigna));
            for (Pregunta p : grupo) {
                contenido.add(BetaContentBlockParam.ofText("[" + p.id() + "]"));
                contenido.add(BetaContentBlockParam.ofImage(BetaImageBlockParam.builder()
                        .source(BetaBase64ImageSource.builder()
                                .mediaType(BetaBase64ImageSource.MediaType.IMAGE_PNG)
                                .data(pngLegible(imagen, p.caja()))
                                .build())
                        .build()));
            }
        });
        contenido.add(BetaContentBlockParam.ofText(
                "Devolve la lectura de estos " + tanda.size() + " recortes."));

        BetaMessage r = claude.enviar(extras -> claude.pedido(MAX_TOKENS)
                        // Transcribir no es razonar, y los tokens de salida son los caros
                        // y los lentos. Los dos riesgos de apagar el pensamiento en Opus 5
                        // (una llamada escrita como texto, etiquetas internas en la
                        // respuesta) no aplican: no hay herramientas y la salida esta
                        // atada al esquema.
                        .thinking(BetaThinkingConfigDisabled.builder().build())
                        .system(SISTEMA)
                        .outputConfig(claude.salida().format(FORMATO).build())
                        .addUserMessageOfBetaContentBlockParams(contenido),
                Duration.ofMillis(presupuestoMs));

        if (r.stopReason().filter(BetaStopReason.REFUSAL::equals).isPresent()) {
            throw new IllegalStateException("Claude no acepto leer los recortes");
        }
        StringBuilder texto = new StringBuilder();
        r.content().forEach(b -> b.text().ifPresent(t -> texto.append(t.text())));
        return lecturas(texto.toString());
    }

    /**
     * id -> lo que se leyo, sin filtrar todavia. Un id que el modelo no
     * devolvio simplemente no esta: ese recorte queda como vino.
     *
     * Se corta en el PRIMER "|": el id nunca lo tiene, y la lectura si puede
     * tenerlo ("estado: A|B").
     */
    static Map<String, String> lecturas(String json) {
        JsonNode raiz;
        try {
            raiz = JSON.readTree(json);
        } catch (IOException e) {
            throw new IllegalStateException("Claude devolvio algo que no es JSON: " + e.getMessage());
        }
        Map<String, String> leido = new HashMap<>();
        for (JsonNode entrada : raiz.path("lecturas")) {
            String s = entrada.asText("");
            int corte = s.indexOf('|');
            if (corte <= 0) continue;
            leido.put(s.substring(0, corte).strip(), s.substring(corte + 1));
        }
        return leido;
    }

    /**
     * El recorte en PNG, agrandado si es muy chico. Un modelo lee mucho mejor
     * un "0..*" de 40 pixeles de alto que uno de 12; el costo en tokens de una
     * imagen asi sigue siendo despreciable.
     */
    static String pngLegible(ImagenDeEntrada imagen, Caja caja) {
        BufferedImage recorte = imagen.recortar(caja);
        int menor = Math.min(recorte.getWidth(), recorte.getHeight());
        if (menor < LADO_LEGIBLE) {
            int k = Math.min(4, (LADO_LEGIBLE + menor - 1) / menor);
            BufferedImage grande = new BufferedImage(recorte.getWidth() * k, recorte.getHeight() * k,
                    BufferedImage.TYPE_INT_RGB);
            Graphics2D g = grande.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.drawImage(recorte, 0, 0, grande.getWidth(), grande.getHeight(), null);
            g.dispose();
            recorte = grande;
        }
        try {
            ByteArrayOutputStream salida = new ByteArrayOutputStream(4096);
            ImageIO.write(recorte, "png", salida);
            return Base64.getEncoder().encodeToString(salida.toByteArray());
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo serializar el recorte", e);
        }
    }

    // ----------------------------------------------------------- PREGUNTAS

    private record Pregunta(String id, String consigna, Caja caja) { }

    /**
     * Le da un id corto a cada recorte que hay que leer y despues aplica la
     * respuesta al mismo objeto. Por identidad y no por igualdad: dos recortes
     * pueden ser iguales campo a campo y seguir siendo dos preguntas.
     */
    private static final class Preguntas {
        final List<Pregunta> lista = new ArrayList<>();
        final Map<Object, String> ids = new IdentityHashMap<>();

        void texto(RecorteDeTexto r) {
            if (r == null || r.texto() != null || !vale(r.caja())) return;
            String id = "t" + (lista.size() + 1);
            ids.put(r, id);
            lista.add(new Pregunta(id, "Cada uno de estos recortes contiene " + queContiene(r.rol()) + ":",
                    r.caja()));
        }

        void simbolo(RecorteDeSimbolo r) {
            // Si el geometrico ya lo clasifico, no se pregunta de nuevo.
            if (r == null || r.figura() != null || !vale(r.caja())) return;
            String id = "s" + (lista.size() + 1);
            ids.put(r, id);
            lista.add(new Pregunta(id, "Cada uno de estos recortes es el extremo de una linea "
                    + "(ROMBO_RELLENO, ROMBO_HUECO, TRIANGULO o NINGUNA):", r.caja()));
        }

        RecorteDeTexto aplicar(RecorteDeTexto r, Map<String, String> respuestas) {
            String id = r == null ? null : ids.get(r);
            if (id == null) return r;
            String texto = RespuestaDeModelo.texto(respuestas.get(id));
            if (texto == null) return r;
            return new RecorteDeTexto(r.ref(), r.rol(), r.caja(), texto, CONFIANZA);
        }

        RecorteDeSimbolo aplicar(RecorteDeSimbolo r, Map<String, String> respuestas) {
            String id = r == null ? null : ids.get(r);
            if (id == null) return r;
            RelationKind figura = RespuestaDeModelo.figura(respuestas.get(id));
            if (figura == null) return r;
            return new RecorteDeSimbolo(r.ref(), r.extremo(), r.caja(), figura, CONFIANZA);
        }
    }

    private static boolean vale(Caja caja) {
        return caja != null && caja.ancho() >= MIN_LADO && caja.alto() >= MIN_LADO;
    }

    private static String queContiene(RolDeTexto rol) {
        return switch (rol) {
            case NOMBRE_CLASE -> "el nombre de una clase";
            case ESTEREOTIPO -> "un estereotipo UML";
            case ATRIBUTO -> "un atributo, con su tipo si lo tiene";
            case METODO -> "la firma de un metodo";
            case CARDINALIDAD -> "una cardinalidad UML como 1, 0..* o 1..*";
            case ROL -> "el nombre de un rol";
            case NOMBRE_RELACION -> "el nombre de una relacion";
        };
    }

    /** Igual que en los otros niveles 2: un extremo con figura manda; gana el origen. */
    private static RelationKind kindDe(RecorteDeSimbolo origen, RecorteDeSimbolo destino,
                                       RelationKind porDefecto) {
        if (origen != null && origen.figura() != null) return origen.figura();
        if (destino != null && destino.figura() != null) return destino.figura();
        return porDefecto;
    }

    // ------------------------------------------------------------- ESQUEMA

    /**
     * La forma de la respuesta: una lista de "id|lectura".
     *
     * Antes era un objeto por recorte ({"id": "t3", "texto": "..."}), y medido
     * contra la API real en una captura de 40 recortes, la sintaxis de esos
     * objetos costaba mas tokens de SALIDA que lo transcripto --y la salida es
     * lo caro (5 veces la entrada) y lo lento (se genera token a token)--. El
     * id sigue viajando pegado a cada lectura, asi que un recorte salteado no
     * corre a los demas. Escrito como texto para que el orden sea siempre el
     * mismo: el servidor compila el esquema y lo cachea por su contenido.
     */
    private static final String ESQUEMA = """
            {
              "type": "object",
              "additionalProperties": false,
              "required": ["lecturas"],
              "properties": {
                "lecturas": {"type": "array", "items": {"type": "string",
                  "description": "id|lectura, por ejemplo t3|- titulo: String o s7|TRIANGULO"}}
              }
            }""";

    private static BetaJsonOutputFormat formato() {
        Map<String, Object> raiz;
        try {
            raiz = JSON.readValue(ESQUEMA, new TypeReference<LinkedHashMap<String, Object>>() { });
        } catch (IOException e) {
            throw new IllegalStateException("El esquema de lectura no es JSON valido", e);
        }
        BetaJsonOutputFormat.Schema.Builder esquema = BetaJsonOutputFormat.Schema.builder();
        raiz.forEach((clave, valor) -> esquema.putAdditionalProperty(clave, JsonValue.from(valor)));
        return BetaJsonOutputFormat.builder().schema(esquema.build()).build();
    }
}
