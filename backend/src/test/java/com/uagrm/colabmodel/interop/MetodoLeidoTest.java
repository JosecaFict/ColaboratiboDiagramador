package com.uagrm.colabmodel.interop;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contrato UML 2.5.1 v1, rebanada (b): el DTO de importacion lleva visibilidad, isStatic e isAbstract.
 * Que el lector los llene (E01) y el servicio los pase a la propuesta es de Rodolfo; aqui solo el contrato.
 */
class MetodoLeidoTest {

    @Test
    void el_constructor_anterior_sigue_valiendo_con_valores_por_defecto() {
        var m = new ModeloLeido.MetodoLeido("pagar", "void", List.of());

        assertThat(m.visibilidad()).isNull();
        assertThat(m.esEstatico()).isFalse();
        assertThat(m.esAbstracto()).isFalse();
    }

    @Test
    void el_constructor_completo_conserva_las_tres_marcas() {
        var m = new ModeloLeido.MetodoLeido("crear", "Figura", List.of(), "PROTECTED", true, false);

        assertThat(m.visibilidad()).isEqualTo("PROTECTED");
        assertThat(m.esEstatico()).isTrue();
        assertThat(m.esAbstracto()).isFalse();
    }

    @Test
    void la_propuesta_las_transporta_y_el_constructor_anterior_las_deja_en_su_valor_por_defecto() {
        var vieja = new PropuestaDeImportacion.MetodoPropuesto("pagar", "void", List.of(), false);
        var nueva = new PropuestaDeImportacion.MetodoPropuesto("area", "double", List.of(), false, "PUBLIC", false, true);

        assertThat(vieja.esEstatico()).isFalse();
        assertThat(vieja.esAbstracto()).isFalse();
        assertThat(nueva.esAbstracto()).isTrue();
        assertThat(nueva.visibilidad()).isEqualTo("PUBLIC");
    }
}
