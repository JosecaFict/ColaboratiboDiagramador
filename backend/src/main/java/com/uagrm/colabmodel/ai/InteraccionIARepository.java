package com.uagrm.colabmodel.ai;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

interface InteraccionIARepository extends JpaRepository<InteraccionIA, UUID> {
    List<InteraccionIA> findByDiagramIdOrderByCreatedAtAscIdAsc(UUID diagramId);
}

