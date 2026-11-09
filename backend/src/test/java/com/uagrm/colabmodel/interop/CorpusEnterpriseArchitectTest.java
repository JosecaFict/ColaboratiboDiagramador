package com.uagrm.colabmodel.interop;

import com.uagrm.colabmodel.modelcore.metamodel.RelationKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T-05 (D-13): el mismo modelo exportado por Enterprise Architect de las tres
 * formas que ofrece su dialogo de exportacion, sin un archivo del docente.
 *
 *   - `ea16-xmi21-tienda.xmi`: XMI 2.1 con las extensiones de EA (conectores,
 *     tipos, diagramas), en windows-1252 y con acentos. Es el default de EA.
 *   - `ea16-xmi251-sin-extensiones.xmi`: XMI 2.5.1 con "Exclude EA Extensions":
 *     UML puro, primitivos de la OMG y sin diagrama.
 *   - `ea-xmi11-uml13-tienda.xmi`: XMI 1.1 / UML 1.3, valores etiquetados y el
 *     eje Y negativo de EA en el diagrama.
 *
 * La forma sale de exports conocidos de EA, no de un EA corriendo (riesgo de
 * ADR-022): si aparece un export real, se suma aca como un archivo mas.
 */
class CorpusEnterpriseArchitectTest {

    static final String XMI21 = "ea16-xmi21-tienda.xmi";
    static final String XMI251 = "ea16-xmi251-sin-extensiones.xmi";
    static final String XMI11 = "ea-xmi11-uml13-tienda.xmi";

