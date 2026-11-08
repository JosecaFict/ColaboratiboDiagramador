package com.uagrm.colabmodel.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProveedorOllamaPlanTest {

    @Test
    void un_borrador_legible_con_identidad_sola_llega_a_la_reparacion_semantica() throws Exception {
        var p = new ObjectMapper().readTree("""
                {"classes":[{"name":"Animal","attributes":[{"name":"idAnimal","dataType":"uuid","pk":true}]}],
                "relations":[{"source":"Animal","target":"Animal","kind":"ASSOCIATION"}],"audit":["a","b"]}
                """);
        var comando = new ComandoPropuestoIA(EsquemaPlanUml.NOMBRE, p);
        assertThat(ProveedorOllama.errorDeFormatoDelPlan(comando)).isNull();
        assertThat(ProveedorOllama.errorEstructuralDelPlan(comando)).contains("solo contiene identidad");
        assertThat(ValidadorPlanFinal.revisar(new RespuestaIA(java.util.List.of(comando), "")))
                .anyMatch(e -> e.contains("solo tiene identidad"));
    }

    @Test
    void no_exige_datos_inventados_a_un_prerrequisito_con_roles_completos() throws Exception {
        var p = new ObjectMapper().readTree("""
                {"classes":[{"name":"Asignatura","attributes":[{"name":"nombre","dataType":"string"}]},
                {"name":"Prerrequisito","attributes":[{"name":"id","pk":true}]}],
                "relations":[{"source":"Asignatura","target":"Prerrequisito","sourceRole":"actual"},
                {"source":"Prerrequisito","target":"Asignatura","targetRole":"requerida"}],
                "audit":["a","b"]}
                """);
        assertThat(ProveedorOllama.errorEstructuralDelPlan(
                new ComandoPropuestoIA(EsquemaPlanUml.NOMBRE, p))).isNull();
    }

    @Test
    void solicita_reparacion_si_una_clase_solo_tiene_identidad() throws Exception {
        var p = new ObjectMapper().readTree("""
                {"classes":[{"name":"Animal","attributes":[{"name":"idAnimal","pk":true}]}],
                "relations":[{}],"audit":["a","b"]}
                """);
        assertThat(ProveedorOllama.errorEstructuralDelPlan(
                new ComandoPropuestoIA(EsquemaPlanUml.NOMBRE, p))).contains("solo contiene identidad");
    }

    @Test
    void convierte_la_salida_json_estructurada_en_el_comando_del_plan() {
        var mapper = new ObjectMapper();
        String json = """
                {"classes":[{"name":"Libro","attributes":[{"name":"id","dataType":"uuid",\
                "required":true,"unique":true,"pk":true},{"name":"titulo","dataType":"string"}]}],"relations":[{"source":"Libro",\
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

    @Test
    void recupera_un_plan_json_aunque_el_modelo_lo_envuelva_en_markdown() {
        String contenido = """
                Aqui esta el plan:
                ```json
                {"classes":[{"name":"Paciente","attributes":[{"name":"idPaciente",\
                "dataType":"uuid","required":true,"unique":true,"pk":true},{"name":"nombre","dataType":"string"}]}],\
                "relations":[{"source":"Paciente","target":"Cita","kind":"ASSOCIATION",\
                "sourceMultiplicity":"1","targetMultiplicity":"0..*"}],\
                "audit":["conserva historial","valida solapamientos"]}
                ```
                """;

        RespuestaIA respuesta = ProveedorOllama.planDesdeContenidoJson(new ObjectMapper(), contenido);

        assertThat(respuesta.comandos()).singleElement().satisfies(comando ->
                assertThat(ProveedorOllama.errorEstructuralDelPlan(comando)).isNull());
    }
}
