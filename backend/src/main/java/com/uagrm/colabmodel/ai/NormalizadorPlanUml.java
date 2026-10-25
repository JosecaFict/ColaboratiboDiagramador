package com.uagrm.colabmodel.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.uagrm.colabmodel.modelcore.command.Lote;

import java.text.Normalizer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Ajustes seguros que no deben depender de la obediencia del modelo local. */
final class NormalizadorPlanUml {

    private NormalizadorPlanUml() { }

    static RespuestaIA normalizar(RespuestaIA respuesta, String requisito) {
        ComandoPropuestoIA comando = respuesta.comandos().stream()
                .filter(c -> EsquemaPlanUml.NOMBRE.equals(c.tipo())).findFirst().orElse(null);
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
            for (JsonNode atributoNodo : clase.path("attributes")) {
                ObjectNode atributo = (ObjectNode) atributoNodo;
                atributo.put("name", identificador(atributo.path("name").asText(), false));
                if (atributo.path("pk").asBoolean(false) && !esIdTecnico(atributo.path("name").asText())) {
                    atributo.put("pk", false);
                    atributo.put("unique", esCandidatoUnico(atributo.path("name").asText()));
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
            if (esHistorica(clase)) historicas.add(limpio);
        }

        int coberturasAgregadas = completarCoberturaExplicita(plan, requisito);

        for (JsonNode relacionNodo : plan.path("relations")) {
            ObjectNode relacion = (ObjectNode) relacionNodo;
            relacion.put("source", nombres.getOrDefault(relacion.path("source").asText(),
                    identificador(relacion.path("source").asText(), true)));
            relacion.put("target", nombres.getOrDefault(relacion.path("target").asText(),
                    identificador(relacion.path("target").asText(), true)));
            if (relacion.has("name")) relacion.put("name", identificador(relacion.path("name").asText(), false));
            if (relacion.has("sourceRole")) relacion.put("sourceRole",
                    identificador(relacion.path("sourceRole").asText(), false));
            if (relacion.has("targetRole")) relacion.put("targetRole",
                    identificador(relacion.path("targetRole").asText(), false));
            relacion.put("name", nombreDeRelacion(relacion.path("source").asText(),
                    relacion.path("target").asText()));
        }

        // Tambien se protege a cualquier clase directamente conectada a un
        // evento historico: borrar una Copia no debe arrastrar sus Prestamos.
        Set<String> protegidas = new HashSet<>(historicas);
        for (JsonNode r : plan.path("relations")) {
            String source = r.path("source").asText();
            String target = r.path("target").asText();
            if (historicas.contains(source)) protegidas.add(target);
            if (historicas.contains(target)) protegidas.add(source);
        }

        int relacionesAjustadas = 0;
        int cardinalidadesHistoricasAjustadas = 0;
        for (JsonNode relacionNodo : plan.path("relations")) {
            ObjectNode relacion = (ObjectNode) relacionNodo;
            String tipo = relacion.path("kind").asText();
            boolean cicloDeVida = tipo.equals("COMPOSITION") || tipo.equals("AGGREGATION");
            if (cicloDeVida && (protegidas.contains(relacion.path("source").asText())
                    || protegidas.contains(relacion.path("target").asText()))) {
                relacion.put("kind", "ASSOCIATION");
                relacionesAjustadas++;
            }
            String source = relacion.path("source").asText();
            String target = relacion.path("target").asText();
            if (historicas.contains(source) && !permiteMuchos(relacion.path("sourceMultiplicity").asText())) {
                relacion.put("sourceMultiplicity", "0..*");
                cardinalidadesHistoricasAjustadas++;
            }
            if (historicas.contains(target) && !permiteMuchos(relacion.path("targetMultiplicity").asText())) {
                relacion.put("targetMultiplicity", "0..*");
                cardinalidadesHistoricasAjustadas++;
            }
        }

        int coautoriasAjustadas = corregirCoautoriaExplicita(plan, requisito);
        int decisionesDeObligatoriedad = ajustarObligatoriedadExplicita(plan, requisito);
        boolean reglaPrestamoActivo = detectaReglaPrestamoActivo(plan, requisito);
        int secundariosRecortados = recortarAtributosSecundarios(plan);

        if (plan.path("audit") instanceof ArrayNode audit) {
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
                    + clavesNaturalesReemplazadas + " clave(s) natural(es) por UUID y conservo unique cuando corresponde.");
            if (unicidadesRetiradas > 0) audit.add("El backend retiro unique de " + unicidadesRetiradas
                    + " atributo(s) descriptivo(s) que pueden repetirse.");
            if (cardinalidadesHistoricasAjustadas > 0) audit.add("El backend amplio "
                    + cardinalidadesHistoricasAjustadas + " multiplicidad(es) para conservar historial completo.");
            if (coberturasAgregadas > 0) audit.add("El backend completo " + coberturasAgregadas
                    + " atributo(s) mencionado(s) explicitamente en el requisito.");
            if (coautoriasAjustadas > 0) audit.add("El backend ajusto la relacion de autoria para permitir coautoria.");
            if (decisionesDeObligatoriedad > 0) audit.add("El backend ajusto la obligatoriedad de ISBN segun el requisito explicito.");
            if (reglaPrestamoActivo) audit.add("Regla de negocio detectada: un Ejemplar no puede tener dos Prestamos "
                    + "activos simultaneamente; se considera activo mientras fechaDevolucion sea nula.");
            if (secundariosRecortados > 0) audit.add("El backend omitio " + secundariosRecortados
                    + " atributo(s) secundario(s) para conservar todas las relaciones dentro del limite seguro.");
            while (audit.size() > 5) audit.remove(0);
            while (audit.size() < 2) audit.add("Las multiplicidades se conservan y se interpretan en ambos extremos.");
        }

        return new RespuestaIA(List.of(new ComandoPropuestoIA(EsquemaPlanUml.NOMBRE, plan)),
                respuesta.notaDelModelo());
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

    private static boolean esCandidatoUnico(String nombre) {
        String n = normal(nombre).replace("_", "");
        return n.contains("isbn") || n.contains("codigo") || n.contains("email")
                || n.contains("correo") || n.contains("matricula") || n.contains("identificacion")
                || n.contains("documento") || n.contains("numero") || n.contains("slug")
                || n.contains("serie") || n.contains("placa");
    }

    private static boolean esTextoRepetible(String nombre) {
        String n = normal(nombre).replace("_", "");
        return n.equals("nombre") || n.endsWith("nombrecompleto") || n.contains("titulo")
                || n.contains("descripcion") || n.contains("detalle") || n.contains("observacion");
    }

    private static String nombreDeRelacion(String source, String target) {
        String clave = normal(source) + "->" + normal(target);
        return switch (clave) {
            case "obra->autor" -> "tieneAutores";
            case "obra->ejemplar", "obra->copia", "obra->copiafisica" -> "tieneEjemplares";
            case "usuario->prestamo", "usuario->prestamohistorico" -> "realizaPrestamos";
            case "ejemplar->prestamo", "copia->prestamo", "copiafisica->prestamo",
                 "copiafisica->prestamohistorico" -> "registraPrestamos";
            case "usuario->reserva" -> "solicitaReservas";
            case "obra->reserva" -> "recibeReservas";
            default -> "relacionaCon" + pluralPascal(target);
        };
    }

    private static String pluralPascal(String nombre) {
        String limpio = identificador(nombre, true);
        String n = normal(limpio);
        if (n.equals("autor")) return "Autores";
        if (n.endsWith("s")) return limpio;
        if (n.endsWith("z")) return limpio.substring(0, limpio.length() - 1) + "ces";
        return limpio + (n.endsWith("r") || n.endsWith("l") || n.endsWith("n") ? "es" : "s");
    }

    private static int ajustarObligatoriedadExplicita(ObjectNode plan, String requisito) {
        String pedido = normal(requisito);
        boolean obligatorio = pedido.contains("isbn obligatorio") || pedido.contains("isbn requerido")
                || pedido.matches("(?s).*toda(?:s)? (?:las )?obra(?:s)?[^.]*isbn.*");
        boolean opcional = pedido.contains("isbn opcional") || pedido.contains("sin isbn")
                || pedido.contains("no tenga isbn") || pedido.contains("no tienen isbn");
        if (!obligatorio && !opcional) return 0;
        for (JsonNode clase : plan.path("classes")) {
            for (JsonNode atributo : clase.path("attributes")) {
                if (normal(atributo.path("name").asText()).equals("isbn")) {
                    ((ObjectNode) atributo).put("required", obligatorio);
                    return 1;
                }
            }
        }
        return 0;
    }

    private static boolean detectaReglaPrestamoActivo(ObjectNode plan, String requisito) {
        String pedido = normal(requisito);
        boolean solicitada = pedido.contains("dos prestamos activos") || pedido.contains("prestamos activos simultaneos")
                || pedido.contains("prestamo activo simultaneo");
        if (!solicitada) return false;
        boolean devolucion = false;
        for (JsonNode clase : plan.path("classes")) {
            if (!normal(clase.path("name").asText()).contains("prestamo")) continue;
            for (JsonNode a : clase.path("attributes")) {
                if (normal(a.path("name").asText()).equals("fechadevolucion")) devolucion = true;
            }
        }
        return devolucion;
    }

    private static boolean permiteMuchos(String multiplicidad) {
        return multiplicidad != null && multiplicidad.contains("*");
    }

    private static int completarCoberturaExplicita(ObjectNode plan, String requisito) {
        String pedido = normal(requisito);
        int agregados = 0;
        if (pedido.contains("devolucion")) agregados += agregarAClase(plan, "prestamo", "fechaDevolucion", "datetime", false);
        if (pedido.contains("vencimiento")) agregados += agregarAClase(plan, "prestamo", "fechaVencimiento", "datetime", true);
        return agregados;
    }

    private static int agregarAClase(ObjectNode plan, String claseBuscada, String nombre,
                                     String tipo, boolean requerido) {
        for (JsonNode claseNodo : plan.path("classes")) {
            ObjectNode clase = (ObjectNode) claseNodo;
            if (!normal(clase.path("name").asText()).contains(claseBuscada)) continue;
            for (JsonNode a : clase.path("attributes")) {
                if (normal(a.path("name").asText()).equals(normal(nombre))) {
                    if (normal(nombre).equals("fechadevolucion")) ((ObjectNode) a).put("required", false);
                    return 0;
                }
            }
            ((ArrayNode) clase.path("attributes")).addObject().put("name", nombre).put("dataType", tipo)
                    .put("required", requerido).put("unique", false).put("pk", false);
            return 1;
        }
        return 0;
    }

    private static int corregirCoautoriaExplicita(ObjectNode plan, String requisito) {
        String pedido = normal(requisito);
        boolean solicitada = pedido.contains("mas de un autor") || pedido.contains("varios autores")
                || pedido.contains("multiples autores") || pedido.contains("coautoria");
        if (!solicitada) return 0;
        for (JsonNode relacionNodo : plan.path("relations")) {
            ObjectNode r = (ObjectNode) relacionNodo;
            String source = normal(r.path("source").asText());
            String target = normal(r.path("target").asText());
            if (source.contains("obra") && target.contains("autor")) {
                r.put("sourceMultiplicity", "0..*");
                r.put("targetMultiplicity", "1..*");
                r.put("kind", "ASSOCIATION");
                return 1;
            }
            if (source.contains("autor") && target.contains("obra")) {
                r.put("sourceMultiplicity", "1..*");
                r.put("targetMultiplicity", "0..*");
                r.put("kind", "ASSOCIATION");
                return 1;
            }
        }
        return 0;
    }

    private static int recortarAtributosSecundarios(ObjectNode plan) {
        int eliminados = 0;
        while (cantidadCambios(plan) > Lote.TOPE_TEXTO_O_VOZ) {
            boolean eliminado = false;
            for (int c = plan.path("classes").size() - 1; c >= 0 && !eliminado; c--) {
                ArrayNode atributos = (ArrayNode) plan.path("classes").get(c).path("attributes");
                if (atributos.size() <= 2) continue; // identidad + al menos un dato propio
                for (int a = atributos.size() - 1; a >= 0; a--) {
                    JsonNode atributo = atributos.get(a);
                    String nombre = normal(atributo.path("name").asText());
                    boolean protegido = atributo.path("pk").asBoolean(false)
                            || nombre.equals("fechadevolucion") || nombre.equals("fechavencimiento");
                    if (!protegido) {
                        atributos.remove(a);
                        eliminados++;
                        eliminado = true;
                        break;
                    }
                }
            }
            if (!eliminado) break;
        }
        return eliminados;
    }

    private static int cantidadCambios(ObjectNode plan) {
        int atributos = 0;
        for (JsonNode clase : plan.path("classes")) atributos += clase.path("attributes").size();
        return plan.path("classes").size() + atributos + plan.path("relations").size();
    }

    private static boolean esHistorica(ObjectNode clase) {
        String nombre = normal(clase.path("name").asText());
        if (nombre.contains("prestamo") || nombre.contains("reserva") || nombre.contains("historial")
                || nombre.contains("transaccion") || nombre.contains("movimiento") || nombre.contains("evento")) {
            return true;
        }
        for (JsonNode a : clase.path("attributes")) {
            String atributo = normal(a.path("name").asText());
            if (atributo.contains("fechadevolucion") || atributo.contains("fechavencimiento")
                    || atributo.contains("fechacierre") || atributo.equals("inicio") || atributo.equals("fin")) {
                return true;
            }
        }
        return false;
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

