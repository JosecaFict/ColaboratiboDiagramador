package com.uagrm.colabmodel.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExpansorPlanUmlTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void herencia_no_exporta_multiplicidades_de_asociacion() throws Exception {
        var plan = mapper.readTree("""
                {"classes":[{"name":"Computadora","attributes":[{"name":"modelo","dataType":"string"}]},
                {"name":"Producto","attributes":[{"name":"codigo","dataType":"string"}]}],
                "relations":[{"source":"Computadora","target":"Producto","kind":"GENERALIZATION",
                "sourceMultiplicity":"*","targetMultiplicity":"1"}],"audit":[]}
                """);
        var resultado = ExpansorPlanUml.expandir(new RespuestaIA(List.of(
                new ComandoPropuestoIA(EsquemaPlanUml.NOMBRE, plan)), ""));
        assertThat(resultado.comandos()).filteredOn(c -> c.tipo().equals("CREATE_RELATION"))
                .singleElement().satisfies(c -> {
                    assertThat(c.parametros().path("claseOrigen").asText()).isEqualTo("Computadora");
                    assertThat(c.parametros().has("sourceMultiplicity")).isFalse();
                    assertThat(c.parametros().has("targetMultiplicity")).isFalse();
                });
    }

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
    void prioriza_identidades_y_relaciones_antes_que_atributos_secundarios() throws Exception {
        var plan = mapper.readTree("""
                {"classes":[
                  {"name":"Paciente","attributes":[
                    {"name":"idPaciente","dataType":"uuid","required":true,"unique":true,"pk":true},
                    {"name":"nombreCompleto","dataType":"string","required":true,"unique":false,"pk":false}]},
                  {"name":"Consulta","attributes":[
                    {"name":"idConsulta","dataType":"uuid","required":true,"unique":true,"pk":true},
                    {"name":"fechaHora","dataType":"datetime","required":true,"unique":false,"pk":false}]}
                ],"relations":[
                  {"source":"Paciente","target":"Consulta","kind":"ASSOCIATION",
                   "sourceMultiplicity":"1","targetMultiplicity":"0..*"}
                ],"audit":["Conserva historial","Relacion completa"]}
                """);

        RespuestaIA expandida = ExpansorPlanUml.expandir(new RespuestaIA(
                List.of(new ComandoPropuestoIA(EsquemaPlanUml.NOMBRE, plan)), ""));

        assertThat(expandida.comandos()).extracting(ComandoPropuestoIA::tipo)
                .containsExactly("CREATE_CLASS", "CREATE_CLASS", "ADD_ATTRIBUTE", "ADD_ATTRIBUTE",
                        "CREATE_RELATION", "ADD_ATTRIBUTE", "ADD_ATTRIBUTE");
    }

    @Test
    void reparte_un_atributo_propio_por_clase_antes_de_agregar_mas_detalles() throws Exception {
        var plan = mapper.readTree("""
                {"classes":[
                  {"name":"Primera","attributes":[
                    {"name":"idPrimera","dataType":"uuid","pk":true},
                    {"name":"datoPrimera","dataType":"string"},
                    {"name":"extraPrimera","dataType":"string"}]},
                  {"name":"Segunda","attributes":[
                    {"name":"idSegunda","dataType":"uuid","pk":true},
                    {"name":"datoSegunda","dataType":"string"}]}
                ],"relations":[
                  {"source":"Primera","target":"Segunda","kind":"ASSOCIATION",
                   "sourceMultiplicity":"1","targetMultiplicity":"0..*"}
                ],"audit":[]}
                """);

        RespuestaIA expandida = ExpansorPlanUml.expandir(new RespuestaIA(
                List.of(new ComandoPropuestoIA(EsquemaPlanUml.NOMBRE, plan)), ""));

        assertThat(expandida.comandos().stream().filter(c -> c.tipo().equals("ADD_ATTRIBUTE"))
                .map(c -> c.parametros().path("name").asText()).toList())
                .containsExactly("idPrimera", "idSegunda", "datoPrimera", "datoSegunda", "extraPrimera");
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
