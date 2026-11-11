package com.uagrm.colabmodel.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.uagrm.colabmodel.modelcore.command.CommandType;
import com.uagrm.colabmodel.modelcore.command.Origin;
import com.uagrm.colabmodel.vision.DeteccionPropuesta;

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
 * @param lecturaDeImagen    solo en un turno IMAGEN (docs/DISENO-CHAT-IMAGENES.md, 5.4); null en los demas, y
 *                           entonces no sale en el JSON
 */
public record PropuestaDeIA(UUID diagramId, Origin origin, List<ComandoAceptado> aceptados,
                            List<ComandoRechazado> rechazados, List<String> descartadosPorTope,
                            List<String> advertencias, int propuestosPorElModelo, String notaDelModelo,
                            String modelo, UUID interaccionId, LecturaDeImagen lecturaDeImagen) {

    /** Una propuesta sin lectura de imagen: todas las de antes de esta funcion. */
    public PropuestaDeIA(UUID diagramId, Origin origin, List<ComandoAceptado> aceptados,
                         List<ComandoRechazado> rechazados, List<String> descartadosPorTope,
                         List<String> advertencias, int propuestosPorElModelo, String notaDelModelo,
                         String modelo, UUID interaccionId) {
        this(diagramId, origin, aceptados, rechazados, descartadosPorTope, advertencias, propuestosPorElModelo,
                notaDelModelo, modelo, interaccionId, null);
    }

    /**
     * Lo que leyo la IA de una imagen. `deteccion` es la misma que recibe el panel actual de /vision/deteccion
     * (sus cajas estan en la imagen de trabajo ya enderezada), asi que traductor.ts no cambia.
     *
     * @param dudasOmitidas cuantas dudas quedaron fuera del tope de 50
     * @param enriquecedoresAplicados nombres de las capas que corrieron y se aceptaron
     * @param enriquecedoresFallidos nombres de las capas que lanzaron o cuya salida se descarto
     * @param opciones lo que se pidio (giro y recorte), o ambos null
     */
    public record LecturaDeImagen(DeteccionPropuesta deteccion, List<Duda> dudas, int dudasOmitidas,
                                  List<String> enriquecedoresAplicados, List<String> enriquecedoresFallidos,
                                  OpcionesDeReproceso opciones) {
        public record OpcionesDeReproceso(Integer giroGrados, AiController.SolicitudDePropuesta.Recorte recorte) { }

        public enum TipoDeDuda {
            SIN_LECTURA_DE_TEXTO, CLASE_SIN_NOMBRE, RELACION_SIN_TIPO, TEXTO_VACIO,
            TEXTO_INCIERTO, CLASE_POCO_CLARA, RELACION_POCO_CLARA
        }

        public record Duda(TipoDeDuda tipo, String elementoRef, String recorteRef,
                           DeteccionPropuesta.RolDeTexto rol, String leido, double confianza, String motivo) { }
    }

    public record ComandoAceptado(CommandType tipo, JsonNode payload, String resumen) { }

    public record ComandoRechazado(int posicion, String tipoCrudo, String motivo) { }

    public boolean truncada() {
        return !descartadosPorTope.isEmpty();
    }

    public PropuestaDeIA conInteraccionId(UUID id) {
        return new PropuestaDeIA(diagramId, origin, aceptados, rechazados, descartadosPorTope,
                advertencias, propuestosPorElModelo, notaDelModelo, modelo, id, lecturaDeImagen);
    }
}
