package com.uagrm.colabmodel.ai;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

interface InteraccionIARepository extends JpaRepository<InteraccionIA, UUID> {
    List<InteraccionIA> findByDiagramIdOrderByCreatedAtAscIdAsc(UUID diagramId);

    /** Los ultimos turnos de un diagrama, del mas nuevo al mas viejo, con el limite en la consulta (T-09). */
    List<InteraccionIA> findByDiagramIdOrderByCreatedAtDescIdDesc(UUID diagramId, Pageable pagina);
}
