package com.uagrm.colabmodel.ai;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SolicitudIATest {

    @Test
    void el_contexto_incluye_metodos_relaciones_y_semantica_de_clase() {
        SolicitudIA s = new SolicitudIA("revisa", ModoIA.REVISAR,
                List.of(new SolicitudIA.ClaseEnContexto("Persona", List.of("nombre: string"),
                        List.of("saludar(): void"), "aggregate", true)),
                List.of(new SolicitudIA.RelacionEnContexto("Persona", "Pedido", "ASSOCIATION",
                        "realiza", "1", "0..*")), List.of());

        assertThat(s.mensajeParaElModelo())
                .contains("Persona (nombre: string)")
                .contains("metodos: saludar(): void")
                .contains("estereotipo: aggregate")
                .contains("abstracta")
                .contains("Persona [1] --ASSOCIATION--> Pedido [0..*] : realiza")
                .contains("cada Persona puede relacionarse con 0..* instancia(s) de Pedido")
                .contains("cada Pedido puede relacionarse con 1 instancia(s) de Persona")
                .contains("No inviertas esta lectura");
    }

    @Test
    void consultar_y_revisar_no_piden_editar() {
        SolicitudIA consulta = new SolicitudIA("explica", ModoIA.CONSULTAR, List.of(), List.of(), List.of());
        SolicitudIA revision = new SolicitudIA("revisa", ModoIA.REVISAR, List.of(), List.of(), List.of());

        assertThat(consulta.instruccionDeSistema()).contains("No llames herramientas");
        assertThat(revision.instruccionDeSistema()).contains("no llames herramientas");
    }
}
