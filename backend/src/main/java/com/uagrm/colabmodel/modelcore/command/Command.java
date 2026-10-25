package com.uagrm.colabmodel.modelcore.command;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * CONTRATO NUCLEO DEL SISTEMA (ADR-002).
 *
 * Unica unidad de escritura del modelo. Mouse, voz, texto, vision e importacion
 * XMI producen exactamente esta estructura.
 *
 * Este contrato esta replicado, campo por campo, en:
 *   - frontend/src/contracts/command.ts   (TypeScript)
 *   - mobile/lib/domain/command.dart      (Dart, solo para referencia)
 *
 * Si cambia aqui, cambia en los tres. No se interpreta: se copia.
 *
 * @param opId      UUID generado por el CLIENTE. Da idempotencia ante reintentos.
 * @param diagramId Diagrama sobre el que opera.
 * @param baseSeq   Numero de secuencia que el cliente tenia al emitir.
 * @param type      Comando del catalogo cerrado.
 * @param origin    Quien lo produjo.
 * @param payload   Datos del comando. Su forma depende de `type`.
 */
public record Command(
        @NotNull UUID opId,
        @NotNull UUID diagramId,
        long baseSeq,
        @NotNull CommandType type,
        @NotNull Origin origin,
        @NotNull JsonNode payload
) {
}
