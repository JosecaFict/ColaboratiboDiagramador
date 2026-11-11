package com.uagrm.colabmodel.ai.imagenes;

import com.uagrm.colabmodel.ai.PropuestaDeIA.LecturaDeImagen.Duda;
import com.uagrm.colabmodel.ai.PropuestaDeIA.LecturaDeImagen.TipoDeDuda;
import com.uagrm.colabmodel.vision.DeteccionPropuesta;
import com.uagrm.colabmodel.vision.DeteccionPropuesta.ClaseDetectada;
import com.uagrm.colabmodel.vision.DeteccionPropuesta.RecorteDeSimbolo;
import com.uagrm.colabmodel.vision.DeteccionPropuesta.RecorteDeTexto;
import com.uagrm.colabmodel.vision.DeteccionPropuesta.RelacionDetectada;
import com.uagrm.colabmodel.vision.DeteccionPropuesta.RolDeTexto;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Que cosas de una lectura de imagen conviene que una persona mire (docs/DISENO-CHAT-IMAGENES.md, 5.4).
 *
 * Es determinista y no usa ningun modelo: solo mira lo que la deteccion ya trae. Las dudas son informativas:
 * no bloquean nada, el cliente las muestra y el usuario las corrige antes de aplicar.
 */
public final class DudasDeLectura {

    /** Texto: por debajo de esto el propio lector duda. Una capa real publica 0.7 (modelo) o 0.8 x calce (plantillas). */
    public static final double UMBRAL_DE_TEXTO = 0.60;
    /** Forma: que tan cerrado quedo un rectangulo o que tan clara es una relacion. */
    public static final double UMBRAL_DE_FORMA = 0.50;
    public static final int MAX_DUDAS = 50;

    private static final Set<RolDeTexto> ROLES_QUE_NO_PUEDEN_ESTAR_VACIOS =
            Set.of(RolDeTexto.NOMBRE_RELACION, RolDeTexto.ATRIBUTO, RolDeTexto.METODO);

    /** `dudas` ya viene ordenada y con el tope aplicado; `omitidas` es lo que quedo afuera. */
    public record Resultado(List<Duda> dudas, int omitidas) {
        public int total() { return dudas.size() + omitidas; }
    }

    private DudasDeLectura() { }

    public static Resultado calcular(DeteccionPropuesta d) {
        List<Duda> todas = new ArrayList<>();
        boolean huboTexto = huboLecturaDeTexto(d);
        if (!huboTexto) {
            // Una sola para toda la imagen: reemplaza a todas las TEXTO_VACIO (no tiene sentido pedir 40 veces lo mismo).
            todas.add(new Duda(TipoDeDuda.SIN_LECTURA_DE_TEXTO, null, null, null, null, 0.0,
                    "No se leyó ningún texto de la imagen: los nombres, atributos y métodos hay que escribirlos a mano"));
        }
        for (ClaseDetectada c : d.clases()) {
            if (huboTexto && enBlanco(c.nombre())) {
                todas.add(new Duda(TipoDeDuda.CLASE_SIN_NOMBRE, c.ref(), refDe(c.recorteNombre()),
                        c.recorteNombre() == null ? RolDeTexto.NOMBRE_CLASE : c.recorteNombre().rol(), null,
                        c.confianza(), "La clase " + c.ref() + " no tiene nombre"));
            }
            for (RecorteDeTexto r : textosDe(c)) agregarDeTexto(todas, huboTexto, c.ref(), r);
            if (c.confianza() < UMBRAL_DE_FORMA) {
                todas.add(new Duda(TipoDeDuda.CLASE_POCO_CLARA, c.ref(), null, null, null, c.confianza(),
                        "El rectángulo de la clase " + c.ref() + " quedó poco claro"));
            }
        }
        for (RelacionDetectada r : d.relaciones()) {
            agregarDeSimbolo(todas, r, r.simboloOrigen());
            agregarDeSimbolo(todas, r, r.simboloDestino());
            agregarDeTexto(todas, huboTexto, r.ref(), r.cardinalidadOrigen());
            agregarDeTexto(todas, huboTexto, r.ref(), r.cardinalidadDestino());
            if (r.confianza() < UMBRAL_DE_FORMA) {
                todas.add(new Duda(TipoDeDuda.RELACION_POCO_CLARA, r.ref(), null, null, null, r.confianza(),
                        "La relación " + r.ref() + " quedó poco clara"));
            }
        }
        // Sort estable: primero la gravedad (la de la tabla del contrato), despues la confianza ascendente.
        todas.sort(Comparator.<Duda>comparingInt(x -> gravedad(x.tipo())).thenComparingDouble(Duda::confianza));
        if (todas.size() <= MAX_DUDAS) return new Resultado(List.copyOf(todas), 0);
        return new Resultado(List.copyOf(todas.subList(0, MAX_DUDAS)), todas.size() - MAX_DUDAS);
    }

