package com.uagrm.colabmodel.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.uagrm.colabmodel.ai.AiController.SolicitudDePropuesta.Recorte;
import com.uagrm.colabmodel.ai.imagenes.DeteccionesDePrueba;
import com.uagrm.colabmodel.ai.imagenes.DeteccionesDePrueba.DetectorFalso;
import com.uagrm.colabmodel.ai.imagenes.ImagenesDelChat;
import com.uagrm.colabmodel.ai.imagenes.ImagenesEnMemoria;
import com.uagrm.colabmodel.ai.imagenes.ProcesadorDeImagenDelChat;
import com.uagrm.colabmodel.iam.AppUser;
import com.uagrm.colabmodel.modelcore.command.CommandType;
import com.uagrm.colabmodel.modelcore.command.Origin;
import com.uagrm.colabmodel.project.AccesoAProyecto.Rol;
import com.uagrm.colabmodel.project.DiagramOperation;
import com.uagrm.colabmodel.project.DiagramOperationRepository;
import com.uagrm.colabmodel.project.Project;
import com.uagrm.colabmodel.project.ProyectosEnMemoria;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.time.OffsetDateTime;
import java.util.*;

import static com.uagrm.colabmodel.ai.imagenes.DeteccionesDePrueba.pngConMarca;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Fase 2 del chat con imagenes, de punta a punta en memoria: `AiController.proponer` con `modo = IMAGEN`, el turno
 * que queda en el historial y `marcarAplicada` con `Origin.IMAGE` (pruebas 22, 24, 26, 27 y 31 del contrato,
 * docs/DISENO-CHAT-IMAGENES.md). Regla de acceso real sobre proyectos en memoria; detector y proveedor, dobles.
 */
class TurnoImagenTest {

    private static final JsonNodeFactory J = JsonNodeFactory.instance;

    private ProyectosEnMemoria datos;
    private ImagenesEnMemoria memoria;
    private ImagenesDelChat imagenes;
    private AppUser ana;      // propietaria
    private AppUser beto;     // EDITOR
    private AppUser carla;    // VIEWER
    private UUID diagramaId;
    private UUID imagenId;

    private final Map<UUID, InteraccionIA> filas = new LinkedHashMap<>();
    private final Map<UUID, DiagramOperation> log = new HashMap<>();
    private final SimpMessagingTemplate broker = mock(SimpMessagingTemplate.class);
    private final ServicioDeIA servicio = mock(ServicioDeIA.class);
    private final DetectorFalso detector = new DetectorFalso();
    private AiController controlador;

    @BeforeEach
    void preparar() {
        datos = new ProyectosEnMemoria();
        ana = datos.usuario("Ana");
        Project p = datos.proyecto(ana, "Biblioteca");
        diagramaId = datos.diagramas.values().iterator().next().getId();
        beto = datos.miembro(p, "Beto", Rol.EDITOR);
        carla = datos.miembro(p, "Carla", Rol.VIEWER);
        memoria = new ImagenesEnMemoria(datos);
        imagenes = memoria.servicio(100, 209_715_200L);
        imagenId = imagenes.subir(diagramaId, beto.getId(), pngConMarca(200, 100, 10, 20), "image/png").imagen().id();
        detector.resultado = DeteccionesDePrueba.conNombres();

        InteraccionIARepository interacciones = mock(InteraccionIARepository.class);
        when(interacciones.save(any(InteraccionIA.class))).thenAnswer(i -> {
            InteraccionIA f = i.getArgument(0);
            if (f.getId() == null) {
                f.setId(UUID.randomUUID());
                f.setCreatedAt(OffsetDateTime.now().plusNanos(filas.size()));
            }
            filas.put(f.getId(), f);
            return f;
        });
        when(interacciones.findById(any())).thenAnswer(i -> Optional.ofNullable(filas.get(i.<UUID>getArgument(0))));
        when(interacciones.findByDiagramIdOrderByCreatedAtAscIdAsc(any())).thenAnswer(i -> filas.values().stream()
                .filter(f -> f.getDiagramId().equals(i.getArgument(0)))
                .sorted(Comparator.comparing(InteraccionIA::getCreatedAt)).toList());
        DiagramOperationRepository operaciones = mock(DiagramOperationRepository.class);
        when(operaciones.findByOpId(any())).thenAnswer(i -> Optional.ofNullable(log.get(i.<UUID>getArgument(0))));

        HistorialIA historial = new HistorialIA(interacciones, datos.users, operaciones,
                new ObjectMapper().findAndRegisterModules(), imagenes);
        ProcesadorDeImagenDelChat procesador = new ProcesadorDeImagenDelChat(imagenes, detector, List.of(),
                40_000_000L, 1600);
        controlador = new AiController(servicio, datos.acceso, "ollama", "qwen3:8b", 4096,
                mock(CatalogoModelosOllama.class), historial, new AvisosDeIA(broker),
                mock(ServicioDeRecomendaciones.class), procesador);
    }

