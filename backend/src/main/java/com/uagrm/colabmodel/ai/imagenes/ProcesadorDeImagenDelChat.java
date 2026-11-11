package com.uagrm.colabmodel.ai.imagenes;

import com.uagrm.colabmodel.ai.AiController.SolicitudDePropuesta.Recorte;
import com.uagrm.colabmodel.ai.PropuestaDeIA;
import com.uagrm.colabmodel.ai.PropuestaDeIA.LecturaDeImagen;
import com.uagrm.colabmodel.modelcore.command.Origin;
import com.uagrm.colabmodel.vision.DeteccionPropuesta;
import com.uagrm.colabmodel.vision.DetectorDeDiagrama;
import com.uagrm.colabmodel.vision.EnriquecedorDeDeteccion;
import com.uagrm.colabmodel.vision.ImagenDeEntrada;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * El turno IMAGEN del chat: lee una imagen YA GUARDADA y devuelve lo que vio (docs/DISENO-CHAT-IMAGENES.md, 5.2 a 5.4).
 *
 * NO pasa por `ServicioDeIA.generar` ni llama a ningun proveedor de texto: corre el detector del nivel 1 y la
 * cadena de {@link EnriquecedorDeDeteccion} con la misma degradacion que `VisionRestController` (el que falla se
 * salta y el turno sale igual). Habla solo con esas interfaces: no nombra ningun proveedor ni modelo.
 *
 * No escribe nada en el modelo: los comandos los arma el cliente a partir de la deteccion (que el usuario puede
 * corregir antes) y los aplica como un BATCH `origin = IMAGE` por el bus. El resultado del recorte y del giro
 * tampoco se guarda: la imagen sigue siendo la misma fila de `ai_images`.
 */
@Service
public class ProcesadorDeImagenDelChat {

    private static final Logger log = LoggerFactory.getLogger(ProcesadorDeImagenDelChat.class);

    /** Lo que devuelve: la propuesta (sin id de turno todavia) y la imagen sobre la que trabajo. */
    public record Lectura(PropuestaDeIA propuesta, ImagenMeta imagen) { }

    private final ImagenesDelChat imagenes;
    private final DetectorDeDiagrama detector;
    private final List<EnriquecedorDeDeteccion> enriquecedores;
    private final ImagenDeEntrada.Limites limites;

    /**
     * @param enriquecedores los niveles 2 disponibles, en el orden de Spring (como en `VisionRestController`)
     */
    public ProcesadorDeImagenDelChat(ImagenesDelChat imagenes, DetectorDeDiagrama detector,
                                     List<EnriquecedorDeDeteccion> enriquecedores,
                                     @Value("${colabmodel.vision.max-pixeles:40000000}") long maxPixeles,
                                     @Value("${colabmodel.vision.max-lado:1600}") int maxLado) {
        this.imagenes = imagenes;
        this.detector = detector;
        this.enriquecedores = enriquecedores;
        // La imagen ya esta guardada: paso los limites de entrada. El peso del binario puede superar los 8 MB de
        // subida (se reencodo, y recortar o girar la pasa a PNG sin perdida), asi que aqui no se vuelve a acotar.
        this.limites = new ImagenDeEntrada.Limites(Integer.MAX_VALUE, maxPixeles, maxLado);
    }

