package com.uagrm.colabmodel.vision;

import com.uagrm.colabmodel.vision.DeteccionPropuesta.ClaseDetectada;
import com.uagrm.colabmodel.vision.DeteccionPropuesta.RecorteDeTexto;
import com.uagrm.colabmodel.vision.DeteccionPropuesta.RelacionDetectada;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * NIVEL 2 SIN MODELO: lee los textos con {@link OcrDePlantillas}.
 *
 * Es la capa que le faltaba a la maquina por defecto. Hasta ahora, sin un modelo
 * local levantado, la propuesta salia con TODAS las cajas sin nombre y el usuario
 * tenia que tipear cada clase, cada atributo y cada cardinalidad a mano: el nivel
 * 1 le ahorraba el dibujo y nada del texto. Y el texto es la mayor parte del
 * trabajo de cargar un diagrama.
 *
 * VA ANTES DEL MODELO (Order 15 contra 20) y eso es a proposito:
 *
 *   - lo que se lee aca sale gratis --milisegundos de CPU, sin red y sin
 *     servidor-- asi que el presupuesto de tiempo del modelo queda libre para lo
 *     que esto NO sabe leer, que es cualquier cosa manuscrita;
 *   - y la lectura por plantillas no inventa. Cuando no reconoce deja null, y ese
 *     null es justamente lo que el modelo va a intentar despues.
 *
 * NO PISA NADA QUE YA ESTE LEIDO, ni suyo ni de otra capa: la cadena de
 * enriquecedores se suma, no se sobreescribe. Es el mismo contrato que cumple el
 * geometrico con los simbolos.
 *
 * SE PUEDE APAGAR con `colabmodel.vision.nivel2.plantillas: false`, para poder
 * comparar contra el modelo --o para descartarlo de una medicion-- sin tocar el
 * codigo. Viene encendido porque no depende de nada instalado.
 */
@Component
@Order(15)
public class EnriquecedorPorPlantillas implements EnriquecedorDeDeteccion {

    /**
     * Cuanto vale una lectura por plantillas en la propuesta.
     *
     * Mas alta que la del modelo (0.7) y no por optimismo: esta lectura es
     * DETERMINISTICA y ya vino filtrada por un umbral de parecido, asi que cuando
     * contesta es porque el dibujo de la letra coincidio. Igual se confirma en el
     * panel, como todo lo demas.
     */
    private static final double CONFIANZA_BASE = 0.8;

    /** Un recorte mas chico que esto no puede contener texto legible. */
    private static final int MIN_LADO = 6;

    /**
     * Tope de tiempo para todos los recortes de una imagen.
     *
     * Es holgado porque la PRIMERA lectura del proceso no paga la lectura sino el
     * calentamiento: medido sobre un diagrama de tres clases y nueve renglones,
     * 6,3 s la primera vez y 0,24 s de ahi en adelante. La diferencia es AWT
     * inicializando su motor de fuentes y dibujando los atlas, que despues quedan
     * cacheados para todo el proceso. Con un tope de 4 s, la primera peticion
     * perdia los ultimos renglones --la segunda clase salia sin nombre-- que es el
     * peor lugar donde ahorrar: la propuesta parece incompleta y no se entiende por
     * que.
     *
     * Igual existe, porque este es el unico endpoint que recibe un binario de
     * afuera: una captura con quinientos renglones no puede quedarse con el hilo.
     * Al agotarse se devuelve lo leido hasta ahi, igual que el nivel 2b.
     */
    private static final long PRESUPUESTO_MS = 12_000;

    private final boolean activo;

    public EnriquecedorPorPlantillas(
            @Value("${colabmodel.vision.nivel2.plantillas:true}") boolean activo) {
        this.activo = activo;
    }

    @Override
    public String nombre() {
        return "plantillas (OCR local)";
    }

    /**
     * Depende de que este proceso pueda dibujar texto, y eso no se puede suponer.
     *
     * En un escritorio siempre se puede. En un contenedor pelado no hay ninguna
     * fuente instalada y `java.awt.Font` no dibuja nada: ahi esta capa se declara
     * caida y la deteccion sigue por el nivel 1, que es exactamente la degradacion
     * que pide el ADR-012.
     */
    @Override
    public boolean disponible() {
        return activo && AtlasDeGlifos.disponible();
    }

