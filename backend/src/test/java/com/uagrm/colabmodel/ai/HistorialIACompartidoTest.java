package com.uagrm.colabmodel.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.uagrm.colabmodel.iam.AppUser;
import com.uagrm.colabmodel.modelcore.command.Command;
import com.uagrm.colabmodel.modelcore.command.CommandType;
import com.uagrm.colabmodel.modelcore.command.Origin;
import com.uagrm.colabmodel.modelcore.metamodel.UmlAttributeRepository;
import com.uagrm.colabmodel.modelcore.metamodel.UmlClassRepository;
import com.uagrm.colabmodel.modelcore.metamodel.UmlMethodRepository;
import com.uagrm.colabmodel.modelcore.metamodel.UmlRelationshipRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * T-04: el historial de la IA es una conversación compartida del diagrama.
 *
 * 1. Cada turno nuevo y cada marca de "aplicada" se difunden por
 *    `/topic/diagram/{id}/ai`: las demás pestañas lo ven sin recargar.
 * 2. "Aplicada" se pone solo si el log prueba que el bus aceptó el BATCH que
 *    aplica ese turno. Un lote rechazado no deja fila en el log.
 * 3. Un VIEWER lee el historial, pero no pide cambios ni marca nada.
 *
 * Se prueba el controlador con la regla de acceso real (AccesoAProyecto sobre
 * proyectos en memoria) y repositorios simulados en memoria.
 */
class HistorialIACompartidoTest {

    private static final JsonNodeFactory J = JsonNodeFactory.instance;

    private ProyectosEnMemoria datos;
    private AppUser ana;      // propietaria
    private AppUser beto;     // EDITOR
    private AppUser carla;    // VIEWER
    private AppUser extrano;  // no es miembro
    private UUID diagramaId;

    private final Map<UUID, InteraccionIA> filas = new LinkedHashMap<>();
    private final Map<UUID, DiagramOperation> log = new HashMap<>();
    private final SimpMessagingTemplate broker = mock(SimpMessagingTemplate.class);
    private final ServicioDeIA servicio = mock(ServicioDeIA.class);
    private AiController controlador;

    @BeforeEach
    void prepararUnDiagramaConTresRoles() {
        datos = new ProyectosEnMemoria();
        ana = datos.usuario("Ana");
        Project p = datos.proyecto(ana, "Biblioteca");
        diagramaId = datos.diagramas.values().iterator().next().getId();
        beto = datos.miembro(p, "Beto", Rol.EDITOR);
        carla = datos.miembro(p, "Carla", Rol.VIEWER);
        extrano = datos.usuario("Extrano");

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

        when(servicio.generar(any(), any(), any(), any(), any(), any())).thenAnswer(i -> propuesta(i.getArgument(0)));

        HistorialIA historial = new HistorialIA(interacciones, datos.users, operaciones, new ObjectMapper());
        controlador = new AiController(servicio, datos.acceso, "ollama", "qwen3:8b", 4096,
                mock(CatalogoModelosOllama.class), historial, new AvisosDeIA(broker),
                mock(ServicioDeRecomendaciones.class));
    }

    // ------------------------------------------------------------ 1. sincronía

    @Test
    void un_pedido_nuevo_se_difunde_a_las_demas_pestanas_con_su_autor() {
        PropuestaDeIA propuesta = proponer(beto, ModoIA.MODIFICAR);

        List<AvisosDeIA.Aviso> avisos = avisosDifundidos();
        assertThat(avisos).hasSize(1);
        AvisosDeIA.Aviso aviso = avisos.get(0);
        assertThat(aviso.tipo()).isEqualTo(AvisosDeIA.Tipo.NUEVA);
        assertThat(aviso.entrada().id()).isEqualTo(propuesta.interaccionId());
        assertThat(aviso.entrada().userName()).isEqualTo("Beto");
        assertThat(aviso.entrada().prompt()).isEqualTo("Agrega una clase Pago");
        assertThat(aviso.entrada().aplicada()).isFalse();
        // La entrada difundida es la misma que despues devuelve el historial.
        assertThat(controlador.historial(diagramaId, como(ana))).containsExactly(aviso.entrada());
    }

    @Test
    void la_marca_de_aplicada_tambien_se_difunde() {
        PropuestaDeIA propuesta = proponer(beto, ModoIA.MODIFICAR);
        UUID opId = loteAceptado(beto, Origin.TEXT, propuesta.interaccionId());

        controlador.marcarAplicada(diagramaId, propuesta.interaccionId(), new AiController.MarcaDeAplicada(opId), como(beto));

        List<AvisosDeIA.Aviso> avisos = avisosDifundidos();
        assertThat(avisos).extracting(AvisosDeIA.Aviso::tipo)
                .containsExactly(AvisosDeIA.Tipo.NUEVA, AvisosDeIA.Tipo.APLICADA);
        assertThat(avisos.get(1).entrada().aplicada()).isTrue();
    }

    // ------------------------------------------------- 2. aplicada = confirmada

