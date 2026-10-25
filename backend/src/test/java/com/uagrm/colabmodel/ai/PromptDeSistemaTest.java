package com.uagrm.colabmodel.ai;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PromptDeSistemaTest {

    @Test
    void disenar_exige_autoevaluacion_y_auditoria_breve() {
        String prompt = PromptDeSistema.para(ModoIA.DISENAR);

        assertThat(prompt).contains("audita silenciosamente", "trazabilidad historica",
                "claves foraneas", "PROPONER_MODELO_UML", "exactamente una vez");
    }

    @Test
    void consultar_trata_el_contexto_textual_como_el_diagrama_real() {
        assertThat(PromptDeSistema.para(ModoIA.CONSULTAR))
                .contains("SON el diagrama actual", "no digas que no puedes verlo", "lectura exacta");
    }
}
