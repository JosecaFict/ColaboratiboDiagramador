package com.uagrm.colabmodel.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T-09: la memoria entra en el contexto del modelo o se recorta, y lo
 * obligatorio (instruccion, herramientas, pedido y diagrama) no se corta nunca.
 */
class PresupuestoDeContextoTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ContextoDeEquipo.Interlocutor beto =
            new ContextoDeEquipo.Interlocutor(UUID.randomUUID(), "Beto", "Modelador");

    private List<ContextoDeEquipo.Turno> turnos(int n, int largo) {
        List<ContextoDeEquipo.Turno> l = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            l.add(new ContextoDeEquipo.Turno("Ana", false, ModoIA.CONSULTAR,
                    "pedido " + i + " " + "x".repeat(largo), "respuesta " + i, ""));
        }
        return l;
    }

    private SolicitudIA solicitud(String prompt, List<ContextoDeEquipo.Turno> turnos) {
        ContextoDeEquipo equipo = new ContextoDeEquipo(beto, ConfiguracionDeIA.POR_DEFECTO, turnos, 0);
        return new SolicitudIA(prompt, ModoIA.CONSULTAR, List.of(), List.of(), List.of(), null, equipo);
    }

    @Test
    void si_todo_entra_no_toca_nada() {
        SolicitudIA s = solicitud("hola", turnos(3, 10));

        SolicitudIA ajustada = new PresupuestoDeContexto(32_000, 512, mapper).ajustar(s);

        assertThat(ajustada).isSameAs(s);
    }

    @Test
    void si_no_entra_saca_primero_los_turnos_mas_viejos() {
        SolicitudIA s = solicitud("hola", turnos(6, 600));
        int sinMemoria = new PresupuestoDeContexto(1, 0, mapper).estimar(
                s.conEquipo(s.equipo().conTurnos(List.of(), 0)));
        // Lugar para lo obligatorio mas unos dos turnos y medio.
        PresupuestoDeContexto p = new PresupuestoDeContexto(sinMemoria + 520, 0, mapper);

        SolicitudIA ajustada = p.ajustar(s);

        assertThat(ajustada.equipo().turnos()).extracting(t -> t.pedido().substring(0, 8))
                .containsExactly("pedido 5", "pedido 6");
        assertThat(ajustada.equipo().turnosOmitidos()).isPositive();
        assertThat(p.estimar(ajustada)).isLessThanOrEqualTo(p.disponibles());
        assertThat(ajustada.mensajeParaElModelo()).contains("no se muestran").endsWith("hola");
    }

    @Test
    void si_no_entra_ni_un_turno_se_va_sin_memoria_pero_con_quien_pregunta() {
        SolicitudIA s = solicitud("hola", turnos(2, 3000));
        int sinMemoria = new PresupuestoDeContexto(1, 0, mapper).estimar(
                s.conEquipo(s.equipo().conTurnos(List.of(), 0)));

        SolicitudIA ajustada = new PresupuestoDeContexto(sinMemoria + 30, 0, mapper).ajustar(s);

        assertThat(ajustada.equipo().turnos()).isEmpty();
        assertThat(ajustada.mensajeParaElModelo()).contains("QUIEN PREGUNTA AHORA: Beto").endsWith("hola");
    }

    @Test
    void si_lo_obligatorio_no_entra_avisa_antes_de_llamar_al_modelo() {
        SolicitudIA s = solicitud("p".repeat(20_000), turnos(2, 10));

        assertThatThrownBy(() -> new PresupuestoDeContexto(4096, 512, mapper).ajustar(s))
                .isInstanceOf(ContextoExcedidoException.class)
                .hasMessageContaining("no entran en el contexto del modelo")
                .hasMessageContaining("Acorta el pedido");
    }

    @Test
    void sin_contexto_de_equipo_igual_controla_lo_obligatorio() {
        SolicitudIA corta = new SolicitudIA("hola", ModoIA.MODIFICAR, List.of(), List.of(),
                EsquemaDeComandos.herramientas(), null);
        SolicitudIA larga = new SolicitudIA("p".repeat(20_000), ModoIA.MODIFICAR, List.of(), List.of(),
                EsquemaDeComandos.herramientas(), null);
        PresupuestoDeContexto p = new PresupuestoDeContexto(4096, 512, mapper);

        assertThat(p.ajustar(corta)).isSameAs(corta);
        assertThatThrownBy(() -> p.ajustar(larga)).isInstanceOf(ContextoExcedidoException.class);
    }

    @Test
    void la_estimacion_cuenta_las_herramientas() {
        PresupuestoDeContexto p = new PresupuestoDeContexto(32_000, 0, mapper);
        SolicitudIA sin = new SolicitudIA("hola", ModoIA.MODIFICAR, List.of(), List.of(), List.of(), null);
        SolicitudIA con = new SolicitudIA("hola", ModoIA.MODIFICAR, List.of(), List.of(),
                EsquemaDeComandos.herramientas(), null);

        assertThat(p.estimar(con) - p.estimar(sin)).isGreaterThan(1000);
    }
}
