package com.uagrm.colabmodel.iam;

import com.uagrm.colabmodel.PostgresDePruebas;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Las sesiones cerradas contra PostgreSQL real: la migracion V10, el mapeo
 * JPA (que Hibernate valida al arrancar) y que sobrevivan a otra instancia del
 * servicio, que es lo que pasa cuando el servidor se reinicia.
 */
@SpringBootTest
class SesionesCerradasPostgresTest extends PostgresDePruebas {

    @Autowired
    JwtService jwt;
    @Autowired
    SesionesCerradas cerradas;
    @Autowired
    JdbcTemplate jdbc;

    @Value("${colabmodel.jwt.secret}")
    String secreto;

    private static AppUser alguien() {
        AppUser u = new AppUser();
        u.setId(UUID.randomUUID());
        u.setEmail("v10-" + UUID.randomUUID() + "@prueba.test");
        u.setFullName("Prueba V10");
        return u;
    }

    @Test
    void la_sesion_cerrada_queda_en_la_base_y_se_rechaza_tras_un_reinicio() {
        String token = jwt.emitir(alguien());
        assertThat(jwt.cerrarSesion(token)).isTrue();

        // Otro JwtService sobre la misma base: como despues de reiniciar.
        JwtService otro = new JwtService(secreto, 480, cerradas);
        assertThatThrownBy(() -> otro.validarYObtenerUserId(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void cerrar_dos_veces_la_misma_sesion_no_falla() {
        String token = jwt.emitir(alguien());

        assertThat(jwt.cerrarSesion(token)).isTrue();
        assertThat(jwt.cerrarSesion(token)).isTrue();
    }

    @Test
    void las_vencidas_se_olvidan_al_cerrar_otra() {
        UUID vieja = UUID.randomUUID();
        jdbc.update("insert into revoked_tokens(jti, expires_at) values (?, now() - interval '1 hour')", vieja);

        cerradas.cerrar(UUID.randomUUID(), Instant.now().plusSeconds(3600));

        assertThat(jdbc.queryForObject("select count(*) from revoked_tokens where jti = ?", Integer.class, vieja))
                .isZero();
    }
}
