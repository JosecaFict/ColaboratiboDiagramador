package com.uagrm.colabmodel.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProveedorOllamaPlanTest {

    @Test
    void convierte_la_salida_json_estructurada_en_el_comando_del_plan() {
        var mapper = new ObjectMapper();
        String json = """
                {"classes":[{"name":"Libro","attributes":[{"name":"id","dataType":"uuid",\
                "required":true,"unique":true,"pk":true}]}],"relations":[{"source":"Libro",\
                "target":"Autor","kind":"ASSOCIATION","sourceMultiplicity":"0..*",\
                "targetMultiplicity":"1..*"}],"audit":["trazabilidad","historial"]}
                """;

        RespuestaIA respuesta = ProveedorOllama.planDesdeContenidoJson(mapper, json);

        assertThat(respuesta.comandos()).singleElement().satisfies(comando -> {
            assertThat(comando.tipo()).isEqualTo(EsquemaPlanUml.NOMBRE);
            assertThat(comando.parametros().path("classes")).hasSize(1);
        });
        assertThat(ProveedorOllama.errorEstructuralDelPlan(respuesta.comandos().getFirst())).isNull();
    }

    @Test
    void no_inventa_un_plan_si_el_contenido_no_es_json() {
        assertThat(ProveedorOllama.planDesdeContenidoJson(new ObjectMapper(), "Voy a crear el modelo")
                .comandos()).isEmpty();
    }
}
