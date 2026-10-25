package com.uagrm.colabmodel.project;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectMemberRepository extends JpaRepository<ProjectMember, ProjectMember.Pk> {
    Optional<ProjectMember> findByProjectIdAndUserId(UUID projectId, UUID userId);

    /** Los miembros INVITADOS: el OWNER no tiene fila (esta en projects.owner_id). */
    List<ProjectMember> findByProjectId(UUID projectId);

    /** Los proyectos donde el usuario es miembro invitado. */
    List<ProjectMember> findByUserId(UUID userId);
}
