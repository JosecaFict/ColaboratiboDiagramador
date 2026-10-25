package com.uagrm.colabmodel.collab;

import com.uagrm.colabmodel.modelcore.command.*;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Deshacer con dos sesiones editando el mismo diagrama (ADR-027).
 *
 * Lo dificil del undo colaborativo no es revertir: es no revertir de mas.
 * "La ultima operacion del diagrama" puede ser de otro, y lo que yo movi pudo
 * haberlo movido otro despues. Estas pruebas pasan por el BUS real, con su
 * hilo unico por diagrama (ADR-005), porque es ahi donde se decide quien gana.
 */
class DeshacerConcurrenteTest {

    private final ModeloEnMemoria d = new ModeloEnMemoria();
    private final Autor pestanaDeAna = new Autor(d.ana, "sesion-ana");
    private final Autor pestanaDeBeto = new Autor(d.beto, "sesion-beto");

    @Test
    void el_undo_es_local_deshace_lo_mio_aunque_lo_ultimo_del_diagrama_sea_de_otro() {
        UUID libro = d.clase(100, 100);
        UUID autor = d.clase(600, 100);
        var mia = d.bus.submit(d.mover(libro, 300, 300), pestanaDeAna);
        d.bus.submit(d.mover(autor, 800, 800), pestanaDeBeto);   // la ultima es de Beto

        d.bus.submit(d.deshacerMover(libro, mia.opId(), 100, 100, 300, 300), pestanaDeAna);

        assertThat(d.x(libro)).isEqualTo(100);
        assertThat(d.x(autor)).isEqualTo(800);   // lo de Beto no se toca
    }

    @Test
    void si_otra_sesion_movio_encima_el_undo_se_rechaza_y_gana_su_cambio() {
        UUID libro = d.clase(100, 100);
        var mia = d.bus.submit(d.mover(libro, 300, 300), pestanaDeAna);
        d.bus.submit(d.mover(libro, 500, 500), pestanaDeBeto);

        var e = catchThrowableOfType(
                () -> d.bus.submit(d.deshacerMover(libro, mia.opId(), 100, 100, 300, 300), pestanaDeAna),
                CommandRejectedException.class);

        assertThat(e.getReason()).isEqualTo(RejectionReason.STALE);
        assertThat(d.x(libro)).isEqualTo(500);
    }

    /**
     * La carrera de verdad: el Ctrl+Z de Ana y el arrastre de Beto salen en el
     * mismo instante desde dos hilos. Hay dos ordenes posibles y los dos son
     * correctos --undo primero y Beto encima, o Beto primero y undo rechazado--,
     * pero en ninguno el undo puede quedar aplicado SOBRE el cambio de Beto.
     * Se repite porque el orden lo elige el planificador.
     */
    @RepeatedTest(40)
    void undo_y_movimiento_ajeno_simultaneos_nunca_pisan_al_otro() throws Exception {
        UUID libro = d.clase(100, 100);
        var mia = d.bus.submit(d.mover(libro, 300, 300), pestanaDeAna);

        CountDownLatch largada = new CountDownLatch(1);
        ExecutorService dos = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> undo = dos.submit(() -> {
                largada.await();
                try {
                    d.bus.submit(d.deshacerMover(libro, mia.opId(), 100, 100, 300, 300), pestanaDeAna);
                    return true;
                } catch (CommandRejectedException e) {
                    assertThat(e.getReason()).isEqualTo(RejectionReason.STALE);
                    return false;
                }
            });
            Future<?> beto = dos.submit(() -> {
                largada.await();
                return d.bus.submit(d.mover(libro, 500, 500), pestanaDeBeto);
            });
            largada.countDown();

            undo.get(5, TimeUnit.SECONDS);
            beto.get(5, TimeUnit.SECONDS);
        } finally {
            dos.shutdownNow();
        }

