package com.uagrm.colabmodel.project;

import com.uagrm.colabmodel.iam.AppUser;
import com.uagrm.colabmodel.project.AccesoAProyecto.Rol;
import com.uagrm.colabmodel.project.CambioDeAcceso.Motivo;
import com.uagrm.colabmodel.project.GestionDeMiembros.Miembro;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Quien es miembro de un proyecto y con que rol (ADR-026), con el estilo de
 * `PermisoDeEscrituraTest`: reglas puras contra repositorios en memoria.
 *
 * Cada prueba que dice "no puede" afirma DOS cosas: el codigo de respuesta y que
 * el estado no se movio. Un rechazo que igual escribe seria peor que no
 * rechazar.
 */
class GestionDeMiembrosTest {

    private ProyectosEnMemoria datos;
    private ApplicationEventPublisher eventos;
    private GestionDeMiembros servicio;

    private AppUser ana;        // propietaria
    private AppUser beto;       // EDITOR
    private AppUser carla;      // VIEWER
    private AppUser extrano;    // no es miembro
    private Project proyecto;

    @BeforeEach
    void prepararProyectoConTresPersonas() {
        datos = new ProyectosEnMemoria();
        eventos = mock(ApplicationEventPublisher.class);
        servicio = new GestionDeMiembros(datos.projects, datos.members, datos.diagrams, datos.users,
                datos.acceso, eventos);

        ana = datos.usuario("Ana");
        proyecto = datos.proyecto(ana, "Biblioteca");
        beto = datos.miembro(proyecto, "Beto", Rol.EDITOR);
        carla = datos.miembro(proyecto, "Carla", Rol.VIEWER);
        extrano = datos.usuario("Extrano");
    }

    // ---------------------------------------------------------------- alta

    @Test
    void el_propietario_agrega_un_editor_y_un_observador() {
        AppUser dani = datos.usuario("Dani");
        AppUser eva = datos.usuario("Eva");

        Miembro comoEditor = servicio.agregar(proyecto.getId(), ana.getId(), dani.getEmail(), "EDITOR");
        Miembro comoObservador = servicio.agregar(proyecto.getId(), ana.getId(), eva.getEmail(), "VIEWER");

        assertThat(comoEditor.role()).isEqualTo(Rol.EDITOR);
        assertThat(comoObservador.role()).isEqualTo(Rol.VIEWER);
        assertThat(datos.rolDe(proyecto, dani)).contains(Rol.EDITOR);
        assertThat(datos.rolDe(proyecto, eva)).contains(Rol.VIEWER);
        assertThat(eventosPublicados()).extracting(CambioDeAcceso::motivo).containsOnly(Motivo.AGREGADO);
    }

    @Test
    void el_email_se_busca_sin_distinguir_mayusculas_ni_espacios() {
        AppUser dani = datos.usuario("Dani");

        servicio.agregar(proyecto.getId(), ana.getId(), "  DANI@UAGRM.EDU.BO ", "VIEWER");

        assertThat(datos.rolDe(proyecto, dani)).contains(Rol.VIEWER);
    }

    @Test
    void un_email_que_no_existe_da_404_y_no_crea_nada() {
        int antes = datos.miembros.size();

        assertRechazo(HttpStatus.NOT_FOUND,
                () -> servicio.agregar(proyecto.getId(), ana.getId(), "nadie@uagrm.edu.bo", "EDITOR"));

        assertThat(datos.miembros).hasSize(antes);
        verifyNoInteractions(eventos);
    }

    @Test
    void agregar_a_quien_ya_es_miembro_da_409() {
        assertRechazo(HttpStatus.CONFLICT,
                () -> servicio.agregar(proyecto.getId(), ana.getId(), beto.getEmail(), "VIEWER"));

        // Y no le cambio el rol de paso.
        assertThat(datos.rolDe(proyecto, beto)).contains(Rol.EDITOR);
    }

    @Test
    void agregar_al_propietario_como_miembro_da_409() {
        int antes = datos.miembros.size();

        assertRechazo(HttpStatus.CONFLICT,
                () -> servicio.agregar(proyecto.getId(), ana.getId(), ana.getEmail(), "VIEWER"));

        assertThat(datos.miembros).hasSize(antes);
    }

