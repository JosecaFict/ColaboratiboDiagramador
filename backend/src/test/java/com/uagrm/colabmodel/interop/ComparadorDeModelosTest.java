package com.uagrm.colabmodel.interop;

import com.uagrm.colabmodel.interop.ComparadorDeModelos.Opciones;
import com.uagrm.colabmodel.interop.ComparadorDeModelos.Resultado;
import com.uagrm.colabmodel.interop.ComparadorDeModelos.Tipo;
import com.uagrm.colabmodel.interop.ModeloLeido.AtributoLeido;
import com.uagrm.colabmodel.interop.ModeloLeido.ClaseLeida;
import com.uagrm.colabmodel.interop.ModeloLeido.MetodoLeido;
import com.uagrm.colabmodel.interop.ModeloLeido.ParametroLeido;
import com.uagrm.colabmodel.interop.ModeloLeido.RelacionLeida;
import com.uagrm.colabmodel.modelcore.metamodel.RelationKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.w3c.dom.Document;
import org.w3c.dom.Node;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import static com.uagrm.colabmodel.interop.CorpusEnterpriseArchitectTest.XMI11;
import static com.uagrm.colabmodel.interop.CorpusEnterpriseArchitectTest.XMI21;
import static com.uagrm.colabmodel.interop.CorpusEnterpriseArchitectTest.XMI251;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * M-QA-05 · Etapa 0: el comparador detecta una propiedad cambiada, un elemento faltante, un elemento sobrante y una
 * identidad distinta, y NO marca diferencia cuando solo cambia el orden, el espacio o las claves internas.
 *
 * Dos archivos del corpus construido que se sabe iguales (el mismo, leido dos veces, y el mismo con el XML
 * reordenado y reindentado) y dos que se sabe distintos (2.1 contra 1.1, y 2.1 contra 2.5.1).
 */
class ComparadorDeModelosTest {

    // ============================================================ iguales

    @ParameterizedTest
    @ValueSource(strings = {XMI21, XMI251, XMI11})
    void el_mismo_archivo_leido_dos_veces_no_tiene_diferencias(String archivo) {
        Resultado r = ComparadorDeModelos.comparar(CorpusEnterpriseArchitectTest.leer(archivo),
                CorpusEnterpriseArchitectTest.leer(archivo), Opciones.CON_IDENTIDAD.conPosiciones(0));
        assertThat(r.iguales()).as(r.comoTexto()).isTrue();
    }

    @Test
    void cambiar_el_orden_las_claves_y_los_espacios_no_es_una_diferencia() {
        ModeloLeido m = CorpusEnterpriseArchitectTest.leer(XMI21);
        ModeloLeido barajado = barajar(m);
        assertThat(barajado.clases().get(0).nombre()).isNotEqualTo(m.clases().get(0).nombre());   // de verdad se movio
        Resultado r = ComparadorDeModelos.comparar(m, barajado);
        assertThat(r.iguales()).as(r.comoTexto()).isTrue();
    }

    @Test
    void las_multiplicidades_equivalentes_no_son_una_diferencia() {
        ModeloLeido m = modelo(new RelacionLeida("a", "b", RelationKind.ASSOCIATION, "x", "*", "1", null, null, false));
        ModeloLeido otro = modelo(new RelacionLeida("a", "b", RelationKind.ASSOCIATION, "x", "0..*", "1..1", null, null, false));
        assertThat(ComparadorDeModelos.comparar(m, otro).iguales()).isTrue();
    }

    @Test
    void el_mismo_xmi_con_el_xml_reordenado_y_reindentado_no_tiene_diferencias() throws Exception {
        for (String archivo : List.of(XMI21, XMI251)) {
            byte[] original = bytes(archivo);
            byte[] variante = reordenarYReindentar(original);
            assertThat(variante).isNotEqualTo(original);
            Resultado r = ComparadorDeModelos.comparar(new LectorXmi().leer(original), new LectorXmi().leer(variante));
            assertThat(r.iguales()).as(archivo + "\n" + r.comoTexto()).isTrue();
        }
    }

