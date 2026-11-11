package com.uagrm.colabmodel.interop;

import com.uagrm.colabmodel.modelcore.metamodel.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Etapa 1, rebanada (a) de la mesa UML 2.5.1 / EA (M-EA-04): la interfaz sale
 * como `uml:Interface` y la `REALIZATION` como `interfaceRealization` dentro de
 * la clase (en el perfil de EA, ademas, con su conector de la Extension); y,
 * como red de seguridad, el XML esta bien formado, cada referencia apunta a un
 * `xmi:id` que existe, la dependencia se exporta y un diagrama SIN elementos
 * nuevos sale byte a byte igual que antes de esta rebanada.
 *
 * Compila y pasa contra NUESTRO lector. Que EA abra el archivo NO se prueba
 * aca: queda pendiente de prueba final en EA real (ADR-022/031).
 */
class EscritorXmiInterfazTest {

    static final UUID DIAGRAMA = UUID.fromString("d0000000-0000-4000-8000-000000000001");
    static final UUID PERSONA = id(1);
    static final UUID CLIENTE = id(2);
    static final UUID PEDIDO = id(3);
    static final UUID PAGABLE = id(4);

    private static UUID id(int n) {
        return UUID.fromString(String.format("c0000000-0000-4000-8000-%012d", n));
    }

    private static UmlClass clase(UUID id, String nombre, String estereotipo, boolean abstracta) {
        UmlClass c = new UmlClass();
        c.setId(id);
        c.setDiagramId(DIAGRAMA);
        c.setName(nombre);
        c.setStereotype(estereotipo);
        c.setAbstract(abstracta);
        return c;
    }

    private static UmlAttribute atributo(int n, UUID clase, String nombre, String tipo, boolean pk) {
        UmlAttribute a = new UmlAttribute();
        a.setId(UUID.fromString(String.format("a0000000-0000-4000-8000-%012d", n)));
        a.setClassId(clase);
        a.setName(nombre);
        a.setDataType(tipo);
        a.setPk(pk);
        a.setOrdinal(n);
        return a;
    }

    private static UmlMethod metodo(int n, UUID clase, String nombre, String retorno, String params) {
        UmlMethod m = new UmlMethod();
        m.setId(UUID.fromString(String.format("e0000000-0000-4000-8000-%012d", n)));
        m.setClassId(clase);
        m.setName(nombre);
        m.setReturnType(retorno);
        m.setParams(params);
        m.setOrdinal(n);
        return m;
    }

    private static UmlRelationship relacion(int n, UUID origen, UUID destino, RelationKind tipo, String nombre) {
        UmlRelationship r = new UmlRelationship();
        r.setId(UUID.fromString(String.format("f0000000-0000-4000-8000-%012d", n)));
        r.setDiagramId(DIAGRAMA);
        r.setSourceClassId(origen);
        r.setTargetClassId(destino);
        r.setKind(tipo);
        r.setName(nombre);
        r.setSourceMultiplicity("0..*");
        r.setTargetMultiplicity("1");
        return r;
    }

    /** Un modelo de antes de esta rebanada: clases, atributos, metodos, asociacion, composicion, herencia, dependencia. */
    static Modelo sinNovedades() {
        List<UmlClass> clases = List.of(clase(PERSONA, "Persona", null, true), clase(CLIENTE, "Cliente", null, false),
                clase(PEDIDO, "Pedido", null, false));
        List<UmlAttribute> atributos = List.of(atributo(1, PERSONA, "nombre", "string", false),
                atributo(2, CLIENTE, "codigo", "int", true), atributo(3, PEDIDO, "total", "decimal", false));
        List<UmlMethod> metodos = List.of(metodo(1, PEDIDO, "calcular", "decimal",
                "[{\"name\":\"descuento\",\"type\":\"decimal\"}]"));
        List<UmlRelationship> relaciones = List.of(
                relacion(1, CLIENTE, PERSONA, RelationKind.GENERALIZATION, null),
                relacion(2, PEDIDO, CLIENTE, RelationKind.ASSOCIATION, "hace"),
                relacion(3, PEDIDO, CLIENTE, RelationKind.COMPOSITION, null),
                relacion(4, PEDIDO, PERSONA, RelationKind.DEPENDENCY, "usa"));
        return new Modelo(clases, atributos, metodos, relaciones);
    }