    static ModeloLeido leer(String archivo) {
        try (InputStream in = CorpusEnterpriseArchitectTest.class.getResourceAsStream("/xmi/ea/" + archivo)) {
            return new LectorXmi().leer(in.readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static ModeloLeido.ClaseLeida clase(ModeloLeido m, String nombre) {
        return m.clases().stream().filter(c -> c.nombre().equals(nombre)).findFirst()
                .orElseThrow(() -> new AssertionError("falta la clase " + nombre + " en " + nombres(m)));
    }

    static ModeloLeido.AtributoLeido atributo(ModeloLeido.ClaseLeida c, String nombre) {
        return c.atributos().stream().filter(a -> a.nombre().equals(nombre)).findFirst()
                .orElseThrow(() -> new AssertionError("falta " + c.nombre() + "." + nombre));
    }

    static List<String> nombres(ModeloLeido m) {
        return m.clases().stream().map(ModeloLeido.ClaseLeida::nombre).toList();
    }

    /** "Origen -> Destino TIPO" con los nombres de las clases, para comparar sin ids. */
    static List<String> relaciones(ModeloLeido m) {
        Map<String, String> nombre = m.clases().stream()
                .collect(Collectors.toMap(ModeloLeido.ClaseLeida::clave, ModeloLeido.ClaseLeida::nombre));
        return m.relaciones().stream()
                .map(r -> nombre.get(r.claveOrigen()) + " -> " + nombre.get(r.claveDestino()) + " " + r.tipo())
                .toList();
    }

    static ModeloLeido.RelacionLeida relacion(ModeloLeido m, String origen, String destino, RelationKind tipo) {
        String a = clase(m, origen).clave();
        String b = clase(m, destino).clave();
        return m.relaciones().stream()
                .filter(r -> r.claveOrigen().equals(a) && r.claveDestino().equals(b) && r.tipo() == tipo)
                .findFirst().orElseThrow(() -> new AssertionError("falta " + origen + " -> " + destino + " " + tipo
                        + " en " + relaciones(m)));
    }

    // ============================================================ las tres

    @ParameterizedTest
    @ValueSource(strings = {XMI21, XMI251, XMI11})
    void los_acentos_de_windows_1252_llegan_intactos(String archivo) {
        ModeloLeido m = leer(archivo);
        assertThat(nombres(m)).contains("LíneaPedido");
        assertThat(clase(m, "Cliente").atributos()).extracting(ModeloLeido.AtributoLeido::nombre).contains("código");
        assertThat(clase(m, "Pedido").atributos()).extracting(ModeloLeido.AtributoLeido::nombre).contains("número");
    }

    @ParameterizedTest
    @ValueSource(strings = {XMI21, XMI251, XMI11})
    void un_atributo_tipado_con_otra_clase_es_una_relacion_aunque_ea_escriba_el_tipo_como_texto(String archivo) {
        ModeloLeido m = leer(archivo);
        assertThat(clase(m, "Pedido").atributos()).extracting(ModeloLeido.AtributoLeido::nombre)
                .doesNotContain("vendedor");
        ModeloLeido.RelacionLeida r = m.relaciones().stream().filter(x -> "vendedor".equals(x.rolDestino()))
                .findFirst().orElseThrow();
        assertThat(r.claveOrigen()).isEqualTo(clase(m, "Pedido").clave());
        assertThat(r.claveDestino()).isEqualTo(clase(m, "Cliente").clave());
        assertThat(r.multiplicidadDestino()).isEqualTo("0..1");
    }

    @ParameterizedTest
    @ValueSource(strings = {XMI21, XMI251, XMI11})
    void una_enumeracion_usada_como_tipo_sigue_siendo_un_atributo_con_su_valor_inicial(String archivo) {
        ModeloLeido m = leer(archivo);
        ModeloLeido.AtributoLeido estado = atributo(clase(m, "Pedido"), "estado");
        assertThat(estado.tipoLeido()).isEqualTo("EstadoPedido");
        assertThat(estado.valorPorDefecto()).isEqualTo("PENDIENTE");
        assertThat(clase(m, "EstadoPedido").estereotipo()).isEqualTo("enumeration");
        assertThat(clase(m, "EstadoPedido").atributos()).extracting(ModeloLeido.AtributoLeido::nombre)
                .containsExactly("PENDIENTE", "PAGADO", "ENVIADO");
        assertThat(atributo(clase(m, "LíneaPedido"), "cantidad").valorPorDefecto()).isEqualTo("1");
    }

    @ParameterizedTest
    @ValueSource(strings = {XMI21, XMI251, XMI11})
    void la_visibilidad_de_cada_atributo_se_conserva(String archivo) {
        ModeloLeido m = leer(archivo);
        assertThat(atributo(clase(m, "Persona"), "nombre").visibilidad()).isEqualTo("PRIVATE");
        String estado = atributo(clase(m, "Pedido"), "estado").visibilidad();
        // En el export 1.1 el estado es protected; en los otros, private.
        assertThat(estado).isEqualTo(archivo.equals(XMI11) ? "PROTECTED" : "PRIVATE");
    }

    @ParameterizedTest
    @ValueSource(strings = {XMI21, XMI251, XMI11})
    void la_interfaz_no_entra_pero_su_operacion_pasa_a_la_clase_que_la_realiza(String archivo) {
        ModeloLeido m = leer(archivo);
        assertThat(nombres(m)).doesNotContain("Pagable");
        assertThat(clase(m, "Pedido").metodos()).extracting(ModeloLeido.MetodoLeido::nombre).contains("pagar");
        String retorno = clase(m, "Pedido").metodos().stream().filter(x -> x.nombre().equals("pagar")).findFirst()
                .orElseThrow().tipoRetorno();
        assertThat(retorno).isIn("boolean", "Boolean");
        assertThat(m.advertencias()).anyMatch(a -> a.contains("Pedido realiza la interfaz Pagable"));
        assertThat(m.advertencias()).anyMatch(a -> a.contains("La interfaz Pagable no entra como clase"));
        assertThat(m.advertencias()).noneMatch(a -> a.contains("Interface"));
        assertThat(m.advertencias()).noneMatch(a -> a.contains("dependencias o realizaciones"));
    }

    @ParameterizedTest
    @ValueSource(strings = {XMI21, XMI251, XMI11})
    void herencia_clase_abstracta_y_composicion_con_el_todo_de_origen(String archivo) {
        ModeloLeido m = leer(archivo);
        assertThat(clase(m, "Persona").abstracta()).isTrue();
        relacion(m, "Cliente", "Persona", RelationKind.GENERALIZATION);
        ModeloLeido.RelacionLeida c = relacion(m, "Pedido", "LíneaPedido", RelationKind.COMPOSITION);
        assertThat(c.multiplicidadOrigen()).isEqualTo("1");
        assertThat(c.multiplicidadDestino()).isEqualTo("1..*");
        ModeloLeido.RelacionLeida a = relacion(m, "Pedido", "Cliente", RelationKind.ASSOCIATION);
        assertThat(m.relaciones()).anyMatch(r -> "realiza".equals(r.nombre()) && "pedidos".equals(r.rolOrigen())
                && "cliente".equals(r.rolDestino()) && "0..*".equals(r.multiplicidadOrigen())
                && "1".equals(r.multiplicidadDestino()));
        assertThat(a).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {XMI21, XMI251, XMI11})
    void las_operaciones_traen_parametros_y_retorno(String archivo) {
        ModeloLeido.ClaseLeida pedido = clase(leer(archivo), "Pedido");
        ModeloLeido.MetodoLeido total = pedido.metodos().stream().filter(x -> x.nombre().equals("calcularTotal"))
                .findFirst().orElseThrow();
        assertThat(total.tipoRetorno()).isIn("double", "Real");
        if (!archivo.equals(XMI11)) {   // la muestra 1.1 trae solo calcularTotal
            ModeloLeido.MetodoLeido agregar = pedido.metodos().stream()
                    .filter(x -> x.nombre().equals("agregarLínea")).findFirst().orElseThrow();
            assertThat(agregar.tipoRetorno()).isEqualTo("void");
            assertThat(agregar.parametros()).extracting(ModeloLeido.ParametroLeido::nombre, ModeloLeido.ParametroLeido::tipo)
                    .containsExactly(org.assertj.core.groups.Tuple.tuple("producto", "Producto"),
                            org.assertj.core.groups.Tuple.tuple("cantidad", archivo.equals(XMI251) ? "Integer" : "int"));
        }
    }

    // ========================================================= XMI 2.1 de EA

    @Test
    void xmi21_lee_las_diez_clases_y_el_datatype_como_valor_embebido() {
        ModeloLeido m = leer(XMI21);
        assertThat(nombres(m)).containsExactlyInAnyOrder("Persona", "Cliente", "Pedido", "LíneaPedido",
                "EstadoPedido", "Dirección", "Producto", "Categoría", "Stock", "Oferta");
        assertThat(clase(m, "Dirección").estereotipo()).isEqualTo("embeddable");
        assertThat(atributo(clase(m, "Cliente"), "domicilio").tipoLeido()).isEqualTo("Dirección");
        assertThat(atributo(clase(m, "Cliente"), "código").pk()).isTrue();
        assertThat(atributo(clase(m, "Pedido"), "total").derivado()).isTrue();
        assertThat(atributo(clase(m, "Persona"), "correo").requerido()).isFalse();
        assertThat(atributo(clase(m, "Persona"), "correo").visibilidad()).isEqualTo("PROTECTED");
        ModeloLeido.AtributoLeido precio = atributo(clase(m, "Producto"), "precio");
        assertThat(precio.visibilidad()).isEqualTo("PUBLIC");
        assertThat(precio.valorPorDefecto()).isEqualTo("0.0");
    }

    @Test
    void xmi21_la_clase_asociativa_no_deja_una_asociacion_de_mas_por_su_conector() {
        ModeloLeido m = leer(XMI21);
        assertThat(relaciones(m)).contains("Cliente -> Oferta ASSOCIATION", "Producto -> Oferta ASSOCIATION");
        assertThat(relaciones(m)).doesNotContain("Cliente -> Producto ASSOCIATION");
        assertThat(m.relaciones()).hasSize(9);
    }

    @Test
    void xmi21_agregacion_reflexiva_y_composicion_strong_salen_del_conector_sin_dudas() {
        ModeloLeido m = leer(XMI21);
        ModeloLeido.RelacionLeida ag = relacion(m, "Categoría", "Producto", RelationKind.AGGREGATION);
        assertThat(ag.nombre()).isEqualTo("clasifica");
        assertThat(ag.rolOrigen()).isEqualTo("categoría");
        ModeloLeido.RelacionLeida reflexiva = relacion(m, "Categoría", "Categoría", RelationKind.ASSOCIATION);
        assertThat(reflexiva.rolOrigen()).isEqualTo("subcategorías");
        assertThat(reflexiva.rolDestino()).isEqualTo("padre");
        assertThat(reflexiva.multiplicidadDestino()).isEqualTo("0..1");
        // El conector de EA dice donde esta el rombo: no se le pide al usuario que lo revise.
        assertThat(m.advertencias()).noneMatch(a -> a.contains("Si quedo al reves"));
    }

    @Test
    void xmi21_la_nota_no_cuenta_como_dependencia_y_el_diagrama_principal_da_las_posiciones() {
        ModeloLeido m = leer(XMI21);
        assertThat(m.advertencias()).noneMatch(a -> a.contains("Note") || a.contains("dependencia"));
        assertThat(m.posiciones()).hasSize(9);
        assertThat(m.posiciones().get(clase(m, "Pedido").clave()))
                .isEqualTo(new ModeloLeido.Posicion(40, 200, 170, 130));
        assertThat(m.advertencias()).anyMatch(a -> a.contains("2 diagramas"));
        assertThat(m.advertencias().get(0)).contains("10 clases UML", "Enterprise Architect", "XMI 2.1");
    }

    // ================================================== XMI 2.5.1 sin extensiones

    @Test
    void xmi251_sin_extensiones_usa_los_primitivos_de_la_omg_y_no_inventa_posiciones() {
        ModeloLeido m = leer(XMI251);
        assertThat(nombres(m)).hasSize(10);
        assertThat(atributo(clase(m, "Pedido"), "número").tipoLeido()).isEqualTo("Integer");
        assertThat(atributo(clase(m, "Pedido"), "total").tipoLeido()).isEqualTo("Real");
        assertThat(m.posiciones()).isEmpty();
        assertThat(relaciones(m)).doesNotContain("Cliente -> Producto ASSOCIATION");
    }

    // ========================================================= XMI 1.1 de EA

    @Test
    void xmi11_el_eje_y_negativo_de_ea_vuelve_positivo() {
        ModeloLeido m = leer(XMI11);
        assertThat(m.posiciones()).hasSize(5);
        assertThat(m.posiciones().get(clase(m, "LíneaPedido").clave()))
                .isEqualTo(new ModeloLeido.Posicion(40, 420, 160, 70));
        assertThat(nombres(m)).doesNotContain("EARootClass", "String", "int");
    }

    // ================================ revision de Codex (RevisionT05BordesTest)

    private static ModeloLeido leerTexto(String cuerpo) {
        return new LectorXmi().leer(("<xmi:XMI xmlns:xmi=\"http://schema.omg.org/spec/XMI/2.1\" "
                + "xmlns:uml=\"http://schema.omg.org/spec/UML/2.1\" xmi:version=\"2.1\">"
                + cuerpo + "</xmi:XMI>").getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    void un_datatype_primitivo_sin_atributos_deja_mandar_el_tipo_y_largo_de_ea() {
        ModeloLeido m = leerTexto("""
            <uml:Model xmi:id="m">
              <packagedElement xmi:type="uml:DataType" xmi:id="str" name="String"/>
              <packagedElement xmi:type="uml:Class" xmi:id="c" name="Cliente">
                <ownedAttribute xmi:type="uml:Property" xmi:id="a" name="nombre" type="str"/>
              </packagedElement>
            </uml:Model>
            <xmi:Extension extender="Enterprise Architect"><elements>
              <element xmi:idref="c"><attributes>
                <attribute xmi:idref="a" name="nombre"><properties type="varchar" length="80"/></attribute>
              </attributes></element>
            </elements></xmi:Extension>
            """);
        assertThat(m.clases()).hasSize(1);
        assertThat(m.clases().get(0).atributos().get(0).tipoLeido()).isEqualTo("varchar(80)");
    }

    @Test
    void una_interfaz_con_una_sobrecarga_transfiere_las_dos_firmas() {
        ModeloLeido m = leerTexto("""
            <uml:Model xmi:id="m">
              <packagedElement xmi:type="uml:Interface" xmi:id="i" name="Pagable">
                <ownedOperation xmi:id="o1" name="pagar">
                  <ownedParameter xmi:id="p1" name="valor" type="EAJava_int" direction="in"/>
                </ownedOperation>
                <ownedOperation xmi:id="o2" name="pagar">
                  <ownedParameter xmi:id="p2" name="valor" type="EAJava_String" direction="in"/>
                </ownedOperation>
              </packagedElement>
              <packagedElement xmi:type="uml:Class" xmi:id="c" name="Pedido"/>
              <packagedElement xmi:type="uml:Realization" xmi:id="r" client="c" supplier="i"/>
            </uml:Model>
            """);
        assertThat(m.clases()).hasSize(1);
        assertThat(m.clases().get(0).metodos())
                .extracting(x -> x.parametros().get(0).tipo()).containsExactly("int", "String");
        assertThat(m.advertencias()).anyMatch(a -> a.contains("pagar(int), pagar(string)"));
    }
}
