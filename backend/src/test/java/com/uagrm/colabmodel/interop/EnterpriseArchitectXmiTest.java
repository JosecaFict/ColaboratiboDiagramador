package com.uagrm.colabmodel.interop;

import com.uagrm.colabmodel.modelcore.mapping.ModeloCanonico;
import com.uagrm.colabmodel.modelcore.metamodel.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ADR-031: el perfil para Enterprise Architect, y el diagrama en las dos
 * direcciones.
 *
 * Mismo criterio que EscritorXmiTest -exportar, reimportar con NUESTRO lector
 * por la misma propuesta que revisa el usuario, volver a derivar el DDL-, mas
 * lo que este perfil agrega: el estereotipo viaja, y la disposicion del lienzo
 * sale como el `<diagram>` de EA y vuelve como la posicion de cada clase.
 *
 * Lo que esto NO prueba es que EA lo abra: la forma del `<diagram>` sale de
 * exports conocidos de EA, no de un EA corriendo (riesgo abierto de ADR-022).
 */
class EnterpriseArchitectXmiTest {

    private final UUID diagramId = UUID.randomUUID();

    private static List<DiagramLayout> enGrilla(List<UmlClass> clases) {
        List<DiagramLayout> r = new ArrayList<>();
        for (int i = 0; i < clases.size(); i++) {
            DiagramLayout l = new DiagramLayout();
            l.setDiagramId(clases.get(i).getDiagramId());
            l.setElementId(clases.get(i).getId());
            l.setX(-100 + (i % 3) * 300);   // negativo a proposito: EA no dibuja ahi
            l.setY(50 + (i / 3) * 250);
            l.setWidth(170);
            l.setHeight(60);
            r.add(l);
        }
        return r;
    }

    private static EscritorXmi.Exportado ea(ModeloCanonico.Cargado m, List<DiagramLayout> disposicion) {
        return EscritorXmi.exportar(m.clases(), m.atributos(), m.metodos(), m.relaciones(), disposicion,
                EscritorXmi.Perfil.ENTERPRISE_ARCHITECT);
    }

    private PropuestaDeImportacion reimportar(byte[] xmi) {
        UmlClassRepository classes = mock(UmlClassRepository.class);
        when(classes.findByDiagramIdAndDeletedAtIsNullOrderByNameAsc(diagramId)).thenReturn(List.of());
        return new ServicioDeImportacion(List.of(new LectorXmi()), classes, mock(UmlAttributeRepository.class),
                mock(UmlMethodRepository.class)).proponer(diagramId, "export.xml", xmi);
    }

    // ============================================================ IDA Y VUELTA

    @ParameterizedTest
    @ValueSource(strings = {"01-uno-a-uno.json", "02-uno-a-muchos.json", "03-muchos-a-muchos.json",
            "05-clase-asociativa.json", "06-nombres-acentos.json", "07-reflexiva-doble.json"})
    void los_mismos_seis_canonicos_vuelven_con_el_mismo_ddl(String canonico) {
        ModeloCanonico.Cargado original = ModeloCanonico.cargar(canonico);
        EscritorXmi.Exportado ex = ea(original, enGrilla(original.clases()));

        ModeloCanonico.Cargado vuelta = RevisionPorDefecto.aplicar(reimportar(ex.xmi()));
        assertThat(LectorXmiTest.ddlDe(vuelta)).as(canonico).isEqualTo(LectorXmiTest.ddlDe(original));
    }

    @Test
    void composicion_y_agregacion_conservan_quien_es_el_todo() {
        ModeloCanonico.Cargado original = LectorXmiTest.todoParte();
        EscritorXmi.Exportado ex = ea(original, enGrilla(original.clases()));

        assertThat(LectorXmiTest.ddlDe(RevisionPorDefecto.aplicar(reimportar(ex.xmi()))))
                .isEqualTo(LectorXmiTest.ddlDe(original));
    }

    // ============================================================ EL DIALECTO