    /** Lo anterior mas una interfaz `Pagable` que `Cliente` realiza. */
    static Modelo conInterfaz() {
        Modelo base = sinNovedades();
        List<UmlClass> clases = new ArrayList<>(base.clases());
        clases.add(clase(PAGABLE, "Pagable", "interface", false));
        List<UmlMethod> metodos = new ArrayList<>(base.metodos());
        metodos.add(metodo(2, PAGABLE, "pagar", "boolean", "[{\"name\":\"monto\",\"type\":\"decimal\"}]"));
        List<UmlRelationship> relaciones = new ArrayList<>(base.relaciones());
        relaciones.add(relacion(5, CLIENTE, PAGABLE, RelationKind.REALIZATION, null));
        return new Modelo(clases, base.atributos(), metodos, relaciones);
    }

    record Modelo(List<UmlClass> clases, List<UmlAttribute> atributos, List<UmlMethod> metodos,
                  List<UmlRelationship> relaciones) {
        EscritorXmi.Exportado exportar(EscritorXmi.Perfil perfil) {
            List<DiagramLayout> layout = new ArrayList<>();
            for (int i = 0; i < clases.size(); i++) {
                DiagramLayout l = new DiagramLayout();
                l.setDiagramId(DIAGRAMA);
                l.setElementId(clases.get(i).getId());
                l.setX(20 + i * 250);
                l.setY(30 + (i % 2) * 200);
                l.setWidth(170);
                l.setHeight(60);
                layout.add(l);
            }
            // Reloj fijo: EA escribe fechas `created`/`modified` y el archivo tiene que ser reproducible.
            return EscritorXmi.exportar(clases, atributos, metodos, relaciones, layout, perfil,
                    java.time.Clock.fixed(java.time.Instant.parse("2026-11-10T12:00:00Z"), java.time.ZoneOffset.UTC));
        }
    }

    static String texto(EscritorXmi.Exportado e) {
        return new String(e.xmi(), StandardCharsets.UTF_8);
    }

    // ============================================== SIN ELEMENTOS NUEVOS: IGUAL QUE ANTES

    /**
     * Los archivos de `xmi/golden/` se generaron con el escritor de e933cfc,
     * ANTES de tocarlo en esta rebanada. Para regenerarlos a proposito:
     * `-Dgolden.escribir=true` (y revisar el diff a mano).
     */
    @ParameterizedTest
    @EnumSource(EscritorXmi.Perfil.class)
    void un_diagrama_sin_elementos_nuevos_exporta_igual_que_antes(EscritorXmi.Perfil perfil) throws IOException {
        String archivo = "sin-novedades-" + perfil.name().toLowerCase(Locale.ROOT) + ".xml";
        byte[] actual = sinNovedades().exportar(perfil).xmi();
        if (Boolean.getBoolean("golden.escribir")) {
            Path destino = Path.of("src/test/resources/xmi/golden", archivo);
            Files.createDirectories(destino.getParent());
            Files.write(destino, actual);
        }
        try (InputStream in = getClass().getResourceAsStream("/xmi/golden/" + archivo)) {
            assertThat(in).as("falta el archivo de referencia " + archivo).isNotNull();
            assertThat(actual).isEqualTo(in.readAllBytes());
        }
    }

    // ============================================================ DEPENDENCIA

