package com.uagrm.colabmodel.vision;

import com.uagrm.colabmodel.modelcore.metamodel.RelationKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El filtro de lo que contesta el modelo.
 *
 * Es la parte del nivel 2 que mas conviene probar, y no es el prompt. Un modelo
 * chico contesta lo pedido la mayoria de las veces; el resto contesta una
 * frase, una disculpa o un parrafo. Sin filtro, cualquiera de las tres termina
 * siendo el nombre de una clase, y de ahi el de una tabla.
 *
 * Los casos de abajo no son inventados: son las formas tipicas en que un modelo
 * de pocos parametros se desvia de la consigna.
 */
class RespuestaDeModeloTest {

    @ParameterizedTest(name = "\"{0}\" -> \"{1}\"")
    @CsvSource({
            "Libro,                          Libro",
            "'  Libro  ',                    Libro",
            "'\"Libro\"',                    Libro",
            "'`Libro`',                      Libro",
            "Libro.,                         Libro",
            "'**Libro**',                    Libro",
            "'titulo: string',               'titulo: string'",
            "'precio: decimal [0..1]',       'precio: decimal [0..1]'",
            "'Año Académico',                'Año Académico'",
            "0..*,                           0..*",
            "'+ prestar(dias: int)',         '+ prestar(dias: int)'",
            // "-" es la visibilidad privada de UML, la mas comun: no es una negativa.
            "'- titulo: String',             '- titulo: String'",
            "'- calcularMulta(): decimal',   '- calcularMulta(): decimal'",
    })
    @DisplayName("Deja pasar lo que de verdad es una lectura")
    void acepta(String crudo, String esperado) {
        assertThat(RespuestaDeModelo.texto(crudo)).isEqualTo(esperado);
    }

    @ParameterizedTest(name = "rechaza: {0}")
    @ValueSource(strings = {
            "VACIO",
            "vacio",
            "NONE",
            "null",
            "N/A",
            "-",
            "No puedo leer el texto de esta imagen",
            "I cannot read the text in this image",
            "I'm sorry, the image is too small to read.",
            "The image shows a rectangle with the word Libro inside it",
            "La imagen contiene una caja con texto",
            "As an AI language model, I can only describe what I see",
            "Este recorte parece contener el nombre de una clase de un diagrama UML muy largo",
            "...",
            "   ",
    })
    @DisplayName("Rechaza las frases, las disculpas y los vacios")
    void rechaza(String crudo) {
        // Ante la duda, null: un null es una celda que el usuario completa; un
        // texto inventado es un defecto que nadie ve.
        assertThat(RespuestaDeModelo.texto(crudo)).isNull();
    }

    @Test
    @DisplayName("Se queda con la primera linea cuando el modelo se explaya")
    void primera_linea() {
        assertThat(RespuestaDeModelo.texto("Libro\n\nEs el nombre de la clase que aparece arriba."))
                .isEqualTo("Libro");
    }

    @Test
    @DisplayName("Saca la introduccion, pero no parte un 'campo: tipo'")
    void introduccion() {
        assertThat(RespuestaDeModelo.texto("El texto de la imagen es: Libro")).isEqualTo("Libro");
        // Aca los dos lados del ':' son el dato, no una frase.
        assertThat(RespuestaDeModelo.texto("titulo: string")).isEqualTo("titulo: string");
    }

    @Test
    @DisplayName("Nada sin una sola letra ni digito entra al modelo")
    void sin_contenido() {
        assertThat(RespuestaDeModelo.texto("***")).isNull();
        assertThat(RespuestaDeModelo.texto("   .  ")).isNull();
    }

    @Test
    @DisplayName("Null entra, null sale")
    void nulo() {
        assertThat(RespuestaDeModelo.texto(null)).isNull();
        assertThat(RespuestaDeModelo.figura(null)).isNull();
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "ROMBO_RELLENO, COMPOSITION",
            "rombo relleno, COMPOSITION",
            "ROMBO_HUECO,   AGGREGATION",
            "TRIANGULO,     GENERALIZATION",
            "El simbolo es un TRIANGULO hueco, GENERALIZATION",
    })
    @DisplayName("Las cuatro figuras se mapean al tipo de relacion que les toca")
    void figuras(String crudo, RelationKind esperado) {
        assertThat(RespuestaDeModelo.figura(crudo)).isEqualTo(esperado);
    }

    @ParameterizedTest(name = "sin figura: {0}")
    @ValueSource(strings = { "NINGUNA", "no se ve nada", "una flecha", "cuadrado", "" })
    @DisplayName("Lo que no es una de las cuatro deja la relacion como estaba")
    void sin_figura(String crudo) {
        // Queda ASSOCIATION, que es la lectura conservadora: no impone
        // ON DELETE CASCADE ni clave primaria compartida.
        assertThat(RespuestaDeModelo.figura(crudo)).isNull();
    }
}
