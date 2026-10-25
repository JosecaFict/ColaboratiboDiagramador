package com.uagrm.colabmodel.modelcore.command;

/**
 * Catalogo CERRADO de comandos (ADR-014).
 *
 * Este enum cumple dos funciones a la vez:
 *   1. Es el contrato del bus de comandos.
 *   2. Es el function-calling schema del asistente de IA.
 *
 * IMPORTANTE: no existe ningun comando de generacion masiva de diagramas.
 * Esa ausencia es lo que hace que la restriccion "la IA no disena, solo edita"
 * sea estructural y no una promesa del prompt.
 */
public enum CommandType {
    CREATE_CLASS,
    RENAME_CLASS,
    DELETE_CLASS,

    ADD_ATTRIBUTE,
    UPDATE_ATTRIBUTE,
    REMOVE_ATTRIBUTE,

    ADD_METHOD,
    REMOVE_METHOD,

    CREATE_RELATION,
    UPDATE_RELATION,
    DELETE_RELATION,

    /** Marca banderas ER: clave, multivaluado, derivado, clave parcial, entidad debil. */
    SET_ER_FLAG,

    /**
     * Contesta uno de los 4 puntos de ambiguedad OO <-> Datos.
     *
     * Es una edicion ATOMICA sobre un elemento --justo lo que ADR-014
     * permite--, no un comando de generacion masiva: sigue sin existir
     * ninguna forma de pedirle a la IA que disene un diagrama entero.
     * Entra por el bus como cualquier otra escritura (ADR-002), asi que
     * se secuencia, se difunde y queda en el log con su autor.
     */
    SET_MODELING_DECISION,

    /**
     * Confirma el esquema fisico que el equipo reviso (ADR-032): el DDL de hoy,
     * identificado por su hash, queda como la base que se va a generar.
     *
     * Tampoco es generacion masiva: no crea ni cambia ningun elemento del
     * modelo. Va por el bus y no por REST porque es una escritura sobre estado
     * compartido --los demas tienen que ver que se confirmo, y quien-- y el
     * bus es el unico camino de escritura (ADR-002).
     */
    CONFIRM_SCHEMA,

    MOVE_ELEMENT,
    RESIZE_ELEMENT,

    /** Aterrizaje atomico de importacion XMI y de vision (foto de pizarra). */
    BATCH
}
