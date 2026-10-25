package com.uagrm.colabmodel.vision;

import com.uagrm.colabmodel.modelcore.metamodel.RelationKind;
import com.uagrm.colabmodel.vision.DeteccionPropuesta.Caja;
import com.uagrm.colabmodel.vision.DeteccionPropuesta.ClaseDetectada;
import com.uagrm.colabmodel.vision.DeteccionPropuesta.Extremo;
import com.uagrm.colabmodel.vision.DeteccionPropuesta.Metricas;
import com.uagrm.colabmodel.vision.DeteccionPropuesta.RecorteDeSimbolo;
import com.uagrm.colabmodel.vision.DeteccionPropuesta.RecorteDeTexto;
import com.uagrm.colabmodel.vision.DeteccionPropuesta.RelacionDetectada;
import com.uagrm.colabmodel.vision.DeteccionPropuesta.RolDeTexto;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * NIVEL 1 (ADR-012): deteccion DETERMINISTICA, sin ningun modelo cargado.
 *
 * Resuelve la geometria y la topologia de un diagrama de clases: que
 * rectangulos hay, en que compartimentos estan divididos, donde hay texto
 * dentro de cada uno, y que lineas unen que cajas. No lee una sola letra --eso
 * es trabajo del nivel 2-- pero deja publicado el recorte exacto donde habria
 * que leerla.
 *
 * POR QUE JAVA PELADO Y NO OPENCV. Un diagrama de clases es arte lineal: casi
 * todo son segmentos rectos, largos y de alto contraste. Eso se resuelve con
 * umbral de Otsu y analisis de corridas, que son cien lineas de aritmetica
 * sobre un byte[]. Traer OpenCV significa binarios nativos por plataforma en
 * el pom, que es un riesgo en CI y en la maquina de cada integrante a cambio
 * de nada para el caso que mas importa: la imagen digital limpia --captura de
 * proyector, screenshot, PDF exportado-- donde esto acierta casi siempre.
 * Cuando haga falta atacar la foto torcida con brillo, esa es otra
 * implementacion de {@link DetectorDeDiagrama} y no toca nada de aca.
 *
 * QUE SI Y QUE NO:
 *   - cajas y sus compartimentos: muy bien en imagen limpia;
 *   - lineas entre cajas, incluso quebradas en L y reflexivas: bien;
 *   - el rombo o el triangulo del extremo: NO. Se recorta y se delega;
 *   - cualquier texto: NO. Se recorta y se delega.
 *
 * Es SIN ESTADO a proposito: Spring lo tiene como singleton y dos peticiones
 * pueden entrar a la vez. Todo el estado de una deteccion vive en Ejecucion.
 */
@Component
public class DetectorGeometrico implements DetectorDeDiagrama {

    public static final String NOMBRE = "geometrico";

    @Override
    public String nombre() {
        return NOMBRE;
    }

    /**
     * Enderezar primero, detectar despues.
     *
     * Son dos pasadas sobre la imagen y vale la pena: sin enderezar, UN grado
     * de inclinacion ya deja la deteccion en cero clases, porque los bordes se
     * astillan en escalones mas cortos que el minimo. La estimacion del angulo
     * es barata --proyecta puntos, no rota la imagen-- y si el diagrama ya
     * estaba derecho no se rota nada y la segunda pasada es la unica.
     */
    @Override
    public DeteccionPropuesta detectar(ImagenDeEntrada imagen) {
        Enderezador.Inclinacion inclinacion = Enderezador.estimar(imagen);
        if (!inclinacion.valeLaPena()) {
            return new Ejecucion(imagen, null).correr();
        }
        DeteccionPropuesta.Enderezado enderezado = new DeteccionPropuesta.Enderezado(
                inclinacion.grados(), imagen.ancho(), imagen.alto(), inclinacion.ganancia());
        return new Ejecucion(imagen.rotada(inclinacion.grados()), enderezado).correr();
    }

    // ===================================================================== //

    /** Estado de UNA deteccion. Nace y muere dentro de detectar(). */
    private static final class Ejecucion {

        /** Huecos de hasta 2 px no cortan una linea: el antialiasing los abre solo. */
        private static final int GAP = 2;

        private final ImagenDeEntrada imagen;
        private final int w;
        private final int h;
        private final Binarizacion.Mapa mapa;
        private final boolean pareceFoto;
        private final DeteccionPropuesta.Enderezado enderezado;

        /** Tolerancia de alineacion. Todo lo demas se deriva del lado mayor. */
        private final int tol;
        private final int minLargo;
        private final int minAncho;
        private final int minAlto;
        private final int ladoSimbolo;

        private final List<String> avisos = new ArrayList<>();
        private final long empezo = System.nanoTime();

        /** Tope de segmentos por orientacion: el emparejado de cajas es cuadratico. */
        private static final int MAX_SEGMENTOS = 400;

        /**
         * Tope de rectas buscadas en la tinta suelta.
         *
         * Un diagrama de clases no tiene ocho conectores diagonales; si
         * aparecieran mas, lo que hay es ruido alineado, y proponer veinte
         * relaciones dudosas es peor que proponer ninguna: el panel de
         * confirmacion deja de ser revisable.
         */
        private static final int MAX_RECTAS = 8;

        Ejecucion(ImagenDeEntrada imagen, DeteccionPropuesta.Enderezado enderezado) {
            this.imagen = imagen;
            this.enderezado = enderezado;
            this.w = imagen.ancho();
            this.h = imagen.alto();
            int lado = Math.max(w, h);
            this.tol = Math.max(6, lado / 200);
            this.minLargo = Math.max(12, lado / 80);
            this.minAncho = Math.max(30, lado / 40);
            this.minAlto = Math.max(20, lado / 50);
            this.ladoSimbolo = Math.max(20, Math.min(56, lado / 28));
            this.mapa = Binarizacion.de(imagen);
            this.pareceFoto = mapa.adaptativa();
        }

        private boolean tintaEn(int x, int y) {
            return mapa.en(x, y);
        }

