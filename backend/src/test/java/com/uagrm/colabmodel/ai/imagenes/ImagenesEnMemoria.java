package com.uagrm.colabmodel.ai.imagenes;

import com.uagrm.colabmodel.project.ProyectosEnMemoria;
import com.uagrm.colabmodel.vision.ImagenDeEntrada;
import org.springframework.beans.factory.ObjectProvider;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Las imagenes del chat sobre dobles en memoria: el repositorio de metadatos, el almacen de bytes y un
 * reencodador de mentira. Sirve a las pruebas de la Fase 1 que NO dependen de como se mapean los bytes ni de
 * como se reencodan (esas dos cosas estan pendientes: costuras {@link AlmacenDeImagenes} y
 * {@link ReencodadorDeImagen}).
 */
public class ImagenesEnMemoria {

    public final ProyectosEnMemoria datos;
    public final Map<UUID, ImagenDelChat> filas = new LinkedHashMap<>();
    public final Map<UUID, byte[]> bytes = new LinkedHashMap<>();
    public final Map<UUID, byte[]> miniaturas = new LinkedHashMap<>();
    public final ImagenesRepository repo = mock(ImagenesRepository.class);
    public final AlmacenFalso almacen = new AlmacenFalso();
    public final ReencodadorFalso reencodador = new ReencodadorFalso();
    /** Lo que paso por el repositorio, en orden: para comprobar que se bloquea ANTES de contar. */
    public final List<String> llamadas = new ArrayList<>();
    private final AtomicInteger reloj = new AtomicInteger();

    public ImagenesEnMemoria(ProyectosEnMemoria datos) {
        this.datos = datos;
        when(repo.findByDiagramIdAndDeletedAtIsNullOrderByCreatedAtDescIdDesc(any())).thenAnswer(i -> filas.values()
                .stream().filter(f -> f.getDiagramId().equals(i.getArgument(0)) && !f.estaEliminada())
                .sorted(Comparator.comparing(ImagenDelChat::getCreatedAt).reversed()).toList());
        when(repo.findByDiagramIdAndSha256AndDeletedAtIsNull(any(), any())).thenAnswer(i -> filas.values().stream()
                .filter(f -> f.getDiagramId().equals(i.getArgument(0)) && f.getSha256().equals(i.getArgument(1))
                        && !f.estaEliminada()).findFirst());
        when(repo.findByIdAndDiagramId(any(), any())).thenAnswer(i -> Optional.ofNullable(filas.get(i.<UUID>getArgument(0)))
                .filter(f -> f.getDiagramId().equals(i.getArgument(1))));
        when(repo.findAllById(anyIterable())).thenAnswer(i -> {
            List<ImagenDelChat> r = new ArrayList<>();
            for (UUID id : i.<Iterable<UUID>>getArgument(0)) if (filas.containsKey(id)) r.add(filas.get(id));
            return r;
        });
        when(repo.uso(any())).thenAnswer(i -> {
            llamadas.add("uso");
            List<ImagenDelChat> vivas = filas.values().stream()
                    .filter(f -> f.getDiagramId().equals(i.getArgument(0)) && !f.estaEliminada()).toList();
            long cantidad = vivas.size();
            long total = vivas.stream().mapToLong(ImagenDelChat::getSizeBytes).sum();
            return new ImagenesRepository.Uso() {
                public long getCantidad() { return cantidad; }
                public long getBytes() { return total; }
            };
        });
        when(repo.bloquearDiagrama(any())).thenAnswer(i -> {
            llamadas.add("bloqueo");
            return Optional.of(i.<UUID>getArgument(0));
        });
    }

    public ImagenesDelChat servicio(int maxPorDiagrama, long maxBytesPorDiagrama) {
        return new ImagenesDelChat(repo, datos.users, proveedor(almacen), proveedor(reencodador), org.springframework.transaction.support.TransactionOperations.withoutTransaction(), maxPorDiagrama,
                maxBytesPorDiagrama, 8L * 1024 * 1024, 40_000_000L, 1600);
    }

