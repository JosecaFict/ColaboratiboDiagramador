package com.uagrm.colabmodel.ai;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.uagrm.colabmodel.modelcore.command.CommandType;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * El function-calling schema, DERIVADO del catalogo cerrado (ADR-014), no
 * mantenido a mano en una lista aparte.
 *
 * La garantia no es "alguien se acuerda de actualizar esto": es el
 * compilador. `paraTipo` es un switch SIN default sobre CommandType. Si
 * mañana se agrega un comando al catalogo, este archivo deja de compilar
 * hasta que alguien decida, explicitamente, si la IA lo puede llamar o no.
 * Es la misma tactica que ya usan `Lote.topePara` y
 * `CommandApplier.aplicarEfecto` para lo mismo con sus propios catalogos.
 *
 * Un comando puede estar en CommandType y NO ser una herramienta de la IA
 * (`Optional.empty()`): MOVE_ELEMENT/RESIZE_ELEMENT son geometria sin
 * semantica que decidir desde un prompt, SET_MODELING_DECISION se contesta
 * con evidencia que la IA no tiene, y BATCH es el propio contenedor.
 */
public final class EsquemaDeComandos {

    private EsquemaDeComandos() { }

    private static final JsonNodeFactory J = JsonNodeFactory.instance;

    public static List<HerramientaIA> herramientas() {
        List<HerramientaIA> lista = new ArrayList<>();
        for (CommandType tipo : CommandType.values()) {
            paraTipo(tipo).ifPresent(lista::add);
        }
        return lista;
    }

    /** true si la IA puede proponer este tipo (independiente de si esta bien formado). */
    static boolean esHerramienta(CommandType tipo) {
        return paraTipo(tipo).isPresent();
    }

