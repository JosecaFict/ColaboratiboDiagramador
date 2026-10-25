package com.uagrm.colabmodel.interop;

import com.uagrm.colabmodel.modelcore.mapping.ModeloCanonico;
import com.uagrm.colabmodel.modelcore.metamodel.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ADR-020 revertida: la exportacion XMI vuelve a estar en alcance.
 *
 * Mismo criterio que el lector SQL (ADR-018), reutilizando el MISMO
 * mecanismo: "equivalencia salvo perdidas declaradas", verificado sobre el
 * DDL derivado y por el camino real -exportar, importar con NUESTRO propio
 * lector, pasar por la MISMA propuesta que revisa el usuario, aplicar sin
 * tocar nada, volver a derivar-. `ddlDe` es el helper de `LectorXmiTest`:
 * no hay un segundo criterio de equivalencia, hay uno solo, reutilizado.
 *
 * De los 7 canonicos, 6 vuelven con el mismo DDL byte a byte. El 7mo
 * (04-herencia) tiene un atributo UNIQUE que no es tambien PK: la unicidad
 * no tiene representacion en UML 2.1 sin inventar un perfil (el mismo motivo
 * por el que `HerramientasXmi.papyrus()` necesita uno propio para probarlo
 * como dialecto ajeno), asi que EscritorXmi la declara como perdida en vez
 * de forzarla, y ese caso se prueba aparte, con su pista.
 */
class EscritorXmiTest {

    private final UUID diagramId = UUID.randomUUID();

    private record IdaYVuelta(EscritorXmi.Exportado exportado, ModeloCanonico.Cargado reimportado,
                              PropuestaDeImportacion propuesta) { }

    private IdaYVuelta idaYVuelta(ModeloCanonico.Cargado original) {
        EscritorXmi.Exportado exportado = EscritorXmi.exportar(original.clases(), original.atributos(),
                original.metodos(), original.relaciones());
        UmlClassRepository classes = mock(UmlClassRepository.class);
        when(classes.findByDiagramIdAndDeletedAtIsNullOrderByNameAsc(diagramId)).thenReturn(List.of());
        ServicioDeImportacion servicio = new ServicioDeImportacion(List.of(new LectorXmi()), classes,
                mock(UmlAttributeRepository.class), mock(UmlMethodRepository.class));
        PropuestaDeImportacion propuesta = servicio.proponer(diagramId, "export.xmi", exportado.xmi());
        return new IdaYVuelta(exportado, RevisionPorDefecto.aplicar(propuesta), propuesta);
    }

    // ============================================ LOS 7 CANONICOS, IDA Y VUELTA

    @ParameterizedTest
    @ValueSource(strings = {"01-uno-a-uno.json", "02-uno-a-muchos.json", "03-muchos-a-muchos.json",
            "05-clase-asociativa.json", "06-nombres-acentos.json", "07-reflexiva-doble.json"})
    void seis_de_los_siete_canonicos_vuelven_con_el_mismo_ddl(String canonico) {
        ModeloCanonico.Cargado original = ModeloCanonico.cargar(canonico);
        IdaYVuelta v = idaYVuelta(original);

        assertThat(LectorXmiTest.ddlDe(v.reimportado())).as(canonico).isEqualTo(LectorXmiTest.ddlDe(original));
        assertThat(v.exportado().xmi()).as("XMI 2.5.1 estandar, sin Extension de EA ni perfil propio")
                .asString().contains("xmlns:uml=\"" + EscritorXmi.NS_UML + "\"")
                .doesNotContain("xmi:version").doesNotContain("EAstereotype").doesNotContain("pathmap://");
    }

    // ================================================================ UML 2.5.1

    @Test
    void es_xmi_2_5_1_con_la_biblioteca_de_tipos_de_la_omg() {
        ModeloCanonico.Cargado m = ModeloCanonico.cargar("02-uno-a-muchos.json");
        String xmi = new String(EscritorXmi.exportar(m.clases(), m.atributos(), m.metodos(), m.relaciones()).xmi(),
                java.nio.charset.StandardCharsets.UTF_8);

        assertThat(xmi)
                .contains("xmlns:xmi=\"http://www.omg.org/spec/XMI/20131001\"")
                .contains("xmlns:uml=\"http://www.omg.org/spec/UML/20161101\"")
                .as("string va a la biblioteca estandar, no a un PrimitiveType local")
                .contains("href=\"http://www.omg.org/spec/UML/20161101/PrimitiveTypes.xmi#String\"")
                .doesNotContain("name=\"string\"")
                .as("memberEnd no es derivado en UML 2.5.1: se serializa")
                .containsPattern("uml:Association\"[^>]*memberEnd=\"r[^\"]+-o r[^\"]+-d\"")
                .contains("visibility=\"private\"");
    }