    // ------------------------------------------------ 22 · el turno se guarda

    @Test
    void el_turno_guarda_la_imagen_y_la_propuesta_con_modo_imagen_y_origen_image() {
        PropuestaDeIA propuesta = leer(beto, "Leé la pizarra", null, null);

        assertThat(filas).hasSize(1);
        InteraccionIA fila = filas.values().iterator().next();
        assertThat(fila.getMode()).isEqualTo(ModoIA.IMAGEN);
        assertThat(fila.getImageId()).isEqualTo(imagenId);
        assertThat(fila.getPrompt()).isEqualTo("Leé la pizarra");
        assertThat(fila.getResponse()).isEqualTo("Leí 2 clases y 1 relación; sin dudas.");
        assertThat(propuesta.origin()).isEqualTo(Origin.IMAGE);
        assertThat(propuesta.aceptados()).isEmpty();
        assertThat(propuesta.lecturaDeImagen()).isNotNull();
        assertThat(propuesta.lecturaDeImagen().deteccion().clases()).hasSize(2);
        assertThat(propuesta.interaccionId()).isEqualTo(fila.getId());

        // El historial devuelve el mismo turno con la imagen y la lectura.
        HistorialIA.Entrada e = controlador.historial(diagramaId, como(ana)).get(0);
        assertThat(e.modo()).isEqualTo(ModoIA.IMAGEN);
        assertThat(e.imagen()).isNotNull();
        assertThat(e.imagen().id()).isEqualTo(imagenId);
        assertThat(e.imagen().eliminada()).isFalse();
        assertThat(e.propuesta().lecturaDeImagen().deteccion().relaciones()).hasSize(1);
        assertThat(e.propuesta().lecturaDeImagen().deteccion().clases().get(0).nombre()).isEqualTo("Cliente");
    }

    @Test
    void el_turno_sale_como_aviso_nueva_con_la_imagen_dentro_de_la_entrada() {
        PropuestaDeIA propuesta = leer(beto, null, null, null);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(broker).convertAndSend(eq("/topic/diagram/" + diagramaId + "/ai"), captor.capture());
        AvisosDeIA.Aviso aviso = (AvisosDeIA.Aviso) captor.getValue();
        assertThat(aviso.tipo()).isEqualTo(AvisosDeIA.Tipo.NUEVA);
        assertThat(aviso.imagen()).isNull();
        assertThat(aviso.entrada().id()).isEqualTo(propuesta.interaccionId());
        assertThat(aviso.entrada().modo()).isEqualTo(ModoIA.IMAGEN);
        assertThat(aviso.entrada().imagen().id()).isEqualTo(imagenId);
    }

    @Test
    void la_propuesta_sobrevive_al_viaje_por_json_con_lectura_dudas_y_opciones() throws Exception {
        PropuestaDeIA propuesta = leer(beto, "x", 90, new Recorte(0, 0, 120, 60));

        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        JsonNode json = mapper.readTree(mapper.writeValueAsString(propuesta));
        assertThat(json.path("lecturaDeImagen").path("opciones").path("giroGrados").asInt()).isEqualTo(90);
        assertThat(json.path("lecturaDeImagen").path("opciones").path("recorte").path("ancho").asInt()).isEqualTo(120);
        PropuestaDeIA otra = mapper.readValue(mapper.writeValueAsString(propuesta), PropuestaDeIA.class);
        assertThat(otra.lecturaDeImagen().opciones().recorte()).isEqualTo(new Recorte(0, 0, 120, 60));
        assertThat(otra).isEqualTo(propuesta);
    }

    // ------------------------------------------------- 31 · sin proveedor de texto

    @Test
    void un_turno_imagen_no_pasa_por_servicio_de_ia_ni_llama_al_proveedor_de_texto() {
        leer(beto, "Leé", null, null);

        verifyNoInteractions(servicio);
        assertThat(detector.llamadas).isEqualTo(1);
    }