    // ===================================================== cambios que SI se ven

    @Test
    void detecta_una_propiedad_cambiada() {
        ModeloLeido base = CorpusEnterpriseArchitectTest.leer(XMI21);
        ModeloLeido mutado = conAtributo(base, "Persona", "nombre",
                a -> new AtributoLeido(a.nombre(), a.tipoLeido(), a.pk(), a.requerido(), a.unico(), a.multivaluado(),
                        a.derivado(), a.claveParcial(), "PUBLIC", a.valorPorDefecto()));
        Resultado r = ComparadorDeModelos.comparar(base, mutado);
        assertThat(r.diferencias()).hasSize(1);
        assertThat(r.diferencias().get(0)).isEqualTo(new ComparadorDeModelos.Diferencia(Tipo.PROPIEDAD_DISTINTA,
                "Persona.nombre", "visibilidad", "PRIVATE", "PUBLIC"));
    }

    @Test
    void detecta_un_valor_inicial_perdido_y_la_abstraccion_cambiada() {
        ModeloLeido base = CorpusEnterpriseArchitectTest.leer(XMI21);
        ModeloLeido mutado = conAtributo(base, "Pedido", "estado",
                a -> new AtributoLeido(a.nombre(), a.tipoLeido(), a.pk(), a.requerido(), a.unico(), a.multivaluado(),
                        a.derivado(), a.claveParcial(), a.visibilidad(), null));
        mutado = conClase(mutado, "Persona", c -> new ClaseLeida(c.clave(), c.nombre(), c.estereotipo(), false,
                c.atributos(), c.metodos()));
        Resultado r = ComparadorDeModelos.comparar(base, mutado);
        assertThat(r.diferencias()).extracting(ComparadorDeModelos.Diferencia::ruta, ComparadorDeModelos.Diferencia::propiedad)
                .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple("Pedido.estado", "valorPorDefecto"),
                        org.assertj.core.groups.Tuple.tuple("Persona", "abstracta"));
    }

    @Test
    void detecta_un_elemento_faltante_y_uno_sobrante() {
        ModeloLeido base = CorpusEnterpriseArchitectTest.leer(XMI21);
        ModeloLeido sinAtributo = conClase(base, "Cliente", c -> new ClaseLeida(c.clave(), c.nombre(), c.estereotipo(),
                c.abstracta(), c.atributos().stream().filter(a -> !a.nombre().equals("código")).toList(), c.metodos()));
        Resultado falta = ComparadorDeModelos.comparar(base, sinAtributo);
        assertThat(falta.diferencias()).extracting(ComparadorDeModelos.Diferencia::tipo, ComparadorDeModelos.Diferencia::ruta)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(Tipo.ELEMENTO_FALTANTE, "Cliente.código"));

        Resultado sobra = ComparadorDeModelos.comparar(sinAtributo, base);
        assertThat(sobra.deTipo(Tipo.ELEMENTO_SOBRANTE)).extracting(ComparadorDeModelos.Diferencia::ruta)
                .containsExactly("Cliente.código");
    }

    @Test
    void detecta_una_clase_y_una_relacion_que_faltan_y_un_tipo_de_relacion_cambiado() {
        ModeloLeido base = CorpusEnterpriseArchitectTest.leer(XMI21);
        List<ClaseLeida> sinStock = base.clases().stream().filter(c -> !c.nombre().equals("Stock")).toList();
        ModeloLeido sinClase = new ModeloLeido(base.formato(), sinStock, base.relaciones(), base.advertencias(), base.posiciones());
        assertThat(ComparadorDeModelos.comparar(base, sinClase).deTipo(Tipo.ELEMENTO_FALTANTE))
                .extracting(ComparadorDeModelos.Diferencia::ruta).contains("Stock");

        // Composicion convertida en agregacion: cambia el TIPO, y eso es otro elemento (otro grupo), no una propiedad.
        List<RelacionLeida> rels = new ArrayList<>();
        for (RelacionLeida r : base.relaciones()) {
            rels.add(r.tipo() == RelationKind.COMPOSITION
                    ? new RelacionLeida(r.claveOrigen(), r.claveDestino(), RelationKind.AGGREGATION, r.nombre(),
                    r.multiplicidadOrigen(), r.multiplicidadDestino(), r.rolOrigen(), r.rolDestino(), r.identificadora())
                    : r);
        }
        ModeloLeido tipoCambiado = new ModeloLeido(base.formato(), base.clases(), rels, base.advertencias(), base.posiciones());
        Resultado r = ComparadorDeModelos.comparar(base, tipoCambiado);
        assertThat(r.diferencias()).extracting(ComparadorDeModelos.Diferencia::tipo)
                .containsExactlyInAnyOrder(Tipo.ELEMENTO_FALTANTE, Tipo.ELEMENTO_SOBRANTE);
        assertThat(r.comoTexto()).contains("Pedido -> LíneaPedido COMPOSITION", "Pedido -> LíneaPedido AGGREGATION");
    }

    @Test
    void detecta_una_multiplicidad_y_un_rol_cambiados_en_la_misma_relacion() {
        ModeloLeido base = modelo(new RelacionLeida("a", "b", RelationKind.ASSOCIATION, "x", "0..*", "1", "as", "b", false));
        ModeloLeido otro = modelo(new RelacionLeida("a", "b", RelationKind.ASSOCIATION, "x", "0..*", "0..1", "as", "bb", false));
        Resultado r = ComparadorDeModelos.comparar(base, otro);
        assertThat(r.diferencias()).extracting(ComparadorDeModelos.Diferencia::propiedad)
                .containsExactlyInAnyOrder("rolDestino", "multiplicidadDestino");
    }

    @Test
    void una_sobrecarga_distinta_es_otro_metodo_y_un_retorno_cambiado_es_una_propiedad() {
        ModeloLeido base = CorpusEnterpriseArchitectTest.leer(XMI251);
        ModeloLeido mutado = conClase(base, "Pedido", c -> {
            List<MetodoLeido> ms = new ArrayList<>(c.metodos());
            ms.add(new MetodoLeido("pagar", "boolean", List.of(new ParametroLeido("valor", "String"))));
            ms.replaceAll(m -> m.nombre().equals("calcularTotal")
                    ? new MetodoLeido(m.nombre(), "int", m.parametros()) : m);
            return new ClaseLeida(c.clave(), c.nombre(), c.estereotipo(), c.abstracta(), c.atributos(), ms);
        });
        Resultado r = ComparadorDeModelos.comparar(base, mutado);
        assertThat(r.diferencias()).extracting(ComparadorDeModelos.Diferencia::tipo, ComparadorDeModelos.Diferencia::ruta)
                .contains(org.assertj.core.groups.Tuple.tuple(Tipo.ELEMENTO_SOBRANTE, "Pedido.pagar(String)"),
                        org.assertj.core.groups.Tuple.tuple(Tipo.PROPIEDAD_DISTINTA, "Pedido.calcularTotal()"));
    }

    // ================================================================ identidad

    @Test
    void un_identificador_distinto_solo_cuenta_si_se_pide_comparar_identidad() {
        ModeloLeido base = CorpusEnterpriseArchitectTest.leer(XMI21);
        ModeloLeido otraClave = conClase(base, "Pedido", c -> new ClaseLeida("EAID_OTRO", c.nombre(), c.estereotipo(),
                c.abstracta(), c.atributos(), c.metodos()));
        // Las relaciones apuntan a la clave vieja: se reapuntan para que SOLO cambie la identidad.
        String vieja = clave(base, "Pedido");
        List<RelacionLeida> rels = base.relaciones().stream().map(r -> new RelacionLeida(
                r.claveOrigen().equals(vieja) ? "EAID_OTRO" : r.claveOrigen(),
                r.claveDestino().equals(vieja) ? "EAID_OTRO" : r.claveDestino(), r.tipo(), r.nombre(),
                r.multiplicidadOrigen(), r.multiplicidadDestino(), r.rolOrigen(), r.rolDestino(), r.identificadora())).toList();
        ModeloLeido obtenido = new ModeloLeido(base.formato(), otraClave.clases(), rels, base.advertencias(), Map.of());

        assertThat(ComparadorDeModelos.comparar(base, obtenido).iguales()).isTrue();
        Resultado r = ComparadorDeModelos.comparar(base, obtenido, Opciones.CON_IDENTIDAD);
        assertThat(r.diferencias()).containsExactly(new ComparadorDeModelos.Diferencia(Tipo.IDENTIDAD_DISTINTA,
                "Pedido", "clave", vieja, "EAID_OTRO"));
    }

    @Test
    void la_posicion_del_diagrama_se_compara_con_tolerancia() {
        ModeloLeido base = CorpusEnterpriseArchitectTest.leer(XMI21);
        String clave = clave(base, "Pedido");
        Map<String, ModeloLeido.Posicion> movidas = new java.util.HashMap<>(base.posiciones());
        ModeloLeido.Posicion p = movidas.get(clave);
        movidas.put(clave, new ModeloLeido.Posicion(p.x() + 3, p.y(), p.ancho(), p.alto()));
        ModeloLeido obtenido = new ModeloLeido(base.formato(), base.clases(), base.relaciones(), base.advertencias(), movidas);

        assertThat(ComparadorDeModelos.comparar(base, obtenido).iguales()).isTrue();   // sin pedir diagrama
        assertThat(ComparadorDeModelos.comparar(base, obtenido, Opciones.SEMANTICA.conPosiciones(5)).iguales()).isTrue();
        assertThat(ComparadorDeModelos.comparar(base, obtenido, Opciones.SEMANTICA.conPosiciones(1)).diferencias())
                .extracting(ComparadorDeModelos.Diferencia::propiedad).containsExactly("posicion");
    }

    // ===================================================== distintos conocidos

    @Test
    void xmi21_contra_xmi11_son_distintos_y_el_informe_dice_en_que() {
        Resultado r = ComparadorDeModelos.comparar(CorpusEnterpriseArchitectTest.leer(XMI21),
                CorpusEnterpriseArchitectTest.leer(XMI11));
        System.out.println("M-QA-05 comparador · 2.1 contra 1.1:\n" + r.comoTexto());
        assertThat(r.iguales()).isFalse();
        // El export 1.1 solo trae 5 clases: las otras cinco faltan.
        assertThat(r.deTipo(Tipo.ELEMENTO_FALTANTE)).extracting(ComparadorDeModelos.Diferencia::ruta)
                .contains("Dirección", "Producto", "Categoría", "Stock", "Oferta");
    }

    @Test
    void xmi21_contra_xmi251_son_distintos_en_lo_que_cada_formato_escribe_distinto() {
        Resultado r = ComparadorDeModelos.comparar(CorpusEnterpriseArchitectTest.leer(XMI21),
                CorpusEnterpriseArchitectTest.leer(XMI251));
        System.out.println("M-QA-05 comparador · 2.1 contra 2.5.1:\n" + r.comoTexto());
        assertThat(r.iguales()).isFalse();
        // No hay clases ni atributos de menos: lo que cambia es el vocabulario de tipos (double/Real, int/Integer).
        assertThat(r.deTipo(Tipo.ELEMENTO_FALTANTE)).extracting(ComparadorDeModelos.Diferencia::ruta)
                .noneMatch(ruta -> !ruta.contains("("));   // a lo sumo, firmas de metodos con otro tipo
        assertThat(r.diferencias()).extracting(ComparadorDeModelos.Diferencia::propiedad)
                .contains("tipo");
    }

    // ================================================================ ayudas

    private static byte[] bytes(String archivo) throws Exception {
        try (InputStream in = ComparadorDeModelosTest.class.getResourceAsStream("/xmi/ea/" + archivo)) {
            return in.readAllBytes();
        }
    }

    private static String clave(ModeloLeido m, String clase) {
        return CorpusEnterpriseArchitectTest.clase(m, clase).clave();
    }

    private static ModeloLeido modelo(RelacionLeida r) {
        return new ModeloLeido("test", List.of(
                new ClaseLeida("a", "A", null, false, List.of(), List.of()),
                new ClaseLeida("b", "B", null, false, List.of(), List.of())), List.of(r), List.of());
    }

    private static ModeloLeido conClase(ModeloLeido m, String nombre, UnaryOperator<ClaseLeida> f) {
        List<ClaseLeida> cs = m.clases().stream().map(c -> c.nombre().equals(nombre) ? f.apply(c) : c).toList();
        return new ModeloLeido(m.formato(), cs, m.relaciones(), m.advertencias(), m.posiciones());
    }

    private static ModeloLeido conAtributo(ModeloLeido m, String clase, String atributo, UnaryOperator<AtributoLeido> f) {
        return conClase(m, clase, c -> new ClaseLeida(c.clave(), c.nombre(), c.estereotipo(), c.abstracta(),
                c.atributos().stream().map(a -> a.nombre().equals(atributo) ? f.apply(a) : a).toList(), c.metodos()));
    }

    /** El mismo modelo con todo en otro orden, claves nuevas y espacios sobrantes en los nombres. */
    private static ModeloLeido barajar(ModeloLeido m) {
        Map<String, String> nuevas = new java.util.HashMap<>();
        for (ClaseLeida c : m.clases()) nuevas.put(c.clave(), "K_" + c.clave().hashCode());
        List<ClaseLeida> clases = new ArrayList<>();
        for (ClaseLeida c : m.clases()) {
            List<AtributoLeido> as = new ArrayList<>(c.atributos());
            Collections.reverse(as);
            List<MetodoLeido> ms = new ArrayList<>(c.metodos());
            Collections.reverse(ms);
            clases.add(new ClaseLeida(nuevas.get(c.clave()), "  " + c.nombre() + " ", c.estereotipo(), c.abstracta(), as, ms));
        }
        Collections.reverse(clases);
        List<RelacionLeida> rels = new ArrayList<>();
        for (RelacionLeida r : m.relaciones()) {
            rels.add(new RelacionLeida(nuevas.get(r.claveOrigen()), nuevas.get(r.claveDestino()), r.tipo(), r.nombre(),
                    r.multiplicidadOrigen(), r.multiplicidadDestino(), r.rolOrigen(), r.rolDestino(), r.identificadora()));
        }
        Collections.reverse(rels);
        return new ModeloLeido(m.formato(), clases, rels, List.of("otro texto de aviso"), Map.of());
    }

    /** Invierte el orden de los hijos de cada elemento `packagedElement` y vuelve a escribir el XML con sangria. */
    private static byte[] reordenarYReindentar(byte[] xmi) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(false);
        Document doc = f.newDocumentBuilder().parse(new ByteArrayInputStream(xmi));
        invertirHermanos(doc.getDocumentElement());
        Transformer t = TransformerFactory.newInstance().newTransformer();
        t.setOutputProperty(OutputKeys.INDENT, "yes");
        t.setOutputProperty(OutputKeys.ENCODING, doc.getXmlEncoding() == null ? "UTF-8" : doc.getXmlEncoding());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        t.transform(new DOMSource(doc), new StreamResult(out));
        return out.toByteArray();
    }

    private static void invertirHermanos(Node n) {
        for (Node h = n.getFirstChild(); h != null; h = h.getNextSibling()) invertirHermanos(h);
        if (!"packagedElement".equals(n.getNodeName()) && !"uml:Model".equals(n.getNodeName())) return;
        List<Node> hijos = new ArrayList<>();
        for (Node h = n.getFirstChild(); h != null; h = h.getNextSibling()) {
            if (h.getNodeType() == Node.ELEMENT_NODE && "packagedElement".equals(h.getNodeName())) hijos.add(h);
        }
        if (hijos.size() < 2) return;
        Node ancla = hijos.get(hijos.size() - 1).getNextSibling();
        for (Node h : hijos) n.removeChild(h);
        Collections.reverse(hijos);
        for (Node h : hijos) n.insertBefore(h, ancla);
    }
}
