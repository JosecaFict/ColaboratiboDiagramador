package com.uagrm.colabmodel.versiones;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contrato v1, rebanada (a), en versiones: lo que se puede afirmar sin PostgreSQL.
 *
 * Un snapshot guardado ANTES de la rebanada (sin interfaces ni REALIZATION) tiene
 * que abrir igual, y uno con interfaz y realizacion tiene que sobrevivir a guardar,
 * releer, comparar y restaurar. Que el plan se aplique por el bus contra la base
 * real lo prueba VersionesTest (necesita PostgreSQL: pendiente de turno).
 */
class InterfazYRealizacionEnVersionesTest {

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private final UUID repo = UUID.randomUUID();
    private final UUID impl = UUID.randomUUID();
    private final UUID rel = UUID.randomUUID();

    /** Una foto minima escrita como la guarda un commit: dos clases y una relacion. */
    private ObjectNode foto(String estereotipoDeRepo, String tipoDeRelacion, boolean conCamposNuevos) {
        ObjectNode raiz = JSON.objectNode();
        raiz.put("id", UUID.randomUUID().toString());
        ArrayNode clases = raiz.putArray("classes");
        clase(clases, repo, "Repositorio", estereotipoDeRepo);
        clase(clases, impl, "RepoLibros", null);
        ArrayNode relaciones = raiz.putArray("relationships");
        ObjectNode r = relaciones.addObject();
        r.put("id", rel.toString()).put("sourceClassId", impl.toString()).put("targetClassId", repo.toString())
                .put("kind", tipoDeRelacion).put("identifying", false).put("degree", 2);
        if (conCamposNuevos) r.put("name", "implementa");
        raiz.putArray("layout");
        raiz.putArray("decisions");
        return raiz;
    }

    private void clase(ArrayNode clases, UUID id, String nombre, String estereotipo) {
        ObjectNode c = clases.addObject();
        c.put("id", id.toString()).put("name", nombre).put("isAbstract", false).put("weak", false)
                .put("erKind", "STRONG").put("inheritanceStrategy", "JOINED");
        if (estereotipo != null) c.put("stereotype", estereotipo);
        c.putArray("attributes");
        c.putArray("methods");
    }

    @Test
    void un_snapshot_anterior_sin_interfaces_ni_realizaciones_sigue_abriendo() {
        Foto vieja = Foto.leer(foto(null, "ASSOCIATION", false));

        assertThat(vieja.clases).hasSize(2);
        assertThat(vieja.clases.get(repo).texto("stereotype")).isNull();
        assertThat(vieja.relaciones.get(rel).texto("kind")).isEqualTo("ASSOCIATION");
        // Guardada y releida es la misma foto: no hay diferencias falsas.
        assertThat(Diferencias.vacio(Diferencias.entre(vieja, Foto.leer(vieja.aJson())))).isTrue();
    }

    @Test
    void un_snapshot_con_interfaz_y_realizacion_conserva_ambas_al_guardar_y_releer() {
        Foto nueva = Foto.leer(foto("interface", "REALIZATION", true));
        Foto releida = Foto.leer(nueva.aJson());

        assertThat(releida.clases.get(repo).texto("stereotype")).isEqualTo("interface");
        assertThat(releida.relaciones.get(rel).texto("kind")).isEqualTo("REALIZATION");
        assertThat(Diferencias.vacio(Diferencias.entre(nueva, releida))).isTrue();
    }

    @Test
    void cambiar_asociacion_por_realizacion_aparece_como_cambio_del_campo_kind() {
        Foto antes = Foto.leer(foto("interface", "ASSOCIATION", false));
        Foto despues = Foto.leer(foto("interface", "REALIZATION", false));

        ObjectNode d = Diferencias.entre(antes, despues);

        assertThat(d.at("/relaciones/modificadas/0/cambios/0/campo").asText()).isEqualTo("kind");
        assertThat(d.at("/relaciones/modificadas/0/cambios/0/antes").asText()).isEqualTo("ASSOCIATION");
        assertThat(d.at("/relaciones/modificadas/0/cambios/0/despues").asText()).isEqualTo("REALIZATION");
    }

    @Test
    void restaurar_una_version_con_realizacion_actualiza_el_tipo_sin_recrear_las_clases() {
        Foto actual = Foto.leer(foto("interface", "ASSOCIATION", false));
        Foto deseada = Foto.leer(foto("interface", "REALIZATION", false));

        List<ObjectNode> plan = PlanDeCambios.de(actual, deseada);

        assertThat(plan).extracting(c -> c.path("type").asText()).containsExactly("UPDATE_RELATION");
        assertThat(plan.get(0).at("/payload/kind").asText()).isEqualTo("REALIZATION");
    }

    @Test
    void restaurar_una_interfaz_recrea_la_clase_antes_que_su_realizacion() {
        // En la version anterior Repositorio era una clase comun: cambiar el estereotipo la recrea,
        // y la realizacion se crea DESPUES, cuando la interfaz ya existe (si no, V1 la rechazaria).
        Foto actual = Foto.leer(foto(null, "ASSOCIATION", false));
        Foto deseada = Foto.leer(foto("interface", "REALIZATION", false));

        List<String> tipos = PlanDeCambios.de(actual, deseada).stream().map(c -> c.path("type").asText()).toList();

        int crearInterfaz = indiceDe(PlanDeCambios.de(actual, deseada), "CREATE_CLASS", "classId", repo);
        int crearRealizacion = indiceDe(PlanDeCambios.de(actual, deseada), "CREATE_RELATION", "relationId", rel);
        assertThat(tipos).contains("DELETE_CLASS", "CREATE_CLASS", "CREATE_RELATION");
        assertThat(crearInterfaz).isGreaterThanOrEqualTo(0);
        assertThat(crearRealizacion).isGreaterThan(crearInterfaz);
        JsonNode payloadRelacion = PlanDeCambios.de(actual, deseada).get(crearRealizacion).path("payload");
        assertThat(payloadRelacion.path("kind").asText()).isEqualTo("REALIZATION");
    }

    private static int indiceDe(List<ObjectNode> plan, String tipo, String campoId, UUID id) {
        for (int i = 0; i < plan.size(); i++) {
            if (plan.get(i).path("type").asText().equals(tipo)
                    && plan.get(i).path("payload").path(campoId).asText().equals(id.toString())) return i;
        }
        return -1;
    }
}
