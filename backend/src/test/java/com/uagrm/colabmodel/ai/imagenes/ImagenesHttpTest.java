package com.uagrm.colabmodel.ai.imagenes;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.uagrm.colabmodel.ai.AvisosDeIA;
import com.uagrm.colabmodel.config.ManejadorDeErrores;
import com.uagrm.colabmodel.iam.AppUser;
import com.uagrm.colabmodel.project.AccesoAProyecto.Rol;
import com.uagrm.colabmodel.project.Diagram;
import com.uagrm.colabmodel.project.Project;
import com.uagrm.colabmodel.project.ProyectosEnMemoria;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.awt.Color;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fase 1 del chat con imagenes (docs/DISENO-CHAT-IMAGENES.md): permisos, codigos de error, deduplicacion, tope,
 * borrado logico y avisos de los endpoints `/ai/imagenes`. El controlador y el servicio son los reales; el
 * repositorio, el almacen de bytes y el reencodador son dobles en memoria ({@link ImagenesEnMemoria}).
 *
 * Lo que necesita PostgreSQL real queda como pendiente y NO cuenta como aprobado: el borrado en cascada del
 * diagrama, el `FOR UPDATE` frente a dos subidas simultaneas, el indice unico parcial y la validacion de las
 * entidades contra V14.
 */
class ImagenesHttpTest {

    private ProyectosEnMemoria datos;
    private ImagenesEnMemoria memoria;
    private AppUser ana;      // OWNER
    private AppUser beto;     // EDITOR
    private AppUser dora;     // otra EDITOR
    private AppUser carla;    // VIEWER
    private AppUser extrano;  // no es miembro
    private Project proyecto;
    private Diagram diagrama;
    private Diagram otroDiagrama;
    private final SimpMessagingTemplate broker = mock(SimpMessagingTemplate.class);
    private MockMvc mvc;

    @BeforeEach
    void prepararDosDiagramasYCuatroRoles() {
        datos = new ProyectosEnMemoria();
        ana = datos.usuario("Ana");
        proyecto = datos.proyecto(ana, "Biblioteca");
        diagrama = datos.diagramas.values().iterator().next();
        otroDiagrama = datos.diagrama(proyecto, "Otro");
        beto = datos.miembro(proyecto, "Beto", Rol.EDITOR);
        dora = datos.miembro(proyecto, "Dora", Rol.EDITOR);
        carla = datos.miembro(proyecto, "Carla", Rol.VIEWER);
        extrano = datos.usuario("Extrano");
        memoria = new ImagenesEnMemoria(datos);
        montar(memoria.servicio(100, 209_715_200L));
    }

    private void montar(ImagenesDelChat servicio) {
        ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .setSerializationInclusion(JsonInclude.Include.NON_NULL);
        mvc = MockMvcBuilders.standaloneSetup(new ImagenesController(servicio, datos.acceso, new AvisosDeIA(broker)))
                .setControllerAdvice(new ManejadorDeErrores())
                .setMessageConverters(new org.springframework.http.converter.ByteArrayHttpMessageConverter(),
                        new MappingJackson2HttpMessageConverter(json)).build();
    }

    // ------------------------------------------------------------------ ayudas

    private ResultActions como(AppUser quien, MockHttpServletRequestBuilder pedido) throws Exception {
        pedido.principal(() -> quien.getId().toString()).accept(MediaType.APPLICATION_JSON, MediaType.ALL);
        return mvc.perform(pedido);
    }

    private String base(Diagram d) {
        return "/api/v1/diagrams/" + d.getId() + "/ai/imagenes";
    }

    private ResultActions subir(AppUser quien, Diagram d, byte[] bytes, String tipo) throws Exception {
        return como(quien, multipart(base(d)).file(new MockMultipartFile("imagen", "foto.x", tipo, bytes)));
    }

    private UUID subida(AppUser quien, Diagram d, Color color) throws Exception {
        MvcResult r = subir(quien, d, ImagenesEnMemoria.png(64, color), "image/png")
                .andExpect(status().isCreated()).andReturn();
        return UUID.fromString(new ObjectMapper().readTree(r.getResponse().getContentAsString()).get("id").asText());
    }

    private List<AvisosDeIA.Aviso> avisos() {
        ArgumentCaptor<Object> carga = ArgumentCaptor.forClass(Object.class);
        verify(broker, org.mockito.Mockito.atLeast(0)).convertAndSend(eq(AvisosDeIA.topico(diagrama.getId())), carga.capture());
        return carga.getAllValues().stream().map(o -> (AvisosDeIA.Aviso) o).toList();
    }

