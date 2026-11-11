package com.uagrm.colabmodel.ai.imagenes;

import com.uagrm.colabmodel.iam.AppUser;
import com.uagrm.colabmodel.iam.AppUserRepository;
import com.uagrm.colabmodel.vision.ImagenDeEntrada;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Las imagenes del chat de la IA: subir, listar, descargar y eliminar (docs/DISENO-CHAT-IMAGENES.md, 2 y 3).
 *
 * No decide permisos de rol: eso lo hace el controlador, con la misma fuente de verdad que el resto
 * (AccesoAProyecto). Aqui viven la validacion, la deduplicacion (D7), el tope (D5) y el borrado logico (D6).
 * Los bytes pasan por {@link AlmacenDeImagenes} y el reencodado por {@link ReencodadorDeImagen}.
 */
@Service
public class ImagenesDelChat {

    private static final long MB = 1024L * 1024L;

    public record ResultadoDeSubida(ImagenMeta imagen, boolean nueva) { }

    public record ResultadoDeEliminacion(ImagenMeta imagen, boolean cambio) { }

    public record Uso(long cantidad, long bytes, int maxCantidad, long maxBytes) { }

    public record Galeria(List<ImagenMeta> imagenes, Uso uso) { }

    /** Lo que hace falta para responder un GET de bytes. */
    public record Descarga(String tipo, String sha256, byte[] bytes) { }

    private final ImagenesRepository imagenes;
    private final AppUserRepository usuarios;
    private final ObjectProvider<AlmacenDeImagenes> almacen;
    private final ObjectProvider<ReencodadorDeImagen> reencodador;
    private final int maxPorDiagrama;
    private final long maxBytesPorDiagrama;
    private final ImagenDeEntrada.Limites limites;

    public ImagenesDelChat(ImagenesRepository imagenes, AppUserRepository usuarios,
                           ObjectProvider<AlmacenDeImagenes> almacen,
                           ObjectProvider<ReencodadorDeImagen> reencodador,
                           @Value("${colabmodel.ai.imagenes.max-por-diagrama:100}") int maxPorDiagrama,
                           @Value("${colabmodel.ai.imagenes.max-bytes-por-diagrama:209715200}") long maxBytesPorDiagrama,
                           @Value("${colabmodel.vision.max-bytes:8388608}") long maxBytes,
                           @Value("${colabmodel.vision.max-pixeles:40000000}") long maxPixeles,
                           @Value("${colabmodel.vision.max-lado:1600}") int maxLado) {
        if (maxPorDiagrama <= 0 || maxBytesPorDiagrama <= 0) {
            throw new IllegalArgumentException("Los topes de colabmodel.ai.imagenes tienen que ser mayores que 0");
        }
        this.imagenes = imagenes;
        this.usuarios = usuarios;
        this.almacen = almacen;
        this.reencodador = reencodador;
        this.maxPorDiagrama = maxPorDiagrama;
        this.maxBytesPorDiagrama = maxBytesPorDiagrama;
        // Los mismos limites que la vision: una imagen guardada y una leida valen lo mismo.
        this.limites = new ImagenDeEntrada.Limites(maxBytes, maxPixeles, maxLado);
    }

    // ----------------------------------------------------------------- subir

