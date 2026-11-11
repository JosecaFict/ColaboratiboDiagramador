package com.uagrm.colabmodel.ai;

import com.uagrm.colabmodel.PostgresDePruebas;
import com.uagrm.colabmodel.ai.AiController.SolicitudDePropuesta.Recorte;
import com.uagrm.colabmodel.ai.imagenes.DeteccionesDePrueba;
import com.uagrm.colabmodel.ai.imagenes.ImagenesDelChat;
import com.uagrm.colabmodel.modelcore.command.Origin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.security.Principal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * La Fase 2 contra PostgreSQL de verdad (docs/DISENO-CHAT-IMAGENES.md, 5.3): el turno IMAGEN se guarda con su
 * `image_id`, el CHECK de `mode` acepta IMAGEN, la lectura viaja por el `jsonb` y vuelve entera, y reprocesar no
 * crea otra fila en `ai_images`.
 *
 * El contexto es el real (detector geometrico y plantillas, que son deterministas). El proveedor del nivel 2 se
 * apaga (`proveedor=ninguno`: no se crea ni EnriquecedorPorModelo ni los de Claude/Gemini), asi que no se toca
 * ningun modelo ni Ollama. Se OMITE si no hay servidor (ver PostgresDePruebas).
 */
@SpringBootTest(properties = "colabmodel.vision.nivel2.proveedor=ninguno")
class TurnoImagenPostgresTest extends PostgresDePruebas {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ImagenesDelChat imagenes;
    @Autowired
    AiController controlador;

    private final String marca = UUID.randomUUID().toString().substring(0, 8);
    private UUID usuario;
    private UUID proyecto;
    private UUID diagrama;
    private UUID imagenId;

    @BeforeEach
    void unDiagramaConUnaImagen() {
        usuario = jdbc.queryForObject("insert into users(email, password_hash, full_name) values (?, 'x', 'Ana') "
                + "returning id", UUID.class, "turno-" + marca + "@prueba.test");
        proyecto = jdbc.queryForObject("insert into projects(name, owner_id) values (?, ?) returning id",
                UUID.class, "Turno imagen " + marca, usuario);
        diagrama = jdbc.queryForObject("insert into diagrams(project_id, name) values (?, 'D') returning id",
                UUID.class, proyecto);
        imagenId = imagenes.subir(diagrama, usuario, unDiagrama(), "image/png").imagen().id();
    }

    @AfterEach
    void limpiar() {
        jdbc.update("delete from projects where owner_id = ?", usuario);
        jdbc.update("delete from users where id = ?", usuario);
    }

    /** Dos cajas de clase con su division interna y una linea que las une, sobre fondo blanco. */
    private static byte[] unDiagrama() {
        BufferedImage img = new BufferedImage(640, 320, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 640, 320);
        g.setColor(Color.BLACK);
        g.setStroke(new BasicStroke(3));
        g.drawRect(40, 60, 180, 120);
        g.drawLine(40, 100, 220, 100);
        g.drawLine(40, 140, 220, 140);
        g.drawRect(420, 60, 180, 120);
        g.drawLine(420, 100, 600, 100);
        g.drawLine(420, 140, 600, 140);
        g.drawLine(220, 120, 420, 120);
        g.dispose();
        return DeteccionesDePrueba.aPng(img);
    }

    private Principal como() {
        return () -> usuario.toString();
    }

    private PropuestaDeIA leer(String prompt, Integer giro, Recorte recorte) {
        return controlador.proponer(diagrama, new AiController.SolicitudDePropuesta(prompt, Origin.TEXT,
                ModoIA.IMAGEN, null, imagenId, giro, recorte), como());
    }

