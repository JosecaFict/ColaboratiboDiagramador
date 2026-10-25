package com.uagrm.colabmodel.modelcore.mapping;

import com.uagrm.colabmodel.modelcore.metamodel.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Las reglas que vienen de la vista Chen y que los 7 modelos canonicos NO
 * ejercitan: entidad debil, multivaluado, derivado y n-arias.
 *
 * Es justo lo que vuelve verificable la 2FN: con claves surrogadas se cumple
 * por construccion, y es el modelo Chen el que introduce claves compuestas
 * reales (ver la nota de honestidad en docs/REGLAS-MAPEO.md).
 */
class ReglasErTest {

    private final UUID diagrama = UUID.randomUUID();

    @Test
    void la_entidad_debil_hereda_la_pk_del_duenio_mas_su_clave_parcial() {
        UmlClass libro = clase("Libro", false);
        UmlClass ejemplar = clase("Ejemplar", true);
        UmlAttribute nroCopia = atributo(ejemplar, "nroCopia", "int");
        nroCopia.setPartialKey(true);

        UmlRelationship tiene = relacion(libro, ejemplar, RelationKind.COMPOSITION);
        tiene.setIdentifying(true);
        tiene.setSourceMultiplicity("1");
        tiene.setTargetMultiplicity("1..*");

        ModeloLogico m = DerivadorLogico.derivar(List.of(libro, ejemplar), List.of(nroCopia), List.of(tiene));

        ModeloLogico.Tabla t = tabla(m, "ejemplar");
        // PK compuesta: FK al duenio + clave parcial. Sin surrogado (Regla 11).
        assertThat(t.columnas()).filteredOn(ModeloLogico.Columna::pk)
                .extracting(ModeloLogico.Columna::nombre)
                .containsExactly("libro_id", "nro_copia");
        assertThat(t.columnas()).extracting(ModeloLogico.Columna::nombre).doesNotContain("id");
    }

    @Test
    void una_entidad_debil_sin_relacion_identificadora_se_denuncia() {
        UmlClass huerfana = clase("Ejemplar", true);

        ModeloLogico m = DerivadorLogico.derivar(List.of(huerfana), List.of(), List.of());

        assertThat(m.advertencias()).anyMatch(a -> a.contains("identificadora"));
    }

    @Test
    void el_atributo_multivaluado_sale_a_su_propia_tabla() {
        UmlClass socio = clase("Socio", false);
        UmlAttribute telefono = atributo(socio, "telefono", "string");
        telefono.setMultivalued(true);

        ModeloLogico m = DerivadorLogico.derivar(List.of(socio), List.of(telefono), List.of());

        // 1FN: no es una opcion (Regla 12).
        assertThat(tabla(m, "socio").columnas()).extracting(ModeloLogico.Columna::nombre)
                .doesNotContain("telefono");
        ModeloLogico.Tabla propia = tabla(m, "socio_telefono");
        assertThat(propia.columnas()).extracting(ModeloLogico.Columna::nombre)
                .containsExactly("socio_id", "telefono");
        assertThat(propia.columnas()).allMatch(ModeloLogico.Columna::pk);
    }

    @Test
    void el_atributo_derivado_no_se_persiste_pero_se_avisa() {
        UmlClass socio = clase("Socio", false);
        UmlAttribute edad = atributo(socio, "edad", "int");
        edad.setDerived(true);

        ModeloLogico m = DerivadorLogico.derivar(List.of(socio), List.of(edad), List.of());

        assertThat(tabla(m, "socio").columnas()).extracting(ModeloLogico.Columna::nombre)
                .doesNotContain("edad");
        assertThat(m.advertencias()).anyMatch(a -> a.contains("derivado"));
    }

    @Test
    void una_relacion_n_aria_se_rechaza_en_vez_de_ignorarse() {
        UmlClass a = clase("A", false);
        UmlClass b = clase("B", false);
        UmlRelationship naria = relacion(a, b, RelationKind.ASSOCIATION);
        naria.setDegree(3);

        ModeloLogico m = DerivadorLogico.derivar(List.of(a, b), List.of(), List.of(naria));

        // Regla 10: nunca en silencio.
        assertThat(m.advertencias()).anyMatch(x -> x.contains("n-aria"));
        assertThat(tabla(m, "a").columnas()).extracting(ModeloLogico.Columna::nombre).containsExactly("id");
    }

    @Test
    void una_relacion_sin_multiplicidades_asume_un_default_y_lo_declara() {
        UmlClass a = clase("Autor", false);
        UmlClass b = clase("Libro", false);

        ModeloLogico m = DerivadorLogico.derivar(List.of(a, b), List.of(),
                List.of(relacion(a, b, RelationKind.ASSOCIATION)));

        // Es lo que dibuja la barra de herramientas hoy: sin multiplicidades.
        assertThat(m.advertencias()).anyMatch(x -> x.contains("multiplicidades"));
        assertThat(tabla(m, "libro").columnas()).extracting(ModeloLogico.Columna::nombre)
                .contains("autor_id");
    }

