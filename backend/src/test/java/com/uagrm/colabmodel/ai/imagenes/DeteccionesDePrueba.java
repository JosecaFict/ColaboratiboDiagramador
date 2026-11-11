package com.uagrm.colabmodel.ai.imagenes;

import com.uagrm.colabmodel.modelcore.metamodel.RelationKind;
import com.uagrm.colabmodel.vision.DeteccionPropuesta;
import com.uagrm.colabmodel.vision.DeteccionPropuesta.Caja;
import com.uagrm.colabmodel.vision.DeteccionPropuesta.ClaseDetectada;
import com.uagrm.colabmodel.vision.DeteccionPropuesta.RecorteDeSimbolo;
import com.uagrm.colabmodel.vision.DeteccionPropuesta.RecorteDeTexto;
import com.uagrm.colabmodel.vision.DeteccionPropuesta.RelacionDetectada;
import com.uagrm.colabmodel.vision.DeteccionPropuesta.RolDeTexto;
import com.uagrm.colabmodel.vision.DetectorDeDiagrama;
import com.uagrm.colabmodel.vision.EnriquecedorDeDeteccion;
import com.uagrm.colabmodel.vision.ImagenDeEntrada;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.function.UnaryOperator;

/** Detecciones armadas a mano y dobles del detector y de los enriquecedores: ningun modelo real. */
public final class DeteccionesDePrueba {

    private DeteccionesDePrueba() { }

    public static final Caja CAJA = new Caja(0, 0, 10, 10);

    public static DeteccionPropuesta.Metricas metricas() {
        return new DeteccionPropuesta.Metricas(100, 100, 100, 100, 1.0, null, 128, "otsu", 0, 0, 1L, false,
                "geometrico");
    }

    public static RecorteDeTexto texto(String ref, RolDeTexto rol, String texto, double confianza) {
        return new RecorteDeTexto(ref, rol, CAJA, texto, confianza);
    }

    /** Una clase con su nombre leido (o null) y la confianza de su rectangulo. */
    public static ClaseDetectada clase(String ref, String nombre, double confianza) {
        RecorteDeTexto nom = nombre == null ? RecorteDeTexto.vacio(ref + "-n", RolDeTexto.NOMBRE_CLASE, CAJA)
                : texto(ref + "-n", RolDeTexto.NOMBRE_CLASE, nombre, 0.9);
        return new ClaseDetectada(ref, CAJA, CAJA, nombre, nom, List.of(), List.of(), 1, confianza);
    }

    public static ClaseDetectada claseConAtributos(String ref, String nombre, List<RecorteDeTexto> atributos) {
        return new ClaseDetectada(ref, CAJA, CAJA, nombre, texto(ref + "-n", RolDeTexto.NOMBRE_CLASE, nombre, 0.9),
                atributos, List.of(), 2, 0.9);
    }

    public static RelacionDetectada relacion(String ref, String origen, String destino, RelationKind kind,
                                             RecorteDeSimbolo simboloDestino, double confianza) {
        return new RelacionDetectada(ref, origen, destino, kind, null, simboloDestino, null, null, confianza);
    }

    public static RecorteDeSimbolo simbolo(String ref, RelationKind figura, double confianza) {
        return new RecorteDeSimbolo(ref, DeteccionPropuesta.Extremo.DESTINO, CAJA, figura, confianza);
    }

    public static DeteccionPropuesta deteccion(List<ClaseDetectada> clases, List<RelacionDetectada> relaciones) {
        return new DeteccionPropuesta(clases, relaciones, List.of(), metricas());
    }

    /** Dos clases y una relacion, todo con confianza alta y sin texto (lo que da el nivel 1 solo). */
    public static DeteccionPropuesta nivel1() {
        return deteccion(List.of(clase("c1", null, 0.9), clase("c2", null, 0.9)),
                List.of(relacion("r1", "c1", "c2", RelationKind.ASSOCIATION, null, 0.9)));
    }

    /** Lo mismo con los nombres ya leidos por un enriquecedor. */
    public static DeteccionPropuesta conNombres() {
        return deteccion(List.of(clase("c1", "Cliente", 0.9), clase("c2", "Pedido", 0.9)),
                List.of(relacion("r1", "c1", "c2", RelationKind.ASSOCIATION, null, 0.9)));
    }

    // ---------------------------------------------------------------- dobles

    /** Detector que devuelve lo que se le diga y anota la imagen que recibio. */
    public static class DetectorFalso implements DetectorDeDiagrama {
        public DeteccionPropuesta resultado = nivel1();
        public ImagenDeEntrada ultima;
        public int llamadas;

        @Override
        public DeteccionPropuesta detectar(ImagenDeEntrada imagen) {
            ultima = imagen;
            llamadas++;
            return resultado;
        }

        @Override
        public String nombre() { return "detector-falso"; }
    }

    /** Capa de nivel 2 de mentira: aplica una transformacion, o lanza, o no esta disponible. */
    public static class EnriquecedorFalso implements EnriquecedorDeDeteccion {
        private final String nombre;
        public UnaryOperator<DeteccionPropuesta> transformacion = d -> d;
        public RuntimeException lanza;
        public boolean disponible = true;
        public int llamadas;

        public EnriquecedorFalso(String nombre) { this.nombre = nombre; }

        @Override
        public DeteccionPropuesta enriquecer(DeteccionPropuesta base, ImagenDeEntrada imagen) {
            llamadas++;
            if (lanza != null) throw lanza;
            return transformacion.apply(base);
        }

        @Override
        public String nombre() { return nombre; }

        @Override
        public boolean disponible() { return disponible; }
    }

    // -------------------------------------------------------------- imagenes

    /**
     * Un PNG de `ancho` x `alto` en gris 100 con un bloque blanco de 10x10 en (mx, my): sirve para ver a donde fue
     * a parar cada pixel tras recortar y girar.
     */
    public static byte[] pngConMarca(int ancho, int alto, int mx, int my) {
        BufferedImage img = new BufferedImage(ancho, alto, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(100, 100, 100));
        g.fillRect(0, 0, ancho, alto);
        g.setColor(Color.WHITE);
        g.fillRect(mx, my, 10, 10);
        g.dispose();
        return aPng(img);
    }

    public static byte[] aPng(BufferedImage img) {
        try (ByteArrayOutputStream salida = new ByteArrayOutputStream()) {
            ImageIO.write(img, "png", salida);
            return salida.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
