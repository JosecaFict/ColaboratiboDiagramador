package com.uagrm.colabmodel.project;

import com.uagrm.colabmodel.codegen.ProyectoGenerado;
import com.uagrm.colabmodel.iam.AppUserRepository;
import com.uagrm.colabmodel.modelcore.metamodel.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.*;

class DiagramSnapshotServiceTest {

    private final UUID diagramId = UUID.randomUUID();
    private final UUID libroId = UUID.randomUUID();
    private final UUID socioId = UUID.randomUUID();

    private UmlClassRepository classes;
    private UmlAttributeRepository attributes;
    private UmlRelationshipRepository relationships;
    private UmlMethodRepository methods;
    private DiagramLayoutRepository layouts;
    private DiagramSnapshotService service;

    @BeforeEach
    void prepararDiagrama() {
        DiagramRepository diagrams = mock(DiagramRepository.class);
        classes = mock(UmlClassRepository.class);
        attributes = mock(UmlAttributeRepository.class);
        relationships = mock(UmlRelationshipRepository.class);
        methods = mock(UmlMethodRepository.class);
        layouts = mock(DiagramLayoutRepository.class);

        Diagram d = new Diagram();
        d.setId(diagramId);
        d.setName("Diagrama Demo");
        d.setCurrentSeq(7);
        when(diagrams.findById(diagramId)).thenReturn(Optional.of(d));

        service = new DiagramSnapshotService(diagrams, classes, attributes, methods,
                relationships, mock(ModelingDecisionRepository.class), layouts,
                mock(ConfirmacionDeEsquemaRepository.class), mock(AppUserRepository.class));
    }

    @Test
    void agrupa_cada_atributo_bajo_su_clase() {
        when(classes.findByDiagramIdAndDeletedAtIsNullOrderByNameAsc(diagramId))
                .thenReturn(List.of(clase(libroId, "Libro"), clase(socioId, "Socio")));
        when(attributes.findByClassIdInOrderByOrdinalAsc(anyCollection()))
                .thenReturn(List.of(atributo(libroId, "isbn", 0), atributo(libroId, "titulo", 1),
                        atributo(socioId, "carnet", 0)));

        DiagramSnapshot snapshot = service.de(diagramId).orElseThrow();

        assertThat(snapshot.classes()).hasSize(2);
        assertThat(snapshot.classes().get(0).attributes()).extracting(DiagramSnapshot.Atributo::name)
                .containsExactly("isbn", "titulo");
        assertThat(snapshot.classes().get(1).attributes()).extracting(DiagramSnapshot.Atributo::name)
                .containsExactly("carnet");
    }

    @Test
    void una_clase_sin_atributos_no_rompe_ni_queda_en_null() {
        when(classes.findByDiagramIdAndDeletedAtIsNullOrderByNameAsc(diagramId))
                .thenReturn(List.of(clase(libroId, "Libro")));
        when(attributes.findByClassIdInOrderByOrdinalAsc(anyCollection())).thenReturn(List.of());

        DiagramSnapshot snapshot = service.de(diagramId).orElseThrow();

        assertThat(snapshot.classes().get(0).attributes()).isEmpty();
    }

    @Test
    void un_diagrama_vacio_no_consulta_atributos() {
        when(classes.findByDiagramIdAndDeletedAtIsNullOrderByNameAsc(diagramId)).thenReturn(List.of());

        DiagramSnapshot snapshot = service.de(diagramId).orElseThrow();

        assertThat(snapshot.classes()).isEmpty();
        assertThat(snapshot.currentSeq()).isEqualTo(7);
        // Sin clases no hay nada que buscar: un IN () vacio es una consulta al pedo.
        verify(attributes, never()).findByClassIdInOrderByOrdinalAsc(anyCollection());
    }

    @Test
    void el_snapshot_lleva_la_geometria_de_las_entidades() {
        when(classes.findByDiagramIdAndDeletedAtIsNullOrderByNameAsc(diagramId))
                .thenReturn(List.of(clase(libroId, "Libro")));
        when(attributes.findByClassIdInOrderByOrdinalAsc(anyCollection())).thenReturn(List.of());
        DiagramLayout l = new DiagramLayout();
        l.setDiagramId(diagramId);
        l.setElementId(libroId);
        l.setX(180);
        l.setY(120);
        when(layouts.findByDiagramId(diagramId)).thenReturn(List.of(l));

        DiagramSnapshot snapshot = service.de(diagramId).orElseThrow();

        assertThat(snapshot.layout()).singleElement()
                .satisfies(geo -> {
                    assertThat(geo.elementId()).isEqualTo(libroId);
                    assertThat(geo.x()).isEqualTo(180);
                    assertThat(geo.y()).isEqualTo(120);
                });
    }

    @Test
    void un_diagrama_que_no_existe_no_devuelve_snapshot() {
        assertThat(service.de(UUID.randomUUID())).isEmpty();
    }

    @Test
    void el_codigo_generado_sale_del_mismo_modelo_que_el_snapshot() {
        when(classes.findByDiagramIdAndDeletedAtIsNullOrderByNameAsc(diagramId))
                .thenReturn(List.of(clase(libroId, "Libro"), clase(socioId, "Socio")));
        when(attributes.findByClassIdInOrderByOrdinalAsc(anyCollection()))
                .thenReturn(List.of(atributo(libroId, "titulo", 0), atributo(socioId, "carnet", 0)));

        ProyectoGenerado p = service.codigoDe(diagramId).orElseThrow();

        // Las mismas clases que ve el snapshot, con sus cinco capas.
        assertThat(p.archivos()).containsKeys(
                "src/main/java/com/uagrm/generado/domain/Libro.java",
                "src/main/java/com/uagrm/generado/repository/LibroRepository.java",
                "src/main/java/com/uagrm/generado/domain/Socio.java");
        // El zip se llama como el diagrama, no "generado" a secas.
        assertThat(p.nombre()).isEqualTo("Diagrama Demo");
        assertThat(p.carpetaRaiz()).isEqualTo("diagrama-demo");
    }

    @Test
    void un_diagrama_vacio_genera_el_andamiaje_pero_ninguna_entidad() {
        when(classes.findByDiagramIdAndDeletedAtIsNullOrderByNameAsc(diagramId)).thenReturn(List.of());

        ProyectoGenerado p = service.codigoDe(diagramId).orElseThrow();

        // Arranca igual: el usuario que aprieta el boton sin clases recibe un
        // proyecto valido y vacio, no un error ni un zip roto.
        assertThat(p.archivos()).containsKey("pom.xml");
        assertThat(p.archivos().keySet()).noneMatch(r -> r.contains("/domain/"));
    }

    @Test
    void un_diagrama_que_no_existe_no_devuelve_codigo() {
        assertThat(service.codigoDe(UUID.randomUUID())).isEmpty();
    }

    private UmlClass clase(UUID id, String nombre) {
        UmlClass c = new UmlClass();
        c.setId(id);
        c.setDiagramId(diagramId);
        c.setName(nombre);
        return c;
    }

    private UmlAttribute atributo(UUID classId, String nombre, int ordinal) {
        UmlAttribute a = new UmlAttribute();
        a.setId(UUID.randomUUID());
        a.setClassId(classId);
        a.setName(nombre);
        a.setOrdinal(ordinal);
        return a;
    }
}
