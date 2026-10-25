package com.uagrm.colabmodel.ai;

import com.uagrm.colabmodel.modelcore.command.Origin;
import com.uagrm.colabmodel.project.AccesoAProyecto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.util.Map;
import java.util.UUID;

/**
 * Pedirle a la IA un cambio en lenguaje natural: el primer paso, que solo
 * PROPONE (misma forma que ImportacionController, ADR-016).
 *
 * No hay un endpoint que aplique. Confirmar es mandar
 * {@link ServicioDeIA#comandoBatchDe} por el WebSocket como un BATCH mas, con
 * los mismos permisos, locks y secuencia que cualquier otro cambio: un
 * segundo camino de escritura para "lo que confirmo la IA" rompería ADR-002.
 */
@RestController
@RequestMapping("/api/v1/diagrams/{diagramId}/ai")
public class AiController {

    private final ServicioDeIA servicio;
    private final AccesoAProyecto acceso;
    private final String proveedorActivo;
    private final String modeloOllama;
    private final int contextoOllama;
    private final CatalogoModelosOllama modelosOllama;
    private final HistorialIA historial;

    public AiController(ServicioDeIA servicio, AccesoAProyecto acceso,
                        @Value("${colabmodel.ai.proveedor:ollama}") String proveedorActivo,
                        @Value("${colabmodel.ai.ollama.model:qwen3:8b}") String modeloOllama,
                        @Value("${colabmodel.ai.ollama.contexto:4096}") int contextoOllama,
                        CatalogoModelosOllama modelosOllama, HistorialIA historial) {
        this.servicio = servicio;
        this.acceso = acceso;
        this.proveedorActivo = proveedorActivo;
        this.modeloOllama = modeloOllama;
        this.contextoOllama = contextoOllama;
        this.modelosOllama = modelosOllama;
        this.historial = historial;
    }

    public record SolicitudDePropuesta(String prompt, Origin origin, ModoIA modo, String modelo) { }
    public record EstadoIA(String proveedor, String modelo, boolean local, int contexto,
                           java.util.List<CatalogoModelosOllama.Opcion> modelos) { }

    @GetMapping("/estado")
    public EstadoIA estado(@PathVariable UUID diagramId, Principal principal) {
        UUID userId = UUID.fromString(principal.getName());
        acceso.rolSobreDiagrama(diagramId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "No sos miembro de este proyecto"));
        boolean local = "ollama".equalsIgnoreCase(proveedorActivo);
        return new EstadoIA(proveedorActivo, local ? modeloOllama : proveedorActivo, local, contextoOllama,
                local ? modelosOllama.opciones() : java.util.List.of());
    }

    @PostMapping("/propuestas")
    public PropuestaDeIA proponer(@PathVariable UUID diagramId, @RequestBody SolicitudDePropuesta cuerpo,
                                  Principal principal) {
        // Igual que en la importacion: proponerle un cambio a un VIEWER que el
        // bus va a rechazar despues es hacerle perder el tiempo revisandolo.
        UUID userId = UUID.fromString(principal.getName());
        AccesoAProyecto.Rol rol = acceso.rolSobreDiagrama(diagramId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "No sos miembro de este proyecto"));
        ModoIA modo = cuerpo.modo() == null ? ModoIA.MODIFICAR : cuerpo.modo();
        boolean pideCambios = modo == ModoIA.DISENAR || modo == ModoIA.MODIFICAR;
        if (pideCambios && !acceso.puedeEditar(rol)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Los " + rol + " no pueden pedirle cambios a la IA");
        }
        Origin origin = cuerpo.origin() == null ? Origin.TEXT : cuerpo.origin();
        String modelo = "ollama".equalsIgnoreCase(proveedorActivo) ? modelosOllama.resolver(cuerpo.modelo()) : null;
        PropuestaDeIA propuesta = servicio.generar(diagramId, cuerpo.prompt(), origin, modo, modelo);
        return historial.registrar(diagramId, userId, modo, cuerpo.prompt(), propuesta);
    }

    @GetMapping("/historial")
    public java.util.List<HistorialIA.Entrada> historial(@PathVariable UUID diagramId, Principal principal) {
        UUID userId = UUID.fromString(principal.getName());
        acceso.rolSobreDiagrama(diagramId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "No sos miembro de este proyecto"));
        return historial.listar(diagramId);
    }

    @PatchMapping("/historial/{interactionId}/aplicada")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void marcarAplicada(@PathVariable UUID diagramId, @PathVariable UUID interactionId,
                               Principal principal) {
        UUID userId = UUID.fromString(principal.getName());
        AccesoAProyecto.Rol rol = acceso.rolSobreDiagrama(diagramId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "No sos miembro de este proyecto"));
        if (!acceso.puedeEditar(rol)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Tu rol no puede aplicar propuestas de IA");
        }
        historial.marcarAplicada(diagramId, interactionId, userId);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> solicitudInvalida(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("mensaje", e.getMessage()));
    }

    @ExceptionHandler(ProveedorIAException.class)
    ResponseEntity<Map<String, String>> proveedorFallo(ProveedorIAException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("mensaje", e.getMessage()));
    }
}
