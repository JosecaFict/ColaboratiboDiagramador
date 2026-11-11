package com.uagrm.colabmodel.ai.imagenes;

import com.uagrm.colabmodel.ai.AiController.SolicitudDePropuesta.Recorte;
import com.uagrm.colabmodel.ai.PropuestaDeIA;
import com.uagrm.colabmodel.ai.PropuestaDeIA.LecturaDeImagen;
import com.uagrm.colabmodel.ai.PropuestaDeIA.LecturaDeImagen.TipoDeDuda;
import com.uagrm.colabmodel.iam.AppUser;
import com.uagrm.colabmodel.modelcore.command.Origin;
import com.uagrm.colabmodel.project.ProyectosEnMemoria;
import com.uagrm.colabmodel.vision.DeteccionPropuesta;
import com.uagrm.colabmodel.vision.EnriquecedorDeDeteccion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static com.uagrm.colabmodel.ai.imagenes.DeteccionesDePrueba.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Fase 2 (pruebas 22, 23, 25, 29 del contrato y el hallazgo de Pepe): el procesador del turno IMAGEN, con dobles
 * del detector y de los enriquecedores. Ningun modelo real.
 */
class ProcesadorDeImagenDelChatTest {

    private ProyectosEnMemoria datos;
    private ImagenesEnMemoria memoria;
    private ImagenesDelChat imagenes;
    private AppUser ana;
    private UUID diagramaId;
    private DetectorFalso detector;

    @BeforeEach
    void preparar() {
        datos = new ProyectosEnMemoria();
        ana = datos.usuario("Ana");
        datos.proyecto(ana, "Biblioteca");
        diagramaId = datos.diagramas.values().iterator().next().getId();
        memoria = new ImagenesEnMemoria(datos);
        imagenes = memoria.servicio(100, 209_715_200L);
        detector = new DetectorFalso();
    }

    private ProcesadorDeImagenDelChat procesador(EnriquecedorDeDeteccion... capas) {
        return new ProcesadorDeImagenDelChat(imagenes, detector, List.of(capas), 40_000_000L, 1600);
    }

    private UUID subir(byte[] png) {
        return imagenes.subir(diagramaId, ana.getId(), png, "image/png").imagen().id();
    }

    private static LecturaDeImagen lectura(ProcesadorDeImagenDelChat.Lectura l) {
        return l.propuesta().lecturaDeImagen();
    }

    // ---------------------------------------------------------- 22 · el turno

    @Test
    void la_propuesta_trae_la_lectura_con_origen_image_sin_comandos_y_el_resumen() {
        UUID id = subir(pngConMarca(200, 100, 10, 20));
        detector.resultado = conNombres();

        ProcesadorDeImagenDelChat.Lectura l = procesador().procesar(diagramaId, id, null, null);

        PropuestaDeIA p = l.propuesta();
        assertThat(p.origin()).isEqualTo(Origin.IMAGE);
        assertThat(p.aceptados()).isEmpty();             // los comandos los arma el cliente con traductor.ts
        assertThat(p.notaDelModelo()).isEqualTo("Leí 2 clases y 1 relación; sin dudas.");
        assertThat(lectura(l).deteccion()).isSameAs(detector.resultado);
        assertThat(lectura(l).opciones()).isEqualTo(new LecturaDeImagen.OpcionesDeReproceso(null, null));
        assertThat(l.imagen().id()).isEqualTo(id);
        assertThat(l.imagen().subidaPorNombre()).isEqualTo("Ana");
    }

    @Test
    void sin_nivel_2_el_resumen_cuenta_las_dudas() {
        UUID id = subir(pngConMarca(200, 100, 10, 20));

        ProcesadorDeImagenDelChat.Lectura l = procesador().procesar(diagramaId, id, null, null);

        assertThat(l.propuesta().notaDelModelo()).isEqualTo("Leí 2 clases y 1 relación; 1 cosa con dudas.");
        assertThat(lectura(l).dudas()).extracting(LecturaDeImagen.Duda::tipo)
                .containsExactly(TipoDeDuda.SIN_LECTURA_DE_TEXTO);
        assertThat(lectura(l).enriquecedoresAplicados()).isEmpty();
        assertThat(lectura(l).enriquecedoresFallidos()).isEmpty();
    }

