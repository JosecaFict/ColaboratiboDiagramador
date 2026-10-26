package com.uagrm.colabmodel.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.uagrm.colabmodel.modelcore.command.Lote;

import java.text.Normalizer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Ajustes al plan del modo Disenar que no dependen de la obediencia del
 * modelo local ni de ningun dominio en particular.
 *
 * Solo cambia lo que se decide por la estructura del plan o por lo que el
 * propio modelo declaro (por ejemplo `historica: true` en una clase). Lo que
 * es dudoso no se corrige aca: lo avisa {@link AuditorDePropuestaIA} y decide
 * la persona antes de aplicar.
 */
final class NormalizadorPlanUml {

    private static final Set<String> RELACIONES_CON_MULTIPLICIDAD = Set.of("ASSOCIATION", "AGGREGATION", "COMPOSITION");

    private NormalizadorPlanUml() { }

    static RespuestaIA normalizar(RespuestaIA respuesta) {
        ComandoPropuestoIA comando = buscarPlan(respuesta);
        if (comando == null || !comando.parametros().isObject()) return respuesta;

        ObjectNode plan = (ObjectNode) comando.parametros().deepCopy();
        Map<String, String> nombres = new HashMap<>();
        Set<String> historicas = new HashSet<>();
        int identidadesAgregadas = 0;
        int clavesNaturalesReemplazadas = 0;
        int unicidadesRetiradas = 0;

        for (JsonNode claseNodo : plan.path("classes")) {
            ObjectNode clase = (ObjectNode) claseNodo;
            String anterior = clase.path("name").asText();
            String limpio = identificador(anterior, true);
            nombres.put(anterior, limpio);
            clase.put("name", limpio);
            if (!clase.path("attributes").isArray()) clase.putArray("attributes");
            for (JsonNode atributoNodo : clase.path("attributes")) {
                ObjectNode atributo = (ObjectNode) atributoNodo;
                atributo.put("name", identificador(atributo.path("name").asText(), false));
                // La identidad es siempre un UUID tecnico (Regla 1). Una clave
                // natural sigue siendo unica: deja de ser PK pero no se pierde.
                if (atributo.path("pk").asBoolean(false) && !esIdTecnico(atributo.path("name").asText())) {
                    atributo.put("pk", false);
                    atributo.put("unique", true);
                    clavesNaturalesReemplazadas++;
                }
                if (!atributo.path("pk").asBoolean(false) && atributo.path("unique").asBoolean(false)
                        && esTextoRepetible(atributo.path("name").asText())) {
                    atributo.put("unique", false);
                    unicidadesRetiradas++;
                }
            }
            if (!tienePk(clase)) {
                ObjectNode id = ((ArrayNode) clase.path("attributes")).insertObject(0);
                id.put("name", "id" + limpio).put("dataType", "uuid")
                        .put("required", true).put("unique", true).put("pk", true);
                identidadesAgregadas++;
            }
            if (clase.path("historica").asBoolean(false)) historicas.add(limpio);
        }

        if (!plan.path("relations").isArray()) plan.putArray("relations");
        for (JsonNode relacionNodo : plan.path("relations")) {
            ObjectNode relacion = (ObjectNode) relacionNodo;
            relacion.put("source", nombres.getOrDefault(relacion.path("source").asText(),
                    identificador(relacion.path("source").asText(), true)));
            relacion.put("target", nombres.getOrDefault(relacion.path("target").asText(),
                    identificador(relacion.path("target").asText(), true)));
            // El nombre y los roles son del modelo: se limpian, nunca se reemplazan.
            limpiarOpcional(relacion, "name");
            limpiarOpcional(relacion, "sourceRole");
            limpiarOpcional(relacion, "targetRole");
        }

        // Participante de un hecho historico: cualquier clase que el hecho
        // referencia (el actor, el objeto). No lo es la parte de una
        // composicion cuyo todo es el propio hecho (Venta -> LineaDeVenta).
        Set<String> participantes = new HashSet<>();
        for (JsonNode r : plan.path("relations")) {
            String source = r.path("source").asText();
            String target = r.path("target").asText();
            boolean historicoEsElTodo = esTodoParte(r) && historicas.contains(source);
            if (historicas.contains(source) && !historicoEsElTodo) participantes.add(target);
            if (historicas.contains(target)) participantes.add(source);
        }

        int relacionesAjustadas = 0;
        int cardinalidadesHistoricasAjustadas = 0;
        for (JsonNode relacionNodo : plan.path("relations")) {
            ObjectNode relacion = (ObjectNode) relacionNodo;
            String source = relacion.path("source").asText();
            String target = relacion.path("target").asText();
            // Borrar el todo no puede arrastrar un historial ni algo que el
            // historial referencia: esa parte pasa a ser una asociacion.
            if (esTodoParte(relacion) && (historicas.contains(target) || participantes.contains(target))) {
                relacion.put("kind", "ASSOCIATION");
                relacionesAjustadas++;
            }
            if (!RELACIONES_CON_MULTIPLICIDAD.contains(relacion.path("kind").asText())) continue;
            if (esTodoParte(relacion) && historicas.contains(source)) continue;
            // Un participante acumula hechos en el tiempo: del lado del hecho
            // tiene que caber mas de uno.
            if (historicas.contains(source) && !historicas.contains(target)
                    && !permiteMuchos(relacion.path("sourceMultiplicity").asText())) {
                relacion.put("sourceMultiplicity", "0..*");
                cardinalidadesHistoricasAjustadas++;
            }
            if (historicas.contains(target) && !historicas.contains(source)
                    && !permiteMuchos(relacion.path("targetMultiplicity").asText())) {
                relacion.put("targetMultiplicity", "0..*");
                cardinalidadesHistoricasAjustadas++;
            }
        }

        int secundariosRecortados = recortarAtributosSecundarios(plan);

        ArrayNode audit = plan.path("audit") instanceof ArrayNode a ? a : plan.putArray("audit");
        if (relacionesAjustadas > 0) {
            for (int i = audit.size() - 1; i >= 0; i--) {
                String punto = normal(audit.get(i).asText());
                if (punto.contains("composicion") || punto.contains("agregacion")
                        || punto.contains("composition") || punto.contains("aggregation")) audit.remove(i);
            }
            audit.add("El backend sustituyo " + relacionesAjustadas
                    + " relacion(es) de ciclo de vida por ASSOCIATION para preservar entidades historicas.");
        }
        if (identidadesAgregadas > 0) audit.add("El backend agrego identidad UUID a "
                + identidadesAgregadas + " clase(s) que no tenian clave primaria.");
        if (clavesNaturalesReemplazadas > 0) audit.add("El backend reemplazo "
                + clavesNaturalesReemplazadas + " clave(s) natural(es) por UUID y las conservo como unique.");
        if (unicidadesRetiradas > 0) audit.add("El backend retiro unique de " + unicidadesRetiradas
                + " atributo(s) descriptivo(s) que pueden repetirse.");
        if (cardinalidadesHistoricasAjustadas > 0) audit.add("El backend amplio "
                + cardinalidadesHistoricasAjustadas + " multiplicidad(es) para conservar historial completo.");
        if (secundariosRecortados > 0) audit.add("El backend omitio " + secundariosRecortados
                + " atributo(s) opcional(es) para que el plan entre en el limite de "
                + Lote.TOPE_TEXTO_O_VOZ + " cambios.");
        while (audit.size() > 5) audit.remove(0);
        while (audit.size() < 2) audit.add("Las multiplicidades se conservan y se interpretan en ambos extremos.");

        return new RespuestaIA(List.of(new ComandoPropuestoIA(EsquemaPlanUml.NOMBRE, plan)),
                respuesta.notaDelModelo());
    }

