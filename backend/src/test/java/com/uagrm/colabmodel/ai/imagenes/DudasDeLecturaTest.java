package com.uagrm.colabmodel.ai.imagenes;

import com.uagrm.colabmodel.ai.PropuestaDeIA.LecturaDeImagen.Duda;
import com.uagrm.colabmodel.ai.PropuestaDeIA.LecturaDeImagen.TipoDeDuda;
import com.uagrm.colabmodel.modelcore.metamodel.RelationKind;
import com.uagrm.colabmodel.vision.DeteccionPropuesta;
import com.uagrm.colabmodel.vision.DeteccionPropuesta.ClaseDetectada;
import com.uagrm.colabmodel.vision.DeteccionPropuesta.RelacionDetectada;
import com.uagrm.colabmodel.vision.DeteccionPropuesta.RolDeTexto;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.uagrm.colabmodel.ai.imagenes.DeteccionesDePrueba.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Prueba 28 del contrato (docs/DISENO-CHAT-IMAGENES.md, 5.4): las dudas de una lectura de imagen. */
class DudasDeLecturaTest {

    private static List<TipoDeDuda> tipos(DudasDeLectura.Resultado r) {
        return r.dudas().stream().map(Duda::tipo).toList();
    }

    @Test
    void sin_nivel_2_hay_una_sola_duda_global_y_ninguna_texto_vacio() {
        // Aunque haya atributos sin texto, sin lectura de texto solo sale SIN_LECTURA_DE_TEXTO, una vez.
        ClaseDetectada c = new ClaseDetectada("c1", CAJA, CAJA, null, RecorteDeTextoVacio.nombre("c1"),
                List.of(RecorteDeTextoVacio.de("a1", RolDeTexto.ATRIBUTO), RecorteDeTextoVacio.de("a2", RolDeTexto.ATRIBUTO)),
                List.of(RecorteDeTextoVacio.de("m1", RolDeTexto.METODO)), 2, 0.9);

        DudasDeLectura.Resultado r = DudasDeLectura.calcular(deteccion(List.of(c, clase("c2", null, 0.9)), List.of()));

        assertThat(tipos(r)).containsExactly(TipoDeDuda.SIN_LECTURA_DE_TEXTO);
        assertThat(r.dudas().get(0).elementoRef()).isNull();
        assertThat(r.omitidas()).isZero();
    }

    @Test
    void el_nivel_1_solo_conserva_las_dudas_de_forma_y_de_tipo_de_relacion() {
        DeteccionPropuesta d = deteccion(List.of(clase("c1", null, 0.30), clase("c2", null, 0.9)),
                List.of(relacion("r1", "c1", "c2", RelationKind.ASSOCIATION, simbolo("s1", null, 0.0), 0.4)));

        DudasDeLectura.Resultado r = DudasDeLectura.calcular(d);

        assertThat(tipos(r)).containsExactly(TipoDeDuda.SIN_LECTURA_DE_TEXTO, TipoDeDuda.RELACION_SIN_TIPO,
                TipoDeDuda.RELACION_POCO_CLARA, TipoDeDuda.CLASE_POCO_CLARA);
        assertThat(r.dudas().get(1).recorteRef()).isEqualTo("s1");
        assertThat(r.dudas().get(1).elementoRef()).isEqualTo("r1");
    }

    @Test
    void con_nivel_2_marca_nombre_vacio_figura_sin_reconocer_y_texto_vacio_de_atributo_pero_no_de_cardinalidad() {
        ClaseDetectada conAtributos = claseConAtributos("c1", "Cliente", List.of(
                texto("c1-a1", RolDeTexto.ATRIBUTO, "id: int", 0.9),
                RecorteDeTextoVacio.de("c1-a2", RolDeTexto.ATRIBUTO)));
        RelacionDetectada rel = new RelacionDetectada("r1", "c1", "c2", RelationKind.ASSOCIATION, null,
                simbolo("r1-s", null, 0.0), RecorteDeTextoVacio.de("r1-ca", RolDeTexto.CARDINALIDAD),
                RecorteDeTextoVacio.de("r1-cb", RolDeTexto.ROL), 0.9);

        DudasDeLectura.Resultado r = DudasDeLectura.calcular(deteccion(
                List.of(conAtributos, clase("c2", null, 0.9)), List.of(rel)));

        assertThat(tipos(r)).containsExactly(TipoDeDuda.CLASE_SIN_NOMBRE, TipoDeDuda.RELACION_SIN_TIPO,
                TipoDeDuda.TEXTO_VACIO);
        Duda vacio = r.dudas().get(2);
        assertThat(vacio.rol()).isEqualTo(RolDeTexto.ATRIBUTO);
        assertThat(vacio.recorteRef()).isEqualTo("c1-a2");
        // La cardinalidad y el rol vacios NO se marcan: una relacion sin multiplicidad es valida.
        assertThat(r.dudas()).noneMatch(x -> x.recorteRef() != null && x.recorteRef().startsWith("r1-c"));
    }

