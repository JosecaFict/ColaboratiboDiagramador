package com.uagrm.colabmodel.versiones;

import org.springframework.http.HttpStatus;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Un pedido de versiones que no se puede cumplir. Sale como
 * {@code {"mensaje": ..., ...extra}} con el estado HTTP dado: la forma de error
 * del resto de la API, mas los datos que la interfaz necesita para actuar
 * (quien tiene el lock, que conflictos faltan resolver).
 */
class RechazoDeVersiones extends RuntimeException {

    private final HttpStatus estado;
    private final Map<String, Object> extra;

    RechazoDeVersiones(HttpStatus estado, String mensaje) {
        this(estado, mensaje, Map.of());
    }

    RechazoDeVersiones(HttpStatus estado, String mensaje, Map<String, Object> extra) {
        super(mensaje);
        this.estado = estado;
        this.extra = extra;
    }

    HttpStatus estado() { return estado; }

    Map<String, Object> cuerpo() {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("mensaje", getMessage());
        c.putAll(extra);
        return c;
    }
}
