package com.uagrm.colabmodel.config;

import com.uagrm.colabmodel.project.DevBootstrapController;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lo que responde la seguridad cuando corta un pedido, y el candado del atajo
 * de desarrollo (ADR-026).
 */
class RespuestasDeSeguridadTest {

    private final RespuestasDeSeguridad respuestas = new RespuestasDeSeguridad();

    @Test
    void sin_sesion_o_con_el_token_vencido_es_401_y_no_403() throws Exception {
        // Spring Security, sin formulario de login, contestaria 403: la interfaz no
        // podria distinguir "tu sesion murio" de "no tenes permiso".
        MockHttpServletResponse r = new MockHttpServletResponse();

        respuestas.commence(new MockHttpServletRequest(), r, new BadCredentialsException("sin token"));

        assertThat(r.getStatus()).isEqualTo(401);
        assertThat(r.getContentType()).isEqualTo("application/json;charset=UTF-8");
        assertThat(r.getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
                .isEqualTo("{\"mensaje\":\"Tu sesión no es válida o venció: iniciá sesión de nuevo\"}");
    }

    @Test
    void un_acceso_denegado_es_403_con_mensaje() throws Exception {
        MockHttpServletResponse r = new MockHttpServletResponse();

        respuestas.handle(new MockHttpServletRequest(), r, new AccessDeniedException("no"));

        assertThat(r.getStatus()).isEqualTo(403);
        assertThat(r.getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
                .isEqualTo("{\"mensaje\":\"No tenés permiso para hacer esto\"}");
    }

    @Test
    void el_atajo_que_emite_tokens_sin_contrasena_solo_existe_con_el_perfil_dev() {
        Profile perfil = DevBootstrapController.class.getAnnotation(Profile.class);

        assertThat(perfil).as("@Profile en DevBootstrapController").isNotNull();
        assertThat(perfil.value()).containsExactly(SecurityConfig.PERFIL_DE_DESARROLLO);
        assertThat(SecurityConfig.PERFIL_DE_DESARROLLO).isEqualTo("dev");
    }
}
