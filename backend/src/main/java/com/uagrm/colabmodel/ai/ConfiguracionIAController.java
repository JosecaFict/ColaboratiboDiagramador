package com.uagrm.colabmodel.ai;

import com.uagrm.colabmodel.project.AccesoAProyecto;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.util.Map;
import java.util.UUID;

/**
 * Configuracion de la IA de un proyecto (T-09, contrato de D-08 punto 1).
 *
 * GET: cualquier miembro (OWNER, EDITOR, VIEWER). Sin fila guardada devuelve
 * los valores por defecto. PUT: solo el propietario; reemplaza los tres campos
 * y los exige a los tres. Quien no es miembro recibe 403 en ambos.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/ia/configuracion")
public class ConfiguracionIAController {

    private final ConfiguracionesDeIA configuraciones;
    private final AccesoAProyecto acceso;

    public ConfiguracionIAController(ConfiguracionesDeIA configuraciones, AccesoAProyecto acceso) {
        this.configuraciones = configuraciones;
        this.acceso = acceso;
    }

    @GetMapping
    public ConfiguracionDeIA leer(@PathVariable UUID projectId, Principal principal) {
        rolDe(projectId, principal);
        return configuraciones.de(projectId);
    }

    @PutMapping
    public ConfiguracionDeIA guardar(@PathVariable UUID projectId,
                                     @RequestBody(required = false) ConfiguracionDeIA.Entrada cuerpo,
                                     Principal principal) {
        AccesoAProyecto.Rol rol = rolDe(projectId, principal);
        if (rol != AccesoAProyecto.Rol.OWNER) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Solo el propietario puede cambiar como se comporta la IA del proyecto");
        }
        return configuraciones.guardar(projectId, UUID.fromString(principal.getName()),
                ConfiguracionDeIA.validar(cuerpo));
    }

    private AccesoAProyecto.Rol rolDe(UUID projectId, Principal principal) {
        UUID userId = UUID.fromString(principal.getName());
        return acceso.rolSobreProyecto(projectId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "No sos miembro de este proyecto"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> invalida(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("mensaje", e.getMessage()));
    }
}