    // ------------------------------------------------------- 1 y 2: permisos

    @Test
    void un_VIEWER_no_puede_subir_y_no_queda_nada() throws Exception {
        subir(carla, diagrama, ImagenesEnMemoria.png(64, Color.WHITE), "image/png")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.mensaje").value("Un VIEWER no puede subir imágenes"));

        assertThat(memoria.filas).isEmpty();
        verifyNoInteractions(broker);
    }

    @Test
    void quien_no_es_miembro_no_puede_subir_listar_ni_descargar() throws Exception {
        UUID id = subida(beto, diagrama, Color.WHITE);

        subir(extrano, diagrama, ImagenesEnMemoria.png(64, Color.YELLOW), "image/png").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.mensaje").value("No sos miembro de este proyecto"));
        como(extrano, get(base(diagrama))).andExpect(status().isForbidden());
        como(extrano, get(base(diagrama) + "/" + id)).andExpect(status().isForbidden());
        como(extrano, get(base(diagrama) + "/" + id + "/miniatura")).andExpect(status().isForbidden());
        como(extrano, delete(base(diagrama) + "/" + id)).andExpect(status().isForbidden());
    }

    // ------------------------------------------------- 3: otro diagrama es 404

    @Test
    void una_imagen_de_otro_diagrama_da_404_en_bytes_miniatura_y_borrado_nunca_403() throws Exception {
        UUID id = subida(beto, diagrama, Color.WHITE);

        // Ana es miembro de los dos diagramas: no es un problema de permisos, la imagen no es de ese diagrama.
        como(ana, get(base(otroDiagrama) + "/" + id)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.mensaje").value("Esa imagen no existe en este diagrama"));
        como(ana, get(base(otroDiagrama) + "/" + id + "/miniatura")).andExpect(status().isNotFound());
        como(ana, delete(base(otroDiagrama) + "/" + id)).andExpect(status().isNotFound());
        como(ana, get(base(otroDiagrama))).andExpect(jsonPath("$.imagenes").isEmpty());
        assertThat(memoria.filas.get(id).estaEliminada()).isFalse();
    }

    @Test
    void una_imagen_que_no_existe_da_404() throws Exception {
        como(ana, get(base(diagrama) + "/" + UUID.randomUUID())).andExpect(status().isNotFound());
    }

    @Test
    void un_id_que_no_es_uuid_da_400_explicado() throws Exception {
        como(ana, get(base(diagrama) + "/no-es-un-uuid")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.mensaje").value("El identificador «imagenId» no es válido"));
    }

    // -------------------------------------------------- 5: entradas invalidas

    @Test
    void lo_que_no_es_una_imagen_aceptable_da_400_con_el_mensaje_de_la_vision() throws Exception {
        // No es una imagen, aunque diga ser un PNG.
        subir(beto, diagrama, "esto no es una imagen".getBytes(), "image/png").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("IMAGEN_INVALIDA"))
                .andExpect(jsonPath("$.mensaje").isNotEmpty());
        // Formato que no se acepta.
        subir(beto, diagrama, ImagenesEnMemoria.png(64, Color.WHITE), "image/gif").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.mensaje").value(org.hamcrest.Matchers.containsString("Se aceptan PNG y JPEG")));
        // Demasiado chica para contener un diagrama (menos de 32 px).
        subir(beto, diagrama, ImagenesEnMemoria.png(16, Color.WHITE), "image/png").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.mensaje").value(org.hamcrest.Matchers.containsString("demasiado chica")));
        // Vacia.
        subir(beto, diagrama, new byte[0], "image/png").andExpect(status().isBadRequest());
        // Mas de 8 MB.
        subir(beto, diagrama, new byte[8 * 1024 * 1024 + 1], "image/png").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.mensaje").value(org.hamcrest.Matchers.containsString("el maximo es 8192 KB")));

        assertThat(memoria.filas).isEmpty();
        verifyNoInteractions(broker);
    }

    @Test
    void sin_la_parte_imagen_da_400() throws Exception {
        como(beto, multipart(base(diagrama)).file(new MockMultipartFile("otra", "x.png", "image/png",
                ImagenesEnMemoria.png(64, Color.WHITE)))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.mensaje").value("Falta el archivo en la parte «imagen»"));
    }

    // -------------------------------------------------------- 6: duplicados

    @Test
    void la_misma_imagen_dos_veces_deja_una_fila_viva_y_la_segunda_es_200_sin_aviso() throws Exception {
        byte[] foto = ImagenesEnMemoria.png(64, Color.WHITE);

        MvcResult primera = subir(beto, diagrama, foto, "image/png").andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith(base(diagrama) + "/")))
                .andReturn();
        MvcResult segunda = subir(dora, diagrama, foto, "image/png").andExpect(status().isOk()).andReturn();

        JsonNode a = new ObjectMapper().readTree(primera.getResponse().getContentAsString());
        JsonNode b = new ObjectMapper().readTree(segunda.getResponse().getContentAsString());
        assertThat(b.get("id")).isEqualTo(a.get("id"));
        assertThat(b.get("subidaPorNombre").asText()).isEqualTo("Beto");   // queda quien la subio primero
        assertThat(memoria.vivas()).hasSize(1);
        assertThat(avisos()).extracting(AvisosDeIA.Aviso::tipo).containsExactly(AvisosDeIA.Tipo.IMAGEN_SUBIDA);
    }