    @Test
    void una_imagen_sin_transformar_llega_al_detector_con_sus_medidas_guardadas() {
        UUID id = subir(pngConMarca(200, 100, 10, 20));

        procesador().procesar(diagramaId, id, null, null);

        assertThat(detector.ultima.ancho()).isEqualTo(200);
        assertThat(detector.ultima.alto()).isEqualTo(100);
        assertThat(detector.ultima.gris(12, 22)).isEqualTo(255);   // la marca
        assertThat(detector.ultima.gris(100, 50)).isEqualTo(100);
    }

    // ------------------------------------------------- 23 · degradacion

    @Test
    void si_el_enriquecedor_falla_el_turno_sale_igual_con_el_nivel_1_y_lo_lista() {
        UUID id = subir(pngConMarca(200, 100, 10, 20));
        EnriquecedorFalso roto = new EnriquecedorFalso("modelo-de-vision");
        roto.lanza = new IllegalStateException("el modelo se cayo");

        ProcesadorDeImagenDelChat.Lectura l = procesador(roto).procesar(diagramaId, id, null, null);

        assertThat(lectura(l).deteccion()).isSameAs(detector.resultado);
        assertThat(lectura(l).enriquecedoresFallidos()).containsExactly("modelo-de-vision");
        assertThat(lectura(l).enriquecedoresAplicados()).isEmpty();
        assertThat(roto.llamadas).isEqualTo(1);
    }

    @Test
    void una_capa_que_funciona_se_aplica_y_una_no_disponible_ni_se_llama_ni_cuenta_como_fallida() {
        UUID id = subir(pngConMarca(200, 100, 10, 20));
        EnriquecedorFalso bueno = new EnriquecedorFalso("plantillas");
        bueno.transformacion = d -> conNombres();
        EnriquecedorFalso apagado = new EnriquecedorFalso("apagado");
        apagado.disponible = false;

        ProcesadorDeImagenDelChat.Lectura l = procesador(bueno, apagado).procesar(diagramaId, id, null, null);

        assertThat(lectura(l).enriquecedoresAplicados()).containsExactly("plantillas");
        assertThat(lectura(l).enriquecedoresFallidos()).isEmpty();
        assertThat(apagado.llamadas).isZero();
        assertThat(lectura(l).deteccion().clases()).extracting(DeteccionPropuesta.ClaseDetectada::nombre)
                .containsExactly("Cliente", "Pedido");
        assertThat(lectura(l).dudas()).isEmpty();
    }

    @Test
    void una_capa_que_falla_no_se_lleva_lo_que_aportaron_las_anteriores() {
        UUID id = subir(pngConMarca(200, 100, 10, 20));
        EnriquecedorFalso primera = new EnriquecedorFalso("primera");
        primera.transformacion = d -> conNombres();
        EnriquecedorFalso segunda = new EnriquecedorFalso("segunda");
        segunda.lanza = new RuntimeException("boom");

        ProcesadorDeImagenDelChat.Lectura l = procesador(primera, segunda).procesar(diagramaId, id, null, null);

        assertThat(lectura(l).deteccion().clases().get(0).nombre()).isEqualTo("Cliente");
        assertThat(lectura(l).enriquecedoresAplicados()).containsExactly("primera");
        assertThat(lectura(l).enriquecedoresFallidos()).containsExactly("segunda");
    }

    // ------------------------------- hallazgo de Pepe: una capa no puede bajar

    @Test
    void una_capa_que_baja_las_clases_se_descarta_como_si_hubiera_fallado() {
        UUID id = subir(pngConMarca(200, 100, 10, 20));
        EnriquecedorFalso borra = new EnriquecedorFalso("borra-clases");
        // Devuelve MENOS clases (y nombres bonitos): el contrato dice que solo rellena huecos.
        borra.transformacion = d -> deteccion(List.of(clase("c1", "Cliente", 0.9)), d.relaciones());

        ProcesadorDeImagenDelChat.Lectura l = procesador(borra).procesar(diagramaId, id, null, null);

        assertThat(lectura(l).deteccion()).isSameAs(detector.resultado);          // se queda la del nivel 1
        assertThat(lectura(l).deteccion().clases()).hasSize(2);
        assertThat(lectura(l).enriquecedoresFallidos()).containsExactly("borra-clases");
        assertThat(lectura(l).enriquecedoresAplicados()).isEmpty();
    }

