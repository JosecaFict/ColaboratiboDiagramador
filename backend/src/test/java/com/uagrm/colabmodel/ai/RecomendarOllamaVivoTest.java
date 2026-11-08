package com.uagrm.colabmodel.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.uagrm.colabmodel.modelcore.command.CommandType;
import com.uagrm.colabmodel.modelcore.command.Origin;
import com.uagrm.colabmodel.modelcore.metamodel.*;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Evaluacion optativa del modo RECOMENDAR contra Ollama real, sin escribir en
 * ningun diagrama: los repositorios son listas en memoria.
 *
 * - dominios(): por cada requisito de src/test/resources/evaluacion, Disenar ->
 *   materializar la propuesta -> Recomendar con el mismo requisito.
 * - academicoConErroresConocidos(): diagrama fijo (segunda corrida de Disenar,
 *   2026-11-08) con errores ya identificados, para comparar versiones.
 *
 * OLLAMA_DOMINIOS elige dominios; OLLAMA_RECOMENDAR_APLICAR=1 ademas convierte
 * las recomendaciones en una propuesta MODIFICAR.
 * Salida en target/evaluaciones-ia/recomendar-*.json.
 */
@EnabledIfEnvironmentVariable(named = "OLLAMA_RECOMENDAR", matches = "1")
@org.junit.jupiter.api.parallel.Execution(org.junit.jupiter.api.parallel.ExecutionMode.SAME_THREAD)
class RecomendarOllamaVivoTest {

    private static final List<String> DOMINIOS = List.of("ventas", "academico", "banco", "ganaderia", "turismo");
    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final String modelo = System.getenv().getOrDefault("OLLAMA_MODEL", "qwen3.5:9b-q4_K_M");
    private final Path salida = Path.of("target", "evaluaciones-ia");

    /** Repositorios en memoria: lo que Disenar propone se materializa aca y Recomendar lo lee. */
    private static final class Memoria {
        final UUID diagramId = UUID.randomUUID();
        final List<UmlClass> clases = new ArrayList<>();
        final List<UmlAttribute> atributos = new ArrayList<>();
        final List<UmlRelationship> relaciones = new ArrayList<>();
        final Map<String, UmlClass> porNombre = new HashMap<>();
        final UmlClassRepository classes = mock(UmlClassRepository.class);
        final UmlAttributeRepository attributes = mock(UmlAttributeRepository.class);
        final UmlMethodRepository methods = mock(UmlMethodRepository.class);
        final UmlRelationshipRepository relationships = mock(UmlRelationshipRepository.class);

        Memoria() {
            when(classes.findByDiagramIdAndDeletedAtIsNullOrderByNameAsc(diagramId)).thenReturn(clases);
            when(attributes.findByClassIdInOrderByOrdinalAsc(any())).thenReturn(atributos);
            when(methods.findByClassIdInOrderByOrdinalAsc(any())).thenReturn(List.of());
            when(relationships.findByDiagramIdAndDeletedAtIsNull(diagramId)).thenReturn(relaciones);
        }

        UmlClass clase(UUID id, String nombre) {
            UmlClass c = new UmlClass();
            c.setId(id);
            c.setDiagramId(diagramId);
            c.setName(nombre);
            clases.add(c);
            porNombre.put(nombre, c);
            return c;
        }

        void atributo(UUID classId, String nombre, String tipo, boolean pk, boolean required, boolean unique) {
            UmlAttribute a = new UmlAttribute();
            a.setId(UUID.randomUUID());
            a.setClassId(classId);
            a.setName(nombre);
            a.setDataType(tipo);
            a.setPk(pk);
            a.setRequired(required);
            a.setUnique(unique);
            a.setOrdinal((int) atributos.stream().filter(x -> x.getClassId().equals(classId)).count());
            atributos.add(a);
        }

        void relacion(UUID s, UUID t, RelationKind kind, String ms, String mt, String rolS, String rolT) {
            UmlRelationship r = new UmlRelationship();
            r.setId(UUID.randomUUID());
            r.setDiagramId(diagramId);
            r.setSourceClassId(s);
            r.setTargetClassId(t);
            r.setKind(kind);
            r.setSourceMultiplicity(ms);
            r.setTargetMultiplicity(mt);
            r.setSourceRole(rolS);
            r.setTargetRole(rolT);
            relaciones.add(r);
        }

