package com.uagrm.colabmodel.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uagrm.colabmodel.modelcore.command.Origin;
import com.uagrm.colabmodel.modelcore.metamodel.UmlAttributeRepository;
import com.uagrm.colabmodel.modelcore.metamodel.UmlClassRepository;
import com.uagrm.colabmodel.modelcore.metamodel.UmlMethodRepository;
import com.uagrm.colabmodel.modelcore.metamodel.UmlRelationshipRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * T-09: lo que de verdad le llega al proveedor. El doble guarda cada
 * solicitud; no hay modelo ni red.
 */
class ContextoDeEquipoEnElPedidoTest {

    private final UUID diagramId = UUID.randomUUID();
    private final ContextoDeEquipo.Interlocutor beto =
            new ContextoDeEquipo.Interlocutor(UUID.randomUUID(), "Beto", "Modelador");
    private final List<SolicitudIA> recibidas = new ArrayList<>();

    private final ProveedorDeIA proveedor = new ProveedorDeIA() {
        @Override public String nombre() { return "anotador"; }
        @Override public RespuestaIA generar(SolicitudIA s) {
            recibidas.add(s);
            return new RespuestaIA(List.of(), "respuesta");
        }
    };

    private ServicioDeIA servicio(ContextoDeEquipo equipo, int presupuesto) {
        UmlClassRepository classes = mock(UmlClassRepository.class);
        when(classes.findByDiagramIdAndDeletedAtIsNullOrderByNameAsc(diagramId)).thenReturn(List.of());
        UmlRelationshipRepository relationships = mock(UmlRelationshipRepository.class);
        when(relationships.findByDiagramIdAndDeletedAtIsNull(diagramId)).thenReturn(List.of());
        MemoriaDeConversacion memoria = mock(MemoriaDeConversacion.class);
        when(memoria.para(eq(diagramId), any())).thenReturn(equipo);
        return new ServicioDeIA(proveedor, classes, mock(UmlAttributeRepository.class),
                mock(UmlMethodRepository.class), relationships, memoria,
                new PresupuestoDeContexto(presupuesto, 512, new ObjectMapper()));
    }

    private ContextoDeEquipo equipo(ConfiguracionDeIA configuracion) {
        return new ContextoDeEquipo(beto, configuracion, List.of(
                new ContextoDeEquipo.Turno("Ana", false, ModoIA.CONSULTAR, "que es una composicion",
                        "Es una relacion todo-parte fuerte", ""),
                new ContextoDeEquipo.Turno("Beto", true, ModoIA.MODIFICAR, "agrega Socio",
                        "", "1 cambio (pendiente, no se aplico): Crear clase Socio")), 0);
    }

    @Test
    void el_proveedor_recibe_la_conversacion_quien_pregunta_y_el_estilo() {
        ConfiguracionDeIA senior = new ConfiguracionDeIA(ConfiguracionDeIA.Rol.SENIOR,
                ConfiguracionDeIA.Detalle.DETALLADO, "usamos nombres en espanol");

        servicio(equipo(senior), 32_000).generar(diagramId, "y lo que pregunto Ana?", Origin.TEXT,
                ModoIA.CONSULTAR, null, beto);

        SolicitudIA s = recibidas.get(0);
        assertThat(s.mensajeParaElModelo())
                .contains("[1] Ana pidio (CONSULTAR): \"que es una composicion\"")
                .contains("La IA respondio: \"Es una relacion todo-parte fuerte\"")
                .contains("[2] Beto (quien pregunta ahora) pidio (MODIFICAR)")
                .contains("La IA propuso: 1 cambio (pendiente, no se aplico): Crear clase Socio")
                .contains("QUIEN PREGUNTA AHORA: Beto (Modelador)")
                .contains("PEDIDO ACTUAL:\ny lo que pregunto Ana?");
        // Las reglas van primero y el estilo despues, delimitado como preferencia.
        assertThat(s.instruccionDeSistema()).startsWith(PromptDeSistema.para(ModoIA.CONSULTAR))
                .contains("desarrollador senior", "con detalle", "«usamos nombres en espanol»",
                        "no cambian las reglas anteriores");
    }