    /**
     * @throws ImagenDeEntrada.ImagenInvalida     400: no es una imagen aceptable
     * @throws TopeDeImagenesExcedido             413: el diagrama llego a su tope
     * @throws ResponseStatusException            503: todavia no esta conectado el almacen o el reencodador
     */
    @Transactional
    public ResultadoDeSubida subir(UUID diagramId, UUID userId, byte[] bytes, String contentType) {
        if (bytes == null || bytes.length == 0) {
            throw new ImagenDeEntrada.ImagenInvalida("No llego ninguna imagen");
        }
        String sha = sha256(bytes);
        // D7: la misma imagen ya viva en este diagrama no se decodifica ni se vuelve a guardar. Una imagen que ya
        // esta guardada paso la validacion, asi que no hace falta repetirla.
        Optional<ImagenDelChat> yaEsta = imagenes.findByDiagramIdAndSha256AndDeletedAtIsNull(diagramId, sha);
        if (yaEsta.isPresent()) return new ResultadoDeSubida(meta(yaEsta.get()), false);

        ImagenDeEntrada.desde(bytes, contentType, limites);   // 400 con el mensaje de siempre
        ReencodadorDeImagen.Reencodada r = reencodador().reencodar(bytes, contentType);

        // Con el diagrama bloqueado: la carrera de duplicados y la del tope se resuelven aqui.
        imagenes.bloquearDiagrama(diagramId);
        yaEsta = imagenes.findByDiagramIdAndSha256AndDeletedAtIsNull(diagramId, sha);
        if (yaEsta.isPresent()) return new ResultadoDeSubida(meta(yaEsta.get()), false);

        ImagenesRepository.Uso uso = imagenes.uso(diagramId);
        if (uso.getCantidad() >= maxPorDiagrama) {
            throw new TopeDeImagenesExcedido("Este diagrama ya tiene " + maxPorDiagrama
                    + " imágenes (el máximo). Eliminá alguna de la galería para subir otra");
        }
        if (uso.getBytes() + r.datos().length > maxBytesPorDiagrama) {
            throw new TopeDeImagenesExcedido("Este diagrama ya usa " + mb(uso.getBytes()) + " MB de "
                    + mb(maxBytesPorDiagrama) + " MB en imágenes y esta pesa " + mb(r.datos().length)
                    + " MB. Eliminá alguna de la galería para subir otra");
        }

        UUID id = UUID.randomUUID();
        var nueva = new AlmacenDeImagenes.NuevaImagen(id, diagramId, userId, r.tipo(), r.ancho(), r.alto(), sha,
                r.datos(), r.miniatura());
        java.time.OffsetDateTime creada;
        try {
            creada = almacen().insertar(nueva);
        } catch (DataIntegrityViolationException e) {
            // Otra transaccion gano la carrera de duplicados (uq_ai_images_diagram_sha): se devuelve esa.
            ImagenDelChat existente = imagenes.findByDiagramIdAndSha256AndDeletedAtIsNull(diagramId, sha)
                    .orElseThrow(() -> e);
            return new ResultadoDeSubida(meta(existente), false);
        }
        ImagenDelChat fila = new ImagenDelChat(id, diagramId, userId, r.tipo(), nueva.bytes(), r.ancho(),
                r.alto(), sha, creada, null, null);
        return new ResultadoDeSubida(meta(fila), true);
    }

    // -------------------------------------------------------------- consultar

    @Transactional(readOnly = true)
    public Galeria galeria(UUID diagramId) {
        List<ImagenDelChat> filas = imagenes.findByDiagramIdAndDeletedAtIsNullOrderByCreatedAtDescIdDesc(diagramId);
        Map<UUID, String> nombres = nombres(filas.stream().map(ImagenDelChat::getUploadedBy).toList());
        ImagenesRepository.Uso uso = imagenes.uso(diagramId);
        return new Galeria(filas.stream().map(f -> ImagenMeta.de(f, nombres.get(f.getUploadedBy()))).toList(),
                new Uso(uso.getCantidad(), uso.getBytes(), maxPorDiagrama, maxBytesPorDiagrama));
    }

    /**
     * Los metadatos de varias imagenes con UNA consulta de imagenes y UNA de nombres (sin N+1, sin bytes).
     * Incluye las eliminadas: el turno las muestra como "imagen eliminada".
     */
    @Transactional(readOnly = true)
    public Map<UUID, ImagenMeta> metas(Collection<UUID> ids) {
        if (ids.isEmpty()) return Map.of();
        List<ImagenDelChat> filas = imagenes.findAllById(ids);
        Map<UUID, String> nombres = nombres(filas.stream().map(ImagenDelChat::getUploadedBy).toList());
        return filas.stream().collect(Collectors.toMap(ImagenDelChat::getId,
                f -> ImagenMeta.de(f, nombres.get(f.getUploadedBy()))));
    }

