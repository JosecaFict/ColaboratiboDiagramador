package com.uagrm.colabmodel.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.uagrm.colabmodel.modelcore.command.CommandType;
import com.uagrm.colabmodel.modelcore.command.Origin;

import java.util.List;
import java.util.UUID;

/**
 * "Esto es lo que va a cambiar", ya validado, para revisar ANTES de tocar el
 * bus (misma doctrina que `PropuestaDeImportacion`, ADR-016).
 *
 * Nunca escribe nada: el que la pide (hoy un test, mañana la pantalla de
 * revisión de la IA) decide si confirma, y confirmar es mandar
 * {@link ServicioDeIA#comandoBatchDe} por el bus como cualquier otro cambio.
 *
 * @param aceptados         cambios validados, listos para revisar y editar antes de aplicar
 * @param rechazados        lo que el modelo propuso y no paso la validacion, con motivo
 * @param descartadosPorTope lo que era valido pero sobraba: el enunciado supero el limite
 *                          de un plan revisable y el sistema degrada en vez de fallar
 *                          entero (ADR-014/ADR-017): se avisa explicitamente que quedo afuera
 * @param advertencias       riesgos conceptuales no bloqueantes detectados antes de aplicar
 * @param propuestosPorElModelo cuantas llamadas a herramienta hizo el modelo en total
 */
public record PropuestaDeIA(UUID diagramId, Origin origin, List<ComandoAceptado> aceptados,
                            List<ComandoRechazado> rechazados, List<String> descartadosPorTope,
                            List<String> advertencias, int propuestosPorElModelo, String notaDelModelo,
                            String modelo, UUID interaccionId) {

    public record ComandoAceptado(CommandType tipo, JsonNode payload, String resumen) { }

    public record ComandoRechazado(int posicion, String tipoCrudo, String motivo) { }

    public boolean truncada() {
        return !descartadosPorTope.isEmpty();
    }

    public PropuestaDeIA conInteraccionId(UUID id) {
        return new PropuestaDeIA(diagramId, origin, aceptados, rechazados, descartadosPorTope,
                advertencias, propuestosPorElModelo, notaDelModelo, modelo, id);
    }
}
