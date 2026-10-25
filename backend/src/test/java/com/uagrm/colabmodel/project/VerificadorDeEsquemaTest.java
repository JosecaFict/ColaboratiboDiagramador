package com.uagrm.colabmodel.project;

import com.uagrm.colabmodel.modelcore.mapping.Decisiones;
import com.uagrm.colabmodel.modelcore.mapping.DerivadorLogico;
import com.uagrm.colabmodel.modelcore.mapping.ModeloCanonico;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lo que se puede probar sin PostgreSQL: como se corta el script y como se
 * ubica el error. La ejecucion contra la base se verifica con la base real
 * (docker compose), igual que la atomicidad del BATCH.
 */
class VerificadorDeEsquemaTest {

    @Test
    @DisplayName("Corta el script en sentencias, cada una con la linea donde empieza")
    void corta_en_sentencias() {
        String ddl = """
                -- Generado desde el modelo conceptual. No editar a mano.

                CREATE TABLE a (
                    id uuid NOT NULL,
                    PRIMARY KEY (id)
                );

                CREATE TABLE b (
                    id uuid NOT NULL
                );

                -- Claves foraneas: despues de TODAS las tablas, asi el orden no importa.
                ALTER TABLE b ADD CONSTRAINT fk_b_id FOREIGN KEY (id) REFERENCES a(id);
                """;

        List<VerificadorDeEsquema.Sentencia> s = VerificadorDeEsquema.sentencias(ddl);

        assertThat(s).extracting(VerificadorDeEsquema.Sentencia::linea).containsExactly(3, 8, 13);
        assertThat(s.get(0).sql()).startsWith("CREATE TABLE a (").endsWith(")").doesNotContain(";");
        assertThat(s.get(2).sql()).startsWith("ALTER TABLE b");
    }

    @ParameterizedTest(name = "modelo {0}")
    @ValueSource(strings = {"01-uno-a-uno.json", "02-uno-a-muchos.json",
            "03-muchos-a-muchos.json", "04-herencia.json", "05-clase-asociativa.json",
            "06-nombres-acentos.json", "07-reflexiva-doble.json"})
    @DisplayName("Una sentencia por tabla, por FK y por indice: nada se pega ni se pierde")
    void una_sentencia_por_objeto(String modelo) {
        ModeloCanonico.Cargado m = ModeloCanonico.cargar(modelo);
        EsquemaFisico e = DiagramSnapshotService.aEsquemaFisico(DerivadorLogico.derivar(
                m.clases(), m.atributos(), m.relaciones(), Decisiones.ninguna()));

        List<VerificadorDeEsquema.Sentencia> s = VerificadorDeEsquema.sentencias(e.script());

        assertThat(s).hasSize(e.resumen().tablas() + e.resumen().clavesForaneas() + e.resumen().indices());
        assertThat(s).allSatisfy(x -> assertThat(x.sql()).doesNotStartWith("--"));
    }

    @Test
    @DisplayName("El error se ubica en la linea del SCRIPT, no de la sentencia")
    void ubica_el_error() {
        // La sentencia empieza en la linea 10 del script; el tipo invalido esta
        // en su tercera linea, en la posicion 46 que informa PostgreSQL.
        VerificadorDeEsquema.Sentencia s = new VerificadorDeEsquema.Sentencia(10,
                "CREATE TABLE a (\n    id uuid NOT NULL,\n    x tipo_raro\n)");
        String mensaje = "ERROR: type \"tipo_raro\" does not exist\n  Position: 46";

        assertThat(VerificadorDeEsquema.lineaDelError(s, mensaje)).isEqualTo(12);
        assertThat(VerificadorDeEsquema.sinPosicion(mensaje))
                .isEqualTo("ERROR: type \"tipo_raro\" does not exist");
    }

    @Test
    @DisplayName("Sin posicion en el mensaje, el error queda en la primera linea de la sentencia")
    void error_sin_posicion() {
        VerificadorDeEsquema.Sentencia s = new VerificadorDeEsquema.Sentencia(7, "CREATE INDEX x ON y (z)");

        assertThat(VerificadorDeEsquema.lineaDelError(s, "ERROR: relation \"y\" does not exist")).isEqualTo(7);
    }
}
