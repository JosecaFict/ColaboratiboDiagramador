package com.uagrm.colabmodel.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    void una_sola_clase_se_disena_sin_relaciones() throws Exception {
        var plan = mapper.readTree("""
                {"classes":[{"name":"Producto","historica":false,"attributes":[
                  {"name":"nombre","dataType":"string","required":true,"unique":false,"pk":false},
                  {"name":"precio","dataType":"decimal","required":true,"unique":false,"pk":false}]}],
                 "relations":[],"audit":["Producto aislado","Precio obligatorio"]}
                """);
        var comando = new ComandoPropuestoIA(EsquemaPlanUml.NOMBRE, plan);

        assertThat(ProveedorOllama.errorEstructuralDelPlan(comando)).isNull();
        assertThat(ExpansorPlanUml.expandir(new RespuestaIA(List.of(comando), "")).comandos())
                .extracting(ComandoPropuestoIA::tipo)
                .containsExactly("CREATE_CLASS", "ADD_ATTRIBUTE", "ADD_ATTRIBUTE");
    }

    @Test
    void varias_clases_sueltas_siguen_siendo_un_plan_incompleto() throws Exception {
        var plan = mapper.readTree("""
                {"classes":[
                  {"name":"Paciente","attributes":[{"name":"nombre","dataType":"string","required":true,"unique":false,"pk":false}]},
                  {"name":"Cita","attributes":[{"name":"fecha","dataType":"datetime","required":true,"unique":false,"pk":false}]}
                ],"relations":[],"audit":["a","b"]}
                """);
        var comando = new ComandoPropuestoIA(EsquemaPlanUml.NOMBRE, plan);

        assertThat(ProveedorOllama.errorEstructuralDelPlan(comando)).contains("ninguna relacion");
        assertThatThrownBy(() -> ExpansorPlanUml.expandir(new RespuestaIA(List.of(comando), "")))
                .hasMessageContaining("ninguna relacion");
    }

    @Test
    void la_herramienta_pide_declarar_si_cada_clase_es_historica() {
        var clase = EsquemaPlanUml.herramienta().parametros().path("properties").path("classes").path("items");

        assertThat(clase.path("properties").path("historica").path("type").asText()).isEqualTo("boolean");
        assertThat(clase.path("required")).extracting(n -> n.asText()).contains("historica");
    }

    @Test
    void detecta_un_plan_vacio_antes_de_expandirlo() throws Exception {
        var vacio = new ComandoPropuestoIA(EsquemaPlanUml.NOMBRE,
                mapper.readTree("{\"classes\":[],\"relations\":[],\"audit\":[]}"));

        assertThat(ProveedorOllama.errorEstructuralDelPlan(vacio)).contains("classes").contains("vacio");
        assertThat(ProveedorOllama.errorEstructuralDelPlan(null)).contains("no llamo");
    }
}
