package com.uagrm.colabmodel.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Convierte el plan completo del arquitecto en el catalogo atomico existente. */
final class ExpansorPlanUml {

    private static final JsonNodeFactory J = JsonNodeFactory.instance;
    private static final Set<String> TIPOS = Set.of("string", "text", "int", "long", "decimal", "boolean",
            "date", "datetime", "uuid");

    private ExpansorPlanUml() { }

    static RespuestaIA expandir(RespuestaIA respuesta) {
        ComandoPropuestoIA plan = respuesta.comandos().stream()
                .filter(c -> EsquemaPlanUml.NOMBRE.equals(c.tipo())).findFirst().orElse(null);
        if (plan == null) return respuesta;

        JsonNode p = plan.parametros();
        if (!p.path("classes").isArray() || p.path("classes").isEmpty()) {
            throw new IllegalArgumentException("El plan UML no contiene clases");
        }
        if (!p.path("relations").isArray() || p.path("relations").isEmpty()) {
            throw new IllegalArgumentException("El plan UML no contiene relaciones");
        }
        List<ComandoPropuestoIA> comandos = new ArrayList<>();
        for (JsonNode clase : p.path("classes")) {
            String nombre = textoRequerido(clase, "name");
            comandos.add(new ComandoPropuestoIA("CREATE_CLASS", J.objectNode().put("name", nombre)));
        }
        for (JsonNode clase : p.path("classes")) {
            String nombre = textoRequerido(clase, "name");
            if (!clase.path("attributes").isArray() || clase.path("attributes").isEmpty()) {
                throw new IllegalArgumentException("La clase \"" + nombre + "\" quedaria sin atributos");
            }
            for (JsonNode atributo : clase.path("attributes")) {
                ObjectNode a = J.objectNode();
                a.put("clase", nombre);
                a.put("name", textoRequerido(atributo, "name"));
                String tipo = textoRequerido(atributo, "dataType");
                if (!TIPOS.contains(tipo)) {
                    throw new IllegalArgumentException("El atributo \"" + a.path("name").asText()
                            + "\" usa un tipo no permitido: " + tipo);
                }
                a.put("dataType", tipo);
                a.put("required", atributo.path("required").asBoolean(false));
                a.put("unique", atributo.path("unique").asBoolean(false));
                a.put("pk", atributo.path("pk").asBoolean(false));
                comandos.add(new ComandoPropuestoIA("ADD_ATTRIBUTE", a));
            }
        }
        for (JsonNode relacion : p.path("relations")) {
            ObjectNode r = J.objectNode();
            r.put("claseOrigen", textoRequerido(relacion, "source"));
            r.put("claseDestino", textoRequerido(relacion, "target"));
            r.put("kind", textoRequerido(relacion, "kind"));
            r.put("sourceMultiplicity", textoRequerido(relacion, "sourceMultiplicity"));
            r.put("targetMultiplicity", textoRequerido(relacion, "targetMultiplicity"));
            copiarOpcional(relacion, r, "name");
            copiarOpcional(relacion, r, "sourceRole");
            copiarOpcional(relacion, r, "targetRole");
            comandos.add(new ComandoPropuestoIA("CREATE_RELATION", r));
        }

        List<String> auditoria = new ArrayList<>();
        for (JsonNode punto : p.path("audit")) if (punto.isTextual()) auditoria.add("- " + punto.asText());
        String nota = auditoria.isEmpty() ? respuesta.notaDelModelo()
                : "Auditoria de diseno:\n" + String.join("\n", auditoria);
        return new RespuestaIA(List.copyOf(comandos), nota);
    }

    private static String textoRequerido(JsonNode n, String campo) {
        String valor = n.path(campo).asText("").trim();
        if (valor.isEmpty()) throw new IllegalArgumentException("El plan UML no contiene \"" + campo + "\"");
        return valor;
    }

    private static void copiarOpcional(JsonNode origen, ObjectNode destino, String campo) {
        String valor = origen.path(campo).asText("").trim();
        if (!valor.isEmpty()) destino.put(campo, valor);
    }
}
