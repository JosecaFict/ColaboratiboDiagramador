package com.uagrm.colabmodel.ai.imagenes;

import com.uagrm.colabmodel.ai.AvisosDeIA;
import com.uagrm.colabmodel.project.AccesoAProyecto;
import com.uagrm.colabmodel.vision.ImagenDeEntrada;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.URI;
import java.security.Principal;
import java.util.Map;
import java.util.UUID;

/**
 * Las imagenes del chat de la IA (docs/DISENO-CHAT-IMAGENES.md, seccion 2).
 *
 * Permisos: subir, OWNER o EDITOR; ver y listar, cualquier miembro; eliminar, quien la subio (si todavia puede
 * editar) o el OWNER. Una imagen de otro diagrama es 404, nunca 403. Los avisos salen DESPUES de que el
 * servicio termino su transaccion, como los del historial.
 */
@RestController
@RequestMapping("/api/v1/diagrams/{diagramId}/ai/imagenes")
public class ImagenesController {

    private final ImagenesDelChat servicio;
    private final AccesoAProyecto acceso;
    private final AvisosDeIA avisos;

    public ImagenesController(ImagenesDelChat servicio, AccesoAProyecto acceso, AvisosDeIA avisos) {
        this.servicio = servicio;
        this.acceso = acceso;
        this.avisos = avisos;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ImagenMeta> subir(@PathVariable UUID diagramId,
                                            @RequestPart(value = "imagen", required = false) MultipartFile imagen,
                                            Principal principal) {
        UUID userId = quien(principal);
        AccesoAProyecto.Rol rol = miembro(diagramId, userId);
        if (!acceso.puedeEditar(rol)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Un VIEWER no puede subir imágenes");
        }
        if (imagen == null) throw new ImagenDeEntrada.ImagenInvalida("Falta el archivo en la parte «imagen»");
        byte[] bytes;
        try {
            bytes = imagen.getBytes();
        } catch (IOException e) {
            throw new ImagenDeEntrada.ImagenInvalida("No se pudo leer el archivo subido");
        }

        ImagenesDelChat.ResultadoDeSubida r = servicio.subir(diagramId, userId, bytes, imagen.getContentType());
        if (!r.nueva()) return ResponseEntity.ok(r.imagen());          // D7: ya estaba, sin aviso
        avisos.imagenSubida(diagramId, r.imagen());
        return ResponseEntity.created(URI.create("/api/v1/diagrams/" + diagramId + "/ai/imagenes/" + r.imagen().id()))
                .body(r.imagen());
    }

    public record GaleriaDeImagenes(java.util.List<ImagenMeta> imagenes, ImagenesDelChat.Uso uso) { }

    @GetMapping
    public GaleriaDeImagenes galeria(@PathVariable UUID diagramId, Principal principal) {
        miembro(diagramId, quien(principal));
        ImagenesDelChat.Galeria g = servicio.galeria(diagramId);
        return new GaleriaDeImagenes(g.imagenes(), g.uso());
    }

    @GetMapping("/{imagenId}")
    public ResponseEntity<byte[]> bytes(@PathVariable UUID diagramId, @PathVariable UUID imagenId,
                                        @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String siNoCoincide,
                                        Principal principal) {
        miembro(diagramId, quien(principal));
        return responder(servicio.datos(diagramId, imagenId), siNoCoincide);
    }

    @GetMapping("/{imagenId}/miniatura")
    public ResponseEntity<byte[]> miniatura(@PathVariable UUID diagramId, @PathVariable UUID imagenId,
                                            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String siNoCoincide,
                                            Principal principal) {
        miembro(diagramId, quien(principal));
        return responder(servicio.miniatura(diagramId, imagenId), siNoCoincide);
    }

    @DeleteMapping("/{imagenId}")
    public ImagenMeta eliminar(@PathVariable UUID diagramId, @PathVariable UUID imagenId, Principal principal) {
        UUID userId = quien(principal);
        AccesoAProyecto.Rol rol = miembro(diagramId, userId);
        ImagenesDelChat.ResultadoDeEliminacion r = servicio.eliminar(diagramId, imagenId, userId,
                // El OWNER siempre; quien la subio, solo si todavia puede editar (O-6: un VIEWER nunca borra).
                subidaPor -> rol == AccesoAProyecto.Rol.OWNER
                        || (userId.equals(subidaPor) && acceso.puedeEditar(rol)));
        if (r.cambio()) avisos.imagenEliminada(diagramId, r.imagen());
        return r.imagen();
    }

    // ---------------------------------------------------------------- ayudas

    /**
     * El tipo sale de lo GUARDADO, nunca del cliente. `no-cache` y no `max-age`: el navegador revalida cada vez
     * y, si la imagen se elimino, recibe 404 en vez de seguir mostrando su copia (D6).
     */
    private static ResponseEntity<byte[]> responder(ImagenesDelChat.Descarga d, String siNoCoincide) {
        String etag = "\"" + d.sha256() + "\"";
        ResponseEntity.BodyBuilder base = ResponseEntity.status(coincide(siNoCoincide, etag)
                        ? HttpStatus.NOT_MODIFIED : HttpStatus.OK)
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.noCache().cachePrivate())
                .eTag(etag);
        if (coincide(siNoCoincide, etag)) return base.build();
        return base.contentType(MediaType.parseMediaType(d.tipo()))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .contentLength(d.bytes().length)
                .body(d.bytes());
    }

    private static boolean coincide(String siNoCoincide, String etag) {
        if (siNoCoincide == null) return false;
        for (String candidato : siNoCoincide.split(",")) {
            String c = candidato.trim();
            if (c.startsWith("W/")) c = c.substring(2);
            if (c.equals(etag) || c.equals("*")) return true;
        }
        return false;
    }

    private static UUID quien(Principal principal) {
        return UUID.fromString(principal.getName());
    }

    /** Cualquier miembro. Sin membresia (o con un diagrama que no existe) es 403, como el resto de la IA. */
    private AccesoAProyecto.Rol miembro(UUID diagramId, UUID userId) {
        return acceso.rolSobreDiagrama(diagramId, userId).orElseThrow(AccesoAProyecto::noEsMiembro);
    }

    // ---------------------------------------------------------------- errores

    /** Entrada mala es 400, nunca 500: el que sube el archivo puede corregirlo. Misma forma que la vision. */
    @ExceptionHandler(ImagenDeEntrada.ImagenInvalida.class)
    public ResponseEntity<Map<String, String>> imagenInvalida(ImagenDeEntrada.ImagenInvalida e) {
        return ResponseEntity.badRequest().body(Map.of("error", "IMAGEN_INVALIDA", "mensaje", e.getMessage()));
    }

    @ExceptionHandler(TopeDeImagenesExcedido.class)
    public ResponseEntity<Map<String, String>> topeExcedido(TopeDeImagenesExcedido e) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Map.of("mensaje", e.getMessage()));
    }
}
