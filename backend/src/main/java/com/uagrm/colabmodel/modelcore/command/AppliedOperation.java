package com.uagrm.colabmodel.modelcore.command;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Operacion ya aceptada, secuenciada y persistida.
 *
 * Es lo que el servidor difunde a /topic/diagram/{id}/ops.
 * El `seq` lo asigna el secuenciador (ADR-005) y define el orden global.
 * Si un cliente detecta un hueco en la secuencia, pide snapshot y resincroniza.
 */
public record AppliedOperation(
        UUID opId,
        long seq,
        CommandType type,
        Origin origin,
        JsonNode payload,
        UUID userId,
        OffsetDateTime ts
) {
}
