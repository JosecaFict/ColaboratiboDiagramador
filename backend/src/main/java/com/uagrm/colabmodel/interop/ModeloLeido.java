package com.uagrm.colabmodel.interop;

import com.uagrm.colabmodel.modelcore.metamodel.RelationKind;

import java.util.List;
import java.util.Map;

/**
 * Lo que un lector sacó de un archivo, TAL CUAL, sin mirar el diagrama.
 *
 * Todos los lectores (JSON hoy; XMI, SQL y la foto despues) producen esta
 * misma forma. Asi la parte dificil --cruzar con lo que ya existe, sugerir
 * tipos y roles, revisar-- se escribe una sola vez y no una por formato.
 *
 * Las clases se referencian por `clave`: el identificador que traiga el
 * archivo (el id de un XMI, el nombre de una tabla), no un UUID nuestro.
 */
public record ModeloLeido(String formato, List<ClaseLeida> clases, List<RelacionLeida> relaciones,
                          List<String> advertencias, Map<String, Posicion> posiciones) {

    /** Sin diagrama: SQL, JSON, o un XMI que trae solo el modelo. */
    public ModeloLeido(String formato, List<ClaseLeida> clases, List<RelacionLeida> relaciones,
                       List<String> advertencias) {
        this(formato, clases, relaciones, advertencias, Map.of());
    }

    /**
     * Donde estaba la clase en el diagrama del archivo (el de EA, o UML DI),
     * por `clave`. Coordenadas de la herramienta de origen, sin normalizar:
     * la revision las acomoda debajo de lo que ya esta dibujado.
     */
    public record Posicion(double x, double y, double ancho, double alto) { }

    public record ClaseLeida(String clave, String nombre, String estereotipo, boolean abstracta,
                             List<AtributoLeido> atributos, List<MetodoLeido> metodos) { }

    /** @param tipoLeido como venia escrito en el archivo (`varchar(50)`, `real`), o null */
    public record AtributoLeido(String nombre, String tipoLeido, boolean pk, boolean requerido, boolean unico,
                                boolean multivaluado, boolean derivado, boolean claveParcial) { }

    /**
     * Una operacion UML, tal cual la trae el archivo (REGLA 6): no va a la
     * Entity ni al DDL, pero el Service generado la emite como stub.
     *
     * @param tipoRetorno "void" si la operacion no declara uno
     */
    public record MetodoLeido(String nombre, String tipoRetorno, List<ParametroLeido> parametros) { }

    public record ParametroLeido(String nombre, String tipo) { }

    public record RelacionLeida(String claveOrigen, String claveDestino, RelationKind tipo, String nombre,
                                String multiplicidadOrigen, String multiplicidadDestino,
                                String rolOrigen, String rolDestino, boolean identificadora) { }
}
