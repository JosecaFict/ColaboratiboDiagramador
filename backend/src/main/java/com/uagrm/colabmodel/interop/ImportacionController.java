package com.uagrm.colabmodel.interop;

import com.uagrm.colabmodel.project.AccesoAProyecto;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.security.Principal;
import java.util.Map;
import java.util.UUID;

/**
 * Importar un modelo desde un archivo: el primer paso, que solo PROPONE.
 *
 * Deliberadamente no hay un endpoint que "importe": aplicar la propuesta es
 * escribir, y toda escritura entra por el bus de comandos (ADR-002). El
 * cliente manda la propuesta revisada como un BATCH por el WebSocket, con los
 * mismos permisos, locks y secuencia que cualquier otro cambio.
 */
@RestController
@RequestMapping("/api/v1/diagrams/{diagramId}/importaciones")
public class ImportacionController {

    private final ServicioDeImportacion servicio;
    private final AccesoAProyecto acceso;

    public ImportacionController(ServicioDeImportacion servicio, AccesoAProyecto acceso) {
        this.servicio = servicio;
        this.acceso = acceso;
    }

    @PostMapping("/propuesta")
    public PropuestaDeImportacion proponer(@PathVariable UUID diagramId, @RequestParam("archivo") MultipartFile archivo,
                                           Principal principal) throws IOException {
        // Se exige permiso de EDICION aunque esto no escriba: proponerle a un
        // VIEWER una importacion que el bus le va a rechazar es hacerle perder
        // el tiempo revisandola.
        UUID userId = UUID.fromString(principal.getName());
        AccesoAProyecto.Rol rol = acceso.rolSobreDiagrama(diagramId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "No sos miembro de este proyecto"));
        if (!acceso.puedeEditar(rol)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Los " + rol + " no pueden importar");
        }
        if (archivo.isEmpty()) {
            throw new ArchivoInvalidoException("El archivo esta vacio");
        }
        return servicio.proponer(diagramId, archivo.getOriginalFilename(), archivo.getBytes());
    }

    @ExceptionHandler(ArchivoInvalidoException.class)
    ResponseEntity<Map<String, String>> archivoInvalido(ArchivoInvalidoException e) {
        return ResponseEntity.badRequest().body(Map.of("mensaje", e.getMessage()));
    }

    @ExceptionHandler(FormatoNoSoportadoException.class)
    ResponseEntity<Map<String, String>> formatoNoSoportado(FormatoNoSoportadoException e) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).body(Map.of("mensaje", e.getMessage()));
    }
}