        /** Lo mismo que haria el bus al confirmar la propuesta, solo para los tipos que produce Disenar. */
        void aplicar(PropuestaDeIA propuesta) {
            for (PropuestaDeIA.ComandoAceptado c : propuesta.aceptados()) {
                JsonNode p = c.payload();
                if (c.tipo() == CommandType.CREATE_CLASS) {
                    clase(UUID.fromString(p.path("classId").asText()), p.path("name").asText());
                } else if (c.tipo() == CommandType.ADD_ATTRIBUTE) {
                    atributo(UUID.fromString(p.path("classId").asText()), p.path("name").asText(),
                            p.path("dataType").asText(), p.path("pk").asBoolean(), p.path("required").asBoolean(),
                            p.path("unique").asBoolean());
                } else if (c.tipo() == CommandType.CREATE_RELATION) {
                    relacion(UUID.fromString(p.path("sourceClassId").asText()),
                            UUID.fromString(p.path("targetClassId").asText()),
                            RelationKind.valueOf(p.path("kind").asText()), texto(p, "sourceMultiplicity"),
                            texto(p, "targetMultiplicity"), texto(p, "sourceRole"), texto(p, "targetRole"));
                }
            }
        }

        private static String texto(JsonNode p, String campo) {
            return p.hasNonNull(campo) ? p.path(campo).asText() : null;
        }
    }

    private ProveedorDeIA ollama() {
        return new ProveedorOllama("http://localhost:11434", 8192, 0.1, 180_000, mapper, new CatalogoModelosOllama(modelo));
    }

    private String requisito(String dominio) throws Exception {
        return new String(getClass().getResourceAsStream("/evaluacion/" + dominio + ".txt").readAllBytes(),
                StandardCharsets.UTF_8);
    }

    @TestFactory
    List<DynamicTest> dominios() {
        Set<String> seleccion = new HashSet<>(Arrays.asList(
                System.getenv().getOrDefault("OLLAMA_DOMINIOS", String.join(",", DOMINIOS)).split(",")));
        return DOMINIOS.stream().filter(seleccion::contains)
                .map(d -> DynamicTest.dynamicTest(d, () -> disenarYRecomendar(d))).toList();
    }

    private void disenarYRecomendar(String dominio) throws Exception {
        Files.createDirectories(salida);
        Memoria m = new Memoria();
        ProveedorDeIA proveedor = ollama();
        ServicioDeIA servicioDeIA = new ServicioDeIA(proveedor, m.classes, m.attributes, m.methods, m.relationships);
        String requisito = requisito(dominio);

        long inicio = System.nanoTime();
        PropuestaDeIA diseno = servicioDeIA.generar(m.diagramId, requisito, Origin.TEXT, ModoIA.DISENAR, modelo);
        long segundosDiseno = (System.nanoTime() - inicio) / 1_000_000_000;
        m.aplicar(diseno);
        System.out.println(dominio + " DISENAR: " + diseno.aceptados().size() + " cambios en " + segundosDiseno + " s");

        var servicio = new ServicioDeRecomendaciones(proveedor, m.classes, m.attributes, m.relationships, servicioDeIA);
        evaluarRecomendaciones(dominio, servicio, m, requisito, Map.of("segundosDiseno", segundosDiseno,
                "diseno", diseno));
    }

