package com.uagrm.colabmodel.versiones;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface DiagramMergeRepository extends JpaRepository<DiagramMerge, UUID> {

    /** Los merges entre dos diagramas, en cualquier sentido, del mas nuevo al mas viejo. */
    @Query("select m from DiagramMerge m where (m.targetDiagramId = :a and m.fromDiagramId = :b)"
            + " or (m.targetDiagramId = :b and m.fromDiagramId = :a) order by m.createdAt desc")
    List<DiagramMerge> entre(UUID a, UUID b);
}