    @Test
    void no_se_puede_agregar_a_nadie_como_propietario_ni_con_un_rol_inventado() {
        AppUser dani = datos.usuario("Dani");
        int antes = datos.miembros.size();

        assertRechazo(HttpStatus.BAD_REQUEST,
                () -> servicio.agregar(proyecto.getId(), ana.getId(), dani.getEmail(), "OWNER"));
        assertRechazo(HttpStatus.BAD_REQUEST,
                () -> servicio.agregar(proyecto.getId(), ana.getId(), dani.getEmail(), "ADMIN"));
        assertRechazo(HttpStatus.BAD_REQUEST,
                () -> servicio.agregar(proyecto.getId(), ana.getId(), dani.getEmail(), null));

        assertThat(datos.miembros).hasSize(antes);
        assertThat(datos.propietarios(proyecto)).isEqualTo(1);
    }

    // ------------------------------------------------------- quien no puede

    @Test
    void un_editor_y_un_observador_no_agregan_ni_cambian_roles_ni_quitan() {
        AppUser dani = datos.usuario("Dani");
        int antes = datos.miembros.size();

        for (AppUser quien : List.of(beto, carla)) {
            assertRechazo(HttpStatus.FORBIDDEN,
                    () -> servicio.agregar(proyecto.getId(), quien.getId(), dani.getEmail(), "VIEWER"));
            assertRechazo(HttpStatus.FORBIDDEN,
                    () -> servicio.cambiarRol(proyecto.getId(), quien.getId(), carla.getId(), "EDITOR"));
            assertRechazo(HttpStatus.FORBIDDEN,
                    () -> servicio.quitar(proyecto.getId(), quien.getId(), quien == beto ? carla.getId() : beto.getId()));
            assertRechazo(HttpStatus.FORBIDDEN,
                    () -> servicio.transferir(proyecto.getId(), quien.getId(), quien.getId()));
        }

        assertThat(datos.miembros).hasSize(antes);
        assertThat(datos.rolDe(proyecto, carla)).contains(Rol.VIEWER);
        assertThat(datos.rolDe(proyecto, beto)).contains(Rol.EDITOR);
        verifyNoInteractions(eventos);
    }

    @Test
    void quien_no_es_miembro_recibe_403_en_todo() {
        UUID x = extrano.getId();
        UUID p = proyecto.getId();

        assertRechazo(HttpStatus.FORBIDDEN, () -> servicio.listar(p, x));
        assertRechazo(HttpStatus.FORBIDDEN, () -> servicio.agregar(p, x, carla.getEmail(), "VIEWER"));
        assertRechazo(HttpStatus.FORBIDDEN, () -> servicio.cambiarRol(p, x, carla.getId(), "EDITOR"));
        assertRechazo(HttpStatus.FORBIDDEN, () -> servicio.quitar(p, x, carla.getId()));
        assertRechazo(HttpStatus.FORBIDDEN, () -> servicio.quitar(p, x, x));         // ni "abandonar" lo que no es suyo
        assertRechazo(HttpStatus.FORBIDDEN, () -> servicio.transferir(p, x, x));

        verifyNoInteractions(eventos);
    }

    // -------------------------------------------------------- cambio de rol

    @Test
    void el_propietario_cambia_roles_y_el_cambio_se_anuncia() {
        Miembro r = servicio.cambiarRol(proyecto.getId(), ana.getId(), carla.getId(), "EDITOR");

        assertThat(r.role()).isEqualTo(Rol.EDITOR);
        assertThat(datos.rolDe(proyecto, carla)).contains(Rol.EDITOR);
        CambioDeAcceso e = unicoEvento();
        assertThat(e.usuarioId()).isEqualTo(carla.getId());
        assertThat(e.motivo()).isEqualTo(Motivo.ROL_CAMBIADO);
        assertThat(e.rol()).isEqualTo(Rol.EDITOR);
        assertThat(e.diagramas()).hasSize(1);   // los diagramas de los que hay que soltar locks
    }

    @Test
    void volver_a_poner_el_mismo_rol_no_anuncia_nada() {
        servicio.cambiarRol(proyecto.getId(), ana.getId(), carla.getId(), "VIEWER");

        verifyNoInteractions(eventos);
    }

