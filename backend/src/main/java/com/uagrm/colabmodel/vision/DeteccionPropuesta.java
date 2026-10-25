package com.uagrm.colabmodel.vision;

import com.uagrm.colabmodel.modelcore.metamodel.RelationKind;

import java.util.List;

/**
 * Lo que la herramienta CREE haber visto en una imagen, antes de tocar el modelo.
 *
 * Es una PROPUESTA, no una escritura. Nada de lo que hay aca existe todavia en
 * el diagrama: son candidatos que el panel muestra para que una persona los
 * confirme o los descarte, y recien entonces se convierten en Commands
 * (ADR-002, origin IMAGE). La doctrina es la misma de la Regla 5 y de los 4
 * puntos de ambiguedad: SUGERIR Y CONFIRMAR, NUNCA DECIDIR SOLA.
 *
 * Por que importa tanto aca: un modelo de vision se equivoca de la peor manera
 * posible, inventando texto que parece plausible. Un atributo alucinado que
 * entra al bus sin que nadie lo mire es peor que un atributo faltante, porque
 * el faltante se nota y el inventado no.
 *
 * DOS NIVELES (ADR-012, degradacion elegante):
 *
 *   Nivel 1 - {@link DetectorGeometrico}. Deterministico, sin modelo cargado.
 *             Resuelve la GEOMETRIA y la TOPOLOGIA: que cajas hay, donde
 *             estan y cuales se conectan con cuales. Deja los textos en null
 *             y publica los RECORTES donde habria que leerlos.
 *
 *   Nivel 2 - {@link EnriquecedorDeDeteccion}. Opcional. Lee esos recortes y
 *             completa nombres, atributos, cardinalidades y el simbolo de
 *             cada extremo.
 *
 * El nivel 1 SIEMPRE responde. Si el nivel 2 no esta, falla o tarda de mas, la
 * propuesta sale igual con las cajas sin nombre: fea pero usable, y el usuario
 * escribe los nombres a mano. El sistema nunca deja de responder.
 *
 * EL RECORTE ES EL PUNTO DE TODO ESTO. Sin nivel 1 hay que preguntarle al
 * modelo "leeme esta pizarra y dame un modelo UML" sobre la imagen entera:
 * miles de tokens de imagen, minutos de CPU y una respuesta inventada. Con
 * nivel 1 se le hacen muchas preguntas triviales sobre recortes de 200x60
 * pixeles: "transcribi este texto", "rombo relleno, rombo hueco, triangulo o
 * nada". Un modelo chico acierta esas; la otra no la acierta ni uno grande.
 *
 * @param clases     cajas candidatas
 * @param relaciones lineas candidatas entre cajas
 * @param avisos     lo detectado que NO se puede mapear, en la doctrina de la
 *                   Regla 10: se lista, nunca se ignora en silencio
 * @param metricas   para el panel, y para poder medir en vez de suponer
 */
