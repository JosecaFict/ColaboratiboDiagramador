package com.uagrm.colabmodel.codegen;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El empaquetado del proyecto generado.
 *
 * Es lo que viaja por HTTP cuando alguien aprieta "Generar Spring Boot", asi
 * que se verifica aca y no solo abriendo el zip a mano.
 */
class ProyectoGeneradoTest {

    private static ProyectoGenerado proyecto(String nombre) {
        Map<String, String> archivos = new LinkedHashMap<>();
        archivos.put("pom.xml", "<project/>");
        archivos.put("src/main/java/com/uagrm/generado/domain/Libro.java", "class Libro {}");
        return new ProyectoGenerado(nombre, archivos, List.of());
    }

    private static Map<String, String> abrir(byte[] zip) throws IOException {
        Map<String, String> salida = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                salida.put(e.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return salida;
    }

    @Test
    void todo_cuelga_de_una_carpeta_raiz() throws IOException {
        Map<String, String> dentro = abrir(proyecto("Diagrama Demo").comoZip());

        // Sin la carpeta raiz, descomprimir desparrama pom.xml y src/ encima
        // de lo que el usuario tuviera abierto.
        assertThat(dentro.keySet()).containsExactly(
                "diagrama-demo/pom.xml",
                "diagrama-demo/src/main/java/com/uagrm/generado/domain/Libro.java");
        assertThat(dentro.get("diagrama-demo/pom.xml")).isEqualTo("<project/>");
    }

    @Test
    void un_nombre_hostil_igual_da_una_carpeta_que_existe_en_cualquier_disco() {
        // Regla 8, la misma normalizacion que las tablas: enie antes que los
        // acentos, para que "Año" no termine siendo "ano".
        assertThat(proyecto("Diseño del Año").carpetaRaiz()).isEqualTo("disenio-del-anio");
    }

    @Test
    void un_nombre_que_se_normaliza_a_nada_no_deja_el_archivo_sin_nombre() {
        // La Regla 8 ya tenia respuesta para esto; no hacia falta inventar otra.
        assertThat(proyecto("   ").carpetaRaiz()).isEqualTo("sin-nombre");
    }

    @Test
    void el_mismo_modelo_da_el_mismo_zip_byte_a_byte() {
        // Si el zip llevara la hora de generacion, dos descargas del mismo
        // diagrama darian archivos distintos y no se podria sostener que el
        // artefacto sale del modelo y de nada mas.
        assertThat(proyecto("Diagrama Demo").comoZip())
                .isEqualTo(proyecto("Diagrama Demo").comoZip());
    }

    @Test
    void el_contenido_sobrevive_a_los_acentos() throws IOException {
        Map<String, String> archivos = new LinkedHashMap<>();
        archivos.put("README.md", "# Año académico — ñandú");
        Map<String, String> dentro = abrir(
                new ProyectoGenerado("Demo", archivos, List.of()).comoZip());

        assertThat(dentro.get("demo/README.md")).isEqualTo("# Año académico — ñandú");
    }
}
