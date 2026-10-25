package com.uagrm.colabmodel.project;

import com.uagrm.colabmodel.iam.AppUser;
import com.uagrm.colabmodel.project.AccesoAProyecto.Rol;
import com.uagrm.colabmodel.project.GestionDeProyectos.DiagramaResumen;
import com.uagrm.colabmodel.project.GestionDeProyectos.ProyectoDetalle;
import com.uagrm.colabmodel.project.GestionDeProyectos.ProyectoResumen;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * Proyectos y diagramas (ADR-026): que cada rol pueda lo que le toca y nada mas.
 * Quien puede que lo decide `AccesoAProyecto`; aca se prueba que cada accion
 * le pregunte lo correcto.
 */
class GestionDeProyectosTest {

    private ProyectosEnMemoria datos;
    private ApplicationEventPublisher eventos;
    private GestionDeProyectos servicio;

    private AppUser ana;        // propietaria
    private AppUser beto;       // EDITOR
    private AppUser carla;      // VIEWER
    private AppUser extrano;
    private Project proyecto;
    private Diagram diagrama;

    @BeforeEach
    void prepararProyecto() {
        datos = new ProyectosEnMemoria();
        eventos = mock(ApplicationEventPublisher.class);
        servicio = new GestionDeProyectos(datos.projects, datos.members, datos.diagrams, datos.users,
                datos.acceso, eventos);

        ana = datos.usuario("Ana");
        proyecto = datos.proyecto(ana, "Biblioteca");
        diagrama = datos.diagramas.values().iterator().next();
        beto = datos.miembro(proyecto, "Beto", Rol.EDITOR);
        carla = datos.miembro(proyecto, "Carla", Rol.VIEWER);
        extrano = datos.usuario("Extrano");
    }

    @Test
    void crear_un_proyecto_crea_tambien_su_primer_diagrama_y_quien_lo_crea_es_el_propietario() {
        ProyectoDetalle nuevo = servicio.crear(extrano.getId(), "  Barberia  ");

        assertThat(nuevo.name()).isEqualTo("Barberia");   // sin los espacios de los bordes
        assertThat(nuevo.myRole()).isEqualTo(Rol.OWNER);
        assertThat(nuevo.owner().userId()).isEqualTo(extrano.getId());
        assertThat(nuevo.diagrams()).hasSize(1);
        assertThat(nuevo.diagrams().get(0).name()).isEqualTo(GestionDeProyectos.PRIMER_DIAGRAMA);
        assertThat(datos.diagramas.values()).anyMatch(d -> d.getProjectId().equals(nuevo.id()));
        // El propietario es implicito: no se le crea fila de miembro.
        assertThat(datos.miembros).noneMatch(m -> m.getProjectId().equals(nuevo.id()));
    }

    @Test
    void un_nombre_vacio_o_demasiado_largo_da_400_y_no_crea_nada() {
        int proyectos = datos.proyectos.size();
        int diagramas = datos.diagramas.size();

        assertRechazo(HttpStatus.BAD_REQUEST, () -> servicio.crear(ana.getId(), "   "));
        assertRechazo(HttpStatus.BAD_REQUEST, () -> servicio.crear(ana.getId(), null));
        assertRechazo(HttpStatus.BAD_REQUEST, () -> servicio.crear(ana.getId(), "x".repeat(161)));

        assertThat(datos.proyectos).hasSize(proyectos);
        assertThat(datos.diagramas).hasSize(diagramas);
    }

    @Test
    void mis_proyectos_trae_los_propios_y_aquellos_donde_soy_miembro_con_mi_rol() {
        Project deCarla = datos.proyecto(carla, "Veterinaria");
        datos.proyecto(extrano, "Ajeno");   // no aparece: no soy miembro
        datos.diagrama(deCarla, "Otro");

        List<ProyectoResumen> deAna = servicio.misProyectos(ana.getId());
        List<ProyectoResumen> deBeto = servicio.misProyectos(beto.getId());

        assertThat(deAna).extracting(ProyectoResumen::name).containsExactly("Biblioteca");
        assertThat(deAna.get(0).myRole()).isEqualTo(Rol.OWNER);
        assertThat(deAna.get(0).diagramCount()).isEqualTo(1);
        assertThat(deAna.get(0).owner().fullName()).isEqualTo("Ana");

        assertThat(deBeto).extracting(ProyectoResumen::name).containsExactly("Biblioteca");
        assertThat(deBeto.get(0).myRole()).isEqualTo(Rol.EDITOR);
        assertThat(deBeto.get(0).owner().email()).isEqualTo(ana.getEmail());

        List<ProyectoResumen> deCarlaLista = servicio.misProyectos(carla.getId());
        assertThat(deCarlaLista).extracting(ProyectoResumen::name).containsExactly("Biblioteca", "Veterinaria");
        assertThat(deCarlaLista).extracting(ProyectoResumen::myRole).containsExactly(Rol.VIEWER, Rol.OWNER);
        assertThat(deCarlaLista.get(1).diagramCount()).isEqualTo(2);

        assertThat(servicio.misProyectos(UUID.randomUUID())).isEmpty();
    }

    @Test
    void los_tres_roles_ven_el_proyecto_y_sus_diagramas_con_su_rol() {
        assertThat(servicio.detalle(proyecto.getId(), ana.getId()).myRole()).isEqualTo(Rol.OWNER);
        assertThat(servicio.detalle(proyecto.getId(), beto.getId()).myRole()).isEqualTo(Rol.EDITOR);
        ProyectoDetalle paraCarla = servicio.detalle(proyecto.getId(), carla.getId());
        assertThat(paraCarla.myRole()).isEqualTo(Rol.VIEWER);
        assertThat(paraCarla.diagrams()).extracting(DiagramaResumen::id).containsExactly(diagrama.getId());
    }

