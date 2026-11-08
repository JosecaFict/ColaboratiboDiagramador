package com.uagrm.colabmodel.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class NormalizadorParentescoPlanTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void convierte_roles_redundantes_en_parentesco_con_orientacion_correcta() throws Exception {
        ObjectNode p = plan();
        assertThat(ProveedorOllama.errorEstructuralDelPlan(new ComandoPropuestoIA(EsquemaPlanUml.NOMBRE, p))).isNull();
        var respuesta = NormalizadorPlanUml.normalizar(new RespuestaIA(List.of(
                new ComandoPropuestoIA(EsquemaPlanUml.NOMBRE, p)), ""), "Registrar padre y madre de cada animal");
        var resultado = respuesta.comandos().getFirst().parametros();
        assertThat(resultado.path("classes")).hasSize(1);
        assertThat(resultado.path("relations")).hasSize(2).allSatisfy(r -> {
            assertThat(r.path("source").asText()).isEqualTo("Animal");
            assertThat(r.path("target").asText()).isEqualTo("Animal");
            assertThat(r.path("sourceRole").asText()).isEqualTo("descendientes");
            assertThat(r.path("sourceMultiplicity").asText()).isEqualTo("0..*");
            assertThat(r.path("targetMultiplicity").asText()).isEqualTo("0..1");
        });
        assertThat(resultado.path("relations")).extracting(r -> r.path("targetRole").asText())
                .containsExactlyInAnyOrder("padre", "madre");
        assertThat(ValidadorPlanFinal.revisar(respuesta)).isEmpty();
        assertThat(p.path("classes")).hasSize(3); // El borrador original no se modifica.
    }

    @Test
    void no_descarta_atributos_propios_ni_claves_naturales_del_rol() throws Exception {
        ObjectNode p = plan();
        ((com.fasterxml.jackson.databind.node.ArrayNode) p.path("classes").get(1).path("attributes"))
                .addObject().put("name", "registroGenealogico").put("dataType", "string").put("pk", true);
        assertThat(NormalizadorParentescoPlan.puedeSimplificar(p, p.path("classes").get(1))).isFalse();
        NormalizadorParentescoPlan.simplificar(p);
        assertThat(p.path("classes")).hasSize(2);
        assertThat(p.toString()).contains("registroGenealogico");
    }

    @Test
    void un_rol_con_datos_comunes_se_fusiona_y_reutiliza_la_recursiva_sin_roles() throws Exception {
        // Ganaderia, 2026-11-08: PadreAnimal con nombre (que Animal no tenia) y una
        // asociacion Animal-Animal sin roles dibujada por el modelo.
        ObjectNode p = (ObjectNode) mapper.readTree("""
                {"classes":[
                  {"name":"Animal","attributes":[{"name":"idAnimal","dataType":"uuid","pk":true},
                    {"name":"fechaNacimiento","dataType":"date"}]},
                  {"name":"PadreAnimal","attributes":[{"name":"idPadreAnimal","dataType":"uuid","pk":true},
                    {"name":"nombre","dataType":"string"},{"name":"fechaNacimiento","dataType":"date"}]}],
                "relations":[
                  {"source":"Animal","target":"PadreAnimal","kind":"ASSOCIATION","sourceMultiplicity":"1","targetMultiplicity":"0..1"},
                  {"source":"Animal","target":"Animal","kind":"ASSOCIATION","sourceMultiplicity":"0..*","targetMultiplicity":"0..1"}],
                "audit":["parentesco","conservacion"]}
                """);
        NormalizadorParentescoPlan.simplificar(p);
        assertThat(p.path("classes")).hasSize(1);
        assertThat(p.path("classes").get(0).path("attributes")).extracting(a -> a.path("name").asText())
                .containsExactly("idAnimal", "fechaNacimiento", "nombre");
        assertThat(p.path("relations")).hasSize(1);
        assertThat(p.path("relations").get(0).path("targetRole").asText()).isEqualTo("padre");
    }

    @Test
    void padre_animal_con_otras_relaciones_tambien_se_fusiona_y_las_pasa_a_animal() throws Exception {
        // Ganaderia, 2026-11-08: PadreAnimal y MadreAnimal unidas a Animal y a Raza quedaban como clases.
        ObjectNode p = (ObjectNode) mapper.readTree("""
                {"classes":[
                  {"name":"Animal","attributes":[{"name":"idAnimal","dataType":"uuid","pk":true}]},
                  {"name":"Raza","attributes":[{"name":"idRaza","dataType":"uuid","pk":true},{"name":"nombre","dataType":"string"}]},
                  {"name":"Lote","attributes":[{"name":"idLote","dataType":"uuid","pk":true},{"name":"nombre","dataType":"string"}]},
                  {"name":"PadreAnimal","attributes":[{"name":"idPadreAnimal","dataType":"uuid","pk":true}]},
                  {"name":"MadreAnimal","attributes":[{"name":"idMadreAnimal","dataType":"uuid","pk":true}]}],
                "relations":[
                  {"source":"Raza","target":"Animal","kind":"ASSOCIATION","sourceMultiplicity":"1","targetMultiplicity":"0..*"},
                  {"source":"PadreAnimal","target":"Animal","kind":"ASSOCIATION","sourceMultiplicity":"0..1","targetMultiplicity":"0..*"},
                  {"source":"PadreAnimal","target":"Raza","kind":"ASSOCIATION","sourceMultiplicity":"0..*","targetMultiplicity":"1"},
                  {"source":"MadreAnimal","target":"Animal","kind":"ASSOCIATION","sourceMultiplicity":"0..1","targetMultiplicity":"0..*"},
                  {"source":"MadreAnimal","target":"Lote","kind":"ASSOCIATION","sourceMultiplicity":"0..*","targetMultiplicity":"1"}],
                "audit":["parentesco","conservacion"]}
                """);
        NormalizadorParentescoPlan.simplificar(p);
        assertThat(p.path("classes")).extracting(c -> c.path("name").asText()).containsExactly("Animal", "Raza", "Lote");
        List<String> relaciones = new java.util.ArrayList<>();
        for (var r : p.path("relations")) relaciones.add(r.path("source").asText() + "-" + r.path("target").asText()
                + ":" + r.path("targetRole").asText());
        // Raza-Animal ya existia (no se duplica); Lote pasa a Animal; padre y madre quedan como recursivas.
        assertThat(relaciones).containsExactlyInAnyOrder("Raza-Animal:", "Animal-Lote:", "Animal-Animal:padre", "Animal-Animal:madre");
    }

    @Test
    void no_convierte_subclases_en_roles_familiares() throws Exception {
        ObjectNode p = plan();
        ((ObjectNode) p.path("relations").get(0)).put("kind", "GENERALIZATION");
        assertThat(NormalizadorParentescoPlan.puedeSimplificar(p, p.path("classes").get(1))).isFalse();
    }

    @Test
    void calcula_el_presupuesto_despues_de_simplificar_roles_redundantes() throws Exception {
        ObjectNode p = plan();
        var cs = (com.fasterxml.jackson.databind.node.ArrayNode) p.path("classes");
        var rs = (com.fasterxml.jackson.databind.node.ArrayNode) p.path("relations");
        for (int i = 0; i < 10; i++) {
            cs.addObject().put("name", "Concepto" + i).putArray("attributes")
                    .addObject().put("name", "nombre").put("dataType", "string");
            rs.addObject().put("source", "Animal").put("target", "Concepto" + i)
                    .put("kind", "ASSOCIATION").put("sourceMultiplicity", "1").put("targetMultiplicity", "0..*");
        }
        assertThat(ProveedorOllama.errorEstructuralDelPlan(new ComandoPropuestoIA(EsquemaPlanUml.NOMBRE, p))).isNull();
        assertThat(p.path("classes")).hasSize(13);
    }

    private ObjectNode plan() throws Exception {
        return (ObjectNode) mapper.readTree("""
                {"classes":[
                  {"name":"Animal","attributes":[{"name":"codigo","dataType":"string"}]},
                  {"name":"Padre","attributes":[{"name":"idPadre","dataType":"uuid","pk":true}]},
                  {"name":"MadreAnimal","attributes":[{"name":"codigo","dataType":"string"}]}],
                "relations":[
                  {"source":"Animal","target":"Padre","kind":"ASSOCIATION","sourceMultiplicity":"1","targetMultiplicity":"1"},
                  {"source":"MadreAnimal","target":"Animal","kind":"ASSOCIATION","sourceMultiplicity":"1","targetMultiplicity":"0..*"}],
                "audit":["parentesco","conservacion"]}
                """);
    }
}
