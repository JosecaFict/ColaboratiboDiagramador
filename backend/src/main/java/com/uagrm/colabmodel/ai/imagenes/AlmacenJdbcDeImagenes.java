package com.uagrm.colabmodel.ai.imagenes;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Los bytes con SQL nativo: la entidad {@link ImagenDelChat} no mapea `data` ni `thumbnail`, asi que ninguna
 * consulta de JPA puede traerlos. Solo esta clase los toca. Participa de la transaccion en curso (JdbcTemplate
 * usa la misma conexion que JPA cuando hay una).
 */
@Repository
public class AlmacenJdbcDeImagenes implements AlmacenDeImagenes {

    private final JdbcTemplate jdbc;

    public AlmacenJdbcDeImagenes(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public OffsetDateTime insertar(NuevaImagen n) {
        Timestamp creada = jdbc.queryForObject(
                "insert into ai_images (id, diagram_id, uploaded_by, content_type, size_bytes, width, height, sha256, "
                        + "data, thumbnail) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning created_at",
                Timestamp.class, n.id(), n.diagramId(), n.subidaPor(), n.tipo(), n.bytes(), n.ancho(), n.alto(),
                n.sha256(), n.datos(), n.miniatura());
        return creada.toInstant().atOffset(ZoneOffset.UTC);
    }

    @Override
    public Optional<byte[]> datos(UUID id) {
        return leer("select data from ai_images where id = ? and deleted_at is null", id);
    }

    @Override
    public Optional<byte[]> miniatura(UUID id) {
        return leer("select thumbnail from ai_images where id = ? and deleted_at is null", id);
    }

    private Optional<byte[]> leer(String sql, UUID id) {
        List<byte[]> r = jdbc.query(sql, (rs, i) -> rs.getBytes(1), id);
        return r.isEmpty() ? Optional.empty() : Optional.ofNullable(r.get(0));
    }

    @Override
    public OffsetDateTime eliminar(UUID id, UUID quien) {
        Timestamp cuando = jdbc.queryForObject(
                "update ai_images set data = null, thumbnail = null, deleted_at = now(), deleted_by = ? "
                        + "where id = ? and deleted_at is null returning deleted_at",
                Timestamp.class, quien, id);
        return cuando.toInstant().atOffset(ZoneOffset.UTC);
    }
}
