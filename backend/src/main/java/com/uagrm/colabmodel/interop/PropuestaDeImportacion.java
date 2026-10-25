package com.uagrm.colabmodel.interop;

import com.uagrm.colabmodel.modelcore.mapping.InferenciaDeAtributos;
import com.uagrm.colabmodel.modelcore.metamodel.RelationKind;

import java.util.List;
import java.util.UUID;

/**
 * Lo leido, ya cruzado con el diagrama y listo para que el usuario lo REVISE.
 *
 * No escribe nada. El cliente la muestra, el usuario la corrige, y recien
 * entonces se convierte en comandos y entra como un BATCH por el bus.
 *
 * @param formato     el lector que la produjo ("JSON", "XMI", "SQL")
 * @param fuente      el nombre del archivo
 * @param advertencias lo que el lector no pudo leer o tuvo que asumir (Regla 10)
 */
public record PropuestaDeImportacion(String formato, String fuente, List<ClasePropuesta> clases,
                                     List<RelacionPropuesta> relaciones, List<String> advertencias) {

    /**
     * @param clave       la del archivo: las relaciones la usan para referirse a esta clase
     * @param id          UUID reservado para crearla. Los ids los asigna el cliente
     *                    (ADR-007), asi que proponerlo de antemano no rompe nada
     * @param idExistente no nulo si el diagrama ya tiene una clase con ese nombre
     * @param posicion    donde estaba en el diagrama del archivo, o null si no vino dibujada
     */
    public record ClasePropuesta(String clave, UUID id, String nombre, String estereotipo, boolean abstracta,
                                 UUID idExistente, List<AtributoPropuesto> atributos,
                                 List<MetodoPropuesto> metodos, ModeloLeido.Posicion posicion) { }

    /**
     * @param tipoLeido  como venia en el archivo, para mostrarlo al lado del sugerido
     * @param tipo       el que se va a usar si el usuario no lo cambia
     * @param yaExiste   la clase existente ya tiene un atributo con ese nombre
     * @param sugerencia rol, confianza y motivo (InferenciaDeAtributos)
     */
    public record AtributoPropuesto(String nombre, String tipoLeido, String tipo, boolean pk, boolean requerido,
                                    boolean unico, boolean multivaluado, boolean derivado, boolean claveParcial,
                                    boolean yaExiste, InferenciaDeAtributos.Sugerencia sugerencia) { }

    /**
     * Un metodo del archivo, listo para su tilde en la revision (REGLA 6).
     *
     * @param yaExiste la clase existente ya tiene un metodo con ese nombre
     */
    public record MetodoPropuesto(String nombre, String tipoRetorno, List<ModeloLeido.ParametroLeido> parametros,
                                  boolean yaExiste) { }

    public record RelacionPropuesta(String claveOrigen, String claveDestino, RelationKind tipo, String nombre,
                                    String multiplicidadOrigen, String multiplicidadDestino,
                                    String rolOrigen, String rolDestino, boolean identificadora) { }
}
