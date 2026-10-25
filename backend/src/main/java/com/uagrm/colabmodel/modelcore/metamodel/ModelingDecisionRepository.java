package com.uagrm.colabmodel.modelcore.metamodel;

import com.uagrm.colabmodel.modelcore.mapping.Ambiguedad;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ModelingDecisionRepository extends JpaRepository<ModelingDecision, UUID> {

    List<ModelingDecision> findByDiagramId(UUID diagramId);

    /**
     * La clave real de la tabla. Se busca por aca --y no por `id`-- porque
     * decidir dos veces sobre lo mismo tiene que ACTUALIZAR la fila, no
     * agregar una segunda que viole el indice unico.
     */
    Optional<ModelingDecision> findByDiagramIdAndElementIdAndAmbiguityKind(
            UUID diagramId, UUID elementId, Ambiguedad.TipoAmbiguedad ambiguityKind);
}
