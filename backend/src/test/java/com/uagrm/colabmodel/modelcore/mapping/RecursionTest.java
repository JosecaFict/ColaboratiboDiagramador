package com.uagrm.colabmodel.modelcore.mapping;

import com.uagrm.colabmodel.modelcore.metamodel.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Relaciones de una clase CONSIGO MISMA.
 *
 * La 1:N reflexiva ya la cubre el modelo canonico 07 (Empleado/jefe). Lo que
 * se prueba aca es lo que el canonico no toca: la N:M reflexiva, que generaba
 * dos columnas con el mismo nombre —o sea un esquema que no compila—, y la
 * auto-herencia, que no significa nada.
 */
class RecursionTest {

    private final UUID diagrama = UUID.randomUUID();
    private final UmlClass empleado = clase("Empleado");

    @Test
    void la_reflexiva_uno_a_muchos_apunta_a_su_propia_tabla() {
        ModeloLogico m = derivar(relacion(RelationKind.ASSOCIATION,
                "0..*", "0..1", "subordinados", "jefe"));

        ModeloLogico.Columna fk = columna(m, "empleado", "jefe_id");
        assertThat(fk.referencia()).isEqualTo("empleado(id)");
        assertThat(fk.nullable()).isTrue();
    }

    @Test
    void la_reflexiva_muchos_a_muchos_distingue_los_extremos_por_el_rol() {
        ModeloLogico m = derivar(relacion(RelationKind.ASSOCIATION,
                "0..*", "0..*", "amigos", "amigoDe"));

        ModeloLogico.Tabla puente = tabla(m, "empleado_amigo_de");
        // Sin esto salian "empleado_id" y "empleado_id": no compila.
        assertThat(puente.columnas()).extracting(ModeloLogico.Columna::nombre)
                .containsExactly("amigos_id", "amigo_de_id")
                .doesNotHaveDuplicates();
        assertThat(puente.columnas()).allMatch(c -> "empleado(id)".equals(c.referencia()));
    }

    @Test
    void una_reflexiva_n_a_n_sin_roles_se_desambigua_y_se_avisa() {
        ModeloLogico m = derivar(relacion(RelationKind.ASSOCIATION, "0..*", "0..*", null, null));

        ModeloLogico.Tabla puente = m.tablas().stream()
                .filter(t -> t.columnas().size() == 2 && !t.nombre().equals("empleado"))
                .findFirst().orElseThrow();
        assertThat(puente.columnas()).extracting(ModeloLogico.Columna::nombre).doesNotHaveDuplicates();
        assertThat(m.advertencias()).anyMatch(a -> a.contains("ROLES"));
    }

    @Test
    void una_clase_no_puede_heredar_de_si_misma() {
        ModeloLogico m = derivar(relacion(RelationKind.GENERALIZATION, null, null, null, null));

        // Antes emitia "empleado.id REFERENCES empleado(id)", que no dice nada.
        assertThat(columna(m, "empleado", "id").referencia()).isNull();
        assertThat(m.advertencias()).anyMatch(a -> a.contains("heredar de si misma"));
    }

    @Test
    void la_composicion_reflexiva_sigue_arrastrando_a_la_parte() {
        ModeloLogico m = derivar(relacion(RelationKind.COMPOSITION,
                "1", "0..*", "padre", "hijos"));

        assertThat(columna(m, "empleado", "padre_id").onDelete()).isEqualTo("CASCADE");
    }

    // ------------------------------------------------------------- APOYO

    private ModeloLogico derivar(UmlRelationship r) {
        return DerivadorLogico.derivar(List.of(empleado), List.of(), List.of(r));
    }

    private static ModeloLogico.Tabla tabla(ModeloLogico m, String nombre) {
        return m.tablas().stream().filter(t -> t.nombre().equals(nombre)).findFirst().orElseThrow();
    }

    private static ModeloLogico.Columna columna(ModeloLogico m, String tabla, String columna) {
        return tabla(m, tabla).columnas().stream()
                .filter(c -> c.nombre().equals(columna)).findFirst().orElseThrow();
    }

    private UmlClass clase(String nombre) {
        UmlClass c = new UmlClass();
        c.setId(UUID.randomUUID());
        c.setDiagramId(diagrama);
        c.setName(nombre);
        return c;
    }

    private UmlRelationship relacion(RelationKind kind, String multOrigen, String multDestino,
                                     String rolOrigen, String rolDestino) {
        UmlRelationship r = new UmlRelationship();
        r.setId(UUID.randomUUID());
        r.setDiagramId(diagrama);
        r.setSourceClassId(empleado.getId());
        r.setTargetClassId(empleado.getId());     // consigo misma
        r.setKind(kind);
        r.setSourceMultiplicity(multOrigen);
        r.setTargetMultiplicity(multDestino);
        r.setSourceRole(rolOrigen);
        r.setTargetRole(rolDestino);
        return r;
    }
}
