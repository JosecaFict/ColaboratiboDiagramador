package com.uagrm.colabmodel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uagrm.colabmodel.iam.AppUser;
import com.uagrm.colabmodel.iam.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Gestion de proyectos, miembros y roles (ADR-026) contra el sistema real:
 * Spring Boot completo, la cadena de seguridad, PostgreSQL con las migraciones
 * de verdad y WebSocket.
 *
 * Es lo unico que puede probar lo que los repositorios en memoria no ven: que V6
 * se aplica, que la base rechaza un segundo propietario aunque se le escriba a
 * mano, que una transferencia que falla a la mitad se revierte en la base, que
 * un pedido sin sesion es 401 y no 403, y que un miembro quitado deja de
 * recibir operaciones por WebSocket.
 *
 * Los usuarios y proyectos que crea llevan una marca unica y se borran al
 * terminar cada prueba. Usa `colabmodel_test`, nunca la base de trabajo.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiDeProyectosTest extends PostgresDePruebas {

    @LocalServerPort
    int puerto;
    @Autowired
    JdbcTemplate jdbc;
    @Value("${colabmodel.jwt.secret}")
    String secreto;

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();
    private final String marca = UUID.randomUUID().toString().substring(0, 8);

    record Resp(int estado, JsonNode cuerpo) { }

    record Cuenta(String nombre, String email, UUID id, String token) { }

    record Proyecto(UUID id, UUID diagramaId) { }

    @AfterEach
    void borrarLoQueCreoLaPrueba() {
        String patron = "it-%-" + marca + "@prueba.test";
        jdbc.update("delete from projects where owner_id in (select id from users where email like ?)", patron);
        jdbc.update("delete from users where email like ?", patron);
    }

    // -------------------------------------------------------- V6 y la base

    @Test
    void v6_esta_aplicada_y_la_base_no_admite_un_segundo_propietario_ni_escribiendo_a_mano() throws Exception {
        assertThat(jdbc.queryForObject(
                "select count(*) from flyway_schema_history where version = '6' and success", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForList(
                "select column_name from information_schema.columns where table_name = 'project_members'",
                String.class)).contains("added_at");

        Cuenta ana = registrar("Ana");
        Proyecto p = crearProyecto(ana, "Biblioteca");

        assertThatThrownBy(() -> jdbc.update(
                "insert into project_members(project_id, user_id, role) values (?, ?, 'OWNER')", p.id(), ana.id()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_member_role");
    }

    // ----------------------------------------------------------------- 401

    @Test
    void sin_token_con_token_invalido_o_vencido_es_401_con_mensaje_y_no_403() throws Exception {
        Cuenta ana = registrar("Ana");
        AppUser aparente = new AppUser();
        aparente.setId(ana.id());
        aparente.setEmail(ana.email());
        aparente.setFullName("Ana");
        String vencido = new JwtService(secreto, -5).emitir(aparente);

        for (String token : new String[]{null, "esto-no-es-un-jwt", vencido}) {
            for (String ruta : List.of("/api/v1/projects", "/api/v1/me", "/api/v1/diagrams/" + UUID.randomUUID())) {
                Resp r = llamar("GET", ruta, token, null);
                assertThat(r.estado()).as("GET %s con token %s", ruta, token == null ? "ausente" : "malo").isEqualTo(401);
                assertThat(r.cuerpo().get("mensaje").asText()).contains("iniciá sesión");
            }
        }
        // Y con el token bueno, entra.
        assertThat(llamar("GET", "/api/v1/me", ana.token(), null).estado()).isEqualTo(200);
    }

    @Test
    void el_atajo_dev_bootstrap_no_existe_sin_el_perfil_dev() throws Exception {
        // Ni el controlador ni la ruta publica: pedir un token sin contrasena da 401.
        assertThat(llamar("POST", "/api/v1/dev/bootstrap", null, null).estado()).isEqualTo(401);
        assertThat(llamar("POST", "/api/v1/dev/bootstrap?role=VIEWER", null, null).estado()).isEqualTo(401);
    }

    // ------------------------------------------------------ roles de punta a punta

    @Test
    void cada_rol_puede_lo_que_le_toca_y_el_no_miembro_no_puede_nada() throws Exception {
        Cuenta ana = registrar("Ana");
        Cuenta beto = registrar("Beto");
        Cuenta ajeno = registrar("Ajeno");
        Proyecto p = crearProyecto(ana, "Biblioteca");
        String base = "/api/v1/projects/" + p.id();

        // Ana lo ve como propia, con su primer diagrama.
        JsonNode lista = llamar("GET", "/api/v1/projects", ana.token(), null).cuerpo();
        assertThat(lista).hasSize(1);
        assertThat(lista.get(0).get("myRole").asText()).isEqualTo("OWNER");
        assertThat(lista.get(0).get("diagramCount").asInt()).isEqualTo(1);
        assertThat(lista.get(0).get("owner").get("email").asText()).isEqualTo(ana.email());

        // Alta de un observador.
        Resp alta = llamar("POST", base + "/members", ana.token(), Map.of("email", beto.email(), "role", "VIEWER"));
        assertThat(alta.estado()).isEqualTo(201);
        assertThat(alta.cuerpo().get("role").asText()).isEqualTo("VIEWER");

        // El ajeno no ve nada de nada: 403 en todo.
        for (String[] pedido : new String[][]{
                {"GET", base}, {"GET", base + "/members"}, {"GET", "/api/v1/diagrams/" + p.diagramaId()},
                {"GET", "/api/v1/diagrams/" + p.diagramaId() + "/logical"}}) {
            assertThat(llamar(pedido[0], pedido[1], ajeno.token(), null).estado()).as(pedido[0] + " " + pedido[1]).isEqualTo(403);
        }
        assertThat(llamar("GET", "/api/v1/projects", ajeno.token(), null).cuerpo()).isEmpty();

        // El observador lee todo y no escribe nada.
        assertThat(llamar("GET", "/api/v1/projects", beto.token(), null).cuerpo().get(0).get("myRole").asText())
                .isEqualTo("VIEWER");
        assertThat(llamar("GET", base, beto.token(), null).estado()).isEqualTo(200);
        assertThat(llamar("GET", base + "/members", beto.token(), null).cuerpo()).hasSize(2);
        assertThat(llamar("GET", "/api/v1/diagrams/" + p.diagramaId(), beto.token(), null).estado()).isEqualTo(200);
        assertThat(llamar("GET", "/api/v1/diagrams/" + p.diagramaId() + "/logical", beto.token(), null).estado()).isEqualTo(200);
        assertThat(llamar("POST", base + "/diagrams", beto.token(), Map.of("name", "X")).estado()).isEqualTo(403);
        assertThat(llamar("PATCH", base, beto.token(), Map.of("name", "X")).estado()).isEqualTo(403);
        assertThat(llamar("DELETE", base, beto.token(), null).estado()).isEqualTo(403);
        assertThat(llamar("POST", base + "/members", beto.token(), Map.of("email", ajeno.email(), "role", "VIEWER")).estado()).isEqualTo(403);
        assertThat(llamar("POST", base + "/transfer", beto.token(), Map.of("userId", beto.id().toString())).estado()).isEqualTo(403);

        // Ana lo promueve a modelador: ahora crea y renombra diagramas, pero no elimina.
        assertThat(llamar("PATCH", base + "/members/" + beto.id(), ana.token(), Map.of("role", "EDITOR")).estado()).isEqualTo(200);
        Resp nuevo = llamar("POST", base + "/diagrams", beto.token(), Map.of("name", "Ventas"));
        assertThat(nuevo.estado()).isEqualTo(201);
        String otroDiagrama = nuevo.cuerpo().get("id").asText();
        assertThat(llamar("PATCH", "/api/v1/diagrams/" + otroDiagrama, beto.token(), Map.of("name", "Ventas 2")).estado()).isEqualTo(200);
        assertThat(llamar("DELETE", "/api/v1/diagrams/" + otroDiagrama, beto.token(), null).estado()).isEqualTo(403);
        assertThat(llamar("DELETE", "/api/v1/diagrams/" + otroDiagrama, ana.token(), null).estado()).isEqualTo(204);
    }

    @Test
    void los_errores_de_negocio_tienen_su_codigo_y_su_mensaje_en_espanol() throws Exception {
        Cuenta ana = registrar("Ana");
        Cuenta beto = registrar("Beto");
        Proyecto p = crearProyecto(ana, "Biblioteca");
        String base = "/api/v1/projects/" + p.id();
        llamar("POST", base + "/members", ana.token(), Map.of("email", beto.email(), "role", "EDITOR"));

        Resp inexistente = llamar("POST", base + "/members", ana.token(), Map.of("email", "nadie@prueba.test", "role", "VIEWER"));
        assertThat(inexistente.estado()).isEqualTo(404);
        assertThat(inexistente.cuerpo().get("mensaje").asText()).isEqualTo("No hay ningún usuario registrado con ese email");

        Resp duplicado = llamar("POST", base + "/members", ana.token(), Map.of("email", beto.email(), "role", "VIEWER"));
        assertThat(duplicado.estado()).isEqualTo(409);
        assertThat(duplicado.cuerpo().get("mensaje").asText()).isEqualTo("Ese usuario ya es miembro del proyecto");

        assertThat(llamar("POST", base + "/members", ana.token(), Map.of("email", ana.email(), "role", "VIEWER")).estado()).isEqualTo(409);
        assertThat(llamar("PATCH", base + "/members/" + beto.id(), ana.token(), Map.of("role", "OWNER")).estado()).isEqualTo(400);
        assertThat(llamar("PATCH", base + "/members/" + ana.id(), ana.token(), Map.of("role", "VIEWER")).estado()).isEqualTo(409);
        assertThat(llamar("POST", base + "/transfer", ana.token(), Map.of("userId", UUID.randomUUID().toString())).estado()).isEqualTo(404);

        Resp vacio = llamar("POST", "/api/v1/projects", ana.token(), Map.of("name", "   "));
        assertThat(vacio.estado()).isEqualTo(400);
        assertThat(vacio.cuerpo().get("mensaje").asText()).isEqualTo("El nombre es obligatorio");
        assertThat(llamar("GET", "/api/v1/projects/no-es-un-uuid", ana.token(), null).estado()).isEqualTo(400);

        // Ninguna de esas puertas dejo un propietario de mas.
        assertThat(jdbc.queryForObject("select count(*) from project_members where project_id = ? and role = 'OWNER'",
                Integer.class, p.id())).isZero();
    }

    @Test
    void editor_y_observador_abandonan_pero_el_propietario_no() throws Exception {
        Cuenta ana = registrar("Ana");
        Cuenta beto = registrar("Beto");
        Cuenta carla = registrar("Carla");
        Proyecto p = crearProyecto(ana, "Biblioteca");
        String base = "/api/v1/projects/" + p.id();
        llamar("POST", base + "/members", ana.token(), Map.of("email", beto.email(), "role", "EDITOR"));
        llamar("POST", base + "/members", ana.token(), Map.of("email", carla.email(), "role", "VIEWER"));

        Resp propietaria = llamar("DELETE", base + "/members/" + ana.id(), ana.token(), null);
        assertThat(propietaria.estado()).isEqualTo(403);
        assertThat(propietaria.cuerpo().get("mensaje").asText()).contains("transferí la propiedad");

        assertThat(llamar("DELETE", base + "/members/" + beto.id(), beto.token(), null).estado()).isEqualTo(204);
        assertThat(llamar("DELETE", base + "/members/" + carla.id(), carla.token(), null).estado()).isEqualTo(204);
        // Ya no son miembros: ni ver el proyecto.
        assertThat(llamar("GET", base, beto.token(), null).estado()).isEqualTo(403);
        assertThat(llamar("GET", base, ana.token(), null).estado()).isEqualTo(200);
    }

    // -------------------------------------------------------- transferencia

    @Test
    void la_transferencia_pasa_la_propiedad_y_la_anterior_queda_editor() throws Exception {
        Cuenta ana = registrar("Ana");
        Cuenta beto = registrar("Beto");
        Proyecto p = crearProyecto(ana, "Biblioteca");
        String base = "/api/v1/projects/" + p.id();
        llamar("POST", base + "/members", ana.token(), Map.of("email", beto.email(), "role", "VIEWER"));

        assertThat(llamar("POST", base + "/transfer", ana.token(), Map.of("userId", beto.id().toString())).estado()).isEqualTo(204);

        assertThat(jdbc.queryForObject("select owner_id from projects where id = ?", UUID.class, p.id())).isEqualTo(beto.id());
        // El nuevo propietario no tiene fila; la anterior es EDITOR.
        assertThat(jdbc.queryForObject("select count(*) from project_members where project_id = ? and user_id = ?",
                Integer.class, p.id(), beto.id())).isZero();
        assertThat(jdbc.queryForObject("select role from project_members where project_id = ? and user_id = ?",
                String.class, p.id(), ana.id())).isEqualTo("EDITOR");
        assertThat(llamar("GET", "/api/v1/projects", beto.token(), null).cuerpo().get(0).get("myRole").asText()).isEqualTo("OWNER");
        assertThat(llamar("GET", "/api/v1/projects", ana.token(), null).cuerpo().get(0).get("myRole").asText()).isEqualTo("EDITOR");
        // Ana ya no puede transferir; Beto si.
        assertThat(llamar("POST", base + "/transfer", ana.token(), Map.of("userId", ana.id().toString())).estado()).isEqualTo(403);
        assertThat(llamar("DELETE", base, ana.token(), null).estado()).isEqualTo(403);
    }

    @Test
    void una_transferencia_que_falla_a_la_mitad_no_deja_nada_cambiado() throws Exception {
        Cuenta ana = registrar("Ana");
        Cuenta beto = registrar("Beto");
        Proyecto p = crearProyecto(ana, "Biblioteca");
        String base = "/api/v1/projects/" + p.id();
        llamar("POST", base + "/members", ana.token(), Map.of("email", beto.email(), "role", "VIEWER"));

        // Hibernate ejecuta, al confirmar, primero el INSERT de la fila de Ana, despues el
        // UPDATE de owner_id y por ULTIMO el DELETE de la fila de Beto. Un trigger que
        // rechaza ese DELETE hace fallar la transaccion cuando los otros dos ya se
        // ejecutaron en la base: si no fuera UNA transaccion, quedarian escritos.
        jdbc.execute("CREATE OR REPLACE FUNCTION it_falla_al_borrar() RETURNS trigger LANGUAGE plpgsql AS $$ "
                + "BEGIN IF OLD.project_id = '" + p.id() + "'::uuid THEN RAISE EXCEPTION 'falla simulada'; END IF; "
                + "RETURN OLD; END $$");
        jdbc.execute("CREATE TRIGGER it_falla BEFORE DELETE ON project_members "
                + "FOR EACH ROW EXECUTE FUNCTION it_falla_al_borrar()");
        try {
            Resp r = llamar("POST", base + "/transfer", ana.token(), Map.of("userId", beto.id().toString()));

            assertThat(r.estado()).as("la transferencia tenia que fallar").isGreaterThanOrEqualTo(500);
            // Nada cambio: Ana sigue siendo la propietaria y sin fila, y Beto sigue siendo observador.
            assertThat(jdbc.queryForObject("select owner_id from projects where id = ?", UUID.class, p.id())).isEqualTo(ana.id());
            assertThat(jdbc.queryForObject("select count(*) from project_members where project_id = ? and user_id = ?",
                    Integer.class, p.id(), ana.id())).isZero();
            assertThat(jdbc.queryForObject("select role from project_members where project_id = ? and user_id = ?",
                    String.class, p.id(), beto.id())).isEqualTo("VIEWER");
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS it_falla ON project_members");
            jdbc.execute("DROP FUNCTION IF EXISTS it_falla_al_borrar()");
        }
    }

    @Test
    void eliminar_el_proyecto_se_lleva_sus_diagramas_y_sus_miembros() throws Exception {
        Cuenta ana = registrar("Ana");
        Cuenta beto = registrar("Beto");
        Proyecto p = crearProyecto(ana, "Biblioteca");
        String base = "/api/v1/projects/" + p.id();
        llamar("POST", base + "/members", ana.token(), Map.of("email", beto.email(), "role", "EDITOR"));

        assertThat(llamar("DELETE", base, ana.token(), null).estado()).isEqualTo(204);

        assertThat(jdbc.queryForObject("select count(*) from projects where id = ?", Integer.class, p.id())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from diagrams where id = ?", Integer.class, p.diagramaId())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from project_members where project_id = ?", Integer.class, p.id())).isZero();
        assertThat(llamar("GET", base, beto.token(), null).estado()).isEqualTo(403);
    }

    // ------------------------------------------------------------ contrasena

    @Test
    void cambiar_la_contrasena_exige_la_actual_y_despues_entra_con_la_nueva_y_no_con_la_vieja() throws Exception {
        Cuenta ana = registrar("Ana");

        Resp mal = llamar("POST", "/api/v1/me/password", ana.token(), Map.of("actual", "no-es-esta", "nueva", "contrasena-nueva"));
        assertThat(mal.estado()).isEqualTo(400);     // 400 y no 401: no cierra la sesion
        assertThat(mal.cuerpo().get("mensaje").asText()).contains("contraseña actual");
        assertThat(llamar("POST", "/api/v1/me/password", ana.token(), Map.of("actual", "contrasena-larga", "nueva", "corta")).estado()).isEqualTo(400);

        assertThat(llamar("POST", "/api/v1/me/password", ana.token(), Map.of("actual", "contrasena-larga", "nueva", "contrasena-nueva")).estado()).isEqualTo(204);

        assertThat(login(ana.email(), "contrasena-nueva").estado()).isEqualTo(200);
        assertThat(login(ana.email(), "contrasena-larga").estado()).isEqualTo(401);
        // El email no distingue mayusculas.
        assertThat(login(ana.email().toUpperCase(), "contrasena-nueva").estado()).isEqualTo(200);
    }

    // ------------------------------------------------------------- WebSocket

    @Test
    void un_no_miembro_no_se_suscribe_al_topico_de_un_diagrama_ajeno() throws Exception {
        Cuenta ana = registrar("Ana");
        Cuenta ajeno = registrar("Ajeno");
        Proyecto p = crearProyecto(ana, "Biblioteca");

        try (ClienteStomp intruso = ClienteStomp.conectar(puerto, ajeno.token())) {
            intruso.suscribir("/topic/diagram/" + p.diagramaId() + "/ops");

            String error = intruso.errores.poll(5, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(error).as("el servidor tenia que rechazar el SUBSCRIBE").contains("No sos miembro");
        }
    }

    @Test
    void un_cambio_de_rol_se_aplica_en_caliente_y_un_miembro_quitado_deja_de_recibir_operaciones() throws Exception {
        Cuenta ana = registrar("Ana");
        Cuenta beto = registrar("Beto");
        Proyecto p = crearProyecto(ana, "Biblioteca");
        String base = "/api/v1/projects/" + p.id();
        String ops = "/topic/diagram/" + p.diagramaId() + "/ops";
        String locks = "/topic/diagram/" + p.diagramaId() + "/locks";
        String aviso = "/user/queue/membresia";
        String rechazos = "/user/queue/errors";
        llamar("POST", base + "/members", ana.token(), Map.of("email", beto.email(), "role", "VIEWER"));

        try (ClienteStomp deAna = ClienteStomp.conectar(puerto, ana.token());
             ClienteStomp deBeto = ClienteStomp.conectar(puerto, beto.token())) {
            deAna.suscribir(ops);
            deAna.suscribir(locks);
            deBeto.suscribir(ops);
            deBeto.suscribir(locks);
            deBeto.suscribir(aviso);
            deBeto.suscribir(rechazos);
            Thread.sleep(500);   // que el broker registre las suscripciones

            // 1. Observador: lee lo que Ana hace, pero su propio comando se rechaza.
            enviarClase(deAna, p, "Libro");
            assertThat(deAna.siguiente(ops, 5000)).isNotNull();
            assertThat(deBeto.siguiente(ops, 5000)).as("el observador ve lo que otros editan").isNotNull();
            enviarClase(deBeto, p, "Intruso");
            JsonNode rechazo = deBeto.siguiente(rechazos, 5000);
            assertThat(rechazo).isNotNull();
            assertThat(rechazo.get("reason").asText()).isEqualTo("FORBIDDEN");

            // 2. Ana lo promueve: sin recargar ni reconectar, su proximo comando entra.
            llamar("PATCH", base + "/members/" + beto.id(), ana.token(), Map.of("role", "EDITOR"));
            JsonNode avisoDeRol = deBeto.siguiente(aviso, 5000);
            assertThat(avisoDeRol).as("aviso privado al cambiar el rol").isNotNull();
            assertThat(avisoDeRol.get("reason").asText()).isEqualTo("ROL_CAMBIADO");
            assertThat(avisoDeRol.get("role").asText()).isEqualTo("EDITOR");
            assertThat(avisoDeRol.get("projectId").asText()).isEqualTo(p.id().toString());
            enviarClase(deBeto, p, "Autor");
            assertThat(deBeto.siguiente(ops, 5000)).as("el editor recien promovido escribe").isNotNull();
            assertThat(deAna.siguiente(ops, 5000)).isNotNull();

            // 3. Beto toma un lock; al degradarlo, se libera y se difunde.
            UUID clase = UUID.randomUUID();
            deBeto.enviar("/app/diagram/" + p.diagramaId() + "/lock",
                    Map.of("elementId", clase.toString(), "userName", "Beto", "held", true));
            JsonNode tomado = deAna.siguiente(locks, 5000);
            assertThat(tomado).isNotNull();
            assertThat(tomado.get("held").asBoolean()).isTrue();

            llamar("PATCH", base + "/members/" + beto.id(), ana.token(), Map.of("role", "VIEWER"));
            assertThat(deBeto.siguiente(aviso, 5000).get("role").asText()).isEqualTo("VIEWER");
            JsonNode liberado = deAna.siguiente(locks, 5000);
            assertThat(liberado).as("se difunde que el lock quedo libre").isNotNull();
            assertThat(liberado.get("held").asBoolean()).isFalse();
            assertThat(liberado.get("elementId").asText()).isEqualTo(clase.toString());
            enviarClase(deBeto, p, "Otra");
            assertThat(deBeto.siguiente(rechazos, 5000).get("reason").asText()).isEqualTo("FORBIDDEN");

            // Un observador degradado sigue leyendo: todavia recibe las operaciones.
            enviarClase(deAna, p, "Editorial");
            assertThat(deBeto.siguiente(ops, 5000)).isNotNull();
            assertThat(deAna.siguiente(ops, 5000)).isNotNull();

            // 4. Ana lo quita: aviso sin rol, y a partir de ahi no le llega ninguna operacion.
            assertThat(llamar("DELETE", base + "/members/" + beto.id(), ana.token(), null).estado()).isEqualTo(204);
            JsonNode baja = deBeto.siguiente(aviso, 5000);
            assertThat(baja).isNotNull();
            assertThat(baja.get("reason").asText()).isEqualTo("QUITADO");
            assertThat(baja.has("role")).as("sin rol = sin acceso").isFalse();

            enviarClase(deAna, p, "Ejemplar");
            assertThat(deAna.siguiente(ops, 5000)).isNotNull();
            assertThat(deBeto.siguiente(ops, 2000)).as("el miembro quitado ya no recibe operaciones").isNull();

            enviarClase(deBeto, p, "Colado");
            JsonNode rechazoFinal = deBeto.siguiente(rechazos, 5000);
            assertThat(rechazoFinal).as("su proximo comando se rechaza").isNotNull();
            assertThat(rechazoFinal.get("reason").asText()).isEqualTo("FORBIDDEN");
            assertThat(rechazoFinal.get("message").asText()).contains("No sos miembro");
        }
        // Nada de lo del intruso llego al modelo.
        assertThat(jdbc.queryForList("select name from uml_class where diagram_id = ? order by name", String.class, p.diagramaId()))
                .containsExactly("Autor", "Editorial", "Ejemplar", "Libro");
    }

    // ----------------------------------------------------------------- apoyo

    private void enviarClase(ClienteStomp cliente, Proyecto p, String nombre) {
        cliente.enviar("/app/diagram/" + p.diagramaId() + "/op", Map.of(
                "opId", UUID.randomUUID().toString(), "diagramId", p.diagramaId().toString(), "baseSeq", 0,
                "type", "CREATE_CLASS", "origin", "MOUSE",
                "payload", Map.of("classId", UUID.randomUUID().toString(), "name", nombre, "x", 10, "y", 10)));
    }

    private Cuenta registrar(String nombre) throws Exception {
        String email = "it-" + nombre.toLowerCase() + "-" + marca + "@prueba.test";
        Resp r = llamar("POST", "/api/v1/auth/register", null,
                Map.of("email", email, "password", "contrasena-larga", "fullName", nombre));
        assertThat(r.estado()).as("registrar a " + nombre).isEqualTo(200);
        return new Cuenta(nombre, email, UUID.fromString(r.cuerpo().get("userId").asText()), r.cuerpo().get("token").asText());
    }

    private Resp login(String email, String contrasena) throws Exception {
        return llamar("POST", "/api/v1/auth/login", null, Map.of("email", email, "password", contrasena));
    }

    private Proyecto crearProyecto(Cuenta duenio, String nombre) throws Exception {
        Resp r = llamar("POST", "/api/v1/projects", duenio.token(), Map.of("name", nombre));
        assertThat(r.estado()).isEqualTo(201);
        return new Proyecto(UUID.fromString(r.cuerpo().get("id").asText()),
                UUID.fromString(r.cuerpo().get("diagrams").get(0).get("id").asText()));
    }

    /**
     * Sin cabecera Accept a proposito: es lo que manda `fetch()` por defecto
     * (`*&#47;*`). Con `jackson-dataformat-xml` en el classpath --lo usa XMI-- un
     * servidor mal ordenado contestaria XML, y `readTree` fallaria aca.
     */
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
