package com.uagrm.colabmodel.project;

import com.uagrm.colabmodel.config.ManejadorDeErrores;
import com.uagrm.colabmodel.iam.AppUser;
import com.uagrm.colabmodel.project.AccesoAProyecto.Rol;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Los codigos de respuesta y la forma `{"mensaje": ...}` de los endpoints de
 * proyectos, miembros y diagramas (ADR-026).
 *
 * Es el controlador REAL con los servicios reales sobre repositorios en
 * memoria: lo que se comprueba es la traduccion a HTTP. La cadena de seguridad
 * (401) y la base (transacciones, el CHECK de V6) las prueban
 * `RespuestasDeSeguridadTest` y `ApiDeProyectosTest`.
 */
class RespuestasHttpTest {

    private MockMvc mvc;
    private ProyectosEnMemoria datos;
    private AppUser ana;      // propietaria
    private AppUser beto;     // EDITOR
    private AppUser carla;    // VIEWER
    private AppUser extrano;
    private Project proyecto;
    private Diagram diagrama;

    @BeforeEach
    void prepararLaApi() {
        datos = new ProyectosEnMemoria();
        ApplicationEventPublisher eventos = mock(ApplicationEventPublisher.class);
        var proyectos = new GestionDeProyectos(datos.projects, datos.members, datos.diagrams, datos.users,
                datos.acceso, eventos);
        var miembros = new GestionDeMiembros(datos.projects, datos.members, datos.diagrams, datos.users,
                datos.acceso, eventos);
        mvc = MockMvcBuilders.standaloneSetup(new ProyectoRestController(proyectos, miembros))
                .setControllerAdvice(new ManejadorDeErrores()).build();

        ana = datos.usuario("Ana");
        proyecto = datos.proyecto(ana, "Biblioteca");
        diagrama = datos.diagramas.values().iterator().next();
        beto = datos.miembro(proyecto, "Beto", Rol.EDITOR);
        carla = datos.miembro(proyecto, "Carla", Rol.VIEWER);
        extrano = datos.usuario("Extrano");
    }

    // ---------------------------------------------------------------- 2xx

