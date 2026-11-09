package com.uagrm.colabmodel.iam;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Las sesiones cerradas, en `revoked_tokens` (V10). En la base y no en memoria:
 * un reinicio del servidor no puede revivir una sesion que el usuario cerro.
 */
@Service
class SesionesCerradasEnBase implements SesionesCerradas {

    private final SesionCerradaRepository repositorio;

    SesionesCerradasEnBase(SesionCerradaRepository repositorio) {
        this.repositorio = repositorio;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean estaCerrada(UUID jti) {
        return repositorio.existsById(jti);
    }

    @Override
    @Transactional
    public void cerrar(UUID jti, Instant venceEn) {
        OffsetDateTime ahora = OffsetDateTime.now(ZoneOffset.UTC);
        // De paso se olvidan las que ya vencieron: la tabla no crece sin fin.
        repositorio.borrarVencidas(ahora);
        if (!repositorio.existsById(jti)) {
            repositorio.save(new SesionCerrada(jti, venceEn.atOffset(ZoneOffset.UTC)));
        }
    }
}