    @Test
    void el_turno_imagen_se_guarda_con_su_image_id_y_vuelve_entero_del_jsonb() {
        PropuestaDeIA propuesta = leer("Leé la pizarra", null, null);

        assertThat(propuesta.origin()).isEqualTo(Origin.IMAGE);
        assertThat(propuesta.interaccionId()).isNotNull();
        assertThat(jdbc.queryForObject("select mode from ai_interactions where id = ?", String.class,
                propuesta.interaccionId())).isEqualTo("IMAGEN");
        assertThat(jdbc.queryForObject("select image_id from ai_interactions where id = ?", UUID.class,
                propuesta.interaccionId())).isEqualTo(imagenId);
        assertThat(jdbc.queryForObject("select proposal->>'origin' from ai_interactions where id = ?", String.class,
                propuesta.interaccionId())).isEqualTo("IMAGE");

        HistorialIA.Entrada e = controlador.historial(diagrama, como()).get(0);
        assertThat(e.modo()).isEqualTo(ModoIA.IMAGEN);
        assertThat(e.imagen().id()).isEqualTo(imagenId);
        assertThat(e.imagen().subidaPorNombre()).isEqualTo("Ana");
        assertThat(e.imagen().eliminada()).isFalse();
        PropuestaDeIA.LecturaDeImagen lectura = e.propuesta().lecturaDeImagen();
        assertThat(lectura).isNotNull();
        assertThat(lectura.deteccion().metricas().anchoOriginal()).isEqualTo(640);
        assertThat(lectura.dudas()).isNotEmpty();   // sin nivel 2 de texto: al menos SIN_LECTURA_DE_TEXTO
        assertThat(lectura.dudas().get(0).tipo())
                .isEqualTo(PropuestaDeIA.LecturaDeImagen.TipoDeDuda.SIN_LECTURA_DE_TEXTO);
        assertThat(lectura.opciones()).isEqualTo(new PropuestaDeIA.LecturaDeImagen.OpcionesDeReproceso(null, null));
        assertThat(e.propuesta().aceptados()).isEmpty();
        assertThat(e.respuesta()).startsWith("Leí ");
    }

    @Test
    void reprocesar_deja_dos_turnos_sobre_la_misma_imagen_y_una_sola_fila_en_ai_images() {
        leer("primera", null, null);
        PropuestaDeIA segunda = leer("girada y recortada", 90, new Recorte(20, 40, 300, 200));

        assertThat(jdbc.queryForObject("select count(*) from ai_images where diagram_id = ?", Integer.class,
                diagrama)).isEqualTo(1);
        assertThat(jdbc.queryForList("select image_id from ai_interactions where diagram_id = ?", UUID.class,
                diagrama)).containsExactly(imagenId, imagenId);
        // El recorte 300x200 girado 90 grados da 200x300: es lo que vio el detector.
        assertThat(segunda.lecturaDeImagen().deteccion().metricas().anchoOriginal()).isEqualTo(200);
        assertThat(segunda.lecturaDeImagen().deteccion().metricas().altoOriginal()).isEqualTo(300);
        assertThat(segunda.lecturaDeImagen().opciones())
                .isEqualTo(new PropuestaDeIA.LecturaDeImagen.OpcionesDeReproceso(90, new Recorte(20, 40, 300, 200)));
    }

    @Test
    void eliminar_la_imagen_deja_el_turno_con_imagen_eliminada_y_ya_no_se_puede_reprocesar() {
        leer("antes de borrar", null, null);

        imagenes.eliminar(diagrama, imagenId, usuario, quien -> true);

        HistorialIA.Entrada e = controlador.historial(diagrama, como()).get(0);
        assertThat(e.imagen().eliminada()).isTrue();
        assertThat(e.propuesta().lecturaDeImagen().deteccion()).isNotNull();   // lo ya leido se conserva
        assertThatThrownBy(() -> leer("despues", null, null)).isInstanceOfSatisfying(ResponseStatusException.class,
                ex -> assertThat(ex.getStatusCode().value()).isEqualTo(404));
        assertThat(jdbc.queryForObject("select count(*) from ai_interactions where diagram_id = ?", Integer.class,
                diagrama)).isEqualTo(1);
    }

    @Test
    void un_recorte_fuera_de_la_imagen_da_400_y_no_deja_turno() {
        assertThatThrownBy(() -> leer("x", null, new Recorte(600, 0, 100, 100)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("El recorte tiene que estar dentro de la imagen (640 × 320)");
        assertThat(jdbc.queryForObject("select count(*) from ai_interactions where diagram_id = ?", Integer.class,
                diagrama)).isZero();
    }

    @Test
    void borrar_el_diagrama_arrastra_los_turnos_y_las_imagenes() {
        leer("x", null, null);

        jdbc.update("delete from diagrams where id = ?", diagrama);

        assertThat(jdbc.queryForObject("select count(*) from ai_interactions where diagram_id = ?", Integer.class,
                diagrama)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from ai_images where diagram_id = ?", Integer.class,
                diagrama)).isZero();
    }

    @Test
    void los_modos_de_texto_siguen_guardandose_sin_image_id() {
        // El CHECK nuevo de V14 sigue aceptando los modos de antes.
        jdbc.update("insert into ai_interactions(diagram_id, user_id, mode, prompt, proposal) "
                + "values (?, ?, 'MODIFICAR', 'p', '{}'::jsonb)", diagrama, usuario);

        assertThat(jdbc.queryForList("select mode from ai_interactions where diagram_id = ?", String.class, diagrama))
                .containsExactly("MODIFICAR");
        assertThat(List.of(ModoIA.values())).contains(ModoIA.IMAGEN);
    }
}
