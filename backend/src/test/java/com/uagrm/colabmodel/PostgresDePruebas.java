package com.uagrm.colabmodel;

import org.junit.jupiter.api.BeforeAll;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Una base PostgreSQL de verdad para las pruebas que no se pueden hacer con
 * repositorios en memoria: migraciones, restricciones, transacciones que se
 * revierten, la cadena de seguridad y el WebSocket completos.
 *
 * Usa su propia base, `colabmodel_test`, en el mismo servidor que el
 * `docker compose` del README. NUNCA toca la base de trabajo (`colabmodel`): la
 * crea si no existe y las pruebas borran lo que crean.
 *
 * Si no hay un PostgreSQL alcanzable, las pruebas se OMITEN --no fallan--, asi
 * `mvn test` sigue andando en una maquina sin Docker. El CI de GitHub lo levanta
 * como servicio para que no se omitan alli (ver .github/workflows/ci.yml).
 *
 * Otro servidor: `-Dcolabmodel.test.db=jdbc:postgresql://host:puerto/` (con la
 * barra final; se le agrega el nombre de la base).
 */
public abstract class PostgresDePruebas {

    static final String SERVIDOR = System.getProperty("colabmodel.test.db", "jdbc:postgresql://localhost:5433/");
    static final String BASE = "colabmodel_test";
    static final String USUARIO = System.getProperty("colabmodel.test.db.user", "colabmodel");
    static final String CLAVE = System.getProperty("colabmodel.test.db.password", "colabmodel");

    @BeforeAll
    static void prepararLaBase() {
        boolean lista;
        String motivo = "";
        try {
            lista = crearBaseSiNoExiste();
        } catch (SQLException e) {
            lista = false;
            motivo = " (" + e.getMessage().lines().findFirst().orElse("") + ")";
        }
        assumeTrue(lista, "No hay un PostgreSQL en " + SERVIDOR + ": se omiten las pruebas contra base real" + motivo);
    }

    @DynamicPropertySource
    static void apuntarALaBaseDePruebas(DynamicPropertyRegistry r) {
        // Salvaguarda: estas pruebas insertan y borran filas. Jamas contra la de trabajo.
        if (!BASE.endsWith("_test")) throw new IllegalStateException("La base de pruebas tiene que terminar en _test");
        r.add("spring.datasource.url", () -> SERVIDOR + BASE);
        r.add("spring.datasource.username", () -> USUARIO);
        r.add("spring.datasource.password", () -> CLAVE);
    }

    private static boolean crearBaseSiNoExiste() throws SQLException {
        try (Connection c = DriverManager.getConnection(SERVIDOR + "postgres", USUARIO, CLAVE)) {
            try (PreparedStatement existe = c.prepareStatement("SELECT 1 FROM pg_database WHERE datname = ?")) {
                existe.setString(1, BASE);
                try (ResultSet r = existe.executeQuery()) {
                    if (r.next()) return true;
                }
            }
            try (Statement s = c.createStatement()) {
                s.execute("CREATE DATABASE " + BASE);
            }
            return true;
        }
    }
}
