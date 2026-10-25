package com.uagrm.colabmodel.modelcore.metamodel;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface UmlAttributeRepository extends JpaRepository<UmlAttribute, UUID> {
    List<UmlAttribute> findByClassId(UUID classId);

    /**
     * El orden NO es cosmetico: el layout radial de Chen coloca cada ovalo
     * segun su indice, asi que sin `ordinal` dos clientes podrian dibujar los
     * atributos en posiciones distintas (ADR-006).
     */
    List<UmlAttribute> findByClassIdInOrderByOrdinalAsc(Collection<UUID> classIds);
}
