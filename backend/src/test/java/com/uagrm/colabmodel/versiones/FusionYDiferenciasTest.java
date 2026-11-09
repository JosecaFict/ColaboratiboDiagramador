package com.uagrm.colabmodel.versiones;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Diff por id y merge a tres bandas sobre fotos armadas a mano: lo que se puede
 * afirmar sin base. Que el resultado se aplique por el bus lo prueba
 * VersionesTest contra PostgreSQL.
 */
class FusionYDiferenciasTest {

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    /** Una foto minima escrita como la guarda un commit. */
    static final class Modelo {
        final ObjectNode raiz = JSON.objectNode();

        Modelo() {
            raiz.put("id", UUID.randomUUID().toString());
            raiz.putArray("classes");
            raiz.putArray("relationships");
            raiz.putArray("layout");
            raiz.putArray("decisions");
        }

        Modelo copia() {
            Modelo m = new Modelo();
            m.raiz.setAll(raiz.deepCopy());
            return m;
        }

        ObjectNode clase(UUID id, String nombre, double x) {
            ObjectNode c = ((com.fasterxml.jackson.databind.node.ArrayNode) raiz.get("classes")).addObject();
            c.put("id", id.toString()).put("name", nombre).put("isAbstract", false).put("weak", false)
                    .put("erKind", "STRONG").put("inheritanceStrategy", "JOINED");
            c.putArray("attributes");
            c.putArray("methods");
            ((com.fasterxml.jackson.databind.node.ArrayNode) raiz.get("layout")).addObject()
                    .put("elementId", id.toString()).put("x", x).put("y", 0).put("width", 170).put("height", 60);
            return c;
        }

        ObjectNode atributo(UUID clase, UUID id, String nombre, String tipo) {
            for (JsonNode c : raiz.get("classes")) {
                if (c.path("id").asText().equals(clase.toString())) {
                    return ((com.fasterxml.jackson.databind.node.ArrayNode) c.get("attributes")).addObject()
                            .put("id", id.toString()).put("classId", clase.toString()).put("name", nombre)
                            .put("dataType", tipo).put("visibility", "PRIVATE").put("ordinal", 0);
                }
            }
            throw new IllegalArgumentException("no hay clase " + clase);
        }

        ObjectNode buscar(UUID id) {
            for (JsonNode c : raiz.get("classes")) {
                if (c.path("id").asText().equals(id.toString())) return (ObjectNode) c;
                for (JsonNode a : c.get("attributes")) {
                    if (a.path("id").asText().equals(id.toString())) return (ObjectNode) a;
                }
            }
            throw new IllegalArgumentException("no hay " + id);
        }

        void borrar(UUID id) {
            Iterator<JsonNode> it = raiz.get("classes").elements();
            while (it.hasNext()) {
                JsonNode c = it.next();
                if (c.path("id").asText().equals(id.toString())) { it.remove(); return; }
                Iterator<JsonNode> at = c.get("attributes").elements();
                while (at.hasNext()) if (at.next().path("id").asText().equals(id.toString())) { at.remove(); return; }
            }
        }

        Foto foto() { return Foto.leer(raiz); }
    }

    private final UUID libro = UUID.randomUUID();
    private final UUID socio = UUID.randomUUID();
    private final UUID titulo = UUID.randomUUID();

    private Modelo base() {
        Modelo m = new Modelo();
        m.clase(libro, "Libro", 0);
        m.clase(socio, "Socio", 300);
        m.atributo(libro, titulo, "titulo", "string");
        return m;
    }

    // --------------------------------------------------------------- DIFF

    @Test
    void el_diff_empareja_por_id_y_dice_que_campo_cambio() {
        Modelo antes = base();
        Modelo despues = antes.copia();
        despues.buscar(libro).put("name", "Ejemplar");
        despues.borrar(socio);
        UUID isbn = UUID.randomUUID();
        despues.atributo(libro, isbn, "isbn", "string");

        ObjectNode d = Diferencias.entre(antes.foto(), despues.foto());

        assertThat(d.at("/clases/modificadas/0/id").asText()).isEqualTo(libro.toString());
        assertThat(d.at("/clases/modificadas/0/cambios/0/campo").asText()).isEqualTo("name");
        assertThat(d.at("/clases/modificadas/0/cambios/0/antes").asText()).isEqualTo("Libro");
        assertThat(d.at("/clases/modificadas/0/cambios/0/despues").asText()).isEqualTo("Ejemplar");
        assertThat(d.at("/clases/eliminadas/0/nombre").asText()).isEqualTo("Socio");
        assertThat(d.at("/atributos/agregadas/0/nombre").asText()).isEqualTo("isbn");
        assertThat(d.at("/atributos/agregadas/0/claseId").asText()).isEqualTo(libro.toString());
        assertThat(d.at("/atributos/agregadas/0/clase").asText()).isEqualTo("Ejemplar");
        assertThat(d.at("/metodos/agregadas").isEmpty()).isTrue();
    }

    @Test
    void la_foto_guardada_y_la_releida_no_dan_diferencias_falsas() {
        Modelo m = base();
        // 200 y 200.0, 1 y 1.0000 son lo mismo.
        m.buscar(titulo).put("minValue", 1).put("length", 40);
        Foto f = m.foto();
        Foto releida = Foto.leer(f.aJson());

        assertThat(Diferencias.vacio(Diferencias.entre(f, releida))).isTrue();
    }