        DeteccionPropuesta correr() {
            List<Segmento> horizontales = fusionarColineales(acotar(extraer(true), "horizontales"));
            List<Segmento> verticales = fusionarColineales(acotar(extraer(false), "verticales"));
            int segmentos = horizontales.size() + verticales.size();

            List<CajaCruda> cajas = detectarCajas(horizontales, verticales);
            marcarTintaInterna(horizontales, verticales, cajas);

            List<ClaseDetectada> clases = construirClases(cajas, horizontales);
            List<RelacionDetectada> relaciones =
                    new ArrayList<>(detectarRelaciones(horizontales, verticales, cajas, clases));

            // El mapa de lo ya interpretado sirve para las dos cosas que faltan,
            // en este orden: buscar las lineas que el analisis de corridas no ve
            // --diagonales y punteadas-- y despues avisar de lo que aun asi
            // quedo sin leer. Antes se construia solo para el aviso, asi que la
            // diagonal se detectaba lo suficiente para nombrarla y no para
            // proponerla.
            boolean[] explicado = mapaExplicado(horizontales, verticales, cajas);
            int rectasSueltas = agregarRectasSueltas(explicado, cajas, clases, relaciones);

            if (clases.isEmpty()) {
                avisos.add("No se reconocio ninguna caja. El nivel 1 busca rectangulos cerrados: "
                        + "si el diagrama esta a mano alzada o la foto esta muy torcida, conviene "
                        + "una captura digital o cargar las clases a mano.");
            }
            if (pareceFoto) {
                avisos.add("La imagen tiene mucho gris intermedio (iluminacion despareja o ruido), "
                        + "asi que se binarizo con umbral adaptativo por ventanas en vez de con uno "
                        + "global. Igual va a leerse peor que una captura de pantalla o del proyector.");
            }
            avisarTintaNoExplicada(explicado, cajas, clases, relaciones);

            return new DeteccionPropuesta(clases, List.copyOf(relaciones), List.copyOf(avisos),
                    new Metricas(
                            imagen.anchoOriginal(), imagen.altoOriginal(), w, h, imagen.escala(),
                            enderezado, mapa.umbral(), mapa.adaptativa() ? "adaptativa" : "global",
                            segmentos, rectasSueltas,
                            (System.nanoTime() - empezo) / 1_000_000, pareceFoto, NOMBRE));
        }

        // ------------------------------------------------- TINTA NO EXPLICADA

        /**
         * Que pixeles ya tienen dueno: los de una caja y los de un trazo recto.
         *
         * Se construye una sola vez y lo usan dos etapas en orden. Primero
         * {@link BuscadorDeRectas}, que busca conectores justamente en lo que
         * quedo AFUERA --es lo que le permite trabajar sobre unos pocos miles de
         * pixeles en vez de sobre el millon de la imagen--; despues el aviso de
         * tinta no interpretada, que mide lo que ni eso pudo leer.
         *
         * Que sea el MISMO mapa no es un ahorro: es lo que garantiza que no se
         * avise de una linea que si se leyo, ni se calle una que no.
         */
        private boolean[] mapaExplicado(List<Segmento> horizontales, List<Segmento> verticales,
                                        List<CajaCruda> cajas) {
            boolean[] explicado = new boolean[w * h];
            for (CajaCruda c : cajas) marcarCaja(explicado, c.caja);
            for (Segmento s : horizontales) marcarSegmento(explicado, s);
            for (Segmento s : verticales) marcarSegmento(explicado, s);
            return explicado;
        }

        /**
         * Propone como relacion cada recta que se encontro en la tinta suelta.
         *
         * Es el nivel 1 leyendo lo que antes solo sabia denunciar: la DIAGONAL
         * --que no produce ninguna corrida larga, porque es una escalera de
         * trocitos-- y la PUNTEADA --que tampoco, ni siquiera cuando es
         * horizontal--. Mientras no existio esto, un diagrama con conectores
         * diagonales devolvia todas sus clases y CERO relaciones, y la
         * dependencia era un tipo de relacion al que la vision no tenia camino.
         *
         * LA RECTA NO ALCANZA: se exige que sus dos puntas lleguen a dos cajas
         * DISTINTAS. Es el mismo criterio que para los segmentos, y es lo que
         * evita que un subrayado, un titulo o una llave suelta entren como
         * relacion. Una recta que no engancha se descarta y su tinta sigue
         * contando como no interpretada, asi que el aviso de siempre sale igual.
         *
         * Las reflexivas por esta via se descartan a proposito: una recta cuyas
         * dos puntas caen en la misma caja es casi siempre un resto del borde o
         * un subrayado, y una reflexiva de verdad --que el modelo soporta-- se
         * dibuja en L y ya la detecta el camino de los segmentos.
         *
         * @return cuantas se propusieron, para las metricas
         */
        private int agregarRectasSueltas(boolean[] explicado, List<CajaCruda> cajas,
                                         List<ClaseDetectada> clases,
                                         List<RelacionDetectada> relaciones) {
            if (cajas.size() < 2) return 0;

            int lado = Math.max(w, h);
            List<BuscadorDeRectas.Recta> rectas = BuscadorDeRectas.buscar(mapa, explicado,
                    Math.max(minAncho, minLargo * 2),
                    // El hueco tolerado tiene que dar para el blanco de una
                    // punteada --que en un diagrama exportado ronda los 6 px--
                    // sin llegar a unir dos conectores distintos puestos en fila.
                    Math.max(6, lado / 100),
                    Math.max(4, lado / 250),
                    MAX_RECTAS);

            // Mas flojo que para un segmento: entre la punta de la recta y la
            // caja puede haber una flecha o un rombo que la tinta ya gasto.
            int tolEnganche = tol * 3;
            int agregadas = 0;
            boolean hayPunteada = false;
            for (BuscadorDeRectas.Recta r : rectas) {
                int a = cajaCercanaA(cajas, r.x1(), r.y1(), tolEnganche);
                int b = cajaCercanaA(cajas, r.x2(), r.y2(), tolEnganche);
                if (a < 0 || b < 0 || a == b) continue;

                // Recien ACA la tinta de la recta pasa a estar interpretada. Si
                // se marcara al encontrarla, una recta que no engancha --una
                // flecha suelta, una llave, un garabato-- quedaria dada por
                // leida sin entrar a la propuesta, y el aviso de tinta no
                // interpretada se callaria justo cuando mas hace falta.
                BuscadorDeRectas.marcar(explicado, w, h, r);
                relaciones.add(relacionEntre(clases, relaciones.size(), a, b,
                        new int[]{r.x1(), r.y1()}, new int[]{r.x2(), r.y2()},
                        // Mas baja que la de un segmento: esto es una recta
                        // ajustada sobre tinta suelta, no un trazo medido.
                        r.punteada() ? 0.5 : 0.6,
                        r.punteada() ? RelationKind.DEPENDENCY : RelationKind.ASSOCIATION));
                agregadas++;
                hayPunteada |= r.punteada();
            }

            if (agregadas > 0) {
                avisos.add(agregadas + " relacion(es) se leyeron de lineas diagonales o punteadas, "
                        + "ajustando una recta sobre la tinta suelta. Son las menos seguras de la "
                        + "propuesta: conviene mirarlas una por una antes de confirmar."
                        + (hayPunteada ? " Una linea punteada se propone como DEPENDENCY, que no "
                        + "genera clave ajena en el esquema derivado." : ""));
            }
            return agregadas;
        }

