package com.uagrm.colabmodel.versiones;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La identidad logica de los elementos entre ramas (ADR-037): id fisico de la
 * rama = id logico XOR sal.
 */
class IdentidadLogicaTest {

    @RepeatedTest(50)
    void la_sal_no_toca_version_ni_variante_y_el_id_de_la_rama_sigue_siendo_v4() {
        UUID sal = IdentidadLogica.nuevaSal();
        UUID logico = UUID.randomUUID();

        UUID enLaRama = IdentidadLogica.aFisico(logico, sal);

        assertThat(IdentidadLogica.esSalValida(sal)).isTrue();
        assertThat(enLaRama.version()).isEqualTo(4);
        assertThat(enLaRama.variant()).isEqualTo(2);
        assertThat(enLaRama).isNotEqualTo(logico);
        // Pasa por texto y vuelve igual: es un UUID comun para Postgres, Java y JS.
        assertThat(UUID.fromString(enLaRama.toString())).isEqualTo(enLaRama);
    }

    @Test
    void ida_y_vuelta_devuelve_el_mismo_id() {
        UUID sal = IdentidadLogica.nuevaSal();
        UUID logico = UUID.randomUUID();

        assertThat(IdentidadLogica.aLogico(IdentidadLogica.aFisico(logico, sal), sal)).isEqualTo(logico);
    }

    @Test
    void en_un_diagrama_raiz_el_id_fisico_es_el_logico() {
        UUID id = UUID.randomUUID();

        assertThat(IdentidadLogica.aFisico(id, null)).isEqualTo(id);
        assertThat(IdentidadLogica.aLogico(id, null)).isEqualTo(id);
    }

    @Test
    void una_rama_de_una_rama_reconoce_el_elemento_de_la_raiz() {
        UUID salRama = IdentidadLogica.nuevaSal();
        UUID salNieta = IdentidadLogica.nuevaSal();
        UUID enMain = UUID.randomUUID();

        // main -> rama -> nieta, siempre traduciendo desde el diagrama de origen.
        UUID enLaRama = IdentidadLogica.traducir(enMain, null, salRama);
        UUID enLaNieta = IdentidadLogica.traducir(enLaRama, salRama, salNieta);

        assertThat(enLaNieta).isNotIn(enMain, enLaRama);
        // Y de la nieta directo a main, sin pasar por la rama.
        assertThat(IdentidadLogica.traducir(enLaNieta, salNieta, null)).isEqualTo(enMain);
        assertThat(IdentidadLogica.traducir(enLaNieta, salNieta, salRama)).isEqualTo(enLaRama);
    }

    @Test
    void un_elemento_creado_en_la_rama_se_mergea_a_main_sin_chocar_con_su_id() {
        UUID sal = IdentidadLogica.nuevaSal();
        // Lo crea alguien en la rama: un UUID cualquiera, sin pasar por la sal.
        UUID nuevoEnLaRama = UUID.randomUUID();

        UUID enMain = IdentidadLogica.traducir(nuevoEnLaRama, sal, null);

        // Otro id: las PK son globales y los dos tienen que poder existir.
        assertThat(enMain).isNotEqualTo(nuevoEnLaRama);
        assertThat(enMain.version()).isEqualTo(4);
        // Y si despues se mergea de vuelta a la rama, es el mismo elemento.
        assertThat(IdentidadLogica.traducir(enMain, null, sal)).isEqualTo(nuevoEnLaRama);
    }
}
