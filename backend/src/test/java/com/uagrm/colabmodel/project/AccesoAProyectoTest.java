package com.uagrm.colabmodel.project;

import com.uagrm.colabmodel.iam.AppUser;
import com.uagrm.colabmodel.project.AccesoAProyecto.Accion;
import com.uagrm.colabmodel.project.AccesoAProyecto.Rol;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * La tabla de permisos del ADR-026, celda por celda.
 *
 * `permite` es lo unico que decide quien puede hacer que: los servicios, el bus
 * y el interceptor de WebSocket le preguntan a el. Si alguien cambia una celda,
 * este test falla y obliga a decir en voz alta que cambio el modelo de roles.
 */
class AccesoAProyectoTest {

    /** {OWNER, EDITOR, VIEWER} por accion: es la tabla del ADR-026 copiada tal cual. */
    private static final Map<Accion, boolean[]> TABLA = new EnumMap<>(Accion.class);

    static {
        //                                          OWNER  EDITOR VIEWER
        TABLA.put(Accion.LEER,                 new boolean[]{true,  true,  true});
        TABLA.put(Accion.EDITAR_MODELO,        new boolean[]{true,  true,  false});
        TABLA.put(Accion.CREAR_DIAGRAMA,       new boolean[]{true,  true,  false});
        TABLA.put(Accion.ELIMINAR_DIAGRAMA,    new boolean[]{true,  false, false});
        TABLA.put(Accion.ADMINISTRAR_PROYECTO, new boolean[]{true,  false, false});
        TABLA.put(Accion.ADMINISTRAR_MIEMBROS, new boolean[]{true,  false, false});
        TABLA.put(Accion.TRANSFERIR_PROPIEDAD, new boolean[]{true,  false, false});
        TABLA.put(Accion.ABANDONAR,            new boolean[]{false, true,  true});
    }

    private final ProyectosEnMemoria datos = new ProyectosEnMemoria();
    private final AccesoAProyecto acceso = datos.acceso;

    @Test
    void la_tabla_cubre_todas_las_acciones() {
        // Una accion nueva sin fila aca es una accion cuyo permiso nadie decidio.
        assertThat(TABLA.keySet()).containsExactlyInAnyOrder(Accion.values());
    }

    @Test
    void cada_celda_de_la_tabla_de_permisos() {
        for (Accion accion : Accion.values()) {
            boolean[] esperado = TABLA.get(accion);
            for (Rol rol : Rol.values()) {
                assertThat(acceso.permite(rol, accion))
                        .as("%s puede %s", rol, accion)
                        .isEqualTo(esperado[rol.ordinal()]);
            }
        }
    }

    @Test
    void puedeEditar_es_la_misma_regla_que_editar_el_modelo() {
        for (Rol rol : Rol.values()) {
            assertThat(acceso.puedeEditar(rol)).isEqualTo(acceso.permite(rol, Accion.EDITAR_MODELO));
        }
    }

    @Test
    void el_propietario_no_se_asigna_pero_editor_y_observador_si() {
        assertThat(acceso.esAsignable(Rol.OWNER)).isFalse();
        assertThat(acceso.esAsignable(Rol.EDITOR)).isTrue();
        assertThat(acceso.esAsignable(Rol.VIEWER)).isTrue();
    }

    @Test
    void exigir_devuelve_el_rol_si_puede_y_403_si_no() {
        AppUser duenio = datos.usuario("Ana");
        Project p = datos.proyecto(duenio, "P");
        AppUser observador = datos.miembro(p, "Beto", Rol.VIEWER);

        assertThat(acceso.exigir(p.getId(), duenio.getId(), Accion.ADMINISTRAR_MIEMBROS)).isEqualTo(Rol.OWNER);
        assertThat(acceso.exigir(p.getId(), observador.getId(), Accion.LEER)).isEqualTo(Rol.VIEWER);

        assertThatThrownBy(() -> acceso.exigir(p.getId(), observador.getId(), Accion.EDITAR_MODELO))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(e.getReason()).contains("Observador").contains("editar el modelo");
                });
    }

    @Test
    void quien_no_es_miembro_recibe_403_y_un_proyecto_inexistente_se_ve_igual() {
        AppUser duenio = datos.usuario("Ana");
        Project p = datos.proyecto(duenio, "P");
        UUID extrano = UUID.randomUUID();

        assertThatThrownBy(() -> acceso.exigir(p.getId(), extrano, Accion.LEER))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        // No revela que ids existen: un proyecto que no existe da lo mismo que uno ajeno.
        assertThatThrownBy(() -> acceso.exigir(UUID.randomUUID(), duenio.getId(), Accion.LEER))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void el_propietario_no_puede_abandonar_y_el_mensaje_dice_que_hacer() {
        AppUser duenio = datos.usuario("Ana");
        Project p = datos.proyecto(duenio, "P");

        assertThatThrownBy(() -> acceso.exigir(p.getId(), duenio.getId(), Accion.ABANDONAR))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(e.getReason()).contains("transferí la propiedad");
                });
    }

    @Test
    void rolesDe_junta_los_proyectos_propios_y_los_de_miembro() {
        AppUser ana = datos.usuario("Ana");
        AppUser beto = datos.usuario("Beto");
        Project deAna = datos.proyecto(ana, "De Ana");
        Project deBeto = datos.proyecto(beto, "De Beto");
        datos.proyecto(beto, "Otro de Beto");
        ProjectMember fila = new ProjectMember();
        fila.setProjectId(deBeto.getId());
        fila.setUserId(ana.getId());
        fila.setRole("VIEWER");
        datos.miembros.add(fila);

        Map<UUID, Rol> roles = acceso.rolesDe(ana.getId());

        assertThat(roles).containsEntry(deAna.getId(), Rol.OWNER).containsEntry(deBeto.getId(), Rol.VIEWER).hasSize(2);
    }

    @Test
    void una_fila_con_un_rol_que_ya_no_existe_no_da_acceso() {
        AppUser duenio = datos.usuario("Ana");
        Project p = datos.proyecto(duenio, "P");
        AppUser raro = datos.miembro(p, "Raro", Rol.EDITOR);
        datos.miembros.get(0).setRole("SUPERUSUARIO");

        assertThat(acceso.rolSobreProyecto(p.getId(), raro.getId())).isEmpty();
    }

    @Test
    void rolDeTexto_no_depende_de_mayusculas_ni_del_idioma_del_sistema() {
        assertThat(AccesoAProyecto.rolDeTexto(" viewer ")).contains(Rol.VIEWER);
        assertThat(AccesoAProyecto.rolDeTexto("Editor")).contains(Rol.EDITOR);
        assertThat(AccesoAProyecto.rolDeTexto("admin")).isEmpty();
        assertThat(AccesoAProyecto.rolDeTexto(null)).isEmpty();
    }
}