    @Test
    void escribe_el_dialecto_que_ea_escribe_de_si_mismo() {
        ModeloCanonico.Cargado m = ModeloCanonico.cargar("02-uno-a-muchos.json");
        String xmi = new String(ea(m, enGrilla(m.clases())).xmi(), StandardCharsets.UTF_8);

        assertThat(xmi)
                .contains("xmi:version=\"2.1\"")
                .contains("xmlns:uml=\"" + EscritorXmi.NS_UML_21 + "\"")
                .as("las clases dentro de un paquete, que es lo que importa EA")
                .containsPattern("<packagedElement xmi:type=\"uml:Package\" xmi:id=\"EAPK_[0-9A-F_]{36}\"")
                .containsPattern("xmi:type=\"uml:Class\" xmi:id=\"EAID_[0-9A-F_]{36}\"")
                .as("tipos como los escribe EA con el lenguaje <none>, no la biblioteca de la OMG")
                .contains("<type xmi:idref=\"EAnone_")
                .doesNotContain("PrimitiveTypes.xmi")
                .as("extremos EAID_src/EAID_dst")
                .contains("xmi:id=\"EAID_src").contains("xmi:id=\"EAID_dst")
                .contains("<xmi:Extension extender=\"Enterprise Architect\"");
    }

    @Test
    void el_diagrama_tiene_una_caja_por_clase_sin_coordenadas_negativas() {
        ModeloCanonico.Cargado m = ModeloCanonico.cargar("03-muchos-a-muchos.json");
        String xmi = new String(ea(m, enGrilla(m.clases())).xmi(), StandardCharsets.UTF_8);

        assertThat(xmi).contains("<properties name=\"Diagrama de clases\" type=\"Logical\"/>");
        for (UmlClass c : m.clases()) {
            String id = "EAID_" + c.getId().toString().toUpperCase().replace('-', '_');
            assertThat(xmi).as(c.getName()).containsPattern("geometry=\"Left=\\d+;Top=\\d+;Right=\\d+;Bottom=\\d+;\""
                    + " subject=\"" + id + "\"");
        }
        assertThat(xmi).as("la de mas arriba a la izquierda queda en (40, 40)").contains("Left=40;Top=40;");
    }

    @Test
    void el_estereotipo_viaja_y_deja_de_ser_una_perdida() {
        UmlClass dir = new UmlClass();
        dir.setId(UUID.randomUUID());
        dir.setDiagramId(diagramId);
        dir.setName("Direccion");
        dir.setStereotype("embeddable");
        UmlAttribute calle = new UmlAttribute();
        calle.setId(UUID.randomUUID());
        calle.setClassId(dir.getId());
        calle.setName("calle");
        calle.setDataType("string");

        EscritorXmi.Exportado ex = EscritorXmi.exportar(List.of(dir), List.of(calle), List.of(), List.of(),
                List.of(), EscritorXmi.Perfil.ENTERPRISE_ARCHITECT);

        assertThat(ex.perdidas()).noneMatch(p -> p.contains("embeddable"));
        assertThat(new LectorXmi().leer(ex.xmi()).clases())
                .singleElement().extracting(ModeloLeido.ClaseLeida::estereotipo).isEqualTo("embeddable");
    }

    @Test
    void el_perfil_estandar_no_cambia() {
        ModeloCanonico.Cargado m = ModeloCanonico.cargar("02-uno-a-muchos.json");
        byte[] viejo = EscritorXmi.exportar(m.clases(), m.atributos(), m.metodos(), m.relaciones()).xmi();
        byte[] nuevo = EscritorXmi.exportar(m.clases(), m.atributos(), m.metodos(), m.relaciones(),
                enGrilla(m.clases()), EscritorXmi.Perfil.ESTANDAR).xmi();

        assertThat(nuevo).as("el estandar no lleva disposicion ni nada de EA").isEqualTo(viejo);
        assertThat(new String(nuevo, StandardCharsets.UTF_8)).doesNotContain("xmi:Extension").doesNotContain("EAID_");
    }