    @Test
    void academicoConErroresConocidos() throws Exception {
        Files.createDirectories(salida);
        Memoria m = new Memoria();
        Map<String, UUID> ids = new HashMap<>();
        String[][] clases = {
                {"Carrera", "idCarrera:uuid:pk", "codigo:string", "nombre:string", "descripcion:text"},
                {"PlanAcademico", "id:uuid:pk", "nombreVersion:string", "fechaVigenciaInicio:date", "fechaVigenciaFin:date"},
                {"PeriodoAcademico", "id:uuid:pk", "nombre:string", "anio:int", "semestre:int"},
                {"Asignatura", "idAsignatura:uuid:pk", "codigo:string", "nombre:string", "descripcion:text",
                        "creditos:int", "cargaHoraria:int"},
                {"DetallePlanAsignatura", "id:uuid:pk", "nivel:int", "caracter:string"},
                {"Prerrequisito", "id:uuid:pk"},
                {"Docente", "id:uuid:pk", "nombre:string", "especialidad:string"},
                {"AsignacionDocente", "id:uuid:pk", "fechaInicio:date", "fechaFin:date"}};
        for (String[] c : clases) {
            UUID id = m.clase(UUID.randomUUID(), c[0]).getId();
            ids.put(c[0], id);
            for (int i = 1; i < c.length; i++) {
                String[] p = c[i].split(":");
                boolean pk = p.length > 2;
                m.atributo(id, p[0], p[1], pk, true, pk);
            }
        }
        String[][] relaciones = {
                {"Carrera", "1", "COMPOSITION", "0..*", "PlanAcademico"},
                {"PlanAcademico", "1", "ASSOCIATION", "0..*", "DetallePlanAsignatura"},
                {"DetallePlanAsignatura", "0..*", "ASSOCIATION", "1", "Asignatura"},
                {"DetallePlanAsignatura", "1", "ASSOCIATION", "0..*", "Prerrequisito"},
                {"Prerrequisito", "0..*", "ASSOCIATION", "1", "Asignatura"},
                {"DetallePlanAsignatura", "0..*", "ASSOCIATION", "1", "PeriodoAcademico"},
                {"AsignacionDocente", "0..*", "ASSOCIATION", "1", "DetallePlanAsignatura"},
                {"AsignacionDocente", "0..*", "ASSOCIATION", "1", "PeriodoAcademico"},
                {"AsignacionDocente", "0..*", "ASSOCIATION", "1", "Docente"}};
        for (String[] r : relaciones) m.relacion(ids.get(r[0]), ids.get(r[4]), RelationKind.valueOf(r[2]), r[1], r[3],
                null, null);

        ProveedorDeIA proveedor = ollama();
        ServicioDeIA servicioDeIA = new ServicioDeIA(proveedor, m.classes, m.attributes, m.methods, m.relationships);
        var servicio = new ServicioDeRecomendaciones(proveedor, m.classes, m.attributes, m.relationships, servicioDeIA);
        evaluarRecomendaciones("academico-errores-conocidos", servicio, m, requisito("academico"), Map.of());
    }

    private void evaluarRecomendaciones(String nombre, ServicioDeRecomendaciones servicio, Memoria m, String requisito,
                                        Map<String, Object> extra) throws Exception {
        long inicio = System.nanoTime();
        RecomendacionesIA recomendaciones = servicio.recomendar(m.diagramId, requisito, modelo);
        long segundos = (System.nanoTime() - inicio) / 1_000_000_000;
        Map<String, Object> resultado = new LinkedHashMap<>(extra);
        resultado.put("segundosRecomendar", segundos);
        resultado.put("recomendaciones", recomendaciones);
        System.out.println(nombre + " RECOMENDAR: " + recomendaciones.resumen() + " (" + segundos + " s)");

        if ("1".equals(System.getenv("OLLAMA_RECOMENDAR_APLICAR")) && !recomendaciones.recomendaciones().isEmpty()) {
            inicio = System.nanoTime();
            PropuestaDeIA propuesta = servicio.proponerAplicacion(m.diagramId, recomendaciones.recomendaciones(),
                    Origin.TEXT, modelo);
            resultado.put("segundosAplicar", (System.nanoTime() - inicio) / 1_000_000_000);
            resultado.put("propuestaAplicar", propuesta);
            System.out.println(nombre + " APLICAR: " + propuesta.aceptados().size() + " aceptados, "
                    + propuesta.rechazados().size() + " rechazados");
        }
        mapper.writeValue(salida.resolve("recomendar-" + nombre + ".json").toFile(), resultado);
        assertThat(recomendaciones.verificaciones()).isNotEmpty();
    }
}
