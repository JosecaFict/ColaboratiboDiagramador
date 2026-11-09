package com.uagrm.colabmodel.ai;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/** Filas de `ai_settings` (V9), una por proyecto. */
interface ConfiguracionIARepository extends JpaRepository<ConfiguracionIAFila, UUID> { }
