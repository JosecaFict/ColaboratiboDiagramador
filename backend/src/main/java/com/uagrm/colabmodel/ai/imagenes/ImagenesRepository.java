package com.uagrm.colabmodel.ai.imagenes;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Solo metadatos: ninguna consulta de aqui toca `data` ni `thumbnail`. */
public interface ImagenesRepository extends JpaRepository<ImagenDelChat, UUID> {

    /** La galeria: las vivas del diagrama, mas nuevas primero. */
    List<ImagenDelChat> findByDiagramIdAndDeletedAtIsNullOrderByCreatedAtDescIdDesc(UUID diagramId);

    /** D7: la viva con ese hash en ese diagrama. El indice parcial `uq_ai_images_diagram_sha` garantiza una. */
    Optional<ImagenDelChat> findByDiagramIdAndSha256AndDeletedAtIsNull(UUID diagramId, String sha256);

    /** Pertenencia: una imagen de otro diagrama no se encuentra (404, nunca 403). */
    Optional<ImagenDelChat> findByIdAndDiagramId(UUID id, UUID diagramId);

    interface Uso {
        long getCantidad();
        long getBytes();
    }

    /** Lo que cuenta para el tope: solo las vivas. */
    @Query("select count(i) as cantidad, coalesce(sum(i.sizeBytes), 0) as bytes from ImagenDelChat i "
            + "where i.diagramId = :diagramId and i.deletedAt is null")
    Uso uso(@Param("diagramId") UUID diagramId);

    /**
     * Serializa las subidas de un diagrama: dos subidas simultaneas no pasan el tope juntas. Se toma ANTES de
     * contar y dura hasta el fin de la transaccion. Necesita una base real para probarse.
     */
    @Query(value = "select id from diagrams where id = :diagramId for update", nativeQuery = true)
    Optional<UUID> bloquearDiagrama(@Param("diagramId") UUID diagramId);
}
