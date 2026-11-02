package com.uagrm.colabmodel.project;

import com.uagrm.colabmodel.codegen.GeneradorSpringBoot;
import com.uagrm.colabmodel.codegen.GeneradorSpringBoot.Almacen;
import com.uagrm.colabmodel.modelcore.mapping.Decisiones;
import com.uagrm.colabmodel.modelcore.mapping.ModeloCanonico;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.security.Principal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * `?store=` en /codegen y /codegen/resumen, y la Vista Documental (ADR-035),
 * contra el controlador real. El resumen de MongoDB tiene la MISMA forma que
 * el de PostgreSQL: lo que no aplica (la huella del DDL y su confirmacion)
 * viaja null, que es lo que el panel Generar espera.
 */
class CodegenHttpTest {

    private final UUID diagrama = UUID.randomUUID();
    private final UUID usuario = UUID.randomUUID();
    private final Principal quien = () -> usuario.toString();
    private MockMvc mvc;

    @BeforeEach
    void preparar() {
        ModeloCanonico.Cargado m = ModeloCanonico.cargar("02-uno-a-muchos.json");
        DiagramSnapshotService snapshots = mock(DiagramSnapshotService.class);
        AccesoAProyecto acceso = mock(AccesoAProyecto.class);
        when(acceso.rolSobreDiagrama(diagrama, usuario)).thenReturn(Optional.of(AccesoAProyecto.Rol.VIEWER));
        for (Almacen a : Almacen.values()) {
            when(snapshots.codigoDe(eq(diagrama), eq(a))).thenReturn(Optional.of(GeneradorSpringBoot.generar(
                    GeneradorSpringBoot.Opciones.por("Demo", a), m.clases(), m.atributos(), List.of(),
                    m.relaciones())));
        }
        when(snapshots.fisicaDe(any())).thenReturn(Optional.empty());
        when(snapshots.correccionesPreGeneracionDe(any())).thenReturn(Optional.of(List.of()));
        when(snapshots.documentalDe(diagrama)).thenReturn(Optional.of(GeneradorSpringBoot.documental("Demo",
                m.clases(), m.atributos(), List.of(), m.relaciones(), Decisiones.ninguna())));
        mvc = MockMvcBuilders.standaloneSetup(new DiagramRestController(mock(DiagramOperationRepository.class),
                snapshots, acceso, mock(VerificadorDeEsquema.class))).build();
    }

    @Test
    void el_resumen_de_mongo_tiene_la_misma_forma_sin_huella_del_ddl() throws Exception {
        mvc.perform(get("/api/v1/diagrams/" + diagrama + "/codegen/resumen").param("store", "mongo").principal(quien).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entidades").value(2))
                .andExpect(jsonPath("$.hashEsquema").doesNotExist())
                .andExpect(jsonPath("$.archivos").isArray())
                .andExpect(jsonPath("$.advertencias").isArray())
                .andExpect(jsonPath("$.correcciones").isArray());
        mvc.perform(get("/api/v1/diagrams/" + diagrama + "/codegen/resumen").principal(quien).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entidades").value(2))
                .andExpect(jsonPath("$.hashEsquema").isString());
    }

    @Test
    void un_store_desconocido_es_un_400() throws Exception {
        mvc.perform(get("/api/v1/diagrams/" + diagrama + "/codegen").param("store", "oracle").principal(quien).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    void el_candado_del_esquema_confirmado_es_solo_de_postgres() throws Exception {
        mvc.perform(get("/api/v1/diagrams/" + diagrama + "/codegen").param("esquema", "otra").principal(quien).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isConflict());
        mvc.perform(get("/api/v1/diagrams/" + diagrama + "/codegen").param("store", "mongo")
                        .param("esquema", "otra").principal(quien).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/zip"));
    }

    @Test
    void la_vista_documental_tiene_la_forma_del_contrato() throws Exception {
        mvc.perform(get("/api/v1/diagrams/" + diagrama + "/documental").principal(quien).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.colecciones[0].nombre").isString())
                .andExpect(jsonPath("$.colecciones[0].entidad").isString())
                .andExpect(jsonPath("$.colecciones[0].campos[0].nombre").value("id"))
                .andExpect(jsonPath("$.colecciones[0].campos[0].tipo").value("uuid"))
                .andExpect(jsonPath("$.colecciones[0].campos[0].requerido").value(true))
                .andExpect(jsonPath("$.colecciones[0].incrustados").isArray())
                .andExpect(jsonPath("$.colecciones[0].referencias").isArray())
                .andExpect(jsonPath("$.colecciones[?(@.nombre == 'pedidos')].referencias[0].campo").value("clienteId"))
                .andExpect(jsonPath("$.advertencias").isArray());
    }

    @Test
    void quien_no_es_miembro_no_ve_la_vista_documental() throws Exception {
        Principal extrano = () -> UUID.randomUUID().toString();
        mvc.perform(get("/api/v1/diagrams/" + diagrama + "/documental").principal(extrano).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }
}