public record DeteccionPropuesta(
        List<ClaseDetectada> clases,
        List<RelacionDetectada> relaciones,
        List<String> avisos,
        Metricas metricas
) {

    /** Rectangulo en pixeles de la imagen de TRABAJO (la ya escalada). */
    public record Caja(int x, int y, int ancho, int alto) {

        public int x2() { return x + ancho; }

        public int y2() { return y + alto; }

        /** Distancia de un punto al borde del rectangulo; 0 si el punto esta dentro. */
        public double distanciaA(int px, int py) {
            int dx = Math.max(Math.max(x - px, px - x2()), 0);
            int dy = Math.max(Math.max(y - py, py - y2()), 0);
            return Math.sqrt((double) dx * dx + (double) dy * dy);
        }

        public boolean contienePunto(int px, int py) {
            return px >= x && px <= x2() && py >= y && py <= y2();
        }
    }

    /** Que se espera encontrar dentro de un recorte. Decide que se le pregunta al nivel 2. */
    public enum RolDeTexto {
        NOMBRE_CLASE, ESTEREOTIPO, ATRIBUTO, METODO,
        CARDINALIDAD, ROL, NOMBRE_RELACION
    }

    /**
     * Un pedacito de imagen donde el nivel 1 vio tinta pero no sabe que dice.
     *
     * @param texto     null hasta que el nivel 2 lo lea. null NO es un error:
     *                  es el estado normal cuando no hay modelo cargado
     * @param confianza 0..1; vale 0 mientras `texto` sea null
     */
    public record RecorteDeTexto(String ref, RolDeTexto rol, Caja caja,
                                 String texto, double confianza) {

        public static RecorteDeTexto vacio(String ref, RolDeTexto rol, Caja caja) {
            return new RecorteDeTexto(ref, rol, caja, null, 0.0);
        }
    }

    public enum Extremo { ORIGEN, DESTINO }

    /**
     * El recorte del extremo de una linea, que es donde vive el rombo o el triangulo.
     *
     * Es la deteccion mas dificil para la CV sola y la mas facil para un modelo
     * chico si se le da recortada: son cuatro figuras posibles en 80x80 pixeles.
     *
     * @param figura null hasta que el nivel 2 la clasifique. Mientras tanto la
     *               relacion queda en ASSOCIATION, que es la lectura mas
     *               conservadora: no impone ON DELETE CASCADE ni PK compartida
     */
    public record RecorteDeSimbolo(String ref, Extremo extremo, Caja caja,
                                   RelationKind figura, double confianza) {

        public static RecorteDeSimbolo vacio(String ref, Extremo extremo, Caja caja) {
            return new RecorteDeSimbolo(ref, extremo, caja, null, 0.0);
        }
    }

    /**
     * Una caja candidata a clase.
     *
     * @param ref       identificador estable DENTRO de esta propuesta ("c1").
     *                  No es un UUID: nada existe todavia. Las relaciones se
     *                  refieren a las clases por este ref, y al confirmar es
     *                  lo unico que hay que traducir a UUID reales
     * @param caja      en pixeles de la imagen de trabajo
     * @param lienzo    la misma caja llevada a coordenadas del canvas, para que
     *                  el diagrama aterrice con la disposicion que tenia en la
     *                  imagen y no apilado en el origen
     * @param nombre    null hasta el nivel 2
     * @param confianza 0..1, segun que tan cerrado quedo el rectangulo
     */
    public record ClaseDetectada(
            String ref,
            Caja caja,
            Caja lienzo,
            String nombre,
            RecorteDeTexto recorteNombre,
            List<RecorteDeTexto> atributos,
            List<RecorteDeTexto> metodos,
            int compartimentos,
            double confianza
    ) { }

    /**
     * Una linea candidata a relacion.
     *
     * @param origenRef  ref de la clase de un extremo
     * @param destinoRef ref de la del otro. Igual a `origenRef` si es
     *                   reflexiva: una clase relacionada consigo misma es
     *                   valida y el modelo ya la soporta
     * @param kind       ASSOCIATION mientras el nivel 2 no diga otra cosa
     */
    public record RelacionDetectada(
            String ref,
            String origenRef,
            String destinoRef,
            RelationKind kind,
            RecorteDeSimbolo simboloOrigen,
            RecorteDeSimbolo simboloDestino,
            RecorteDeTexto cardinalidadOrigen,
            RecorteDeTexto cardinalidadDestino,
            double confianza
    ) { }

    /**
     * La imagen se roto para enderezarla, y cuanto.
     *
     * Va en las metricas porque CAMBIA EL MARCO DE REFERENCIA: todas las Caja
     * de la propuesta estan en la imagen ya enderezada, no en la que se subio.
     * Con el angulo y los dos tamanos, quien quiera dibujar encima de la foto
     * original puede deshacer la rotacion --se roto alrededor del centro-- y
     * despues dividir por `escala`.
     *
     * @param grados       lo que se roto; el mismo signo que se le paso a rotar
     * @param anchoPrevio  tamano ANTES de enderezar (el centro de rotacion sale de aca)
     * @param ganancia     cuanto mejoro la concentracion de tinta al enderezar
     */
    public record Enderezado(double grados, int anchoPrevio, int altoPrevio, double ganancia) { }

    /**
     * Para el panel, y para poder MEDIR en que condiciones esto sirve.
     *
     * @param anchoTrabajo el marco en el que estan TODAS las Caja de la
     *                     propuesta: despues de reescalar y de enderezar
     * @param enderezado   ausente si no hizo falta rotar
     * @param binarizacion "global" con el umbral unico de Otsu, o "adaptativa"
     *                     cuando la imagen tenia tanto gris intermedio que hubo
     *                     que calcular un umbral por ventana. Se informa porque
     *                     cambia como hay que leer todo lo demas: con umbral
     *                     adaptativo el `umbral` de arriba solo gobierna las
     *                     zonas lisas
     * @param rectasSueltas cuantas relaciones salieron de ajustar una recta
     *                     sobre la tinta que el analisis de corridas no explico
     *                     --las diagonales y las punteadas--. Son las menos
     *                     seguras de la propuesta, y conviene que el panel lo
     *                     pueda decir sin tener que adivinarlo
     * @param pareceFoto   muchos pixeles en gris intermedio: iluminacion
     *                     despareja o ruido de camara. Es la senal de que la
     *                     deteccion va a ser peor, y de que conviene una
     *                     captura digital antes que una foto
     */
    public record Metricas(
            int anchoOriginal, int altoOriginal,
            int anchoTrabajo, int altoTrabajo,
            double escala,
            Enderezado enderezado,
            int umbral,
            String binarizacion,
            int segmentos,
            int rectasSueltas,
            long milis,
            boolean pareceFoto,
            String detector
    ) { }

    public static DeteccionPropuesta vacia(Metricas metricas, List<String> avisos) {
        return new DeteccionPropuesta(List.of(), List.of(), avisos, metricas);
    }
}
