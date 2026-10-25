package com.uagrm.colabmodel.modelcore.metamodel;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface UmlMethodRepository extends JpaRepository<UmlMethod, UUID> {
    List<UmlMethod> findByClassIdOrderByOrdinalAsc(UUID classId);

    /** Una sola consulta para todas las clases del diagrama: sin esto seria un N+1. */
    List<UmlMethod> findByClassIdInOrderByOrdinalAsc(Collection<UUID> classIds);
}
