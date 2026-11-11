package com.uagrm.colabmodel.ai;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.uagrm.colabmodel.ai.imagenes.ImagenDelChat;
import com.uagrm.colabmodel.ai.imagenes.ImagenMeta;
import com.uagrm.colabmodel.ai.imagenes.ImagenesDelChat;
import com.uagrm.colabmodel.ai.imagenes.ImagenesEnMemoria;
import com.uagrm.colabmodel.iam.AppUser;
import com.uagrm.colabmodel.modelcore.command.Origin;
import com.uagrm.colabmodel.project.AccesoAProyecto.Rol;
import com.uagrm.colabmodel.project.DiagramOperationRepository;
import com.uagrm.colabmodel.project.Project;
import com.uagrm.colabmodel.project.ProyectosEnMemoria;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Fase 1 del chat con imagenes: `HistorialIA.Entrada.imagen` (docs/DISENO-CHAT-IMAGENES.md, 5.1).
 *
 * Una entrada sin imagen no la trae; con imagen viva trae sus metadatos; eliminada, `eliminada = true` y sin
 * bytes. Listar el historial resuelve las imagenes de TODOS los turnos con UNA consulta de metadatos (sin N+1)
 * y nunca lee bytes.
 */
class HistorialConImagenesTest {

    private ProyectosEnMemoria datos;
    private ImagenesEnMemoria memoria;
    private ImagenesDelChat imagenes;
    private AppUser ana;
    private UUID diagramaId;
    private final List<InteraccionIA> filas = new ArrayList<>();
    private HistorialIA historial;

    @BeforeEach
    void preparar() {
        datos = new ProyectosEnMemoria();
        ana = datos.usuario("Ana");
        Project p = datos.proyecto(ana, "Biblioteca");
        diagramaId = datos.diagramas.values().iterator().next().getId();
        memoria = new ImagenesEnMemoria(datos);
        imagenes = spy(memoria.servicio(100, 209_715_200L));

        InteraccionIARepository interacciones = mock(InteraccionIARepository.class);
        when(interacciones.findByDiagramIdOrderByCreatedAtAscIdAsc(any())).thenAnswer(i -> List.copyOf(filas));
        when(interacciones.findById(any())).thenAnswer(i -> filas.stream()
                .filter(f -> f.getId().equals(i.getArgument(0))).findFirst());
        when(interacciones.save(any(InteraccionIA.class))).thenAnswer(i -> i.getArgument(0));
        historial = new HistorialIA(interacciones, datos.users, mock(DiagramOperationRepository.class),
                new ObjectMapper(), imagenes);
    }

    private InteraccionIA turno(UUID imagenId, int n) {
        InteraccionIA f = new InteraccionIA();
        f.setId(UUID.randomUUID());
        f.setDiagramId(diagramaId);
        f.setUserId(ana.getId());
        // ModoIA.IMAGEN es de la Fase 2: aqui basta que el turno tenga image_id.
        f.setMode(ModoIA.MODIFICAR);
        f.setPrompt("turno " + n);
        f.setProposal("{}");
        f.setCreatedAt(OffsetDateTime.parse("2026-11-11T10:00:00-04:00").plusSeconds(n));
        f.setImageId(imagenId);
        filas.add(f);
        return f;
    }

    private UUID imagen(Color color) {
        var r = imagenes.subir(diagramaId, ana.getId(), ImagenesEnMemoria.png(64, color), "image/png");
        return r.imagen().id();
    }

    @Test
    void un_turno_sin_imagen_no_trae_el_campo() throws Exception {
        turno(null, 1);

        HistorialIA.Entrada e = historial.listar(diagramaId).get(0);

        assertThat(e.imagen()).isNull();
        // Con `non_null` ni siquiera sale en el JSON: una entrada de antes de la funcion se ve como siempre.
        String json = new ObjectMapper().registerModule(new JavaTimeModule())
                .setSerializationInclusion(JsonInclude.Include.NON_NULL).writeValueAsString(e);
        assertThat(json).doesNotContain("\"imagen\"");
    }

    @Test
    void el_constructor_anterior_de_entrada_deja_la_imagen_en_null() {
        var e = new HistorialIA.Entrada(UUID.randomUUID(), null, "x", ModoIA.MODIFICAR, null, "p", null, null, false,
                OffsetDateTime.now());

        assertThat(e.imagen()).isNull();
    }

    @Test
    void un_turno_con_imagen_viva_trae_sus_metadatos_y_nada_de_bytes() throws Exception {
        UUID id = imagen(Color.WHITE);
        turno(id, 1);

        HistorialIA.Entrada e = historial.listar(diagramaId).get(0);

        assertThat(e.imagen().id()).isEqualTo(id);
        assertThat(e.imagen().subidaPorNombre()).isEqualTo("Ana");
        assertThat(e.imagen().tipo()).isEqualTo("image/png");
        assertThat(e.imagen().eliminada()).isFalse();
        String json = new ObjectMapper().registerModule(new JavaTimeModule())
                .setSerializationInclusion(JsonInclude.Include.NON_NULL).writeValueAsString(e);
        assertThat(json).contains("\"imagen\":{").doesNotContain("datos").doesNotContain("thumbnail");
        assertThat(memoria.almacen.lecturasDeBytes).isZero();
    }

    @Test
    void un_turno_cuya_imagen_fue_eliminada_la_muestra_eliminada_y_conserva_el_turno() {
        UUID id = imagen(Color.WHITE);
        turno(id, 1);
        imagenes.eliminar(diagramaId, id, ana.getId(), subidaPor -> true);

        HistorialIA.Entrada e = historial.listar(diagramaId).get(0);

        assertThat(e.imagen().eliminada()).isTrue();
        assertThat(e.imagen().eliminadaEn()).isNotNull();
        assertThat(e.imagen().id()).isEqualTo(id);
        assertThat(e.prompt()).isEqualTo("turno 1");          // el turno no pierde su historia
        assertThat(memoria.almacen.lecturasDeBytes).isZero();
    }

    @Test
    void listar_50_turnos_con_imagen_hace_una_sola_consulta_de_metadatos_y_no_lee_bytes() {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 10; i++) ids.add(imagen(new Color(10 + i * 20, 40, 90)));
        for (int n = 0; n < 50; n++) turno(ids.get(n % ids.size()), n);       // 50 turnos, 10 imagenes
        turno(null, 99);                                                      // y uno sin imagen
        org.mockito.Mockito.clearInvocations(memoria.repo, imagenes);

        List<HistorialIA.Entrada> entradas = historial.listar(diagramaId);

        assertThat(entradas).hasSize(51);
        assertThat(entradas.subList(0, 50)).allSatisfy(e -> assertThat(e.imagen()).isNotNull());
        assertThat(entradas.get(50).imagen()).isNull();
        verify(memoria.repo, times(1)).findAllById(anyIterable());
        verify(imagenes, times(1)).metas(any());
        verify(imagenes, never()).metaDe(any());
        assertThat(memoria.almacen.lecturasDeBytes).isZero();
    }

    @Test
    void marcar_aplicada_devuelve_la_entrada_con_su_imagen() {
        UUID id = imagen(Color.WHITE);
        InteraccionIA f = turno(id, 1);
        f.setAppliedAt(OffsetDateTime.now());   // ya aplicada: marcarAplicada solo reconstruye la entrada

        HistorialIA.Entrada e = historial.marcarAplicada(diagramaId, f.getId(), ana.getId(), null);

        assertThat(e.imagen()).extracting(ImagenMeta::id).isEqualTo(id);
        assertThat(e.aplicada()).isTrue();
    }
}