    @Test
    void un_pedido_de_texto_sigue_pasando_por_servicio_de_ia() {
        when(servicio.generar(any(), any(), any(), any(), any(), any())).thenReturn(new PropuestaDeIA(diagramaId,
                Origin.TEXT, List.of(), List.of(), List.of(), List.of(), 0, "Listo", "qwen3:8b", null));

        controlador.proponer(diagramaId, new AiController.SolicitudDePropuesta("Agrega Pago", Origin.TEXT,
                ModoIA.MODIFICAR, null), como(beto));

        verify(servicio).generar(eq(diagramaId), eq("Agrega Pago"), eq(Origin.TEXT), eq(ModoIA.MODIFICAR), any(), any());
        assertThat(detector.llamadas).isZero();
        assertThat(filas.values().iterator().next().getImageId()).isNull();
    }

    @Test
    void el_origin_del_cuerpo_se_ignora_el_turno_sale_con_image() {
        PropuestaDeIA propuesta = controlador.proponer(diagramaId, new AiController.SolicitudDePropuesta("x",
                Origin.VOICE, ModoIA.IMAGEN, null, imagenId, null, null), como(beto));

        assertThat(propuesta.origin()).isEqualTo(Origin.IMAGE);
    }

    // --------------------------------------------- 27 · validacion de la solicitud