        /**
         * Avisa cuando quedo tinta que nadie supo leer.
         *
         * ES LA ULTIMA RED, y sigue siendo la parte mas importante de este
         * detector. Un conector diagonal o punteado no produce ninguna corrida
         * larga, asi que durante un tiempo devolvia dos clases, cero relaciones
         * y NINGUN aviso. Eso contradice la doctrina sobre la que esta
         * construido todo el modulo --la Regla 10 dice detectar, rechazar y
         * listar, nunca ignorar en silencio-- y es exactamente el modo de falla
         * invisible que se le critica a un modelo de vision suelto.
         *
         * Hoy esas dos lineas se INTENTAN leer con {@link BuscadorDeRectas}, y
         * esto avisa de lo que ni asi entro: una curva, un trazo a mano alzada,
         * una llave, texto fuera de las cajas. No intenta adivinar QUE es: mide
         * cuanta tinta quedo sin dueno y, si es mucha, lo dice.
         */
        private void avisarTintaNoExplicada(boolean[] explicado, List<CajaCruda> cajas,
                                            List<ClaseDetectada> clases,
                                            List<RelacionDetectada> relaciones) {
            if (cajas.isEmpty()) return;   // ya hay un aviso mas claro que este

            avisarCorredores(explicado, cajas, clases, relaciones);

            long total = 0;
            long sinExplicar = 0;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    if (!tintaEn(x, y)) continue;
                    total++;
                    if (!explicado[y * w + x]) sinExplicar++;
                }
            }
            if (total == 0) return;

