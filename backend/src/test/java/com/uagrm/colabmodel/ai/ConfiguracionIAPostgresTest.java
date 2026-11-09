package com.uagrm.colabmodel.ai;

import com.uagrm.colabmodel.PostgresDePruebas;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T-09 contra PostgreSQL de verdad: V9 se aplica, la configuracion persiste,
 * hay una sola fila por proyecto, se borra con el proyecto y la base rechaza
 * por su cuenta lo que el controlador ya rechaza.
 */
@SpringBootTest
class ConfiguracionIAPostgresTest extends PostgresDePruebas {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ConfiguracionesDeIA configuraciones;

    private final String marca = UUID.randomUUID().toString().substring(0, 8);
    private UUID usuario;
    private UUID proyecto;

    @BeforeEach
    void unProyecto() {
        usuario = jdbc.queryForObject("insert into users(email, password_hash, full_name) values (?, 'x', 'Ana') "
                + "returning id", UUID.class, "t09-" + marca + "@prueba.test");
        proyecto = jdbc.queryForObject("insert into projects(name, owner_id) values (?, ?) returning id",
                UUID.class, "T-09 " + marca, usuario);
    }

    @AfterEach
    void limpiar() {
        jdbc.update("delete from projects where owner_id = ?", usuario);
        jdbc.update("delete from users where id = ?", usuario);
    }

    @Test
    void sin_fila_devuelve_la_de_por_defecto_y_no_la_crea() {
        assertThat(configuraciones.de(proyecto)).isEqualTo(ConfiguracionDeIA.POR_DEFECTO);
        assertThat(filas()).isZero();
    }

    @Test
    void guardar_dos_veces_deja_una_sola_fila_con_lo_ultimo() {
        configuraciones.guardar(proyecto, usuario, new ConfiguracionDeIA(ConfiguracionDeIA.Rol.SENIOR,
                ConfiguracionDeIA.Detalle.BREVE, "primero"));
        configuraciones.guardar(proyecto, usuario, new ConfiguracionDeIA(ConfiguracionDeIA.Rol.REVISOR,
                ConfiguracionDeIA.Detalle.DETALLADO, "despues"));

        assertThat(filas()).isEqualTo(1);
        assertThat(configuraciones.de(proyecto)).isEqualTo(new ConfiguracionDeIA(ConfiguracionDeIA.Rol.REVISOR,
                ConfiguracionDeIA.Detalle.DETALLADO, "despues"));
        assertThat(jdbc.queryForObject("select updated_by from ai_settings where project_id = ?", UUID.class,
                proyecto)).isEqualTo(usuario);
    }

    @Test
    void se_borra_con_el_proyecto() {
        configuraciones.guardar(proyecto, usuario, ConfiguracionDeIA.POR_DEFECTO);

        jdbc.update("delete from projects where id = ?", proyecto);

        assertThat(filas()).isZero();
    }

    @Test
    void la_base_rechaza_valores_invalidos_aunque_se_escriban_a_mano() {
        assertThatThrownBy(() -> jdbc.update("insert into ai_settings(project_id, rol, detalle, instrucciones) "
                + "values (?, 'JEFE', 'NORMAL', '')", proyecto)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("insert into ai_settings(project_id, rol, detalle, instrucciones) "
                + "values (?, 'MENTOR', 'NORMAL', ?)", proyecto, "x".repeat(1001)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(filas()).isZero();
    }

    private int filas() {
        return jdbc.queryForObject("select count(*) from ai_settings where project_id = ?", Integer.class, proyecto);
    }
}
