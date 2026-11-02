package com.uagrm.colabmodel.versiones;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DiagramVersionRepository extends JpaRepository<DiagramVersion, UUID> {

    /** Historial: del mas nuevo al mas viejo. */
    List<DiagramVersion> findByDiagramIdOrderBySeqDescCreatedAtDesc(UUID diagramId);

    Optional<DiagramVersion> findFirstByDiagramIdOrderBySeqDescCreatedAtDesc(UUID diagramId);

    boolean existsByDiagramIdAndNameIgnoreCase(UUID diagramId, String name);
}
