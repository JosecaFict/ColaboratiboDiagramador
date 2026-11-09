package com.uagrm.colabmodel.ai;

import com.uagrm.colabmodel.iam.AppUser;
import com.uagrm.colabmodel.project.AccesoAProyecto.Rol;
import com.uagrm.colabmodel.project.Project;
import com.uagrm.colabmodel.project.ProyectosEnMemoria;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * T-09, contrato compartido con T-10 (D-08 punto 1): GET para los tres roles,
 * PUT solo del propietario, los tres campos obligatorios, y quien no es
 * miembro recibe 403 en ambos.
 */
class ConfiguracionIAControllerTest {

    private ProyectosEnMemoria datos;
    private AppUser ana, beto, carla, extrano;
    private UUID proyectoId;
    private final Map<UUID, ConfiguracionIAFila> filas = new HashMap<>();
    private ConfiguracionIAController controlador;

    @BeforeEach
    void preparar() {
        datos = new ProyectosEnMemoria();
        ana = datos.usuario("Ana");
        Project p = datos.proyecto(ana, "Biblioteca");
        proyectoId = p.getId();
        beto = datos.miembro(p, "Beto", Rol.EDITOR);
        carla = datos.miembro(p, "Carla", Rol.VIEWER);
        extrano = datos.usuario("Extrano");

        ConfiguracionIARepository repo = mock(ConfiguracionIARepository.class);
        when(repo.findById(any())).thenAnswer(i -> Optional.ofNullable(filas.get(i.<UUID>getArgument(0))));
        when(repo.save(any())).thenAnswer(i -> {
            ConfiguracionIAFila f = i.getArgument(0);
            filas.put(f.getProjectId(), f);
            return f;
        });
        controlador = new ConfiguracionIAController(new ConfiguracionesDeIA(repo), datos.acceso);
    }

    private static Principal como(AppUser u) {
        return () -> u.getId().toString();
    }

    private static ConfiguracionDeIA.Entrada entrada(String rol, String detalle, String instrucciones) {
        return new ConfiguracionDeIA.Entrada(rol, detalle, instrucciones);
    }

    @Test
    void sin_guardar_nada_los_tres_roles_leen_la_configuracion_por_defecto_sin_crear_la_fila() {
        for (AppUser u : new AppUser[] {ana, beto, carla}) {
            assertThat(controlador.leer(proyectoId, como(u))).isEqualTo(ConfiguracionDeIA.POR_DEFECTO);
        }
        assertThat(ConfiguracionDeIA.POR_DEFECTO)
                .isEqualTo(new ConfiguracionDeIA(ConfiguracionDeIA.Rol.MENTOR, ConfiguracionDeIA.Detalle.NORMAL, ""));
        assertThat(filas).isEmpty();
    }

    @Test
    void el_propietario_la_cambia_y_todos_leen_la_nueva() {
        ConfiguracionDeIA guardada = controlador.guardar(proyectoId,
                entrada("senior", "BREVE", "  somos tres  "), como(ana));

        assertThat(guardada).isEqualTo(new ConfiguracionDeIA(ConfiguracionDeIA.Rol.SENIOR,
                ConfiguracionDeIA.Detalle.BREVE, "somos tres"));
        assertThat(controlador.leer(proyectoId, como(carla))).isEqualTo(guardada);
        assertThat(filas).containsKey(proyectoId);
    }

    @Test
    void un_editor_o_un_observador_no_la_cambian() {
        for (AppUser u : new AppUser[] {beto, carla}) {
            assertThatThrownBy(() -> controlador.guardar(proyectoId, entrada("REVISOR", "NORMAL", ""), como(u)))
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        }
        assertThat(filas).isEmpty();
    }

    @Test
    void quien_no_es_miembro_no_la_lee_ni_la_cambia() {
        assertThatThrownBy(() -> controlador.leer(proyectoId, como(extrano)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> controlador.guardar(proyectoId, entrada("MENTOR", "NORMAL", ""), como(extrano)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void el_put_exige_los_tres_campos_y_valores_validos() {
        assertThatThrownBy(() -> controlador.guardar(proyectoId, entrada("MENTOR", null, ""), como(ana)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Faltan datos");
        assertThatThrownBy(() -> controlador.guardar(proyectoId, null, como(ana)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> controlador.guardar(proyectoId, entrada("JEFE", "NORMAL", ""), como(ana)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("rol \"JEFE\"");
        assertThatThrownBy(() -> controlador.guardar(proyectoId, entrada("MENTOR", "ENORME", ""), como(ana)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("detalle");
        assertThatThrownBy(() -> controlador.guardar(proyectoId, entrada("MENTOR", "NORMAL", "x".repeat(1001)),
                como(ana))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("1000");
        assertThat(filas).isEmpty();
        // El error se responde como 400 con "mensaje".
        assertThat(controlador.invalida(new IllegalArgumentException("algo")).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(controlador.invalida(new IllegalArgumentException("algo")).getBody()).containsEntry("mensaje", "algo");
    }

    @Test
    void mil_caracteres_justos_se_aceptan() {
        assertThat(controlador.guardar(proyectoId, entrada("MENTOR", "NORMAL", "x".repeat(1000)), como(ana))
                .instrucciones()).hasSize(1000);
    }
}
