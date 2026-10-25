package com.uagrm.colabmodel.ai;

import com.uagrm.colabmodel.modelcore.command.CommandType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El function-calling schema es el catalogo cerrado (ADR-014), no una lista
 * aparte. Estos tests documentan lo que EsquemaDeComandos.paraTipo ya
 * garantiza en tiempo de compilacion: un switch sin `default` sobre
 * CommandType deja de compilar en cuanto se agrega un comando nuevo, hasta
 * que alguien decide explicitamente si la IA lo puede llamar.
 */
class EsquemaDeComandosTest {

    /** CONFIRM_SCHEMA: confirmar la base es la firma de una persona (ADR-032). */
    private static final Set<String> EXCLUIDOS_A_POSTA =
            Set.of("BATCH", "MOVE_ELEMENT", "RESIZE_ELEMENT", "SET_MODELING_DECISION", "CONFIRM_SCHEMA");

    @Test
    void expone_una_herramienta_por_cada_comando_de_edicion_atomica() {
        Set<String> nombres = EsquemaDeComandos.herramientas().stream()
                .map(HerramientaIA::nombre).collect(Collectors.toSet());

        assertThat(nombres).containsExactlyInAnyOrder("CREATE_CLASS", "RENAME_CLASS", "DELETE_CLASS",
                "ADD_ATTRIBUTE", "UPDATE_ATTRIBUTE", "REMOVE_ATTRIBUTE", "ADD_METHOD", "REMOVE_METHOD",
                "CREATE_RELATION", "UPDATE_RELATION", "DELETE_RELATION", "SET_ER_FLAG");
    }

    @Test
    void nunca_expone_un_comando_de_generacion_masiva_ni_de_geometria() {
        Set<String> nombres = EsquemaDeComandos.herramientas().stream()
                .map(HerramientaIA::nombre).collect(Collectors.toSet());

        assertThat(nombres).doesNotContainAnyElementsOf(EXCLUIDOS_A_POSTA);
    }

    @Test
    void el_catalogo_completo_queda_cubierto_una_sola_vez() {
        List<HerramientaIA> herramientas = EsquemaDeComandos.herramientas();
        for (CommandType tipo : CommandType.values()) {
            boolean esHerramienta = herramientas.stream().anyMatch(h -> h.nombre().equals(tipo.name()));
            assertThat(esHerramienta).as("tipo " + tipo).isEqualTo(!EXCLUIDOS_A_POSTA.contains(tipo.name()));
        }
    }

    @Test
    void cada_herramienta_declara_un_objeto_con_campos_requeridos() {
        for (HerramientaIA h : EsquemaDeComandos.herramientas()) {
            assertThat(h.parametros().path("type").asText()).as(h.nombre()).isEqualTo("object");
            assertThat(h.parametros().path("required").size()).as(h.nombre()).isGreaterThan(0);
            assertThat(h.descripcion()).as(h.nombre()).isNotBlank();
        }
    }

    @Test
    void documenta_sin_ambiguedad_los_extremos_de_una_relacion() {
        HerramientaIA relacion = EsquemaDeComandos.herramientas().stream()
                .filter(h -> h.nombre().equals("CREATE_RELATION")).findFirst().orElseThrow();

        assertThat(relacion.descripcion()).contains("Book [1] -- [0..*] Copy")
                .contains("sourceMultiplicity=1").contains("targetMultiplicity=0..*");
        assertThat(relacion.parametros().path("properties").path("sourceMultiplicity")
                .path("description").asText()).contains("claseOrigen").contains("claseDestino");
    }
}
