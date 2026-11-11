package com.uagrm.colabmodel.interop;

import static com.uagrm.colabmodel.interop.EscritorXmiInterfazTest.conInterfaz;
import static com.uagrm.colabmodel.interop.EscritorXmiInterfazTest.parsear;
import static com.uagrm.colabmodel.interop.EscritorXmiInterfazTest.sinNovedades;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Rebanada (b) en el escritor: metodos estaticos y abstractos. En la parte UML van como atributos de la
 * `ownedOperation` (los dos perfiles); en el perfil EA tambien en `<operations>` de la Extension, que es de
 * donde EA 16.1 los lee.
 */
class EscritorXmiMetodosTest {

    @ParameterizedTest
    @EnumSource(EscritorXmi.Perfil.class)
    void la_operacion_lleva_estatico_y_abstracto_solo_cuando_son_verdaderos(EscritorXmi.Perfil perfil) {
        Document doc = parsear(conInterfaz().exportar(perfil).xmi());

        assertThat(operacion(doc, "ownedOperation", "crear").getAttribute("isStatic")).isEqualTo("true");
        assertThat(operacion(doc, "ownedOperation", "crear").hasAttribute("isAbstract")).isFalse();
        assertThat(operacion(doc, "ownedOperation", "pagar").getAttribute("isAbstract")).isEqualTo("true");
        assertThat(operacion(doc, "ownedOperation", "describir").getAttribute("isAbstract")).isEqualTo("true");
        assertThat(operacion(doc, "ownedOperation", "calcular").hasAttribute("isStatic")).isFalse();
        assertThat(operacion(doc, "ownedOperation", "calcular").hasAttribute("isAbstract")).isFalse();
    }

    @Test
    void el_perfil_ea_repite_las_banderas_en_operations_de_la_extension() {
        Document doc = parsear(conInterfaz().exportar(EscritorXmi.Perfil.ENTERPRISE_ARCHITECT).xmi());

        Element crear = tipo(operacion(doc, "operation", "crear"));
        assertThat(crear.getAttribute("static")).isEqualTo("true");
        assertThat(crear.getAttribute("isAbstract")).isEqualTo("false");
        assertThat(crear.getAttribute("type")).isEqualTo("Pedido");

        Element pagar = tipo(operacion(doc, "operation", "pagar"));
        assertThat(pagar.getAttribute("static")).isEqualTo("false");
        assertThat(pagar.getAttribute("isAbstract")).isEqualTo("true");

        assertThat(doc.getElementsByTagName("operation").getLength()).isEqualTo(conInterfaz().metodos().size());
    }

    @Test
    void el_perfil_estandar_no_escribe_la_extension() {
        Document doc = parsear(conInterfaz().exportar(EscritorXmi.Perfil.ESTANDAR).xmi());
        assertThat(doc.getElementsByTagName("operations").getLength()).isZero();
    }

    @Test
    void un_modelo_sin_metodos_estaticos_ni_abstractos_no_cambia_la_parte_uml() {
        String xmi = new String(sinNovedades().exportar(EscritorXmi.Perfil.ESTANDAR).xmi());
        assertThat(xmi).doesNotContain("isStatic").doesNotContain("isAbstract=\"true\" name=\"calcular\"");
    }

    private static Element operacion(Document doc, String etiqueta, String nombre) {
        List<Element> encontradas = new ArrayList<>();
        NodeList nodos = doc.getElementsByTagName(etiqueta);
        for (int i = 0; i < nodos.getLength(); i++) {
            Element e = (Element) nodos.item(i);
            if (nombre.equals(e.getAttribute("name"))) encontradas.add(e);
        }
        assertThat(encontradas).as(etiqueta + " " + nombre).hasSize(1);
        return encontradas.get(0);
    }

    private static Element tipo(Element operacion) {
        NodeList hijos = operacion.getElementsByTagName("type");
        return (Element) hijos.item(0);
    }
}