    @Test
    void el_borde_del_texto_es_0_60_el_059_duda_y_el_060_no() {
        DeteccionPropuesta d = deteccion(List.of(claseConAtributos("c1", "Cliente", List.of(
                texto("a1", RolDeTexto.ATRIBUTO, "dudoso", 0.59),
                texto("a2", RolDeTexto.ATRIBUTO, "justo", 0.60)))), List.of());

        DudasDeLectura.Resultado r = DudasDeLectura.calcular(d);

        assertThat(r.dudas()).hasSize(1);
        assertThat(r.dudas().get(0).tipo()).isEqualTo(TipoDeDuda.TEXTO_INCIERTO);
        assertThat(r.dudas().get(0).leido()).isEqualTo("dudoso");
        assertThat(r.dudas().get(0).confianza()).isEqualTo(0.59);
    }

    @Test
    void el_borde_de_la_forma_es_0_50_el_049_duda_y_el_050_no() {
        DeteccionPropuesta d = deteccion(
                List.of(clase("c1", "A", 0.49), clase("c2", "B", 0.50), clase("c3", "C", 0.9)),
                List.of(relacion("r1", "c1", "c2", RelationKind.ASSOCIATION, null, 0.49),
                        relacion("r2", "c2", "c3", RelationKind.ASSOCIATION, null, 0.50)));

        DudasDeLectura.Resultado r = DudasDeLectura.calcular(d);

        assertThat(r.dudas()).extracting(Duda::tipo, Duda::elementoRef).containsExactly(
                org.assertj.core.groups.Tuple.tuple(TipoDeDuda.RELACION_POCO_CLARA, "r1"),
                org.assertj.core.groups.Tuple.tuple(TipoDeDuda.CLASE_POCO_CLARA, "c1"));
    }

    @Test
    void el_orden_es_por_gravedad_y_dentro_de_cada_una_por_confianza_ascendente() {
        DeteccionPropuesta d = deteccion(List.of(
                        claseConAtributos("c1", "Cliente", List.of(
                                texto("a1", RolDeTexto.ATRIBUTO, "x", 0.55),
                                texto("a2", RolDeTexto.ATRIBUTO, "y", 0.20),
                                RecorteDeTextoVacio.de("a3", RolDeTexto.METODO))),
                        clase("c2", null, 0.2),
                        clase("c3", "Hoja", 0.45)),
                List.of(relacion("r1", "c1", "c2", RelationKind.ASSOCIATION, simbolo("s1", null, 0.0), 0.3)));

        DudasDeLectura.Resultado r = DudasDeLectura.calcular(d);

        assertThat(tipos(r)).containsExactly(TipoDeDuda.CLASE_SIN_NOMBRE, TipoDeDuda.RELACION_SIN_TIPO,
                TipoDeDuda.TEXTO_VACIO, TipoDeDuda.TEXTO_INCIERTO, TipoDeDuda.TEXTO_INCIERTO,
                TipoDeDuda.RELACION_POCO_CLARA, TipoDeDuda.CLASE_POCO_CLARA, TipoDeDuda.CLASE_POCO_CLARA);
        // Los dos TEXTO_INCIERTO: primero el de 0.20 y despues el de 0.55.
        assertThat(r.dudas().subList(3, 5)).extracting(Duda::confianza).containsExactly(0.20, 0.55);
        // Las dos CLASE_POCO_CLARA: c2 (0.2) antes que c3 (0.45).
        assertThat(r.dudas().subList(6, 8)).extracting(Duda::elementoRef).containsExactly("c2", "c3");
    }

    @Test
    void el_tope_es_50_y_el_resto_se_cuenta_en_omitidas() {
        List<ClaseDetectada> clases = new ArrayList<>();
        clases.add(clase("c0", "Con texto", 0.9));   // para que haya lectura de texto
        for (int i = 1; i <= 60; i++) clases.add(clase("c" + i, "N" + i, 0.1 + i / 1000.0));

        DudasDeLectura.Resultado r = DudasDeLectura.calcular(deteccion(clases, List.of()));

        assertThat(r.dudas()).hasSize(50);
        assertThat(r.omitidas()).isEqualTo(10);
        assertThat(r.total()).isEqualTo(60);
        // Se quedan las 50 de menor confianza.
        assertThat(r.dudas().get(0).elementoRef()).isEqualTo("c1");
        assertThat(r.dudas().get(49).elementoRef()).isEqualTo("c50");
    }

    @Test
    void una_imagen_sin_dudas_devuelve_lista_vacia_no_nula() {
        DudasDeLectura.Resultado r = DudasDeLectura.calcular(conNombres());

        assertThat(r.dudas()).isEmpty();
        assertThat(r.omitidas()).isZero();
    }

    /** Recortes de texto sin leer. */
    static final class RecorteDeTextoVacio {
        static DeteccionPropuesta.RecorteDeTexto de(String ref, RolDeTexto rol) {
            return DeteccionPropuesta.RecorteDeTexto.vacio(ref, rol, CAJA);
        }

        static DeteccionPropuesta.RecorteDeTexto nombre(String ref) {
            return de(ref + "-n", RolDeTexto.NOMBRE_CLASE);
        }
    }
}