            int porcentaje = (int) Math.round(100.0 * sinExplicar / total);
            if (porcentaje >= 8) {
                avisos.add("Quedo un " + porcentaje + " % de la tinta sin interpretar, fuera de toda "
                        + "caja, de todo trazo recto y de toda recta ajustada. Las lineas diagonales "
                        + "y punteadas si se buscan, asi que esto suele ser un trazo curvo o a mano "
                        + "alzada, una llave, o texto que quedo afuera de las cajas.");
            }
        }

        /**
         * Para cada par de clases SIN relacion, mira si hay tinta en el
         * corredor que las une.
         *
         * Es el aviso util, el que se puede accionar: en vez de "quedo tinta
         * suelta" dice QUE DOS CLASES tienen algo dibujado entre ellas que no
         * se pudo leer, que es lo que el usuario necesita saber para cargar esa
         * relacion a mano. Atrapa la diagonal y la punteada, que es justo lo
         * que el nivel 1 no sabe ver.
         */
        private void avisarCorredores(boolean[] explicado, List<CajaCruda> cajas,
                                      List<ClaseDetectada> clases,
                                      List<RelacionDetectada> relaciones) {
            if (cajas.size() < 2 || cajas.size() > 12) return;   // acota el costo cuadratico

            Set<String> yaRelacionadas = new LinkedHashSet<>();
            for (RelacionDetectada r : relaciones) {
                yaRelacionadas.add(r.origenRef() + "|" + r.destinoRef());
                yaRelacionadas.add(r.destinoRef() + "|" + r.origenRef());
            }

            for (int i = 0; i < cajas.size(); i++) {
                for (int j = i + 1; j < cajas.size(); j++) {
                    String par = clases.get(i).ref() + "|" + clases.get(j).ref();
                    if (yaRelacionadas.contains(par)) continue;
                    if (!hayRastroEntre(explicado, cajas.get(i).caja, cajas.get(j).caja)) continue;
                    avisos.add("Hay trazos entre " + clases.get(i).ref() + " y " + clases.get(j).ref()
                            + " que no pude interpretar. La diagonal y la punteada rectas si se leen, "
                            + "asi que lo que hay ahi no es una recta: un trazo curvo, uno a mano "
                            + "alzada, o una linea que no llega a tocar las dos cajas. Si ahi va una "
                            + "relacion, hay que cargarla a mano.");
                }
            }
        }

        /** Recorre la recta entre los dos centros, salteando el interior de las cajas. */
        private boolean hayRastroEntre(boolean[] explicado, Caja a, Caja b) {
            double x = a.x() + a.ancho() / 2.0;
            double y = a.y() + a.alto() / 2.0;
            double dx = (b.x() + b.ancho() / 2.0) - x;
            double dy = (b.y() + b.alto() / 2.0) - y;
            double largo = Math.hypot(dx, dy);
            if (largo < 1) return false;
            dx /= largo;
            dy /= largo;

            int radio = Math.max(2, tol / 2);
            int mirados = 0;
            int conTinta = 0;
            for (double t = 0; t <= largo; t += 2) {
                int px = (int) Math.round(x + dx * t);
                int py = (int) Math.round(y + dy * t);
                if (a.contienePunto(px, py) || b.contienePunto(px, py)) continue;
                mirados++;
                if (tintaSinExplicarCerca(explicado, px, py, radio)) conTinta++;
            }
            // Un cuarto del corredor con tinta: suficiente para una punteada
            // (que tiene tanto hueco como trazo) y demasiado para el ruido.
            return mirados >= 10 && conTinta >= mirados * 0.25;
        }

        private boolean tintaSinExplicarCerca(boolean[] explicado, int px, int py, int radio) {
            for (int y = py - radio; y <= py + radio; y++) {
                for (int x = px - radio; x <= px + radio; x++) {
                    if (!tintaEn(x, y)) continue;
                    if (!explicado[y * w + x]) return true;
                }
            }
            return false;
        }

        private void marcarCaja(boolean[] explicado, Caja caja) {
            int x0 = Math.max(0, caja.x() - tol);
            int y0 = Math.max(0, caja.y() - tol);
            int x1 = Math.min(w - 1, caja.x2() + tol);
            int y1 = Math.min(h - 1, caja.y2() + tol);
            for (int y = y0; y <= y1; y++) {
                for (int x = x0; x <= x1; x++) explicado[y * w + x] = true;
            }
        }

        private void marcarSegmento(boolean[] explicado, Segmento s) {
            int margen = s.grosor / 2 + 2;
            int x0 = Math.max(0, s.x1() - margen);
            int y0 = Math.max(0, s.y1() - margen);
            int x1 = Math.min(w - 1, s.x2() + margen);
            int y1 = Math.min(h - 1, s.y2() + margen);
            for (int y = y0; y <= y1; y++) {
                for (int x = x0; x <= x1; x++) explicado[y * w + x] = true;
            }
        }

        // ---------------------------------------------------------- SEGMENTOS

        /**
         * Corridas largas de tinta, fusionadas entre filas contiguas.
         *
         * Una linea de 2 px de grosor aparece en dos filas: si no se fusionan,
         * cada borde de cada caja cuenta doble y el emparejado de rectangulos
         * se llena de duplicados.
         *
         * Que el minimo de largo sea relativo al lado mayor es lo que filtra
         * el texto sin tener que reconocerlo: los trazos de las letras son
         * cortos, los bordes de las cajas no.
         */
        private List<Segmento> extraer(boolean horizontal) {
            int lineas = horizontal ? h : w;
            int largoLinea = horizontal ? w : h;

            List<Grupo> grupos = new ArrayList<>();
            for (int i = 0; i < lineas; i++) {
                int inicio = -1;
                int ultimo = -1;
                for (int j = 0; j < largoLinea; j++) {
                    boolean hay = horizontal ? tintaEn(j, i) : tintaEn(i, j);
                    if (hay) {
                        if (inicio < 0) inicio = j;
                        ultimo = j;
                    } else if (inicio >= 0 && j - ultimo > GAP) {
                        agregar(grupos, i, inicio, ultimo);
                        inicio = -1;
                    }
                }
                if (inicio >= 0) agregar(grupos, i, inicio, ultimo);
            }

            List<Segmento> segmentos = new ArrayList<>(grupos.size());
            for (Grupo g : grupos) {
                segmentos.add(new Segmento(g.a, g.b, (int) Math.round(g.sumaPos / (double) g.cuenta),
                        horizontal, g.cuenta));
            }
            segmentos.sort(Comparator.comparingInt(s -> s.pos));
            return segmentos;
        }

        private void agregar(List<Grupo> grupos, int linea, int a, int b) {
            if (b - a < minLargo) return;
            for (Grupo g : grupos) {
                if (linea - g.ultima > 1) continue;
                int solape = Math.min(g.b, b) - Math.max(g.a, a);
                int menor = Math.min(g.b - g.a, b - a);
                if (solape > 0 && menor > 0 && solape >= 0.6 * menor) {
                    g.sumar(linea, a, b);
                    return;
                }
            }
            grupos.add(new Grupo(linea, a, b));
        }

        /**
         * Tope duro de segmentos.
         *
         * El emparejado de rectangulos es cuadratico, y una imagen con trama,
         * sombreado o una tabla enorme puede producir miles de trazos. Sin
         * tope, una sola peticion ocupa la CPU por minutos --y este endpoint
         * recibe binarios de afuera--. Se conservan los mas largos, que son
         * los que pueden ser bordes de una caja.
         */
        private List<Segmento> acotar(List<Segmento> segmentos, String cual) {
            if (segmentos.size() <= MAX_SEGMENTOS) return segmentos;
            avisos.add("La imagen tiene " + segmentos.size() + " trazos " + cual
                    + "; se analizan los " + MAX_SEGMENTOS + " mas largos. "
                    + "Suele pasar con capturas que traen tablas o texto alrededor del diagrama.");
            List<Segmento> recortada = new ArrayList<>(segmentos);
            recortada.sort(Comparator.comparingInt((Segmento s) -> s.b - s.a).reversed());
            recortada = new ArrayList<>(recortada.subList(0, MAX_SEGMENTOS));
            recortada.sort(Comparator.comparingInt(s -> s.pos));
            return recortada;
        }

        /**
         * Vuelve a unir los pedazos de un mismo trazo que quedaron casi alineados.
         *
         * Hace falta por el enderezado. Una linea dibujada a 1 o 2 grados se
         * guarda como una ESCALERA de tramos largos; al rotarla para
         * enderezarla, la interpolacion no los deja perfectamente alineados y
         * el borde de una caja llega aca partido en dos o tres piezas a uno o
         * dos pixeles de distancia. Medido: a 2 grados se perdia una de las dos
         * clases y aparecian relaciones fantasma entre los pedazos.
         *
         * El hueco tolerado es chico a proposito (2 * tol). Con uno grande, el
         * borde superior de una caja y un conector que sale a la misma altura
         * se fusionarian en un solo trazo y se perderia la relacion.
         */
        private List<Segmento> fusionarColineales(List<Segmento> segmentos) {
            int tolPos = Math.max(2, tol / 3);
            int tolHueco = tol * 2;
            boolean[] consumido = new boolean[segmentos.size()];
            List<Segmento> fusionados = new ArrayList<>();

            for (int i = 0; i < segmentos.size(); i++) {
                if (consumido[i]) continue;
                Segmento base = segmentos.get(i);
                int a = base.a;
                int b = base.b;
                long sumaPos = (long) base.pos * base.grosor;
                int peso = base.grosor;

                boolean crecio = true;
                while (crecio) {
                    crecio = false;
                    for (int j = i + 1; j < segmentos.size(); j++) {
                        if (consumido[j]) continue;
                        Segmento otro = segmentos.get(j);
                        int pos = (int) Math.round(sumaPos / (double) peso);
                        if (Math.abs(otro.pos - pos) > tolPos) continue;
                        int hueco = Math.max(a, otro.a) - Math.min(b, otro.b);
                        if (hueco > tolHueco) continue;
                        a = Math.min(a, otro.a);
                        b = Math.max(b, otro.b);
                        sumaPos += (long) otro.pos * otro.grosor;
                        peso += otro.grosor;
                        consumido[j] = true;
                        crecio = true;
                    }
                }
                fusionados.add(new Segmento(a, b, (int) Math.round(sumaPos / (double) peso),
                        base.horizontal, peso));
            }
            fusionados.sort(Comparator.comparingInt(s -> s.pos));
            return fusionados;
        }

        // ------------------------------------------------------------- CAJAS

        /**
         * Un rectangulo son dos horizontales alineadas mas dos verticales cuyos
         * EXTREMOS COINCIDEN con ellas.
         *
         * La exigencia de que coincidan --y no solo de que las crucen-- es lo
         * que hace fiable esto, y costo dos falsos positivos entenderlo:
         *
         *   - la linea separadora de compartimentos y el borde superior
         *     formaban un "rectangulo" interno, porque el borde vertical
         *     completo por supuesto cubre ese tramo;
         *   - dos clases apiladas y del mismo ancho formaban una caja gigante
         *     que se las tragaba a las dos.
         *
         * Pidiendo que la vertical EMPIECE y TERMINE en las horizontales, los
         * dos casos se caen solos y no hace falta ningun desempate posterior.
         */
        private List<CajaCruda> detectarCajas(List<Segmento> horizontales, List<Segmento> verticales) {
            List<CajaCruda> candidatas = new ArrayList<>();
            for (Segmento arriba : horizontales) {
                for (Segmento abajo : horizontales) {
                    int altoCaja = abajo.pos - arriba.pos;
                    if (altoCaja < minAlto) continue;
                    if (Math.abs(arriba.a - abajo.a) > tol || Math.abs(arriba.b - abajo.b) > tol) continue;

                    int x1 = (arriba.a + abajo.a) / 2;
                    int x2 = (arriba.b + abajo.b) / 2;
                    if (x2 - x1 < minAncho) continue;

                    Segmento izquierda = verticalEn(verticales, x1, arriba.pos, abajo.pos);
                    Segmento derecha = verticalEn(verticales, x2, arriba.pos, abajo.pos);
                    if (izquierda == null || derecha == null || izquierda == derecha) continue;

                    Caja caja = new Caja(x1, arriba.pos, x2 - x1, altoCaja);
                    double confianza = confianzaDe(caja, arriba, abajo, izquierda, derecha);
                    candidatas.add(new CajaCruda(caja, arriba, abajo, izquierda, derecha, confianza));
                }
            }

            candidatas.sort(Comparator.comparingDouble((CajaCruda c) -> c.confianza).reversed()
                    .thenComparing(c -> -(long) c.caja.ancho() * c.caja.alto()));

            List<CajaCruda> aceptadas = new ArrayList<>();
            for (CajaCruda c : candidatas) {
                if (aceptadas.stream().anyMatch(a -> casiIgual(a.caja, c.caja))) continue;
                aceptadas.add(c);
                c.arriba.usado = true;
                c.abajo.usado = true;
                c.izquierda.usado = true;
                c.derecha.usado = true;
            }
            aceptadas.sort(Comparator.comparingInt((CajaCruda c) -> c.caja.y())
                    .thenComparingInt(c -> c.caja.x()));

            if (hayAnidadas(aceptadas)) {
                avisos.add("Hay una caja dibujada dentro de otra. Se proponen las dos por separado: "
                        + "el modelo no tiene clases anidadas (Regla 10).");
            }
            return aceptadas;
        }

        /** Un paquete UML o un marco alrededor del diagrama caen aca. */
        private boolean hayAnidadas(List<CajaCruda> cajas) {
            for (CajaCruda fuera : cajas) {
                for (CajaCruda dentro : cajas) {
                    if (fuera == dentro) continue;
                    if (fuera.caja.x() < dentro.caja.x() && fuera.caja.y() < dentro.caja.y()
                            && fuera.caja.x2() > dentro.caja.x2()
                            && fuera.caja.y2() > dentro.caja.y2()) {
                        return true;
                    }
                }
            }
            return false;
        }

        /** Vertical cuyos dos extremos caen sobre las horizontales, no solo que las cruce. */
        private Segmento verticalEn(List<Segmento> verticales, int x, int yArriba, int yAbajo) {
            Segmento mejor = null;
            int mejorDistancia = Integer.MAX_VALUE;
            for (Segmento v : verticales) {
                if (Math.abs(v.pos - x) > tol) continue;
                if (Math.abs(v.a - yArriba) > tol || Math.abs(v.b - yAbajo) > tol) continue;
                int distancia = Math.abs(v.pos - x);
                if (distancia < mejorDistancia) {
                    mejorDistancia = distancia;
                    mejor = v;
                }
            }
            return mejor;
        }

        private double confianzaDe(Caja caja, Segmento arriba, Segmento abajo,
                                   Segmento izquierda, Segmento derecha) {
            double ancho = Math.max(1, caja.ancho());
            double altoCaja = Math.max(1, caja.alto());
            double suma = Math.min(1, (arriba.b - arriba.a) / ancho)
                    + Math.min(1, (abajo.b - abajo.a) / ancho)
                    + Math.min(1, (izquierda.b - izquierda.a) / altoCaja)
                    + Math.min(1, (derecha.b - derecha.a) / altoCaja);
            return Math.max(0, Math.min(1, suma / 4));
        }

        private boolean casiIgual(Caja a, Caja b) {
            return Math.abs(a.x() - b.x()) <= tol && Math.abs(a.y() - b.y()) <= tol
                    && Math.abs(a.x2() - b.x2()) <= tol && Math.abs(a.y2() - b.y2()) <= tol;
        }

        /**
         * Todo segmento contenido en una caja ya no es candidato a conector.
         *
         * Sin esto, un atributo subrayado o una linea de separacion mal medida
         * se convierte en una relacion fantasma entre la caja y si misma.
         */
        private void marcarTintaInterna(List<Segmento> horizontales, List<Segmento> verticales,
                                        List<CajaCruda> cajas) {
            for (List<Segmento> lista : List.of(horizontales, verticales)) {
                for (Segmento s : lista) {
                    if (s.usado) continue;
                    for (CajaCruda c : cajas) {
                        if (c.caja.contienePunto(s.x1(), s.y1()) && c.caja.contienePunto(s.x2(), s.y2())) {
                            s.usado = true;
                            break;
                        }
                    }
                }
            }
        }

        // ------------------------------------------------------------ CLASES

        private List<ClaseDetectada> construirClases(List<CajaCruda> cajas, List<Segmento> horizontales) {
            double factor = factorDeLienzo(cajas);
            int minX = cajas.stream().mapToInt(c -> c.caja.x()).min().orElse(0);
            int minY = cajas.stream().mapToInt(c -> c.caja.y()).min().orElse(0);

            List<ClaseDetectada> clases = new ArrayList<>(cajas.size());
            for (int i = 0; i < cajas.size(); i++) {
                CajaCruda cruda = cajas.get(i);
                String ref = "c" + (i + 1);
                Caja caja = cruda.caja;

                List<Integer> separadores = separadoresDe(cruda, horizontales);
                List<int[]> tramos = tramosDe(caja, separadores);

                List<RecorteDeTexto> nombre = new ArrayList<>();
                List<RecorteDeTexto> atributos = new ArrayList<>();
                List<RecorteDeTexto> metodos = new ArrayList<>();
                for (int t = 0; t < tramos.size(); t++) {
                    RolDeTexto rol = switch (t) {
                        case 0 -> RolDeTexto.NOMBRE_CLASE;
                        case 1 -> RolDeTexto.ATRIBUTO;
                        default -> RolDeTexto.METODO;
                    };
                    List<RecorteDeTexto> destino = switch (t) {
                        case 0 -> nombre;
                        case 1 -> atributos;
                        default -> metodos;
                    };
                    int desde = destino.size();
                    lineasDeTexto(caja, tramos.get(t)[0], tramos.get(t)[1], ref, rol, destino, desde);
                }
                // Sin separadores no hay compartimentos: la primera linea es el
                // nombre y el resto son atributos. Es la lectura mas util de
                // una caja simple, que es como se dibuja en una pizarra apurada.
                if (tramos.size() == 1 && nombre.size() > 1) {
                    for (int k = 1; k < nombre.size(); k++) {
                        RecorteDeTexto r = nombre.get(k);
                        atributos.add(new RecorteDeTexto(ref + "-a" + k, RolDeTexto.ATRIBUTO,
                                r.caja(), null, 0));
                    }
                    nombre = new ArrayList<>(nombre.subList(0, 1));
                }

                Caja lienzo = new Caja(
                        80 + (int) Math.round((caja.x() - minX) * factor),
                        80 + (int) Math.round((caja.y() - minY) * factor),
                        Math.max(120, (int) Math.round(caja.ancho() * factor)),
                        Math.max(60, (int) Math.round(caja.alto() * factor)));

                clases.add(new ClaseDetectada(ref, caja, lienzo, null,
                        nombre.isEmpty() ? null : nombre.get(0),
                        List.copyOf(atributos), List.copyOf(metodos),
                        tramos.size(), cruda.confianza));
            }
            return clases;
        }

        /**
         * Lleva los pixeles a coordenadas del canvas conservando la disposicion.
         *
         * Se escala para que la caja de ancho mediano quede en los 170 px que
         * usa CREATE_CLASS por defecto. Importa mas de lo que parece: si todo
         * aterriza en el origen, el usuario tiene que re-acomodar el diagrama
         * entero a mano y la lectura de la imagen deja de ahorrar trabajo.
         */
        private double factorDeLienzo(List<CajaCruda> cajas) {
            if (cajas.isEmpty()) return 1.0;
            int[] anchos = cajas.stream().mapToInt(c -> c.caja.ancho()).sorted().toArray();
            int mediana = anchos[anchos.length / 2];
            if (mediana <= 0) return 1.0;
            return Math.max(0.15, Math.min(4.0, 170.0 / mediana));
        }

        /** Horizontales internas que cruzan la caja de lado a lado. */
        private List<Integer> separadoresDe(CajaCruda cruda, List<Segmento> horizontales) {
            List<Integer> separadores = new ArrayList<>();
            Caja caja = cruda.caja;
            int margen = Math.max(4, tol / 2);
            for (Segmento s : horizontales) {
                if (s == cruda.arriba || s == cruda.abajo) continue;
                if (s.pos <= caja.y() + margen || s.pos >= caja.y2() - margen) continue;
                if (Math.abs(s.a - caja.x()) > tol * 2 || Math.abs(s.b - caja.x2()) > tol * 2) continue;
                separadores.add(s.pos);
                s.usado = true;
            }
            separadores.sort(Integer::compareTo);
            return separadores;
        }

        private List<int[]> tramosDe(Caja caja, List<Integer> separadores) {
            List<int[]> tramos = new ArrayList<>();
            int y = caja.y();
            for (int sep : separadores) {
                if (sep - y > 6) tramos.add(new int[]{y, sep});
                y = sep;
            }
            if (caja.y2() - y > 6) tramos.add(new int[]{y, caja.y2()});
            if (tramos.isEmpty()) tramos.add(new int[]{caja.y(), caja.y2()});
            return tramos;
        }

        /**
         * Bandas horizontales con tinta dentro de un compartimento: una por
         * renglon. Cada una es un recorte que el nivel 2 tendra que leer.
         */
        private void lineasDeTexto(Caja caja, int y0, int y1, String ref, RolDeTexto rol,
                                   List<RecorteDeTexto> destino, int desde) {
            int margen = Math.max(3, tol / 2);
            int xa = caja.x() + margen;
            int xb = caja.x2() - margen;
            if (xb - xa < 4) return;

            int inicio = -1;
            int ultimo = -1;
            int encontradas = 0;
            for (int y = y0 + margen; y <= y1 - margen; y++) {
                boolean hay = false;
                for (int x = xa; x <= xb && !hay; x++) hay = tintaEn(x, y);
                if (hay) {
                    if (inicio < 0) inicio = y;
                    ultimo = y;
                } else if (inicio >= 0 && y - ultimo > 2) {
                    if (agregarBanda(caja, inicio, ultimo, xa, xb, ref, rol, destino, desde + encontradas)) {
                        encontradas++;
                    }
                    inicio = -1;
                    if (encontradas >= 25) break;
                }
            }
            if (inicio >= 0 && encontradas < 25) {
                if (agregarBanda(caja, inicio, ultimo, xa, xb, ref, rol, destino, desde + encontradas)) {
                    encontradas++;
                }
            }
            if (encontradas >= 25) {
                avisos.add("La caja " + ref + " tiene mas de 25 renglones de texto; se proponen los "
                        + "primeros. Si es una clase real conviene partirla.");
            }
        }

        private boolean agregarBanda(Caja caja, int y0, int y1, int xa, int xb, String ref,
                                     RolDeTexto rol, List<RecorteDeTexto> destino, int indice) {
            if (y1 - y0 < 3) return false;
            int xMin = Integer.MAX_VALUE;
            int xMax = Integer.MIN_VALUE;
            for (int y = y0; y <= y1; y++) {
                for (int x = xa; x <= xb; x++) {
                    if (!tintaEn(x, y)) continue;
                    if (x < xMin) xMin = x;
                    if (x > xMax) xMax = x;
                }
            }
            if (xMin > xMax) return false;
            int pad = 2;
            Caja recorte = new Caja(
                    Math.max(caja.x(), xMin - pad),
                    Math.max(caja.y(), y0 - pad),
                    Math.min(caja.x2(), xMax + pad) - Math.max(caja.x(), xMin - pad),
                    Math.min(caja.y2(), y1 + pad) - Math.max(caja.y(), y0 - pad));
            if (recorte.ancho() < 3 || recorte.alto() < 3) return false;
            destino.add(RecorteDeTexto.vacio(ref + "-" + rol.name().charAt(0) + indice, rol, recorte));
            return true;
        }

        // --------------------------------------------------------- RELACIONES

        /**
         * Los segmentos que sobraron son conectores.
         *
         * Se encadenan por cercania de extremos para que una linea quebrada en
         * L --dos segmentos-- cuente como una sola relacion, y despues se mira
         * a que cajas toca la cadena entera.
         */
        private List<RelacionDetectada> detectarRelaciones(List<Segmento> horizontales,
                                                           List<Segmento> verticales,
                                                           List<CajaCruda> cajas,
                                                           List<ClaseDetectada> clases) {
            List<Segmento> sueltos = new ArrayList<>();
            for (Segmento s : horizontales) if (!s.usado && !esResiduoDeBorde(s, cajas)) sueltos.add(s);
            for (Segmento s : verticales) if (!s.usado && !esResiduoDeBorde(s, cajas)) sueltos.add(s);
            if (sueltos.isEmpty() || cajas.isEmpty()) return List.of();

            int tolUnion = tol * 2;
            int tolEnganche = (int) (tol * 2.5);

            int[] padre = new int[sueltos.size()];
            for (int i = 0; i < padre.length; i++) padre[i] = i;
            for (int i = 0; i < sueltos.size(); i++) {
                for (int j = i + 1; j < sueltos.size(); j++) {
                    if (seTocan(sueltos.get(i), sueltos.get(j), tolUnion)) unir(padre, i, j);
                }
            }

            List<RelacionDetectada> relaciones = new ArrayList<>();
            int sueltas = 0;
            Set<Integer> raices = new LinkedHashSet<>();
            for (int i = 0; i < padre.length; i++) raices.add(raiz(padre, i));

            for (int raiz : raices) {
                List<int[]> puntos = new ArrayList<>();
                for (int i = 0; i < sueltos.size(); i++) {
                    if (raiz(padre, i) != raiz) continue;
                    Segmento s = sueltos.get(i);
                    puntos.add(new int[]{s.x1(), s.y1()});
                    puntos.add(new int[]{s.x2(), s.y2()});
                }

                List<int[]> enganches = new ArrayList<>();   // {x, y, indiceCaja}
                for (int[] p : puntos) {
                    int caja = cajaCercanaA(cajas, p[0], p[1], tolEnganche);
                    if (caja >= 0) enganches.add(new int[]{p[0], p[1], caja});
                }
                Set<Integer> tocadas = new LinkedHashSet<>();
                for (int[] e : enganches) tocadas.add(e[2]);

                if (tocadas.size() > 2) {
                    avisos.add("Una linea toca " + tocadas.size() + " clases a la vez. Una relacion "
                            + "n-aria no se mapea: hay que reificarla como clase asociativa (Regla 10).");
                    continue;
                }
                if (tocadas.isEmpty()) {
                    sueltas++;
                    continue;
                }
                if (tocadas.size() == 1) {
                    int caja = tocadas.iterator().next();
                    int[][] extremos = masLejanos(enganches);
                    if (extremos == null || distancia(extremos[0], extremos[1]) < minAncho / 2.0) {
                        sueltas++;
                        continue;
                    }
                    // Reflexiva: el modelo ya soporta que una clase se relacione
                    // consigo misma, asi que se propone en vez de descartarse.
                    relaciones.add(relacionEntre(clases, relaciones.size(), caja, caja,
                            extremos[0], extremos[1], 0.6, RelationKind.ASSOCIATION));
                    continue;
                }

                int[] a = mejorEnganche(enganches, tocadas.stream().toList().get(0));
                int[] b = mejorEnganche(enganches, tocadas.stream().toList().get(1));
                if (a == null || b == null) {
                    sueltas++;
                    continue;
                }
                relaciones.add(relacionEntre(clases, relaciones.size(), a[2], b[2], a, b, 0.85,
                        RelationKind.ASSOCIATION));
            }

            if (sueltas > 0) {
                avisos.add(sueltas + " linea(s) no llegan a ninguna clase o llegan a una sola. "
                        + "No se proponen como relacion: puede ser una flecha suelta, una llave "
                        + "o una linea de la pizarra que no es parte del diagrama.");
            }
            return relaciones;
        }

        /**
         * @param kind lo que el TRAZO permite afirmar por si solo. Sin leer el
         *             extremo, la lectura conservadora es la asociacion simple
         *             --no impone CASCADE ni PK compartida--; una linea punteada
         *             es la unica excepcion, porque el estilo del trazo ya dice
         *             dependencia sin necesidad de ninguna figura
         */
        private RelacionDetectada relacionEntre(List<ClaseDetectada> clases, int indice,
                                                int cajaA, int cajaB, int[] puntoA, int[] puntoB,
                                                double confianza, RelationKind kind) {
            String ref = "r" + (indice + 1);
            String origen = clases.get(cajaA).ref();
            String destino = clases.get(cajaB).ref();
            return new RelacionDetectada(ref, origen, destino, kind,
                    RecorteDeSimbolo.vacio(ref + "-s0", Extremo.ORIGEN,
                            cuadradoEn(puntoA[0], puntoA[1], puntoB[0], puntoB[1])),
                    RecorteDeSimbolo.vacio(ref + "-s1", Extremo.DESTINO,
                            cuadradoEn(puntoB[0], puntoB[1], puntoA[0], puntoA[1])),
                    RecorteDeTexto.vacio(ref + "-m0", RolDeTexto.CARDINALIDAD,
                            cardinalidadEn(puntoA[0], puntoA[1], puntoB[0], puntoB[1])),
                    RecorteDeTexto.vacio(ref + "-m1", RolDeTexto.CARDINALIDAD,
                            cardinalidadEn(puntoB[0], puntoB[1], puntoA[0], puntoA[1])),
                    confianza);
        }

        /** Cuadrado sobre el extremo, corrido hacia adentro de la linea. */
        private Caja cuadradoEn(int x, int y, int haciaX, int haciaY) {
            double dx = haciaX - x;
            double dy = haciaY - y;
            double largo = Math.max(1, Math.hypot(dx, dy));
            int cx = x + (int) Math.round(dx / largo * ladoSimbolo * 0.5);
            int cy = y + (int) Math.round(dy / largo * ladoSimbolo * 0.5);
            return recortar(cx - ladoSimbolo / 2, cy - ladoSimbolo / 2, ladoSimbolo, ladoSimbolo);
        }

        /** Rectangulito al costado del extremo, donde suele ir el "1" o el "0..*". */
        private Caja cardinalidadEn(int x, int y, int haciaX, int haciaY) {
            double dx = haciaX - x;
            double dy = haciaY - y;
            double largo = Math.max(1, Math.hypot(dx, dy));
            int cx = x + (int) Math.round(dx / largo * ladoSimbolo);
            int cy = y + (int) Math.round(dy / largo * ladoSimbolo);
            int ancho = (int) (ladoSimbolo * 1.6);
            int altoCaja = (int) (ladoSimbolo * 0.9);
            int desplazX = (int) Math.round(-dy / largo * ladoSimbolo * 0.8);
            int desplazY = (int) Math.round(dx / largo * ladoSimbolo * 0.8);
            return recortar(cx + desplazX - ancho / 2, cy + desplazY - altoCaja / 2, ancho, altoCaja);
        }

        private Caja recortar(int x, int y, int ancho, int altoCaja) {
            int x0 = Math.max(0, Math.min(x, w - 1));
            int y0 = Math.max(0, Math.min(y, h - 1));
            return new Caja(x0, y0, Math.max(1, Math.min(ancho, w - x0)),
                    Math.max(1, Math.min(altoCaja, h - y0)));
        }

        /**
         * Un trazo que corre pegado al borde de UNA misma caja es ese borde, no
         * un conector.
         *
         * Aparece cuando el enderezado deja restos: pedazos del borde que no
         * llegaron a armar el rectangulo quedan sueltos, se encadenan entre si
         * y salen como relaciones de la caja consigo misma. A 1 grado eso daba
         * cinco relaciones donde hay una.
         *
         * Se exige que los TRES puntos --los dos extremos y el medio-- esten
         * cerca del contorno de la MISMA caja. Un conector de verdad tiene un
         * extremo en una caja y el otro en otra, asi que nunca cumple las tres
         * condiciones contra una sola.
         */
        private boolean esResiduoDeBorde(Segmento s, List<CajaCruda> cajas) {
            int medioX = (s.x1() + s.x2()) / 2;
            int medioY = (s.y1() + s.y2()) / 2;
            for (CajaCruda c : cajas) {
                if (alContorno(c.caja, s.x1(), s.y1()) <= tol
                        && alContorno(c.caja, s.x2(), s.y2()) <= tol
                        && alContorno(c.caja, medioX, medioY) <= tol) {
                    return true;
                }
            }
            return false;
        }

        /** Distancia al CONTORNO del rectangulo: estar adentro tampoco es estar sobre el. */
        private double alContorno(Caja c, int px, int py) {
            if (c.contienePunto(px, py)) {
                return Math.min(Math.min(px - c.x(), c.x2() - px),
                        Math.min(py - c.y(), c.y2() - py));
            }
            return c.distanciaA(px, py);
        }

        private boolean seTocan(Segmento a, Segmento b, int tolerancia) {
            int[][] extremosA = {{a.x1(), a.y1()}, {a.x2(), a.y2()}};
            int[][] extremosB = {{b.x1(), b.y1()}, {b.x2(), b.y2()}};
            for (int[] pa : extremosA) {
                for (int[] pb : extremosB) {
                    if (distancia(pa, pb) <= tolerancia) return true;
                }
            }
            return false;
        }

        private int cajaCercanaA(List<CajaCruda> cajas, int x, int y, int tolerancia) {
            int mejor = -1;
            double mejorDistancia = Double.MAX_VALUE;
            for (int i = 0; i < cajas.size(); i++) {
                double d = cajas.get(i).caja.distanciaA(x, y);
                if (d <= tolerancia && d < mejorDistancia) {
                    mejorDistancia = d;
                    mejor = i;
                }
            }
            return mejor;
        }

        private int[] mejorEnganche(List<int[]> enganches, int caja) {
            for (int[] e : enganches) if (e[2] == caja) return e;
            return null;
        }

        private int[][] masLejanos(List<int[]> puntos) {
            if (puntos.size() < 2) return null;
            int[] a = null;
            int[] b = null;
            double mejor = -1;
            for (int i = 0; i < puntos.size(); i++) {
                for (int j = i + 1; j < puntos.size(); j++) {
                    double d = distancia(puntos.get(i), puntos.get(j));
                    if (d > mejor) {
                        mejor = d;
                        a = puntos.get(i);
                        b = puntos.get(j);
                    }
                }
            }
            return a == null ? null : new int[][]{a, b};
        }

        private static double distancia(int[] a, int[] b) {
            return Math.hypot(a[0] - b[0], a[1] - b[1]);
        }

        private static int raiz(int[] padre, int i) {
            while (padre[i] != i) {
                padre[i] = padre[padre[i]];
                i = padre[i];
            }
            return i;
        }

        private static void unir(int[] padre, int i, int j) {
            int ri = raiz(padre, i);
            int rj = raiz(padre, j);
            if (ri != rj) padre[rj] = ri;
        }
    }

    // ===================================================================== //

    /** Corridas contiguas que se van fusionando en un unico segmento grueso. */
    private static final class Grupo {
        int a;
        int b;
        int ultima;
        long sumaPos;
        int cuenta;

        Grupo(int linea, int a, int b) {
            this.a = a;
            this.b = b;
            this.ultima = linea;
            this.sumaPos = linea;
            this.cuenta = 1;
        }

        void sumar(int linea, int inicio, int fin) {
            a = Math.min(a, inicio);
            b = Math.max(b, fin);
            ultima = linea;
            sumaPos += linea;
            cuenta++;
        }
    }

    /**
     * Un trazo recto. `pos` es la coordenada constante (la y de una horizontal,
     * la x de una vertical) y [a, b] el recorrido sobre el otro eje.
     */
    private static final class Segmento {
        final int a;
        final int b;
        final int pos;
        final boolean horizontal;
        final int grosor;
        boolean usado;

        Segmento(int a, int b, int pos, boolean horizontal, int grosor) {
            this.a = a;
            this.b = b;
            this.pos = pos;
            this.horizontal = horizontal;
            this.grosor = grosor;
        }

        int x1() { return horizontal ? a : pos; }

        int y1() { return horizontal ? pos : a; }

        int x2() { return horizontal ? b : pos; }

        int y2() { return horizontal ? pos : b; }
    }

    private static final class CajaCruda {
        final Caja caja;
        final Segmento arriba;
        final Segmento abajo;
        final Segmento izquierda;
        final Segmento derecha;
        final double confianza;

        CajaCruda(Caja caja, Segmento arriba, Segmento abajo,
                  Segmento izquierda, Segmento derecha, double confianza) {
            this.caja = caja;
            this.arriba = arriba;
            this.abajo = abajo;
            this.izquierda = izquierda;
            this.derecha = derecha;
            this.confianza = confianza;
        }
    }
}
