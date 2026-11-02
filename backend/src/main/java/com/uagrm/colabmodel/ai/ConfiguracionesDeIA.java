package com.uagrm.colabmodel.ai;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Leer y guardar la configuracion de la IA de cada proyecto (T-09). */
@Service
public class ConfiguracionesDeIA {

    private final ConfiguracionIARepository filas;

    ConfiguracionesDeIA(ConfiguracionIARepository filas) {
        this.filas = filas;
    }

    /** La configuracion vigente; si el proyecto nunca la cambio, la de por defecto (sin crear la fila). */
    @Transactional(readOnly = true)
    public ConfiguracionDeIA de(UUID projectId) {
        return filas.findById(projectId).map(ConfiguracionIAFila::comoConfiguracion)
                .orElse(ConfiguracionDeIA.POR_DEFECTO);
    }

    @Transactional
    public ConfiguracionDeIA guardar(UUID projectId, UUID userId, ConfiguracionDeIA nueva) {
        ConfiguracionIAFila fila = filas.findById(projectId).orElseGet(() -> {
            ConfiguracionIAFila f = new ConfiguracionIAFila();
            f.setProjectId(projectId);
            return f;
        });
        fila.tomar(nueva);
        fila.setUpdatedAt(OffsetDateTime.now());
        fila.setUpdatedBy(userId);
        return filas.save(fila).comoConfiguracion();
    }
}
