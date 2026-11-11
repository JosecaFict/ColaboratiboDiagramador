package com.uagrm.colabmodel.modelcore.mapping;

import com.uagrm.colabmodel.codegen.GeneradorSpringBoot;
import com.uagrm.colabmodel.modelcore.metamodel.RelationKind;
import com.uagrm.colabmodel.modelcore.metamodel.UmlClass;
import com.uagrm.colabmodel.modelcore.metamodel.UmlRelationship;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Una interfaz es un contrato, no una entidad: no genera tabla, y su realizacion
 * no genera FK (D-15). Sin esto, una REALIZATION caeria en el camino de las
 * asociaciones y el esquema ganaria una columna que no existe en el dominio.
 */
class InterfacesTest {

    private final List<UmlClass> clases = new ArrayList<>();
    private final List<UmlRelationship> relaciones = new ArrayList<>();

    private UmlClass clase(String nombre, String estereotipo) {
        UmlClass c = new UmlClass();
        c.setId(UUID.randomUUID());
        c.setDiagramId(UUID.randomUUID());
        c.setName(nombre);
        c.setStereotype(estereotipo);
        clases.add(c);
        return c;
    }

    private void relacion(RelationKind tipo, UmlClass origen, UmlClass destino) {
        UmlRelationship r = new UmlRelationship();
        r.setId(UUID.randomUUID());
        r.setDiagramId(origen.getDiagramId());
        r.setSourceClassId(origen.getId());
        r.setTargetClassId(destino.getId());
        r.setKind(tipo);
        r.setSourceMultiplicity("1");
        r.setTargetMultiplicity("0..*");
        relaciones.add(r);
    }

    private ModeloLogico derivar() {
        return DerivadorLogico.derivar(clases, List.of(), relaciones);
    }

    @Test
    void la_interfaz_no_genera_tabla_pero_la_clase_que_la_realiza_si() {
        UmlClass repo = clase("Repositorio", "interface");
        UmlClass impl = clase("RepoLibros", null);
        relacion(RelationKind.REALIZATION, impl, repo);

        assertThat(derivar().tablas()).extracting(ModeloLogico.Tabla::nombre)
                .contains("repo_libros").doesNotContain("repositorio");
    }

    @Test
    void la_realizacion_no_genera_clave_foranea_y_avisa_que_no_genera_nada() {
        UmlClass repo = clase("Repositorio", "interface");
        UmlClass impl = clase("RepoLibros", null);
        relacion(RelationKind.REALIZATION, impl, repo);

        ModeloLogico m = derivar();

        assertThat(m.tablas().stream().flatMap(t -> t.columnas().stream())
                .filter(c -> c.referencia() != null)).isEmpty();
        assertThat(m.advertencias()).anyMatch(a -> a.contains("realizacion") && a.contains("no genera nada"));
    }

    @Test
    void una_asociacion_que_toca_una_interfaz_se_ignora_con_aviso_como_la_de_una_enumeracion() {
        UmlClass repo = clase("Repositorio", "interface");
        UmlClass libro = clase("Libro", null);
        relacion(RelationKind.ASSOCIATION, libro, repo);

        ModeloLogico m = derivar();

        assertThat(m.tablas().stream().flatMap(t -> t.columnas().stream())
                .filter(c -> c.referencia() != null)).isEmpty();
        assertThat(m.advertencias()).anyMatch(a -> a.contains("toca una interfaz"));
    }

    @Test
    void el_generador_no_emite_nada_para_la_interfaz_en_postgres_ni_en_mongo_y_si_para_su_clase() {
        UmlClass repo = clase("Repositorio", "interface");
        UmlClass impl = clase("RepoLibros", null);
        relacion(RelationKind.REALIZATION, impl, repo);

        for (GeneradorSpringBoot.Almacen almacen : GeneradorSpringBoot.Almacen.values()) {
            var proyecto = GeneradorSpringBoot.generar(
                    GeneradorSpringBoot.Opciones.por("Demo", almacen), clases, List.of(), List.of(), relaciones,
                    Decisiones.ninguna());

            assertThat(proyecto.archivos().keySet()).as(almacen.name())
                    .anyMatch(r -> r.contains("RepoLibros")).noneMatch(r -> r.contains("Repositorio"));
        }
    }

    @Test
    void una_clase_comun_con_otro_estereotipo_sigue_generando_tabla() {
        clase("Libro", "entity");
        clase("Interfaz", "interfaces");

        assertThat(derivar().tablas()).extracting(ModeloLogico.Tabla::nombre).contains("libro", "interfaz");
    }

    @Test
    void el_estereotipo_se_compara_sin_mayusculas_ni_espacios() {
        assertThat(Interfaces.esEstereotipo(" Interface ")).isTrue();
        assertThat(Interfaces.esEstereotipo("interface")).isTrue();
        assertThat(Interfaces.esEstereotipo("enumeration")).isFalse();
        assertThat(Interfaces.esEstereotipo(null)).isFalse();
    }
}
