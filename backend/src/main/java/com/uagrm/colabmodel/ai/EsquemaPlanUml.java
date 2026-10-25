package com.uagrm.colabmodel.ai;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/** Herramienta de alto nivel usada solo para disenar un modelo completo. */
final class EsquemaPlanUml {

    static final String NOMBRE = "PROPONER_MODELO_UML";
    private static final JsonNodeFactory J = JsonNodeFactory.instance;

    private EsquemaPlanUml() { }

    static HerramientaIA herramienta() {
        ObjectNode atributo = objeto();
        propiedades(atributo).set("name", texto("Nombre camelCase, sin tildes"));
        propiedades(atributo).set("dataType", enumerado("string", "text", "int", "long", "decimal",
                "boolean", "date", "datetime", "uuid"));
        propiedades(atributo).set("required", booleano());
        propiedades(atributo).set("unique", booleano());
        propiedades(atributo).set("pk", booleano());
        requeridos(atributo, "name", "dataType", "required", "unique", "pk");

        ObjectNode clase = objeto();
        propiedades(clase).set("name", texto("Nombre PascalCase singular, sin tildes"));
        ObjectNode atributos = arreglo(atributo);
        atributos.put("minItems", 1);
        propiedades(clase).set("attributes", atributos);
        requeridos(clase, "name", "attributes");

        ObjectNode relacion = objeto();
        propiedades(relacion).set("source", texto("Nombre exacto de la clase origen"));
        propiedades(relacion).set("target", texto("Nombre exacto de la clase destino"));
        propiedades(relacion).set("kind", enumerado("ASSOCIATION", "AGGREGATION", "COMPOSITION",
                "GENERALIZATION", "DEPENDENCY"));
        propiedades(relacion).set("name", texto("Nombre verbal opcional en camelCase"));
        propiedades(relacion).set("sourceMultiplicity", multiplicidad());
        propiedades(relacion).set("targetMultiplicity", multiplicidad());
        propiedades(relacion).set("sourceRole", texto("Rol opcional del origen"));
        propiedades(relacion).set("targetRole", texto("Rol opcional del destino"));
        requeridos(relacion, "source", "target", "kind", "sourceMultiplicity", "targetMultiplicity");

        ObjectNode raiz = objeto();
        ObjectNode clases = arreglo(clase);
        clases.put("minItems", 1);
        ObjectNode relaciones = arreglo(relacion);
        relaciones.put("minItems", 1);
        propiedades(raiz).set("classes", clases);
        propiedades(raiz).set("relations", relaciones);
        ObjectNode auditoria = arreglo(texto("Observacion breve de diseno"));
        auditoria.put("minItems", 2).put("maxItems", 5);
        propiedades(raiz).set("audit", auditoria);
        requeridos(raiz, "classes", "relations", "audit");

        return new HerramientaIA(NOMBRE,
                "Propone UN modelo UML conceptual completo en una sola llamada. Incluye todas las clases con al menos "
                        + "un atributo y todas las relaciones con multiplicidad en ambos extremos. Los atributos solo "
                        + "representan datos propios; nunca simulan asociaciones ni usan tipos de otras clases. "
                        + "Las entidades historicas (prestamos, reservas, eventos) se relacionan con ASSOCIATION: "
                        + "AGGREGATION y COMPOSITION no representan reglas de borrado o conservacion. "
                        + "Book [1] -- [0..*] Copy se expresa source=Book, target=Copy, "
                        + "sourceMultiplicity=1, targetMultiplicity=0..*.", raiz);
    }

    private static ObjectNode objeto() {
        ObjectNode n = J.objectNode();
        n.put("type", "object");
        n.put("additionalProperties", false);
        n.set("properties", J.objectNode());
        n.set("required", J.arrayNode());
        return n;
    }

    private static ObjectNode propiedades(ObjectNode objeto) { return (ObjectNode) objeto.path("properties"); }

    private static void requeridos(ObjectNode objeto, String... nombres) {
        ArrayNode a = (ArrayNode) objeto.path("required");
        List.of(nombres).forEach(a::add);
    }

    private static ObjectNode texto(String descripcion) {
        ObjectNode n = J.objectNode().put("type", "string");
        n.put("description", descripcion);
        return n;
    }

    private static ObjectNode booleano() { return J.objectNode().put("type", "boolean"); }

    private static ObjectNode enumerado(String... valores) {
        ObjectNode n = J.objectNode().put("type", "string");
        ArrayNode e = n.putArray("enum");
        List.of(valores).forEach(e::add);
        return n;
    }

    private static ObjectNode multiplicidad() { return enumerado("1", "0..1", "*", "0..*", "1..*"); }

    private static ObjectNode arreglo(ObjectNode items) {
        ObjectNode n = J.objectNode().put("type", "array");
        n.set("items", items);
        return n;
    }
}
