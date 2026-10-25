package com.uagrm.colabmodel.project;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DiagramOperationRepository extends JpaRepository<DiagramOperation, Long> {

    /** Catch-up tras reconexion: todo lo que el cliente se perdio. */
    List<DiagramOperation> findByDiagramIdAndSeqGreaterThanOrderBySeqAsc(UUID diagramId, long sinceSeq);

    /** Idempotencia: si el opId ya se aplico, se devuelve el resultado anterior. */
    Optional<DiagramOperation> findByOpId(UUID opId);
}