    private static Optional<HerramientaIA> paraTipo(CommandType tipo) {
        return switch (tipo) {
            case CREATE_CLASS -> Optional.of(new HerramientaIA(tipo.name(),
                    "Crea una clase nueva, vacia, en el diagrama.",
                    objeto(List.of(
                            campo("name", texto("Nombre de la clase, en PascalCase"))),
                            List.of("name"))));

            case RENAME_CLASS -> Optional.of(new HerramientaIA(tipo.name(),
                    "Cambia el nombre de una clase que ya existe.",
                    objeto(List.of(
                            campo("clase", texto("Nombre actual de la clase")),
                            campo("name", texto("Nombre nuevo"))),
                            List.of("clase", "name"))));

            case DELETE_CLASS -> Optional.of(new HerramientaIA(tipo.name(),
                    "Borra una clase del diagrama, con sus atributos, metodos y relaciones.",
                    objeto(List.of(
                            campo("clase", texto("Nombre de la clase a borrar"))),
                            List.of("clase"))));

            case ADD_ATTRIBUTE -> Optional.of(new HerramientaIA(tipo.name(),
                    "Agrega UN atributo a una clase que ya existe.",
                    objeto(List.of(
                            campo("clase", texto("Clase donde va el atributo")),
                            campo("name", texto("Nombre del atributo")),
                            campo("dataType", enumerado("Tipo de dato. Si no se sabe, se infiere del nombre",
                                    "string", "text", "int", "long", "decimal", "boolean", "date", "datetime", "uuid")),
                            campo("required", booleano("Si el atributo es obligatorio")),
                            campo("unique", booleano("Si el atributo no se puede repetir")),
                            campo("pk", booleano("Si es (parte de) la clave natural del negocio"))),
                            List.of("clase", "name"))));

            case UPDATE_ATTRIBUTE -> Optional.of(new HerramientaIA(tipo.name(),
                    "Cambia el nombre, el tipo o las banderas de un atributo que ya existe.",
                    objeto(List.of(
                            campo("clase", texto("Clase duena del atributo")),
                            campo("atributo", texto("Nombre actual del atributo")),
                            campo("name", texto("Nombre nuevo, si cambia")),
                            campo("dataType", texto("Tipo nuevo, si cambia")),
                            campo("required", booleano("Si pasa a ser obligatorio")),
                            campo("unique", booleano("Si pasa a ser unico"))),
                            List.of("clase", "atributo"))));

            case REMOVE_ATTRIBUTE -> Optional.of(new HerramientaIA(tipo.name(),
                    "Quita un atributo de una clase.",
                    objeto(List.of(
                            campo("clase", texto("Clase duena del atributo")),
                            campo("atributo", texto("Nombre del atributo a quitar"))),
                            List.of("clase", "atributo"))));

            case ADD_METHOD -> Optional.of(new HerramientaIA(tipo.name(),
                    "Agrega UN metodo a una clase. El generador emite su firma, no su cuerpo.",
                    objeto(List.of(
                            campo("clase", texto("Clase donde va el metodo")),
                            campo("name", texto("Nombre del metodo")),
                            campo("returnType", texto("Tipo de retorno. Por defecto, void")),
                            campo("params", arregloDeParametros())),
                            List.of("clase", "name"))));

            case REMOVE_METHOD -> Optional.of(new HerramientaIA(tipo.name(),
                    "Quita un metodo de una clase.",
                    objeto(List.of(
                            campo("clase", texto("Clase duena del metodo")),
                            campo("metodo", texto("Nombre del metodo a quitar"))),
                            List.of("clase", "metodo"))));

            case CREATE_RELATION -> Optional.of(new HerramientaIA(tipo.name(),
                    "Crea una relacion UML entre dos clases que ya existen. La multiplicidad escrita junto a una clase "
                            + "indica cuantas instancias de ESA clase pueden asociarse con una instancia de la clase del "
                            + "otro extremo. Ejemplo Book [1] -- [0..*] Copy: claseOrigen=Book, claseDestino=Copy, "
                            + "sourceMultiplicity=1 y targetMultiplicity=0..*. Cada Copy pertenece a un Book y un Book "
                            + "puede tener muchas Copy.",
                    objeto(List.of(
                            campo("claseOrigen", texto("Clase de origen")),
                            campo("claseDestino", texto("Clase de destino")),
                            campo("kind", enumerado("Tipo de relacion",
                                    "ASSOCIATION", "AGGREGATION", "COMPOSITION", "GENERALIZATION", "DEPENDENCY")),
                            campo("name", texto("Nombre de la relacion, opcional")),
                            campo("sourceMultiplicity", texto("Cuantas instancias de claseOrigen se asocian con una "
                                    + "instancia de claseDestino. Valores: 1, 0..1, *, 0..*, 1..*")),
                            campo("targetMultiplicity", texto("Cuantas instancias de claseDestino se asocian con una "
                                    + "instancia de claseOrigen. Valores: 1, 0..1, *, 0..*, 1..*")),
                            campo("sourceRole", texto("Rol del lado origen, opcional")),
                            campo("targetRole", texto("Rol del lado destino, opcional"))),
                            List.of("claseOrigen", "claseDestino", "kind"))));

            case UPDATE_RELATION -> Optional.of(new HerramientaIA(tipo.name(),
                    "Cambia el tipo, el nombre o las multiplicidades de una relacion que ya existe "
                            + "entre dos clases.",
                    objeto(List.of(
                            campo("claseOrigen", texto("Clase de origen de la relacion")),
                            campo("claseDestino", texto("Clase de destino de la relacion")),
                            campo("kind", enumerado("Tipo nuevo, si cambia",
                                    "ASSOCIATION", "AGGREGATION", "COMPOSITION", "GENERALIZATION", "DEPENDENCY")),
                            campo("name", texto("Nombre nuevo, si cambia")),
                            campo("sourceMultiplicity", texto("Cuantas instancias de la clase origen se asocian con una "
                                    + "instancia de la clase destino: 1, 0..1, *, 0..* o 1..*")),
                            campo("targetMultiplicity", texto("Cuantas instancias de la clase destino se asocian con una "
                                    + "instancia de la clase origen: 1, 0..1, *, 0..* o 1..*"))),
                            List.of("claseOrigen", "claseDestino"))));

            case DELETE_RELATION -> Optional.of(new HerramientaIA(tipo.name(),
                    "Borra la relacion entre dos clases.",
                    objeto(List.of(
                            campo("claseOrigen", texto("Clase de origen")),
                            campo("claseDestino", texto("Clase de destino"))),
                            List.of("claseOrigen", "claseDestino"))));

            case SET_ER_FLAG -> Optional.of(new HerramientaIA(tipo.name(),
                    "Marca o quita una bandera ER sobre una clase (debil) o un atributo "
                            + "(multivaluado, derivado, clave parcial, parte de la clave).",
                    objeto(List.of(
                            campo("clase", texto("Clase a marcar, o duena del atributo a marcar")),
                            campo("atributo", texto("Atributo a marcar. Si se omite, la bandera es de la clase")),
                            campo("flag", enumerado("Bandera a marcar",
                                    "weak", "multivalued", "derived", "partialKey", "pk")),
                            campo("value", booleano("Valor de la bandera"))),
                            List.of("clase", "flag", "value"))));

            // Geometria pura (ADR-004 nivel 3): un prompt de texto no decide coordenadas.
            case MOVE_ELEMENT, RESIZE_ELEMENT -> Optional.empty();

            // Se contesta con evidencia que la IA no tiene (que construccion del
            // modelo genero la ambiguedad): es una decision del panel, no del prompt.
            case SET_MODELING_DECISION -> Optional.empty();

            // Confirmar la base es la firma de una persona sobre lo que reviso
            // (ADR-032). Si la IA pudiera, la confirmacion no diria nada.
            case CONFIRM_SCHEMA -> Optional.empty();

            // El contenedor no es una herramienta: lo arma ServicioDeIA con lo aceptado.
            case BATCH -> Optional.empty();
        };
    }

    // ------------------------------------------------------------ JSON SCHEMA

    private record Campo(String nombre, ObjectNode esquema) { }

    private static Campo campo(String nombre, ObjectNode esquema) {
        return new Campo(nombre, esquema);
    }

    private static ObjectNode texto(String descripcion) {
        ObjectNode n = J.objectNode();
        n.put("type", "string");
        n.put("description", descripcion);
        return n;
    }

    private static ObjectNode booleano(String descripcion) {
        ObjectNode n = J.objectNode();
        n.put("type", "boolean");
        n.put("description", descripcion);
        return n;
    }

    private static ObjectNode enumerado(String descripcion, String... valores) {
        ObjectNode n = texto(descripcion);
        ArrayNode arr = n.putArray("enum");
        for (String v : valores) arr.add(v);
        return n;
    }

    private static ObjectNode arregloDeParametros() {
        ObjectNode n = J.objectNode();
        n.put("type", "array");
        n.put("description", "Parametros del metodo, en orden");
        ObjectNode items = n.putObject("items");
        items.put("type", "object");
        ObjectNode props = items.putObject("properties");
        props.set("name", texto("Nombre del parametro"));
        props.set("type", texto("Tipo del parametro"));
        ArrayNode req = items.putArray("required");
        req.add("name");
        return n;
    }

    private static ObjectNode objeto(List<Campo> campos, List<String> requeridos) {
        ObjectNode n = J.objectNode();
        n.put("type", "object");
        ObjectNode props = n.putObject("properties");
        for (Campo c : campos) props.set(c.nombre(), c.esquema());
        ArrayNode req = n.putArray("required");
        requeridos.forEach(req::add);
        return n;
    }
}
