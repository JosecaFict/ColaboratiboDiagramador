package com.uagrm.colabmodel.iam;

import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Cerrar sesion en el servidor: el token deja de servir aunque no haya vencido.
 * Antes el boton «Cerrar sesión» solo borraba el token del navegador, y una
 * copia seguia abriendo la API hasta que vencia (8 horas).
 */
class CerrarSesionTest {

    private static final String SECRETO = "una-clave-de-prueba-de-al-menos-32-bytes-para-hs256";

    /** Las sesiones cerradas en memoria, como las guarda la base. */
    private static final class EnMemoria implements SesionesCerradas {
        final Map<UUID, Instant> cerradas = new HashMap<>();

        @Override
        public boolean estaCerrada(UUID jti) { return cerradas.containsKey(jti); }

        @Override
        public void cerrar(UUID jti, Instant venceEn) { cerradas.put(jti, venceEn); }
    }

    private final EnMemoria cerradas = new EnMemoria();
    private final JwtService jwt = new JwtService(SECRETO, 480, cerradas);

    private static AppUser ana() {
        AppUser u = new AppUser();
        u.setId(UUID.randomUUID());
        u.setEmail("ana@prueba.test");
        u.setFullName("Ana");
        return u;
    }

    @Test
    void un_token_cerrado_ya_no_valida_y_los_demas_si() {
        AppUser ana = ana();
        String computadora = jwt.emitir(ana);
        String telefono = jwt.emitir(ana);

        assertThat(jwt.cerrarSesion(computadora)).isTrue();

        assertThatThrownBy(() -> jwt.validarYObtenerUserId(computadora))
                .isInstanceOf(JwtException.class).hasMessageContaining("se cerro");
        // Cerrar una sesion no cierra las otras del mismo usuario.
        assertThat(jwt.validarYObtenerUserId(telefono)).isEqualTo(ana.getId());
    }

    @Test
    void se_recuerda_hasta_que_el_token_vence() {
        String token = jwt.emitir(ana());
        jwt.cerrarSesion(token);

        Instant vence = cerradas.cerradas.values().iterator().next();
        assertThat(vence).isBetween(Instant.now().plusSeconds(479 * 60), Instant.now().plusSeconds(481 * 60));
    }

    @Test
    void un_token_invalido_no_tiene_sesion_que_cerrar() {
        assertThat(jwt.cerrarSesion("esto-no-es-un-jwt")).isFalse();
        assertThat(cerradas.cerradas).isEmpty();
    }

    @Test
    void cada_token_lleva_su_propio_id() {
        AppUser ana = ana();
        jwt.cerrarSesion(jwt.emitir(ana));
        jwt.cerrarSesion(jwt.emitir(ana));

        assertThat(cerradas.cerradas).hasSize(2);
    }

    @Test
    void el_endpoint_cierra_la_sesion_del_token_que_lo_llama() throws Exception {
        AuthController controlador = new AuthController(mock(AppUserRepository.class), new BCryptPasswordEncoder(), jwt);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controlador).build();
        String token = jwt.emitir(ana());

        mvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        assertThatThrownBy(() -> jwt.validarYObtenerUserId(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void salir_sin_token_o_con_uno_vencido_no_es_un_error() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                new AuthController(mock(AppUserRepository.class), new BCryptPasswordEncoder(), jwt)).build();

        mvc.perform(post("/api/v1/auth/logout")).andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer basura"))
                .andExpect(status().isNoContent());
    }
}
