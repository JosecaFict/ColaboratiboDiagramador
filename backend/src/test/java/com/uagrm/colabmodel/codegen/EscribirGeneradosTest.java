package com.uagrm.colabmodel.codegen;

import com.uagrm.colabmodel.codegen.GeneradorSpringBoot.Almacen;
import com.uagrm.colabmodel.modelcore.mapping.ModeloCanonico;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Escribe a disco los proyectos generados de los 7 canonicos, la biblioteca y
 * la tienda, en las dos variantes, para correrles `mvn package` encima: es el
 * criterio de terminado de ADR-034/035 y no se puede probar desde aca.
 *
 * Solo corre a pedido:
 *   mvn test -Dtest=EscribirGeneradosTest -Dgenerados.salida=C:/tmp/generados
 */
@EnabledIfSystemProperty(named = "generados.salida", matches = ".+")
class EscribirGeneradosTest {

    private static final List<String> CANONICOS = List.of(
            "01-uno-a-uno.json", "02-uno-a-muchos.json", "03-muchos-a-muchos.json", "04-herencia.json",
            "05-clase-asociativa.json", "06-nombres-acentos.json", "07-reflexiva-doble.json");

    @Test
    void escribe_todos_los_proyectos() throws IOException {
        Path salida = Path.of(System.getProperty("generados.salida"));
        for (Almacen almacen : Almacen.values()) {
            String sufijo = almacen.name().toLowerCase();
            for (String canonico : CANONICOS) {
                ModeloCanonico.Cargado m = ModeloCanonico.cargar(canonico);
                String nombre = canonico.replace(".json", "");
                GeneradorSpringBoot.generar(GeneradorSpringBoot.Opciones.por("Canonico " + nombre, almacen),
                                m.clases(), m.atributos(), List.of(), m.relaciones())
                        .escribirEn(salida.resolve(sufijo).resolve(nombre));
            }
            ManifestDeAppTest.Biblioteca.generar(GeneradorSpringBoot.Opciones.por("Biblioteca", almacen))
                    .escribirEn(salida.resolve(sufijo).resolve("biblioteca"));
            ModelosDePrueba.tienda().generar(GeneradorSpringBoot.Opciones.por("Tienda", almacen))
                    .escribirEn(salida.resolve(sufijo).resolve("tienda"));
        }
        assertThat(salida.resolve("mongo/tienda/pom.xml")).exists();
    }
}
