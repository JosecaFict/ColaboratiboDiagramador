package com.uagrm.colabmodel.interop;

import com.uagrm.colabmodel.modelcore.metamodel.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.uagrm.colabmodel.interop.EscritorXmiInterfazTest.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Dos decisiones del usuario sobre la SALIDA, tomadas al ver el archivo en EA 16.1, iguales en los dos perfiles
 * (ColabModel sigue guardando los dos datos; solo dejan de exportarse):
 *
 *  1. Un rol de relacion que repite el nombre de la clase del extremo (con la primera en minuscula, o ese nombre
 *     en plural: +s, +es) no se exporta como nombre del extremo. Un rol distinto sale como siempre.
 *  2. La multiplicidad de un atributo (lowerValue/upperValue y, en EA, `bounds`) solo se exporta cuando es
 *     multivaluado; los demas no llevan multiplicidad, para que EA no muestre [0..1]. La de los extremos de
 *     asociacion no cambia.
 */
class EscritorXmiSalidaTest {

    private static final UUID ORDEN = id(5);

    private static UUID id(int n) {
        return UUID.fromString(String.format("c0000000-0000-4000-8000-%012d", n));
    }

    private static UmlClass clase(UUID id, String nombre) {
        UmlClass c = new UmlClass();
        c.setId(id);
        c.setDiagramId(DIAGRAMA);
        c.setName(nombre);
        return c;
    }

    private static UmlRelationship relacion(int n, UUID origen, UUID destino, String rolOrigen, String rolDestino) {
        UmlRelationship r = new UmlRelationship();
        r.setId(UUID.fromString(String.format("f0000000-0000-4000-8000-%012d", n)));
        r.setDiagramId(DIAGRAMA);
        r.setSourceClassId(origen);
        r.setTargetClassId(destino);
        r.setKind(RelationKind.ASSOCIATION);
        r.setSourceRole(rolOrigen);
        r.setTargetRole(rolDestino);
        r.setSourceMultiplicity("0..*");
        r.setTargetMultiplicity("1");
        return r;
    }

    private static UmlAttribute atributo(int n, UUID clase, String nombre, boolean requerido, boolean multi) {
        UmlAttribute a = new UmlAttribute();
        a.setId(UUID.fromString(String.format("a0000000-0000-4000-8000-%012d", n)));
        a.setClassId(clase);
        a.setName(nombre);
        a.setRequired(requerido);
        a.setMultivalued(multi);
        a.setOrdinal(n);
        return a;
    }

    private static EscritorXmi.Exportado exportar(EscritorXmi.Perfil perfil, List<UmlAttribute> atributos,
                                                  List<UmlRelationship> relaciones) {
        return EscritorXmi.exportar(List.of(clase(PEDIDO, "Pedido"), clase(CLIENTE, "Cliente"), clase(ORDEN, "Orden")),
                atributos, List.of(), relaciones, List.of(), perfil);
    }

    // ================================================================== ROLES

    @ParameterizedTest
    @EnumSource(EscritorXmi.Perfil.class)
    void un_rol_que_repite_el_nombre_de_la_clase_no_se_exporta_y_uno_distinto_si(EscritorXmi.Perfil perfil) {
        EscritorXmi.Exportado ex = exportar(perfil, List.of(), List.of(
                relacion(1, PEDIDO, CLIENTE, "pedidos", "cliente"),      // plural +s y minuscula: los dos redundantes
                relacion(2, ORDEN, CLIENTE, "ordenes", "comprador"),     // plural +es redundante; "comprador" se queda
                relacion(3, PEDIDO, ORDEN, "Pedido", "orden")));         // "Pedido" con mayuscula NO es el rol minusculo
        Document doc = parsear(ex.xmi());

        List<String> nombresDeExtremos = elementos(doc, "ownedEnd").stream()
                .map(e -> e.getAttribute("name")).toList();
        assertThat(nombresDeExtremos).containsExactly("", "", "", "comprador", "Pedido", "");   // "" = sin nombre
        if (perfil == EscritorXmi.Perfil.ENTERPRISE_ARCHITECT) {
            List<String> rolesDeConectores = elementos(doc, "role").stream().map(e -> e.getAttribute("name")).toList();
            assertThat(rolesDeConectores).containsExactly("", "", "", "comprador", "Pedido", "");
        }
    }

    @ParameterizedTest
    @EnumSource(EscritorXmi.Perfil.class)
    void se_declara_en_un_solo_aviso_aunque_haya_varias_relaciones(EscritorXmi.Perfil perfil) {
        EscritorXmi.Exportado ex = exportar(perfil, List.of(), List.of(
                relacion(1, PEDIDO, CLIENTE, "pedidos", "cliente"), relacion(2, ORDEN, CLIENTE, "ordenes", "cliente")));
        assertThat(ex.perdidas()).filteredOn(p -> p.contains("roles de relacion")).hasSize(1);

        EscritorXmi.Exportado sin = exportar(perfil, List.of(), List.of(
                relacion(1, PEDIDO, CLIENTE, "compras", "comprador")));
        assertThat(sin.perdidas()).as("si no se omitio ningun rol, no hay nada que avisar")
                .noneMatch(p -> p.contains("roles de relacion"));
    }

