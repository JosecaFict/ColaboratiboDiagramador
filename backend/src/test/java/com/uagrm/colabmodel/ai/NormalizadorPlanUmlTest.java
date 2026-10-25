package com.uagrm.colabmodel.ai;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NormalizadorPlanUmlTest {

    private static final JsonNodeFactory J = JsonNodeFactory.instance;

    @Test
    void normaliza_identificadores_y_protege_el_historial_indirecto() {
        ObjectNode plan = J.objectNode();
        var clases = plan.putArray("classes");
        clase(clases.addObject(), "Obra", "idObra");
        clase(clases.addObject(), "Copia física", "códigoInventario");
        clase(clases.addObject(), "Préstamo", "fechaDevolución");
        clase(clases.addObject(), "Usuario", "identificacionUniversitaria");
        ObjectNode tituloNatural = (ObjectNode) clases.get(0).path("attributes").get(0);
        tituloNatural.put("name", "titulo").put("pk", true).put("unique", false);
        ObjectNode codigoNatural = (ObjectNode) clases.get(1).path("attributes").get(0);
        codigoNatural.put("pk", true).put("unique", false);
        ObjectNode devolucion = (ObjectNode) clases.get(2).path("attributes").get(0);
        devolucion.put("required", true);
        var relaciones = plan.putArray("relations");
        relacion(relaciones.addObject(), "Obra", "Copia física", "COMPOSITION");
        relacion(relaciones.addObject(), "Copia física", "Préstamo", "ASSOCIATION");
        plan.putArray("audit").add("Se usa COMPOSITION para el ciclo de vida").add("Se conserva el historial");

        RespuestaIA normalizada = NormalizadorPlanUml.normalizar(new RespuestaIA(
                List.of(new ComandoPropuestoIA(EsquemaPlanUml.NOMBRE, plan)), ""),
                "Debe gestionar devoluciones, vencimiento y obras con mas de un autor. "
                        + "Ningun ejemplar puede tener dos prestamos activos simultaneos. ISBN opcional.");
        var resultado = normalizada.comandos().getFirst().parametros();

        assertThat(resultado.path("classes").get(1).path("name").asText()).isEqualTo("CopiaFisica");
        assertThat(resultado.path("classes").get(1).path("attributes").toString())
                .contains("codigoInventario", "idCopiaFisica");
        assertThat(resultado.path("classes").get(2).path("name").asText()).isEqualTo("Prestamo");
        assertThat(resultado.path("classes").get(3).path("attributes").toString())
                .contains("idUsuario", "identificacionUniversitaria");
        assertThat(resultado.path("relations").get(0).path("kind").asText()).isEqualTo("ASSOCIATION");
        assertThat(resultado.path("relations").get(0).path("name").asText()).isEqualTo("tieneEjemplares");
        assertThat(resultado.path("relations").get(1).path("name").asText()).isEqualTo("registraPrestamos");
        assertThat(resultado.path("audit").toString()).doesNotContain("COMPOSITION").contains("backend sustituyo");
        assertThat(resultado.path("audit").toString()).contains("dos Prestamos activos");
        assertThat(resultado.path("classes").get(0).path("attributes").get(0).path("pk").asBoolean()).isTrue();
        assertThat(resultado.path("classes").get(0).path("attributes").toString())
                .contains("idObra", "\"titulo\"").contains("\"unique\":false", "\"pk\":false");
        assertThat(resultado.path("classes").get(1).path("attributes").toString())
                .contains("codigoInventario", "\"unique\":true", "\"pk\":false");
        assertThat(resultado.path("classes").get(2).path("attributes").toString())
                .contains("fechaDevolucion", "fechaVencimiento");
        assertThat(resultado.path("classes").get(2).path("attributes").toString())
                .contains("\"fechaDevolucion\",\"dataType\":\"string\",\"required\":false");
        assertThat(resultado.path("relations").get(1).path("targetMultiplicity").asText()).isEqualTo("0..*");
    }

    private static void clase(ObjectNode clase, String nombre, String atributo) {
        clase.put("name", nombre);
        clase.putArray("attributes").addObject().put("name", atributo).put("dataType", "string")
                .put("required", true).put("unique", false).put("pk", false);
    }

    private static void relacion(ObjectNode relacion, String source, String target, String kind) {
        relacion.put("source", source).put("target", target).put("kind", kind)
                .put("sourceMultiplicity", "1").put("targetMultiplicity", "0..*");
    }
}
