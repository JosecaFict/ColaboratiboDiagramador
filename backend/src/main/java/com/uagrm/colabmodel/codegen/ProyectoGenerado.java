package com.uagrm.colabmodel.codegen;

import com.uagrm.colabmodel.modelcore.mapping.Nombres;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * El proyecto generado, todavia en memoria: ruta relativa -> contenido.
 *
 * Separar "generar" de "escribir" no es ceremonia: permite verificar lo que
 * sale sin tocar el disco, y que el dia de manana el artefacto se empaquete
 * en un zip o se mande por HTTP sin cambiar el generador.
 */
public record ProyectoGenerado(String nombre, Map<String, String> archivos, List<String> advertencias) {

    /**
     * Fecha fija para todas las entradas del zip. Sin esto, generar dos veces
     * el MISMO modelo da dos archivos distintos byte a byte, y entonces no se
     * puede afirmar en una defensa que el zip sale del modelo y de nada mas.
     * 2024 y no la epoca Unix porque el formato zip cuenta desde 1980.
     */
    private static final FileTime FECHA_FIJA = FileTime.from(Instant.parse("2024-01-01T00:00:00Z"));

    public void escribirEn(Path destino) throws IOException {
        for (Map.Entry<String, String> archivo : archivos.entrySet()) {
            Path salida = destino.resolve(archivo.getKey());
            Files.createDirectories(salida.getParent());
            Files.writeString(salida, archivo.getValue(), StandardCharsets.UTF_8);
        }
    }

    /**
     * El mismo proyecto, comprimido y con una carpeta raiz.
     *
     * La carpeta raiz no es cosmetica: sin ella, descomprimir desparrama
     * pom.xml y src/ sobre lo que sea que el usuario tuviera abierto.
     */
    public byte[] comoZip() {
        String raiz = carpetaRaiz();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, String> archivo : archivos.entrySet()) {
                ZipEntry entrada = new ZipEntry(raiz + "/" + archivo.getKey());
                entrada.setLastModifiedTime(FECHA_FIJA);
                zip.putNextEntry(entrada);
                zip.write(archivo.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        } catch (IOException e) {
            // Escribir en memoria no puede fallar por E/S: si falla, es un bug.
            throw new UncheckedIOException(e);
        }
        return buffer.toByteArray();
    }

    /**
     * Nombre de carpeta y de archivo derivado del nombre del diagrama.
     *
     * Reusa la normalizacion de la Regla 8 en lugar de inventar otra: un
     * diagrama llamado "Diseño Año" tiene que dar una carpeta que exista en
     * cualquier sistema de archivos, no "Diseño Año".
     */
    public String carpetaRaiz() {
        return Nombres.tabla(nombre).replace('_', '-');
    }
}
