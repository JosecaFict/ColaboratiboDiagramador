package com.uagrm.colabmodel.versiones;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Las columnas de rama de `diagrams` (V8): de que diagrama y version nacio, con
 * que nombre, y su sal de ids.
 *
 * Se leen y escriben por JDBC en vez de agregarlas a la entidad `Diagram`: esa
 * entidad es del modulo project y la usan el bus y el applier, y asi este
 * frente no la toca. Hibernate no conoce estas columnas, asi que guardar un
 * Diagram no las pisa.
 */
@Component
class Ramas {

    record Rama(UUID diagramId, UUID projectId, String diagramName, long currentSeq, String branchName,
                UUID forkedFromDiagramId, UUID forkedFromVersionId, UUID idSalt, OffsetDateTime createdAt) { }

    private static final String COLUMNAS = "id, project_id, name, current_seq, branch_name, "
            + "forked_from_diagram_id, forked_from_version_id, id_salt, created_at";

    private final JdbcTemplate jdbc;

    Ramas(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    Optional<Rama> de(UUID diagramId) {
        return jdbc.query("select " + COLUMNAS + " from diagrams where id = ?", Ramas::fila, diagramId)
                .stream().findFirst();
    }

    /** Las ramas que nacieron de este diagrama, de la mas vieja a la mas nueva. */
    List<Rama> hijasDe(UUID diagramId) {
        return jdbc.query("select " + COLUMNAS + " from diagrams where forked_from_diagram_id = ? order by created_at",
                Ramas::fila, diagramId);
    }

    void marcar(UUID diagramId, String branchName, UUID desdeDiagrama, UUID desdeVersion, UUID sal) {
        jdbc.update("update diagrams set branch_name = ?, forked_from_diagram_id = ?, forked_from_version_id = ?,"
                + " id_salt = ? where id = ?", branchName, desdeDiagrama, desdeVersion, sal, diagramId);
    }

    private static Rama fila(ResultSet r, int i) throws SQLException {
        return new Rama(r.getObject("id", UUID.class), r.getObject("project_id", UUID.class),
                r.getString("name"), r.getLong("current_seq"), r.getString("branch_name"),
                r.getObject("forked_from_diagram_id", UUID.class), r.getObject("forked_from_version_id", UUID.class),
                r.getObject("id_salt", UUID.class), r.getObject("created_at", OffsetDateTime.class));
    }
}
