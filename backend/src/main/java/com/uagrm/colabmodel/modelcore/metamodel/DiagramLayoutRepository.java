package com.uagrm.colabmodel.modelcore.metamodel;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DiagramLayoutRepository extends JpaRepository<DiagramLayout, DiagramLayout.Pk> {
    Optional<DiagramLayout> findByDiagramIdAndElementId(UUID diagramId, UUID elementId);
    List<DiagramLayout> findByDiagramId(UUID diagramId);
}
