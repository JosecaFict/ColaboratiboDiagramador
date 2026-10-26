package com.uagrm.colabmodel.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.uagrm.colabmodel.modelcore.command.Lote;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cada caso usa un dominio distinto a proposito: el normalizador no puede
 * depender de ningun vocabulario, solo de la estructura del plan y de lo
 * que el modelo declaro.
 */
class NormalizadorPlanUmlTest {

    private static final JsonNodeFactory J = JsonNodeFactory.instance;

    @Test
    void limpia_identificadores_y_asegura_identidad_en_una_tienda() {
        ObjectNode plan = plan();
        ObjectNode categoria = clase(plan, "Categoría de producto", false);
        atributo(categoria, "nombre", "string", true).put("unique", true);
        ObjectNode producto = clase(plan, "Producto", false);
        atributo(producto, "códigoBarras", "string", true).put("pk", true);
        relacion(plan, "Categoría de producto", "Producto", "ASSOCIATION", "1", "0..*");

        JsonNode r = normalizar(plan);

        assertThat(clase(r, 0).path("name").asText()).isEqualTo("CategoriaDeProducto");
        assertThat(atributo(r, 0, "idCategoriaDeProducto").path("pk").asBoolean()).isTrue();
        assertThat(atributo(r, 0, "nombre").path("unique").asBoolean()).isFalse();
        assertThat(atributo(r, 1, "codigoBarras").path("pk").asBoolean()).isFalse();
        assertThat(atributo(r, 1, "codigoBarras").path("unique").asBoolean()).isTrue();
        assertThat(atributo(r, 1, "idProducto").path("pk").asBoolean()).isTrue();
        assertThat(r.path("relations").get(0).path("source").asText()).isEqualTo("CategoriaDeProducto");
    }

    @Test
    void respeta_el_nombre_de_relacion_que_eligio_el_modelo() {
        ObjectNode plan = plan();
        atributo(clase(plan, "Paciente", false), "nombre", "string", true);
        atributo(clase(plan, "Cita", false), "fecha", "datetime", true);
        atributo(clase(plan, "Medico", false), "matricula", "string", true);
        relacion(plan, "Paciente", "Cita", "ASSOCIATION", "1", "0..*").put("name", "agenda");
        relacion(plan, "Medico", "Cita", "ASSOCIATION", "1", "0..*").put("name", "Atendida por");
        relacion(plan, "Paciente", "Medico", "ASSOCIATION", "0..*", "0..*").put("name", "  ");

        JsonNode relaciones = normalizar(plan).path("relations");

        assertThat(relaciones.get(0).path("name").asText()).isEqualTo("agenda");
        assertThat(relaciones.get(1).path("name").asText()).isEqualTo("atendidaPor");
        assertThat(relaciones.get(2).has("name")).isFalse();
    }

    @Test
    void protege_el_historial_que_el_modelo_declaro_en_una_clinica() {
        ObjectNode plan = plan();
        atributo(clase(plan, "Consultorio", false), "numero", "int", true);
        atributo(clase(plan, "Medico", false), "matricula", "string", true);
        atributo(clase(plan, "Paciente", false), "nombre", "string", true);
        atributo(clase(plan, "Cita", true), "fecha", "datetime", true);
        relacion(plan, "Consultorio", "Medico", "COMPOSITION", "1", "0..*");
        relacion(plan, "Paciente", "Cita", "COMPOSITION", "1", "0..*");
        relacion(plan, "Medico", "Cita", "ASSOCIATION", "1", "1");
        plan.putArray("audit").add("Se usa COMPOSITION para el ciclo de vida").add("Se conserva el historial");

        JsonNode r = normalizar(plan);
        JsonNode relaciones = r.path("relations");

        // Medico es participante de Cita: borrar el Consultorio no puede arrastrarlo.
        assertThat(relaciones.get(0).path("kind").asText()).isEqualTo("ASSOCIATION");
        assertThat(relaciones.get(1).path("kind").asText()).isEqualTo("ASSOCIATION");
        assertThat(relaciones.get(2).path("targetMultiplicity").asText()).isEqualTo("0..*");
        assertThat(relaciones.get(2).path("sourceMultiplicity").asText()).isEqualTo("1");
        assertThat(r.path("audit").toString()).doesNotContain("COMPOSITION").contains("backend sustituyo");
    }

