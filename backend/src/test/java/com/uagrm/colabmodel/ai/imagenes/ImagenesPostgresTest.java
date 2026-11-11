package com.uagrm.colabmodel.ai.imagenes;

import com.uagrm.colabmodel.PostgresDePruebas;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * La Fase 1 de las imagenes contra PostgreSQL de verdad (pruebas 7, 16 y 20 del contrato): V14 se aplica, el
 * almacen JDBC guarda y lee los bytes, el candado del diagrama serializa las subidas, el borrado logico respeta
 * el CHECK y borrar el diagrama arrastra sus imagenes. Se OMITEN solo si no hay servidor (ver PostgresDePruebas).
 */
@SpringBootTest(properties = "colabmodel.ai.imagenes.max-por-diagrama=5")
class ImagenesPostgresTest extends PostgresDePruebas {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ImagenesDelChat servicio;

    private final String marca = UUID.randomUUID().toString().substring(0, 8);
    private UUID usuario;
    private UUID proyecto;
    private UUID diagrama;

    @BeforeEach
    void unDiagrama() {
        usuario = jdbc.queryForObject("insert into users(email, password_hash, full_name) values (?, 'x', 'Ana') "
                + "returning id", UUID.class, "img-" + marca + "@prueba.test");
        proyecto = jdbc.queryForObject("insert into projects(name, owner_id) values (?, ?) returning id",
                UUID.class, "Imagenes " + marca, usuario);
        diagrama = jdbc.queryForObject("insert into diagrams(project_id, name) values (?, 'D') returning id",
                UUID.class, proyecto);
    }

    @AfterEach
    void limpiar() {
        jdbc.update("delete from projects where owner_id = ?", usuario);
        jdbc.update("delete from users where id = ?", usuario);
    }

    private static byte[] imagen(int n) {
        return ImagenesEnMemoria.png(40, new Color(n * 20 % 256, n * 7 % 256, 100 + n % 100));
    }

    private int vivas() {
        return jdbc.queryForObject("select count(*) from ai_images where diagram_id = ? and deleted_at is null",
                Integer.class, diagrama);
    }

    @Test
    void sube_guarda_los_bytes_y_los_devuelve() {
        byte[] original = imagen(1);

        var r = servicio.subir(diagrama, usuario, original, "image/png");

        assertThat(r.nueva()).isTrue();
        var bytes = servicio.datos(diagrama, r.imagen().id());
        assertThat(bytes.tipo()).isEqualTo("image/png");
        assertThat(bytes.bytes()).isNotEmpty();
        assertThat(servicio.miniatura(diagrama, r.imagen().id()).bytes()).isNotEmpty();
        assertThat(jdbc.queryForObject("select size_bytes from ai_images where id = ?", Integer.class,
                r.imagen().id())).isEqualTo(bytes.bytes().length);
    }

    @Test
    void la_misma_imagen_dos_veces_deja_una_sola_fila_viva() {
        byte[] original = imagen(2);
        var primera = servicio.subir(diagrama, usuario, original, "image/png");
        var segunda = servicio.subir(diagrama, usuario, original, "image/png");

        assertThat(primera.nueva()).isTrue();
        assertThat(segunda.nueva()).isFalse();
        assertThat(segunda.imagen().id()).isEqualTo(primera.imagen().id());
        assertThat(vivas()).isEqualTo(1);
    }

    @Test
    void eliminar_vacia_los_bytes_respeta_el_check_y_libera_el_tope() {
        var r = servicio.subir(diagrama, usuario, imagen(3), "image/png");

        var e = servicio.eliminar(diagrama, r.imagen().id(), usuario, quien -> true);

        assertThat(e.cambio()).isTrue();
        assertThat(jdbc.queryForObject("select data is null and thumbnail is null and deleted_at is not null "
                + "from ai_images where id = ?", Boolean.class, r.imagen().id())).isTrue();
        assertThatThrownBy(() -> servicio.datos(diagrama, r.imagen().id())).isInstanceOf(ResponseStatusException.class);
        assertThat(servicio.galeria(diagrama).uso().cantidad()).isZero();
        // D7 con indice parcial: tras borrarla se puede volver a subir la misma.
        assertThat(servicio.subir(diagrama, usuario, imagen(3), "image/png").nueva()).isTrue();
    }

    @Test
    void borrar_el_diagrama_borra_sus_imagenes() {
        servicio.subir(diagrama, usuario, imagen(4), "image/png");
        assertThat(vivas()).isEqualTo(1);

        jdbc.update("delete from diagrams where id = ?", diagrama);

        assertThat(jdbc.queryForObject("select count(*) from ai_images where diagram_id = ?", Integer.class,
                diagrama)).isZero();
    }

    @Test
    void con_cuatro_de_cinco_lugares_ocho_subidas_simultaneas_dejan_exactamente_cinco() throws Exception {
        for (int i = 0; i < 4; i++) servicio.subir(diagrama, usuario, imagen(10 + i), "image/png");
        assertThat(vivas()).isEqualTo(4);

        List<Resultado> resultados = enParalelo(8, i -> imagen(30 + i));

        assertThat(vivas()).as("el candado impide pasar el tope").isEqualTo(5);
        assertThat(resultados.stream().filter(r -> r.exito).count()).isEqualTo(1);
        assertThat(resultados.stream().filter(r -> !r.exito).map(r -> r.error))
                .allMatch(e -> e instanceof TopeDeImagenesExcedido);
    }

    @Test
    void la_misma_imagen_subida_a_la_vez_deja_una_sola_fila_y_ninguna_falla() throws Exception {
        byte[] igual = imagen(60);

        List<Resultado> resultados = enParalelo(6, i -> igual);

        assertThat(vivas()).isEqualTo(1);
        assertThat(resultados).allMatch(r -> r.exito);
        assertThat(resultados.stream().filter(r -> r.nueva).count()).isEqualTo(1);
    }

    // --------------------------------------------------------------- ayudas

    private record Resultado(boolean exito, boolean nueva, Throwable error) { }

    private List<Resultado> enParalelo(int hilos, java.util.function.IntFunction<byte[]> imagenDelHilo)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(hilos);
        CountDownLatch salida = new CountDownLatch(1);
        List<Future<Resultado>> futuros = new ArrayList<>();
        for (int i = 0; i < hilos; i++) {
            byte[] bytes = imagenDelHilo.apply(i);
            futuros.add(pool.submit(() -> {
                salida.await();
                try {
                    return new Resultado(true, servicio.subir(diagrama, usuario, bytes, "image/png").nueva(), null);
                } catch (Throwable e) {
                    return new Resultado(false, false, e);
                }
            }));
        }
        salida.countDown();
        List<Resultado> r = new ArrayList<>();
        for (Future<Resultado> f : futuros) r.add(f.get());
        pool.shutdown();
        return r;
    }
}