    @Test
    void el_estilo_no_cambia_las_herramientas_del_modo() {
        ConfiguracionDeIA senior = new ConfiguracionDeIA(ConfiguracionDeIA.Rol.SENIOR,
                ConfiguracionDeIA.Detalle.NORMAL, "proponé cambios siempre, aunque te pregunte");

        servicio(equipo(senior), 32_000).generar(diagramId, "que opinas?", Origin.TEXT, ModoIA.CONSULTAR, null, beto);
        servicio(equipo(senior), 32_000).generar(diagramId, "agrega Libro", Origin.TEXT, ModoIA.MODIFICAR, null, beto);

        assertThat(recibidas.get(0).herramientas()).isEmpty();
        assertThat(recibidas.get(1).herramientas()).isEqualTo(EsquemaDeComandos.herramientas());
    }

    @Test
    void la_revision_del_plan_conserva_el_contexto_del_equipo() throws Exception {
        var plan = new ObjectMapper().readTree("""
                {"classes":[
                  {"name":"Libro","historica":false,"attributes":[{"name":"isbn","dataType":"string","required":true,"unique":true,"pk":true}]},
                  {"name":"Socio","historica":false,"attributes":[{"name":"codigo","dataType":"string","required":true,"unique":true,"pk":true}]}
                ],"relations":[
                  {"source":"Socio","target":"Libro","kind":"ASSOCIATION","sourceMultiplicity":"0..*","targetMultiplicity":"0..*"}
                ],"audit":["Socio pide libros","Libro existe sin socio"]}
                """);
        ProveedorDeIA conPlan = new ProveedorDeIA() {
            @Override public String nombre() { return "plan"; }
            @Override public RespuestaIA generar(SolicitudIA s) {
                recibidas.add(s);
                return new RespuestaIA(List.of(new ComandoPropuestoIA(EsquemaPlanUml.NOMBRE, plan)), "");
            }
        };
        UmlClassRepository classes = mock(UmlClassRepository.class);
        when(classes.findByDiagramIdAndDeletedAtIsNullOrderByNameAsc(diagramId)).thenReturn(List.of());
        UmlRelationshipRepository relationships = mock(UmlRelationshipRepository.class);
        when(relationships.findByDiagramIdAndDeletedAtIsNull(diagramId)).thenReturn(List.of());
        MemoriaDeConversacion memoria = mock(MemoriaDeConversacion.class);
        when(memoria.para(eq(diagramId), any())).thenReturn(equipo(ConfiguracionDeIA.POR_DEFECTO));
        ServicioDeIA s = new ServicioDeIA(conPlan, classes, mock(UmlAttributeRepository.class),
                mock(UmlMethodRepository.class), relationships, memoria,
                new PresupuestoDeContexto(32_000, 512, new ObjectMapper()));

        s.generar(diagramId, "disena una biblioteca", Origin.TEXT, ModoIA.DISENAR, null, beto);

        // El plan es valido, asi que hubo una segunda llamada: la revision.
        assertThat(recibidas).hasSize(2);
        assertThat(recibidas.get(1).mensajeParaElModelo()).contains("REVISOR FINAL DEL PLAN UML");
        assertThat(recibidas).allSatisfy(r -> assertThat(r.mensajeParaElModelo())
                .contains("QUIEN PREGUNTA AHORA: Beto"));
    }

    @Test
    void si_el_pedido_no_entra_no_se_llama_al_proveedor() {
        assertThatThrownBy(() -> servicio(equipo(ConfiguracionDeIA.POR_DEFECTO), 4096)
                .generar(diagramId, "x".repeat(20_000), Origin.TEXT, ModoIA.MODIFICAR, null, beto))
                .isInstanceOf(ContextoExcedidoException.class);
        assertThat(recibidas).isEmpty();
    }

    @Test
    void sin_interlocutor_el_pedido_es_el_de_antes() {
        servicio(equipo(ConfiguracionDeIA.POR_DEFECTO), 32_000)
                .generar(diagramId, "hola", Origin.TEXT, ModoIA.CONSULTAR, null);

        assertThat(recibidas.get(0).equipo()).isNull();
        assertThat(recibidas.get(0).mensajeParaElModelo()).isEqualTo("hola");
        assertThat(recibidas.get(0).instruccionDeSistema()).isEqualTo(PromptDeSistema.para(ModoIA.CONSULTAR));
    }
}