    @ParameterizedTest
    @EnumSource(EscritorXmi.Perfil.class)
    void la_dependencia_se_exporta_con_cliente_y_proveedor(EscritorXmi.Perfil perfil) {
        EscritorXmi.Exportado ex = sinNovedades().exportar(perfil);
        Document doc = parsear(ex.xmi());

        List<Element> deps = elementos(doc, "packagedElement").stream()
                .filter(e -> "uml:Dependency".equals(e.getAttribute("xmi:type"))).toList();
        assertThat(deps).hasSize(1);
        assertThat(deps.get(0).getAttribute("name")).isEqualTo("usa");
        assertThat(deps.get(0).getAttribute("client")).isEqualTo(idClase(perfil, PEDIDO));
        assertThat(deps.get(0).getAttribute("supplier")).isEqualTo(idClase(perfil, PERSONA));
        assertThat(new LectorXmi().leer(ex.xmi()).relaciones())
                .anyMatch(r -> r.tipo() == RelationKind.DEPENDENCY);
    }

    // ===================================================== INTERFAZ Y REALIZACION

    @ParameterizedTest
    @EnumSource(EscritorXmi.Perfil.class)
    void la_clase_con_estereotipo_interface_sale_como_uml_interface_y_no_se_declara_perdida(
            EscritorXmi.Perfil perfil) {
        EscritorXmi.Exportado ex = conInterfaz().exportar(perfil);
        Element interfaz = elementos(parsear(ex.xmi()), "packagedElement").stream()
                .filter(e -> idClase(perfil, PAGABLE).equals(e.getAttribute("xmi:id"))).findFirst().orElseThrow();

        assertThat(interfaz.getAttribute("xmi:type")).isEqualTo("uml:Interface");
        assertThat(hijos(interfaz, "ownedOperation")).extracting(e -> e.getAttribute("name")).containsExactly("pagar");
        assertThat(ex.perdidas()).as("la interfaz ya no es una perdida")
                .noneMatch(p -> p.contains("Pagable") || p.toLowerCase(Locale.ROOT).contains("interface"));
    }

    @ParameterizedTest
    @EnumSource(EscritorXmi.Perfil.class)
    void la_realizacion_sale_como_interface_realization_dentro_de_la_clase_y_nunca_como_asociacion(
            EscritorXmi.Perfil perfil) {
        EscritorXmi.Exportado ex = conInterfaz().exportar(perfil);
        Document doc = parsear(ex.xmi());
        String idRealizacion = idRelacion(perfil, 5);

        Element cliente = elementos(doc, "packagedElement").stream()
                .filter(e -> idClase(perfil, CLIENTE).equals(e.getAttribute("xmi:id"))).findFirst().orElseThrow();
        Element real = hijos(cliente, "interfaceRealization").stream().findFirst().orElseThrow();
        assertThat(real.getAttribute("xmi:type")).isEqualTo("uml:InterfaceRealization");
        assertThat(real.getAttribute("xmi:id")).isEqualTo(idRealizacion);
        assertThat(real.getAttribute("client")).isEqualTo(idClase(perfil, CLIENTE));
        assertThat(real.getAttribute("supplier")).isEqualTo(idClase(perfil, PAGABLE));
        assertThat(real.getAttribute("contract")).isEqualTo(idClase(perfil, PAGABLE));

        assertThat(elementos(doc, "packagedElement")).as("una realizacion no es una asociacion")
                .noneMatch(e -> "uml:Association".equals(e.getAttribute("xmi:type"))
                        && e.getAttribute("xmi:id").equals(idRealizacion));
        assertThat(elementos(doc, "packagedElement")).as("tampoco queda suelta en el paquete")
                .noneMatch(e -> e.getAttribute("xmi:id").equals(idRealizacion));
        assertThat(elementos(doc, "ownedEnd")).as("solo las dos asociaciones de la base tienen extremos")
                .hasSize(4);
    }