    @Test
    void solo_el_propietario_renombra_y_elimina_el_proyecto() {
        assertThat(servicio.renombrar(proyecto.getId(), ana.getId(), "Biblioteca UAGRM").name())
                .isEqualTo("Biblioteca UAGRM");

        for (AppUser quien : List.of(beto, carla, extrano)) {
            assertRechazo(HttpStatus.FORBIDDEN, () -> servicio.renombrar(proyecto.getId(), quien.getId(), "Hackeado"));
            assertRechazo(HttpStatus.FORBIDDEN, () -> servicio.eliminar(proyecto.getId(), quien.getId()));
        }

        assertThat(proyecto.getName()).isEqualTo("Biblioteca UAGRM");
        assertThat(datos.proyectos).containsKey(proyecto.getId());
        verifyNoInteractions(eventos);
    }

    @Test
    void eliminar_el_proyecto_avisa_a_todos_sus_miembros_y_lista_los_diagramas_que_se_van() {
        Diagram segundo = datos.diagrama(proyecto, "Otro");

        servicio.eliminar(proyecto.getId(), ana.getId());

        assertThat(datos.proyectos).doesNotContainKey(proyecto.getId());
        var captor = ArgumentCaptor.forClass(Object.class);
        verify(eventos).publishEvent(captor.capture());
        RecursoEliminado e = (RecursoEliminado) captor.getValue();
        assertThat(e.fueElProyecto()).isTrue();
        assertThat(e.diagramas()).containsExactlyInAnyOrder(diagrama.getId(), segundo.getId());
        assertThat(e.miembros()).containsExactlyInAnyOrder(ana.getId(), beto.getId(), carla.getId());
    }

    // -------------------------------------------------------------- diagramas

    @Test
    void el_propietario_y_el_editor_crean_y_renombran_diagramas_el_observador_no() {
        DiagramaResumen deAna = servicio.crearDiagrama(proyecto.getId(), ana.getId(), "Ventas");
        DiagramaResumen deBeto = servicio.crearDiagrama(proyecto.getId(), beto.getId(), "Compras");
        assertThat(servicio.renombrarDiagrama(deAna.id(), beto.getId(), "Ventas 2").name()).isEqualTo("Ventas 2");
        assertThat(servicio.renombrarDiagrama(deBeto.id(), ana.getId(), "Compras 2").name()).isEqualTo("Compras 2");

        int antes = datos.diagramas.size();
        assertRechazo(HttpStatus.FORBIDDEN, () -> servicio.crearDiagrama(proyecto.getId(), carla.getId(), "X"));
        assertRechazo(HttpStatus.FORBIDDEN, () -> servicio.renombrarDiagrama(deAna.id(), carla.getId(), "X"));
        assertRechazo(HttpStatus.FORBIDDEN, () -> servicio.crearDiagrama(proyecto.getId(), extrano.getId(), "X"));
        assertThat(datos.diagramas).hasSize(antes);
        assertThat(datos.diagramas.get(deAna.id()).getName()).isEqualTo("Ventas 2");
    }

    @Test
    void solo_el_propietario_elimina_diagramas() {
        for (AppUser quien : List.of(beto, carla, extrano)) {
            assertRechazo(HttpStatus.FORBIDDEN, () -> servicio.eliminarDiagrama(diagrama.getId(), quien.getId()));
        }
        assertThat(datos.diagramas).containsKey(diagrama.getId());
        verifyNoInteractions(eventos);

        servicio.eliminarDiagrama(diagrama.getId(), ana.getId());

        assertThat(datos.diagramas).doesNotContainKey(diagrama.getId());
        var captor = ArgumentCaptor.forClass(Object.class);
        verify(eventos).publishEvent(captor.capture());
        RecursoEliminado e = (RecursoEliminado) captor.getValue();
        assertThat(e.fueElProyecto()).isFalse();
        assertThat(e.diagramaEliminado()).isEqualTo(diagrama.getId());
    }

    @Test
    void un_diagrama_que_no_existe_se_ve_como_uno_ajeno_igual_que_al_leerlo() {
        assertRechazo(HttpStatus.FORBIDDEN, () -> servicio.renombrarDiagrama(UUID.randomUUID(), ana.getId(), "X"));
        assertRechazo(HttpStatus.FORBIDDEN, () -> servicio.eliminarDiagrama(UUID.randomUUID(), ana.getId()));
    }

    @Test
    void el_no_miembro_recibe_403_en_todo() {
        UUID x = extrano.getId();
        assertRechazo(HttpStatus.FORBIDDEN, () -> servicio.detalle(proyecto.getId(), x));
        assertRechazo(HttpStatus.FORBIDDEN, () -> servicio.renombrar(proyecto.getId(), x, "X"));
        assertRechazo(HttpStatus.FORBIDDEN, () -> servicio.eliminar(proyecto.getId(), x));
        assertRechazo(HttpStatus.FORBIDDEN, () -> servicio.crearDiagrama(proyecto.getId(), x, "X"));
        assertRechazo(HttpStatus.FORBIDDEN, () -> servicio.renombrarDiagrama(diagrama.getId(), x, "X"));
        assertRechazo(HttpStatus.FORBIDDEN, () -> servicio.eliminarDiagrama(diagrama.getId(), x));
    }

    private void assertRechazo(HttpStatus esperado, Executable accion) {
        assertThatThrownBy(accion::execute)
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(esperado));
    }
}
