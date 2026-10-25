package com.uagrm.colabmodel.ai;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/** Lista cerrada de modelos locales que la UI puede solicitar. */
@Component
public class CatalogoModelosOllama {

    public record Opcion(String id, String nombre, String descripcion, boolean recomendado, boolean think) { }

    private final String predeterminado;
    private final List<Opcion> opciones;

    public CatalogoModelosOllama(
            @Value("${colabmodel.ai.ollama.model:qwen3:8b}") String predeterminado) {
        this.predeterminado = predeterminado;
        this.opciones = List.of(
                new Opcion("qwen3:8b", "Qwen 3 8B", "Mas rapido para consultas y cambios simples", false, true),
                new Opcion("qwen3.5:9b-q4_K_M", "Qwen 3.5 9B", "Mayor calidad para disenar y revisar", true, false));
    }

    public String resolver(String solicitado) {
        String elegido = solicitado == null || solicitado.isBlank() ? predeterminado : solicitado;
        return opciones.stream().anyMatch(o -> o.id().equals(elegido)) ? elegido :
                error("El modelo local \"" + elegido + "\" no esta permitido");
    }

    public boolean usaThinking(String modelo) {
        return opciones.stream().filter(o -> o.id().equals(modelo)).findFirst()
                .map(Opcion::think).orElseThrow();
    }

    public String predeterminado() { return predeterminado; }

    public List<Opcion> opciones() { return opciones; }

    private static String error(String mensaje) {
        throw new IllegalArgumentException(mensaje);
    }
}
