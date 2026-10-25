package com.uagrm.colabmodel.project;

import com.uagrm.colabmodel.modelcore.command.RejectionReason;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * CONFIRM_SCHEMA (ADR-032): se confirma lo que se reviso, y solo si
 * PostgreSQL lo acepta. Las dos condiciones las pone el servidor.
 */
class ConfirmadorDeEsquemaTest {

    private final UUID diagramId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final String script = "CREATE TABLE libro (\n    id uuid NOT NULL,\n    PRIMARY KEY (id)\n);\n";

    private DiagramSnapshotService esquemas;
    private VerificadorDeEsquema verificador;
    private ConfirmacionDeEsquemaRepository confirmaciones;
    private ConfirmadorDeEsquema confirmador;

    @BeforeEach
    void preparar() {
        esquemas = mock(DiagramSnapshotService.class);
        verificador = mock(VerificadorDeEsquema.class);
        confirmaciones = mock(ConfirmacionDeEsquemaRepository.class);
        DiagramRepository diagrams = mock(DiagramRepository.class);

        Diagram d = new Diagram();
        d.setId(diagramId);
        d.setCurrentSeq(41);
        when(diagrams.findById(diagramId)).thenReturn(Optional.of(d));

        EsquemaFisico.Tabla libro = new EsquemaFisico.Tabla("libro", "Libro", List.of(), List.of("id"),
                List.of(), List.of(), UUID.randomUUID());
        when(esquemas.fisicaDe(diagramId)).thenReturn(Optional.of(new EsquemaFisico("PostgreSQL",
                new EsquemaFisico.Resumen(1, 1, 0, 0, 0), List.of(libro), script, List.of(),
                EsquemaFisico.huellaDe(script), null)));

        confirmador = new ConfirmadorDeEsquema(esquemas, verificador, confirmaciones, diagrams);
    }

    private static VerificadorDeEsquema.Verificacion aceptado() {
        return new VerificadorDeEsquema.Verificacion(true, 1, 1, 0, 0, 0, 12, null, null, null);
    }

    @Test
    @DisplayName("Confirma el DDL revisado, con su hash, su texto, el seq de la operacion y quien fue")
    void confirma() {
        when(verificador.verificar(script)).thenReturn(aceptado());

        Optional<ConfirmadorDeEsquema.Rechazo> r = confirmador.confirmar(diagramId,
                EsquemaFisico.huellaDe(script), userId);

        assertThat(r).isEmpty();
        ArgumentCaptor<ConfirmacionDeEsquema> guardada = ArgumentCaptor.forClass(ConfirmacionDeEsquema.class);
        verify(confirmaciones).save(guardada.capture());
        assertThat(guardada.getValue().getDdlHash()).isEqualTo(EsquemaFisico.huellaDe(script));
        assertThat(guardada.getValue().getDdl()).isEqualTo(script);
        assertThat(guardada.getValue().getSeq()).as("el seq de ESTA operacion").isEqualTo(42);
        assertThat(guardada.getValue().getConfirmedBy()).isEqualTo(userId);
    }

    @Test
    @DisplayName("Si el esquema cambio desde que se reviso, no confirma: STALE")
    void esquema_cambiado() {
        Optional<ConfirmadorDeEsquema.Rechazo> r = confirmador.confirmar(diagramId,
                EsquemaFisico.huellaDe("otro script"), userId);

        assertThat(r).get().extracting(ConfirmadorDeEsquema.Rechazo::razon).isEqualTo(RejectionReason.STALE);
        // Ni siquiera se ejecuta: no tiene sentido verificar algo que no se va a confirmar.
        verifyNoInteractions(verificador);
        verify(confirmaciones, never()).save(any());
    }

    @Test
    @DisplayName("Si PostgreSQL lo rechaza, no confirma y dice en que linea")
    void postgres_lo_rechaza() {
        when(verificador.verificar(script)).thenReturn(new VerificadorDeEsquema.Verificacion(false, 0, 0, 0, 0,
                0, 5, "ERROR: syntax error at or near \"uuidd\"", 2, "CREATE TABLE libro (...)"));

        Optional<ConfirmadorDeEsquema.Rechazo> r = confirmador.confirmar(diagramId,
                EsquemaFisico.huellaDe(script), userId);

        assertThat(r).get().satisfies(x -> {
            assertThat(x.razon()).isEqualTo(RejectionReason.INVALID);
            assertThat(x.mensaje()).contains("linea 2").contains("syntax error");
        });
        verify(confirmaciones, never()).save(any());
    }
}