    /** Sin almacen ni reencodador conectados: lo que pasa hoy en una instalacion sin su implementacion. */
    public ImagenesDelChat servicioSinCosturas() {
        return new ImagenesDelChat(repo, datos.users, proveedor(null), proveedor(null), org.springframework.transaction.support.TransactionOperations.withoutTransaction(), 100, 209_715_200L,
                8L * 1024 * 1024, 40_000_000L, 1600);
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> proveedor(T valor) {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(valor);
        return p;
    }

    // ---------------------------------------------------------------- dobles

    public class AlmacenFalso implements AlmacenDeImagenes {
        public int lecturasDeBytes = 0;

        @Override
        public OffsetDateTime insertar(NuevaImagen n) {
            OffsetDateTime creada = OffsetDateTime.parse("2026-11-11T10:00:00-04:00").plusSeconds(reloj.incrementAndGet());
            filas.put(n.id(), new ImagenDelChat(n.id(), n.diagramId(), n.subidaPor(), n.tipo(), n.bytes(), n.ancho(),
                    n.alto(), n.sha256(), creada, null, null));
            bytes.put(n.id(), n.datos());
            miniaturas.put(n.id(), n.miniatura());
            return creada;
        }

        @Override
        public Optional<byte[]> datos(UUID id) {
            lecturasDeBytes++;
            return Optional.ofNullable(bytes.get(id));
        }

        @Override
        public Optional<byte[]> miniatura(UUID id) {
            lecturasDeBytes++;
            return Optional.ofNullable(miniaturas.get(id));
        }

        @Override
        public OffsetDateTime eliminar(UUID id, UUID quien) {
            ImagenDelChat f = filas.get(id);
            OffsetDateTime cuando = OffsetDateTime.parse("2026-11-12T10:00:00-04:00").plusSeconds(reloj.incrementAndGet());
            filas.put(id, new ImagenDelChat(f.getId(), f.getDiagramId(), f.getUploadedBy(), f.getContentType(),
                    f.getSizeBytes(), f.getWidth(), f.getHeight(), f.getSha256(), f.getCreatedAt(), cuando, quien));
            bytes.remove(id);
            miniaturas.remove(id);
            return cuando;
        }
    }

    /**
     * NO es el reencodador real (pendiente, seccion 7): devuelve los mismos bytes, deduce el tipo por la
     * firma del archivo (no por lo que dijo el cliente) y una miniatura de mentira.
     */
    public static class ReencodadorFalso implements ReencodadorDeImagen {
        @Override
        public Reencodada reencodar(byte[] original, String contentType) {
            try {
                BufferedImage img = ImageIO.read(new ByteArrayInputStream(original));
                String tipo = original.length > 2 && (original[0] & 0xFF) == 0xFF && (original[1] & 0xFF) == 0xD8
                        ? "image/jpeg" : "image/png";
                return new Reencodada(original, tipo, img.getWidth(), img.getHeight(), new byte[]{1, 2, 3});
            } catch (IOException e) {
                throw new ImagenDeEntrada.ImagenInvalida("No se pudo leer la imagen");
            }
        }
    }

    // --------------------------------------------------------------- fixtures

    /** Un PNG liso de `lado` x `lado`; colores distintos dan SHA-256 distintos. */
    public static byte[] png(int lado, Color color) {
        return imagen(lado, color, "png");
    }

    public static byte[] jpeg(int lado, Color color) {
        return imagen(lado, color, "jpg");
    }

    private static byte[] imagen(int lado, Color color, String formato) {
        BufferedImage img = new BufferedImage(lado, lado, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, lado, lado);
        g.setColor(Color.BLACK);
        g.drawRect(2, 2, lado / 2, lado / 2);
        g.dispose();
        try (ByteArrayOutputStream salida = new ByteArrayOutputStream()) {
            ImageIO.write(img, formato, salida);
            return salida.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public List<ImagenDelChat> vivas() {
        return filas.values().stream().filter(f -> !f.estaEliminada()).collect(Collectors.toList());
    }
}