    @Test
    void un_tipo_sin_equivalente_exacto_en_la_omg_queda_local_y_con_su_nombre() {
        ModeloCanonico.Cargado m = ModeloCanonico.cargar("02-uno-a-muchos.json");
        UmlAttribute a = m.atributos().get(0);
        a.setDataType("decimal");
        a.setDefaultValue("0 < 1");

        String xmi = new String(EscritorXmi.exportar(m.clases(), m.atributos(), m.metodos(), m.relaciones()).xmi(),
                java.nio.charset.StandardCharsets.UTF_8);

        assertThat(xmi).as("decimal no es Real: perderia la exactitud")
                .contains("xmi:type=\"uml:PrimitiveType\" xmi:id=\"t-decimal\" name=\"decimal\"")
                .doesNotContain("#Real")
                .contains("<defaultValue xmi:type=\"uml:OpaqueExpression\"")
                .contains("<body>0 &lt; 1</body>");
    }

    @Test
    void herencia_pierde_declaradamente_el_unico_que_no_es_tambien_pk() {
        ModeloCanonico.Cargado original = ModeloCanonico.cargar("04-herencia.json");
        IdaYVuelta v = idaYVuelta(original);

        // Persona.ci es UNIQUE y no es PK (Usuario.email de 01-uno-a-uno tambien
        // es unico, pero AL SER PK no depende de UML para reconstruirse: por eso
        // ese canonico si vuelve identico).
        assertThat(LectorXmiTest.ddlDe(v.reimportado())).as("se pierde el UNIQUE de Persona.ci")
                .isNotEqualTo(LectorXmiTest.ddlDe(original));
        assertThat(v.exportado().perdidas()).anyMatch(p -> p.contains("ci") && p.contains("unico"));
    }

    // ==================================================== COMPOSICION/AGREGACION

    @Test
    void composicion_y_agregacion_conservan_quien_es_el_todo() {
        ModeloCanonico.Cargado original = LectorXmiTest.todoParte();
        IdaYVuelta v = idaYVuelta(original);

        assertThat(LectorXmiTest.ddlDe(v.reimportado())).isEqualTo(LectorXmiTest.ddlDe(original));
        assertThat(new LectorXmi().leer(v.exportado().xmi()).relaciones())
                .extracting(ModeloLeido.RelacionLeida::tipo)
                .containsExactlyInAnyOrder(RelationKind.COMPOSITION, RelationKind.AGGREGATION);
    }

    // ======================================================== PERDIDAS DECLARADAS

    @Test
    void lo_que_uml_no_representa_se_declara_y_no_se_inventa_un_perfil() {
        UUID d = UUID.randomUUID();
        UmlClass debil = new UmlClass();
        debil.setId(UUID.randomUUID());
        debil.setDiagramId(d);
        debil.setName("Detalle");
        debil.setWeak(true);
        debil.setStereotype("embeddable");

        EscritorXmi.Exportado ex = EscritorXmi.exportar(List.of(debil), List.of(), List.of(), List.of());

        assertThat(ex.perdidas()).anySatisfy(p -> assertThat(p).contains("Detalle").contains("embeddable"));
        assertThat(ex.perdidas()).anySatisfy(p -> assertThat(p).contains("Detalle").contains("debil"));
        assertThat(ex.perdidas()).anyMatch(p -> p.contains("A-1..A-4"));
        assertThat(ex.xmi()).asString().doesNotContain("xmi:Extension").doesNotContain("profileApplication");
    }

    @Test
    void una_clase_sin_nada_que_declarar_no_agrega_perdidas_de_mas() {
        ModeloCanonico.Cargado m = ModeloCanonico.cargar("02-uno-a-muchos.json");
        EscritorXmi.Exportado ex = EscritorXmi.exportar(m.clases(), m.atributos(), m.metodos(), m.relaciones());

        // La unica perdida siempre presente es la estructural (A-1..A-4): nada
        // en este canonico dispara ademas estereotipo, ER o herencia.
        assertThat(ex.perdidas()).hasSize(1);
        assertThat(ex.perdidas().get(0)).contains("A-1..A-4");
    }
}
