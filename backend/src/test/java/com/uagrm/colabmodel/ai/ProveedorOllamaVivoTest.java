package com.uagrm.colabmodel.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La contraparte local de ProveedorGeminiVivoTest: la unica pregunta que
 * ninguna simulacion contesta es si el modelo que efectivamente esta bajado
 * en esta maquina sabe llamar herramientas con NUESTRO esquema.
 *
 * Apagada salvo que se pida a mano, igual que la de Gemini, porque depende de
 * un Ollama corriendo y de un modelo bajado:
 *
 *     set OLLAMA_VIVO=1
 *     set OLLAMA_MODEL=qwen2.5:7b
 *     mvn -Dtest=ProveedorOllamaVivoTest test
 *
 * No alcanza con "contesto algo": un modelo chico contesta siempre. Lo que se
 * mide aca es la tasa de comandos que sobreviven al catalogo (ADR-014), que
 * es lo que decide si el asistente de edicion local sirve o cansa.
 */
class ProveedorOllamaVivoTest {

    /**
     * @param esperados los tipos que una respuesta CORRECTA tiene que incluir.
     *                  Un comando bien formado puede ser igual la respuesta
     *                  equivocada (un "tiene muchas" contestado con
     *                  ADD_ATTRIBUTE en vez de CREATE_RELATION), y eso ningun
     *                  chequeo de esquema puede ver.
     */
    private record Caso(String prompt, List<SolicitudIA.ClaseEnContexto> contexto,
                        Set<String> esperados) { }

    private static final List<Caso> CASOS = List.of(
            new Caso("Crea una clase Factura con numero, fecha y total.", List.of(),
                    Set.of("CREATE_CLASS", "ADD_ATTRIBUTE")),
            new Caso("Agrega una clase Libro con titulo e isbn, y relacionala con Autor.",
                    List.of(new SolicitudIA.ClaseEnContexto("Autor", List.of("nombre"))),
                    Set.of("CREATE_CLASS", "ADD_ATTRIBUTE", "CREATE_RELATION")),
            new Caso("Al cliente le falta el email, agregaselo como obligatorio y unico.",
                    List.of(new SolicitudIA.ClaseEnContexto("Cliente", List.of("nombre", "telefono"))),
                    Set.of("ADD_ATTRIBUTE")),
            new Caso("Renombra Producto a Articulo.",
                    List.of(new SolicitudIA.ClaseEnContexto("Producto", List.of("nombre", "precio"))),
                    Set.of("RENAME_CLASS")),
            new Caso("Un pedido tiene muchas lineas de pedido.",
                    List.of(new SolicitudIA.ClaseEnContexto("Pedido", List.of("fecha")),
                            new SolicitudIA.ClaseEnContexto("LineaPedido", List.of("cantidad"))),
                    Set.of("CREATE_RELATION")));