    /**
     * @param giroGrados ya validado por quien llama (hasta 10 grados o multiplo de 90), o null
     * @param recorte    en pixeles de la imagen guardada, o null; aqui se comprueba que cabe
     * @throws ResponseStatusException 404 si la imagen no es de este diagrama o fue eliminada
     * @throws IllegalArgumentException 400 si el recorte no cabe en la imagen guardada
     */
    public Lectura procesar(UUID diagramId, UUID imagenId, Integer giroGrados, Recorte recorte) {
        ImagenesDelChat.Descarga guardada = imagenes.datos(diagramId, imagenId);   // 404
        ImagenMeta meta = imagenes.metaDe(imagenId);
        if (meta == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Esa imagen no existe en este diagrama");
        if (recorte != null) comprobarRecorte(recorte, meta.ancho(), meta.alto());

        int giro = giroGrados == null ? 0 : giroGrados;
        ImagenDeEntrada entrada;
        if (recorte == null && giro == 0) {
            entrada = ImagenDeEntrada.desde(guardada.bytes(), guardada.tipo(), limites);
        } else {
            BufferedImage img = TransformacionDeImagen.decodificar(guardada.bytes());
            // Primero el recorte, en pixeles de la imagen que el usuario ve; despues el giro sobre ese recorte.
            if (recorte != null) img = TransformacionDeImagen.recortar(img, recorte.x(), recorte.y(), recorte.ancho(), recorte.alto());
            if (giro != 0) img = TransformacionDeImagen.girar(img, giro);
            entrada = ImagenDeEntrada.desde(TransformacionDeImagen.aPng(img), "image/png", limites);
        }

        // El Enderezador automatico sigue corriendo dentro del detector: el giro del usuario se suma al fino.
        DeteccionPropuesta base = detector.detectar(entrada);
        List<String> aplicados = new ArrayList<>();
        List<String> fallidos = new ArrayList<>();
        DeteccionPropuesta finalDet = enriquecer(base, entrada, aplicados, fallidos);

        DudasDeLectura.Resultado dudas = DudasDeLectura.calcular(finalDet);
        LecturaDeImagen lectura = new LecturaDeImagen(finalDet, dudas.dudas(), dudas.omitidas(),
                List.copyOf(aplicados), List.copyOf(fallidos),
                new LecturaDeImagen.OpcionesDeReproceso(giroGrados, recorte));
        String modelo = finalDet.metricas() == null ? null : finalDet.metricas().detector();
        PropuestaDeIA propuesta = new PropuestaDeIA(diagramId, Origin.IMAGE, List.of(), List.of(), List.of(), List.of(),
                0, resumen(finalDet, dudas.total()), modelo, null, lectura);
        return new Lectura(propuesta, meta);
    }

    /**
     * Nivel 2 si esta; si no, o si falla, lo que habia (ADR-012). Ademas del `try/catch` de la vision, una capa
     * NO PUEDE BAJAR el numero de clases ni de relaciones (hallazgo de Pepe): el contrato de la interfaz dice que
     * solo rellena huecos, asi que una salida con menos elementos es un incumplimiento y se descarta como si
     * hubiera fallado. Una salida null tambien. El nombre va a `fallidos`.
     */
    private DeteccionPropuesta enriquecer(DeteccionPropuesta base, ImagenDeEntrada entrada,
                                          List<String> aplicados, List<String> fallidos) {
        DeteccionPropuesta actual = base;
        for (EnriquecedorDeDeteccion capa : enriquecedores) {
            String nombre = capa.nombre();
            try {
                if (!capa.disponible()) continue;
                DeteccionPropuesta siguiente = capa.enriquecer(actual, entrada);
                if (siguiente == null || siguiente.clases() == null || siguiente.relaciones() == null) {
                    log.warn("El enriquecedor {} devolvio una deteccion nula; se descarta", nombre);
                    fallidos.add(nombre);
                    continue;
                }
                if (siguiente.clases().size() < actual.clases().size()
                        || siguiente.relaciones().size() < actual.relaciones().size()) {
                    log.warn("El enriquecedor {} bajo las clases de {} a {} o las relaciones de {} a {}; se descarta",
                            nombre, actual.clases().size(), siguiente.clases().size(),
                            actual.relaciones().size(), siguiente.relaciones().size());
                    fallidos.add(nombre);
                    continue;
                }
                actual = siguiente;
                aplicados.add(nombre);
            } catch (RuntimeException e) {
                log.warn("El enriquecedor {} fallo; se sigue con lo que habia", nombre, e);
                fallidos.add(nombre);
            }
        }
        return actual;
    }

    private static void comprobarRecorte(Recorte r, int ancho, int alto) {
        boolean cabe = r.x() >= 0 && r.y() >= 0 && r.ancho() >= 32 && r.alto() >= 32
                && (long) r.x() + r.ancho() <= ancho && (long) r.y() + r.alto() <= alto;
        if (!cabe) {
            throw new IllegalArgumentException("El recorte tiene que estar dentro de la imagen (" + ancho + " × "
                    + alto + ") y medir al menos 32 px");
        }
    }

    /** "Leí 4 clases y 3 relaciones; 5 cosas con dudas." */
    static String resumen(DeteccionPropuesta d, int dudas) {
        int c = d.clases().size();
        int r = d.relaciones().size();
        return "Leí " + c + (c == 1 ? " clase" : " clases") + " y " + r + (r == 1 ? " relación" : " relaciones") + "; "
                + (dudas == 0 ? "sin dudas." : dudas + (dudas == 1 ? " cosa con dudas." : " cosas con dudas."));
    }
}
