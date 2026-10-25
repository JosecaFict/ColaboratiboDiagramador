package com.uagrm.colabmodel.project;

import com.uagrm.colabmodel.modelcore.command.RejectionReason;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * El efecto de CONFIRM_SCHEMA (ADR-032): deja escrito que ESTE esquema es el
 * que el equipo reviso y quiere en la base del backend generado.
 *
 * Dos condiciones, verificadas aca y no en la pantalla, porque la pantalla no
 * es la que manda:
 *   1. El esquema de hoy es el que se reviso. La pantalla manda el hash de lo
 *      que tenia delante; si mientras tanto alguien edito una columna, se
 *      estaria confirmando algo que nadie miro.
 *   2. PostgreSQL lo acepta. Confirmar un script que la base rechaza seria
 *      firmar algo que no arranca, asi que se vuelve a verificar aunque la
 *      pantalla ya lo haya hecho.
 */
@Service
public class ConfirmadorDeEsquema {

    /** Por que no se confirmo, con la razon que entiende el bus. */
    public record Rechazo(RejectionReason razon, String mensaje) { }

    private final DiagramSnapshotService esquemas;
    private final VerificadorDeEsquema verificador;
    private final ConfirmacionDeEsquemaRepository confirmaciones;
    private final DiagramRepository diagrams;

    public ConfirmadorDeEsquema(DiagramSnapshotService esquemas, VerificadorDeEsquema verificador,
                                ConfirmacionDeEsquemaRepository confirmaciones, DiagramRepository diagrams) {
        this.esquemas = esquemas;
        this.verificador = verificador;
        this.confirmaciones = confirmaciones;
        this.diagrams = diagrams;
    }

    /** @return vacio si quedo confirmado; si no, el motivo para decirselo a quien confirmo. */
    public Optional<Rechazo> confirmar(UUID diagramId, String hashRevisado, UUID userId) {
        EsquemaFisico actual = esquemas.fisicaDe(diagramId).orElse(null);
        if (actual == null) {
            return Optional.of(new Rechazo(RejectionReason.NOT_FOUND, "El diagrama no existe"));
        }
        if (actual.tablas().isEmpty()) {
            return Optional.of(new Rechazo(RejectionReason.INVALID,
                    "No hay tablas que confirmar: el modelo todavia no tiene clases"));
        }
        if (!actual.hash().equals(hashRevisado)) {
            return Optional.of(new Rechazo(RejectionReason.STALE,
                    "El esquema cambio desde que lo revisaste (alguien edito el modelo):"
                            + " volve a revisarlo antes de confirmar"));
        }
        VerificadorDeEsquema.Verificacion v = verificador.verificar(actual.script());
        if (!v.ok()) {
            return Optional.of(new Rechazo(RejectionReason.INVALID, "PostgreSQL rechaza el esquema"
                    + (v.linea() != null ? " en la linea " + v.linea() : "") + ": " + v.error()));
        }

        // El seq de ESTA operacion: el bus es de un solo hilo por diagrama, asi
        // que nadie puede tomar el siguiente numero entre medio.
        long seq = diagrams.findById(diagramId).map(Diagram::getCurrentSeq).orElse(0L) + 1;
        ConfirmacionDeEsquema c = confirmaciones.findById(diagramId).orElseGet(() -> {
            ConfirmacionDeEsquema nueva = new ConfirmacionDeEsquema();
            nueva.setDiagramId(diagramId);
            return nueva;
        });
        c.setDdlHash(actual.hash());
        c.setDdl(actual.script());
        c.setSeq(seq);
        c.setConfirmedBy(userId);
        c.setConfirmedAt(OffsetDateTime.now());
        confirmaciones.save(c);
        return Optional.empty();
    }
}