    @Test
    void una_capa_que_baja_las_relaciones_tambien_se_descarta_y_las_siguientes_siguen_corriendo() {
        UUID id = subir(pngConMarca(200, 100, 10, 20));
        EnriquecedorFalso borra = new EnriquecedorFalso("borra-relaciones");
        borra.transformacion = d -> deteccion(d.clases(), List.of());
        EnriquecedorFalso bueno = new EnriquecedorFalso("bueno");
        bueno.transformacion = d -> conNombres();

        ProcesadorDeImagenDelChat.Lectura l = procesador(borra, bueno).procesar(diagramaId, id, null, null);

        assertThat(lectura(l).deteccion().relaciones()).hasSize(1);
        assertThat(lectura(l).enriquecedoresFallidos()).containsExactly("borra-relaciones");
        assertThat(lectura(l).enriquecedoresAplicados()).containsExactly("bueno");
    }

    @Test
    void una_capa_que_agrega_elementos_si_se_acepta_y_una_que_devuelve_null_se_descarta() {
        UUID id = subir(pngConMarca(200, 100, 10, 20));
        EnriquecedorFalso agrega = new EnriquecedorFalso("agrega");
        agrega.transformacion = d -> deteccion(List.of(clase("c1", "A", 0.9), clase("c2", "B", 0.9), clase("c3", "C", 0.9)),
                d.relaciones());
        EnriquecedorFalso nula = new EnriquecedorFalso("nula");
        nula.transformacion = d -> null;

        ProcesadorDeImagenDelChat.Lectura l = procesador(agrega, nula).procesar(diagramaId, id, null, null);

        assertThat(lectura(l).deteccion().clases()).hasSize(3);
        assertThat(lectura(l).enriquecedoresAplicados()).containsExactly("agrega");
        assertThat(lectura(l).enriquecedoresFallidos()).containsExactly("nula");
    }

    // ----------------------------------------------- 25 · reprocesar

    @Test
    void reprocesar_con_giro_y_recorte_no_crea_otra_fila_y_las_opciones_quedan_anotadas() {
        UUID id = subir(pngConMarca(200, 100, 10, 20));
        ProcesadorDeImagenDelChat p = procesador();

        p.procesar(diagramaId, id, null, null);
        ProcesadorDeImagenDelChat.Lectura l = p.procesar(diagramaId, id, 90, new Recorte(0, 0, 120, 60));

        assertThat(memoria.vivas()).hasSize(1);
        assertThat(memoria.filas).hasSize(1);
        assertThat(l.imagen().id()).isEqualTo(id);
        assertThat(lectura(l).opciones()).isEqualTo(
                new LecturaDeImagen.OpcionesDeReproceso(90, new Recorte(0, 0, 120, 60)));
    }

    // ------------------------------------------- 29 · recorte, giro, orden

    @Test
    void el_recorte_se_hace_sobre_la_imagen_guardada_y_el_giro_sobre_el_recorte() {
        // 200x100 con la marca en (10..19, 20..29). Recorte de 120x60 desde (0,0), despues 90 grados horarios.
        UUID id = subir(pngConMarca(200, 100, 10, 20));

        procesador().procesar(diagramaId, id, 90, new Recorte(0, 0, 120, 60));

        // El recorte 120x60 girado 90 grados queda 60x120 (si se girara primero la imagen entera y despues se
        // recortara, saldria otra cosa).
        assertThat(detector.ultima.ancho()).isEqualTo(60);
        assertThat(detector.ultima.alto()).isEqualTo(120);
        // Un punto (x,y) del recorte va a (alto-1-y, x) = (59-y, x): la marca (10..19, 20..29) cae en x'=30..39, y'=10..19.
        assertThat(detector.ultima.gris(35, 15)).isEqualTo(255);
        assertThat(detector.ultima.gris(15, 35)).isEqualTo(100);
        assertThat(detector.ultima.gris(10, 20)).isEqualTo(100);   // donde estaba la marca sin girar
    }

    @Test
    void solo_recortar_respeta_los_pixeles_de_la_imagen_guardada() {
        UUID id = subir(pngConMarca(200, 100, 150, 50));

        procesador().procesar(diagramaId, id, null, new Recorte(140, 40, 60, 60));

        assertThat(detector.ultima.ancho()).isEqualTo(60);
        assertThat(detector.ultima.alto()).isEqualTo(60);
        assertThat(detector.ultima.gris(15, 15)).isEqualTo(255);   // (150-140, 50-40) = (10..19, 10..19)
        assertThat(detector.ultima.gris(40, 40)).isEqualTo(100);
    }

