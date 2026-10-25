package com.uagrm.colabmodel.modelcore.metamodel;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface UmlRelationshipRepository extends JpaRepository<UmlRelationship, UUID> {
    List<UmlRelationship> findBySourceClassIdOrTargetClassId(UUID sourceClassId, UUID targetClassId);

    List<UmlRelationship> findByDiagramIdAndDeletedAtIsNull(UUID diagramId);
}
