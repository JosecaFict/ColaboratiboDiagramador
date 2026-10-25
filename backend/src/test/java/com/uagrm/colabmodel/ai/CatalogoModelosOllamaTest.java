package com.uagrm.colabmodel.ai;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CatalogoModelosOllamaTest {

    private final CatalogoModelosOllama catalogo = new CatalogoModelosOllama("qwen3:8b");

    @Test
    void permite_8b_y_9b_con_thinking_separado() {
        assertThat(catalogo.resolver("qwen3:8b")).isEqualTo("qwen3:8b");
        assertThat(catalogo.usaThinking("qwen3:8b")).isTrue();
        assertThat(catalogo.resolver("qwen3.5:9b-q4_K_M")).isEqualTo("qwen3.5:9b-q4_K_M");
        assertThat(catalogo.usaThinking("qwen3.5:9b-q4_K_M")).isFalse();
    }

    @Test
    void usa_el_predeterminado_y_rechaza_un_modelo_arbitrario() {
        assertThat(catalogo.resolver(null)).isEqualTo("qwen3:8b");
        assertThatThrownBy(() -> catalogo.resolver("modelo/desconocido"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no esta permitido");
    }
}
