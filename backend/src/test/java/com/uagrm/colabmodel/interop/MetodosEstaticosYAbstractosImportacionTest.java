package com.uagrm.colabmodel.interop;

import com.uagrm.colabmodel.modelcore.metamodel.UmlAttributeRepository;
import com.uagrm.colabmodel.modelcore.metamodel.UmlClassRepository;
import com.uagrm.colabmodel.modelcore.metamodel.UmlMethodRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * M-EA-03, rebanada (b): visibilidad, estatico y abstracto de cada metodo, y la interfaz que no se lee abstracta.
 *
 * Los dos `tanda2-*-v4` son el ejemplo nuestro de la tanda 2 de pruebas con EA (perfil EA y perfil estandar).
 * Son archivos construidos por ColaboratiBo, NO exportados por EA: no acreditan compatibilidad real.
 */
class MetodosEstaticosYAbstractosImportacionTest {

    static final String V4_EA = "tanda2-interfaz-realizacion-ea-v4.xml";
    static final String V4_ESTANDAR = "tanda2-interfaz-realizacion-estandar-v4.xmi";

    private static ModeloLeido leer(String archivo) {
        try (InputStream in = MetodosEstaticosYAbstractosImportacionTest.class.getResourceAsStream("/xmi/ea/" + archivo)) {
            return new LectorXmi().leer(in.readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static ModeloLeido leerTexto(String cuerpo) {
        return new LectorXmi().leer(("<xmi:XMI xmlns:xmi=\"http://schema.omg.org/spec/XMI/2.1\" "
                + "xmlns:uml=\"http://schema.omg.org/spec/UML/2.1\" xmi:version=\"2.1\">"
                + cuerpo + "</xmi:XMI>").getBytes(StandardCharsets.UTF_8));
    }

    private static ModeloLeido.MetodoLeido metodo(ModeloLeido m, String clase, String nombre) {
        return CorpusEnterpriseArchitectTest.clase(m, clase).metodos().stream()
                .filter(x -> x.nombre().equals(nombre)).findFirst()
                .orElseThrow(() -> new AssertionError("falta " + clase + "." + nombre));
    }

    @ParameterizedTest
    @ValueSource(strings = {V4_EA, V4_ESTANDAR})
    void el_v4_trae_pagar_y_describir_abstractos_crear_estatico_y_la_visibilidad(String archivo) {
        ModeloLeido m = leer(archivo);
        assertThat(metodo(m, "Pagable", "pagar").esAbstracto()).isTrue();
        assertThat(metodo(m, "Persona", "describir").esAbstracto()).isTrue();
        assertThat(metodo(m, "Pedido", "crear").esEstatico()).isTrue();
        assertThat(metodo(m, "Pedido", "crear").esAbstracto()).isFalse();
        assertThat(metodo(m, "Pedido", "calcular").esEstatico()).isFalse();
        assertThat(metodo(m, "Pedido", "calcular").esAbstracto()).isFalse();
        // En el v4 todas las operaciones son publicas.
        for (String[] op : new String[][]{{"Pagable", "pagar"}, {"Persona", "describir"}, {"Pedido", "crear"},
                {"Pedido", "calcular"}}) {
            assertThat(metodo(m, op[0], op[1]).visibilidad()).as(op[0] + "." + op[1]).isEqualTo("PUBLIC");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {V4_EA, V4_ESTANDAR})
    void la_interfaz_pagable_no_se_lee_abstracta_aunque_su_clase_diga_isAbstract_true(String archivo) {
        ModeloLeido m = leer(archivo);
        assertThat(CorpusEnterpriseArchitectTest.clase(m, "Pagable").estereotipo()).isEqualTo("interface");
        assertThat(CorpusEnterpriseArchitectTest.clase(m, "Pagable").abstracta()).isFalse();
        // Una clase abstracta de verdad sigue siendolo.
        assertThat(CorpusEnterpriseArchitectTest.clase(m, "Persona").abstracta()).isTrue();
    }

    @Test
    void el_v4_de_ea_dice_isAbstract_true_en_la_interfaz_y_el_lector_lo_ignora_a_proposito() {
        // Es la diferencia 3 de Pepe, decidida por el usuario: el escritor de EA lo pone en toda interfaz.
        String xml = new String(leerBytes(V4_EA), StandardCharsets.UTF_8);
        assertThat(xml).contains("name=\"Pagable\" visibility=\"public\" isAbstract=\"true\"");
        assertThat(leer(V4_EA).clases()).filteredOn(c -> c.nombre().equals("Pagable"))
                .singleElement().satisfies(c -> assertThat(c.abstracta()).isFalse());
    }

    private static byte[] leerBytes(String archivo) {
        try (InputStream in = MetodosEstaticosYAbstractosImportacionTest.class.getResourceAsStream("/xmi/ea/" + archivo)) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void un_export_de_ea_sin_banderas_en_el_ownedOperation_las_toma_del_bloque_operations_de_la_extension() {
        // La forma real de EA: el ownedOperation no lleva isStatic ni isAbstract y la visibilidad se repite como
        // scope; las banderas estan en <operation><type static= isAbstract=>. Mismo orden que tienda-desde-ea-con-tipos.xml.
        ModeloLeido m = leerTexto("""
            <uml:Model xmi:id="m">
              <packagedElement xmi:type="uml:Class" xmi:id="C1" name="Servicio">
                <ownedOperation xmi:id="O1" name="crear" visibility="public"/>
                <ownedOperation xmi:id="O2" name="validar" visibility="protected"/>
                <ownedOperation xmi:id="O3" name="auditar" visibility="private"/>
                <ownedOperation xmi:id="O4" name="plantilla" visibility="package"/>
                <ownedOperation xmi:id="O5" name="normal" visibility="public"/>
              </packagedElement>
            </uml:Model>
            <xmi:Extension extender="Enterprise Architect" extenderID="6.5">
              <elements>
                <element xmi:idref="C1" xmi:type="uml:Class" name="Servicio">
                  <operations>
                    <operation xmi:idref="O1" name="crear" scope="Public">
                      <type type="Servicio" const="false" static="true" isAbstract="false" synchronised="0" pure="0" isQuery="false"/>
                    </operation>
                    <operation xmi:idref="O2" name="validar" scope="Protected">
                      <type type="boolean" const="false" static="false" isAbstract="true" synchronised="0" pure="0" isQuery="false"/>
                    </operation>
                    <operation xmi:idref="O3" name="auditar" scope="Private">
                      <type type="void" const="false" static="true" isAbstract="false"/>
                    </operation>
                    <operation xmi:idref="O4" name="plantilla" scope="Package">
                      <type type="void" const="false" static="false" isAbstract="false"/>
                    </operation>
                    <operation xmi:idref="O5" name="normal" scope="Public">
                      <type type="void" const="false" static="false" isAbstract="false"/>
                    </operation>
                  </operations>
                </element>
              </elements>
            </xmi:Extension>
            """);
        assertThat(metodo(m, "Servicio", "crear").esEstatico()).isTrue();
        assertThat(metodo(m, "Servicio", "crear").esAbstracto()).isFalse();
        assertThat(metodo(m, "Servicio", "validar").esAbstracto()).isTrue();
        assertThat(metodo(m, "Servicio", "validar").esEstatico()).isFalse();
        assertThat(metodo(m, "Servicio", "auditar").esEstatico()).isTrue();
        assertThat(metodo(m, "Servicio", "normal").esEstatico()).isFalse();
        assertThat(metodo(m, "Servicio", "normal").esAbstracto()).isFalse();
        assertThat(metodo(m, "Servicio", "validar").visibilidad()).isEqualTo("PROTECTED");
        assertThat(metodo(m, "Servicio", "auditar").visibilidad()).isEqualTo("PRIVATE");
        assertThat(metodo(m, "Servicio", "plantilla").visibilidad()).isEqualTo("PACKAGE");
        assertThat(metodo(m, "Servicio", "crear").visibilidad()).isEqualTo("PUBLIC");
    }

    @Test
    void la_visibilidad_de_un_metodo_sin_ella_en_el_archivo_queda_en_null_y_no_se_inventa() {
        ModeloLeido m = leerTexto("""
            <uml:Model xmi:id="m">
              <packagedElement xmi:type="uml:Class" xmi:id="C1" name="Servicio">
                <ownedOperation xmi:id="O1" name="hacer"/>
              </packagedElement>
            </uml:Model>
            """);
        assertThat(metodo(m, "Servicio", "hacer").visibilidad()).isNull();
        assertThat(metodo(m, "Servicio", "hacer").esEstatico()).isFalse();
        assertThat(metodo(m, "Servicio", "hacer").esAbstracto()).isFalse();
    }

    @Test
    void el_estatico_de_uml_1x_viene_como_ownerScope_classifier() {
        ModeloLeido m = leerTexto("""
            <uml:Model xmi:id="m">
              <packagedElement xmi:type="uml:Class" xmi:id="C1" name="Util">
                <ownedOperation xmi:id="O1" name="global" ownerScope="classifier"/>
                <ownedOperation xmi:id="O2" name="local" ownerScope="instance"/>
              </packagedElement>
            </uml:Model>
            """);
        assertThat(metodo(m, "Util", "global").esEstatico()).isTrue();
        assertThat(metodo(m, "Util", "local").esEstatico()).isFalse();
    }

    @Test
    void la_propuesta_lleva_visibilidad_estatico_y_abstracto_de_cada_metodo_a_la_revision() {
        UUID diagrama = UUID.randomUUID();
        UmlClassRepository classes = mock(UmlClassRepository.class);
        when(classes.findByDiagramIdAndDeletedAtIsNullOrderByNameAsc(diagrama)).thenReturn(List.of());
        PropuestaDeImportacion p = new ServicioDeImportacion(List.of(new LectorXmi()), classes,
                mock(UmlAttributeRepository.class), mock(UmlMethodRepository.class))
                .proponer(diagrama, "v4.xml", leerBytes(V4_EA));
        PropuestaDeImportacion.ClasePropuesta pedido = p.clases().stream().filter(c -> c.nombre().equals("Pedido"))
                .findFirst().orElseThrow();
        PropuestaDeImportacion.MetodoPropuesto crear = pedido.metodos().stream().filter(x -> x.nombre().equals("crear"))
                .findFirst().orElseThrow();
        assertThat(crear.esEstatico()).isTrue();
        assertThat(crear.esAbstracto()).isFalse();
        assertThat(crear.visibilidad()).isEqualTo("PUBLIC");
        PropuestaDeImportacion.ClasePropuesta pagable = p.clases().stream().filter(c -> c.nombre().equals("Pagable"))
                .findFirst().orElseThrow();
        assertThat(pagable.abstracta()).isFalse();
        assertThat(pagable.metodos()).singleElement().satisfies(x -> assertThat(x.esAbstracto()).isTrue());
    }
}
