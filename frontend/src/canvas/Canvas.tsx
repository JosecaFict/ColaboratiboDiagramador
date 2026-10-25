import { useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react'
import { SIMBOLO_VISIBILIDAD, esEnumeracion } from '../contracts/command'
import type {
  ElementLayout, NotationView, UmlAttribute, UmlClass, UmlMethod, UmlRelationship,
} from '../contracts/command'
import { posicionarAtributos } from './chenLayout'

interface Punto { x: number; y: number }
interface Caja { x: number; y: number; width: number; height: number }

interface Props {
  view: NotationView
  classes: UmlClass[]
  relationships: UmlRelationship[]
  layout: Record<string, ElementLayout>
  locks: Record<string, string>          // elementId -> nombre del dueno del lease
  onSelect?: (elementId: string) => void
  onMove?: (elementId: string, x: number, y: number) => void
  relacionSeleccionada?: string | null
  onSelectRelation?: (relationId: string) => void
  /** Con la herramienta Relacion activa, arrastrar conecta en vez de mover. */
  modoConector?: boolean
  onConectar?: (origenId: string, destinoId: string) => void
  notacionCardinalidad?: NotacionCardinalidad
  /** Guarda los quiebres de una linea. Arreglo vacio = volver a recta. */
  onMoverQuiebres?: (relationId: string, puntos: Punto[]) => void
}

export type NotacionCardinalidad = 'UML' | 'CHEN'

interface Arrastre { id: string; dx: number; dy: number; x: number; y: number; movido: boolean }
interface Conector { origenId: string; desde: Punto; hasta: Punto }
/** Quiebre que se esta arrastrando: se trabaja sobre una copia del arreglo. */
interface ArrastreQuiebre { relationId: string; indice: number; puntos: Punto[] }

/** Punto medio recorriendo la polilinea, no el del segmento del medio. */
function medioDelCamino(pts: Punto[]): Punto {
  const largos: number[] = []
  let total = 0
  for (let i = 1; i < pts.length; i++) {
    const l = Math.hypot(pts[i].x - pts[i - 1].x, pts[i].y - pts[i - 1].y)
    largos.push(l)
    total += l
  }
  let restante = total / 2
  for (let i = 0; i < largos.length; i++) {
    if (restante <= largos[i]) {
      const t = largos[i] === 0 ? 0 : restante / largos[i]
      return {
        x: pts[i].x + (pts[i + 1].x - pts[i].x) * t,
        y: pts[i].y + (pts[i + 1].y - pts[i].y) * t,
      }
    }
    restante -= largos[i]
  }
  return pts[pts.length - 1]
}

/** Menos que esto es un clic, no un arrastre. */
const UMBRAL = 3

/** Por debajo de 0.2 no se lee nada; por encima de 3 no aporta. */
const ZOOM_MIN = 0.2
const ZOOM_MAX = 3
/** Cuanto salta el zoom con los botones y el teclado. */
const PASO_ZOOM = 1.2
/** Aire alrededor del modelo al encuadrarlo, en unidades del modelo. */
const MARGEN_ENCUADRE = 60
/**
 * Aire que el area de trabajo agrega alrededor del dibujo, en unidades del
 * modelo. Es lo que se puede recorrer mas alla de la ultima clase: sin esto,
 * llevar una clase al borde no dejaria lugar donde soltarla.
 */
const MARGEN_AREA = 700
/** Lado de la trama de puntos del fondo, en unidades del modelo. */
const TRAMA = 22
/** Franja que ocupan los controles de zoom: el encuadre no la usa. */
const ALTO_CONTROLES = 48

/** Arrastre del lienzo: donde empezo el mouse y donde estaba el scroll. */
interface Paneo { clientX: number; clientY: number; left: number; top: number }
/**
 * "El punto (x,y) del modelo tiene que quedar a px,py del borde del hueco".
 * Es lo que hace que el zoom no se lleve de paseo lo que estabas mirando.
 */
interface Ancla { x: number; y: number; px: number; py: number }

/**
 * OPCION B: se guarda UNA forma canonica (la de UML) y se PROYECTA a la
 * notacion elegida. Convertir lo guardado seria destructivo y ademas
 * rompería la Vista Logica, que decide 1:N o N:M buscando un `*`.
 *
 * La forma (min,max) de Chen es reversible: `0..*` y `1..*` no se confunden.
 */
const EQUIVALENCIAS_CHEN: Record<string, string> = {
  '1': '(1,1)',
  '0..1': '(0,1)',
  '1..*': '(1,N)',
  '0..*': '(0,N)',
  '*': '(0,N)',
}

/**
 * PROYECCION, no conversion (ADR-003).
 *
 * Las dos vistas leen el MISMO modelo. Al pasar a Chen, los metodos y la
 * visibilidad no se borran: no se dibujan. Al volver, siguen ahi.
 */
/**
 * Alto de una caja en la vista de Clases: cabecera + atributos + metodos.
 *
 * Vive suelta porque la usan el dibujo Y el calculo de bordes de las lineas:
 * si divergen, las relaciones se cortan en el aire.
 */
function altoDeCaja(c: UmlClass): number {
  // Una enumeracion no dibuja metodos: su segundo compartimento son los literales.
  const metodos = c.methods.length > 0 && !esEnumeracion(c) ? 6 + c.methods.length * 18 : 0
  return 30 + c.attributes.length * 18 + 8 + metodos
}

/**
 * Una Property tal como la escribe UML 2.5.1 (seccion 9.5.4):
 *
 *   <visibilidad> ['/'] <nombre> ':' <tipo> ['[' <multiplicidad> ']']
 *                 ['=' <valor>] ['{' <modificadores> '}']
 *
 * Ejemplo: "- /saldo: decimal [0..1] = 0 {id}".
 *
 * Tres cosas que antes no cumplian el estandar:
 *
 *   - la visibilidad salia "-" FIJA, porque el atributo no la guardaba;
 *   - la clave primaria se marcaba con un bolo negro, que no es un simbolo
 *     UML, y ademas SUBRAYADA. En UML el subrayado significa estatica --de
 *     alcance de clasificador--, no clave: subrayarla es notacion ER, correcta
 *     en Chen y ambigua aca. El modificador que corresponde es {id};
 *   - los derivados no se veian; UML los escribe con barra delante.
 *
 * La multiplicidad se escribe cuando NO es 1..1, porque ese es el valor que
 * UML asume si se la omite: callarla en un atributo opcional seria afirmar que
 * es obligatorio.
 *
 * `unique` no se dibuja: en UML {unique} habla de que una coleccion no repita
 * elementos, no de una restriccion UNIQUE de base de datos. Poner esa llave
 * aca diria algo distinto de lo que el modelo quiere decir; esa restriccion se
 * ve en la Vista Logica, que es donde vive.
 */
function firmaDeAtributo(a: UmlAttribute): string {
  // `?? '-'` no es paranoia: un atributo que venga de una respuesta vieja o
  // incompleta imprimia la palabra "undefined" delante del nombre.
  const visibilidad = SIMBOLO_VISIBILIDAD[a.visibility] ?? '-'
  const derivado = a.derived ? '/' : ''
  const multiplicidad = a.multivalued ? ' [*]' : a.required ? '' : ' [0..1]'
  const porDefecto = a.defaultValue ? ` = ${a.defaultValue}` : ''
  const modificadores = a.pk ? ' {id}' : ''
  return `${visibilidad} ${derivado}${a.name}: ${a.dataType}`
       + `${multiplicidad}${porDefecto}${modificadores}`
}

/** "+ prestar(socio: uuid, dias: int): boolean", como se escribe en UML. */
function firmaDeMetodo(m: UmlMethod): string {
  const params = m.params.map((p) => `${p.name}: ${p.type}`).join(', ')
  const retorno = m.returnType && m.returnType !== 'void' ? `: ${m.returnType}` : ''
  return `${SIMBOLO_VISIBILIDAD[m.visibility]} ${m.name}(${params})${retorno}`
}

/**
 * Una firma larga se sale de la caja y se superpone con lo que haya al lado.
 * Se corta para dibujar y va entera en el tooltip: perder texto en pantalla es
 * aceptable, perderlo del modelo no.
 */
function recortar(texto: string, ancho: number): string {
  // 10px de monoespaciada miden ~6px por caracter.
  const caben = Math.max(4, Math.floor((ancho - 20) / 6))
  return texto.length <= caben ? texto : texto.slice(0, caben - 1) + '…'
}

export function Canvas({
  view, classes, relationships, layout, locks, onSelect, onMove,
  relacionSeleccionada, onSelectRelation, modoConector, onConectar,
  notacionCardinalidad = 'UML', onMoverQuiebres,
}: Props) {
  const svgRef = useRef<SVGSVGElement>(null)
  const zonaRef = useRef<HTMLDivElement>(null)

  /**
   * Cuantos pixeles mide una unidad del modelo.
   *
   * El DESPLAZAMIENTO no vive aca: lo lleva el scroll del navegador sobre un
   * area de trabajo que se dibuja entera, como en draw.io. Por eso hay barras
   * de verdad, la rueda y el teclado funcionan solos, y el lienzo se puede
   * recorrer en cualquier direccion sin que lo sepa React.
   */
  const [zoom, setZoom] = useState(1)

  /**
   * Hueco visible en pixeles, sin contar las barras. El area minima y el
   * encuadre se calculan con el, no con un 1200x700 fijo.
   */
  const [tam, setTam] = useState({ w: 1200, h: 700 })
  /** Hasta que no se midio de verdad, `tam` es una suposicion. */
  const [medido, setMedido] = useState(false)
  const [paneo, setPaneo] = useState<Paneo | null>(null)
  const paneoRef = useRef<Paneo | null>(null)
  /** Barra espaciadora apretada: cualquier arrastre mueve el lienzo. */
  const [espacio, setEspacio] = useState(false)
  const espacioRef = useRef(false)
  const [arrastre, setArrastre] = useState<Arrastre | null>(null)
  const [conector, setConector] = useState<Conector | null>(null)
  const conectorRef = useRef<Conector | null>(null)

  const fijarConector = (c: Conector | null) => {
    conectorRef.current = c
    setConector(c)
  }

  const cardinalidad = (m?: string | null) => {
    if (!m) return ''
    return notacionCardinalidad === 'CHEN' ? EQUIVALENCIAS_CHEN[m] ?? m : m
  }

  /** Marcadores y trazo de cada tipo, segun la notacion UML. */
  const notacion = (kind: UmlRelationship['kind']) => {
    switch (kind) {
      case 'AGGREGATION':
        return { inicio: 'url(#rombo-hueco)', fin: undefined, punteada: false }
      case 'COMPOSITION':
        return { inicio: 'url(#rombo-relleno)', fin: undefined, punteada: false }
      case 'GENERALIZATION':
        return { inicio: undefined, fin: 'url(#triangulo-hueco)', punteada: false }
      case 'DEPENDENCY':
        return { inicio: undefined, fin: 'url(#flecha-abierta)', punteada: true }
      default:
        return { inicio: undefined, fin: undefined, punteada: false }
    }
  }

  /**
   * El arrastre vive en una ref y se espeja al estado solo para redibujar.
   * Los handlers NO pueden avisar desde dentro de un actualizador de estado:
   * StrictMode los invoca dos veces y saldrian dos MOVE_ELEMENT por arrastre.
   */
  const arrastreRef = useRef<Arrastre | null>(null)
  const fijarArrastre = (a: Arrastre | null) => {
    arrastreRef.current = a
    setArrastre(a)
  }

  // Las callbacks se leen por ref: si no, cada render de App volveria a
  // registrar los listeners en plena mitad de un arrastre.
  const [quiebre, setQuiebre] = useState<ArrastreQuiebre | null>(null)
  const quiebreRef = useRef<ArrastreQuiebre | null>(null)
  const fijarQuiebre = (q: ArrastreQuiebre | null) => {
    quiebreRef.current = q
    setQuiebre(q)
  }

  const cbRef = useRef({ onSelect, onMove, onConectar, onMoverQuiebres })
  cbRef.current = { onSelect, onMove, onConectar, onMoverQuiebres }

  /** Quiebres vigentes: durante el arrastre manda la copia de trabajo. */
  const quiebresDe = (relationId: string): Punto[] => {
    if (quiebre && quiebre.relationId === relationId) return quiebre.puntos
    return layout[relationId]?.waypoints ?? []
  }

  /**
   * Empieza a arrastrar un quiebre; si `insertar`, primero lo crea.
   *
   * `base` son los quiebres que el usuario ESTA VIENDO, que no siempre son
   * los guardados: una relacion reflexiva se dibuja con un bucle por defecto
   * que todavia no se persistio. Tocarlo lo convierte en quiebres de verdad.
   */
  const tomarQuiebre = (e: React.MouseEvent, relationId: string, indice: number,
                        insertar: boolean, base: Punto[]) => {
    if (!onMoverQuiebres) return
    const p = aCoordenadasDelModelo(e)
    if (!p) return
    e.stopPropagation()
    e.preventDefault()
    const puntos = insertar
      ? [...base.slice(0, indice), { x: p.x, y: p.y }, ...base.slice(indice)]
      : [...base]
    fijarQuiebre({ relationId, indice, puntos })
  }

  useEffect(() => {
    if (!quiebre) return

    const mover = (e: MouseEvent) => {
      const q = quiebreRef.current
      const p = aCoordenadasDelModelo(e)
      if (!q || !p) return
      const puntos = [...q.puntos]
      puntos[q.indice] = { x: Math.round(p.x), y: Math.round(p.y) }
      fijarQuiebre({ ...q, puntos })
    }

    const soltar = () => {
      const q = quiebreRef.current
      fijarQuiebre(null)
      if (q) cbRef.current.onMoverQuiebres?.(q.relationId, q.puntos)
    }

    window.addEventListener('mousemove', mover)
    window.addEventListener('mouseup', soltar)
    return () => {
      window.removeEventListener('mousemove', mover)
      window.removeEventListener('mouseup', soltar)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [quiebre?.relationId, quiebre?.indice])

  /** Doble clic sobre un quiebre lo saca: asi se vuelve a la recta. */
  const quitarQuiebre = (relationId: string, indice: number, base: Punto[]) => {
    cbRef.current.onMoverQuiebres?.(relationId, base.filter((_, i) => i !== indice))
  }

  /**
   * Una relacion de una clase consigo misma no puede ir "del borde al borde":
   * los dos extremos caerian en el mismo punto y la linea mediria cero.
   * Se dibuja como un bucle que sale por arriba y vuelve por la derecha.
   */
  const bucleReflexivo = (c: Caja) => ({
    desde: { x: c.x + c.width * 0.7, y: c.y },
    hasta: { x: c.x + c.width, y: c.y + c.height * 0.35 },
    quiebres: [
      { x: c.x + c.width * 0.7, y: c.y - 42 },
      { x: c.x + c.width + 42, y: c.y - 42 },
      { x: c.x + c.width + 42, y: c.y + c.height * 0.35 },
    ],
  })

  useEffect(() => {
    const zona = zonaRef.current
    if (!zona) return
    // clientWidth y no getBoundingClientRect: las barras ocupan lugar y no
    // son hueco visible. Contarlas haria que el encuadre quede corto.
    const medir = () => {
      if (zona.clientWidth > 0 && zona.clientHeight > 0) {
        setTam({ w: zona.clientWidth, h: zona.clientHeight })
        setMedido(true)
      }
    }
    medir()
    const ro = new ResizeObserver(medir)
    ro.observe(zona)
    return () => ro.disconnect()
  }, [])

  /**
   * Caja que envuelve TODO lo dibujado, con las posiciones que se estan
   * VIENDO: durante un arrastre manda la provisoria, asi el area crece
   * mientras se lleva una clase hacia afuera y nunca se corta el dibujo.
   */
  const limitesDelContenido = (): Caja | null => {
    let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity
    const sumar = (x: number, y: number) => {
      if (x < minX) minX = x
      if (y < minY) minY = y
      if (x > maxX) maxX = x
      if (y > maxY) maxY = y
    }
    for (const c of classes) {
      const r = caja(c.id)
      if (!r) continue
      // En Chen los ovalos orbitan la entidad y se salen de su caja.
      const orla = view === 'CHEN' && c.attributes.length > 0 ? 130 : 0
      sumar(r.x - orla, r.y - orla)
      sumar(r.x + r.width + orla, r.y + r.height + orla)
    }
    // Un quiebre arrastrado lejos tambien es parte del dibujo.
    for (const r of relationships) {
      for (const p of quiebresDe(r.id)) sumar(p.x, p.y)
    }
    if (minX === Infinity) return null
    return { x: minX, y: minY, width: maxX - minX, height: maxY - minY }
  }

  /**
   * Ultimo desplazamiento conocido. Hay que recordarlo porque el navegador
   * recorta `scrollLeft` solo en cuanto el area se achica, y entonces ya no
   * se puede saber que se estaba mirando para compensarlo.
   */
  const scrollRef = useRef({ left: 0, top: 0 })
  /** Ancla pendiente: se aplica despues del proximo dibujo. */
  const anclaRef = useRef<Ancla | null>(null)
  /** Area y zoom del dibujo anterior, para no perder el punto de vista. */
  const previoRef = useRef<{ area: Caja; zoom: number } | null>(null)

  const fijarScroll = (zona: HTMLDivElement, left: number, top: number) => {
    zona.scrollLeft = left
    zona.scrollTop = top
    // Lo que se guarda es lo que QUEDO, no lo que se pidio: el navegador
    // recorta contra los limites del area.
    scrollRef.current = { left: zona.scrollLeft, top: zona.scrollTop }
  }

  /** Lleva el punto `a.x,a.y` del modelo al lugar `a.px,a.py` del hueco. */
  const aplicarAncla = (a: Ancla, ar: Caja, z: number) => {
    const zona = zonaRef.current
    if (!zona) return
    fijarScroll(zona, (a.x - ar.x) * z - a.px, (a.y - ar.y) * z - a.py)
  }

  /** Punto del modelo que esta a `px,py` del borde del hueco. */
  const puntoDelHueco = (px: number, py: number, ar: Caja, z: number): Punto => ({
    x: ar.x + (scrollRef.current.left + px) / z,
    y: ar.y + (scrollRef.current.top + py) / z,
  })

  /**
   * Zoom dejando QUIETO el punto que esta bajo `cursor` (o el centro del
   * hueco). Escalar sin anclar hace perder de vista lo que se estaba mirando.
   */
  const escalar = (factor: number, cursor?: { clientX: number; clientY: number }) => {
    const nuevo = Math.min(ZOOM_MAX, Math.max(ZOOM_MIN, zoom * factor))
    if (nuevo === zoom) return
    const r = zonaRef.current?.getBoundingClientRect()
    const px = cursor && r ? cursor.clientX - r.left : tam.w / 2
    const py = cursor && r ? cursor.clientY - r.top : tam.h / 2
    const p = puntoDelHueco(px, py, area, zoom)
    anclaRef.current = { x: p.x, y: p.y, px, py }
    setZoom(nuevo)
  }

  /** Deja todo el modelo a la vista, centrado. */
  const encuadrar = () => {
    const b = limitesDelContenido()
    if (!b) return
    const ancho = b.width + MARGEN_ENCUADRE * 2
    const alto = b.height + MARGEN_ENCUADRE * 2
    // La franja de los controles no cuenta como espacio libre: si contara, la
    // clase de mas abajo quedaria justo debajo de los botones.
    const util = Math.max(120, tam.h - ALTO_CONTROLES)
    // Nunca agranda mas alla del 100%: un modelo de una sola clase se veria
    // gigante y desorienta mas de lo que ayuda.
    const z = Math.min(1, Math.max(ZOOM_MIN, Math.min(tam.w / ancho, util / alto)))
    const a: Ancla = {
      x: b.x + b.width / 2, y: b.y + b.height / 2,
      px: tam.w / 2, py: util / 2,
    }
    // Si el zoom no cambia no hay redibujo, y el ancla pendiente no se
    // aplicaria nunca: hay que moverse ya.
    if (z === zoom) aplicarAncla(a, area, zoom)
    else { anclaRef.current = a; setZoom(z) }
  }

  /**
   * Despues de cada dibujo: primero el ancla pendiente; si no hay, se sostiene
   * el punto de vista cuando el area se corrio sola (alguien arrastro una
   * clase mas alla del margen y la hoja crecio hacia ese lado).
   */
  useLayoutEffect(() => {
    const zona = zonaRef.current
    if (!zona) return
    const a = anclaRef.current
    const previo = previoRef.current
    if (a) {
      anclaRef.current = null
      aplicarAncla(a, area, zoom)
    } else if (previo && (previo.area.x !== area.x || previo.area.y !== area.y || previo.zoom !== zoom)) {
      const p = puntoDelHueco(0, 0, previo.area, previo.zoom)
      aplicarAncla({ x: p.x, y: p.y, px: 0, py: 0 }, area, zoom)
    }
    previoRef.current = { area, zoom }
  })

  useEffect(() => {
    const zona = zonaRef.current
    if (!zona) return
    const rueda = (e: WheelEvent) => {
      // La rueda pelada, Shift+rueda y el trackpad los maneja el navegador:
      // es scroll de verdad sobre el area. Solo se intercepta el zoom.
      if (!e.ctrlKey && !e.metaKey) return
      e.preventDefault()
      gestoRef.current.escalar(Math.exp(-e.deltaY * 0.002), e)
    }
    zona.addEventListener('wheel', rueda, { passive: false })
    return () => zona.removeEventListener('wheel', rueda)
  }, [])

  /** Arranca a arrastrar la hoja desde donde esta el mouse. */
  const iniciarPaneo = (e: { clientX: number; clientY: number; preventDefault: () => void }) => {
    const zona = zonaRef.current
    if (!zona) return
    e.preventDefault()
    const p = { clientX: e.clientX, clientY: e.clientY, left: zona.scrollLeft, top: zona.scrollTop }
    paneoRef.current = p
    setPaneo(p)
  }

  useEffect(() => {
    if (!paneo) return

    const mover = (e: MouseEvent) => {
      const zona = zonaRef.current
      const p = paneoRef.current
      if (!zona || !p) return
      // El scroll va al reves que el mouse: arrastrar hacia la derecha trae lo
      // que habia a la izquierda, como empujar una hoja sobre la mesa.
      fijarScroll(zona, p.left - (e.clientX - p.clientX), p.top - (e.clientY - p.clientY))
    }

    const soltar = () => {
      paneoRef.current = null
      setPaneo(null)
    }

    window.addEventListener('mousemove', mover)
    window.addEventListener('mouseup', soltar)
    return () => {
      window.removeEventListener('mousemove', mover)
      window.removeEventListener('mouseup', soltar)
    }
  }, [paneo])

  useEffect(() => {
    /**
     * Escribiendo en un campo, la barra espaciadora es un espacio; con un
     * boton enfocado, es su activacion. En los dos casos no es del lienzo.
     */
    const escribiendo = (t: EventTarget | null) => {
      const el = t as HTMLElement | null
      if (!el || !el.tagName) return false
      return /^(INPUT|TEXTAREA|SELECT|BUTTON|A)$/.test(el.tagName) || el.isContentEditable
    }

    const abajo = (e: KeyboardEvent) => {
      if (escribiendo(e.target)) return
      if (e.code === 'Space') {
        if (!espacioRef.current) { espacioRef.current = true; setEspacio(true) }
        e.preventDefault()   // si no, la pagina salta una pantalla
        return
      }
      if (e.ctrlKey || e.metaKey || e.altKey) return
      if (e.key === '+' || e.key === '=') gestoRef.current.escalar(PASO_ZOOM)
      else if (e.key === '-' || e.key === '_') gestoRef.current.escalar(1 / PASO_ZOOM)
      else if (e.key === '0') gestoRef.current.cienPorCiento()
      else if (e.key === 'f' || e.key === 'F') gestoRef.current.encuadrar()
    }

    const arriba = (e: KeyboardEvent) => {
      if (e.code === 'Space') { espacioRef.current = false; setEspacio(false) }
    }

    window.addEventListener('keydown', abajo)
    window.addEventListener('keyup', arriba)
    return () => {
      window.removeEventListener('keydown', abajo)
      window.removeEventListener('keyup', arriba)
    }
  }, [])

  /** Vuelve al 100% sin moverse de donde uno estaba mirando. */
  const cienPorCiento = () => escalar(1 / zoom)

  // Mismo patron que `cbRef`: los gestos leen la ultima version sin volver a
  // registrar sus listeners en cada render.
  const gestoRef = useRef({ escalar, encuadrar, cienPorCiento })
  gestoRef.current = { escalar, encuadrar, cienPorCiento }

  /**
   * Primer encuadre automatico. El modelo que llega del servidor puede estar
   * entero fuera del hueco inicial, y el lienzo se veria vacio sin que nadie
   * sepa que hay que ir a buscarlo.
   */
  const encuadradoRef = useRef(false)
  useEffect(() => {
    // Encuadrar con el tamano supuesto deja medio modelo afuera: hay que
    // esperar a la medicion real del hueco.
    if (encuadradoRef.current || !medido || classes.length === 0) return
    encuadradoRef.current = true
    encuadrar()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [medido, classes.length, tam.w, tam.h])

  /**
   * El SVG se estira con `width="100%"` sobre el viewBox de la ventana, asi
   * que un pixel de pantalla no es una unidad del modelo. La matriz del SVG
   * hace la conversion sin que tengamos que adivinar la escala ni el paneo.
   */
  const aCoordenadasDelModelo = (e: { clientX: number; clientY: number }) => {
    const svg = svgRef.current
    if (!svg) return null
    const ctm = svg.getScreenCTM()
    if (!ctm) return null
    const punto = svg.createSVGPoint()
    punto.x = e.clientX
    punto.y = e.clientY
    return punto.matrixTransform(ctm.inverse())
  }

  const alPresionar = (e: React.MouseEvent, elementId: string) => {
    // Espacio o boton del medio: el gesto es mover el LIENZO, no la clase.
    if (espacioRef.current || e.button === 1) { iniciarPaneo(e); return }

    // Con la herramienta Relacion activa, arrastrar TIRA UN CONECTOR en vez
    // de mover la clase: son la misma gestualidad, las distingue el modo.
    if (modoConector && onConectar) {
      const caj = caja(elementId)
      if (!caj) return
      e.preventDefault()
      fijarConector({ origenId: elementId, desde: centroDe(caj), hasta: centroDe(caj) })
      return
    }

    if (!onSelect && !onMove) return
    const l = layout[elementId]
    if (!l) return
    const p = aCoordenadasDelModelo(e)
    if (!p) return
    e.preventDefault()
    fijarArrastre({ id: elementId, dx: p.x - l.x, dy: p.y - l.y, x: l.x, y: l.y, movido: false })
  }

  useEffect(() => {
    if (!conector) return

    const mover = (e: MouseEvent) => {
      const p = aCoordenadasDelModelo(e)
      const c = conectorRef.current
      if (!p || !c) return
      fijarConector({ ...c, hasta: { x: p.x, y: p.y } })
    }

    const soltar = (e: MouseEvent) => {
      const c = conectorRef.current
      fijarConector(null)
      const p = aCoordenadasDelModelo(e)
      if (!c || !p) return
      const destino = claseEn(p)
      // Soltar sobre la MISMA clase es valido: es una relacion reflexiva
      // (el jefe de un empleado es otro empleado). Solo soltar al aire no hace nada.
      if (destino) cbRef.current.onConectar?.(c.origenId, destino)
    }

    window.addEventListener('mousemove', mover)
    window.addEventListener('mouseup', soltar)
    return () => {
      window.removeEventListener('mousemove', mover)
      window.removeEventListener('mouseup', soltar)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [conector?.origenId])

  useEffect(() => {
    if (!arrastre) return

    const mover = (e: MouseEvent) => {
      const a = arrastreRef.current
      const p = aCoordenadasDelModelo(e)
      if (!a || !p) return
      const x = p.x - a.dx
      const y = p.y - a.dy
      const movido = a.movido || Math.abs(x - a.x) > UMBRAL || Math.abs(y - a.y) > UMBRAL
      fijarArrastre({ ...a, x, y, movido })
    }

    const soltar = () => {
      const a = arrastreRef.current
      fijarArrastre(null)
      if (!a) return
      // Un arrastre mueve; un clic sin desplazamiento selecciona.
      if (a.movido) cbRef.current.onMove?.(a.id, Math.round(a.x), Math.round(a.y))
      else cbRef.current.onSelect?.(a.id)
    }

    window.addEventListener('mousemove', mover)
    window.addEventListener('mouseup', soltar)
    return () => {
      window.removeEventListener('mousemove', mover)
      window.removeEventListener('mouseup', soltar)
    }
  }, [arrastre?.id])

  /** Geometria efectiva: durante el arrastre manda la posicion provisoria. */
  const geo = (id: string): ElementLayout | null => {
    const l = layout[id]
    if (!l) return null
    return arrastre && arrastre.id === id ? { ...l, x: arrastre.x, y: arrastre.y } : l
  }

  const centro = (id: string) => {
    const l = geo(id)
    if (!l) return { x: 0, y: 0 }
    return { x: l.x + l.width / 2, y: l.y + l.height / 2 }
  }

  const porId = useMemo(() => new Map(classes.map((c) => [c.id, c])), [classes])

  /** En Clases la caja crece con los atributos; en Chen es fija. */
  const caja = (id: string): Caja | null => {
    const l = geo(id)
    const c = porId.get(id)
    if (!l || !c) return null
    const height = view === 'CHEN' ? l.height : altoDeCaja(c)
    return { x: l.x, y: l.y, width: l.width, height }
  }

  const centroDe = (r: Caja): Punto => ({ x: r.x + r.width / 2, y: r.y + r.height / 2 })

  /** Donde corta el borde de la caja la recta que va hacia `hacia`. */
  const borde = (r: Caja, hacia: Punto): Punto => {
    const c = centroDe(r)
    const dx = hacia.x - c.x
    const dy = hacia.y - c.y
    if (dx === 0 && dy === 0) return c
    const t = Math.min(
      dx === 0 ? Infinity : (r.width / 2) / Math.abs(dx),
      dy === 0 ? Infinity : (r.height / 2) / Math.abs(dy))
    return { x: c.x + dx * t, y: c.y + dy * t }
  }

  const claseEn = (p: Punto): string | null => {
    for (const c of classes) {
      const r = caja(c.id)
      if (r && p.x >= r.x && p.x <= r.x + r.width && p.y >= r.y && p.y <= r.y + r.height) return c.id
    }
    return null
  }

  const anguloRelaciones = useMemo(() => {
    const mapa: Record<string, number[]> = {}
    for (const r of relationships) {
      const a = centro(r.sourceClassId)
      const b = centro(r.targetClassId)
      ;(mapa[r.sourceClassId] ??= []).push(Math.atan2(b.y - a.y, b.x - a.x))
      ;(mapa[r.targetClassId] ??= []).push(Math.atan2(a.y - b.y, a.x - b.x))
    }
    return mapa
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [relationships, layout, arrastre])

  /**
   * AREA DE TRABAJO: la hoja por la que se recorre, en unidades del modelo.
   *
   * Es el dibujo mas un margen ancho a cada lado, y nunca menor que el hueco
   * visible. Se dibuja ENTERA y el navegador la desplaza: de su tamano salen
   * las barras. Mover la hoja no toca ni una coordenada del modelo, asi que
   * mirar no genera MOVE_ELEMENT ni se le contagia a los demas de la sesion.
   */
  const area: Caja = (() => {
    const b = limitesDelContenido() ?? { x: 0, y: 0, width: 800, height: 500 }
    let x = b.x - MARGEN_AREA
    let y = b.y - MARGEN_AREA
    let width = b.width + MARGEN_AREA * 2
    let height = b.height + MARGEN_AREA * 2
    // El hueco visible es el minimo: un area mas chica dejaria franjas
    // muertas a los costados y barras que no llegan a ningun lado.
    const hw = tam.w / zoom
    const hh = tam.h / zoom
    if (width < hw) { x -= (hw - width) / 2; width = hw }
    if (height < hh) { y -= (hh - height) / 2; height = hh }
    // Redondeado: media unidad corre TODO el dibujo medio pixel y el texto
    // sale borroso en cada arrastre.
    return { x: Math.round(x), y: Math.round(y), width: Math.round(width), height: Math.round(height) }
  })()
  const trama = TRAMA * zoom

  return (
    <div className="lienzo-marco">
      <div
        ref={zonaRef}
        className={paneo ? 'lienzo-zona paneando' : espacio ? 'lienzo-zona con-mano' : 'lienzo-zona'}
        onScroll={(e) => {
          scrollRef.current = { left: e.currentTarget.scrollLeft, top: e.currentTarget.scrollTop }
        }}>
      <svg ref={svgRef} className="lienzo"
           width={area.width * zoom} height={area.height * zoom}
           viewBox={`${area.x} ${area.y} ${area.width} ${area.height}`}
           style={{
             // La trama viaja con la hoja y se escala con el zoom, alineada al
             // origen del modelo: si quedara quieta, el desplazamiento no se
             // siente y no se sabe cuanto uno se movio.
             backgroundSize: `${trama}px ${trama}px`,
             backgroundPosition: `${-area.x * zoom}px ${-area.y * zoom}px`,
           }}
           onMouseDown={(e) => {
             // Fondo vacio, boton del medio o espacio: se arrastra la hoja.
             // Sobre una clase o una linea el evento nunca llega hasta aca.
             if (paneoRef.current) return
             if (e.target === svgRef.current || e.button === 1 || espacioRef.current) iniciarPaneo(e)
           }}>
        <defs>
          {/* Generalizacion: triangulo HUECO. UML no usa punta rellena aca. */}
          <marker id="triangulo-hueco" markerWidth="14" markerHeight="12" refX="13" refY="6"
                  orient="auto" markerUnits="userSpaceOnUse">
            <path d="M0,0 L13,6 L0,12 z" fill="#fff" stroke="#475569" strokeWidth={1.4} />
          </marker>
          {/* Dependencia: punta abierta, sin relleno ni base. */}
          <marker id="flecha-abierta" markerWidth="14" markerHeight="12" refX="12" refY="6"
                  orient="auto" markerUnits="userSpaceOnUse">
            <path d="M0,0 L12,6 L0,12" fill="none" stroke="#475569" strokeWidth={1.4} />
          </marker>
          {/* Agregacion: rombo hueco del lado del TODO. */}
          <marker id="rombo-hueco" markerWidth="20" markerHeight="12" refX="1" refY="6"
                  orient="auto" markerUnits="userSpaceOnUse">
            <path d="M0,6 L9,0 L18,6 L9,12 z" fill="#fff" stroke="#475569" strokeWidth={1.4} />
          </marker>
          {/* Composicion: el mismo rombo, relleno. */}
          <marker id="rombo-relleno" markerWidth="20" markerHeight="12" refX="1" refY="6"
                  orient="auto" markerUnits="userSpaceOnUse">
            <path d="M0,6 L9,0 L18,6 L9,12 z" fill="#475569" stroke="#475569" strokeWidth={1.4} />
          </marker>
        </defs>

        {/* ---------------- RELACIONES ---------------- */}
        {relationships.map((r) => {
          // La linea termina en el BORDE de cada caja, no en su centro: si no,
          // el rombo y el triangulo quedan tapados por la clase.
          const cajaA = caja(r.sourceClassId)
          const cajaB = caja(r.targetClassId)
          if (!cajaA || !cajaB) return null

          // Con quiebres, la linea sale de la caja hacia el PRIMER quiebre, no
          // hacia la otra clase: si no, arranca torcida.
          const guardados = quiebresDe(r.id)
          const reflexiva = r.sourceClassId === r.targetClassId
          const bucle = reflexiva ? bucleReflexivo(cajaA) : null

          const quiebres = bucle && guardados.length === 0 ? bucle.quiebres : guardados
          const a = bucle ? bucle.desde : borde(cajaA, quiebres[0] ?? centroDe(cajaB))
          const b = bucle ? bucle.hasta : borde(cajaB, quiebres[quiebres.length - 1] ?? centroDe(cajaA))
          const puntos = [a, ...quiebres, b]
          const m = medioDelCamino(puntos)
          const total = r.sourceParticipation === 'TOTAL' || r.targetParticipation === 'TOTAL'
          const n = notacion(r.kind)
          const elegida = relacionSeleccionada === r.id
          const trazo = puntos.map((p) => `${p.x},${p.y}`).join(' ')

          return (
            <g key={r.id} className={onSelectRelation ? 'relacion' : undefined}>
              <polyline points={trazo} fill="none"
                        stroke={elegida ? '#1d4ed8' : '#64748b'}
                        strokeWidth={elegida ? 2.5 : total && view === 'CHEN' ? 3 : 1.5}
                        strokeDasharray={n.punteada ? '6 4' : undefined}
                        markerStart={n.inicio} markerEnd={n.fin} />

              {/* Una franja invisible por SEGMENTO: acertarle a 1.5px es
                  imposible, y ademas asi se sabe donde insertar el quiebre. */}
              {onSelectRelation && puntos.slice(1).map((fin, i) => (
                <line key={i} x1={puntos[i].x} y1={puntos[i].y} x2={fin.x} y2={fin.y}
                      stroke="transparent" strokeWidth={14} pointerEvents="stroke"
                      style={{ cursor: elegida && onMoverQuiebres ? 'copy' : 'pointer' }}
                      onMouseDown={(e) => {
                        if (elegida && onMoverQuiebres) { tomarQuiebre(e, r.id, i, true, quiebres); return }
                        e.stopPropagation()
                        onSelectRelation(r.id)
                      }} />
              ))}

              {/* Las manijas solo aparecen en la relacion elegida. */}
              {elegida && onMoverQuiebres && quiebres.map((q, i) => (
                <circle key={i} cx={q.x} cy={q.y} r={5} className="quiebre"
                        onMouseDown={(e) => tomarQuiebre(e, r.id, i, false, quiebres)}
                        onDoubleClick={(e) => { e.stopPropagation(); quitarQuiebre(r.id, i, quiebres) }}>
                  <title>Arrastrá para acomodar · doble clic para quitarlo</title>
                </circle>
              ))}

              {view === 'CHEN' ? (
                <>
                  {/* rombo ER (no confundir con el de agregacion); doble si identifica */}
                  <polygon points={`${m.x},${m.y - 22} ${m.x + 42},${m.y} ${m.x},${m.y + 22} ${m.x - 42},${m.y}`}
                           fill="#fff" stroke="#475569" strokeWidth={1.5} />
                  {r.identifying && (
                    <polygon points={`${m.x},${m.y - 16} ${m.x + 32},${m.y} ${m.x},${m.y + 16} ${m.x - 32},${m.y}`}
                             fill="none" stroke="#475569" strokeWidth={1.2} />
                  )}
                  {r.name && (
                    <text x={m.x} y={m.y + 4} textAnchor="middle" className="txt-rel">{r.name}</text>
                  )}
                </>
              ) : (
                r.name && (
                  <text x={m.x} y={m.y - 6} textAnchor="middle" className="txt-rel">{r.name}</text>
                )
              )}

              {/* Las cardinalidades se pegan a su EXTREMO, siguiendo el primer
                  y el ultimo tramo, no la recta imaginaria entre clases. */}
              <text x={a.x + (puntos[1].x - a.x) * 0.3} y={a.y + (puntos[1].y - a.y) * 0.3 - 6}
                    className="txt-card">{cardinalidad(r.sourceMultiplicity)}</text>
              <text x={b.x + (puntos[puntos.length - 2].x - b.x) * 0.3}
                    y={b.y + (puntos[puntos.length - 2].y - b.y) * 0.3 - 6}
                    className="txt-card">{cardinalidad(r.targetMultiplicity)}</text>
            </g>
          )
        })}

        {/* Conector elastico mientras se arrastra una relacion nueva. */}
        {conector && (
          <line x1={conector.desde.x} y1={conector.desde.y} x2={conector.hasta.x} y2={conector.hasta.y}
                stroke="#1d4ed8" strokeWidth={1.8} strokeDasharray="5 4" pointerEvents="none" />
        )}

        {/* ---------------- ENTIDADES ---------------- */}
        {classes.map((c) => {
          const l = geo(c.id)
          if (!l) return null
          const cen = centro(c.id)
          const bloqueadaPor = locks[c.id]

          // --- Vista CHEN: rectangulo + ovalos calculados (nunca almacenados) ---
          if (view === 'CHEN') {
            const puntos = posicionarAtributos(cen, c.attributes.length, anguloRelaciones[c.id] ?? [])
            return (
              <g key={c.id} onMouseDown={(e) => alPresionar(e, c.id)} className="entidad">
                {c.attributes.map((a, i) => {
                  const p = puntos[i]
                  if (!p) return null
                  return (
                    <g key={a.id}>
                      <line x1={cen.x} y1={cen.y} x2={p.x} y2={p.y} stroke="#94a3b8" strokeWidth={1} />
                      <ellipse cx={p.x} cy={p.y} rx={46} ry={19}
                               fill="#fff" stroke="#475569"
                               strokeWidth={a.multivalued ? 3 : 1.4}
                               strokeDasharray={a.derived ? '5 3' : undefined} />
                      {a.multivalued && (
                        <ellipse cx={p.x} cy={p.y} rx={41} ry={15} fill="none" stroke="#475569" strokeWidth={1.1} />
                      )}
                      <text x={p.x} y={p.y + 4} textAnchor="middle"
                            className={a.pk ? 'txt-attr clave' : a.partialKey ? 'txt-attr clave-parcial' : 'txt-attr'}>
                        {a.name}
                      </text>
                    </g>
                  )
                })}
                <rect x={l.x} y={l.y} width={l.width} height={l.height} rx={3}
                      fill="#f8fafc" stroke={bloqueadaPor ? '#dc2626' : '#334155'} strokeWidth={2} />
                {c.weak && (
                  <rect x={l.x + 5} y={l.y + 5} width={l.width - 10} height={l.height - 10} rx={2}
                        fill="none" stroke="#334155" strokeWidth={1.2} />
                )}
                <text x={cen.x} y={cen.y + 5} textAnchor="middle" className="txt-entidad">{c.name}</text>
                {bloqueadaPor && (
                  <text x={l.x} y={l.y - 8} className="txt-lock">editando: {bloqueadaPor}</text>
                )}
              </g>
            )
          }

          // --- Vista CLASES: caja con compartimentos ---
          const alto = altoDeCaja(c)
          // Regla 6: el tercer compartimento. Solo aparece si hay metodos, para
          // no dejar una franja vacia en cada clase que no los usa.
          const yMetodos = l.y + 36 + c.attributes.length * 18
          const esEnum = esEnumeracion(c)
          return (
            <g key={c.id} onMouseDown={(e) => alPresionar(e, c.id)}
               className={arrastre?.id === c.id ? 'entidad arrastrando' : 'entidad'}>
              <rect x={l.x} y={l.y} width={l.width} height={alto} rx={3}
                    fill="#fff" stroke={bloqueadaPor ? '#dc2626' : '#334155'} strokeWidth={2} />
              <rect x={l.x} y={l.y} width={l.width} height={28} rx={3} fill="#e2e8f0" />
              {c.stereotype && (
                <text x={l.x + l.width / 2} y={l.y + 11} textAnchor="middle" className="txt-estereotipo">
                  «{c.stereotype}»
                </text>
              )}
              <text x={l.x + l.width / 2} y={l.y + (c.stereotype ? 23 : 19)} textAnchor="middle"
                    className={c.isAbstract ? 'txt-entidad abstracta' : 'txt-entidad'}>{c.name}</text>
              <line x1={l.x} y1={l.y + 28} x2={l.x + l.width} y2={l.y + 28} stroke="#334155" />
              {c.attributes.map((a, i) => (
                <text key={a.id} x={l.x + 10} y={l.y + 46 + i * 18} className="txt-attr">
                  {/* Un literal de enumeracion no lleva visibilidad ni tipo. */}
                  {esEnum ? a.name : firmaDeAtributo(a)}
                </text>
              ))}
              {c.methods.length > 0 && !esEnum && (
                <>
                  <line x1={l.x} y1={yMetodos} x2={l.x + l.width} y2={yMetodos} stroke="#334155" />
                  {c.methods.map((m, i) => {
                    const firma = firmaDeMetodo(m)
                    return (
                      <text key={m.id} x={l.x + 10} y={yMetodos + 18 + i * 18} className="txt-metodo">
                        {recortar(firma, l.width)}
                        <title>{firma}</title>
                      </text>
                    )
                  })}
                </>
              )}
              {bloqueadaPor && (
                <text x={l.x} y={l.y - 8} className="txt-lock">editando: {bloqueadaPor}</text>
              )}
            </g>
          )
        })}
      </svg>
      </div>

      <div className="zoom-ctrl">
        <button type="button" onClick={() => escalar(1 / PASO_ZOOM)}
                title="Alejar (tecla −, o Ctrl + rueda)">−</button>
        <button type="button" className="zoom-nivel" onClick={cienPorCiento}
                title="Volver al 100% (tecla 0)">{Math.round(zoom * 100)}%</button>
        <button type="button" onClick={() => escalar(PASO_ZOOM)}
                title="Acercar (tecla +, o Ctrl + rueda)">+</button>
        <span className="zoom-sep" />
        <button type="button" onClick={encuadrar}
                title="Ver todo el modelo (tecla F)">⤢</button>
      </div>
    </div>
  )
}