    @Test
    void la_misma_imagen_en_otro_diagrama_es_otra_fila() throws Exception {
        byte[] foto = ImagenesEnMemoria.png(64, Color.WHITE);

        subir(beto, diagrama, foto, "image/png").andExpect(status().isCreated());
        subir(beto, otroDiagrama, foto, "image/png").andExpect(status().isCreated());

        assertThat(memoria.vivas()).hasSize(2);
    }

    // ---------------------------------------------------------------- 8: tope

    @Test
    void con_el_tope_de_cantidad_lleno_da_413_explicado_y_no_guarda() throws Exception {
        montar(memoria.servicio(2, 209_715_200L));
        subida(beto, diagrama, Color.WHITE);
        subida(beto, diagrama, Color.YELLOW);

        subir(beto, diagrama, ImagenesEnMemoria.png(64, Color.CYAN), "image/png").andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.mensaje").value("Este diagrama ya tiene 2 imágenes (el máximo). "
                        + "Eliminá alguna de la galería para subir otra"));

        assertThat(memoria.vivas()).hasSize(2);
    }

    @Test
    void con_el_tope_de_bytes_lleno_da_413_con_los_dos_numeros() throws Exception {
        int unaImagen = ImagenesEnMemoria.png(64, Color.WHITE).length;
        montar(memoria.servicio(100, unaImagen + 10));
        subida(beto, diagrama, Color.WHITE);

        subir(beto, diagrama, ImagenesEnMemoria.png(64, Color.YELLOW), "image/png").andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.mensaje").value(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("ya usa"), org.hamcrest.Matchers.containsString(" MB de "),
                        org.hamcrest.Matchers.containsString("y esta pesa"))));

        assertThat(memoria.vivas()).hasSize(1);
    }

    @Test
    void el_tope_es_por_diagrama_y_una_duplicada_no_lo_gasta() throws Exception {
        montar(memoria.servicio(1, 209_715_200L));
        byte[] foto = ImagenesEnMemoria.png(64, Color.WHITE);
        subir(beto, diagrama, foto, "image/png").andExpect(status().isCreated());

        subir(beto, diagrama, foto, "image/png").andExpect(status().isOk());                       // ya estaba
        subir(beto, otroDiagrama, ImagenesEnMemoria.png(64, Color.RED), "image/png")
                .andExpect(status().isCreated());                                                  // otro diagrama
    }

    @Test
    void la_subida_bloquea_el_diagrama_antes_de_contar() throws Exception {
        subida(beto, diagrama, Color.WHITE);

        assertThat(memoria.llamadas).containsExactly("bloqueo", "uso");
    }

    @Test
    void una_configuracion_con_tope_cero_falla_al_arrancar() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> memoria.servicio(0, 1))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> memoria.servicio(1, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------ 13: cabeceras de GET

    @Test
    void el_get_responde_con_el_tipo_guardado_y_las_cabeceras_de_seguridad() throws Exception {
        // El cliente dice que es JPEG pero los bytes son PNG: manda lo guardado, no lo que dijo el cliente.
        MvcResult r = subir(beto, diagrama, ImagenesEnMemoria.png(64, Color.WHITE), "image/jpeg")
                .andExpect(status().isCreated()).andReturn();
        UUID id = UUID.fromString(new ObjectMapper().readTree(r.getResponse().getContentAsString()).get("id").asText());
        String sha = memoria.filas.get(id).getSha256();

        MvcResult bytes = como(carla, get(base(diagrama) + "/" + id)).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Disposition", "inline"))
                .andExpect(header().string("ETag", "\"" + sha + "\""))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("private"), org.hamcrest.Matchers.containsString("no-cache"))))
                .andReturn();
        assertThat(bytes.getResponse().getContentAsByteArray()).isEqualTo(memoria.bytes.get(id));
        assertThat(bytes.getResponse().getHeader("Cache-Control")).doesNotContain("max-age");
        assertThat(bytes.getResponse().getContentLength()).isEqualTo(memoria.bytes.get(id).length);
    }

    @Test
    void la_miniatura_es_siempre_jpeg_con_las_mismas_cabeceras_y_su_propio_etag() throws Exception {
        UUID id = subida(beto, diagrama, Color.WHITE);
        String sha = memoria.filas.get(id).getSha256();

        como(carla, get(base(diagrama) + "/" + id + "/miniatura")).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/jpeg"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("ETag", "\"" + sha + "-t\""));
    }

    @Test
    void con_if_none_match_igual_al_etag_responde_304_sin_cuerpo_y_con_otro_valor_manda_la_imagen() throws Exception {
        UUID id = subida(beto, diagrama, Color.WHITE);
        String etag = "\"" + memoria.filas.get(id).getSha256() + "\"";

        MvcResult sin = como(ana, get(base(diagrama) + "/" + id).header("If-None-Match", etag))
                .andExpect(status().isNotModified()).andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andReturn();
        assertThat(sin.getResponse().getContentAsByteArray()).isEmpty();
        como(ana, get(base(diagrama) + "/" + id).header("If-None-Match", "W/" + etag)).andExpect(status().isNotModified());
        como(ana, get(base(diagrama) + "/" + id).header("If-None-Match", "\"otro\"")).andExpect(status().isOk());
    }

    // ---------------------------------------------------------- galeria y uso

    @Test
    void la_galeria_lista_solo_metadatos_de_las_vivas_mas_nuevas_primero_con_el_uso() throws Exception {
        UUID primera = subida(beto, diagrama, Color.WHITE);
        UUID segunda = subida(dora, diagrama, Color.YELLOW);

        MvcResult r = como(carla, get(base(diagrama))).andExpect(status().isOk())
                .andExpect(jsonPath("$.imagenes.length()").value(2))
                .andExpect(jsonPath("$.imagenes[0].id").value(segunda.toString()))
                .andExpect(jsonPath("$.imagenes[1].id").value(primera.toString()))
                .andExpect(jsonPath("$.imagenes[0].subidaPorNombre").value("Dora"))
                .andExpect(jsonPath("$.uso.cantidad").value(2))
                .andExpect(jsonPath("$.uso.maxCantidad").value(100))
                .andExpect(jsonPath("$.uso.maxBytes").value(209_715_200L))
                .andReturn();

        JsonNode una = new ObjectMapper().readTree(r.getResponse().getContentAsString()).get("imagenes").get(0);
        // Metadatos y nada mas: ni bytes, ni miniatura, ni URL, ni nombre de archivo.
        assertThat(una.fieldNames()).toIterable().containsExactlyInAnyOrder("id", "subidaPorId", "subidaPorNombre",
                "tipo", "ancho", "alto", "bytes", "creadaEn", "eliminada");
        assertThat(una.get("eliminada").asBoolean()).isFalse();
        assertThat(memoria.almacen.lecturasDeBytes).isZero();
    }

    @Test
    void subir_responde_con_los_metadatos_de_la_imagen_guardada() throws Exception {
        byte[] foto = ImagenesEnMemoria.jpeg(64, Color.WHITE);

        subir(beto, diagrama, foto, "image/jpeg").andExpect(status().isCreated())
                .andExpect(jsonPath("$.tipo").value("image/jpeg")).andExpect(jsonPath("$.ancho").value(64))
                .andExpect(jsonPath("$.alto").value(64)).andExpect(jsonPath("$.bytes").value(foto.length))
                .andExpect(jsonPath("$.subidaPorId").value(beto.getId().toString()))
                .andExpect(jsonPath("$.subidaPorNombre").value("Beto")).andExpect(jsonPath("$.eliminada").value(false));
    }

    // -------------------------------------------------------- 14 y 15: borrar

    @Test
    void borra_quien_la_subio_y_el_OWNER_y_nadie_mas() throws Exception {
        UUID deBeto = subida(beto, diagrama, Color.WHITE);
        UUID deDora = subida(dora, diagrama, Color.YELLOW);
        UUID tercera = subida(beto, diagrama, Color.CYAN);

        como(dora, delete(base(diagrama) + "/" + deBeto)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.mensaje").value("Solo quien subió la imagen o el propietario puede eliminarla"));
        como(carla, delete(base(diagrama) + "/" + deBeto)).andExpect(status().isForbidden());
        assertThat(memoria.filas.get(deBeto).estaEliminada()).isFalse();

        como(beto, delete(base(diagrama) + "/" + deBeto)).andExpect(status().isOk())
                .andExpect(jsonPath("$.eliminada").value(true));          // quien la subio
        como(ana, delete(base(diagrama) + "/" + deDora)).andExpect(status().isOk())
                .andExpect(jsonPath("$.eliminada").value(true));          // el OWNER, de otro
        assertThat(memoria.vivas()).extracting(ImagenDelChat::getId).containsExactly(tercera);
    }

    @Test
    void quien_subio_pero_ya_es_VIEWER_no_puede_borrar_y_el_OWNER_si() throws Exception {
        UUID id = subida(beto, diagrama, Color.WHITE);
        datos.miembros.stream().filter(m -> m.getUserId().equals(beto.getId())).forEach(m -> m.setRole(Rol.VIEWER.name()));

        como(beto, delete(base(diagrama) + "/" + id)).andExpect(status().isForbidden());
        como(ana, delete(base(diagrama) + "/" + id)).andExpect(status().isOk());
    }

    @Test
    void borrar_deja_la_fila_sin_bytes_y_el_turno_podra_decir_imagen_eliminada() throws Exception {
        UUID id = subida(beto, diagrama, Color.WHITE);
        int peso = memoria.filas.get(id).getSizeBytes();

        como(beto, delete(base(diagrama) + "/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString())).andExpect(jsonPath("$.eliminada").value(true))
                .andExpect(jsonPath("$.eliminadaEn").isNotEmpty())
                .andExpect(jsonPath("$.tipo").value("image/png")).andExpect(jsonPath("$.bytes").value(peso))
                .andExpect(jsonPath("$.ancho").value(64));

        assertThat(memoria.filas).containsKey(id);                // la fila queda
        assertThat(memoria.filas.get(id).getDeletedBy()).isEqualTo(beto.getId());
        assertThat(memoria.bytes).doesNotContainKey(id);          // los bytes no
        assertThat(memoria.miniaturas).doesNotContainKey(id);
    }

    @Test
    void borrar_dos_veces_es_idempotente_y_el_aviso_sale_una_sola_vez() throws Exception {
        UUID id = subida(beto, diagrama, Color.WHITE);

        como(beto, delete(base(diagrama) + "/" + id)).andExpect(status().isOk());
        como(beto, delete(base(diagrama) + "/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.eliminada").value(true));

        assertThat(avisos()).extracting(AvisosDeIA.Aviso::tipo)
                .containsExactly(AvisosDeIA.Tipo.IMAGEN_SUBIDA, AvisosDeIA.Tipo.IMAGEN_ELIMINADA);
    }

    @Test
    void una_eliminada_da_404_en_bytes_y_miniatura_y_no_sale_en_la_galeria() throws Exception {
        UUID id = subida(beto, diagrama, Color.WHITE);
        como(beto, delete(base(diagrama) + "/" + id)).andExpect(status().isOk());

        como(carla, get(base(diagrama) + "/" + id)).andExpect(status().isNotFound());
        como(carla, get(base(diagrama) + "/" + id + "/miniatura")).andExpect(status().isNotFound());
        como(carla, get(base(diagrama))).andExpect(jsonPath("$.imagenes").isEmpty());
    }

    @Test
    void eliminar_libera_el_cupo_y_volver_a_subir_la_misma_imagen_crea_una_fila_nueva() throws Exception {
        montar(memoria.servicio(1, 209_715_200L));
        byte[] foto = ImagenesEnMemoria.png(64, Color.WHITE);
        UUID vieja = subida(beto, diagrama, Color.WHITE);
        subir(beto, diagrama, ImagenesEnMemoria.png(64, Color.YELLOW), "image/png").andExpect(status().isPayloadTooLarge());

        como(beto, delete(base(diagrama) + "/" + vieja)).andExpect(status().isOk());
        MvcResult nueva = subir(beto, diagrama, foto, "image/png").andExpect(status().isCreated()).andReturn();

        UUID idNueva = UUID.fromString(new ObjectMapper().readTree(nueva.getResponse().getContentAsString()).get("id").asText());
        assertThat(idNueva).isNotEqualTo(vieja);
        assertThat(memoria.filas).hasSize(2);
        assertThat(memoria.vivas()).extracting(ImagenDelChat::getId).containsExactly(idNueva);
    }

    // -------------------------------------------------------------- 17: avisos

    @Test
    void los_avisos_llevan_la_imagen_y_no_la_entrada_y_van_al_topico_del_diagrama() throws Exception {
        UUID id = subida(beto, diagrama, Color.WHITE);
        como(ana, delete(base(diagrama) + "/" + id)).andExpect(status().isOk());

        List<AvisosDeIA.Aviso> avisos = avisos();
        assertThat(avisos).hasSize(2);
        assertThat(avisos.get(0).tipo()).isEqualTo(AvisosDeIA.Tipo.IMAGEN_SUBIDA);
        assertThat(avisos.get(0).entrada()).isNull();
        assertThat(avisos.get(0).imagen().id()).isEqualTo(id);
        assertThat(avisos.get(0).imagen().eliminada()).isFalse();
        assertThat(avisos.get(1).tipo()).isEqualTo(AvisosDeIA.Tipo.IMAGEN_ELIMINADA);
        assertThat(avisos.get(1).imagen().eliminada()).isTrue();
        // Con `non_null` el aviso de imagen no trae `entrada`, que es lo que espera el cliente para ignorarlo.
        String json = new ObjectMapper().registerModule(new JavaTimeModule())
                .setSerializationInclusion(JsonInclude.Include.NON_NULL).writeValueAsString(avisos.get(0));
        assertThat(json).contains("\"imagen\"").doesNotContain("\"entrada\"");
    }

    @Test
    void los_avisos_nuevos_de_historial_siguen_siendo_los_de_siempre() {
        var viejo = new AvisosDeIA.Aviso(AvisosDeIA.Tipo.NUEVA, null);

        assertThat(viejo.imagen()).isNull();
        assertThat(AvisosDeIA.Tipo.values()).extracting(Enum::name)
                .containsExactly("NUEVA", "APLICADA", "IMAGEN_SUBIDA", "IMAGEN_ELIMINADA");
    }

    // ------------------------------------------------ mientras no hay costuras

    @Test
    void sin_almacen_ni_reencodador_conectados_subir_da_503_explicado_y_no_rompe_el_resto() throws Exception {
        montar(memoria.servicioSinCosturas());

        subir(beto, diagrama, ImagenesEnMemoria.png(64, Color.WHITE), "image/png")
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.mensaje").value("El reencodado de imágenes todavía no está disponible"));
        como(carla, get(base(diagrama))).andExpect(status().isOk());      // la galeria solo lee metadatos
    }

    // ------------------------------------------------- 21: 413 del multipart

    @Test
    void el_413_del_multipart_global_dice_8_MB_en_imagenes_y_conserva_el_texto_del_xmi_en_el_resto() {
        var manejador = new ManejadorDeErrores();
        var excepcion = new org.springframework.web.multipart.MaxUploadSizeExceededException(25L * 1024 * 1024);

        var deImagen = manejador.archivoGrande(excepcion, new org.springframework.mock.web.MockHttpServletRequest(
                "POST", "/api/v1/diagrams/" + diagrama.getId() + "/ai/imagenes"));
        var deImportacion = manejador.archivoGrande(excepcion, new org.springframework.mock.web.MockHttpServletRequest(
                "POST", "/api/v1/diagrams/" + diagrama.getId() + "/import"));

        assertThat(deImagen.getStatusCode().value()).isEqualTo(413);
        assertThat(deImagen.getBody().get("mensaje")).isEqualTo("La imagen es demasiado grande: el máximo es de 8 MB");
        assertThat(deImportacion.getStatusCode().value()).isEqualTo(413);
        assertThat(deImportacion.getBody().get("mensaje")).contains("25 MB").contains("XMI");
    }
}
