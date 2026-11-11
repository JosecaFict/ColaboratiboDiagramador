package com.uagrm.colabmodel.interop;

import com.uagrm.colabmodel.modelcore.metamodel.*;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static com.uagrm.colabmodel.interop.EscritorXmiInterfazTest.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Los dos fallos que el usuario hallo al importar el perfil ENTERPRISE_ARCHITECT en EA 16.1 real (build 1625):
 * se perdian TODOS los tipos y no se creaba el diagrama. La forma correcta se copio de un export del propio EA
 * (`xmi/ea/ea161-1625-tienda-con-tipos.xmi`, con Pedido.fecha de tipo date y Pedido.numero de tipo int).
 *
 * Todo esto compila y pasa contra nuestro lector y contra la forma de ese export; que EA 16.1 cree el diagrama y
 * muestre los tipos con el archivo nuevo NO se prueba aca: queda pendiente de prueba final en EA real.
 */
class EscritorXmiEa161Test {

    private static final EscritorXmi.Perfil EA = EscritorXmi.Perfil.ENTERPRISE_ARCHITECT;

    // ============================================================ FALLO 1: TIPOS

    @Test
    void los_tipos_se_definen_en_primitivetypes_y_se_referencian_por_idref() {
        Document doc = parsear(conInterfaz().exportar(EA).xmi());

        Element tipos = elementos(doc, "primitivetypes").get(0);
        assertThat(hijos(tipos, "packagedElement")).singleElement()
                .satisfies(p -> assertThat(p.getAttribute("name")).isEqualTo("EA_PrimitiveTypes_Package"));
        List<Element> definidos = new ArrayList<>();
        for (Element e : elementos(doc, "packagedElement")) {
            if ("uml:PrimitiveType".equals(e.getAttribute("xmi:type"))) definidos.add(e);
        }
        assertThat(definidos).extracting(e -> e.getAttribute("xmi:id"))
                .contains("EAJava_string", "EAJava_int", "EAJava_decimal", "EAJava_boolean");
        assertThat(definidos).extracting(e -> e.getAttribute("name")).contains("string", "int", "decimal", "boolean");
        assertThat(texto(conInterfaz().exportar(EA))).as("ya no se referencia un tipo que nadie define")
                .doesNotContain("EAnone_");

        // Atributo: <type xmi:idref="EAJava_int"/> DESPUES de lowerValue/upperValue (como EA) y el nombre en la Extension.
        Element codigo = elementos(doc, "ownedAttribute").stream()
                .filter(e -> e.getAttribute("name").equals("codigo")).findFirst().orElseThrow();
        assertThat(codigo.hasAttribute("type")).as("EA tipa los atributos con un hijo <type>").isFalse();
        assertThat(hijos(codigo, "type")).singleElement()
                .satisfies(t -> assertThat(t.getAttribute("xmi:idref")).isEqualTo("EAJava_int"));
        assertThat(codigo.getLastChild().getPreviousSibling().getNodeName()).isEqualTo("type");
        Element extension = elementos(doc, "attribute").stream()
                .filter(e -> e.getAttribute("name").equals("codigo")).findFirst().orElseThrow();
        assertThat(hijos(extension, "properties").get(0).getAttribute("type")).isEqualTo("int");

        // Parametros y retorno.
        Element operacion = elementos(doc, "ownedOperation").stream()
                .filter(e -> e.getAttribute("name").equals("calcular")).findFirst().orElseThrow();
        List<Element> parametros = hijos(operacion, "ownedParameter");
        assertThat(parametros).extracting(p -> p.getAttribute("name")).containsExactly("descuento", "return");
        assertThat(parametros).extracting(p -> p.getAttribute("type")).containsExactly("EAJava_decimal", "EAJava_decimal");
        assertThat(parametros.get(1).getAttribute("direction")).isEqualTo("return");
    }

    @Test
    void un_tipo_que_es_una_clase_del_modelo_se_referencia_por_el_id_de_la_clase_y_no_se_define_como_primitivo() {
        UmlClass pedido = clasePrueba(PEDIDO, "Pedido", null);
        UmlClass estado = clasePrueba(CLIENTE, "Estado", "enumeration");
        UmlAttribute a = new UmlAttribute();
        a.setId(java.util.UUID.fromString("a0000000-0000-4000-8000-000000000099"));
        a.setClassId(PEDIDO);
        a.setName("estado");
        a.setDataType("Estado");
        EscritorXmi.Exportado ex = EscritorXmi.exportar(List.of(pedido, estado), List.of(a), List.of(), List.of(),
                List.of(), EA);
        Document doc = parsear(ex.xmi());

        Element atributo = elementos(doc, "ownedAttribute").get(0);
        assertThat(hijos(atributo, "type").get(0).getAttribute("xmi:idref")).isEqualTo(idClase(EA, CLIENTE));
        assertThat(texto(ex)).doesNotContain("EAJava_Estado");
        assertThat(referenciasRotas(doc)).isEmpty();
    }

    private static UmlClass clasePrueba(java.util.UUID id, String nombre, String estereotipo) {
        UmlClass c = new UmlClass();
        c.setId(id);
        c.setDiagramId(DIAGRAMA);
        c.setName(nombre);
        c.setStereotype(estereotipo);
        return c;
    }

    // ============================================================ FALLO 2: DIAGRAMA

