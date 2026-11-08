package com.uagrm.colabmodel.ai;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * Salida del modo RECOMENDAR. Es una herramienta para Claude y Gemini, y el
 * `format` (salida restringida por gramatica) para Ollama: el mismo esquema
 * sirve a los tres sin que ServicioDeRecomendaciones sepa cual contesto.
 */
final class EsquemaRecomendaciones {

    static final String NOMBRE = "PROPONER_RECOMENDACIONES";
    static final int MAXIMO = 8;
    static final List<String> GRAVEDADES = List.of("ALTA", "MEDIA", "BAJA");
    static final List<String> CATEGORIAS = List.of("REQUISITO", "RELACION", "MULTIPLICIDAD", "ATRIBUTO",
            "CLAVE", "NOMBRE", "OTRO");
    private static final JsonNodeFactory J = JsonNodeFactory.instance;

    private EsquemaRecomendaciones() { }

    static HerramientaIA herramienta() {
        ObjectNode recomendacion = objeto();
        // El orden importa en una salida generada token a token: el modelo
        // describe el problema antes de redactar la accion que lo corrige.
        propiedad(recomendacion, "titulo", texto("Titulo breve del problema"));
        propiedad(recomendacion, "gravedad", enumerado(GRAVEDADES));
        propiedad(recomendacion, "categoria", enumerado(CATEGORIAS));
        propiedad(recomendacion, "regla", texto("Regla del requisito que se incumple, citada brevemente; "
                + "cadena vacia si no corresponde a una regla del requisito"));
        ObjectNode elementos = J.objectNode().put("type", "array");
        elementos.set("items", texto("Nombre exacto de una clase o Clase.atributo del diagrama"));
        propiedad(recomendacion, "elementos", elementos);
        propiedad(recomendacion, "problema", texto("Por que es un problema, en una oracion"));
        propiedad(recomendacion, "accion", texto("Instruccion imperativa y autocontenida para corregirlo con "
                + "ediciones atomicas, con nombres, tipos y multiplicidades exactos"));
        requeridos(recomendacion, "titulo", "gravedad", "categoria", "regla", "elementos", "problema", "accion");

        ObjectNode raiz = objeto();
        propiedad(raiz, "resumen", texto("Una o dos oraciones sobre el estado general del diagrama"));
        ObjectNode lista = J.objectNode().put("type", "array").put("maxItems", MAXIMO);
        lista.set("items", recomendacion);
        propiedad(raiz, "recomendaciones", lista);
        requeridos(raiz, "resumen", "recomendaciones");

        return new HerramientaIA(NOMBRE, "Entrega las recomendaciones de mejora del diagrama actual en una sola "
                + "llamada, de la mas grave a la menos grave, como maximo " + MAXIMO + ".", raiz);
    }

    private static ObjectNode objeto() {
        ObjectNode n = J.objectNode();
        n.put("type", "object");
        n.put("additionalProperties", false);
        n.set("properties", J.objectNode());
        n.set("required", J.arrayNode());
        return n;
    }

    private static void propiedad(ObjectNode objeto, String nombre, ObjectNode esquema) {
        ((ObjectNode) objeto.path("properties")).set(nombre, esquema);
    }

    private static void requeridos(ObjectNode objeto, String... nombres) {
        ArrayNode a = (ArrayNode) objeto.path("required");
        List.of(nombres).forEach(a::add);
    }

    private static ObjectNode texto(String descripcion) {
        return J.objectNode().put("type", "string").put("description", descripcion);
    }

    private static ObjectNode enumerado(List<String> valores) {
        ObjectNode n = J.objectNode().put("type", "string");
        ArrayNode e = n.putArray("enum");
        valores.forEach(e::add);
        return n;
    }
}
