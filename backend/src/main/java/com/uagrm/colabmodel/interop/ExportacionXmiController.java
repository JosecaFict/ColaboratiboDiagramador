package com.uagrm.colabmodel.interop;

import com.uagrm.colabmodel.modelcore.metamodel.*;
import com.uagrm.colabmodel.project.AccesoAProyecto;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.util.List;
import java.util.UUID;

/**
 * Exportar el diagrama como XMI 2.5.1 / UML 2.5.1 estandar (ADR-013, ADR-022, ADR-024).
 *
 * Va con permiso de LECTURA, no de escritura, con el mismo razonamiento que
 * el zip del generador ({@code DiagramRestController#codigo}): no toca el
 * diagrama, y quien puede abrir la Vista Logica ya tiene el modelo completo
 * delante. Por eso es GET y de solo lectura, no un endpoint del bus.
 *
 * No hay pantalla de revision aca: a diferencia de importar, exportar no
 * decide nada que el usuario tenga que confirmar. Lo que este XMI no puede
 * llevarse (perdidas declaradas, ADR-018) queda escrito en el propio archivo
 * como {@code xmi:Documentation} y en el header {@code X-Colabmodel-Perdidas},
 * para que se vea ANTES de mirar el archivo, no despues.
 */
@RestController
@RequestMapping("/api/v1/diagrams/{diagramId}")
public class ExportacionXmiController {

    private final UmlClassRepository classes;
    private final UmlAttributeRepository attributes;
    private final UmlMethodRepository methods;
    private final UmlRelationshipRepository relationships;
    private final DiagramLayoutRepository layouts;
    private final AccesoAProyecto acceso;

    public ExportacionXmiController(UmlClassRepository classes, UmlAttributeRepository attributes,
                                    UmlMethodRepository methods, UmlRelationshipRepository relationships,
                                    DiagramLayoutRepository layouts, AccesoAProyecto acceso) {
        this.classes = classes;
        this.attributes = attributes;
        this.methods = methods;
        this.relationships = relationships;
        this.layouts = layouts;
        this.acceso = acceso;
    }

    @GetMapping("/xmi")
    @Transactional(readOnly = true)
    public ResponseEntity<byte[]> exportar(@PathVariable UUID diagramId,
                                           @RequestParam(defaultValue = "estandar") String perfil,
                                           Principal principal) {
        // ADR-031: "ea" es XMI 2.1 con la Extension de EA (diagrama incluido);
        // cualquier otro valor, el estandar de ADR-024.
        EscritorXmi.Perfil elegido = "ea".equalsIgnoreCase(perfil)
                ? EscritorXmi.Perfil.ENTERPRISE_ARCHITECT : EscritorXmi.Perfil.ESTANDAR;
        UUID userId = UUID.fromString(principal.getName());
        if (acceso.rolSobreDiagrama(diagramId, userId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No sos miembro de este proyecto");
        }

        List<UmlClass> vivas = classes.findByDiagramIdAndDeletedAtIsNullOrderByNameAsc(diagramId);
        List<UUID> ids = vivas.stream().map(UmlClass::getId).toList();
        List<UmlAttribute> atributos = ids.isEmpty() ? List.of() : attributes.findByClassIdInOrderByOrdinalAsc(ids);
        List<UmlMethod> metodos = ids.isEmpty() ? List.of() : methods.findByClassIdInOrderByOrdinalAsc(ids);
        List<UmlRelationship> vivasRelaciones = relationships.findByDiagramIdAndDeletedAtIsNull(diagramId);

        List<DiagramLayout> disposicion = elegido == EscritorXmi.Perfil.ENTERPRISE_ARCHITECT
                ? layouts.findByDiagramId(diagramId) : List.of();

        EscritorXmi.Exportado exportado = EscritorXmi.exportar(vivas, atributos, metodos, vivasRelaciones,
                disposicion, elegido);
        return ResponseEntity.ok()
                .contentType(MediaType.valueOf("application/xml"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(elegido == EscritorXmi.Perfil.ENTERPRISE_ARCHITECT
                                ? "diagrama-ea.xml" : "diagrama.xmi").build().toString())
                .header("X-Colabmodel-Perdidas", String.valueOf(exportado.perdidas().size()))
                .body(exportado.xmi());
    }
}