    @Test
    void los_giros_rectos_dejan_las_medidas_cambiadas_y_el_180_las_conserva() {
        UUID id = subir(pngConMarca(200, 100, 10, 20));
        ProcesadorDeImagenDelChat p = procesador();

        p.procesar(diagramaId, id, -90, null);
        assertThat(detector.ultima.ancho()).isEqualTo(100);
        assertThat(detector.ultima.alto()).isEqualTo(200);
        // -90 es 270 horario: (x,y) -> (y, w-1-x) = (20..29, 190..199 - 9 ...): la marca x 10..19 -> y' 180..189.
        assertThat(detector.ultima.gris(25, 185)).isEqualTo(255);

        p.procesar(diagramaId, id, 180, null);
        assertThat(detector.ultima.ancho()).isEqualTo(200);
        assertThat(detector.ultima.alto()).isEqualTo(100);
        // 180: (x,y) -> (199-x, 99-y): la marca cae en x' 180..189, y' 70..79.
        assertThat(detector.ultima.gris(185, 75)).isEqualTo(255);
    }

    @Test
    void un_giro_fino_agranda_el_lienzo_sin_cortar_las_esquinas() {
        UUID id = subir(pngConMarca(200, 100, 10, 20));

        procesador().procesar(diagramaId, id, 5, null);

        assertThat(detector.ultima.ancho()).isGreaterThan(200);
        assertThat(detector.ultima.alto()).isGreaterThan(100);
        // Lo que rodea se rellena con el gris del borde (100), no con negro ni blanco.
        assertThat(detector.ultima.gris(0, 0)).isEqualTo(100);
    }

    @Test
    void el_enderezador_automatico_no_se_apaga_el_giro_del_usuario_solo_se_suma() {
        // El procesador no toca el detector: le pasa la imagen ya girada y el detector sigue decidiendo su fino.
        UUID id = subir(pngConMarca(200, 100, 10, 20));

        procesador().procesar(diagramaId, id, 90, null);

        assertThat(detector.llamadas).isEqualTo(1);
        assertThat(detector.ultima).isNotNull();
    }

    // -------------------------------------------------------- errores

    @Test
    void una_imagen_de_otro_diagrama_o_eliminada_o_inexistente_da_404() {
        UUID id = subir(pngConMarca(200, 100, 10, 20));
        UUID ajena = UUID.randomUUID();
        memoria.filas.put(ajena, new ImagenDelChat(ajena, UUID.randomUUID(), ana.getId(), "image/png", 10, 200, 100,
                "a".repeat(64), OffsetDateTime.now(), null, null));
        memoria.bytes.put(ajena, pngConMarca(200, 100, 1, 1));
        imagenes.eliminar(diagramaId, id, ana.getId(), quien -> true);
        ProcesadorDeImagenDelChat p = procesador();

        for (UUID malo : List.of(id, ajena, UUID.randomUUID())) {
            assertThatThrownBy(() -> p.procesar(diagramaId, malo, null, null))
                    .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                        assertThat(e.getStatusCode().value()).isEqualTo(404);
                        assertThat(e.getReason()).isEqualTo("Esa imagen no existe en este diagrama");
                    });
        }
        assertThat(detector.llamadas).isZero();
    }

    @Test
    void un_recorte_fuera_de_la_imagen_guardada_o_menor_de_32_px_da_400_con_el_mensaje_del_contrato() {
        UUID id = subir(pngConMarca(200, 100, 10, 20));
        ProcesadorDeImagenDelChat p = procesador();
        List<Recorte> malos = List.of(
                new Recorte(-1, 0, 100, 50),     // x negativa
                new Recorte(0, -5, 100, 50),     // y negativa
                new Recorte(150, 0, 100, 50),    // x + ancho > 200
                new Recorte(0, 60, 100, 50),     // y + alto > 100
                new Recorte(0, 0, 31, 50),       // ancho < 32
                new Recorte(0, 0, 100, 31));     // alto < 32

        for (Recorte r : malos) {
            assertThatThrownBy(() -> p.procesar(diagramaId, id, null, r))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("El recorte tiene que estar dentro de la imagen (200 × 100) y medir al menos 32 px");
        }
        assertThat(detector.llamadas).isZero();
        // Justo en el borde si cabe.
        p.procesar(diagramaId, id, null, new Recorte(168, 68, 32, 32));
        assertThat(detector.ultima.ancho()).isEqualTo(32);
    }
}