    @Test
    @EnabledIfEnvironmentVariable(named = "OLLAMA_VIVO", matches = ".+")
    @DisplayName("El modelo local traduce pedidos en espanol a comandos del catalogo")
    void contra_el_ollama_local() {
        String modelo = System.getenv().getOrDefault("OLLAMA_MODEL", "llama3.1");
        String baseUrl = System.getenv().getOrDefault("OLLAMA_URL", "http://localhost:11434");
        ProveedorOllama proveedor = new ProveedorOllama(baseUrl, 4096, 0.1, 120_000,
                new ObjectMapper(), new CatalogoModelosOllama(modelo));

        Set<String> catalogo = EsquemaDeComandos.herramientas().stream()
                .map(HerramientaIA::nombre).collect(Collectors.toSet());

        int propuestos = 0;
        int casosResueltos = 0;
        long msTotal = 0;
        int delCatalogo = 0;
        int bienFormados = 0;
        List<String> fallas = new ArrayList<>();

        System.out.println("=== Modelo local: " + modelo + " ===");
        for (Caso caso : CASOS) {
            long t0 = System.currentTimeMillis();
            RespuestaIA r = proveedor.generar(new SolicitudIA(caso.prompt(), caso.contexto(),
                    EsquemaDeComandos.herramientas()));
            long ms = System.currentTimeMillis() - t0;
            msTotal += ms;

            System.out.println("\n> " + caso.prompt() + "   [" + ms + " ms]");
            if (r.comandos().isEmpty()) {
                System.out.println("  (ningun comando; nota: " + r.notaDelModelo() + ")");
                fallas.add(caso.prompt() + ": no propuso ningun comando");
            }
            Set<String> yaExisten = caso.contexto().stream()
                    .map(x -> x.nombre().toLowerCase()).collect(Collectors.toCollection(java.util.HashSet::new));
            for (ComandoPropuestoIA c : r.comandos()) {
                propuestos++;
                boolean conocido = catalogo.contains(c.tipo());
                if (conocido) {
                    delCatalogo++;
                } else {
                    fallas.add("\"" + c.tipo() + "\" no existe en el catalogo");
                }
                String problema = conocido ? problema(c, yaExisten) : "tipo fuera del catalogo";
                if (problema == null) {
                    bienFormados++;
                } else if (conocido) {
                    fallas.add(c.tipo() + ": " + problema);
                }
                System.out.println("  " + (problema == null ? "OK  " : "MAL ")
                        + c.tipo() + " " + c.parametros()
                        + (problema == null ? "" : "   <- " + problema));
            }

            Set<String> tipos = r.comandos().stream()
                    .map(ComandoPropuestoIA::tipo).collect(Collectors.toSet());
            if (tipos.containsAll(caso.esperados())) {
                casosResueltos++;
            } else {
                Set<String> faltan = new java.util.TreeSet<>(caso.esperados());
                faltan.removeAll(tipos);
                System.out.println("  !! bien formado pero incompleto: no propuso " + faltan);
            }
        }

        System.out.println("\n=== Resultado con " + modelo + " ===");
        System.out.println("Comandos propuestos : " + propuestos);
        System.out.println("Del catalogo        : " + delCatalogo + porcentaje(delCatalogo, propuestos));
        System.out.println("Bien formados       : " + bienFormados + porcentaje(bienFormados, propuestos));
        System.out.println("Casos resueltos     : " + casosResueltos + "/" + CASOS.size()
                + porcentaje(casosResueltos, CASOS.size()));
        System.out.println("Segundos por pedido : " + (msTotal / CASOS.size() / 1000));
        if (!fallas.isEmpty()) {
            System.out.println("Rechazos:");
            fallas.forEach(f -> System.out.println("  - " + f));
        }

        // El piso: sirve como asistente si la mayoria de lo que propone se
        // puede aplicar. Por debajo de eso el usuario revisa mas de lo que gana.
        assertThat(propuestos).as("el modelo no propuso ni un comando").isPositive();
        assertThat(bienFormados * 2).as("menos de la mitad de los comandos son aplicables")
                .isGreaterThanOrEqualTo(propuestos);
    }

    /**
     * El primer motivo por el que ResolutorDeReferencias rechazaria este
     * comando, o null si es aplicable.
     *
     * No alcanza con mirar los campos `required`: un modelo chico llama a la
     * herramienta EQUIVOCADA con los campos de otra (CREATE_CLASS con "clase"
     * y "dataType" es un ADD_ATTRIBUTE disfrazado) y pasaria como valido
     * porque CREATE_CLASS solo exige "name". Los campos de mas son la señal.
     */
    private String problema(ComandoPropuestoIA c, Set<String> yaExisten) {
        HerramientaIA h = EsquemaDeComandos.herramientas().stream()
                .filter(x -> x.nombre().equals(c.tipo())).findFirst().orElseThrow();

        for (JsonNode requerido : h.parametros().path("required")) {
            JsonNode valor = c.parametros().get(requerido.asText());
            if (valor == null || valor.isNull() || (valor.isTextual() && valor.asText().isBlank())) {
                return "falta el campo obligatorio \"" + requerido.asText() + "\"";
            }
        }

        JsonNode propiedades = h.parametros().path("properties");
        for (java.util.Iterator<String> it = c.parametros().fieldNames(); it.hasNext(); ) {
            String campo = it.next();
            if (!propiedades.has(campo)) {
                return "el campo \"" + campo + "\" no existe en " + c.tipo() + " (llamo a la herramienta equivocada)";
            }
        }

        // Mismo rechazo que ResolutorDeReferencias.crearClase.
        if ("CREATE_CLASS".equals(c.tipo())) {
            String nombre = c.parametros().path("name").asText("").toLowerCase();
            if (!yaExisten.add(nombre)) {
                return "la clase \"" + nombre + "\" ya existe en el diagrama";
            }
        }
        return null;
    }

    private String porcentaje(int parte, int total) {
        return total == 0 ? "" : String.format(" (%d%%)", Math.round(100f * parte / total));
    }
}
