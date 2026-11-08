package com.uagrm.colabmodel.ai;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Respuesta a UNA regla: una pregunta pequena y concreta, que es donde un
 * modelo local de 8-9B rinde. El orden de los campos hace que el modelo
 * cite la evidencia y la explique antes de dar el veredicto.
 */
final class EsquemaVerificacionRegla {

    static final String NOMBRE = "VERIFICAR_REGLA";
    private static final JsonNodeFactory J = JsonNodeFactory.instance;

    private EsquemaVerificacionRegla() { }

    static HerramientaIA herramienta() {
        ObjectNode raiz = J.objectNode().put("type", "object").put("additionalProperties", false);
        ObjectNode p = raiz.putObject("properties");
        ObjectNode evidencia = p.putObject("evidencia").put("type", "array");
        evidencia.putObject("items").put("type", "string")
                .put("description", "Clase, Clase.atributo o ClaseA-ClaseB, solo si aparece en el diagrama");
        p.putObject("explicacion").put("type", "string")
                .put("description", "Como se cumple o por que no, en una o dos oraciones");
        p.putObject("cumple").put("type", "boolean");
        p.putObject("accion").put("type", "string")
                .put("description", "Si no cumple: instruccion imperativa y autocontenida para corregirlo. Si cumple: vacia");
        raiz.putArray("required").add("evidencia").add("explicacion").add("cumple").add("accion");
        return new HerramientaIA(NOMBRE, "Indica si el diagrama cumple una regla del requisito.", raiz);
    }
}
