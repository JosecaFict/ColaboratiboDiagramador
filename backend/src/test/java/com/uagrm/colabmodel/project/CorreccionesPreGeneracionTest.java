package com.uagrm.colabmodel.project;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Las correcciones antes de generar no conocen ningun dominio. */
class CorreccionesPreGeneracionTest {

    @Test
    void sugiere_un_nombre_solo_si_falta_o_es_de_relleno() {
        assertThat(DiagramSnapshotService.nombreSugerido(null, "ASSOCIATION", "Cita", "0..*", null))
                .isEqualTo("tieneCitas");
        assertThat(DiagramSnapshotService.nombreSugerido("association", "ASSOCIATION", "Medico", "1", null))
                .isEqualTo("tieneMedico");
        assertThat(DiagramSnapshotService.nombreSugerido("relacionaConAutores", "ASSOCIATION", "Autor", "1..*", null))
                .isEqualTo("tieneAutores");
        assertThat(DiagramSnapshotService.nombreSugerido("agenda", "ASSOCIATION", "Cita", "0..*", null)).isNull();
    }

    @Test
    void prefiere_el_rol_del_destino_y_respeta_la_herencia() {
        assertThat(DiagramSnapshotService.nombreSugerido("", "ASSOCIATION", "Persona", "1", "Titular de cuenta"))
                .isEqualTo("titularDeCuenta");
        assertThat(DiagramSnapshotService.nombreSugerido(null, "GENERALIZATION", "Persona", null, null)).isNull();
    }

    @Test
    void pluraliza_con_las_reglas_regulares_del_espanol() {
        assertThat(DiagramSnapshotService.nombreSugerido(null, "COMPOSITION", "Luz", "*", null)).isEqualTo("tieneLuces");
        assertThat(DiagramSnapshotService.nombreSugerido(null, "ASSOCIATION", "Producto", "0..*", null))
                .isEqualTo("tieneProductos");
    }
}
