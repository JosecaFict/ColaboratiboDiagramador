package com.uagrm.colabmodel.versiones;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.uagrm.colabmodel.modelcore.metamodel.ErKind;
import com.uagrm.colabmodel.modelcore.metamodel.InheritanceStrategy;
import com.uagrm.colabmodel.modelcore.metamodel.NotationView;
import com.uagrm.colabmodel.modelcore.metamodel.Visibility;
import com.uagrm.colabmodel.project.DiagramSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contrato v1, rebanada (b), en versiones: metodos con isStatic e isAbstract.
 *
 * Lo central es que una version guardada ANTES de que existieran las banderas no
 * traiga una diferencia falsa contra el modelo vivo (ausente = false), y que las
 * banderas sobrevivan a diferencias, fusion y restauracion. Que el plan se aplique por
 * el bus contra la base real lo prueba VersionesTest (necesita PostgreSQL: pendiente).
 */
class MetodosEstaticosYAbstractosEnVersionesTest {

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private final UUID diagrama = UUID.randomUUID();
    private final UUID figura = UUID.randomUUID();
    private final UUID area = UUID.randomUUID();

    /** Una foto como la guarda un commit: una clase abstracta con un metodo. `banderas` null = foto anterior a (b). */
    private ObjectNode foto(Boolean estatico, Boolean abstracto, String retorno) {
        ObjectNode raiz = JSON.objectNode();
        raiz.put("id", diagrama.toString());
        ArrayNode clases = raiz.putArray("classes");
        ObjectNode c = clases.addObject();
        c.put("id", figura.toString()).put("name", "Figura").put("isAbstract", true).put("weak", false)
                .put("erKind", "STRONG").put("inheritanceStrategy", "JOINED");
        c.putArray("attributes");
        ObjectNode m = c.putArray("methods").addObject();
        m.put("id", area.toString()).put("classId", figura.toString()).put("name", "area")
                .put("returnType", retorno).put("visibility", "PUBLIC").put("ordinal", 0);
        m.putArray("params");
        if (estatico != null) m.put("isStatic", estatico);
        if (abstracto != null) m.put("isAbstract", abstracto);
        raiz.putArray("relationships");
        raiz.putArray("layout");
        raiz.putArray("decisions");
        return raiz;
    }

    /** El mismo modelo como lo lee el servidor del modelo vivo. */
    private Foto vivo(boolean estatico, boolean abstracto) {
        var metodo = new DiagramSnapshot.Metodo(area, figura, "area", "double", List.of(), Visibility.PUBLIC, 0,
                estatico, abstracto);
        var clase = new DiagramSnapshot.Clase(figura, diagrama, "Figura", null, true, false, ErKind.STRONG,
                InheritanceStrategy.JOINED, List.of(), List.of(metodo));
        return Foto.de(new DiagramSnapshot(diagrama, "D", 1, NotationView.CLASS, List.of(clase), List.of(),
                List.of()), List.of());
    }

    @Test
    void una_version_anterior_sin_banderas_no_da_diferencias_falsas_contra_el_modelo_vivo() {
        Foto vieja = Foto.leer(foto(null, null, "double"));

        // Ausente es false, no null: sin esto Diferencias veria un cambio que nadie hizo.
        assertThat(vieja.metodos.get(area).campo("isStatic").isBoolean()).isTrue();
        assertThat(vieja.metodos.get(area).campo("isStatic").asBoolean()).isFalse();
        assertThat(vieja.metodos.get(area).campo("isAbstract").asBoolean()).isFalse();
        assertThat(Diferencias.vacio(Diferencias.entre(vieja, vivo(false, false)))).isTrue();
    }

    @Test
    void una_version_anterior_se_restaura_sin_tocar_el_metodo_si_no_cambio() {
        Foto vieja = Foto.leer(foto(null, null, "double"));

        assertThat(PlanDeCambios.de(vivo(false, false), vieja)).isEmpty();
    }

    @Test
    void las_banderas_sobreviven_a_guardar_y_releer() {
        Foto nueva = Foto.leer(foto(true, false, "double"));
        Foto releida = Foto.leer(nueva.aJson());

        assertThat(releida.metodos.get(area).campo("isStatic").asBoolean()).isTrue();
        assertThat(releida.metodos.get(area).campo("isAbstract").asBoolean()).isFalse();
        assertThat(Diferencias.vacio(Diferencias.entre(nueva, releida))).isTrue();
    }

    @Test
    void cambiar_una_bandera_aparece_como_cambio_de_ese_campo() {
        ObjectNode d = Diferencias.entre(Foto.leer(foto(false, false, "double")), Foto.leer(foto(true, false, "double")));

        assertThat(d.at("/metodos/modificadas/0/cambios/0/campo").asText()).isEqualTo("isStatic");
        assertThat(d.at("/metodos/modificadas/0/cambios/0/antes").asBoolean()).isFalse();
        assertThat(d.at("/metodos/modificadas/0/cambios/0/despues").asBoolean()).isTrue();
    }

    @Test
    void restaurar_una_version_con_otra_bandera_quita_y_agrega_el_metodo_llevando_las_banderas() {
        List<ObjectNode> plan = PlanDeCambios.de(Foto.leer(foto(false, false, "double")),
                Foto.leer(foto(true, false, "double")));

        assertThat(plan).extracting(c -> c.path("type").asText()).containsExactly("REMOVE_METHOD", "ADD_METHOD");
        JsonNode agregado = plan.get(1).path("payload");
        assertThat(agregado.path("isStatic").asBoolean()).isTrue();
        assertThat(agregado.path("isAbstract").asBoolean()).isFalse();
    }

    @Test
    void la_fusion_combina_una_bandera_de_un_lado_con_otro_campo_del_otro() {
        Foto base = Foto.leer(foto(false, false, "double"));
        Foto nuestro = Foto.leer(foto(true, false, "double"));
        Foto suyo = Foto.leer(foto(false, false, "int"));

        var r = Fusion.combinar(base, nuestro, suyo, Map.of());

        assertThat(r.conflictos()).isEmpty();
        assertThat(r.combinada().metodos.get(area).campo("isStatic").asBoolean()).isTrue();
        assertThat(r.combinada().metodos.get(area).texto("returnType")).isEqualTo("int");
    }

    @Test
    void la_misma_bandera_cambiada_distinto_en_los_dos_lados_es_conflicto() {
        Foto base = Foto.leer(foto(false, false, "double"));
        Foto nuestro = Foto.leer(foto(true, false, "double"));
        Foto suyo = Foto.leer(foto(false, true, "double"));

        var r = Fusion.combinar(base, nuestro, suyo, Map.of());

        // Cada uno cambio una bandera distinta: no hay conflicto de campo (V2 lo vuelve a exigir el bus al aplicar).
        assertThat(r.conflictos()).isEmpty();
        assertThat(r.combinada().metodos.get(area).campo("isStatic").asBoolean()).isTrue();
        assertThat(r.combinada().metodos.get(area).campo("isAbstract").asBoolean()).isTrue();
    }
}
