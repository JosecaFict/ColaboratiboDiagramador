package com.uagrm.colabmodel.ai;

/**
 * Un modelo de lenguaje que traduce un pedido a llamadas de herramienta.
 *
 * El resto del sistema (ServicioDeIA, la validacion, el tope de ADR-014) no
 * sabe ni le importa cual implementacion esta activa: Claude API y Ollama
 * local son intercambiables por configuracion (`colabmodel.ai.proveedor`),
 * asi que decidir cual se usa el dia de la defensa no bloquea el resto del
 * desarrollo.
 *
 * Deliberadamente NO devuelve comandos validados: `RespuestaIA` es la salida
 * cruda del modelo, tan sospechosa como un archivo importado. La validacion
 * (catalogo cerrado, tope de 8, referencias existentes) vive en
 * ServicioDeIA/ResolutorDeReferencias y corre SIEMPRE, sin importar el
 * proveedor.
 */
public interface ProveedorDeIA {

    /** Para loguear y para mostrarle al usuario cual esta contestando. */
    String nombre();

    /**
     * @throws ProveedorIAException si no se pudo contactar al modelo o su
     *         respuesta no tiene una forma reconocible
     */
    RespuestaIA generar(SolicitudIA solicitud);
}
