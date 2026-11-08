package com.uagrm.colabmodel.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.uagrm.colabmodel.modelcore.command.Origin;
import com.uagrm.colabmodel.modelcore.metamodel.*;
import org.junit.jupiter.api.Test;
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
 * Evaluacion optativa del modo RECOMENDAR contra Ollama real. Carga un
 * diagrama con errores conocidos (la segunda corrida de Disenar del plan
 * academico, 2026-11-08), pide recomendaciones con el requisito original y,
 * con OLLAMA_RECOMENDAR_APLICAR=1, convierte las de gravedad ALTA en una
 * propuesta MODIFICAR. No escribe en ningun diagrama real.
 *
 * Salida en target/evaluaciones-ia/recomendar-academico*.json.
 */
@EnabledIfEnvironmentVariable(named = "OLLAMA_RECOMENDAR", matches = "1")
class RecomendarOllamaVivoTest {

    private final UUID diagramId = UUID.randomUUID();
    private final List<UmlClass> clases = new ArrayList<>();
    private final List<UmlAttribute> atributos = new ArrayList<>();
    private final List<UmlRelationship> relaciones = new ArrayList<>();
    private final Map<String, UmlClass> porNombre = new HashMap<>();

    /** Atributos como "nombre:tipo" o "nombre:tipo:pk". */
    private void clase(String nombre, String... campos) {
        UmlClass c = new UmlClass();
        c.setId(UUID.randomUUID());
        c.setDiagramId(diagramId);
        c.setName(nombre);
        clases.add(c);
        porNombre.put(nombre, c);
        int orden = 0;
        for (String campo : campos) {
            String[] p = campo.split(":");
            UmlAttribute a = new UmlAttribute();
            a.setId(UUID.randomUUID());
            a.setClassId(c.getId());
            a.setName(p[0]);
            a.setDataType(p[1]);
            a.setPk(p.length > 2 && p[2].equals("pk"));
            a.setRequired(true);
            a.setUnique(a.isPk());
            a.setOrdinal(orden++);
            atributos.add(a);
        }
    }

    private void relacion(String s, String ms, RelationKind kind, String mt, String t) {
        UmlRelationship r = new UmlRelationship();
        r.setId(UUID.randomUUID());
        r.setDiagramId(diagramId);
        r.setSourceClassId(porNombre.get(s).getId());
        r.setTargetClassId(porNombre.get(t).getId());
        r.setKind(kind);
        r.setSourceMultiplicity(ms);
        r.setTargetMultiplicity(mt);
        relaciones.add(r);
    }

    private void diagramaAcademicoSegundaCorrida() {
        clase("Carrera", "idCarrera:uuid:pk", "codigo:string", "nombre:string", "descripcion:text");
        clase("PlanAcademico", "id:uuid:pk", "nombreVersion:string", "fechaVigenciaInicio:date", "fechaVigenciaFin:date");
        clase("PeriodoAcademico", "id:uuid:pk", "nombre:string", "anio:int", "semestre:int");
        clase("Asignatura", "idAsignatura:uuid:pk", "codigo:string", "nombre:string", "descripcion:text",
                "creditos:int", "cargaHoraria:int");
        clase("DetallePlanAsignatura", "id:uuid:pk", "nivel:int", "caracter:string");
        clase("Prerrequisito", "id:uuid:pk");
        clase("Docente", "id:uuid:pk", "nombre:string", "especialidad:string");
        clase("AsignacionDocente", "id:uuid:pk", "fechaInicio:date", "fechaFin:date");
        relacion("Carrera", "1", RelationKind.COMPOSITION, "0..*", "PlanAcademico");
        relacion("PlanAcademico", "1", RelationKind.ASSOCIATION, "0..*", "DetallePlanAsignatura");
        relacion("DetallePlanAsignatura", "0..*", RelationKind.ASSOCIATION, "1", "Asignatura");
        relacion("DetallePlanAsignatura", "1", RelationKind.ASSOCIATION, "0..*", "Prerrequisito");
        relacion("Prerrequisito", "0..*", RelationKind.ASSOCIATION, "1", "Asignatura");
        relacion("DetallePlanAsignatura", "0..*", RelationKind.ASSOCIATION, "1", "PeriodoAcademico");
        relacion("AsignacionDocente", "0..*", RelationKind.ASSOCIATION, "1", "DetallePlanAsignatura");
        relacion("AsignacionDocente", "0..*", RelationKind.ASSOCIATION, "1", "PeriodoAcademico");
        relacion("AsignacionDocente", "0..*", RelationKind.ASSOCIATION, "1", "Docente");
    }

    @Test
    void academico() throws Exception {
        diagramaAcademicoSegundaCorrida();
        String modelo = System.getenv().getOrDefault("OLLAMA_MODEL", "qwen3.5:9b-q4_K_M");
        ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        ProveedorDeIA ollama = new ProveedorOllama("http://localhost:11434", 8192, 0.1, 180_000,
                mapper, new CatalogoModelosOllama(modelo));

        UmlClassRepository classes = mock(UmlClassRepository.class);
        when(classes.findByDiagramIdAndDeletedAtIsNullOrderByNameAsc(diagramId)).thenReturn(clases);
        UmlAttributeRepository attributes = mock(UmlAttributeRepository.class);
        when(attributes.findByClassIdInOrderByOrdinalAsc(any())).thenReturn(atributos);
        UmlMethodRepository methods = mock(UmlMethodRepository.class);
        when(methods.findByClassIdInOrderByOrdinalAsc(any())).thenReturn(List.of());
        UmlRelationshipRepository relationships = mock(UmlRelationshipRepository.class);
        when(relationships.findByDiagramIdAndDeletedAtIsNull(diagramId)).thenReturn(relaciones);
        ServicioDeIA servicioDeIA = new ServicioDeIA(ollama, classes, attributes, methods, relationships);
        var servicio = new ServicioDeRecomendaciones(ollama, classes, attributes, relationships, servicioDeIA);

        String requisito = new String(getClass().getResourceAsStream("/evaluacion/academico.txt").readAllBytes(),
                StandardCharsets.UTF_8);
        Path salida = Path.of("target", "evaluaciones-ia");
        Files.createDirectories(salida);

        long inicio = System.nanoTime();
        RecomendacionesIA recomendaciones = servicio.recomendar(diagramId, requisito, modelo);
        long segundos = (System.nanoTime() - inicio) / 1_000_000_000;
        mapper.writeValue(salida.resolve("recomendar-academico.json").toFile(),
                Map.of("segundos", segundos, "resultado", recomendaciones));
        System.out.println("RECOMENDAR academico: " + recomendaciones.recomendaciones().size()
                + " recomendaciones en " + segundos + " s");
        assertThat(recomendaciones.recomendaciones()).isNotEmpty();

        if ("1".equals(System.getenv("OLLAMA_RECOMENDAR_APLICAR"))) {
            var altas = recomendaciones.recomendaciones().stream().filter(r -> r.gravedad().equals("ALTA")).toList();
            inicio = System.nanoTime();
            PropuestaDeIA propuesta = servicio.proponerAplicacion(diagramId, altas.isEmpty()
                    ? recomendaciones.recomendaciones() : altas, Origin.TEXT, modelo);
            segundos = (System.nanoTime() - inicio) / 1_000_000_000;
            mapper.writeValue(salida.resolve("recomendar-academico-propuesta.json").toFile(),
                    Map.of("segundos", segundos, "propuesta", propuesta));
            System.out.println("APLICAR academico: " + propuesta.aceptados().size() + " aceptados, "
                    + propuesta.rechazados().size() + " rechazados en " + segundos + " s");
        }
    }
}