    private static void agregarDeSimbolo(List<Duda> dudas, RelacionDetectada r, RecorteDeSimbolo s) {
        if (s != null && s.figura() == null) {
            dudas.add(new Duda(TipoDeDuda.RELACION_SIN_TIPO, r.ref(), s.ref(), null, null, s.confianza(),
                    "No se reconoció la figura del extremo de la relación " + r.ref()
                            + "; quedó como asociación por defecto"));
        }
    }

    private static void agregarDeTexto(List<Duda> dudas, boolean huboTexto, String elementoRef, RecorteDeTexto r) {
        if (r == null || !huboTexto) return;
        if (enBlanco(r.texto())) {
            if (ROLES_QUE_NO_PUEDEN_ESTAR_VACIOS.contains(r.rol())) {
                dudas.add(new Duda(TipoDeDuda.TEXTO_VACIO, elementoRef, r.ref(), r.rol(), null, r.confianza(),
                        "No se pudo leer el texto (" + r.rol() + ") de " + elementoRef));
            }
        } else if (r.confianza() < UMBRAL_DE_TEXTO) {
            dudas.add(new Duda(TipoDeDuda.TEXTO_INCIERTO, elementoRef, r.ref(), r.rol(), r.texto(), r.confianza(),
                    "El texto leído en " + elementoRef + " es poco seguro: revisalo"));
        }
    }

    /** Mas baja = mas grave. */
    private static int gravedad(TipoDeDuda tipo) {
        return switch (tipo) {
            case SIN_LECTURA_DE_TEXTO -> 1;
            case CLASE_SIN_NOMBRE -> 2;
            case RELACION_SIN_TIPO -> 3;
            case TEXTO_VACIO -> 4;
            case TEXTO_INCIERTO -> 5;
            case RELACION_POCO_CLARA -> 6;
            case CLASE_POCO_CLARA -> 7;
        };
    }

    /** Algun recorte de la deteccion final trae texto no vacio (texto == null es el estado normal sin nivel 2). */
    static boolean huboLecturaDeTexto(DeteccionPropuesta d) {
        for (ClaseDetectada c : d.clases()) {
            for (RecorteDeTexto r : textosDe(c)) if (!enBlanco(r.texto())) return true;
        }
        for (RelacionDetectada r : d.relaciones()) {
            if (r.cardinalidadOrigen() != null && !enBlanco(r.cardinalidadOrigen().texto())) return true;
            if (r.cardinalidadDestino() != null && !enBlanco(r.cardinalidadDestino().texto())) return true;
        }
        return false;
    }

    private static List<RecorteDeTexto> textosDe(ClaseDetectada c) {
        List<RecorteDeTexto> l = new ArrayList<>();
        if (c.recorteNombre() != null) l.add(c.recorteNombre());
        if (c.atributos() != null) l.addAll(c.atributos());
        if (c.metodos() != null) l.addAll(c.metodos());
        return l;
    }

    private static String refDe(RecorteDeTexto r) {
        return r == null ? null : r.ref();
    }

    private static boolean enBlanco(String s) {
        return s == null || s.isBlank();
    }
}