    @Test
    void sin_declaracion_no_adivina_historial_por_el_nombre() {
        ObjectNode plan = plan();
        atributo(clase(plan, "Copia", false), "codigo", "string", true);
        atributo(clase(plan, "Prestamo", false), "fechaDevolucion", "date", false);
        relacion(plan, "Copia", "Prestamo", "COMPOSITION", "1", "1");

        JsonNode relacion = normalizar(plan).path("relations").get(0);

        assertThat(relacion.path("kind").asText()).isEqualTo("COMPOSITION");
        assertThat(relacion.path("targetMultiplicity").asText()).isEqualTo("1");
    }

    @Test
    void un_hecho_historico_puede_ser_el_todo_de_sus_partes() {
        ObjectNode plan = plan();
        atributo(clase(plan, "Cliente", false), "email", "string", true);
        atributo(clase(plan, "Venta", true), "fecha", "datetime", true);
        atributo(clase(plan, "LineaDeVenta", false), "cantidad", "int", true);
        relacion(plan, "Venta", "LineaDeVenta", "COMPOSITION", "1", "1..*");
        relacion(plan, "Cliente", "Venta", "ASSOCIATION", "1", "1");

        JsonNode relaciones = normalizar(plan).path("relations");

        assertThat(relaciones.get(0).path("kind").asText()).isEqualTo("COMPOSITION");
        assertThat(relaciones.get(0).path("sourceMultiplicity").asText()).isEqualTo("1");
        assertThat(relaciones.get(1).path("targetMultiplicity").asText()).isEqualTo("0..*");
    }

    @Test
    void si_no_entra_en_el_tope_recorta_primero_lo_opcional() {
        ObjectNode plan = plan();
        ObjectNode ficha = clase(plan, "Ficha", false);
        atributo(ficha, "idFicha", "uuid", true).put("pk", true);
        for (int i = 0; i < 20; i++) atributo(ficha, "obligatorio" + i, "string", true);
        for (int i = 0; i < 15; i++) atributo(ficha, "opcional" + i, "string", false);

        JsonNode r = normalizar(plan);
        JsonNode atributos = clase(r, 0).path("attributes");

        assertThat(1 + atributos.size()).isEqualTo(Lote.TOPE_TEXTO_O_VOZ);
        assertThat(atributos.toString()).contains("\"obligatorio19\"", "\"idFicha\"").doesNotContain("\"opcional14\"");
        assertThat(r.path("audit").toString()).contains("opcional");
    }

    @Test
    void informa_las_clases_historicas_con_su_nombre_normalizado() {
        ObjectNode plan = plan();
        atributo(clase(plan, "Inscripción", true), "fecha", "date", true);
        atributo(clase(plan, "Curso", false), "nombre", "string", true);
        relacion(plan, "Curso", "Inscripción", "ASSOCIATION", "1", "0..*");

        RespuestaIA normalizada = NormalizadorPlanUml.normalizar(respuesta(plan));

        assertThat(NormalizadorPlanUml.clasesHistoricas(normalizada)).containsExactly("Inscripcion");
    }

    private static JsonNode normalizar(ObjectNode plan) {
        return NormalizadorPlanUml.normalizar(respuesta(plan)).comandos().getFirst().parametros();
    }

    private static RespuestaIA respuesta(ObjectNode plan) {
        return new RespuestaIA(List.of(new ComandoPropuestoIA(EsquemaPlanUml.NOMBRE, plan)), "");
    }

    private static ObjectNode plan() {
        ObjectNode plan = J.objectNode();
        plan.putArray("classes");
        plan.putArray("relations");
        return plan;
    }

    private static ObjectNode clase(ObjectNode plan, String nombre, boolean historica) {
        ObjectNode clase = ((ArrayNode) plan.path("classes")).addObject();
        clase.put("name", nombre).put("historica", historica).putArray("attributes");
        return clase;
    }

    private static ObjectNode atributo(ObjectNode clase, String nombre, String tipo, boolean requerido) {
        return ((ArrayNode) clase.path("attributes")).addObject().put("name", nombre).put("dataType", tipo)
                .put("required", requerido).put("unique", false).put("pk", false);
    }

    private static ObjectNode relacion(ObjectNode plan, String source, String target, String kind,
                                       String mSource, String mTarget) {
        return ((ArrayNode) plan.path("relations")).addObject().put("source", source).put("target", target)
                .put("kind", kind).put("sourceMultiplicity", mSource).put("targetMultiplicity", mTarget);
    }

    private static JsonNode clase(JsonNode plan, int indice) {
        return plan.path("classes").get(indice);
    }

    private static JsonNode atributo(JsonNode plan, int clase, String nombre) {
        for (JsonNode a : clase(plan, clase).path("attributes")) {
            if (a.path("name").asText().equals(nombre)) return a;
        }
        throw new AssertionError("No existe el atributo " + nombre + " en " + clase(plan, clase));
    }
}