    // ==================================================== EL DIAGRAMA, DE VUELTA

    @Test
    void la_disposicion_exportada_vuelve_como_posicion_de_cada_clase() {
        ModeloCanonico.Cargado m = ModeloCanonico.cargar("03-muchos-a-muchos.json");
        List<DiagramLayout> disposicion = enGrilla(m.clases());
        PropuestaDeImportacion p = reimportar(ea(m, disposicion).xmi());

        DiagramLayout primera = disposicion.get(0);
        for (int i = 0; i < m.clases().size(); i++) {
            String nombre = m.clases().get(i).getName();
            ModeloLeido.Posicion pos = p.clases().stream().filter(c -> c.nombre().equals(nombre)).findFirst()
                    .orElseThrow().posicion();
            assertThat(pos).as(nombre).isNotNull();
            // Se conserva la distancia entre clases: lo corrido es igual para todas.
            assertThat(pos.x() - 40).as(nombre).isEqualTo(disposicion.get(i).getX() - primera.getX());
            assertThat(pos.y() - 40).as(nombre).isEqualTo(disposicion.get(i).getY() - primera.getY());
        }
    }

    // ======================================= DIAGRAMAS AJENOS: EA 2.1, EA 1.1, UML DI

    private static final String CLASES_21 = """
            <?xml version="1.0" encoding="windows-1252"?>
            <xmi:XMI xmi:version="2.1" xmlns:uml="http://schema.omg.org/spec/UML/2.1" xmlns:xmi="http://schema.omg.org/spec/XMI/2.1">
              <xmi:Documentation exporter="Enterprise Architect" exporterVersion="6.5"/>
              <uml:Model xmi:type="uml:Model" name="EA_Model" visibility="public">
                <packagedElement xmi:type="uml:Package" xmi:id="EAPK_1" name="Biblioteca" visibility="public">
                  <packagedElement xmi:type="uml:Class" xmi:id="EAID_LIBRO" name="Libro" visibility="public">
                    <ownedAttribute xmi:type="uml:Property" xmi:id="EAID_A1" name="titulo" visibility="private">
                      <type xmi:idref="EAJava_String"/>
                    </ownedAttribute>
                  </packagedElement>
                  <packagedElement xmi:type="uml:Class" xmi:id="EAID_AUTOR" name="Autor" visibility="public"/>
                  <packagedElement xmi:type="uml:Class" xmi:id="EAID_SOCIO" name="Socio" visibility="public"/>
                </packagedElement>
              </uml:Model>
              %s
            </xmi:XMI>
            """;

    private static Map<String, ModeloLeido.Posicion> posicionesPorNombre(String xmi) {
        ModeloLeido leido = new LectorXmi().leer(xmi.getBytes(StandardCharsets.UTF_8));
        Map<String, ModeloLeido.Posicion> r = new java.util.HashMap<>();
        for (ModeloLeido.ClaseLeida c : leido.clases()) {
            ModeloLeido.Posicion p = leido.posiciones().get(c.clave());
            if (p != null) r.put(c.nombre(), p);
        }
        return r;
    }

    @Test
    void lee_el_diagrama_de_la_extension_de_ea() {
        String xmi = CLASES_21.formatted("""
                <xmi:Extension extender="Enterprise Architect" extenderID="6.5">
                  <diagrams>
                    <diagram xmi:id="EAID_D1">
                      <model package="EAPK_1" localID="3" owner="EAPK_1"/>
                      <properties name="Biblioteca" type="Logical"/>
                      <elements>
                        <element geometry="Left=100;Top=50;Right=220;Bottom=140;" subject="EAID_LIBRO" seqno="1" style="DUID=AB12;"/>
                        <element geometry="Left=400;Top=60;Right=490;Bottom=130;" subject="EAID_AUTOR" seqno="2"/>
                        <element geometry="SX=0;SY=0;EX=0;EY=0;EDGE=2;" subject="EAID_CONECTOR" style="Mode=3;"/>
                      </elements>
                    </diagram>
                  </diagrams>
                </xmi:Extension>""");

        Map<String, ModeloLeido.Posicion> p = posicionesPorNombre(xmi);
        assertThat(p.get("Libro")).isEqualTo(new ModeloLeido.Posicion(100, 50, 120, 90));
        assertThat(p.get("Autor")).isEqualTo(new ModeloLeido.Posicion(400, 60, 90, 70));
        assertThat(p).as("Socio no esta en el diagrama: va a la grilla").doesNotContainKey("Socio");
    }