    @Override
    public DeteccionPropuesta enriquecer(DeteccionPropuesta base, ImagenDeEntrada imagen) {
        Reloj reloj = new Reloj(PRESUPUESTO_MS);
        int[] cuenta = new int[2];   // {leidos, intentados}

        List<ClaseDetectada> clases = new ArrayList<>(base.clases().size());
        for (ClaseDetectada c : base.clases()) {
            RecorteDeTexto nombre = leer(c.recorteNombre(), imagen, reloj, cuenta);
            clases.add(new ClaseDetectada(c.ref(), c.caja(), c.lienzo(),
                    nombre == null ? c.nombre() : nombre.texto(),
                    nombre,
                    leerTodos(c.atributos(), imagen, reloj, cuenta),
                    leerTodos(c.metodos(), imagen, reloj, cuenta),
                    c.compartimentos(), c.confianza()));
        }

        List<RelacionDetectada> relaciones = new ArrayList<>(base.relaciones().size());
        for (RelacionDetectada r : base.relaciones()) {
            relaciones.add(new RelacionDetectada(r.ref(), r.origenRef(), r.destinoRef(), r.kind(),
                    r.simboloOrigen(), r.simboloDestino(),
                    leer(r.cardinalidadOrigen(), imagen, reloj, cuenta),
                    leer(r.cardinalidadDestino(), imagen, reloj, cuenta),
                    r.confianza()));
        }

        List<String> avisos = new ArrayList<>(base.avisos());
        agregarAviso(avisos, cuenta, reloj);
        return new DeteccionPropuesta(List.copyOf(clases), List.copyOf(relaciones),
                List.copyOf(avisos), base.metricas());
    }

    /**
     * El aviso dice CUANTO se leyo, no que se leyo bien.
     *
     * Que la mitad de los renglones quede en blanco es un resultado normal --texto
     * manuscrito, muy chico o con una fuente rara-- pero sin decirlo parece un
     * error del sistema. Y cuando si se leyo, lo que hace falta avisar es lo
     * contrario: que hay que mirarlo antes de confirmar, porque un nombre de clase
     * mal leido termina siendo el nombre de una tabla.
     */
    private void agregarAviso(List<String> avisos, int[] cuenta, Reloj reloj) {
        int leidos = cuenta[0];
        int intentados = cuenta[1];
        if (intentados == 0) return;

        if (leidos == 0) {
            avisos.add("No se pudo leer ninguno de los " + intentados + " textos con el lector "
                    + "local: pasa con letra manuscrita, con texto muy chico o con una fuente "
                    + "que no se parece a ninguna de las probadas. Los nombres se cargan a mano "
                    + "en el panel, o se enciende el modelo de vision del nivel 2b.");
            return;
        }
        String cuanto = leidos + " de " + intentados + " textos";
        if (leidos < intentados) {
            avisos.add("Se leyeron " + cuanto + " con el lector local; el resto quedo en blanco "
                    + "para completar a mano. Conviene revisar los nombres leidos antes de "
                    + "confirmar: una letra cambiada termina siendo el nombre de una tabla.");
        } else {
            avisos.add("Se leyeron los " + intentados + " textos con el lector local. Conviene "
                    + "revisarlos antes de confirmar: una letra cambiada --una l por una I, "
                    + "que en muchas fuentes son el mismo dibujo-- termina siendo el nombre de "
                    + "una tabla.");
        }
        if (reloj.agotado()) {
            avisos.add("El lector local se quedo sin tiempo: lo que falta quedo sin texto.");
        }
    }

    private List<RecorteDeTexto> leerTodos(List<RecorteDeTexto> recortes, ImagenDeEntrada imagen,
                                           Reloj reloj, int[] cuenta) {
        List<RecorteDeTexto> leidos = new ArrayList<>(recortes.size());
        for (RecorteDeTexto r : recortes) leidos.add(leer(r, imagen, reloj, cuenta));
        return List.copyOf(leidos);
    }

    private RecorteDeTexto leer(RecorteDeTexto recorte, ImagenDeEntrada imagen, Reloj reloj,
                                int[] cuenta) {
        if (recorte == null || recorte.texto() != null) return recorte;
        if (recorte.caja() == null || recorte.caja().ancho() < MIN_LADO
                || recorte.caja().alto() < MIN_LADO) {
            return recorte;
        }
        if (reloj.agotado()) return recorte;

        cuenta[1]++;
        OcrDePlantillas.Lectura lectura =
                OcrDePlantillas.leer(imagen.recortar(recorte.caja()), recorte.rol());
        if (lectura == null) return recorte;

        cuenta[0]++;
        // La confianza que se publica combina las dos: la del calce de los glifos y
        // el techo de la capa. El panel ordena por esto, asi que un renglon que
        // calzo justo tiene que verse distinto de uno que calzo perfecto.
        return new RecorteDeTexto(recorte.ref(), recorte.rol(), recorte.caja(),
                lectura.texto(), CONFIANZA_BASE * lectura.confianza());
    }

    /** Presupuesto de tiempo para toda la imagen. */
    private static final class Reloj {

        private final long hasta;

        Reloj(long presupuestoMs) {
            this.hasta = System.nanoTime() + presupuestoMs * 1_000_000;
        }

        boolean agotado() {
            return System.nanoTime() > hasta;
        }
    }
}
