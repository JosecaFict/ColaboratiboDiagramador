package com.uagrm.colabmodel.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExpansorPlanUmlTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void expande_clases_antes_de_atributos_y_relaciones() throws Exception {
        var plan = mapper.readTree("""
                {"classes":[
                  {"name":"Libro","attributes":[{"name":"isbn","dataType":"string","required":true,"unique":true,"pk":true}]},
                  {"name":"Ejemplar","attributes":[{"name":"codigo","dataType":"string","required":true,"unique":true,"pk":true}]}
                ],"relations":[
                  {"source":"Libro","target":"Ejemplar","kind":"ASSOCIATION","sourceMultiplicity":"1","targetMultiplicity":"0..*"}
                ],"audit":["Separa titulo y copia fisica","Conserva trazabilidad"]}
                """);

        RespuestaIA expandida = ExpansorPlanUml.expandir(new RespuestaIA(
                List.of(new ComandoPropuestoIA(EsquemaPlanUml.NOMBRE, plan)), ""));

        assertThat(expandida.comandos()).extracting(ComandoPropuestoIA::tipo)
                .containsExactly("CREATE_CLASS", "CREATE_CLASS", "ADD_ATTRIBUTE", "ADD_ATTRIBUTE", "CREATE_RELATION");
        assertThat(expandida.notaDelModelo()).contains("Auditoria de diseno", "Separa titulo", "Conserva trazabilidad");
    }

    @Test
    void la_herramienta_exige_atributos_relaciones_y_auditoria() {
        var esquema = EsquemaPlanUml.herramienta().parametros();

        assertThat(EsquemaPlanUml.herramienta().nombre()).isEqualTo("PROPONER_MODELO_UML");
        assertThat(esquema.path("required")).extracting(n -> n.asText())
                .containsExactly("classes", "relations", "audit");
        assertThat(esquema.path("properties").path("classes").path("items")
                .path("properties").path("attributes").path("minItems").asInt()).isEqualTo(1);
    }

    @Test
    void detecta_un_plan_vacio_antes_de_expandirlo() throws Exception {
        var vacio = new ComandoPropuestoIA(EsquemaPlanUml.NOMBRE,
                mapper.readTree("{\"classes\":[],\"relations\":[],\"audit\":[]}"));

        assertThat(ProveedorOllama.errorEstructuralDelPlan(vacio)).contains("classes").contains("vacio");
        assertThat(ProveedorOllama.errorEstructuralDelPlan(null)).contains("no llamo");
    }
}