    @Test
    void con_varios_diagramas_usa_el_que_dibuja_mas_clases_y_lo_avisa() {
        String xmi = CLASES_21.formatted("""
                <xmi:Extension extender="Enterprise Architect" extenderID="6.5">
                  <diagrams>
                    <diagram xmi:id="EAID_CHICO"><elements>
                      <element geometry="Left=900;Top=900;Right=1000;Bottom=1000;" subject="EAID_LIBRO"/>
                    </elements></diagram>
                    <diagram xmi:id="EAID_GRANDE"><elements>
                      <element geometry="Left=10;Top=10;Right=110;Bottom=80;" subject="EAID_LIBRO"/>
                      <element geometry="Left=210;Top=10;Right=310;Bottom=80;" subject="EAID_AUTOR"/>
                    </elements></diagram>
                  </diagrams>
                </xmi:Extension>""");

        assertThat(posicionesPorNombre(xmi).get("Libro").x()).isEqualTo(10);
        assertThat(new LectorXmi().leer(xmi.getBytes(StandardCharsets.UTF_8)).advertencias())
                .anyMatch(a -> a.contains("2 diagramas"));
    }

    @Test
    void xmi_1_1_de_ea_con_el_eje_y_negativo() {
        String xmi = """
                <?xml version="1.0" encoding="windows-1252"?>
                <XMI xmi.version="1.1" xmlns:UML="omg.org/UML1.3">
                  <XMI.content>
                    <UML:Model name="EA Model" xmi.id="MX_EAID_1">
                      <UML:Namespace.ownedElement>
                        <UML:Class name="Libro" xmi.id="EAID_LIBRO" visibility="public"/>
                      </UML:Namespace.ownedElement>
                    </UML:Model>
                    <UML:Diagram name="Biblioteca" xmi.id="EAID_D1" diagramType="ClassDiagram">
                      <UML:Diagram.element>
                        <UML:DiagramElement geometry="Left=120;Top=-80;Right=240;Bottom=-170;" subject="EAID_LIBRO" seqno="1"/>
                      </UML:Diagram.element>
                    </UML:Diagram>
                  </XMI.content>
                </XMI>
                """;

        assertThat(posicionesPorNombre(xmi).get("Libro")).isEqualTo(new ModeloLeido.Posicion(120, 80, 120, 90));
    }

    @Test
    void uml_di_con_bounds() {
        String xmi = CLASES_21.formatted("""
                <umldi:UMLClassDiagram xmi:id="DI1" xmlns:umldi="http://www.omg.org/spec/UML/20131001/UMLDI">
                  <ownedElement xmi:type="umldi:UMLShape" xmi:id="S1" modelElement="EAID_SOCIO">
                    <bounds xmi:type="dc:Bounds" x="30" y="70" width="150" height="80"/>
                  </ownedElement>
                </umldi:UMLClassDiagram>""");

        assertThat(posicionesPorNombre(xmi).get("Socio")).isEqualTo(new ModeloLeido.Posicion(30, 70, 150, 80));
    }

    @Test
    void sin_diagrama_no_hay_posiciones_y_nada_cambia() {
        assertThat(posicionesPorNombre(CLASES_21.formatted(""))).isEmpty();
    }
}
