package com.uagrm.colabmodel.ai;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class RevisorHerenciaPlanTest {
    private static final JsonNodeFactory J = JsonNodeFactory.instance;

    @Test
    void detecta_direccion_invertida_contra_la_declaracion_del_modelo() {
        ObjectNode p = plan();
        herencia(p, "Producto", "Computadora");
        ((com.fasterxml.jackson.databind.node.ArrayNode) p.path("audit"))
                .add("Computadora es subclase de Producto (GENERALIZATION).");
        assertThat(RevisorHerenciaPlan.revisar(p, "Ventas")).anyMatch(e -> e.contains("Herencia invertida"));
    }

    @Test
    void acepta_subclase_hacia_superclase_sin_inferir_por_nombres() {
        ObjectNode p = plan();
        herencia(p, "Computadora", "Producto");
        assertThat(RevisorHerenciaPlan.revisar(p, "Computadora hereda de Producto")).isEmpty();
    }

    @Test
    void detecta_ciclos_indirectos_y_autorreferencia() {
        ObjectNode p = plan();
        herencia(p, "A", "B"); herencia(p, "B", "C"); herencia(p, "C", "A");
        assertThat(RevisorHerenciaPlan.revisar(p, "")).anyMatch(e -> e.contains("Ciclo"));
        ObjectNode auto = plan(); herencia(auto, "Animal", "Animal");
        assertThat(RevisorHerenciaPlan.revisar(auto, "")).anyMatch(e -> e.contains("si misma"));
    }

    private static ObjectNode plan() {
        ObjectNode p = J.objectNode(); p.putArray("relations"); p.putArray("audit"); return p;
    }
    private static void herencia(ObjectNode p, String s, String t) {
        ((com.fasterxml.jackson.databind.node.ArrayNode) p.path("relations")).addObject()
                .put("source", s).put("target", t).put("kind", "GENERALIZATION");
    }
}