    @ParameterizedTest
    @EnumSource(EscritorXmi.Perfil.class)
    void al_releer_un_extremo_sin_rol_no_rompe_el_lector(EscritorXmi.Perfil perfil) {
        EscritorXmi.Exportado ex = exportar(perfil, List.of(), List.of(
                relacion(1, PEDIDO, CLIENTE, "pedidos", "cliente"), relacion(2, ORDEN, CLIENTE, "ordenes", "comprador")));
        ModeloLeido leido = new LectorXmi().leer(ex.xmi());

        assertThat(leido.relaciones()).hasSize(2);
        assertThat(leido.clases()).extracting(ModeloLeido.ClaseLeida::nombre)
                .containsExactlyInAnyOrder("Pedido", "Cliente", "Orden");
    }

    // ========================================================== MULTIPLICIDAD

    @ParameterizedTest
    @EnumSource(EscritorXmi.Perfil.class)
    void solo_los_atributos_multivaluados_llevan_multiplicidad(EscritorXmi.Perfil perfil) {
        EscritorXmi.Exportado ex = exportar(perfil, List.of(
                atributo(1, PEDIDO, "numero", true, false),
                atributo(2, PEDIDO, "nota", false, false),
                atributo(3, PEDIDO, "etiquetas", false, true)), List.of());
        Document doc = parsear(ex.xmi());

        for (Element a : elementos(doc, "ownedAttribute")) {
            boolean multi = a.getAttribute("name").equals("etiquetas");
            assertThat(hijos(a, "lowerValue")).as(a.getAttribute("name")).hasSize(multi ? 1 : 0);
            assertThat(hijos(a, "upperValue")).as(a.getAttribute("name")).hasSize(multi ? 1 : 0);
            if (multi) assertThat(hijos(a, "upperValue").get(0).getAttribute("value")).isEqualTo("*");
        }
        if (perfil == EscritorXmi.Perfil.ENTERPRISE_ARCHITECT) {
            List<Element> bounds = new ArrayList<>();
            for (Element a : elementos(doc, "attribute")) bounds.addAll(hijos(a, "bounds"));
            assertThat(bounds).as("en la Extension de EA, `bounds` solo para el multivaluado").hasSize(1);
        }
    }

    @ParameterizedTest
    @EnumSource(EscritorXmi.Perfil.class)
    void la_multiplicidad_de_los_extremos_de_asociacion_no_cambia(EscritorXmi.Perfil perfil) {
        Document doc = parsear(exportar(perfil, List.of(), List.of(relacion(1, PEDIDO, CLIENTE, "x", "y"))).xmi());

        List<Element> extremos = elementos(doc, "ownedEnd");
        assertThat(hijos(extremos.get(0), "upperValue").get(0).getAttribute("value")).isEqualTo("*");
        assertThat(hijos(extremos.get(1), "upperValue").get(0).getAttribute("value")).isEqualTo("1");
        assertThat(hijos(extremos.get(1), "lowerValue")).hasSize(1);
    }

    @ParameterizedTest
    @EnumSource(EscritorXmi.Perfil.class)
    void el_dato_opcional_se_declara_una_sola_vez_y_solo_si_se_pierde(EscritorXmi.Perfil perfil) {
        EscritorXmi.Exportado dos = exportar(perfil, List.of(
                atributo(1, PEDIDO, "nota", false, false), atributo(2, PEDIDO, "obs", false, false)), List.of());
        assertThat(dos.perdidas()).filteredOn(p -> p.contains("multiplicidad de los atributos")).hasSize(1);

        EscritorXmi.Exportado nada = exportar(perfil, List.of(
                atributo(1, PEDIDO, "numero", true, false), atributo(2, PEDIDO, "etiquetas", false, true)), List.of());
        assertThat(nada.perdidas()).as("obligatorios y multivaluados no pierden nada")
                .noneMatch(p -> p.contains("multiplicidad de los atributos"));
    }

    @ParameterizedTest
    @EnumSource(EscritorXmi.Perfil.class)
    void al_releer_un_atributo_sin_multiplicidad_no_rompe_el_lector(EscritorXmi.Perfil perfil) {
        EscritorXmi.Exportado ex = exportar(perfil, List.of(atributo(1, PEDIDO, "nota", false, false)), List.of());
        ModeloLeido leido = new LectorXmi().leer(ex.xmi());

        ModeloLeido.AtributoLeido nota = leido.clases().stream().filter(c -> c.nombre().equals("Pedido"))
                .findFirst().orElseThrow().atributos().get(0);
        assertThat(nota.nombre()).isEqualTo("nota");
        // Hallazgo para Rodolfo: sin multiplicidad el lector asume 1..1 (valor por omision de UML), asi que el
        // atributo opcional vuelve obligatorio. No rompe; cambia el dato. Por eso se declara como perdida.
        assertThat(nota.requerido()).isTrue();
    }
}
