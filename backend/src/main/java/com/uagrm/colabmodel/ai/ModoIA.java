package com.uagrm.colabmodel.ai;

/** La intencion explicita de una intervencion del asistente. */
public enum ModoIA {
    CONSULTAR,
    REVISAR,
    DISENAR,
    MODIFICAR,
    /** Revisa el diagrama real y devuelve recomendaciones aplicables, sin cambiar nada. */
    RECOMENDAR,
    /** Uso interno de RECOMENDAR: verifica UNA regla del requisito contra el diagrama. */
    VERIFICAR
}
