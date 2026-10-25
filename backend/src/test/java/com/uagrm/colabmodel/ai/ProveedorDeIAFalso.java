package com.uagrm.colabmodel.ai;

/** Proveedor de prueba: devuelve siempre la misma respuesta, sin red. */
class ProveedorDeIAFalso implements ProveedorDeIA {

    private final RespuestaIA respuesta;

    ProveedorDeIAFalso(RespuestaIA respuesta) {
        this.respuesta = respuesta;
    }

    @Override
    public String nombre() {
        return "falso";
    }

    @Override
    public RespuestaIA generar(SolicitudIA solicitud) {
        return respuesta;
    }
}
