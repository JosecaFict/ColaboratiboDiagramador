package com.uagrm.colabmodel.project;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface DiagramRepository extends JpaRepository<Diagram, UUID> {
    List<Diagram> findByProjectId(UUID projectId);

    List<Diagram> findByProjectIdOrderByCreatedAtAsc(UUID projectId);

    /** Cuantos diagramas tiene cada proyecto, en una sola consulta: [projectId, cantidad]. */
    @Query("select d.projectId, count(d) from Diagram d where d.projectId in :projectIds group by d.projectId")
    List<Object[]> contarPorProyecto(Collection<UUID> projectIds);
}