    /** Las clases que el modelo declaro historicas, con el nombre ya normalizado. */
    static Set<String> clasesHistoricas(RespuestaIA respuesta) {
        ComandoPropuestoIA comando = buscarPlan(respuesta);
        if (comando == null) return Set.of();
        Set<String> historicas = new LinkedHashSet<>();
        for (JsonNode clase : comando.parametros().path("classes")) {
            if (clase.path("historica").asBoolean(false)) historicas.add(clase.path("name").asText());
        }
        return Set.copyOf(historicas);
    }

    private static ComandoPropuestoIA buscarPlan(RespuestaIA respuesta) {
        return respuesta.comandos().stream()
                .filter(c -> EsquemaPlanUml.NOMBRE.equals(c.tipo())).findFirst().orElse(null);
    }

    /** Composicion o agregacion: el origen es el todo y el destino la parte. */
    private static boolean esTodoParte(JsonNode relacion) {
        String tipo = relacion.path("kind").asText();
        return tipo.equals("COMPOSITION") || tipo.equals("AGGREGATION");
    }

    private static void limpiarOpcional(ObjectNode relacion, String campo) {
        if (!relacion.has(campo)) return;
        String valor = relacion.path(campo).asText("");
        if (valor.isBlank()) relacion.remove(campo);
        else relacion.put(campo, identificador(valor, false));
    }

