package com.uagrm.colabmodel.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PlanUmlOllamaVivoTest {

    @Test
    @EnabledIfEnvironmentVariable(named = "OLLAMA_VIVO", matches = ".+")
    void propone_un_modelo_completo_en_una_llamada() {
        String modelo = System.getenv().getOrDefault("OLLAMA_MODEL", "qwen3.5:9b-q4_K_M");
        var proveedor = new ProveedorOllama("http://localhost:11434", 4096, 0.1, 300_000,
                new ObjectMapper(), new CatalogoModelosOllama(modelo));
        var solicitud = new SolicitudIA(
                "Disena una biblioteca con obras, copias fisicas, usuarios y prestamos historicos. "
                        + "Usa nombres en espanol y cardinalidades completas.",
                ModoIA.DISENAR, List.of(), List.of(), List.of(EsquemaPlanUml.herramienta()), modelo);

        RespuestaIA cruda = proveedor.generar(solicitud);
        assertThat(cruda.comandos()).singleElement()
                .satisfies(c -> assertThat(c.tipo()).isEqualTo(EsquemaPlanUml.NOMBRE));

        RespuestaIA expandida = ExpansorPlanUml.expandir(NormalizadorPlanUml.normalizar(cruda,
                "Biblioteca con prestamos historicos y devoluciones"));
        assertThat(expandida.comandos()).anyMatch(c -> c.tipo().equals("CREATE_CLASS"));
        assertThat(expandida.comandos()).anyMatch(c -> c.tipo().equals("ADD_ATTRIBUTE"));
        assertThat(expandida.comandos()).anyMatch(c -> c.tipo().equals("CREATE_RELATION"));
        System.out.println("Plan UML expandido: " + expandida.comandos().size() + " cambios");
        System.out.println(expandida.notaDelModelo());
    }
}