    @Test
    void mover_una_caja_aparece_como_cambio_de_posicion() {
        Modelo antes = base();
        Modelo despues = antes.copia();
        ((ObjectNode) despues.raiz.get("layout").get(0)).put("x", 99);

        ObjectNode d = Diferencias.entre(antes.foto(), despues.foto());

        assertThat(d.at("/clases/modificadas/0/cambios/0/campo").asText()).isEqualTo("x");
        assertThat(d.at("/clases/modificadas/0/cambios/0/despues").asDouble()).isEqualTo(99);
    }

    // -------------------------------------------------------------- MERGE

    @Test
    void cambios_de_lados_distintos_se_combinan_sin_conflicto() {
        Modelo base = base();
        Modelo nuestro = base.copia();
        nuestro.buscar(libro).put("name", "Ejemplar");
        Modelo suyo = base.copia();
        suyo.buscar(titulo).put("dataType", "text");
        UUID dni = UUID.randomUUID();
        suyo.atributo(socio, dni, "dni", "string");

        var r = Fusion.combinar(base.foto(), nuestro.foto(), suyo.foto(), Map.of());

        assertThat(r.conflictos()).isEmpty();
        assertThat(r.combinada().clases.get(libro).texto("name")).isEqualTo("Ejemplar");
        assertThat(r.combinada().atributos.get(titulo).texto("dataType")).isEqualTo("text");
        assertThat(r.combinada().atributos).containsKey(dni);
    }

    @Test
    void el_mismo_campo_cambiado_distinto_en_los_dos_lados_es_conflicto_y_se_resuelve() {
        Modelo base = base();
        Modelo nuestro = base.copia();
        nuestro.buscar(libro).put("name", "Ejemplar");
        Modelo suyo = base.copia();
        suyo.buscar(libro).put("name", "Obra");

        var sinResolver = Fusion.combinar(base.foto(), nuestro.foto(), suyo.foto(), Map.of());
        assertThat(sinResolver.conflictos()).hasSize(1);
        Fusion.Conflicto c = sinResolver.conflictos().get(0);
        assertThat(c.elementId()).isEqualTo(libro);
        assertThat(c.tipo()).isEqualTo("clase");
        assertThat(c.campo()).isEqualTo("name");
        assertThat(c.base().asText()).isEqualTo("Libro");
        assertThat(c.nuestro().asText()).isEqualTo("Ejemplar");
        assertThat(c.suyo().asText()).isEqualTo("Obra");
        assertThat(sinResolver.sinResolver()).hasSize(1);

        var resuelto = Fusion.combinar(base.foto(), nuestro.foto(), suyo.foto(),
                Map.of(Fusion.clave(libro, "name"), Fusion.Lado.SUYO));
        assertThat(resuelto.sinResolver()).isEmpty();
        assertThat(resuelto.combinada().clases.get(libro).texto("name")).isEqualTo("Obra");
    }

    @Test
    void borrar_de_un_lado_lo_que_el_otro_cambio_es_conflicto_de_existencia() {
        Modelo base = base();
        Modelo nuestro = base.copia();
        nuestro.borrar(libro);
        Modelo suyo = base.copia();
        // No le cambian la clase: le agregan un atributo. Tambien cuenta.
        UUID isbn = UUID.randomUUID();
        suyo.atributo(libro, isbn, "isbn", "string");

        var r = Fusion.combinar(base.foto(), nuestro.foto(), suyo.foto(), Map.of());
        assertThat(r.conflictos()).singleElement().satisfies(c -> {
            assertThat(c.campo()).isEqualTo("existencia");
            assertThat(c.base().asText()).isEqualTo("existe");
            assertThat(c.nuestro().asText()).isEqualTo("eliminado");
            assertThat(c.suyo().asText()).isEqualTo("existe");
        });
        // Por defecto, "nuestro": la clase y lo suyo se van.
        assertThat(r.combinada().clases).doesNotContainKey(libro);
        assertThat(r.combinada().atributos).doesNotContainKey(isbn);

        var suyo_ = Fusion.combinar(base.foto(), nuestro.foto(), suyo.foto(),
                Map.of(Fusion.clave(libro, "existencia"), Fusion.Lado.SUYO));
        assertThat(suyo_.combinada().clases).containsKey(libro);
        // Si se queda la clase, se queda con TODO lo suyo, tambien lo que no cambio.
        assertThat(suyo_.combinada().atributos).containsKeys(isbn, titulo);
    }

    @Test
    void borrar_lo_que_el_otro_no_toco_es_un_borrado_limpio() {
        Modelo base = base();
        Modelo nuestro = base.copia();
        Modelo suyo = base.copia();
        suyo.borrar(socio);

        var r = Fusion.combinar(base.foto(), nuestro.foto(), suyo.foto(), Map.of());

        assertThat(r.conflictos()).isEmpty();
        assertThat(r.combinada().clases).doesNotContainKey(socio).containsKey(libro);
    }

    @Test
    void moverla_en_los_dos_lados_no_es_conflicto_y_gana_nuestro() {
        Modelo base = base();
        Modelo nuestro = base.copia();
        ((ObjectNode) nuestro.raiz.get("layout").get(0)).put("x", 10);
        Modelo suyo = base.copia();
        ((ObjectNode) suyo.raiz.get("layout").get(0)).put("x", 20).put("y", 5);

        var r = Fusion.combinar(base.foto(), nuestro.foto(), suyo.foto(), Map.of());

        assertThat(r.conflictos()).isEmpty();
        assertThat(r.combinada().geometria.get(libro).campos().get("x").asDouble()).isEqualTo(10);
        // y solo lo cambio "suyo": llega.
        assertThat(r.combinada().geometria.get(libro).campos().get("y").asDouble()).isEqualTo(5);
    }
}
