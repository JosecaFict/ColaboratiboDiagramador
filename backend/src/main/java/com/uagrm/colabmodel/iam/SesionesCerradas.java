package com.uagrm.colabmodel.iam;

import java.time.Instant;
import java.util.UUID;

/**
 * Los tokens cuya sesion se cerro antes de que vencieran.
 *
 * Es una interfaz para que {@link JwtService} se pueda construir en las
 * pruebas sin base de datos ({@link #NINGUNA}); en la aplicacion la implementa
 * {@link SesionesCerradasEnBase}.
 */
public interface SesionesCerradas {

    boolean estaCerrada(UUID jti);

    void cerrar(UUID jti, Instant venceEn);

    /** Ninguna sesion se cierra: el comportamiento de antes, para las pruebas que no lo usan. */
    SesionesCerradas NINGUNA = new SesionesCerradas() {
        @Override
        public boolean estaCerrada(UUID jti) { return false; }

        @Override
        public void cerrar(UUID jti, Instant venceEn) { }
    };
}
