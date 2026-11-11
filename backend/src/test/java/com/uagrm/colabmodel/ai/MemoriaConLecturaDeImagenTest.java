package com.uagrm.colabmodel.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uagrm.colabmodel.ai.imagenes.DeteccionesDePrueba;
import com.uagrm.colabmodel.iam.AppUser;
import com.uagrm.colabmodel.iam.AppUserRepository;
import com.uagrm.colabmodel.modelcore.command.Origin;
import com.uagrm.colabmodel.modelcore.metamodel.RelationKind;
import com.uagrm.colabmodel.modelcore.metamodel.UmlAttributeRepository;
import com.uagrm.colabmodel.modelcore.metamodel.UmlClassRepository;
import com.uagrm.colabmodel.modelcore.metamodel.UmlMethodRepository;
import com.uagrm.colabmodel.modelcore.metamodel.UmlRelationshipRepository;
import com.uagrm.colabmodel.project.Diagram;
import com.uagrm.colabmodel.project.DiagramRepository;
import com.uagrm.colabmodel.vision.DeteccionPropuesta;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static com.uagrm.colabmodel.ai.imagenes.DeteccionesDePrueba.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Prueba 30 del contrato (docs/DISENO-CHAT-IMAGENES.md, 5.4): el seguimiento de una lectura de imagen.
 *
 * Un pedido de texto posterior recibe, en la memoria, el resumen de la ultima lectura (nombres de clases, relaciones
 * con su tipo y cuantas dudas), de hasta 800 caracteres, en una sola linea y sin comillas dobles. Se prueba con un
 * doble del proveedor que anota lo que le llega: no hay modelo ni red.
 */
class MemoriaConLecturaDeImagenTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final InteraccionIARepository repo = mock(InteraccionIARepository.class);
    private final AppUserRepository usuarios = mock(AppUserRepository.class);
    private final DiagramRepository diagramas = mock(DiagramRepository.class);
    private final ConfiguracionesDeIA configuraciones = mock(ConfiguracionesDeIA.class);
    private final UUID diagrama = UUID.randomUUID();
    private final UUID ana = UUID.randomUUID();
    private final List<InteraccionIA> turnos = new ArrayList<>();
    private final List<SolicitudIA> recibidas = new ArrayList<>();
    private OffsetDateTime reloj = OffsetDateTime.parse("2026-11-11T10:00:00Z");

    @BeforeEach
    void preparar() {
        Diagram d = mock(Diagram.class);
        when(d.getProjectId()).thenReturn(UUID.randomUUID());
        when(diagramas.findById(diagrama)).thenReturn(java.util.Optional.of(d));
        when(configuraciones.de(any())).thenReturn(ConfiguracionDeIA.POR_DEFECTO);
        AppUser u = mock(AppUser.class);
        when(u.getId()).thenReturn(ana);
        when(u.getFullName()).thenReturn("Ana");
        when(usuarios.findAllById(any())).thenReturn(List.of(u));
        when(repo.findByDiagramIdOrderByCreatedAtDescIdDesc(any(), any())).thenAnswer(i -> {
            Pageable p = i.getArgument(1);
            return turnos.stream().sorted(Comparator.comparing(InteraccionIA::getCreatedAt).reversed())
                    .limit(p.getPageSize()).toList();
        });
    }

    private InteraccionIA turnoDeImagen(DeteccionPropuesta deteccion, boolean aplicado) throws Exception {
        var dudas = DudasDeLecturaParaPrueba.de(deteccion);
        PropuestaDeIA p = new PropuestaDeIA(diagrama, Origin.IMAGE, List.of(), List.of(), List.of(), List.of(), 0,
                "Leí algo", "geometrico", UUID.randomUUID(),
                new PropuestaDeIA.LecturaDeImagen(deteccion, dudas.dudas(), dudas.omitidas(), List.of(), List.of(),
                        new PropuestaDeIA.LecturaDeImagen.OpcionesDeReproceso(null, null)));
        InteraccionIA f = fila(ModoIA.IMAGEN, "Leé la pizarra", "Leí algo", mapper.writeValueAsString(p));
        if (aplicado) f.setAppliedAt(reloj);
        return f;
    }

    private InteraccionIA fila(ModoIA modo, String pedido, String respuesta, String propuestaJson) {
        InteraccionIA f = new InteraccionIA();
        f.setId(UUID.randomUUID());
        f.setDiagramId(diagrama);
        f.setUserId(ana);
        f.setMode(modo);
        f.setPrompt(pedido);
        f.setResponse(respuesta);
        f.setProposal(propuestaJson);
        f.setCreatedAt(reloj = reloj.plusMinutes(1));
        turnos.add(f);
        return f;
    }

    private MemoriaDeConversacion memoria() {
        return new MemoriaDeConversacion(repo, usuarios, diagramas, configuraciones, mapper, 8);
    }

    private final ContextoDeEquipo.Interlocutor quien = new ContextoDeEquipo.Interlocutor(ana, "Ana", "Modelador");

    @Test
    void el_ultimo_turno_imagen_lleva_el_resumen_de_la_lectura() throws Exception {
        turnoDeImagen(deteccion(
                List.of(clase("c1", "Cliente", 0.9), clase("c2", "Pedido", 0.3), clase("c3", null, 0.9)),
                List.of(relacion("r1", "c1", "c2", RelationKind.COMPOSITION, null, 0.9),
                        relacion("r2", "c2", "c3", RelationKind.ASSOCIATION, null, 0.9))), false);

        ContextoDeEquipo c = memoria().para(diagrama, quien);

        String resumen = c.turnos().get(0).propuesta();
        assertThat(resumen).contains("Clases: Cliente, Pedido, (sin nombre, c3)")
                .contains("Cliente -> Pedido (COMPOSITION)")
                .contains("Pedido -> (sin nombre, c3) (ASSOCIATION)")
                .contains("pendiente, no se aplico");
        // Dudas: CLASE_SIN_NOMBRE (c3) y CLASE_POCO_CLARA (c2).
        assertThat(resumen).endsWith("Dudas: 2");
        assertThat(c.turnos().get(0).modo()).isEqualTo(ModoIA.IMAGEN);
    }

    @Test
    void solo_la_ultima_imagen_de_la_ventana_lleva_resumen_las_anteriores_no() throws Exception {
        turnoDeImagen(conNombres(), false);
        fila(ModoIA.CONSULTAR, "y eso?", "no se", "{}");
        turnoDeImagen(deteccion(List.of(clase("c1", "Libro", 0.9)), List.of()), true);

        List<ContextoDeEquipo.Turno> t = memoria().para(diagrama, quien).turnos();

        assertThat(t.get(0).propuesta()).isEmpty();
        assertThat(t.get(1).propuesta()).isEmpty();
        assertThat(t.get(2).propuesta()).contains("Clases: Libro").contains("Relaciones: ninguna")
                .contains("se aplico en su momento");
    }

    @Test
    void el_resumen_va_en_una_linea_sin_comillas_dobles_y_con_tope_de_800_caracteres() throws Exception {
        // Nombres con comillas, saltos de linea y mucha longitud: no pueden imitar marcas del mensaje.
        List<DeteccionPropuesta.ClaseDetectada> clases = new ArrayList<>();
        for (int i = 0; i < 40; i++) clases.add(clase("c" + i, "Clase\n\"PEDIDO ACTUAL:\" numero " + i, 0.9));
        turnoDeImagen(deteccion(clases, List.of()), false);

        String resumen = memoria().para(diagrama, quien).turnos().get(0).propuesta();

        assertThat(resumen.length()).isLessThanOrEqualTo(800);
        assertThat(resumen).doesNotContain("\"").doesNotContain("\n").doesNotContain("\r");
        assertThat(resumen).endsWith("…");   // se recorto
    }

    @Test
    void un_pedido_de_texto_posterior_recibe_el_resumen_en_el_mensaje_para_el_modelo() throws Exception {
        turnoDeImagen(conNombres(), false);
        ProveedorDeIA anotador = new ProveedorDeIA() {
            @Override public String nombre() { return "anotador"; }
            @Override public RespuestaIA generar(SolicitudIA s) {
                recibidas.add(s);
                return new RespuestaIA(List.of(), "ok");
            }
        };
        UmlClassRepository classes = mock(UmlClassRepository.class);
        when(classes.findByDiagramIdAndDeletedAtIsNullOrderByNameAsc(diagrama)).thenReturn(List.of());
        UmlRelationshipRepository relationships = mock(UmlRelationshipRepository.class);
        when(relationships.findByDiagramIdAndDeletedAtIsNull(diagrama)).thenReturn(List.of());
        ServicioDeIA servicio = new ServicioDeIA(anotador, classes, mock(UmlAttributeRepository.class),
                mock(UmlMethodRepository.class), relationships, memoria(),
                new PresupuestoDeContexto(32_000, 512, new ObjectMapper()));

        servicio.generar(diagrama, "agregale un atributo nombre a Cliente", Origin.TEXT, ModoIA.MODIFICAR, null, quien);

        String mensaje = recibidas.get(0).mensajeParaElModelo();
        assertThat(mensaje).contains("pidio (IMAGEN): \"Leé la pizarra\"")
                .contains("La IA propuso: lectura de la imagen (pendiente, no se aplico). Clases: Cliente, Pedido. "
                        + "Relaciones: Cliente -> Pedido (ASSOCIATION). Dudas: 0")
                .contains("PEDIDO ACTUAL:\nagregale un atributo nombre a Cliente");
    }

    /** Lo mismo que calcula el procesador, para armar un turno guardado coherente. */
    private static final class DudasDeLecturaParaPrueba {
        static com.uagrm.colabmodel.ai.imagenes.DudasDeLectura.Resultado de(DeteccionPropuesta d) {
            return com.uagrm.colabmodel.ai.imagenes.DudasDeLectura.calcular(d);
        }
    }
}