    @Test
    void crear_un_proyecto_responde_201_con_su_primer_diagrama() throws Exception {
        como(ana, post("/api/v1/projects"), "{\"name\":\"Barberia\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Barberia"))
                .andExpect(jsonPath("$.myRole").value("OWNER"))
                .andExpect(jsonPath("$.diagrams.length()").value(1));
    }

    @Test
    void mis_proyectos_trae_el_rol_el_propietario_y_la_cantidad_de_diagramas() throws Exception {
        como(beto, get("/api/v1/projects"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Biblioteca"))
                .andExpect(jsonPath("$[0].myRole").value("EDITOR"))
                .andExpect(jsonPath("$[0].owner.email").value(ana.getEmail()))
                .andExpect(jsonPath("$[0].diagramCount").value(1));
    }

    @Test
    void agregar_un_miembro_responde_201_y_eliminar_o_quitar_responden_204() throws Exception {
        AppUser dani = datos.usuario("Dani");

        como(ana, post("/api/v1/projects/" + proyecto.getId() + "/members"),
                "{\"email\":\"" + dani.getEmail() + "\",\"role\":\"VIEWER\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("VIEWER"));
        como(ana, delete("/api/v1/projects/" + proyecto.getId() + "/members/" + dani.getId()), null)
                .andExpect(status().isNoContent());
        como(ana, delete("/api/v1/diagrams/" + diagrama.getId()), null).andExpect(status().isNoContent());
        como(ana, delete("/api/v1/projects/" + proyecto.getId()), null).andExpect(status().isNoContent());
    }

    @Test
    void transferir_responde_204_y_el_cambio_se_ve_en_la_lista_de_miembros() throws Exception {
        como(ana, post("/api/v1/projects/" + proyecto.getId() + "/transfer"), "{\"userId\":\"" + beto.getId() + "\"}")
                .andExpect(status().isNoContent());

        como(carla, get("/api/v1/projects/" + proyecto.getId() + "/members"), null)
                .andExpect(jsonPath("$[0].userId").value(beto.getId().toString()))
                .andExpect(jsonPath("$[0].role").value("OWNER"))
                // Despues del propietario, por antiguedad: Carla ya estaba; Ana entra como EDITOR al transferir.
                .andExpect(jsonPath("$[1].userId").value(carla.getId().toString()))
                .andExpect(jsonPath("$[1].role").value("VIEWER"))
                .andExpect(jsonPath("$[2].userId").value(ana.getId().toString()))
                .andExpect(jsonPath("$[2].role").value("EDITOR"));
    }

    // ---------------------------------------------------------------- 403

    @Test
    void el_no_miembro_recibe_403_con_mensaje_en_todo_endpoint() throws Exception {
        String p = "/api/v1/projects/" + proyecto.getId();
        for (ResultActions r : new ResultActions[]{
                como(extrano, get(p), null),
                como(extrano, patch(p), "{\"name\":\"X\"}"),
                como(extrano, delete(p), null),
                como(extrano, post(p + "/diagrams"), "{\"name\":\"X\"}"),
                como(extrano, patch("/api/v1/diagrams/" + diagrama.getId()), "{\"name\":\"X\"}"),
                como(extrano, delete("/api/v1/diagrams/" + diagrama.getId()), null),
                como(extrano, get(p + "/members"), null),
                como(extrano, post(p + "/members"), "{\"email\":\"" + carla.getEmail() + "\",\"role\":\"VIEWER\"}"),
                como(extrano, patch(p + "/members/" + carla.getId()), "{\"role\":\"EDITOR\"}"),
                como(extrano, delete(p + "/members/" + carla.getId()), null),
                como(extrano, post(p + "/transfer"), "{\"userId\":\"" + carla.getId() + "\"}")}) {
            r.andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.mensaje").value("No sos miembro de este proyecto"));
        }
    }

    @Test
    void un_observador_y_un_editor_reciben_403_en_lo_que_no_les_toca() throws Exception {
        String p = "/api/v1/projects/" + proyecto.getId();

        como(carla, post(p + "/diagrams"), "{\"name\":\"X\"}").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.mensaje").value("Como Observador no podés crear ni renombrar diagramas"));
        como(beto, delete("/api/v1/diagrams/" + diagrama.getId()), null).andExpect(status().isForbidden());
        como(beto, patch(p), "{\"name\":\"X\"}").andExpect(status().isForbidden());
        como(beto, delete(p), null).andExpect(status().isForbidden());
        como(beto, post(p + "/members"), "{\"email\":\"" + extrano.getEmail() + "\",\"role\":\"VIEWER\"}")
                .andExpect(status().isForbidden());
        como(beto, patch(p + "/members/" + carla.getId()), "{\"role\":\"EDITOR\"}").andExpect(status().isForbidden());
        como(beto, delete(p + "/members/" + carla.getId()), null).andExpect(status().isForbidden());
        como(beto, post(p + "/transfer"), "{\"userId\":\"" + beto.getId() + "\"}").andExpect(status().isForbidden());
    }

    @Test
    void el_propietario_no_puede_abandonar_403_con_la_indicacion() throws Exception {
        como(ana, delete("/api/v1/projects/" + proyecto.getId() + "/members/" + ana.getId()), null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.mensaje").value(org.hamcrest.Matchers.containsString("transferí la propiedad")));
    }

    @Test
    void editor_y_observador_abandonan_con_204() throws Exception {
        como(beto, delete("/api/v1/projects/" + proyecto.getId() + "/members/" + beto.getId()), null)
                .andExpect(status().isNoContent());
        como(carla, delete("/api/v1/projects/" + proyecto.getId() + "/members/" + carla.getId()), null)
                .andExpect(status().isNoContent());
    }

    // ------------------------------------------------------ 404 / 409 / 400

    @Test
    void un_email_inexistente_da_404_y_un_duplicado_409_con_mensajes_en_espanol() throws Exception {
        String miembros = "/api/v1/projects/" + proyecto.getId() + "/members";

        como(ana, post(miembros), "{\"email\":\"nadie@uagrm.edu.bo\",\"role\":\"VIEWER\"}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.mensaje").value("No hay ningún usuario registrado con ese email"));
        como(ana, post(miembros), "{\"email\":\"" + beto.getEmail() + "\",\"role\":\"VIEWER\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.mensaje").value("Ese usuario ya es miembro del proyecto"));
    }

    @Test
    void owner_por_el_endpoint_de_roles_da_400_y_degradar_o_quitar_al_propietario_409() throws Exception {
        String p = "/api/v1/projects/" + proyecto.getId();

        como(ana, patch(p + "/members/" + carla.getId()), "{\"role\":\"OWNER\"}").andExpect(status().isBadRequest());
        como(ana, post(p + "/members"), "{\"email\":\"" + extrano.getEmail() + "\",\"role\":\"OWNER\"}")
                .andExpect(status().isBadRequest());
        como(ana, patch(p + "/members/" + ana.getId()), "{\"role\":\"VIEWER\"}").andExpect(status().isConflict());
    }

    @Test
    void transferir_a_quien_no_es_miembro_da_404_y_a_uno_mismo_409() throws Exception {
        String p = "/api/v1/projects/" + proyecto.getId() + "/transfer";

        como(ana, post(p), "{\"userId\":\"" + extrano.getId() + "\"}").andExpect(status().isNotFound());
        como(ana, post(p), "{\"userId\":\"" + ana.getId() + "\"}").andExpect(status().isConflict());
    }

    @Test
    void la_validacion_de_los_cuerpos_da_400_con_el_mensaje_en_espanol() throws Exception {
        String p = "/api/v1/projects/" + proyecto.getId();

        como(ana, post("/api/v1/projects"), "{\"name\":\"  \"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.mensaje").value("El nombre es obligatorio"));
        como(ana, post(p + "/members"), "{\"email\":\"\",\"role\":\"\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.mensaje").value(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("El email es obligatorio"),
                        org.hamcrest.Matchers.containsString("El rol es obligatorio"))));
        como(ana, post(p + "/transfer"), "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.mensaje").value("Indicá a quién se transfiere la propiedad"));
    }

    @Test
    void un_cuerpo_que_no_es_json_y_un_id_que_no_es_uuid_dan_400_no_500() throws Exception {
        como(ana, post("/api/v1/projects"), "esto no es json").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.mensaje").value("El cuerpo de la solicitud no es válido"));
        como(ana, get("/api/v1/projects/no-es-un-uuid"), null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.mensaje").value("El identificador «id» no es válido"));
    }

    @Test
    void las_respuestas_de_error_llevan_utf8_para_que_los_acentos_lleguen_bien() throws Exception {
        var respuesta = como(ana, post("/api/v1/projects"), "{\"name\":\"  \"}").andReturn().getResponse();

        assertThat(respuesta.getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
        assertThat(respuesta.getContentAsString(StandardCharsets.UTF_8)).contains("obligatorio");
    }

    // ---------------------------------------------------------------- apoyo

    private ResultActions como(AppUser quien, MockHttpServletRequestBuilder pedido, String cuerpo) throws Exception {
        // Sin Accept, MockMvc "standalone" elige XML (el proyecto trae
        // jackson-dataformat-xml para XMI). Spring Boot lo reordena al final y el
        // cliente pide JSON de todos modos; ApiDeProyectosTest lo comprueba en vivo.
        pedido.principal(() -> quien.getId().toString()).accept(MediaType.APPLICATION_JSON);
        if (cuerpo != null) pedido.contentType(MediaType.APPLICATION_JSON).content(cuerpo);
        return mvc.perform(pedido);
    }
}