    @Test
    void un_lote_rechazado_no_esta_en_el_log_y_la_propuesta_no_se_marca() {
        PropuestaDeIA propuesta = proponer(beto, ModoIA.MODIFICAR);
        UUID opRechazada = UUID.randomUUID();   // el bus lo rechazo: nunca llego al log

        assertThatThrownBy(() -> controlador.marcarAplicada(diagramaId, propuesta.interaccionId(),
                new AiController.MarcaDeAplicada(opRechazada), como(beto)))
                .isInstanceOf(LoteNoConfirmadoException.class);

        assertThat(controlador.historial(diagramaId, como(beto)).get(0).aplicada()).isFalse();
        assertThat(avisosDifundidos()).extracting(AvisosDeIA.Aviso::tipo).containsExactly(AvisosDeIA.Tipo.NUEVA);
    }

    @Test
    void sin_opId_no_se_marca() {
        PropuestaDeIA propuesta = proponer(beto, ModoIA.MODIFICAR);

        assertThatThrownBy(() -> controlador.marcarAplicada(diagramaId, propuesta.interaccionId(), null, como(beto)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> controlador.marcarAplicada(diagramaId, propuesta.interaccionId(),
                new AiController.MarcaDeAplicada(null), como(beto)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(filas.get(propuesta.interaccionId()).getAppliedAt()).isNull();
    }

    @Test
    void una_operacion_aceptada_que_no_aplica_esta_propuesta_no_sirve_de_prueba() {
        PropuestaDeIA propuesta = proponer(beto, ModoIA.MODIFICAR);
        UUID turno = propuesta.interaccionId();

        UUID deOtroUsuario = loteAceptado(ana, Origin.TEXT, turno);
        UUID deOtroTurno = loteAceptado(beto, Origin.TEXT, UUID.randomUUID());
        UUID sinTurno = loteAceptado(beto, Origin.TEXT, null);
        UUID deImportacion = loteAceptado(beto, Origin.XMI_IMPORT, turno);
        UUID noEsLote = operacion(beto, CommandType.CREATE_CLASS, Origin.TEXT, turno, diagramaId);
        UUID deOtroDiagrama = operacion(beto, CommandType.BATCH, Origin.TEXT, turno, UUID.randomUUID());

        for (UUID opId : List.of(deOtroUsuario, deOtroTurno, sinTurno, deImportacion, noEsLote, deOtroDiagrama)) {
            assertThatThrownBy(() -> controlador.marcarAplicada(diagramaId, turno,
                    new AiController.MarcaDeAplicada(opId), como(beto)))
                    .as("opId %s", opId)
                    .isInstanceOf(LoteNoConfirmadoException.class);
        }
        assertThat(filas.get(turno).getAppliedAt()).isNull();
    }

    @Test
    void con_el_lote_aceptado_en_el_log_se_marca_por_texto_o_por_voz() {
        for (Origin origen : List.of(Origin.TEXT, Origin.VOICE)) {
            PropuestaDeIA propuesta = proponer(beto, ModoIA.MODIFICAR);
            UUID opId = loteAceptado(beto, origen, propuesta.interaccionId());

            HistorialIA.Entrada entrada = controlador.marcarAplicada(diagramaId, propuesta.interaccionId(),
                    new AiController.MarcaDeAplicada(opId), como(beto));

            assertThat(entrada.aplicada()).as("origen %s", origen).isTrue();
            assertThat(filas.get(propuesta.interaccionId()).getAppliedBy()).isEqualTo(beto.getId());
        }
    }

    @Test
    void marcar_dos_veces_no_falla_ni_cambia_quien_la_aplico() {
        PropuestaDeIA propuesta = proponer(beto, ModoIA.MODIFICAR);
        UUID opId = loteAceptado(beto, Origin.TEXT, propuesta.interaccionId());
        var marca = new AiController.MarcaDeAplicada(opId);

        controlador.marcarAplicada(diagramaId, propuesta.interaccionId(), marca, como(beto));
        OffsetDateTime primera = filas.get(propuesta.interaccionId()).getAppliedAt();
        HistorialIA.Entrada otra = controlador.marcarAplicada(diagramaId, propuesta.interaccionId(), marca, como(ana));

        assertThat(otra.aplicada()).isTrue();
        assertThat(filas.get(propuesta.interaccionId()).getAppliedAt()).isEqualTo(primera);
        assertThat(filas.get(propuesta.interaccionId()).getAppliedBy()).isEqualTo(beto.getId());
    }

    @Test
    void el_batch_armado_desde_la_propuesta_dice_que_turno_aplica() {
        ServicioDeIA real = new ServicioDeIA(new ProveedorDeIAFalso(new RespuestaIA(List.of(), "")),
                mock(UmlClassRepository.class), mock(UmlAttributeRepository.class),
                mock(UmlMethodRepository.class), mock(UmlRelationshipRepository.class));
        UUID turno = UUID.randomUUID();

        Command batch = real.comandoBatchDe(propuesta(diagramaId).conInteraccionId(turno));

        assertThat(batch.payload().path(HistorialIA.CLAVE_INTERACCION).asText()).isEqualTo(turno.toString());
    }

    // ----------------------------------------------------- 3. autorización

    @Test
    void un_viewer_lee_el_historial_compartido() {
        PropuestaDeIA propuesta = proponer(beto, ModoIA.MODIFICAR);

        List<HistorialIA.Entrada> visto = controlador.historial(diagramaId, como(carla));

        assertThat(visto).extracting(HistorialIA.Entrada::id).containsExactly(propuesta.interaccionId());
    }

    @Test
    void un_viewer_no_pide_cambios_a_la_ia() {
        for (ModoIA modo : List.of(ModoIA.DISENAR, ModoIA.MODIFICAR)) {
            assertThatThrownBy(() -> proponer(carla, modo))
                    .as("modo %s", modo)
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            e -> assertThat(e.getStatusCode().value()).isEqualTo(403));
        }
        assertThat(filas).isEmpty();
        verifyNoInteractions(broker);
    }

    @Test
    void un_viewer_no_marca_aplicada_ni_con_un_lote_aceptado_en_el_log() {
        PropuestaDeIA propuesta = proponer(beto, ModoIA.MODIFICAR);
        UUID opId = loteAceptado(beto, Origin.TEXT, propuesta.interaccionId());

        assertThatThrownBy(() -> controlador.marcarAplicada(diagramaId, propuesta.interaccionId(),
                new AiController.MarcaDeAplicada(opId), como(carla)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(403));

        assertThat(filas.get(propuesta.interaccionId()).getAppliedAt()).isNull();
        assertThat(avisosDifundidos()).extracting(AvisosDeIA.Aviso::tipo).containsExactly(AvisosDeIA.Tipo.NUEVA);
    }

    @Test
    void un_no_miembro_no_lee_el_historial() {
        proponer(beto, ModoIA.MODIFICAR);

        assertThatThrownBy(() -> controlador.historial(diagramaId, como(extrano)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(403));
    }

    // -------------------------------------------- T-09: quien pregunta

    /** La identidad que recibe la IA sale de la sesion y del proyecto, no del cuerpo del pedido. */
    @Test
    void la_ia_recibe_quien_pregunta_desde_la_sesion_con_su_rol() {
        proponer(beto, ModoIA.MODIFICAR);
        proponer(carla, ModoIA.CONSULTAR);

        ArgumentCaptor<ContextoDeEquipo.Interlocutor> quien = ArgumentCaptor.forClass(ContextoDeEquipo.Interlocutor.class);
        verify(servicio, times(2)).generar(eq(diagramaId), any(), any(), any(), any(), quien.capture());
        assertThat(quien.getAllValues()).extracting(ContextoDeEquipo.Interlocutor::userId)
                .containsExactly(beto.getId(), carla.getId());
        assertThat(quien.getAllValues()).extracting(ContextoDeEquipo.Interlocutor::nombre)
                .containsExactly("Beto", "Carla");
        assertThat(quien.getAllValues()).extracting(ContextoDeEquipo.Interlocutor::rolEnProyecto)
                .containsExactly("Modelador", "Observador");
    }

    // ------------------------------------------------------------- apoyo

    private PropuestaDeIA proponer(AppUser quien, ModoIA modo) {
        return controlador.proponer(diagramaId,
                new AiController.SolicitudDePropuesta("Agrega una clase Pago", Origin.TEXT, modo, null), como(quien));
    }

    private static PropuestaDeIA propuesta(UUID diagramId) {
        var p = J.objectNode();
        p.put("classId", UUID.randomUUID().toString());
        p.put("name", "Pago");
        return new PropuestaDeIA(diagramId, Origin.TEXT,
                List.of(new PropuestaDeIA.ComandoAceptado(CommandType.CREATE_CLASS, p, "Crear la clase Pago")),
                List.of(), List.of(), List.of(), 1, "Listo", "qwen3:8b", null);
    }

    private UUID loteAceptado(AppUser quien, Origin origen, UUID turno) {
        return operacion(quien, CommandType.BATCH, origen, turno, diagramaId);
    }

    /** Lo que deja el CommandApplier en el log cuando el bus acepta una operación. */
    private UUID operacion(AppUser quien, CommandType tipo, Origin origen, UUID turno, UUID diagrama) {
        var payload = J.objectNode();
        payload.put("descripcion", "Propuesta IA");
        payload.putArray("comandos");
        if (turno != null) payload.put(HistorialIA.CLAVE_INTERACCION, turno.toString());
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

    private List<AvisosDeIA.Aviso> avisosDifundidos() {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(broker, atLeast(0)).convertAndSend(eq("/topic/diagram/" + diagramaId + "/ai"), captor.capture());
        return captor.getAllValues().stream().map(AvisosDeIA.Aviso.class::cast).toList();
    }

    private static Principal como(AppUser u) {
        return () -> u.getId().toString();
    }
}
