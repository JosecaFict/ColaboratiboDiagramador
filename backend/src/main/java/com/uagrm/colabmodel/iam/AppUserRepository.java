package com.uagrm.colabmodel.iam;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AppUserRepository extends JpaRepository<AppUser, UUID> {
    Optional<AppUser> findByEmail(String email);
    boolean existsByEmail(String email);

    /**
     * Los emails no distinguen mayusculas: "Ana@Uagrm.edu.bo" y "ana@uagrm.edu.bo"
     * son la misma casilla. El registro los guarda en minusculas; esta busqueda
     * tambien encuentra los que se guardaron antes con otra grafia.
     */
    Optional<AppUser> findByEmailIgnoreCase(String email);
    boolean existsByEmailIgnoreCase(String email);
}
