package com.uagrm.colabmodel.project;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectRepository extends JpaRepository<Project, UUID> {
    List<Project> findByOwnerId(UUID ownerId);

    /**
     * El proyecto con su fila bloqueada hasta el fin de la transaccion.
     *
     * Toda operacion que toca quien es miembro o quien es propietario pasa por
     * aca: "siempre hay exactamente un OWNER" es un invariante que dos pedidos
     * simultaneos (transferir y quitar, dos transferencias) romperian si cada
     * uno leyera el estado viejo. Serializarlos por proyecto cuesta nada: son
     * operaciones raras y de una sola persona.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Project p where p.id = :id")
    Optional<Project> findParaModificar(UUID id);
}