    @Test
    void owner_no_se_asigna_por_el_endpoint_de_roles() {
        assertRechazo(HttpStatus.BAD_REQUEST,
                () -> servicio.cambiarRol(proyecto.getId(), ana.getId(), carla.getId(), "OWNER"));

        assertThat(datos.rolDe(proyecto, carla)).contains(Rol.VIEWER);
        assertThat(datos.propietarios(proyecto)).isEqualTo(1);
        verifyNoInteractions(eventos);
    }

    @Test
    void al_propietario_no_se_lo_degrada_ni_se_lo_quita() {
        assertRechazo(HttpStatus.CONFLICT,
                () -> servicio.cambiarRol(proyecto.getId(), ana.getId(), ana.getId(), "EDITOR"));
        assertRechazo(HttpStatus.CONFLICT,
                () -> servicio.cambiarRol(proyecto.getId(), ana.getId(), ana.getId(), "VIEWER"));

        assertThat(datos.rolDe(proyecto, ana)).contains(Rol.OWNER);
        assertThat(datos.miembros).noneMatch(m -> m.getUserId().equals(ana.getId()));
        verifyNoInteractions(eventos);
    }

    @Test
    void cambiar_el_rol_de_quien_no_es_miembro_da_404() {
        assertRechazo(HttpStatus.NOT_FOUND,
                () -> servicio.cambiarRol(proyecto.getId(), ana.getId(), extrano.getId(), "EDITOR"));
    }

    // ----------------------------------------------------------- quitar / irse

    @Test
    void el_propietario_quita_a_un_miembro_y_se_anuncia_la_baja() {
        servicio.quitar(proyecto.getId(), ana.getId(), beto.getId());

        assertThat(datos.rolDe(proyecto, beto)).isEmpty();
        CambioDeAcceso e = unicoEvento();
        assertThat(e.usuarioId()).isEqualTo(beto.getId());
        assertThat(e.motivo()).isEqualTo(Motivo.QUITADO);
        assertThat(e.rol()).isNull();   // sin rol = sin acceso
    }

    @Test
    void un_editor_y_un_observador_pueden_abandonar() {
        servicio.quitar(proyecto.getId(), beto.getId(), beto.getId());
        servicio.quitar(proyecto.getId(), carla.getId(), carla.getId());

        assertThat(datos.rolDe(proyecto, beto)).isEmpty();
        assertThat(datos.rolDe(proyecto, carla)).isEmpty();
        assertThat(eventosPublicados()).extracting(CambioDeAcceso::motivo).containsOnly(Motivo.ABANDONO);
        assertThat(datos.propietarios(proyecto)).isEqualTo(1);
    }

