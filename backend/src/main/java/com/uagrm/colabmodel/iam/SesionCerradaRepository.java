package com.uagrm.colabmodel.iam;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

interface SesionCerradaRepository extends JpaRepository<SesionCerrada, UUID> {

    /** Los tokens ya vencidos no hace falta recordarlos: igual se rechazan por vencidos. */
    @Modifying
    @Transactional
    @Query("delete from SesionCerrada s where s.expiresAt < :ahora")
    int borrarVencidas(OffsetDateTime ahora);
}
