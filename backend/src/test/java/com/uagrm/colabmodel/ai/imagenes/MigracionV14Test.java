package com.uagrm.colabmodel.ai.imagenes;

import jakarta.persistence.Column;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V14 contra las entidades, SIN base de datos: cada columna que mapea una entidad existe en la migracion.
 *
 * NO sustituye a `ddl-auto: validate` ni prueba la migracion contra PostgreSQL con datos previos: eso necesita
 * una base real y queda pendiente. Solo atrapa el error tonto (un nombre de columna distinto) sin ella.
 */
class MigracionV14Test {

    private static String v14() throws IOException {
        try (InputStream in = MigracionV14Test.class.getResourceAsStream("/db/migration/V14__chat_con_imagenes.sql")) {
            assertThat(in).as("V14 en el classpath").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
    }

    private static Set<String> columnasMapeadas(Class<?> entidad) {
        return Arrays.stream(entidad.getDeclaredFields()).map(f -> f.getAnnotation(Column.class))
                .filter(c -> c != null).map(Column::name).collect(Collectors.toSet());
    }

    /** Los nombres de columna del CREATE TABLE ai_images (la primera palabra de cada linea de columna). */
    private static Set<String> columnasDeLaTabla(String sql) {
        Matcher cuerpo = Pattern.compile("CREATE TABLE ai_images \\((.*?)\\n\\);", Pattern.DOTALL).matcher(sql);
        assertThat(cuerpo.find()).isTrue();
        return Arrays.stream(cuerpo.group(1).split("\n")).map(String::trim)
                .filter(l -> l.matches("[a-z_0-9]+\\s+(UUID|VARCHAR|INTEGER|BYTEA|TIMESTAMPTZ).*"))
                .map(l -> l.split("\\s+")[0]).collect(Collectors.toSet());
    }

    @Test
    void cada_columna_de_la_entidad_de_imagenes_existe_en_la_tabla() throws IOException {
        assertThat(columnasDeLaTabla(v14())).containsAll(columnasMapeadas(ImagenDelChat.class));
    }

    @Test
    void la_entidad_no_mapea_los_bytes_y_la_tabla_los_tiene() throws IOException {
        // Costura pendiente: los bytes los lee y escribe el almacen, nunca la entidad de metadatos.
        assertThat(columnasMapeadas(ImagenDelChat.class)).doesNotContain("data", "thumbnail");
        assertThat(columnasDeLaTabla(v14())).contains("data", "thumbnail");
    }

    @Test
    void la_entidad_del_turno_mapea_image_id_y_la_migracion_lo_agrega() throws Exception {
        assertThat(columnasMapeadas(Class.forName("com.uagrm.colabmodel.ai.InteraccionIA"))).contains("image_id");
        assertThat(v14()).contains("ALTER TABLE ai_interactions ADD COLUMN image_id UUID REFERENCES ai_images(id) ON DELETE SET NULL");
    }

    @Test
    void la_migracion_dice_lo_que_el_contrato_pide() throws IOException {
        String sql = v14();
        assertThat(sql).contains("REFERENCES diagrams(id) ON DELETE CASCADE")
                .contains("CREATE UNIQUE INDEX uq_ai_images_diagram_sha ON ai_images(diagram_id, sha256) WHERE deleted_at IS NULL")
                .contains("'CONSULTAR', 'REVISAR', 'DISENAR', 'MODIFICAR', 'IMAGEN'")
                .contains("sha256        VARCHAR(64)")      // no CHAR: con validate, bpchar no es varchar
                .doesNotContain(" CHAR(64)");
        List<String> chk = Arrays.stream(sql.split("\n")).filter(l -> l.contains("chk_ai_image_bytes")).toList();
        assertThat(chk).isNotEmpty();
    }

    @Test
    void la_entidad_de_imagenes_es_de_solo_lectura_para_jpa() {
        assertThat(ImagenDelChat.class.isAnnotationPresent(org.hibernate.annotations.Immutable.class)).isTrue();
        Field[] campos = ImagenDelChat.class.getDeclaredFields();
        assertThat(campos).extracting(Field::getName).doesNotContain("datos", "data", "thumbnail", "miniatura");
    }
}
