package com.uagrm.colabmodel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * `POST /api/v1/dev/bootstrap` con el perfil `dev` (ADR-026): el atajo de la
 * seccion 5 del README sigue funcionando, y sirve para recuperar el proyecto
 * demo con una cuenta real.
 *
 * `ApiDeProyectosTest` prueba el otro lado: sin el perfil, el atajo no existe.
 * Usa `colabmodel_test`: los usuarios demo que crea se borran al terminar.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
class AtajoDeDesarrolloTest extends PostgresDePruebas {

    @LocalServerPort
    int puerto;
    @Autowired
    JdbcTemplate jdbc;

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();

    record Resp(int estado, JsonNode cuerpo) { }

    @AfterEach
    void borrarLoQueCreoLaPrueba() {
        jdbc.update("delete from projects where owner_id in (select id from users where email like 'demo%@uagrm.edu.bo' or email like 'it-dev-%')");
        jdbc.update("delete from users where email like 'demo%@uagrm.edu.bo' or email like 'it-dev-%'");
    }

    @Test
    void con_el_perfil_dev_emite_un_token_de_cada_rol_y_el_viewer_no_escribe() throws Exception {
        Resp viewer = llamar("POST", "/api/v1/dev/bootstrap?role=VIEWER", null, null);
        Resp editor = llamar("POST", "/api/v1/dev/bootstrap?role=EDITOR", null, null);
        Resp duenio = llamar("POST", "/api/v1/dev/bootstrap", null, null);

        assertThat(viewer.estado()).isEqualTo(200);
        assertThat(editor.estado()).isEqualTo(200);
        assertThat(duenio.estado()).isEqualTo(200);
        // Los tres al mismo proyecto demo, con tres usuarios distintos.
        assertThat(viewer.cuerpo().get("projectId")).isEqualTo(duenio.cuerpo().get("projectId"));
        assertThat(editor.cuerpo().get("projectId")).isEqualTo(duenio.cuerpo().get("projectId"));
        assertThat(viewer.cuerpo().get("userId")).isNotEqualTo(editor.cuerpo().get("userId"));

        String proyecto = duenio.cuerpo().get("projectId").asText();
        String comoViewer = viewer.cuerpo().get("token").asText();
        String comoDuenio = duenio.cuerpo().get("token").asText();
        assertThat(llamar("GET", "/api/v1/projects/" + proyecto, comoViewer, null).cuerpo().get("myRole").asText()).isEqualTo("VIEWER");
        assertThat(llamar("GET", "/api/v1/projects/" + proyecto, comoDuenio, null).cuerpo().get("myRole").asText()).isEqualTo("OWNER");
        assertThat(jdbc.queryForObject("select count(*) from projects where owner_id in (select id from users where email = 'demo@uagrm.edu.bo')", Integer.class)).isEqualTo(1);
    }

    @Test
    void un_role_invalido_es_400() throws Exception {
        assertThat(llamar("POST", "/api/v1/dev/bootstrap?role=ADMIN", null, null).estado()).isEqualTo(400);
    }

    @Test
    void el_proyecto_demo_se_recupera_con_una_cuenta_real_y_el_atajo_no_crea_otro() throws Exception {
        // El camino que se le propone al dueño: entrar con el token demo (que ya no se
        // puede obtener por login: su hash es "{noop}demo"), agregar la cuenta real y
        // transferirle la propiedad. Todo por la API, sin SQL a mano.
        Resp demo = llamar("POST", "/api/v1/dev/bootstrap", null, null);
        String proyecto = demo.cuerpo().get("projectId").asText();
        String diagrama = demo.cuerpo().get("diagramId").asText();
        String tokenDemo = demo.cuerpo().get("token").asText();

        String email = "it-dev-" + UUID.randomUUID().toString().substring(0, 8) + "@prueba.test";
        Resp real = llamar("POST", "/api/v1/auth/register", null,
                Map.of("email", email, "password", "contrasena-larga", "fullName", "Cuenta Real"));
        String tokenReal = real.cuerpo().get("token").asText();
        String idReal = real.cuerpo().get("userId").asText();

        assertThat(llamar("POST", "/api/v1/projects/" + proyecto + "/members", tokenDemo,
                Map.of("email", email, "role", "EDITOR")).estado()).isEqualTo(201);
        assertThat(llamar("POST", "/api/v1/projects/" + proyecto + "/transfer", tokenDemo,
                Map.of("userId", idReal)).estado()).isEqualTo(204);

        // La cuenta real es la propietaria y ve el mismo diagrama, sin haber tocado nada de el.
        Resp detalle = llamar("GET", "/api/v1/projects/" + proyecto, tokenReal, null);
        assertThat(detalle.cuerpo().get("myRole").asText()).isEqualTo("OWNER");
        assertThat(detalle.cuerpo().get("diagrams").get(0).get("id").asText()).isEqualTo(diagrama);
        assertThat(llamar("GET", "/api/v1/diagrams/" + diagrama, tokenReal, null).estado()).isEqualTo(200);

        // Y el atajo sigue apuntando a ESE proyecto: no crea un segundo "Proyecto Demo" vacio.
        Resp otra = llamar("POST", "/api/v1/dev/bootstrap", null, null);
        assertThat(otra.cuerpo().get("projectId").asText()).isEqualTo(proyecto);
        assertThat(jdbc.queryForObject("select count(*) from projects where name = 'Proyecto Demo'", Integer.class)).isEqualTo(1);
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
