package com.uagrm.colabmodel.project;

import com.uagrm.colabmodel.modelcore.mapping.ModeloLogico;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.util.List;
import java.util.UUID;

/**
 * Plano de CONTROL (REST). El plano de DATOS va por WebSocket.
 * No se mezclan.
 */
@RestController
@RequestMapping("/api/v1")
public class DiagramRestController {

    private final DiagramOperationRepository operations;
    private final DiagramSnapshotService snapshots;
    private final AccesoAProyecto acceso;
    /** Ejecuta el DDL en un esquema temporal y lo deshace siempre (ADR-032). */
    private final VerificadorDeEsquema verificador;

    public DiagramRestController(DiagramOperationRepository operations,
                                 DiagramSnapshotService snapshots, AccesoAProyecto acceso,
                                 VerificadorDeEsquema verificador) {
        this.operations = operations;
        this.snapshots = snapshots;
        this.acceso = acceso;
        this.verificador = verificador;
    }

    /** Snapshot completo + currentSeq. Es lo que pide un cliente al entrar o al resincronizar. */
    @GetMapping("/diagrams/{id}")
    public ResponseEntity<DiagramSnapshot> snapshot(@PathVariable UUID id, Principal principal) {
        exigirLectura(id, principal);
        return snapshots.de(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Vista Logica: el modelo conceptual proyectado a tablas, PK y FK (ADR-003). */
    @GetMapping("/diagrams/{id}/logical")
    public ResponseEntity<ModeloLogico> logica(@PathVariable UUID id, Principal principal) {
        exigirLectura(id, principal);
        return snapshots.logicaDe(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Tipo y rol sugeridos para un atributo, por su nombre.
     *
     * Lectura: sugerir no es decidir. El panel la pide mientras el usuario
     * escribe, y la misma regla servira para leer una foto o importar un SQL.
     */
    @GetMapping("/diagrams/{id}/sugerencias/atributo")
    public ResponseEntity<com.uagrm.colabmodel.modelcore.mapping.InferenciaDeAtributos.Sugerencia> sugerirAtributo(
            @PathVariable UUID id, @RequestParam String nombre,
            @RequestParam(required = false) UUID claseId,
            @RequestParam(required = false) String tipo, Principal principal) {
        exigirLectura(id, principal);
        return snapshots.sugerirAtributo(id, claseId, nombre, tipo)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Los 4 puntos donde el modelo OO y el de datos no dicen lo mismo.
     *
     * Va con permiso de lectura: ver las preguntas no es contestarlas. La
     * respuesta entra por el bus como cualquier otra escritura (ADR-002).
     */
    @GetMapping("/diagrams/{id}/ambiguities")
    public ResponseEntity<List<DiagramSnapshotService.AmbiguedadResuelta>> ambiguedades(
            @PathVariable UUID id, Principal principal) {
        exigirLectura(id, principal);
        return snapshots.ambiguedadesDe(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Vista Fisica: el esquema de PostgreSQL, navegable.
     *
     * Tercera lectura de la misma derivacion (ADR-003). Devuelve el script
     * VERBATIM del generador mas el detalle por tabla, porque un DDL de 20
     * tablas son varios cientos de lineas y la pregunta que alguien se hace
     * mirandolo no es "mostrame todo" sino "donde esta esta columna" o "quien
     * referencia a esta tabla".
     */
    @GetMapping("/diagrams/{id}/physical")
    public ResponseEntity<EsquemaFisico> fisica(@PathVariable UUID id, Principal principal,
                                                @RequestHeader(value = "If-None-Match",
                                                        required = false) String etagCliente) {
        exigirLectura(id, principal);

        // El esquema solo cambia cuando cambia el `seq`, asi que sirve de ETag.
        // Con esto, volver a la pestana no manda el cuerpo: es un 304.
        var conVersion = snapshots.fisicaConVersion(id);
        if (conVersion.isEmpty()) return ResponseEntity.notFound().build();

        String etag = "\"esquema-" + conVersion.get().seq() + "\"";
        if (etag.equals(etagCliente)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).build();
        }
        return ResponseEntity.ok().eTag(etag).body(conVersion.get().esquema());
    }

    /**
     * Ejecuta el DDL de hoy en PostgreSQL, dentro de una transaccion que se
     * deshace, y dice si lo acepto (ADR-032).
     *
     * POST y no GET porque ejecuta algo, aunque no deje nada. Va con permiso de
     * LECTURA: no toca el modelo ni la base, y es la misma informacion que ya
     * tiene quien puede ver el script.
     */
    @PostMapping("/diagrams/{id}/physical/verificacion")
    public ResponseEntity<VerificacionDelEsquema> verificar(@PathVariable UUID id, Principal principal) {
        exigirLectura(id, principal);
        return snapshots.fisicaConVersion(id)
                .map(e -> new VerificacionDelEsquema(e.esquema().hash(), e.seq(),
                        verificador.verificar(e.esquema().script())))
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** @param hash el esquema que se verifico: la pantalla solo confirma ESE. */
    public record VerificacionDelEsquema(String hash, long seq, VerificadorDeEsquema.Verificacion resultado) { }

    /**
     * Lo que el generador PIENSA hacer, antes de hacerlo.
     *
     * Existe separado de la descarga porque un zip no puede contarle al
     * usuario lo que dejo afuera: la Regla 10 pide que lo no soportado se
     * liste, y listarlo dentro del archivo que se acaba de bajar llega tarde.
     * Por la misma razon dice si la base que va en el zip es la confirmada.
     */
    @GetMapping("/diagrams/{id}/codegen/resumen")
    public ResponseEntity<ResumenGeneracion> resumenCodigo(@PathVariable UUID id, Principal principal) {
        exigirLectura(id, principal);
        EsquemaFisico.Confirmacion confirmacion = snapshots.fisicaDe(id)
                .map(EsquemaFisico::confirmacion).orElse(null);
        List<DiagramSnapshotService.CorreccionPreGeneracion> correcciones = snapshots
                .correccionesPreGeneracionDe(id).orElse(List.of());
        return snapshots.codigoDe(id)
                .map(p -> ResumenGeneracion.de(p, confirmacion, correcciones))
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * El backend Spring Boot generado, en un zip.
     *
     * Va con permiso de LECTURA, no de escritura: no toca el diagrama, y
     * quien puede abrir la Vista Logica ya tiene el esquema completo delante.
     * Negarle el zip a un VIEWER seria un candado sobre una puerta abierta.
     *
     * Con `?esquema=<hash>` se pide el zip de ESA base (ADR-032): si el DDL que
     * sale ahora no es el confirmado --alguien edito entre la confirmacion y la
     * descarga--, responde 409 en vez de entregar otra cosa. Se compara el DDL
     * del zip ya armado, no el de un pedido anterior: es lo que se entrega.
     */
    @GetMapping("/diagrams/{id}/codegen")
    public ResponseEntity<byte[]> codigo(@PathVariable UUID id,
                                         @RequestParam(name = "esquema", required = false) String esquema,
                                         Principal principal) {
        exigirLectura(id, principal);
        var proyecto = snapshots.codigoDe(id);
        if (proyecto.isEmpty()) return ResponseEntity.notFound().build();
        var p = proyecto.get();
        if (esquema != null && !esquema.equals(ResumenGeneracion.hashDelDdl(p))) {
            // Respuesta directa y no una excepcion: /error no esta abierto en
            // la seguridad, y el 409 llegaria convertido en un 403 engañoso.
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body("La base cambio desde que se confirmo: revisala otra vez en la Vista Fisica"
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        return ResponseEntity.ok()
                .contentType(MediaType.valueOf("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(p.carpetaRaiz() + ".zip").build().toString())
                .body(p.comoZip());
    }

    /**
     * Cuantas entidades salieron, que archivos trae y que quedo sin soportar.
     *
     * @param hashEsquema  la huella del DDL que va en el zip
     * @param confirmacion lo que el equipo confirmo, o null; `vigente` dice si
     *                     es exactamente este DDL
     */
    public record ResumenGeneracion(String nombre, String archivo, int entidades,
                                    List<String> archivos, List<String> advertencias,
                                    String hashEsquema, EsquemaFisico.Confirmacion confirmacion,
                                    List<DiagramSnapshotService.CorreccionPreGeneracion> correcciones) {
        static ResumenGeneracion de(com.uagrm.colabmodel.codegen.ProyectoGenerado p,
                                    EsquemaFisico.Confirmacion confirmacion,
                                    List<DiagramSnapshotService.CorreccionPreGeneracion> correcciones) {
            long entidades = p.archivos().keySet().stream()
                    .filter(r -> r.contains("/domain/")).count();
            return new ResumenGeneracion(p.nombre(), p.carpetaRaiz() + ".zip", (int) entidades,
                    List.copyOf(p.archivos().keySet()), p.advertencias(), hashDelDdl(p), confirmacion,
                    correcciones);
        }

        static String hashDelDdl(com.uagrm.colabmodel.codegen.ProyectoGenerado p) {
            return EsquemaFisico.huellaDe(p.archivos().getOrDefault(
                    com.uagrm.colabmodel.codegen.GeneradorSpringBoot.RUTA_DDL, ""));
        }
    }

    /**
     * CATCH-UP tras reconexion (escenario de calidad EC-08).
     * El cliente que detecta un hueco en la secuencia pide desde su ultimo seq.
     */
    @GetMapping("/diagrams/{id}/operations")
    public List<DiagramOperation> since(@PathVariable UUID id,
                                        @RequestParam(defaultValue = "0") long sinceSeq,
                                        Principal principal) {
        exigirLectura(id, principal);
        return operations.findByDiagramIdAndSeqGreaterThanOrderBySeqAsc(id, sinceSeq);
    }

    /**
     * Los TRES roles pueden leer; quien no es miembro, no. Antes daba igual
     * porque el snapshot devolvia listas vacias: ahora devuelve el modelo
     * entero, asi que sin esto cualquiera con un token valido podria leer
     * cualquier diagrama sabiendo su id.
     */
    private void exigirLectura(UUID diagramId, Principal principal) {
        UUID userId = UUID.fromString(principal.getName());
        if (acceso.rolSobreDiagrama(diagramId, userId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No sos miembro de este proyecto");
        }
    }
}
