package com.uagrm.colabmodel.modelcore.command;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * El contenido de un comando BATCH: varios cambios que entran TODOS o NINGUNO.
 *
 * Forma del payload:
 * <pre>
 * { "descripcion": "Importado de biblioteca.json",
 *   "comandos": [ { "type": "CREATE_CLASS", "payload": { ... } }, ... ] }
 * </pre>
 *
 * Existe porque importar un modelo, leer una foto o una frase de la IA producen
 * muchos cambios de una vez. Mandados uno por uno, un fallo en el cambio 14 de
 * 30 deja el diagrama a medias, y los demas usuarios ven aparecer piezas
 * sueltas. Como lote se aplican en UNA transaccion y ocupan UN seq.
 *
 * No es un comando de generacion masiva (ADR-014): cada cambio de adentro es un
 * comando atomico del mismo catalogo cerrado, y el tope depende de quien lo
 * produce.
 */
public record Lote(String descripcion, List<Command> comandos) {

    /**
     * Un copiloto puede proponer un modelo pequeno completo en una sola
     * revision. El catalogo cerrado, la validacion y la confirmacion humana
     * siguen siendo la barrera de seguridad; ocho obligaba a repetir prompts.
     */
    public static final int TOPE_TEXTO_O_VOZ = 32;

    /** Una importacion trae un modelo entero, pero con limite: es una transaccion. */
    public static final int TOPE_IMPORTACION = 500;

    public static int topePara(Origin origen) {
        return switch (origen) {
            case TEXT, VOICE -> TOPE_TEXTO_O_VOZ;
            case MOUSE, IMAGE, XMI_IMPORT -> TOPE_IMPORTACION;
        };
    }

    /**
     * Lee y valida la forma del lote. Cada cambio hereda el diagrama, el
     * origen y el opId del BATCH: el lote es UNA operacion, no N.
     *
     * @throws CommandRejectedException si la forma no es valida
     */
    public static Lote de(Command batch) {
        JsonNode p = batch.payload();
        JsonNode lista = p.path("comandos");
        if (!lista.isArray() || lista.isEmpty()) {
            throw rechazo(batch, "Un BATCH necesita una lista \"comandos\" con al menos un cambio");
        }
        int tope = topePara(batch.origin());
        if (lista.size() > tope) {
            throw rechazo(batch, "El lote trae " + lista.size() + " cambios y el tope para "
                    + batch.origin() + " es " + tope
                    + (tope == TOPE_TEXTO_O_VOZ ? " (la propuesta debe dividirse en planes revisables)" : ""));
        }

        List<Command> comandos = new ArrayList<>();
        int i = 1;
        for (JsonNode c : lista) {
            CommandType tipo;
            try {
                tipo = CommandType.valueOf(c.path("type").asText());
            } catch (IllegalArgumentException e) {
                throw rechazo(batch, "Cambio " + i + ": tipo de comando desconocido \"" + c.path("type").asText() + "\"");
            }
            if (tipo == CommandType.BATCH) {
                throw rechazo(batch, "Cambio " + i + ": un BATCH no puede contener otro BATCH");
            }
            // Se confirma lo que se reviso (ADR-032). Dentro de un lote, el
            // esquema que se confirma lo estarian cambiando los otros cambios.
            if (tipo == CommandType.CONFIRM_SCHEMA) {
                throw rechazo(batch, "Cambio " + i + ": CONFIRM_SCHEMA no puede ir dentro de un lote");
            }
            if (!c.path("payload").isObject()) {
                throw rechazo(batch, "Cambio " + i + " (" + tipo + "): falta el payload");
            }
            comandos.add(new Command(batch.opId(), batch.diagramId(), batch.baseSeq(), tipo, batch.origin(),
                    c.path("payload")));
            i++;
        }
        return new Lote(p.path("descripcion").asText(""), List.copyOf(comandos));
    }

    /**
     * Recorre los cambios en orden. Si uno se rechaza, el rechazo sale a nombre
     * del BATCH y dice CUAL fallo: sin la posicion, "La clase no existe" en un
     * lote de 40 cambios no dice nada.
     *
     * Lo usan el bus (locks) y el aplicador (efectos), para que los dos
     * rechazos se lean igual.
     */
    public void paraCadaCambio(Command batch, java.util.function.Consumer<Command> accion) {
        int i = 1;
        for (Command cambio : comandos) {
            try {
                accion.accept(cambio);
            } catch (CommandRejectedException e) {
                throw new CommandRejectedException(batch.opId(), e.getReason(),
                        "Cambio " + i + " de " + comandos.size() + " (" + cambio.type() + "): "
                                + e.getMessage().replaceAll("\\.+$", "") + ". No se aplico ninguno.");
            }
            i++;
        }
    }

    private static CommandRejectedException rechazo(Command batch, String mensaje) {
        return new CommandRejectedException(batch.opId(), RejectionReason.INVALID, mensaje);
    }
}
