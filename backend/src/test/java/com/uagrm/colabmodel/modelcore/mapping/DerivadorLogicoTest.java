package com.uagrm.colabmodel.modelcore.mapping;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La Vista Logica contra los 7 modelos canonicos de regresion.
 *
 * Cada caso comprueba lo que el propio archivo declara en su campo
 * "esperado". Los dos ultimos son los que el README marca como los que mas
 * probablemente aporte el docente el dia de la presentacion.
 */
class DerivadorLogicoTest {

    @Test
    void un_uuid_tecnico_del_modelo_es_la_misma_pk_fisica_y_no_una_columna_duplicada() {
        var clase = new com.uagrm.colabmodel.modelcore.metamodel.UmlClass();
        clase.setId(UUID.randomUUID());
        clase.setName("Obra");

        var id = new com.uagrm.colabmodel.modelcore.metamodel.UmlAttribute();
        id.setId(UUID.randomUUID());
        id.setClassId(clase.getId());
        id.setName("idObra");
        id.setDataType("uuid");
        id.setPk(true);
        id.setRequired(true);
        id.setUnique(true);

        ModeloLogico m = DerivadorLogico.derivar(List.of(clase), List.of(id), List.of());

        ModeloLogico.Tabla obra = tabla(m, "obra").orElseThrow();
        assertThat(obra.columnas()).extracting(ModeloLogico.Columna::nombre).containsExactly("id");
        assertThat(obra.columnas().getFirst().atributoId()).isEqualTo(id.getId());
        assertThat(m.advertencias()).noneMatch(a -> a.contains("clave natural"));
    }

    @Test
    void uno_a_uno_pone_la_fk_unica_en_el_lado_dependiente() {
        ModeloLogico m = ModeloCanonico.derivar("01-uno-a-uno.json");

        ModeloLogico.Columna fk = columna(m, "perfil", "usuario_id").orElseThrow();
        assertThat(fk.unique()).isTrue();
        assertThat(fk.nullable()).isFalse();
        assertThat(fk.referencia()).isEqualTo("usuario(id)");
    }

    @Test
    void uno_a_muchos_pone_la_fk_en_el_lado_muchos() {
        ModeloLogico m = ModeloCanonico.derivar("02-uno-a-muchos.json");

        assertThat(columna(m, "pedido", "cliente_id")).isPresent();
        assertThat(columna(m, "pedido", "cliente_id").orElseThrow().nullable()).isFalse();
        // La FK va en "pedido", nunca al reves.
        assertThat(columna(m, "cliente", "pedido_id")).isEmpty();
    }

    @Test
    void muchos_a_muchos_genera_tabla_puente() {
        ModeloLogico m = ModeloCanonico.derivar("03-muchos-a-muchos.json");

        ModeloLogico.Tabla puente = tabla(m, "estudiante_materia").orElseThrow();
        assertThat(puente.columnas()).extracting(ModeloLogico.Columna::nombre)
                .containsExactly("estudiante_id", "materia_id");
        assertThat(puente.columnas()).allMatch(ModeloLogico.Columna::pk);   // PK compuesta
    }

    @Test
    void herencia_joined_comparte_la_pk_y_la_clase_abstracta_igual_genera_tabla() {
        ModeloLogico m = ModeloCanonico.derivar("04-herencia.json");

        assertThat(m.tablas()).extracting(ModeloLogico.Tabla::nombre)
                .contains("persona", "empleado", "cliente");

        for (String hija : List.of("empleado", "cliente")) {
            ModeloLogico.Columna id = columna(m, hija, "id").orElseThrow();
            assertThat(id.pk()).isTrue();
            assertThat(id.referencia()).isEqualTo("persona(id)");
        }
    }

    @Test
    void la_clase_asociativa_tiene_id_propio_y_dos_fk() {
        ModeloLogico m = ModeloCanonico.derivar("05-clase-asociativa.json");

        ModeloLogico.Tabla inscripcion = tabla(m, "inscripcion").orElseThrow();
        assertThat(inscripcion.columnas()).extracting(ModeloLogico.Columna::nombre)
                .contains("id", "estudiante_id", "materia_id", "fecha", "nota");
        // Lectura OO (A-4): id surrogado propio, no PK compuesta.
        assertThat(columna(m, "inscripcion", "id").orElseThrow().pk()).isTrue();
        assertThat(columna(m, "inscripcion", "estudiante_id").orElseThrow().pk()).isFalse();
    }

    @Test
    void los_nombres_hostiles_dan_identificadores_validos() {
        ModeloLogico m = ModeloCanonico.derivar("06-nombres-acentos.json");

        // Enie transliterada a "ni": "ano" no es una opcion.
        assertThat(m.tablas()).extracting(ModeloLogico.Tabla::nombre)
                .contains("anio_academico", "ninio", "pez");
        // Regla 2: no se pluraliza. "pezs" seria el error clasico.
        assertThat(m.tablas()).extracting(ModeloLogico.Tabla::nombre).doesNotContain("pezs");

        assertThat(columna(m, "anio_academico", "descripcion")).isPresent();
        // Palabras reservadas de PostgreSQL, desactivadas con sufijo.
        assertThat(columna(m, "ninio", "user_")).isPresent();
        assertThat(columna(m, "ninio", "group_")).isPresent();
    }

    @Test
    void la_relacion_reflexiva_y_la_doble_salen_del_ROL_y_no_de_la_clase() {
        ModeloLogico m = ModeloCanonico.derivar("07-reflexiva-doble.json");

        // Auto-relacion: el jefe es un empleado.
        ModeloLogico.Columna jefe = columna(m, "empleado", "jefe_id").orElseThrow();
        assertThat(jefe.referencia()).isEqualTo("empleado(id)");
        assertThat(jefe.nullable()).isTrue();   // 0..1

        // Dos relaciones entre Vuelo y Aeropuerto: sin roles serian dos
        // columnas "aeropuerto_id" y el esquema no compilaria.
        assertThat(columna(m, "vuelo", "origen_id")).isPresent();
        assertThat(columna(m, "vuelo", "destino_id")).isPresent();
        assertThat(columna(m, "vuelo", "aeropuerto_id")).isEmpty();
    }

    @Test
    void una_clave_natural_se_degrada_a_unique_y_se_avisa() {
        ModeloLogico m = ModeloCanonico.derivar("03-muchos-a-muchos.json");

        // Regla 1: el usuario marco "registro" como PK; la PK real es el
        // surrogado y la clave natural queda como UNIQUE NOT NULL.
        assertThat(columna(m, "estudiante", "id").orElseThrow().pk()).isTrue();
        ModeloLogico.Columna registro = columna(m, "estudiante", "registro").orElseThrow();
        assertThat(registro.pk()).isFalse();
        assertThat(registro.unique()).isTrue();
        assertThat(registro.nullable()).isFalse();
        assertThat(m.advertencias()).anyMatch(a -> a.contains("clave natural"));
    }

    private static Optional<ModeloLogico.Tabla> tabla(ModeloLogico m, String nombre) {
        return m.tablas().stream().filter(t -> t.nombre().equals(nombre)).findFirst();
    }

    private static Optional<ModeloLogico.Columna> columna(ModeloLogico m, String tabla, String columna) {
        return tabla(m, tabla).flatMap(t -> t.columnas().stream()
                .filter(c -> c.nombre().equals(columna)).findFirst());
    }
}
