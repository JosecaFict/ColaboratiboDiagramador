package com.uagrm.colabmodel.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class ReparadorDirigidoPlanTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void agrega_el_enlace_faltante_sin_reescribir_clases_ni_atributos() throws Exception {
        var original = borrador();
        var ajuste = comando("CREATE_RELATION", """
                {"claseOrigen":"Producto","claseDestino":"Inventario","kind":"ASSOCIATION",
                "sourceMultiplicity":"1","targetMultiplicity":"0..1"}
                """);
        var resultado = ReparadorDirigidoPlan.aplicar(original, new RespuestaIA(List.of(ajuste), ""));
        assertThat(resultado.comandos().getFirst().parametros().path("classes"))
                .isEqualTo(original.comandos().getFirst().parametros().path("classes"));
        assertThat(resultado.comandos().getFirst().parametros().path("relations")).hasSize(1);
        assertThat(original.comandos().getFirst().parametros().path("relations")).isEmpty();
    }

    @Test
    void un_ajuste_invalido_no_deja_cambios_parciales_en_el_borrador() throws Exception {
        var original = borrador();
        var valido = comando("ADD_ATTRIBUTE", """
                {"clase":"Producto","name":"precio","dataType":"decimal"}
                """);
        var invalido = comando("DELETE_CLASS", "{\"clase\":\"Producto\"}");
        assertThatThrownBy(() -> ReparadorDirigidoPlan.aplicar(original, new RespuestaIA(List.of(valido, invalido), "")))
                .isInstanceOf(ProveedorIAException.class);
        assertThat(original.comandos().getFirst().parametros().toString()).doesNotContain("precio");
    }

    @Test
    void no_actualiza_una_pareja_con_dos_roles_sin_saber_cual_corregir() throws Exception {
        var original = borrador();
        var p = original.comandos().getFirst().parametros();
        var rs = (com.fasterxml.jackson.databind.node.ArrayNode) p.path("relations");
        for (String rol : List.of("origen", "destino")) rs.addObject().put("source", "Producto")
                .put("target", "Inventario").put("kind", "ASSOCIATION").put("targetRole", rol);
        var ajuste = comando("UPDATE_RELATION", """
                {"claseOrigen":"Producto","claseDestino":"Inventario","sourceMultiplicity":"0..*"}
                """);
        assertThatThrownBy(() -> ReparadorDirigidoPlan.aplicar(original, new RespuestaIA(List.of(ajuste), "")))
                .isInstanceOf(ProveedorIAException.class).hasMessageContaining("ambigua");
    }

    @Test
    void permite_completar_una_entidad_omitida_con_sus_datos_y_vinculos() throws Exception {
        var original = borrador();
        var ajustes = List.of(comando("CREATE_CLASS", "{\"name\":\"Proveedor\"}"),
                comando("ADD_ATTRIBUTE", "{\"clase\":\"Proveedor\",\"name\":\"idProveedor\",\"dataType\":\"uuid\",\"pk\":true}"),
                comando("ADD_ATTRIBUTE", "{\"clase\":\"Proveedor\",\"name\":\"nombre\",\"dataType\":\"string\"}"),
                comando("CREATE_RELATION", """
                        {"claseOrigen":"Producto","claseDestino":"Proveedor","kind":"ASSOCIATION",
                        "sourceMultiplicity":"0..*","targetMultiplicity":"1"}
                        """));
        var resultado = ReparadorDirigidoPlan.aplicar(original, new RespuestaIA(ajustes, ""));
        assertThat(resultado.comandos().getFirst().parametros().path("classes")).hasSize(3);
        assertThat(original.comandos().getFirst().parametros().path("classes")).hasSize(2);
    }

    @Test
    void una_clase_nueva_no_habilita_cambiar_identidades_de_las_existentes() throws Exception {
        var original = borrador();
        var ajustes = List.of(comando("CREATE_CLASS", "{\"name\":\"Proveedor\"}"),
                comando("ADD_ATTRIBUTE", "{\"clase\":\"Producto\",\"name\":\"otraClave\",\"dataType\":\"uuid\",\"pk\":true}"));
        assertThatThrownBy(() -> ReparadorDirigidoPlan.aplicar(original, new RespuestaIA(ajustes, "")))
                .isInstanceOf(ProveedorIAException.class).hasMessageContaining("identidades existentes");
        assertThat(original.comandos().getFirst().parametros().path("classes")).hasSize(2);
    }

    @Test
    void rechaza_expansion_ilimitada_y_no_altera_el_borrador() throws Exception {
        var original = borrador();
        var ajustes = List.of(comando("CREATE_CLASS", "{\"name\":\"Uno\"}"),
                comando("CREATE_CLASS", "{\"name\":\"Dos\"}"), comando("CREATE_CLASS", "{\"name\":\"Tres\"}"));
        assertThatThrownBy(() -> ReparadorDirigidoPlan.aplicar(original, new RespuestaIA(ajustes, "")))
                .isInstanceOf(ProveedorIAException.class).hasMessageContaining("hasta dos");
        assertThat(original.comandos().getFirst().parametros().path("classes")).hasSize(2);
    }

    private RespuestaIA borrador() throws Exception {
        return new RespuestaIA(List.of(comando(EsquemaPlanUml.NOMBRE, """
                {"classes":[{"name":"Producto","attributes":[{"name":"codigo","dataType":"string"}]},
                {"name":"Inventario","attributes":[{"name":"cantidad","dataType":"int"}]}],
                "relations":[],"audit":["supuesto","restriccion"]}
                """)), "");
    }

    @Test
    void los_indices_siguen_identificando_la_relacion_original_despues_de_borrar_otra() throws Exception {
        var original = borrador();
        var rs = (com.fasterxml.jackson.databind.node.ArrayNode) original.comandos().getFirst().parametros().path("relations");
        for (String rol : List.of("anterior", "destino")) rs.addObject().put("source", "Producto")
                .put("target", "Inventario").put("kind", "ASSOCIATION").put("targetRole", rol)
                .put("sourceMultiplicity", "1").put("targetMultiplicity", "1");
        var borrar = comando("DELETE_RELATION", """
                {"claseOrigen":"Producto","claseDestino":"Inventario","indiceRelacion":0}
                """);
        var actualizar = comando("UPDATE_RELATION", """
                {"claseOrigen":"Producto","claseDestino":"Inventario","indiceRelacion":1,"targetRole":"origen"}
                """);
        var resultado = ReparadorDirigidoPlan.aplicar(original, new RespuestaIA(List.of(borrar, actualizar), ""));
        var finales = resultado.comandos().getFirst().parametros().path("relations");
        assertThat(finales).hasSize(1);
        assertThat(finales.get(0).path("targetRole").asText()).isEqualTo("origen");
        assertThat(original.comandos().getFirst().parametros().path("relations")).hasSize(2);
    }
    private ComandoPropuestoIA comando(String tipo, String json) throws Exception {
        return new ComandoPropuestoIA(tipo, mapper.readTree(json));
    }
}
