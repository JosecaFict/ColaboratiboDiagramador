package com.uagrm.colabmodel.modelcore.command;

/**
 * Bus unico de escritura (ADR-002).
 *
 * Toda modificacion del metamodelo pasa por aqui, venga de donde venga.
 * La implementacion serializa por diagrama con un ejecutor de UN SOLO HILO
 * por diagramId: las operaciones de un mismo diagrama se ordenan entre si,
 * distintos diagramas avanzan en paralelo.
 */
public interface CommandBus {

    /**
     * Valida, secuencia, persiste y difunde un comando.
     *
     * @param command comando entrante
     * @param autor   la persona (permiso, log) y su sesion (locks)
     * @return la operacion aplicada con su numero de secuencia definitivo
     * @throws CommandRejectedException si no supera validacion, permisos o locks
     */
    AppliedOperation submit(Command command, Autor autor);
}