    /** 404 si no es de este diagrama o fue eliminada: los bytes ya no existen. */
    @Transactional(readOnly = true)
    public Descarga datos(UUID diagramId, UUID imagenId) {
        ImagenDelChat i = viva(diagramId, imagenId);
        byte[] bytes = almacen().datos(i.getId()).orElseThrow(ImagenesDelChat::noExiste);
        return new Descarga(i.getContentType(), i.getSha256(), bytes);
    }

    @Transactional(readOnly = true)
    public Descarga miniatura(UUID diagramId, UUID imagenId) {
        ImagenDelChat i = viva(diagramId, imagenId);
        byte[] bytes = almacen().miniatura(i.getId()).orElseThrow(ImagenesDelChat::noExiste);
        return new Descarga("image/jpeg", i.getSha256() + "-t", bytes);
    }

    // --------------------------------------------------------------- eliminar

    /**
     * Borrado logico (D6): vacia los bytes y marca la fila; el turno que la uso sigue apuntandola y la muestra
     * como eliminada. Idempotente: borrar una eliminada devuelve lo mismo y `cambio = false`.
     *
     * @param puedeEliminar recibe a quien la subio (null si su cuenta se borro) y dice si el que borra puede
     * @throws ResponseStatusException 403 si no puede, 404 si no es de este diagrama
     */
    @Transactional
    public ResultadoDeEliminacion eliminar(UUID diagramId, UUID imagenId, UUID quien,
                                           Function<UUID, Boolean> puedeEliminar) {
        ImagenDelChat i = imagenes.findByIdAndDiagramId(imagenId, diagramId).orElseThrow(ImagenesDelChat::noExiste);
        if (!Boolean.TRUE.equals(puedeEliminar.apply(i.getUploadedBy()))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Solo quien subió la imagen o el propietario puede eliminarla");
        }
        if (i.estaEliminada()) return new ResultadoDeEliminacion(meta(i), false);
        java.time.OffsetDateTime cuando = almacen().eliminar(i.getId(), quien);
        // La entidad es de solo lectura y ya esta cargada: se arma la vista de eliminada con lo que devolvio el almacen.
        var eliminada = new ImagenDelChat(i.getId(), i.getDiagramId(), i.getUploadedBy(), i.getContentType(),
                i.getSizeBytes(), i.getWidth(), i.getHeight(), i.getSha256(), i.getCreatedAt(), cuando, quien);
        return new ResultadoDeEliminacion(meta(eliminada), true);
    }

    // ---------------------------------------------------------------- ayudas

    public ImagenMeta metaDe(UUID imagenId) {
        return metas(Set.of(imagenId)).get(imagenId);
    }

    private ImagenMeta meta(ImagenDelChat i) {
        return ImagenMeta.de(i, nombres(java.util.Collections.singletonList(i.getUploadedBy())).get(i.getUploadedBy()));
    }

    private Map<UUID, String> nombres(Collection<UUID> ids) {
        Set<UUID> distintos = new HashSet<>(ids);
        distintos.remove(null);
        if (distintos.isEmpty()) return Map.of();
        return usuarios.findAllById(distintos).stream().collect(Collectors.toMap(AppUser::getId, AppUser::getFullName));
    }

    private ImagenDelChat viva(UUID diagramId, UUID imagenId) {
        ImagenDelChat i = imagenes.findByIdAndDiagramId(imagenId, diagramId).orElseThrow(ImagenesDelChat::noExiste);
        if (i.estaEliminada()) throw noExiste();
        return i;
    }

    private static ResponseStatusException noExiste() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Esa imagen no existe en este diagrama");
    }

    private AlmacenDeImagenes almacen() {
        AlmacenDeImagenes a = almacen.getIfAvailable();
        if (a == null) throw noConectado("El almacén de imágenes");
        return a;
    }

    private ReencodadorDeImagen reencodador() {
        ReencodadorDeImagen r = reencodador.getIfAvailable();
        if (r == null) throw noConectado("El reencodado de imágenes");
        return r;
    }

    private static ResponseStatusException noConectado(String que) {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, que + " todavía no está disponible");
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("El JDK no trae SHA-256", e);
        }
    }

    private static String mb(long bytes) {
        return String.format(Locale.ROOT, "%.1f", bytes / (double) MB);
    }
}
