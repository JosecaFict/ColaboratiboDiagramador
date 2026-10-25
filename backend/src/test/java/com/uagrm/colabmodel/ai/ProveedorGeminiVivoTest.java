package com.uagrm.colabmodel.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La unica prueba que SALE A INTERNET, y por eso esta apagada salvo que haya
 * una clave puesta en el ambiente.
 *
 * Sirve para una sola pregunta, la que ninguna simulacion puede contestar:
 * la cuenta gratuita de AI Studio acepta nuestras herramientas y devuelve
 * comandos del catalogo? Se corre a mano antes de la defensa:
 *
 *     set GEMINI_API_KEY=la-clave
 *     mvn -Dtest=ProveedorGeminiVivoTest test
 *
 * Sin la variable se SALTEA (no falla), asi que `mvn test` sigue corriendo
 * completo en una maquina sin clave y sin conexion.
 *
 * El modelo tambien se puede cambiar desde el ambiente (`GEMINI_MODEL`), que
 * es lo primero a probar si Google contesta 429 por cuota: un flash-lite
 * tiene un limite diario mas alto.
 */
class ProveedorGeminiVivoTest {

    @Test
    @EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
    @DisplayName("Gemini de verdad traduce un pedido en espanol a comandos del catalogo")
    void contra_la_api_real() {
        String modelo = System.getenv().getOrDefault("GEMINI_MODEL", "gemini-3.8-flash");
        ProveedorGemini proveedor = new ProveedorGemini(
                System.getenv("GEMINI_API_KEY"), modelo,
                "https://generativelanguage.googleapis.com/v1beta", new ObjectMapper());

        RespuestaIA r = proveedor.generar(new SolicitudIA(
                "Agrega una clase Libro con titulo e isbn, y relacionala con Autor.",
                List.of(new SolicitudIA.ClaseEnContexto("Autor", List.of("nombre"))),
                EsquemaDeComandos.herramientas()));

        System.out.println("Modelo: " + modelo);
        System.out.println("Nota: " + r.notaDelModelo());
        r.comandos().forEach(c -> System.out.println("  " + c.tipo() + " " + c.parametros()));

        assertThat(r.comandos()).isNotEmpty();
        assertThat(r.comandos()).allSatisfy(c ->
                assertThat(EsquemaDeComandos.herramientas())
                        .extracting(HerramientaIA::nombre)
                        .contains(c.tipo()));
    }
}