    private static boolean tienePk(ObjectNode clase) {
        for (JsonNode a : clase.path("attributes")) if (a.path("pk").asBoolean(false)) return true;
        return false;
    }

    private static boolean esIdTecnico(String nombre) {
        if (nombre == null) return false;
        String n = nombre.trim();
        return n.equalsIgnoreCase("id") || n.matches("^id[A-Z].*")
                || n.matches("(?i)^id[_-].+");
    }

    /** Textos descriptivos que dos instancias pueden compartir sin error. */
    private static boolean esTextoRepetible(String nombre) {
        String n = normal(nombre).replace("_", "");
        return n.equals("nombre") || n.endsWith("nombrecompleto") || n.contains("titulo")
                || n.contains("descripcion") || n.contains("detalle") || n.contains("observacion")
                || n.equals("name") || n.endsWith("fullname") || n.contains("title")
                || n.contains("description") || n.contains("detail") || n.contains("note");
    }

    private static boolean permiteMuchos(String multiplicidad) {
        return multiplicidad != null && multiplicidad.contains("*");
    }

    /**
     * Si el plan no entra en el tope, se quitan primero los atributos
     * opcionales y despues los obligatorios, de la ultima clase hacia la
     * primera. La identidad y las relaciones no se tocan, y cada clase
     * conserva al menos un dato propio.
     */
    private static int recortarAtributosSecundarios(ObjectNode plan) {
        int eliminados = 0;
        for (boolean tambienObligatorios : new boolean[] {false, true}) {
            while (cantidadCambios(plan) > Lote.TOPE_TEXTO_O_VOZ) {
                if (!quitarUnAtributo(plan, tambienObligatorios)) break;
                eliminados++;
            }
        }
        return eliminados;
    }

    private static boolean quitarUnAtributo(ObjectNode plan, boolean tambienObligatorios) {
        for (int c = plan.path("classes").size() - 1; c >= 0; c--) {
            ArrayNode atributos = (ArrayNode) plan.path("classes").get(c).path("attributes");
            if (atributos.size() <= 2) continue; // identidad + al menos un dato propio
            for (int a = atributos.size() - 1; a >= 0; a--) {
                JsonNode atributo = atributos.get(a);
                if (atributo.path("pk").asBoolean(false)) continue;
                if (!tambienObligatorios && atributo.path("required").asBoolean(false)) continue;
                atributos.remove(a);
                return true;
            }
        }
        return false;
    }

    private static int cantidadCambios(ObjectNode plan) {
        int atributos = 0;
        for (JsonNode clase : plan.path("classes")) atributos += clase.path("attributes").size();
        return plan.path("classes").size() + atributos + plan.path("relations").size();
    }

    private static String identificador(String valor, boolean pascalCase) {
        String sinTildes = Normalizer.normalize(valor == null ? "" : valor, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        String[] partes = sinTildes.replaceAll("[^A-Za-z0-9]+", " ").trim().split("\\s+");
        StringBuilder resultado = new StringBuilder();
        for (int i = 0; i < partes.length; i++) {
            if (partes[i].isEmpty()) continue;
            String parte = partes[i];
            if (i == 0 && !pascalCase) resultado.append(Character.toLowerCase(parte.charAt(0))).append(parte.substring(1));
            else resultado.append(Character.toUpperCase(parte.charAt(0))).append(parte.substring(1));
        }
        String limpio = resultado.toString();
        if (limpio.isBlank()) return pascalCase ? "Elemento" : "valor";
        if (Character.isDigit(limpio.charAt(0))) return (pascalCase ? "Elemento" : "valor") + limpio;
        return limpio;
    }

    private static String normal(String valor) {
        return Normalizer.normalize(valor == null ? "" : valor, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }
}