        // Sea cual sea el orden, lo ultimo que queda es el cambio de Beto.
        assertThat(d.x(libro)).isEqualTo(500);
        assertThat(d.y(libro)).isEqualTo(500);
    }

    /**
     * La misma carrera en la semantica (escalon 2): Ana deshace su renombrar
     * mientras Beto, con la clase libre, la renombra. Gane quien gane el hilo,
     * el nombre final es el de Beto.
     */
    @RepeatedTest(40)
    void undo_de_renombrar_y_renombrar_ajeno_simultaneos_nunca_pisan_al_otro() throws Exception {
        UUID libro = d.clase("Libro", 0, 0);
        var mia = d.bus.submit(d.comando(CommandType.RENAME_CLASS,
                "{'classId':'%s','name':'Libros'}".formatted(libro)), pestanaDeAna);

        CountDownLatch largada = new CountDownLatch(1);
        ExecutorService dos = Executors.newFixedThreadPool(2);
        try {
            Future<?> undo = dos.submit(() -> {
                largada.await();
                try {
                    d.bus.submit(d.deshacer(CommandType.RENAME_CLASS, "{'classId':'%s','name':'Libro'}".formatted(libro),
                            mia.opId(), "{'name':'Libros'}"), pestanaDeAna);
                } catch (CommandRejectedException e) {
                    assertThat(e.getReason()).isEqualTo(RejectionReason.STALE);
                }
                return null;
            });
            Future<?> beto = dos.submit(() -> {
                largada.await();
                return d.bus.submit(d.comando(CommandType.RENAME_CLASS,
                        "{'classId':'%s','name':'Ejemplar'}".formatted(libro)), pestanaDeBeto);
            });
            largada.countDown();
            undo.get(5, TimeUnit.SECONDS);
            beto.get(5, TimeUnit.SECONDS);
        } finally {
            dos.shutdownNow();
        }

        assertThat(d.clases.get(libro).getName()).isEqualTo("Ejemplar");
    }

    /**
     * Escalon 3: Ana deshace la creacion de una clase mientras Beto le agrega
     * un atributo. Si gana Ana, el atributo de Beto se rechaza (la clase ya no
     * esta); si gana Beto, el undo de Ana se rechaza (la clase ya no esta
     * vacia). Lo que nunca puede pasar es un atributo colgando de una clase
     * borrada, ni que el undo se lleve el trabajo de Beto.
     */
    @RepeatedTest(40)
    void deshacer_crear_y_agregarle_algo_simultaneos_nunca_dejan_huerfanos() throws Exception {
        UUID socio = UUID.randomUUID();
        UUID dni = UUID.randomUUID();
        var creada = d.bus.submit(d.comando(CommandType.CREATE_CLASS,
                "{'classId':'%s','name':'Socio'}".formatted(socio)), pestanaDeAna);

        CountDownLatch largada = new CountDownLatch(1);
        ExecutorService dos = Executors.newFixedThreadPool(2);
        try {
            Future<?> undo = dos.submit(() -> {
                largada.await();
                try {
                    d.bus.submit(d.deshacer(CommandType.DELETE_CLASS, "{'classId':'%s'}".formatted(socio),
                            creada.opId(), "{'name':'Socio','attributeIds':[],'methodIds':[],'relationIds':[]}"),
                            pestanaDeAna);
                } catch (CommandRejectedException e) {
                    assertThat(e.getReason()).isEqualTo(RejectionReason.STALE);
                }
                return null;
            });
            Future<?> beto = dos.submit(() -> {
                largada.await();
                try {
                    d.bus.submit(d.comando(CommandType.ADD_ATTRIBUTE,
                            "{'classId':'%s','attributeId':'%s','name':'dni'}".formatted(socio, dni)), pestanaDeBeto);
                } catch (CommandRejectedException e) {
                    assertThat(e.getReason()).isEqualTo(RejectionReason.NOT_FOUND);
                }
                return null;
            });
            largada.countDown();
            undo.get(5, TimeUnit.SECONDS);
            beto.get(5, TimeUnit.SECONDS);
        } finally {
            dos.shutdownNow();
        }

        // O la clase sigue con el atributo de Beto, o no queda ninguno de los dos.
        assertThat(d.atributos.containsKey(dni)).isEqualTo(d.viva(socio));
    }

    @Test
    void la_otra_pestana_de_la_misma_persona_tambien_puede_deshacer_lo_suyo_pero_no_pisar() {
        // El undo es por sesion en la interfaz; en el servidor la autoria es
        // del usuario. Una segunda pestana de Ana que movio encima es "otro".
        UUID libro = d.clase(100, 100);
        var mia = d.bus.submit(d.mover(libro, 300, 300), pestanaDeAna);
        d.bus.submit(d.mover(libro, 450, 450), new Autor(d.ana, "sesion-ana-2"));

        var e = catchThrowableOfType(
                () -> d.bus.submit(d.deshacerMover(libro, mia.opId(), 100, 100, 300, 300), pestanaDeAna),
                CommandRejectedException.class);

        assertThat(e.getReason()).isEqualTo(RejectionReason.STALE);
        assertThat(d.x(libro)).isEqualTo(450);
    }
}