    @Test
    void modo_imagen_sin_imagen_id_da_400() {
        assertThatThrownBy(() -> pedir(beto, ModoIA.IMAGEN, null, null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Falta la imagen que se quiere leer");
    }

    @Test
    void imagen_id_con_otro_modo_da_400() {
        assertThatThrownBy(() -> pedir(beto, ModoIA.MODIFICAR, imagenId, null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("La imagen solo se usa en el modo IMAGEN");
    }

    @Test
    void giro_o_recorte_con_otro_modo_dan_400() {
        assertThatThrownBy(() -> pedir(beto, ModoIA.MODIFICAR, null, 90, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("El giro y el recorte solo valen al leer una imagen");
        assertThatThrownBy(() -> pedir(beto, ModoIA.CONSULTAR, null, null, new Recorte(0, 0, 50, 50)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("El giro y el recorte solo valen al leer una imagen");
        verifyNoInteractions(servicio);
    }

    @Test
    void el_giro_de_45_da_400_y_el_de_90_el_de_7_y_el_de_menos_90_pasan() {
        for (int malo : new int[]{45, -45, 11, 91, 181, -181, 360, 270, 100}) {
            assertThatThrownBy(() -> pedir(beto, ModoIA.IMAGEN, imagenId, malo, null))
                    .as("giro " + malo)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("El giro debe ser de hasta 10° o un múltiplo de 90°");
        }
        for (int bueno : new int[]{0, 90, 7, -7, 10, -10, -90, 180, -180}) {
            PropuestaDeIA p = pedir(beto, ModoIA.IMAGEN, imagenId, bueno, null);
            assertThat(p.lecturaDeImagen().opciones().giroGrados()).isEqualTo(bueno);
        }
    }

    @Test
    void recorte_fuera_de_la_imagen_o_menor_de_32_da_400() {
        assertThatThrownBy(() -> pedir(beto, ModoIA.IMAGEN, imagenId, null, new Recorte(150, 0, 100, 50)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("El recorte tiene que estar dentro de la imagen (");
        assertThatThrownBy(() -> pedir(beto, ModoIA.IMAGEN, imagenId, null, new Recorte(0, 0, 20, 50)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageEndingWith("y medir al menos 32 px");
        assertThat(filas).isEmpty();   // un pedido invalido no deja turno
    }

    @Test
    void el_prompt_vacio_en_modo_imagen_toma_el_valor_por_defecto_con_quien_subio_la_imagen() {
        leer(beto, "   ", null, null);
        leer(beto, null, null, null);

        assertThat(filas.values()).extracting(InteraccionIA::getPrompt)
                .containsExactly("Leer la imagen subida por Beto", "Leer la imagen subida por Beto");
    }

    @Test
    void un_viewer_no_puede_pedirle_a_la_ia_que_lea_una_imagen_403() {
        assertThatThrownBy(() -> pedir(carla, ModoIA.IMAGEN, imagenId, null, null))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode().value()).isEqualTo(403);
                    assertThat(e.getReason()).isEqualTo("Los VIEWER no pueden pedirle cambios a la IA");
                });
        assertThat(filas).isEmpty();
    }

    @Test
    void un_no_miembro_da_403() {
        AppUser extrano = datos.usuario("Extrano");

        assertThatThrownBy(() -> pedir(extrano, ModoIA.IMAGEN, imagenId, null, null))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(403));
    }

    @Test
    void una_imagen_eliminada_da_404_y_no_deja_turno() {
        imagenes.eliminar(diagramaId, imagenId, ana.getId(), quien -> true);

        assertThatThrownBy(() -> pedir(beto, ModoIA.IMAGEN, imagenId, null, null))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode().value()).isEqualTo(404);
                    assertThat(e.getReason()).isEqualTo("Esa imagen no existe en este diagrama");
                });
        assertThat(filas).isEmpty();
    }

    @Test
    void sin_el_procesador_conectado_un_turno_imagen_da_503_y_no_deja_turno() {
        AiController sinProcesador = new AiController(servicio, datos.acceso, "ollama", "qwen3:8b", 4096,
                mock(CatalogoModelosOllama.class), mock(HistorialIA.class), new AvisosDeIA(broker),
                mock(ServicioDeRecomendaciones.class));

        assertThatThrownBy(() -> sinProcesador.proponer(diagramaId, new AiController.SolicitudDePropuesta("x",
                Origin.TEXT, ModoIA.IMAGEN, null, imagenId, null, null), como(beto)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(503));
    }

    // ----------------------------------------------------- 25 · reprocesar

    @Test
    void reprocesar_crea_otro_turno_sobre_la_misma_imagen_y_no_otra_fila_en_la_galeria() {
        leer(beto, "primera", null, null);
        leer(beto, "reproceso", 90, new Recorte(0, 0, 120, 60));

        assertThat(filas.values()).extracting(InteraccionIA::getImageId).containsExactly(imagenId, imagenId);
        assertThat(memoria.filas).hasSize(1);
        assertThat(controlador.historial(diagramaId, como(ana))).extracting(e -> e.imagen().id())
                .containsExactly(imagenId, imagenId);
    }

    // -------------------------------- 24 y 26 · aplicar y verificarLote con IMAGE

    @Test
    void aplicar_un_turno_imagen_con_un_lote_origin_image_lo_marca_como_aplicado() {
        PropuestaDeIA propuesta = leer(beto, null, null, null);
        UUID opId = lote(beto, Origin.IMAGE, propuesta.interaccionId(), diagramaId, CommandType.BATCH);

        HistorialIA.Entrada e = controlador.marcarAplicada(diagramaId, propuesta.interaccionId(),
                new AiController.MarcaDeAplicada(opId), como(beto));

        assertThat(e.aplicada()).isTrue();
        assertThat(e.imagen().id()).isEqualTo(imagenId);
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(broker, times(2)).convertAndSend(eq("/topic/diagram/" + diagramaId + "/ai"), captor.capture());
        assertThat(captor.getAllValues()).extracting(o -> ((AvisosDeIA.Aviso) o).tipo())
                .containsExactly(AvisosDeIA.Tipo.NUEVA, AvisosDeIA.Tipo.APLICADA);
    }

    @Test
    void verificar_lote_image_rechaza_opid_inexistente_otro_diagrama_otro_usuario_otra_interaccion_y_no_batch() {
        PropuestaDeIA propuesta = leer(beto, null, null, null);
        PropuestaDeIA otra = leer(beto, null, null, null);
        UUID turno = propuesta.interaccionId();

        // opId que nunca llego al log.
        assertRechaza(beto, turno, UUID.randomUUID(), "El lote no figura como aceptado en este diagrama");
        // Una operacion de otro diagrama.
        assertRechaza(beto, turno, lote(beto, Origin.IMAGE, turno, UUID.randomUUID(), CommandType.BATCH),
                "El lote no figura como aceptado en este diagrama");
        // Otro usuario.
        assertRechaza(beto, turno, lote(ana, Origin.IMAGE, turno, diagramaId, CommandType.BATCH),
                "El lote lo envio otro usuario");
        // Otra interaccion en el payload.
        assertRechaza(beto, turno, lote(beto, Origin.IMAGE, otra.interaccionId(), diagramaId, CommandType.BATCH),
                "El lote no aplica esta propuesta");
        // No es un BATCH.
        assertRechaza(beto, turno, lote(beto, Origin.IMAGE, turno, diagramaId, CommandType.CREATE_CLASS),
                "La operacion indicada no es un lote de la IA");
        // El MOUSE y el XMI siguen sin valer.
        assertRechaza(beto, turno, lote(beto, Origin.MOUSE, turno, diagramaId, CommandType.BATCH),
                "La operacion indicada no es un lote de la IA");
        assertRechaza(beto, turno, lote(beto, Origin.XMI_IMPORT, turno, diagramaId, CommandType.BATCH),
                "La operacion indicada no es un lote de la IA");
        assertThat(filas.get(turno).getAppliedAt()).isNull();
    }

    @Test
    void un_lote_image_no_marca_un_turno_que_no_es_imagen_y_uno_text_no_marca_un_turno_imagen() {
        when(servicio.generar(any(), any(), any(), any(), any(), any())).thenReturn(new PropuestaDeIA(diagramaId,
                Origin.TEXT, List.of(), List.of(), List.of(), List.of(), 0, "Listo", "qwen3:8b", null));
        PropuestaDeIA deTexto = controlador.proponer(diagramaId, new AiController.SolicitudDePropuesta("Agrega Pago",
                Origin.TEXT, ModoIA.MODIFICAR, null), como(beto));
        PropuestaDeIA deImagen = leer(beto, null, null, null);

        assertRechaza(beto, deTexto.interaccionId(),
                lote(beto, Origin.IMAGE, deTexto.interaccionId(), diagramaId, CommandType.BATCH),
                "La operacion indicada no corresponde a esta propuesta");
        assertRechaza(beto, deImagen.interaccionId(),
                lote(beto, Origin.TEXT, deImagen.interaccionId(), diagramaId, CommandType.BATCH),
                "La operacion indicada no corresponde a esta propuesta");
        assertRechaza(beto, deImagen.interaccionId(),
                lote(beto, Origin.VOICE, deImagen.interaccionId(), diagramaId, CommandType.BATCH),
                "La operacion indicada no corresponde a esta propuesta");
        // Y los emparejamientos correctos siguen valiendo.
        UUID ok = lote(beto, Origin.TEXT, deTexto.interaccionId(), diagramaId, CommandType.BATCH);
        assertThat(controlador.marcarAplicada(diagramaId, deTexto.interaccionId(),
                new AiController.MarcaDeAplicada(ok), como(beto)).aplicada()).isTrue();
    }

    // ----------------------------------------------------------------- apoyo

    private void assertRechaza(AppUser quien, UUID turno, UUID opId, String mensaje) {
        assertThatThrownBy(() -> controlador.marcarAplicada(diagramaId, turno, new AiController.MarcaDeAplicada(opId),
                como(quien)))
                .isInstanceOfAny(LoteNoConfirmadoException.class, IllegalArgumentException.class)
                .hasMessageStartingWith(mensaje);
    }

    private PropuestaDeIA leer(AppUser quien, String prompt, Integer giro, Recorte recorte) {
        return controlador.proponer(diagramaId, new AiController.SolicitudDePropuesta(prompt, Origin.TEXT,
                ModoIA.IMAGEN, null, imagenId, giro, recorte), como(quien));
    }

    private PropuestaDeIA pedir(AppUser quien, ModoIA modo, UUID imagen, Integer giro, Recorte recorte) {
        return controlador.proponer(diagramaId, new AiController.SolicitudDePropuesta("pedido", Origin.TEXT, modo,
                null, imagen, giro, recorte), como(quien));
    }

    /** Lo que deja el CommandApplier en el log cuando el bus acepta una operacion. */
    private UUID lote(AppUser quien, Origin origen, UUID turno, UUID diagrama, CommandType tipo) {
        var payload = J.objectNode();
        payload.put("descripcion", "Propuesta IA");
        payload.putArray("comandos");
        payload.put(HistorialIA.CLAVE_INTERACCION, turno.toString());
        DiagramOperation op = new DiagramOperation();
        op.setOpId(UUID.randomUUID());
        op.setDiagramId(diagrama);
        op.setSeq(log.size() + 1);
        op.setUserId(quien.getId());
        op.setOpType(tipo);
        op.setOrigin(origen);
        op.setPayload(payload.toString());
        log.put(op.getOpId(), op);
        return op.getOpId();
    }

    private static Principal como(AppUser u) {
        return () -> u.getId().toString();
    }
}
