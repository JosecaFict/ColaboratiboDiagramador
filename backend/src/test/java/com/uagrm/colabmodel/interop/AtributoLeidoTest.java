package com.uagrm.colabmodel.interop;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contrato UML 2.5.1 v1, rebanada (c): el DTO de importacion lleva isStatic e isReadOnly del atributo.
 * Que el lector los llene (Rodolfo) y el servicio los pase a la propuesta no es de esta rebanada.
 */
class AtributoLeidoTest {

    @Test
    void los_constructores_anteriores_siguen_valiendo_con_las_dos_marcas_en_false() {
        var corto = new ModeloLeido.AtributoLeido("titulo", "string", false, false, false, false, false, false);
        var medio = new ModeloLeido.AtributoLeido("titulo", "string", false, false, false, false, false, false,
                "PRIVATE", "x");

        assertThat(corto.esEstatico()).isFalse();
        assertThat(corto.soloLectura()).isFalse();
        assertThat(medio.visibilidad()).isEqualTo("PRIVATE");
        assertThat(medio.esEstatico()).isFalse();
    }

    @Test
    void el_constructor_completo_conserva_las_dos_marcas() {
        var a = new ModeloLeido.AtributoLeido("MAX", "int", false, false, false, false, false, false,
                "PUBLIC", "10", true, true);

        assertThat(a.esEstatico()).isTrue();
        assertThat(a.soloLectura()).isTrue();
        assertThat(a.valorPorDefecto()).isEqualTo("10");
    }

    @Test
    void la_propuesta_las_transporta_y_los_constructores_anteriores_las_dejan_en_false() {
        var vieja = new PropuestaDeImportacion.AtributoPropuesto("t", "string", "string", false, false, false, false,
                false, false, false, null);
        var media = new PropuestaDeImportacion.AtributoPropuesto("t", "string", "string", false, false, false, false,
                false, false, false, null, "PRIVATE", null);
        var nueva = new PropuestaDeImportacion.AtributoPropuesto("MAX", "int", "int", false, false, false, false,
                false, false, false, null, "PUBLIC", "10", true, true);

        assertThat(vieja.esEstatico()).isFalse();
        assertThat(media.soloLectura()).isFalse();
        assertThat(nueva.esEstatico()).isTrue();
        assertThat(nueva.soloLectura()).isTrue();
    }
}
