package com.uagrm.colabmodel.versiones;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Versiones tipo Git (ADR-037), contrato en docs/DISENO-FASE-2.md, seccion B.
 *
 * Plano de control: estas rutas no escriben el modelo por su cuenta. Restaurar,
 * crear rama y mergear terminan en un BATCH por el bus, que es el que difunde
 * por /topic/diagram/{id}/ops.
 */
@RestController
@RequestMapping("/api/v1/diagrams/{id}")
public class VersionesController {

    private final ServicioDeVersiones servicio;

    public VersionesController(ServicioDeVersiones servicio) {
        this.servicio = servicio;
    }

    public record PedidoDeCommit(String name, String message) { }

    public record PedidoDeRama(String name, UUID fromVersionId) { }

    public record PedidoDeMerge(UUID fromDiagramId, List<ServicioDeVersiones.Resolucion> resoluciones) { }

    @PostMapping("/versions")
    public ResponseEntity<ServicioDeVersiones.VersionDto> commit(@PathVariable UUID id,
                                                                 @RequestBody PedidoDeCommit pedido,
                                                                 Principal principal) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(servicio.commit(id, yo(principal), pedido.name(), pedido.message()));
    }

    @GetMapping("/versions")
    public List<ServicioDeVersiones.VersionDto> historial(@PathVariable UUID id, Principal principal) {
        return servicio.historial(id, yo(principal));
    }

    @GetMapping("/versions/{vid}")
    public ServicioDeVersiones.VersionConFoto version(@PathVariable UUID id, @PathVariable UUID vid,
                                                      Principal principal) {
        return servicio.version(id, vid, yo(principal));
    }

    @GetMapping("/diff")
    public ObjectNode diff(@PathVariable UUID id, @RequestParam(required = false) String from,
                           @RequestParam(required = false) String to, Principal principal) {
        return servicio.diff(id, from, to, yo(principal));
    }

    @PostMapping("/versions/{vid}/restore")
    public ServicioDeVersiones.Aplicado restaurar(@PathVariable UUID id, @PathVariable UUID vid, Principal principal) {
        return servicio.restaurar(id, vid, yo(principal));
    }

    @PostMapping("/branches")
    public ResponseEntity<ServicioDeVersiones.RamaDto> crearRama(@PathVariable UUID id,
                                                                 @RequestBody PedidoDeRama pedido,
                                                                 Principal principal) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(servicio.crearRama(id, yo(principal), pedido.name(), pedido.fromVersionId()));
    }

    @GetMapping("/branches")
    public ObjectNode ramas(@PathVariable UUID id, Principal principal) {
        return servicio.ramas(id, yo(principal));
    }

    @PostMapping("/merge/preview")
    public ObjectNode previa(@PathVariable UUID id, @RequestBody PedidoDeMerge pedido, Principal principal) {
        return servicio.previaDelMerge(id, pedido.fromDiagramId(), yo(principal));
    }

    @PostMapping("/merge")
    public ServicioDeVersiones.Aplicado mergear(@PathVariable UUID id, @RequestBody PedidoDeMerge pedido,
                                                Principal principal) {
        return servicio.mergear(id, pedido.fromDiagramId(), pedido.resoluciones(), yo(principal));
    }

    @ExceptionHandler(RechazoDeVersiones.class)
    public ResponseEntity<Map<String, Object>> rechazo(RechazoDeVersiones e) {
        return ResponseEntity.status(e.estado()).body(e.cuerpo());
    }

    private static UUID yo(Principal principal) {
        return UUID.fromString(principal.getName());
    }
}