    @Test
    void el_perfil_ea_agrega_el_conector_de_la_extension_con_ea_type_realisation() {
        EscritorXmi.Exportado ex = conInterfaz().exportar(EscritorXmi.Perfil.ENTERPRISE_ARCHITECT);
        Document doc = parsear(ex.xmi());

        Element conector = elementos(doc, "connector").stream()
                .filter(e -> idRelacion(EscritorXmi.Perfil.ENTERPRISE_ARCHITECT, 5).equals(e.getAttribute("xmi:idref")))
                .findFirst().orElseThrow();
        assertThat(hijos(conector, "source").get(0).getAttribute("xmi:idref"))
                .isEqualTo(idClase(EscritorXmi.Perfil.ENTERPRISE_ARCHITECT, CLIENTE));
        assertThat(hijos(conector, "target").get(0).getAttribute("xmi:idref"))
                .isEqualTo(idClase(EscritorXmi.Perfil.ENTERPRISE_ARCHITECT, PAGABLE));
        assertThat(hijos(conector, "properties").get(0).getAttribute("ea_type")).isEqualTo("Realisation");

        Element elementoInterfaz = elementos(doc, "element").stream()
                .filter(e -> idClase(EscritorXmi.Perfil.ENTERPRISE_ARCHITECT, PAGABLE).equals(e.getAttribute("xmi:idref")))
                .findFirst().orElseThrow();
        assertThat(elementoInterfaz.getAttribute("xmi:type")).isEqualTo("uml:Interface");
        Element propiedades = hijos(elementoInterfaz, "properties").get(0);
        assertThat(propiedades.getAttribute("sType")).isEqualTo("Interface");
        assertThat(propiedades.getAttribute("isAbstract")).isEqualTo("true");
        assertThat(propiedades.hasAttribute("stereotype")).as("el estereotipo ya es la metaclase").isFalse();
    }

    @Test
    void el_perfil_estandar_no_lleva_extension_ni_conector() {
        String xmi = texto(conInterfaz().exportar(EscritorXmi.Perfil.ESTANDAR));
        assertThat(xmi).doesNotContain("xmi:Extension").doesNotContain("<connector").doesNotContain("Realisation");
    }

    // ======================================= BIEN FORMADO E INTEGRIDAD DE REFERENCIAS

    @ParameterizedTest
    @EnumSource(EscritorXmi.Perfil.class)
    void el_xml_esta_bien_formado_y_toda_referencia_apunta_a_un_xmi_id_existente(EscritorXmi.Perfil perfil) {
        for (Modelo m : List.of(sinNovedades(), conInterfaz())) {
            List<String> rotas = referenciasRotas(parsear(m.exportar(perfil).xmi()));
            assertThat(rotas).as("referencias sin destino en el perfil " + perfil).isEmpty();
        }
    }

    @Test
    void la_prueba_de_integridad_detecta_una_referencia_rota() {
        Document doc = parsear(("<xmi:XMI xmlns:xmi=\"x\"><packagedElement xmi:id=\"a\" client=\"b\"/></xmi:XMI>")
                .getBytes(StandardCharsets.UTF_8));
        assertThat(referenciasRotas(doc)).singleElement().asString().contains("client").contains("b");
    }

    /** Atributos que son referencias (valor = uno o varios xmi:id). `type` solo cuando es atributo de un extremo, atributo o parametro. */
    // `xmi:id` de los tipos primitivos de EA vive en `primitivetypes` (dentro de la Extension): se cuenta como definido.
    private static final Set<String> REFERENCIAS = Set.of("xmi:idref", "client", "supplier", "general", "contract",
            "memberEnd", "navigableOwnedEnd", "association", "subject", "package", "owner");
    private static final Set<String> CON_TYPE_DE_REFERENCIA = Set.of("ownedAttribute", "ownedEnd", "ownedParameter");

