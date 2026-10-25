package com.uagrm.colabmodel.modelcore.command;

import java.util.UUID;

/**
 * Quien emite un comando: la PERSONA y la SESION de edicion desde la que lo hace.
 *
 * Hacen falta las dos cosas porque responden preguntas distintas:
 *   - `userId` decide el PERMISO (EC-11) y queda como autor en el log.
 *   - `sesion` decide la EXCLUSION MUTUA (EC-01).
 *
 * Con solo el `userId`, dos pestanas de la misma persona eran un unico
 * escritor para el servidor: las dos podian editar la misma clase a la vez, y
 * cerrar una soltaba los locks que la otra seguia usando. Una pestana es un
 * editor concurrente aunque la abra la misma persona.
 *
 * `sesion` es un identificador opaco: hoy es la sesion STOMP, pero un
 * importador XMI o el asistente de IA pueden traer la suya sin que el bus sepa
 * de donde vino.
 *
 * @param sesion null para quien no mantiene locks: solo puede escribir sobre
 *               entidades libres
 */
public record Autor(UUID userId, String sesion) { }