    @Test
    void la_extension_tiene_los_bloques_en_el_orden_en_que_los_escribe_ea() {
        Document doc = parsear(conInterfaz().exportar(EA).xmi());
        Element extension = elementos(doc, "xmi:Extension").get(0);
        List<String> bloques = new ArrayList<>();
        for (org.w3c.dom.Node n = extension.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e) bloques.add(e.getTagName());
        }
        assertThat(bloques).containsExactly("elements", "connectors", "primitivetypes", "profiles", "diagrams");
    }

    @Test
    void hay_un_conector_por_relacion_con_el_tipo_de_ea() {
        Document doc = parsear(conInterfaz().exportar(EA).xmi());
        List<Element> conectores = elementos(doc, "connector");

        assertThat(conectores).hasSize(conInterfaz().relaciones().size());
        assertThat(conectores).extracting(c -> hijos(c, "properties").get(0).getAttribute("ea_type"))
                .containsExactlyInAnyOrder("Generalization", "Association", "Association", "Dependency", "Realisation");
        Element composicion = conectores.stream()
                .filter(c -> idRelacion(EA, 3).equals(c.getAttribute("xmi:idref"))).findFirst().orElseThrow();
        // El todo es el origen. EA 16.1 real (tanda 1) dibuja el rombo en el extremo del conector que lleva
        // `aggregation`; con el rombo en el destino lo ponia del lado de la parte.
        assertThat(hijos(hijos(composicion, "source").get(0), "type").get(0).getAttribute("aggregation"))
                .as("el rombo va en el extremo del todo, que es el origen").isEqualTo("composite");
        assertThat(hijos(hijos(composicion, "target").get(0), "type").get(0).getAttribute("aggregation"))
                .isEqualTo("none");
        assertThat(hijos(hijos(composicion, "source").get(0), "type").get(0).getAttribute("multiplicity"))
                .isEqualTo("0..*");
    }

    @Test
    void el_paquete_tambien_esta_en_los_elements_de_la_extension() {
        Document doc = parsear(sinNovedades().exportar(EA).xmi());
        Element primero = hijos(elementos(doc, "elements").get(0), "element").get(0);
        assertThat(primero.getAttribute("xmi:type")).isEqualTo("uml:Package");
        assertThat(hijos(primero, "model").get(0).getAttribute("ea_eleType")).isEqualTo("package");
    }

    @Test
    void el_diagrama_lleva_los_nodos_de_configuracion_y_cada_conector_como_entrada() {
        Modelo m = conInterfaz();
        Document doc = parsear(m.exportar(EA).xmi());
        Element diagrama = elementos(doc, "diagram").get(0);

        List<String> nodos = new ArrayList<>();
        for (org.w3c.dom.Node n = diagrama.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e) nodos.add(e.getTagName());
        }
        assertThat(nodos).containsExactly("model", "properties", "project", "style1", "style2", "swimlanes",
                "matrixitems", "extendedProperties", "persistentstyle", "xrefs", "elements");

        List<Element> entradas = hijos(hijos(diagrama, "elements").get(0), "element");
        assertThat(entradas).hasSize(m.clases().size() + m.relaciones().size());
        assertThat(entradas.stream().limit(m.clases().size()))
                .allSatisfy(e -> assertThat(e.getAttribute("style")).startsWith("DUID="));
        assertThat(entradas.stream().skip(m.clases().size()))
                .allSatisfy(e -> assertThat(e.getAttribute("style")).contains("SOID=").contains("EOID="));
        assertThat(referenciasRotas(doc)).isEmpty();
    }

    // ================================================ LO QUE EA EXPORTA, LEIDO CON NUESTRO LECTOR

    @Test
    void nuestro_lector_lee_los_tipos_de_un_export_real_de_ea_16_1() throws IOException {
        ModeloLeido leido;
        try (InputStream in = getClass().getResourceAsStream("/xmi/ea/ea161-1625-tienda-con-tipos.xmi")) {
            assertThat(in).isNotNull();
            leido = new LectorXmi().leer(in.readAllBytes());
        }
        ModeloLeido.ClaseLeida pedido = leido.clases().stream().filter(c -> c.nombre().equals("Pedido"))
                .findFirst().orElseThrow();
        assertThat(pedido.atributos()).filteredOn(a -> a.nombre().equals("fecha"))
                .singleElement().extracting(ModeloLeido.AtributoLeido::tipoLeido).isEqualTo("date");
        assertThat(pedido.atributos()).filteredOn(a -> a.nombre().equals("numero"))
                .singleElement().extracting(ModeloLeido.AtributoLeido::tipoLeido).isEqualTo("int");
    }

    @Test
    void nuestro_lector_lee_de_vuelta_los_tipos_de_nuestro_archivo_para_ea() {
        ModeloLeido leido = new LectorXmi().leer(sinNovedades().exportar(EA).xmi());
        ModeloLeido.ClaseLeida pedido = leido.clases().stream().filter(c -> c.nombre().equals("Pedido"))
                .findFirst().orElseThrow();
        assertThat(pedido.atributos()).extracting(ModeloLeido.AtributoLeido::tipoLeido).containsExactly("decimal");
        assertThat(pedido.metodos()).singleElement().satisfies(m -> {
            assertThat(m.tipoRetorno()).isEqualTo("decimal");
        });
    }
}