    @Test
    void el_propietario_no_puede_abandonar_primero_tiene_que_transferir() {
        assertThatThrownBy(() -> servicio.quitar(proyecto.getId(), ana.getId(), ana.getId()))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(e.getReason()).contains("transferí la propiedad");
                });

        assertThat(datos.rolDe(proyecto, ana)).contains(Rol.OWNER);
        verifyNoInteractions(eventos);
    }

    @Test
    void quitar_a_quien_no_es_miembro_da_404() {
        assertRechazo(HttpStatus.NOT_FOUND, () -> servicio.quitar(proyecto.getId(), ana.getId(), extrano.getId()));
    }

    // -------------------------------------------------------- transferencia

    @Test
    void la_transferencia_pasa_la_propiedad_y_el_propietario_anterior_queda_editor() {
        servicio.transferir(proyecto.getId(), ana.getId(), carla.getId());

        // owner_id es ahora Carla, y ella no tiene fila (el propietario es implicito).
        assertThat(proyecto.getOwnerId()).isEqualTo(carla.getId());
        assertThat(datos.rolDe(proyecto, carla)).contains(Rol.OWNER);
        assertThat(datos.miembros).noneMatch(m -> m.getUserId().equals(carla.getId()));
        // Ana queda como EDITOR, con fila.
        assertThat(datos.rolDe(proyecto, ana)).contains(Rol.EDITOR);
        // Beto no se entera de nada.
        assertThat(datos.rolDe(proyecto, beto)).contains(Rol.EDITOR);
        // Exactamente un propietario, y nunca una fila OWNER.
        assertThat(datos.propietarios(proyecto)).isEqualTo(1);

        assertThat(eventosPublicados()).extracting(CambioDeAcceso::motivo, CambioDeAcceso::usuarioId, CambioDeAcceso::rol)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(Motivo.PROPIEDAD_RECIBIDA, carla.getId(), Rol.OWNER),
                        org.assertj.core.groups.Tuple.tuple(Motivo.PROPIEDAD_CEDIDA, ana.getId(), Rol.EDITOR));
    }

    @Test
    void despues_de_transferir_la_nueva_propietaria_manda_y_la_anterior_ya_no() {
        servicio.transferir(proyecto.getId(), ana.getId(), carla.getId());

        // Ana ya no puede administrar...
        assertRechazo(HttpStatus.FORBIDDEN,
                () -> servicio.cambiarRol(proyecto.getId(), ana.getId(), beto.getId(), "VIEWER"));
        // ... y Carla si, incluida la que era propietaria.
        servicio.cambiarRol(proyecto.getId(), carla.getId(), ana.getId(), "VIEWER");
        assertThat(datos.rolDe(proyecto, ana)).contains(Rol.VIEWER);
    }

    @Test
    void solo_se_transfiere_a_un_miembro() {
        assertRechazo(HttpStatus.NOT_FOUND, () -> servicio.transferir(proyecto.getId(), ana.getId(), extrano.getId()));

        assertThat(proyecto.getOwnerId()).isEqualTo(ana.getId());
        verifyNoInteractions(eventos);
    }

    @Test
    void no_se_transfiere_a_uno_mismo() {
        assertRechazo(HttpStatus.CONFLICT, () -> servicio.transferir(proyecto.getId(), ana.getId(), ana.getId()));

        assertThat(proyecto.getOwnerId()).isEqualTo(ana.getId());
    }

    @Test
    void toda_operacion_que_cambia_miembros_es_una_sola_transaccion_con_el_proyecto_bloqueado() throws Exception {
        // La atomicidad la garantiza la base, pero solo si el metodo entero corre
        // en UNA transaccion. Que alguien le saque el @Transactional a `transferir`
        // no lo detectaria ninguna prueba en memoria: este es el candado, y
        // ApiDeProyectosTest prueba la reversion contra PostgreSQL.
        for (String nombre : List.of("agregar", "cambiarRol", "quitar", "transferir")) {
            Method m = Stream.of(GestionDeMiembros.class.getDeclaredMethods())
                    .filter(x -> x.getName().equals(nombre)).findFirst().orElseThrow();
            assertThat(m.getAnnotation(Transactional.class)).as("@Transactional en %s", nombre).isNotNull();
            assertThat(m.getAnnotation(Transactional.class).readOnly()).isFalse();
        }

        // Y cada una bloquea la fila del proyecto: dos pedidos simultaneos se serializan.
        servicio.transferir(proyecto.getId(), ana.getId(), carla.getId());
        servicio.cambiarRol(proyecto.getId(), carla.getId(), beto.getId(), "VIEWER");
        servicio.quitar(proyecto.getId(), carla.getId(), beto.getId());
        servicio.agregar(proyecto.getId(), carla.getId(), beto.getEmail(), "EDITOR");
        verify(datos.projects, times(4)).findParaModificar(proyecto.getId());
    }

    // --------------------------------------------------------------- lectura

    @Test
    void los_tres_roles_ven_la_lista_con_el_propietario_primero() {
        for (AppUser quien : List.of(ana, beto, carla)) {
            List<Miembro> lista = servicio.listar(proyecto.getId(), quien.getId());

            assertThat(lista).extracting(Miembro::role).containsExactly(Rol.OWNER, Rol.EDITOR, Rol.VIEWER);
            assertThat(lista.get(0).userId()).isEqualTo(ana.getId());
            assertThat(lista.get(0).email()).isEqualTo(ana.getEmail());
        }
    }

    // -------------------------------------------------------------- apoyo

    private void assertRechazo(HttpStatus esperado, Executable accion) {
        assertThatThrownBy(accion::execute)
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(esperado));
    }

    private List<CambioDeAcceso> eventosPublicados() {
        var captor = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(eventos, atLeastOnce()).publishEvent(captor.capture());
        return captor.getAllValues().stream().map(o -> (CambioDeAcceso) o).toList();
    }

    private CambioDeAcceso unicoEvento() {
        List<CambioDeAcceso> todos = eventosPublicados();
        assertThat(todos).hasSize(1);
        return todos.get(0);
    }
}
