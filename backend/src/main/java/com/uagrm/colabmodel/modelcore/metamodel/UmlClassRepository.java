package com.uagrm.colabmodel.modelcore.metamodel;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface UmlClassRepository extends JpaRepository<UmlClass, UUID> {

    /** Las borradas llevan tombstone (deleted_at) y no entran en el snapshot. */
    List<UmlClass> findByDiagramIdAndDeletedAtIsNullOrderByNameAsc(UUID diagramId);
}
