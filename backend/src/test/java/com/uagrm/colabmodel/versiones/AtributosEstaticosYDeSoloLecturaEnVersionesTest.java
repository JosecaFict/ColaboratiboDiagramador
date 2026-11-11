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
 * Contrato v1, rebanada (c), en versiones: atributos con isStatic y readOnly. Una version guardada ANTES
 * de que existieran las banderas no debe traer una diferencia falsa contra el modelo vivo (ausente = false).
 * Que el plan se aplique por el bus contra la base real lo prueba VersionesTest (necesita PostgreSQL).
 */
class AtributosEstaticosYDeSoloLecturaEnVersionesTest {

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private final UUID diagrama = UUID.randomUUID();
    private final UUID libro = UUID.randomUUID();
    private final UUID total = UUID.randomUUID();

    /** Una foto como la guarda un commit. `estatico` y `lectura` null = foto anterior a (c). */
    private ObjectNode foto(Boolean estatico, Boolean lectura, String tipo, boolean conAtributo) {
        ObjectNode raiz = JSON.objectNode();
        raiz.put("id", diagrama.toString());
        ArrayNode clases = raiz.putArray("classes");
        ObjectNode c = clases.addObject();
        c.put("id", libro.toString()).put("name", "Libro").put("isAbstract", false).put("weak", false)
                .put("erKind", "STRONG").put("inheritanceStrategy", "JOINED");
        ArrayNode atributos = c.putArray("attributes");
        if (conAtributo) {
            ObjectNode a = atributos.addObject();
            a.put("id", total.toString()).put("classId", libro.toString()).put("name", "total")
                    .put("dataType", tipo).put("visibility", "PRIVATE").put("ordinal", 0);
            if (estatico != null) a.put("isStatic", estatico);
            if (lectura != null) a.put("readOnly", lectura);
        }
        c.putArray("methods");
        raiz.putArray("relationships");
        raiz.putArray("layout");
        raiz.putArray("decisions");
        return raiz;
    }

    private Foto leer(Boolean estatico, Boolean lectura, String tipo) {
        return Foto.leer(foto(estatico, lectura, tipo, true));
    }

    private Foto vivo(boolean estatico, boolean lectura) {
        var atributo = new DiagramSnapshot.Atributo(total, libro, "total", "string", Visibility.PRIVATE, null,
                false, false, false, 0, false, false, false, null, null, null, null, estatico, lectura);
        var clase = new DiagramSnapshot.Clase(libro, diagrama, "Libro", null, false, false, ErKind.STRONG,
                InheritanceStrategy.JOINED, List.of(atributo), List.of());
        return Foto.de(new DiagramSnapshot(diagrama, "D", 1, NotationView.CLASS, List.of(clase), List.of(),
                List.of()), List.of());
    }

    @Test
    void una_version_anterior_sin_banderas_no_da_diferencias_falsas_contra_el_modelo_vivo() {
        Foto vieja = leer(null, null, "string");

        assertThat(vieja.atributos.get(total).campo("isStatic").isBoolean()).isTrue();
        assertThat(vieja.atributos.get(total).campo("isStatic").asBoolean()).isFalse();
        assertThat(vieja.atributos.get(total).campo("readOnly").asBoolean()).isFalse();
        assertThat(Diferencias.vacio(Diferencias.entre(vieja, vivo(false, false)))).isTrue();
    }

    @Test
    void una_version_anterior_se_restaura_sin_tocar_el_atributo_si_no_cambio() {
        assertThat(PlanDeCambios.de(vivo(false, false), leer(null, null, "string"))).isEmpty();
    }

    @Test
    void las_banderas_sobreviven_a_guardar_y_releer() {
        Foto nueva = leer(true, true, "string");
        Foto releida = Foto.leer(nueva.aJson());

        assertThat(releida.atributos.get(total).campo("isStatic").asBoolean()).isTrue();
        assertThat(releida.atributos.get(total).campo("readOnly").asBoolean()).isTrue();
        assertThat(Diferencias.vacio(Diferencias.entre(nueva, releida))).isTrue();
    }

    @Test
    void cambiar_una_bandera_aparece_como_cambio_de_ese_campo() {
        ObjectNode d = Diferencias.entre(leer(false, false, "string"), leer(false, true, "string"));

        assertThat(d.at("/atributos/modificadas/0/cambios/0/campo").asText()).isEqualTo("readOnly");
        assertThat(d.at("/atributos/modificadas/0/cambios/0/despues").asBoolean()).isTrue();
    }

    @Test
    void restaurar_una_version_con_otra_bandera_actualiza_el_atributo_con_solo_esa_bandera() {
        List<ObjectNode> plan = PlanDeCambios.de(leer(false, false, "string"), leer(true, false, "string"));

        assertThat(plan).extracting(c -> c.path("type").asText()).containsExactly("UPDATE_ATTRIBUTE");
        JsonNode p = plan.get(0).path("payload");
        assertThat(p.path("isStatic").asBoolean()).isTrue();
        assertThat(p.has("readOnly")).isFalse();
    }

    @Test
    void agregar_en_la_restauracion_lleva_las_dos_banderas() {
        Foto sinAtributo = Foto.leer(foto(null, null, "string", false));

        List<ObjectNode> plan = PlanDeCambios.de(sinAtributo, leer(true, true, "string"));

        assertThat(plan).extracting(c -> c.path("type").asText()).containsExactly("ADD_ATTRIBUTE");
        assertThat(plan.get(0).path("payload").path("isStatic").asBoolean()).isTrue();
        assertThat(plan.get(0).path("payload").path("readOnly").asBoolean()).isTrue();
    }

    @Test
    void la_fusion_combina_una_bandera_de_un_lado_con_otro_campo_del_otro() {
        var r = Fusion.combinar(leer(false, false, "string"), leer(true, false, "string"),
                leer(false, false, "int"), Map.of());

        assertThat(r.conflictos()).isEmpty();
        assertThat(r.combinada().atributos.get(total).campo("isStatic").asBoolean()).isTrue();
        assertThat(r.combinada().atributos.get(total).texto("dataType")).isEqualTo("int");
    }

    @Test
    void la_fusion_de_una_bandera_cambiada_en_un_solo_lado_no_es_conflicto() {
        Foto base = leer(false, false, "string");
        var r = Fusion.combinar(base, leer(false, true, "string"), base, Map.of());

        assertThat(r.conflictos()).isEmpty();
        assertThat(r.combinada().atributos.get(total).campo("readOnly").asBoolean()).isTrue();
    }
}
