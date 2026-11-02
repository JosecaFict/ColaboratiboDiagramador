package com.uagrm.colabmodel.versiones;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uagrm.colabmodel.PostgresDePruebas;
import com.uagrm.colabmodel.collab.LockManager;
import com.uagrm.colabmodel.modelcore.command.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Versiones tipo Git (ADR-037) contra el sistema real: Spring Boot completo,
 * PostgreSQL con V8 aplicada, la cadena de seguridad y el bus de comandos.
 *
 * El modelo se edita por el bus, como lo haria el canvas: asi restaurar y
 * mergear se prueban contra el mismo applier, los mismos locks y las mismas
 * restricciones de la base que cualquier edicion. Lo que crea cada prueba
 * lleva una marca unica y se borra al terminar. Usa `colabmodel_test`.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class VersionesTest extends PostgresDePruebas {

    @LocalServerPort
    int puerto;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    CommandBus bus;
    @Autowired
    LockManager locks;

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();
    private final String marca = UUID.randomUUID().toString().substring(0, 8);

    record Resp(int estado, JsonNode cuerpo) { }

    record Cuenta(String nombre, String email, UUID id, String token) { }

    @AfterEach
    void borrarLoQueCreoLaPrueba() {
        String patron = "it-%-" + marca + "@prueba.test";
        jdbc.update("delete from projects where owner_id in (select id from users where email like ?)", patron);
        jdbc.update("delete from users where email like ?", patron);
    }

    // ============================================================ COMMIT

    @Test
    void commit_historial_version_y_diff_con_la_forma_del_contrato() throws Exception {
        Cuenta ana = registrar("Ana");
        UUID d = crearProyecto(ana);
        UUID libro = clase(d, ana, "Libro", 10, 20);
        UUID titulo = atributo(d, ana, libro, "titulo", "string");
        UUID socio = clase(d, ana, "Socio", 300, 20);

        Resp v1 = llamar("POST", "/api/v1/diagrams/" + d + "/versions", ana.token(),
                Map.of("name", "v1", "message", "Primera"));
        assertThat(v1.estado()).isEqualTo(201);
        assertThat(v1.cuerpo().get("name").asText()).isEqualTo("v1");
        assertThat(v1.cuerpo().get("authorName").asText()).isEqualTo("Ana");
        assertThat(v1.cuerpo().get("seq").asLong()).isEqualTo(seq(d));
        UUID v1Id = id(v1);

        op(d, ana, "RENAME_CLASS", Map.of("classId", libro, "name", "Ejemplar"));
        op(d, ana, "DELETE_CLASS", Map.of("classId", socio));
        UUID isbn = atributo(d, ana, libro, "isbn", "string");
        op(d, ana, "MOVE_ELEMENT", Map.of("elementId", libro, "elementKind", "CLASS", "x", 99, "y", 20));

        Resp diff = llamar("GET", "/api/v1/diagrams/" + d + "/diff?from=" + v1Id, ana.token(), null);
        assertThat(diff.estado()).isEqualTo(200);
        JsonNode c = diff.cuerpo();
        assertThat(c.at("/desde/versionId").asText()).isEqualTo(v1Id.toString());
        assertThat(c.at("/hasta/versionId").isNull()).isTrue();
        assertThat(c.at("/hasta/seq").asLong()).isEqualTo(seq(d));
        assertThat(c.at("/clases/eliminadas/0/id").asText()).isEqualTo(socio.toString());
        JsonNode modificada = c.at("/clases/modificadas/0");
        assertThat(modificada.get("id").asText()).isEqualTo(libro.toString());
        assertThat(campos(modificada)).contains("name", "x");
        assertThat(c.at("/atributos/agregadas/0/id").asText()).isEqualTo(isbn.toString());
        assertThat(c.at("/atributos/agregadas/0/clase").asText()).isEqualTo("Ejemplar");
        assertThat(c.at("/atributos/agregadas/0/claseId").asText()).isEqualTo(libro.toString());
        assertThat(c.at("/metodos/agregadas").isArray()).isTrue();
        assertThat(c.at("/relaciones/modificadas").isArray()).isTrue();

        Resp v2 = llamar("POST", "/api/v1/diagrams/" + d + "/versions", ana.token(), Map.of("name", "v2"));
        assertThat(v2.cuerpo().get("parentVersionId").asText()).isEqualTo(v1Id.toString());

        Resp historial = llamar("GET", "/api/v1/diagrams/" + d + "/versions", ana.token(), null);
        assertThat(historial.cuerpo()).hasSize(2);
        assertThat(historial.cuerpo().get(0).get("name").asText()).isEqualTo("v2");

        Resp una = llamar("GET", "/api/v1/diagrams/" + d + "/versions/" + v1Id, ana.token(), null);
        assertThat(una.cuerpo().at("/snapshot/classes")).hasSize(2);
        assertThat(una.cuerpo().at("/snapshot/classes/0/attributes/0/id").asText()).isEqualTo(titulo.toString());

        // Entre dos versiones tambien.
        Resp entreVersiones = llamar("GET", "/api/v1/diagrams/" + d + "/diff?from=" + v1Id + "&to=" + id(v2),
                ana.token(), null);
        assertThat(entreVersiones.cuerpo().at("/hasta/versionId").asText()).isEqualTo(id(v2).toString());
        assertThat(entreVersiones.cuerpo().at("/clases/eliminadas")).hasSize(1);

        Resp repetida = llamar("POST", "/api/v1/diagrams/" + d + "/versions", ana.token(), Map.of("name", "V1"));
        assertThat(repetida.estado()).isEqualTo(409);
        assertThat(repetida.cuerpo().get("mensaje").asText()).contains("V1").contains("Ya hay");
    }

    @Test
    void un_observador_lee_el_historial_pero_no_puede_commitear_ni_restaurar() throws Exception {
        Cuenta ana = registrar("Ana");
        Cuenta oscar = registrar("Oscar");
        UUID d = crearProyecto(ana);
        agregarMiembro(ana, d, oscar, "VIEWER");
        clase(d, ana, "Libro", 0, 0);
        UUID v1 = id(llamar("POST", "/api/v1/diagrams/" + d + "/versions", ana.token(), Map.of("name", "v1")));

        assertThat(llamar("GET", "/api/v1/diagrams/" + d + "/versions", oscar.token(), null).estado()).isEqualTo(200);
        assertThat(llamar("GET", "/api/v1/diagrams/" + d + "/diff?from=" + v1, oscar.token(), null).estado())
                .isEqualTo(200);
        Resp commit = llamar("POST", "/api/v1/diagrams/" + d + "/versions", oscar.token(), Map.of("name", "mia"));
        assertThat(commit.estado()).isEqualTo(403);
        assertThat(llamar("POST", "/api/v1/diagrams/" + d + "/versions/" + v1 + "/restore", oscar.token(), null)
                .estado()).isEqualTo(403);
        assertThat(llamar("POST", "/api/v1/diagrams/" + d + "/branches", oscar.token(),
                Map.of("name", "x", "fromVersionId", v1)).estado()).isEqualTo(403);

        Cuenta extrano = registrar("Extrano");
        for (String ruta : List.of("/versions", "/branches", "/diff?from=" + v1)) {
            Resp ajeno = llamar("GET", "/api/v1/diagrams/" + d + ruta, extrano.token(), null);
            assertThat(ajeno.estado()).as(ruta).isEqualTo(403);
            assertThat(ajeno.cuerpo().get("mensaje").asText()).as(ruta).contains("No sos miembro");
        }
        // Un diagrama que no existe se ve igual que uno ajeno, con la misma forma.
        Resp inexistente = llamar("GET", "/api/v1/diagrams/" + UUID.randomUUID() + "/branches", ana.token(), null);
        assertThat(inexistente.estado()).isEqualTo(403);
        assertThat(inexistente.cuerpo().get("mensaje").asText()).contains("No sos miembro");
    }

    // =========================================================== RESTORE

    @Test
    void restaurar_vuelve_exactamente_a_la_version_con_un_solo_batch_en_el_log() throws Exception {
        Cuenta ana = registrar("Ana");
        UUID d = crearProyecto(ana);
        UUID libro = clase(d, ana, "Libro", 10, 20);
        UUID socio = clase(d, ana, "Socio", 300, 20);
        UUID titulo = atributo(d, ana, libro, "titulo", "string");
        UUID precio = atributo(d, ana, libro, "precio", "decimal");
        op(d, ana, "UPDATE_ATTRIBUTE", Map.of("attributeId", precio, "minValue", 0));
        UUID prestar = UUID.randomUUID();
        op(d, ana, "ADD_METHOD", Map.of("classId", libro, "methodId", prestar, "name", "prestar",
                "params", List.of(Map.of("name", "socio", "type", "Socio"))));
        UUID presta = UUID.randomUUID();
        op(d, ana, "CREATE_RELATION", Map.of("relationId", presta, "sourceClassId", socio, "targetClassId", libro,
                "kind", "ASSOCIATION", "sourceMultiplicity", "1", "targetMultiplicity", "0..*"));
        op(d, ana, "MOVE_ELEMENT", Map.of("elementId", presta, "elementKind", "RELATION",
                "waypoints", List.of(Map.of("x", 150, "y", 80))));
        UUID v1 = id(llamar("POST", "/api/v1/diagrams/" + d + "/versions", ana.token(), Map.of("name", "v1")));

        // Lo que hace dificil volver: intercambiar dos nombres (indice unico),
        // un metodo cambiado (no hay UPDATE_METHOD), una clase que se volvio
        // abstracta (CREATE_CLASS es lo unico que la fija), un atributo borrado,
        // una relacion borrada y otra clase nueva.
        op(d, ana, "RENAME_CLASS", Map.of("classId", libro, "name", "Tmp"));
        op(d, ana, "RENAME_CLASS", Map.of("classId", socio, "name", "Libro"));
        op(d, ana, "RENAME_CLASS", Map.of("classId", libro, "name", "Socio"));
        op(d, ana, "REMOVE_METHOD", Map.of("methodId", prestar));
        op(d, ana, "ADD_METHOD", Map.of("classId", libro, "methodId", prestar, "name", "prestar", "returnType", "boolean"));
        op(d, ana, "REMOVE_ATTRIBUTE", Map.of("attributeId", titulo));
        op(d, ana, "UPDATE_ATTRIBUTE", Map.of("attributeId", precio, "minValue", 5));
        op(d, ana, "DELETE_RELATION", Map.of("relationId", presta));
        op(d, ana, "DELETE_CLASS", Map.of("classId", socio));
        op(d, ana, "CREATE_CLASS", Map.of("classId", socio, "name", "Libro", "isAbstract", true));
        clase(d, ana, "Autor", 600, 0);
        op(d, ana, "MOVE_ELEMENT", Map.of("elementId", libro, "elementKind", "CLASS", "x", 500, "y", 500));

        long antes = seq(d);
        Resp r = llamar("POST", "/api/v1/diagrams/" + d + "/versions/" + v1 + "/restore", ana.token(), null);
        assertThat(r.estado()).as(String.valueOf(r.cuerpo())).isEqualTo(200);
        assertThat(r.cuerpo().get("seq").asLong()).isEqualTo(antes + 1);
        assertThat(r.cuerpo().get("cambios").asInt()).isGreaterThan(5);

        Resp diff = llamar("GET", "/api/v1/diagrams/" + d + "/diff?from=" + v1, ana.token(), null);
        assertThat(sinCambios(diff.cuerpo())).as(diff.cuerpo().toString()).isTrue();
        // Mismos ids: lo que nombraba a esos elementos sigue apuntando a ellos.
        assertThat(jdbc.queryForObject("select name from uml_class where id = ? and deleted_at is null",
                String.class, libro)).isEqualTo("Libro");
        assertThat(jdbc.queryForObject("select is_abstract from uml_class where id = ?", Boolean.class, socio)).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from uml_attribute where id = ?", Integer.class, titulo)).isEqualTo(1);

        // UNA operacion en el log, un BATCH, con su autor.
        Map<String, Object> ultima = jdbc.queryForMap(
                "select op_type, user_id, payload->>'descripcion' as d from diagram_operations where diagram_id = ? and seq = ?",
                d, antes + 1);
        assertThat(ultima.get("op_type")).isEqualTo("BATCH");
        assertThat(ultima.get("user_id")).isEqualTo(ana.id());
        assertThat((String) ultima.get("d")).contains("v1");

        // Restaurar lo que ya esta no gasta seq.
        Resp otraVez = llamar("POST", "/api/v1/diagrams/" + d + "/versions/" + v1 + "/restore", ana.token(), null);
        assertThat(otraVez.cuerpo().get("cambios").asInt()).isZero();
        assertThat(seq(d)).isEqualTo(antes + 1);
    }

    @Test
    void restaurar_respeta_los_locks_y_dice_quien_tiene_el_elemento() throws Exception {
        Cuenta ana = registrar("Ana");
        Cuenta beto = registrar("Beto");
        UUID d = crearProyecto(ana);
        agregarMiembro(ana, d, beto, "EDITOR");
        UUID libro = clase(d, ana, "Libro", 0, 0);
        UUID v1 = id(llamar("POST", "/api/v1/diagrams/" + d + "/versions", ana.token(), Map.of("name", "v1")));
        op(d, ana, "RENAME_CLASS", Map.of("classId", libro, "name", "Ejemplar"));
        long antes = seq(d);

        assertThat(locks.acquire(d, libro, beto.id(), "sesion-de-beto", "Beto")).isTrue();
        try {
            Resp r = llamar("POST", "/api/v1/diagrams/" + d + "/versions/" + v1 + "/restore", ana.token(), null);
            assertThat(r.estado()).isEqualTo(409);
            assertThat(r.cuerpo().get("mensaje").asText()).contains("Beto").contains("Ejemplar");
            assertThat(r.cuerpo().get("usuario").asText()).isEqualTo("Beto");
            assertThat(r.cuerpo().get("elementId").asText()).isEqualTo(libro.toString());
            assertThat(seq(d)).isEqualTo(antes);
        } finally {
            locks.release(d, libro, "sesion-de-beto");
        }
        assertThat(llamar("POST", "/api/v1/diagrams/" + d + "/versions/" + v1 + "/restore", ana.token(), null)
                .estado()).isEqualTo(200);
    }

    // ===================================================== RAMAS Y MERGE

    @Test
    void una_rama_copia_la_version_con_la_misma_identidad_logica() throws Exception {
        Cuenta ana = registrar("Ana");
        UUID d = crearProyecto(ana);
        UUID libro = clase(d, ana, "Libro", 10, 20);
        UUID titulo = atributo(d, ana, libro, "titulo", "string");
        UUID v1 = id(llamar("POST", "/api/v1/diagrams/" + d + "/versions", ana.token(), Map.of("name", "v1")));
        // Lo que paso despues del commit no va a la rama.
        clase(d, ana, "Tarde", 0, 0);

        Resp creada = llamar("POST", "/api/v1/diagrams/" + d + "/branches", ana.token(),
                Map.of("name", "experimento", "fromVersionId", v1));
        assertThat(creada.estado()).as(String.valueOf(creada.cuerpo())).isEqualTo(201);
        UUID rama = UUID.fromString(creada.cuerpo().get("diagramId").asText());
        assertThat(creada.cuerpo().get("name").asText()).isEqualTo("experimento");
        assertThat(creada.cuerpo().get("forkedFromDiagramId").asText()).isEqualTo(d.toString());
        assertThat(creada.cuerpo().get("forkedFromVersionId").asText()).isEqualTo(v1.toString());

        UUID sal = jdbc.queryForObject("select id_salt from diagrams where id = ?", UUID.class, rama);
        assertThat(IdentidadLogica.esSalValida(sal)).isTrue();
        UUID libroEnRama = IdentidadLogica.aFisico(libro, sal);
        assertThat(jdbc.queryForObject("select name from uml_class where id = ? and diagram_id = ?",
                String.class, libroEnRama, rama)).isEqualTo("Libro");
        assertThat(jdbc.queryForObject("select class_id from uml_attribute where id = ?", UUID.class,
                IdentidadLogica.aFisico(titulo, sal))).isEqualTo(libroEnRama);
        assertThat(jdbc.queryForObject("select count(*) from uml_class where diagram_id = ?", Integer.class, rama))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("select x from diagram_layout where element_id = ?", Double.class, libroEnRama))
                .isEqualTo(10);

        Resp lista = llamar("GET", "/api/v1/diagrams/" + d + "/branches", ana.token(), null);
        assertThat(lista.cuerpo().at("/actual/diagramId").asText()).isEqualTo(d.toString());
        assertThat(lista.cuerpo().at("/actual/name").asText()).isEqualTo("main");
        assertThat(lista.cuerpo().get("origen").isNull()).isTrue();
        assertThat(lista.cuerpo().get("ramas")).singleElement().satisfies(r -> {
            assertThat(r.get("diagramId").asText()).isEqualTo(rama.toString());
            assertThat(r.get("forkedFromVersionName").asText()).isEqualTo("v1");
        });
        // Desde adentro de la rama: sabe que lo es y de donde traer cambios.
        Resp desdeLaRama = llamar("GET", "/api/v1/diagrams/" + rama + "/branches", ana.token(), null);
        assertThat(desdeLaRama.cuerpo().at("/actual/name").asText()).isEqualTo("experimento");
        assertThat(desdeLaRama.cuerpo().at("/origen/diagramId").asText()).isEqualTo(d.toString());
        assertThat(desdeLaRama.cuerpo().at("/origen/name").asText()).isEqualTo("main");
        assertThat(desdeLaRama.cuerpo().at("/origen/forkedFromVersionId").asText()).isEqualTo(v1.toString());
        assertThat(desdeLaRama.cuerpo().at("/origen/forkedFromVersionName").asText()).isEqualTo("v1");
        assertThat(desdeLaRama.cuerpo().get("ramas")).isEmpty();
        // El primer commit de la rama cuelga de la version de la que nacio.
        Resp primero = llamar("POST", "/api/v1/diagrams/" + rama + "/versions", ana.token(), Map.of("name", "r1"));
        assertThat(primero.cuerpo().get("parentVersionId").asText()).isEqualTo(v1.toString());

        assertThat(llamar("POST", "/api/v1/diagrams/" + d + "/branches", ana.token(),
                Map.of("name", "Experimento", "fromVersionId", v1)).estado()).isEqualTo(409);
    }

    @Test
    void merge_a_tres_bandas_con_conflicto_resuelto_y_base_que_avanza() throws Exception {
        Cuenta ana = registrar("Ana");
        UUID main = crearProyecto(ana);
        UUID libro = clase(main, ana, "Libro", 10, 20);
        UUID titulo = atributo(main, ana, libro, "titulo", "string");
        UUID socio = clase(main, ana, "Socio", 300, 20);
        UUID v1 = id(llamar("POST", "/api/v1/diagrams/" + main + "/versions", ana.token(), Map.of("name", "v1")));
        UUID rama = UUID.fromString(llamar("POST", "/api/v1/diagrams/" + main + "/branches", ana.token(),
                Map.of("name", "experimento", "fromVersionId", v1)).cuerpo().get("diagramId").asText());
        UUID sal = jdbc.queryForObject("select id_salt from diagrams where id = ?", UUID.class, rama);

        // En la rama: renombrar Libro (choca), agregar dni a Socio y una clase nueva.
        op(rama, ana, "RENAME_CLASS", Map.of("classId", IdentidadLogica.aFisico(libro, sal), "name", "Obra"));
        UUID dniEnRama = atributo(rama, ana, IdentidadLogica.aFisico(socio, sal), "dni", "string");
        UUID autorEnRama = clase(rama, ana, "Autor", 600, 20);
        // En main: renombrar Libro distinto y cambiar el tipo de titulo (no choca).
        op(main, ana, "RENAME_CLASS", Map.of("classId", libro, "name", "Ejemplar"));
        op(main, ana, "UPDATE_ATTRIBUTE", Map.of("attributeId", titulo, "dataType", "text"));

        Resp previa = llamar("POST", "/api/v1/diagrams/" + main + "/merge/preview", ana.token(),
                Map.of("fromDiagramId", rama));
        assertThat(previa.estado()).as(String.valueOf(previa.cuerpo())).isEqualTo(200);
        JsonNode p = previa.cuerpo();
        assertThat(p.at("/base/versionId").asText()).isEqualTo(v1.toString());
        assertThat(p.at("/conflictos")).singleElement().satisfies(c -> {
            // Con el id del DESTINO: la interfaz lo selecciona en su canvas.
            assertThat(c.get("elementId").asText()).isEqualTo(libro.toString());
            assertThat(c.get("tipo").asText()).isEqualTo("clase");
            assertThat(c.get("campo").asText()).isEqualTo("name");
            assertThat(c.get("base").asText()).isEqualTo("Libro");
            assertThat(c.get("nuestro").asText()).isEqualTo("Ejemplar");
            assertThat(c.get("suyo").asText()).isEqualTo("Obra");
        });
        assertThat(p.at("/suyos/clases/agregadas/0/nombre").asText()).isEqualTo("Autor");
        assertThat(p.at("/suyos/clases/agregadas/0/id").asText())
                .isEqualTo(IdentidadLogica.aLogico(autorEnRama, sal).toString());
        assertThat(p.at("/nuestros/atributos/modificadas/0/id").asText()).isEqualTo(titulo.toString());
        assertThat(p.get("puedeMergear").asBoolean()).isFalse();

        Resp sinResolver = llamar("POST", "/api/v1/diagrams/" + main + "/merge", ana.token(),
                Map.of("fromDiagramId", rama, "resoluciones", List.of()));
        assertThat(sinResolver.estado()).isEqualTo(409);
        assertThat(sinResolver.cuerpo().get("conflictos")).hasSize(1);
        long antes = seq(main);
        assertThat(seq(main)).isEqualTo(antes);

        Resp hecho = llamar("POST", "/api/v1/diagrams/" + main + "/merge", ana.token(),
                Map.of("fromDiagramId", rama, "resoluciones",
                        List.of(Map.of("elementId", libro, "campo", "name", "eleccion", "suyo"))));
        assertThat(hecho.estado()).as(String.valueOf(hecho.cuerpo())).isEqualTo(200);
        assertThat(hecho.cuerpo().get("seq").asLong()).isEqualTo(antes + 1);

        assertThat(jdbc.queryForObject("select name from uml_class where id = ?", String.class, libro)).isEqualTo("Obra");
        assertThat(jdbc.queryForObject("select data_type from uml_attribute where id = ?", String.class, titulo))
                .isEqualTo("text");
        UUID autorEnMain = IdentidadLogica.traducir(autorEnRama, sal, null);
        assertThat(jdbc.queryForObject("select name from uml_class where id = ? and diagram_id = ?",
                String.class, autorEnMain, main)).isEqualTo("Autor");
        assertThat(jdbc.queryForObject("select class_id from uml_attribute where id = ?", UUID.class,
                IdentidadLogica.traducir(dniEnRama, sal, null))).isEqualTo(socio);
        assertThat(jdbc.queryForObject("select op_type from diagram_operations where diagram_id = ? and seq = ?",
                String.class, main, antes + 1)).isEqualTo("BATCH");

        // La base avanzo: volver a mergear no vuelve a preguntar lo ya resuelto.
        Resp otraVez = llamar("POST", "/api/v1/diagrams/" + main + "/merge/preview", ana.token(),
                Map.of("fromDiagramId", rama));
        assertThat(otraVez.cuerpo().get("conflictos")).isEmpty();
        assertThat(otraVez.cuerpo().at("/base/tipo").asText()).isEqualTo("merge");
        assertThat(sinCambios(otraVez.cuerpo().get("aplicables"))).isTrue();

        // Y al reves: lo de main llega a la rama, con los ids de la rama.
        Resp alReves = llamar("POST", "/api/v1/diagrams/" + rama + "/merge", ana.token(),
                Map.of("fromDiagramId", main, "resoluciones", List.of()));
        assertThat(alReves.estado()).as(String.valueOf(alReves.cuerpo())).isEqualTo(200);
        assertThat(jdbc.queryForObject("select data_type from uml_attribute where id = ?", String.class,
                IdentidadLogica.aFisico(titulo, sal))).isEqualTo("text");
    }

    @Test
    void borrar_en_un_lado_lo_que_el_otro_cambio_pide_decidir_la_existencia() throws Exception {
        Cuenta ana = registrar("Ana");
        UUID main = crearProyecto(ana);
        UUID libro = clase(main, ana, "Libro", 0, 0);
        UUID v1 = id(llamar("POST", "/api/v1/diagrams/" + main + "/versions", ana.token(), Map.of("name", "v1")));
        UUID rama = UUID.fromString(llamar("POST", "/api/v1/diagrams/" + main + "/branches", ana.token(),
                Map.of("name", "b", "fromVersionId", v1)).cuerpo().get("diagramId").asText());
        UUID sal = jdbc.queryForObject("select id_salt from diagrams where id = ?", UUID.class, rama);
        atributo(rama, ana, IdentidadLogica.aFisico(libro, sal), "isbn", "string");
        op(main, ana, "DELETE_CLASS", Map.of("classId", libro));

        JsonNode c = llamar("POST", "/api/v1/diagrams/" + main + "/merge/preview", ana.token(),
                Map.of("fromDiagramId", rama)).cuerpo().at("/conflictos/0");
        assertThat(c.get("campo").asText()).isEqualTo("existencia");
        assertThat(c.get("nuestro").asText()).isEqualTo("eliminado");
        assertThat(c.get("suyo").asText()).isEqualTo("existe");

        Resp hecho = llamar("POST", "/api/v1/diagrams/" + main + "/merge", ana.token(),
                Map.of("fromDiagramId", rama, "resoluciones",
                        List.of(Map.of("elementId", libro, "campo", "existencia", "eleccion", "suyo"))));
        assertThat(hecho.estado()).as(String.valueOf(hecho.cuerpo())).isEqualTo(200);
        // Vuelve con el MISMO id (reusa el tombstone) y con lo que le agregaron.
        assertThat(jdbc.queryForObject("select deleted_at is null from uml_class where id = ?", Boolean.class, libro))
                .isTrue();
        assertThat(jdbc.queryForList("select name from uml_attribute where class_id = ?", String.class, libro))
                .containsExactly("isbn");
    }

    @Test
    void dos_elementos_nuevos_con_el_mismo_nombre_son_conflicto_en_la_previa_y_no_un_422_al_aplicar() throws Exception {
        Cuenta ana = registrar("Ana");
        UUID main = crearProyecto(ana);
        UUID libro = clase(main, ana, "Libro", 0, 0);
        UUID v1 = id(llamar("POST", "/api/v1/diagrams/" + main + "/versions", ana.token(), Map.of("name", "v1")));
        UUID rama = UUID.fromString(llamar("POST", "/api/v1/diagrams/" + main + "/branches", ana.token(),
                Map.of("name", "b", "fromVersionId", v1)).cuerpo().get("diagramId").asText());
        UUID sal = jdbc.queryForObject("select id_salt from diagrams where id = ?", UUID.class, rama);
        UUID prestamoNuestro = clase(main, ana, "Prestamo", 0, 200);
        UUID prestamoSuyo = clase(rama, ana, "prestamo", 300, 200);       // mismo nombre para el indice unico
        atributo(main, ana, libro, "isbn", "string");
        UUID isbnSuyo = atributo(rama, ana, IdentidadLogica.aFisico(libro, sal), "ISBN", "string");

        JsonNode p = llamar("POST", "/api/v1/diagrams/" + main + "/merge/preview", ana.token(),
                Map.of("fromDiagramId", rama)).cuerpo();
        assertThat(p.get("puedeMergear").asBoolean()).isFalse();
        UUID prestamoSuyoEnMain = IdentidadLogica.traducir(prestamoSuyo, sal, null);
        UUID isbnSuyoEnMain = IdentidadLogica.traducir(isbnSuyo, sal, null);
        assertThat(p.get("conflictos")).hasSize(2).allSatisfy(c -> {
            assertThat(c.get("campo").asText()).isEqualTo("name");
            assertThat(c.get("motivo").asText()).isEqualTo("nombre repetido");
        });
        assertThat(p.get("conflictos")).anySatisfy(c -> {
            // El conflicto es sobre el que trae "suyo"; el de "nuestro" no se toca.
            assertThat(c.get("elementId").asText()).isEqualTo(prestamoSuyoEnMain.toString());
            assertThat(c.get("tipo").asText()).isEqualTo("clase");
            assertThat(c.get("suyo").asText()).isEqualTo("prestamo");
            assertThat(c.get("sugerido").asText()).isEqualTo("prestamo_2");
        });

        assertThat(llamar("POST", "/api/v1/diagrams/" + main + "/merge", ana.token(),
                Map.of("fromDiagramId", rama, "resoluciones", List.of())).estado()).isEqualTo(409);

        Resp hecho = llamar("POST", "/api/v1/diagrams/" + main + "/merge", ana.token(),
                Map.of("fromDiagramId", rama, "resoluciones", List.of(
                        Map.of("elementId", prestamoSuyoEnMain, "campo", "name", "eleccion", "suyo"),
                        Map.of("elementId", isbnSuyoEnMain, "campo", "name", "eleccion", "nuestro"))));
        assertThat(hecho.estado()).as(String.valueOf(hecho.cuerpo())).isEqualTo(200);
        assertThat(jdbc.queryForObject("select name from uml_class where id = ?", String.class, prestamoNuestro))
                .isEqualTo("Prestamo");
        assertThat(jdbc.queryForObject("select name from uml_class where id = ?", String.class, prestamoSuyoEnMain))
                .isEqualTo("prestamo_2");
        // "nuestro" en el atributo: el suyo no entra.
        assertThat(jdbc.queryForObject("select count(*) from uml_attribute where id = ?", Integer.class, isbnSuyoEnMain))
                .isZero();
    }

    @Test
    void un_merge_que_borra_un_elemento_con_decision_de_modelado_no_tumba_el_lote() throws Exception {
        Cuenta ana = registrar("Ana");
        UUID main = crearProyecto(ana);
        UUID pedido = clase(main, ana, "Pedido", 0, 0);
        UUID linea = clase(main, ana, "Linea", 300, 0);
        UUID tiene = UUID.randomUUID();
        op(main, ana, "CREATE_RELATION", Map.of("relationId", tiene, "sourceClassId", pedido, "targetClassId", linea,
                "kind", "COMPOSITION", "sourceMultiplicity", "1", "targetMultiplicity", "1..*"));
        op(main, ana, "SET_MODELING_DECISION", Map.of("elementId", tiene, "ambiguityKind", "COMPOSITION", "chosen", "OO"));
        UUID v1 = id(llamar("POST", "/api/v1/diagrams/" + main + "/versions", ana.token(), Map.of("name", "v1")));
        UUID rama = UUID.fromString(llamar("POST", "/api/v1/diagrams/" + main + "/branches", ana.token(),
                Map.of("name", "b", "fromVersionId", v1)).cuerpo().get("diagramId").asText());
        UUID sal = jdbc.queryForObject("select id_salt from diagrams where id = ?", UUID.class, rama);
        // La decision viajo a la rama, con el id de la rama.
        assertThat(jdbc.queryForObject("select chosen from modeling_decisions where diagram_id = ? and element_id = ?",
                String.class, rama, IdentidadLogica.aFisico(tiene, sal))).isEqualTo("OO");

        // "Suyo" borra la relacion y su clase; "nuestro" cambia la decision.
        op(rama, ana, "DELETE_CLASS", Map.of("classId", IdentidadLogica.aFisico(linea, sal)));
        op(main, ana, "SET_MODELING_DECISION", Map.of("elementId", tiene, "ambiguityKind", "COMPOSITION", "chosen", "DATA"));

        JsonNode p = llamar("POST", "/api/v1/diagrams/" + main + "/merge/preview", ana.token(),
                Map.of("fromDiagramId", rama)).cuerpo();
        // Lo que se pregunta es la clase (borrada contra cambiada), no la
        // decision de una relacion que se va con ella.
        assertThat(p.get("conflictos")).noneSatisfy(c ->
                assertThat(c.get("campo").asText()).startsWith("decision."));

        List<Map<String, Object>> resoluciones = new ArrayList<>();
        p.get("conflictos").forEach(c -> resoluciones.add(Map.of("elementId", c.get("elementId").asText(),
                "campo", c.get("campo").asText(), "eleccion", "suyo")));
        Resp hecho = llamar("POST", "/api/v1/diagrams/" + main + "/merge", ana.token(),
                Map.of("fromDiagramId", rama, "resoluciones", resoluciones));
        assertThat(hecho.estado()).as(String.valueOf(hecho.cuerpo())).isEqualTo(200);
        assertThat(jdbc.queryForObject("select deleted_at is not null from uml_relationship where id = ?",
                Boolean.class, tiene)).isTrue();
        assertThat(jdbc.queryForObject("select deleted_at is not null from uml_class where id = ?",
                Boolean.class, linea)).isTrue();
    }

    @Test
    void un_lote_calculado_contra_un_seq_viejo_se_rechaza_sin_tocar_nada() throws Exception {
        Cuenta ana = registrar("Ana");
        UUID d = crearProyecto(ana);
        clase(d, ana, "Libro", 0, 0);
        long seq = seq(d);
        Map<String, Object> lote = Map.of("descripcion", "Restaurar la version \"v1\"", "seqEsperado", seq - 1,
                "comandos", List.of(Map.of("type", "CREATE_CLASS",
                        "payload", Map.of("classId", UUID.randomUUID(), "name", "Tarde"))));

        CommandRejectedException e = org.junit.jupiter.api.Assertions.assertThrows(CommandRejectedException.class,
                () -> bus.submit(new Command(UUID.randomUUID(), d, seq, CommandType.BATCH, Origin.MOUSE,
                        json.valueToTree(lote)), new Autor(ana.id(), "sesion-Ana")));
        assertThat(e.getReason()).isEqualTo(RejectionReason.STALE);
        assertThat(e.getMessage()).contains("cambio").contains("no se aplico nada");
        assertThat(seq(d)).isEqualTo(seq);
        assertThat(jdbc.queryForObject("select count(*) from uml_class where diagram_id = ? and name = 'Tarde'",
                Integer.class, d)).isZero();

        // Con el seq correcto, el mismo lote pasa.
        Map<String, Object> bien = new HashMap<>(lote);
        bien.put("seqEsperado", seq);
        bus.submit(new Command(UUID.randomUUID(), d, seq, CommandType.BATCH, Origin.MOUSE, json.valueToTree(bien)),
                new Autor(ana.id(), "sesion-Ana"));
        assertThat(seq(d)).isEqualTo(seq + 1);
    }

    @Test
    void no_se_mergean_diagramas_sin_historia_comun() throws Exception {
        Cuenta ana = registrar("Ana");
        UUID uno = crearProyecto(ana);
        UUID proyecto = jdbc.queryForObject("select project_id from diagrams where id = ?", UUID.class, uno);
        Resp otro = llamar("POST", "/api/v1/projects/" + proyecto + "/diagrams", ana.token(), Map.of("name", "Otro"));
        UUID dos = UUID.fromString(otro.cuerpo().get("id").asText());

        Resp r = llamar("POST", "/api/v1/diagrams/" + uno + "/merge/preview", ana.token(), Map.of("fromDiagramId", dos));
        assertThat(r.estado()).isEqualTo(422);
        assertThat(r.cuerpo().get("mensaje").asText()).contains("historia");
    }

    // ============================================================ AYUDAS

    private UUID clase(UUID d, Cuenta quien, String nombre, double x, double y) {
        UUID id = UUID.randomUUID();
        op(d, quien, "CREATE_CLASS", Map.of("classId", id, "name", nombre, "x", x, "y", y));
        return id;
    }

    private UUID atributo(UUID d, Cuenta quien, UUID clase, String nombre, String tipo) {
        UUID id = UUID.randomUUID();
        op(d, quien, "ADD_ATTRIBUTE", Map.of("classId", clase, "attributeId", id, "name", nombre, "dataType", tipo));
        return id;
    }

    /** Por el bus, como el canvas, con una sesion propia (sin locks tomados). */
    private AppliedOperation op(UUID d, Cuenta quien, String tipo, Map<String, Object> payload) {
        Command c = new Command(UUID.randomUUID(), d, seq(d), CommandType.valueOf(tipo), Origin.MOUSE,
                json.valueToTree(payload));
        return bus.submit(c, new Autor(quien.id(), "sesion-" + quien.nombre()));
    }

    private long seq(UUID d) {
        return jdbc.queryForObject("select current_seq from diagrams where id = ?", Long.class, d);
    }

    private static UUID id(Resp r) {
        assertThat(r.estado()).as(String.valueOf(r.cuerpo())).isBetween(200, 201);
        return UUID.fromString(r.cuerpo().get("id").asText());
    }

    private static List<String> campos(JsonNode modificada) {
        List<String> r = new ArrayList<>();
        modificada.get("cambios").forEach(c -> r.add(c.get("campo").asText()));
        return r;
    }

    private static boolean sinCambios(JsonNode diff) {
        for (String s : List.of("clases", "atributos", "metodos", "relaciones")) {
            for (String g : List.of("agregadas", "eliminadas", "modificadas")) {
                if (!diff.path(s).path(g).isEmpty()) return false;
            }
        }
        return true;
    }

    private Cuenta registrar(String nombre) throws Exception {
        String email = "it-" + nombre.toLowerCase() + "-" + marca + "@prueba.test";
        Resp r = llamar("POST", "/api/v1/auth/register", null,
                Map.of("email", email, "password", "contrasena-larga", "fullName", nombre));
        assertThat(r.estado()).as("registrar a " + nombre).isEqualTo(200);
        return new Cuenta(nombre, email, UUID.fromString(r.cuerpo().get("userId").asText()),
                r.cuerpo().get("token").asText());
    }

    /** Un proyecto nuevo; devuelve su primer diagrama. */
    private UUID crearProyecto(Cuenta duenio) throws Exception {
        Resp r = llamar("POST", "/api/v1/projects", duenio.token(), Map.of("name", "Versiones " + marca));
        assertThat(r.estado()).isEqualTo(201);
        return UUID.fromString(r.cuerpo().get("diagrams").get(0).get("id").asText());
    }

    private void agregarMiembro(Cuenta duenio, UUID diagrama, Cuenta quien, String rol) throws Exception {
        UUID proyecto = jdbc.queryForObject("select project_id from diagrams where id = ?", UUID.class, diagrama);
        Resp r = llamar("POST", "/api/v1/projects/" + proyecto + "/members", duenio.token(),
                Map.of("email", quien.email(), "role", rol));
        assertThat(r.estado()).as(String.valueOf(r.cuerpo())).isBetween(200, 201);
    }

    private Resp llamar(String metodo, String ruta, String token, Object cuerpo) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + puerto + ruta));
        if (token != null) b.header("Authorization", "Bearer " + token);
        if (cuerpo != null) b.header("Content-Type", "application/json");
        b.method(metodo, cuerpo == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(cuerpo)));
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new Resp(r.statusCode(), r.body().isBlank() ? null : json.readTree(r.body()));
    }
}
