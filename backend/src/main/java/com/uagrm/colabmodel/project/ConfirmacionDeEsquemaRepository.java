package com.uagrm.colabmodel.project;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/** La clave es el diagrama: hay a lo sumo una confirmacion vigente por diagrama. */
public interface ConfirmacionDeEsquemaRepository extends JpaRepository<ConfirmacionDeEsquema, UUID> {
}