    static List<String> referenciasRotas(Document doc) {
        Set<String> ids = new HashSet<>();
        for (Element e : todos(doc)) if (e.hasAttribute("xmi:id")) ids.add(e.getAttribute("xmi:id"));
        List<String> rotas = new ArrayList<>();
        for (Element e : todos(doc)) {
            NamedNodeMapView atributos = new NamedNodeMapView(e);
            for (String nombre : atributos.nombres()) {
                boolean esRef = REFERENCIAS.contains(nombre)
                        || (nombre.equals("type") && CON_TYPE_DE_REFERENCIA.contains(e.getTagName()));
                if (!esRef) continue;
                for (String valor : e.getAttribute(nombre).trim().split("\\s+")) {
                    if (valor.isEmpty()) continue;
                    if (!ids.contains(valor)) {
                        rotas.add("<" + e.getTagName() + " " + nombre + "=\"" + valor + "\"> no existe como xmi:id");
                    }
                }
            }
        }
        return rotas;
    }

    // Sin excepciones: un tipo `EAJava_x` TIENE que estar definido (EA 16.1 descarta los que no lo estan).

    private record NamedNodeMapView(Element e) {
        List<String> nombres() {
            List<String> r = new ArrayList<>();
            for (int i = 0; i < e.getAttributes().getLength(); i++) r.add(e.getAttributes().item(i).getNodeName());
            return r;
        }
    }

    // ============================================== ARCHIVOS DE EJEMPLO PARA EA REAL

    /**
     * No prueba nada: con `-Dejemplos.escribir=<carpeta>` escribe un archivo por
     * perfil (interfaz + realizacion) para que Pepe y el usuario los abran en EA
     * real. Sin la propiedad se omite.
     */
    @ParameterizedTest
    @EnumSource(EscritorXmi.Perfil.class)
    void escribe_los_ejemplos_para_abrir_en_ea(EscritorXmi.Perfil perfil) throws IOException {
        String carpeta = System.getProperty("ejemplos.escribir");
        org.junit.jupiter.api.Assumptions.assumeTrue(carpeta != null, "solo con -Dejemplos.escribir=<carpeta>");
        Files.createDirectories(Path.of(carpeta));
        String nombre = perfil == EscritorXmi.Perfil.ENTERPRISE_ARCHITECT
                ? "interfaz-realizacion-ea.xml" : "interfaz-realizacion-estandar.xmi";
        Files.write(Path.of(carpeta, nombre), conInterfaz().exportar(perfil).xmi());
    }

    // ============================================================ AYUDAS

    static String idClase(EscritorXmi.Perfil perfil, UUID id) {
        return perfil == EscritorXmi.Perfil.ENTERPRISE_ARCHITECT ? "EAID_" + guid(id) : "c" + id;
    }

    static String idRelacion(EscritorXmi.Perfil perfil, int n) {
        UUID id = UUID.fromString(String.format("f0000000-0000-4000-8000-%012d", n));
        return perfil == EscritorXmi.Perfil.ENTERPRISE_ARCHITECT ? "EAID_" + guid(id) : "r" + id;
    }

    private static String guid(UUID id) {
        return id.toString().toUpperCase(Locale.ROOT).replace('-', '_');
    }

    static Document parsear(byte[] xmi) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(false);   // asi `xmi:id` es un nombre de atributo literal
            return f.newDocumentBuilder().parse(new ByteArrayInputStream(xmi));
        } catch (Exception e) {
            throw new AssertionError("el XML no esta bien formado: " + e.getMessage(), e);
        }
    }

    static List<Element> todos(Document doc) {
        return elementos(doc, "*");
    }

    static List<Element> elementos(Document doc, String nombre) {
        NodeList l = doc.getElementsByTagName(nombre);
        List<Element> r = new ArrayList<>();
        for (int i = 0; i < l.getLength(); i++) r.add((Element) l.item(i));
        return r;
    }

    static List<Element> hijos(Element padre, String nombre) {
        List<Element> r = new ArrayList<>();
        for (Node n = padre.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && e.getTagName().equals(nombre)) r.add(e);
        }
        return r;
    }
}