    @Test
    void la_composicion_arrastra_a_la_parte_y_la_agregacion_la_suelta() {
        // Es la diferencia que el usuario dibuja con un rombo relleno o hueco.
        // Si las dos generaran la misma FK, ese dibujo no significaria nada.
        assertThat(fkDe(RelationKind.COMPOSITION, "0..*").onDelete()).isEqualTo("CASCADE");
        assertThat(fkDe(RelationKind.AGGREGATION, "0..*").onDelete()).isEqualTo("SET NULL");
        assertThat(fkDe(RelationKind.ASSOCIATION, "0..*").onDelete()).isEqualTo("RESTRICT");
    }

    @Test
    void la_composicion_obliga_la_fk_aunque_la_multiplicidad_diga_que_no() {
        ModeloLogico.Columna fk = fkDe(RelationKind.COMPOSITION, "0..*");

        assertThat(fk.nullable()).isFalse();   // la parte no existe sin el todo
    }

    @Test
    void una_agregacion_con_fk_obligatoria_no_puede_ser_set_null_y_lo_dice() {
        UmlClass todo = clase("Equipo", false);
        UmlClass parte = clase("Jugador", false);
        UmlRelationship r = relacion(todo, parte, RelationKind.AGGREGATION);
        r.setSourceMultiplicity("1");      // obligatoria: no se puede soltar
        r.setTargetMultiplicity("0..*");

        ModeloLogico m = DerivadorLogico.derivar(List.of(todo, parte), List.of(), List.of(r));

        assertThat(columna(m, "jugador", "equipo_id").onDelete()).isEqualTo("RESTRICT");
        assertThat(m.advertencias()).anyMatch(a -> a.contains("SET NULL"));
    }

    @Test
    void un_identificador_larguisimo_se_trunca_con_hash_para_no_colisionar() {
        String base = "nombre_completo_del_representante_legal_autorizado_por_la_institucion";
        String uno = Nombres.identificador(base + "_primero");
        String otro = Nombres.identificador(base + "_segundo");

        assertThat(uno).hasSizeLessThanOrEqualTo(63);
        assertThat(otro).hasSizeLessThanOrEqualTo(63);
        // Comparten prefijo: sin hash serian la misma columna y no compilaria.
        assertThat(uno).isNotEqualTo(otro);
    }

    private static ModeloLogico.Tabla tabla(ModeloLogico m, String nombre) {
        return m.tablas().stream().filter(t -> t.nombre().equals(nombre)).findFirst().orElseThrow();
    }

    private static ModeloLogico.Columna columna(ModeloLogico m, String tabla, String columna) {
        return tabla(m, tabla).columnas().stream()
                .filter(c -> c.nombre().equals(columna)).findFirst().orElseThrow();
    }

    /** Todo (1) --- parte (0..*): la FK queda del lado de la parte. */
    private ModeloLogico.Columna fkDe(RelationKind kind, String multiplicidadDestino) {
        UmlClass todo = clase("Equipo", false);
        UmlClass parte = clase("Jugador", false);
        UmlRelationship r = relacion(todo, parte, kind);
        r.setSourceMultiplicity("0..1");
        r.setTargetMultiplicity(multiplicidadDestino);

        ModeloLogico m = DerivadorLogico.derivar(List.of(todo, parte), List.of(), List.of(r));
        return columna(m, "jugador", "equipo_id");
    }

    private UmlClass clase(String nombre, boolean debil) {
        UmlClass c = new UmlClass();
        c.setId(UUID.randomUUID());
        c.setDiagramId(diagrama);
        c.setName(nombre);
        c.setWeak(debil);
        c.setErKind(debil ? ErKind.WEAK : ErKind.STRONG);
        return c;
    }

    private UmlAttribute atributo(UmlClass clase, String nombre, String tipo) {
        UmlAttribute a = new UmlAttribute();
        a.setId(UUID.randomUUID());
        a.setClassId(clase.getId());
        a.setName(nombre);
        a.setDataType(tipo);
        return a;
    }

    private UmlRelationship relacion(UmlClass origen, UmlClass destino, RelationKind kind) {
        UmlRelationship r = new UmlRelationship();
        r.setId(UUID.randomUUID());
        r.setDiagramId(diagrama);
        r.setSourceClassId(origen.getId());
        r.setTargetClassId(destino.getId());
        r.setKind(kind);
        return r;
    }
}
