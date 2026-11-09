package com.uagrm.colabmodel.codegen;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Compila y ejecuta las utilidades emitidas, incluido su manejo HTTP de errores. Sin base ni servidor. */
class ProduccionBordesTest {
    @TempDir static Path raiz;
    static URLClassLoader generado;
    static MockMvc mvc;

    @BeforeAll
    static void compilarYPreparar() throws Exception {
        var fuentes = PlantillasProduccion.comunes("prueba.generada", "prueba/generada");
        fuentes.put("prueba/generada/SondaProduccion.java", """
                package prueba.generada;
                import prueba.generada.service.Paginas;
                import org.springframework.web.bind.annotation.*;
                import java.util.Map;
                import java.util.Set;
                @RestController
                public class SondaProduccion {
                    @GetMapping("/sonda")
                    public Map<String, Object> consultar(
                            @RequestParam(name="sort", required=false) String sort,
                            @RequestParam(name="page", defaultValue="0") int page,
                            @RequestParam(name="size", required=false) Integer size) {
                        var orden = Paginas.orden(sort, Set.of("nombre", "id"));
                        var pagina = Paginas.pagina(page, size, orden);
                        return Map.of("orden", orden.toString(), "size", pagina.getPageSize(),
                                "offset", pagina.getOffset());
                    }
                }
                """);
        var salida = Files.createDirectories(raiz.resolve("clases"));
        var args = new ArrayList<>(List.of("-classpath", System.getProperty("java.class.path"),
                "-encoding", "UTF-8", "-d", salida.toString()));
        for (var fuente : fuentes.entrySet()) {
            var ruta = raiz.resolve(fuente.getKey());
            Files.createDirectories(ruta.getParent());
            Files.writeString(ruta, fuente.getValue(), StandardCharsets.UTF_8);
            args.add(ruta.toString());
        }
        var javac = ToolProvider.getSystemJavaCompiler();
        assertThat(javac).as("Las pruebas del generador necesitan JDK, no solo JRE").isNotNull();
        var errores = new ByteArrayOutputStream();
        assertThat(javac.run(null, null, errores, args.toArray(String[]::new)))
                .withFailMessage("Codigo generado invalido: %s", errores.toString(StandardCharsets.UTF_8))
                .isZero();
        generado = new URLClassLoader(new URL[]{salida.toUri().toURL()}, ProduccionBordesTest.class.getClassLoader());
        var controlador = generado.loadClass("prueba.generada.SondaProduccion").getConstructor().newInstance();
        var manejador = generado.loadClass("prueba.generada.web.ManejadorDeErrores").getConstructor().newInstance();
        // El classpath de P1 incluye XML por XMI; solicitar el contrato JSON
        // explicitamente evita negociar XML en esta sonda de P2.
        mvc = MockMvcBuilders.standaloneSetup(controlador).setControllerAdvice(manejador)
                .defaultRequest(get("/").accept(MediaType.APPLICATION_JSON)).build();
    }

    @AfterAll
    static void cerrar() throws Exception {
        if (generado != null) generado.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {",", ",,", ",asc", "nombre,", "nombre,asc,desc", "nombre,,",
            "nombre,asc,", "nombre,   ", "nombre,aleatorio", "inexistente", " ,desc"})
    void sort_invalido_devuelve_400_con_el_campo_sin_ocultar_componentes(String sort) throws Exception {
        mvc.perform(get("/sonda").param("sort", sort))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isString())
                .andExpect(jsonPath("$.errores.sort").isString());
    }

    @Test
    void conserva_orden_opcional_y_ascendente_por_defecto() throws Exception {
        mvc.perform(get("/sonda")).andExpect(status().isOk())
                .andExpect(jsonPath("$.orden").value("UNSORTED"))
                .andExpect(jsonPath("$.size").value(20));
        mvc.perform(get("/sonda").param("sort", "nombre")).andExpect(status().isOk())
                .andExpect(jsonPath("$.orden").value("nombre: ASC"));
        mvc.perform(get("/sonda").param("sort", " nombre , DeSc ")).andExpect(status().isOk())
                .andExpect(jsonPath("$.orden").value("nombre: DESC"));
    }

    @Test
    void valida_limites_de_pagina_y_tamanio() throws Exception {
        mvc.perform(get("/sonda").param("page", "-1")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores.page").isString());
        for (String size : List.of("0", "-1", "501")) {
            mvc.perform(get("/sonda").param("size", size)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errores.size").isString());
        }
        mvc.perform(get("/sonda").param("size", "500").param("page", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.offset").value(1000));
    }
}
