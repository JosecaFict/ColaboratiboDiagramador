package com.uagrm.colabmodel.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;

/**
 * Lo que responde la cadena de seguridad cuando corta un pedido, con la forma
 * `{"mensaje": ...}` del resto de la API.
 *
 * Sin esto, Spring Security contesta 403 a quien no manda token (su entrada por
 * defecto sin formulario de login es `Http403ForbiddenEntryPoint`), y la
 * interfaz no podria distinguir "tu sesion murio" (401: volver al login) de
 * "no tenes permiso" (403: mostrar el aviso y seguir). Un token vencido o
 * invalido llega aca igual que uno ausente: `JwtAuthenticationFilter` lo ignora
 * y el pedido queda anonimo.
 */
public class RespuestasDeSeguridad implements AuthenticationEntryPoint, AccessDeniedHandler {

    static final String SIN_SESION = "Tu sesión no es válida o venció: iniciá sesión de nuevo";
    static final String SIN_PERMISO = "No tenés permiso para hacer esto";

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException e) throws IOException {
        responder(response, HttpStatus.UNAUTHORIZED, SIN_SESION);
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException e) throws IOException {
        responder(response, HttpStatus.FORBIDDEN, SIN_PERMISO);
    }

    private static void responder(HttpServletResponse response, HttpStatus estado, String mensaje)
            throws IOException {
        response.setStatus(estado.value());
        response.setContentType("application/json;charset=UTF-8");
        // Los textos son constantes sin comillas ni barras: no hace falta escapar.
        response.getWriter().write("{\"mensaje\":\"" + mensaje + "\"}");
    }
}
