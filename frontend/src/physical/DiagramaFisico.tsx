import { useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react'
import type { ElementLayout } from '../contracts/command'
import {
  ALTO_CABECERA, ALTO_FILA, acomodar, filaDeLaClave, limites, marcasDe, rutaDeFk,
  type Caja, type Lado, type Punto, type Ruta,
} from './diagrama'
import type { TablaFisica } from './esquema'

interface Props {
  tablas: TablaFisica[]
  /** La geometria del diagrama de Clases: de ahi sale donde va cada tabla. */
  layout: Record<string, ElementLayout>
  seleccionada: string | null
  onSeleccionar: (tabla: string) => void
  /** Lo que coincide con la busqueda; el resto se atenua. null = sin busqueda. */
  resaltadas: Set<string> | null
  /** Tablas cuya clase edita otra sesion. */
  bloqueadas: Set<string>
  /** Cambia cuando hay que llevar la vista hasta la seleccionada. */
  enfocar: number
  posiciones: Record<string, Punto>
  onMoverTabla: (tabla: string, posicion: Punto) => void
  onRestablecer: () => void
}

// Mismos valores que el lienzo de Clases: las dos hojas se manejan igual.
const ZOOM_MIN = 0.35
const ZOOM_INICIAL_MIN = 0.6
const ZOOM_MAX = 3
const PASO_ZOOM = 1.2
const MARGEN_ENCUADRE = 60
const MARGEN_AREA = 700
const TRAMA = 22
const ALTO_CONTROLES = 48

interface Paneo { clientX: number; clientY: number; left: number; top: number }
interface Ancla { x: number; y: number; px: number; py: number }
interface ArrastreTabla { tabla: string; clientX: number; clientY: number; x: number; y: number }

/**
 * DIAGRAMA FISICO (ADR-032, etapa 2): todas las tablas y sus FK, de un vistazo.
 *
 * Se recorre como el lienzo de Clases, que es como draw.io: una hoja que se
 * dibuja entera y desplaza el navegador (barras de verdad, rueda, Shift+rueda),
 * arrastre del fondo, boton del medio o barra espaciadora para mover la hoja,
 * Ctrl+rueda para el zoom anclado al puntero, `+` `-` `0` y `F` para encuadrar.
 *
 * Las tablas NO se arrastran: su lugar sale del diagrama de Clases (ver
 * diagrama.ts). Para acomodar una, se mueve su clase y la tabla la sigue.
 */
export function DiagramaFisico({ tablas, layout, seleccionada, onSeleccionar, resaltadas, bloqueadas,
  enfocar, posiciones, onMoverTabla, onRestablecer }: Props) {
  const zonaRef = useRef<HTMLDivElement>(null)
  const svgRef = useRef<SVGSVGElement>(null)
  const [zoom, setZoom] = useState(1)
  const [tam, setTam] = useState({ w: 1000, h: 600 })
  const [medido, setMedido] = useState(false)
  const [paneo, setPaneo] = useState<Paneo | null>(null)
  const paneoRef = useRef<Paneo | null>(null)
  const [espacio, setEspacio] = useState(false)
  const espacioRef = useRef(false)

  const cajas = useMemo(() => {
    const base = acomodar(tablas, layout)
    for (const [nombre, posicion] of Object.entries(posiciones)) {
      if (base[nombre]) base[nombre] = { ...base[nombre], x: posicion.x, y: posicion.y }
    }
    return base
  }, [tablas, layout, posiciones])
  const porNombre = useMemo(() => new Map(tablas.map((t) => [t.nombre, t])), [tablas])

  useEffect(() => {
    const zona = zonaRef.current
    if (!zona) return
    // clientWidth y no getBoundingClientRect: las barras no son hueco visible.
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

  /** La hoja: el dibujo con aire a cada lado, nunca menor que el hueco. */
  const contenido = limites(Object.values(cajas))
  const area: Caja = (() => {
    const b = contenido ?? { x: 0, y: 0, width: 800, height: 500 }
    let x = b.x - MARGEN_AREA
    let y = b.y - MARGEN_AREA
    let width = b.width + MARGEN_AREA * 2
    let height = b.height + MARGEN_AREA * 2
    const hw = tam.w / zoom
    const hh = tam.h / zoom
    if (width < hw) { x -= (hw - width) / 2; width = hw }
    if (height < hh) { y -= (hh - height) / 2; height = hh }
    return { x: Math.round(x), y: Math.round(y), width: Math.round(width), height: Math.round(height) }
  })()

  const scrollRef = useRef({ left: 0, top: 0 })
  const anclaRef = useRef<Ancla | null>(null)
  const previoRef = useRef<{ area: Caja; zoom: number } | null>(null)

  const fijarScroll = (zona: HTMLDivElement, left: number, top: number) => {
    zona.scrollLeft = left
    zona.scrollTop = top
    scrollRef.current = { left: zona.scrollLeft, top: zona.scrollTop }
  }

  const aplicarAncla = (a: Ancla, ar: Caja, z: number) => {
    const zona = zonaRef.current
    if (zona) fijarScroll(zona, (a.x - ar.x) * z - a.px, (a.y - ar.y) * z - a.py)
  }

  const puntoDelHueco = (px: number, py: number, ar: Caja, z: number): Punto => ({
    x: ar.x + (scrollRef.current.left + px) / z,
    y: ar.y + (scrollRef.current.top + py) / z,
  })

  /** Zoom dejando quieto el punto bajo el cursor (o el centro del hueco). */
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

  /** Todo el esquema a la vista, sin agrandar mas alla del 100%. */
  const encuadrar = () => {
    const b = contenido
    if (!b) return
    const util = Math.max(120, tam.h - ALTO_CONTROLES)
    const z = Math.min(1, Math.max(ZOOM_INICIAL_MIN, Math.min(tam.w / (b.width + MARGEN_ENCUADRE * 2),
      util / (b.height + MARGEN_ENCUADRE * 2))))
    const a: Ancla = { x: b.x + b.width / 2, y: b.y + b.height / 2, px: tam.w / 2, py: util / 2 }
    if (z === zoom) aplicarAncla(a, area, zoom)
    else { anclaRef.current = a; setZoom(z) }
  }

  const cienPorCiento = () => escalar(1 / zoom)

  useLayoutEffect(() => {
    const a = anclaRef.current
    const previo = previoRef.current
    if (a) {
      anclaRef.current = null
      aplicarAncla(a, area, zoom)
    } else if (previo && (previo.area.x !== area.x || previo.area.y !== area.y || previo.zoom !== zoom)) {
      // La hoja se corrio sola (llego una tabla nueva mas alla del margen):
      // se sostiene lo que se estaba mirando.
      const p = puntoDelHueco(0, 0, previo.area, previo.zoom)
      aplicarAncla({ x: p.x, y: p.y, px: 0, py: 0 }, area, zoom)
    }
    previoRef.current = { area, zoom }
  })

  const gestoRef = useRef({ escalar, encuadrar, cienPorCiento })
  gestoRef.current = { escalar, encuadrar, cienPorCiento }

  useEffect(() => {
    const zona = zonaRef.current
    if (!zona) return
    const rueda = (e: WheelEvent) => {
      tocadoRef.current = true
      // Rueda sola y Shift+rueda son scroll del navegador; solo Ctrl es zoom.
      if (!e.ctrlKey && !e.metaKey) return
      e.preventDefault()
      gestoRef.current.escalar(Math.exp(-e.deltaY * 0.002), e)
    }
    zona.addEventListener('wheel', rueda, { passive: false })
    return () => zona.removeEventListener('wheel', rueda)
  }, [])

  const iniciarPaneo = (e: { clientX: number; clientY: number; preventDefault: () => void }) => {
    const zona = zonaRef.current
    if (!zona) return
    e.preventDefault()
    const p = { clientX: e.clientX, clientY: e.clientY, left: zona.scrollLeft, top: zona.scrollTop }
    paneoRef.current = p
    setPaneo(p)
  }

  const [arrastreTabla, setArrastreTabla] = useState<ArrastreTabla | null>(null)
  const arrastreTablaRef = useRef<ArrastreTabla | null>(null)
  const iniciarArrastreTabla = (e: React.MouseEvent, tabla: string, caja: Caja) => {
    if (e.button !== 0 || espacioRef.current) return
    e.preventDefault()
    e.stopPropagation()
    tocar()
    onSeleccionar(tabla)
    const inicio = { tabla, clientX: e.clientX, clientY: e.clientY, x: caja.x, y: caja.y }
    arrastreTablaRef.current = inicio
    setArrastreTabla(inicio)
  }

  useEffect(() => {
    if (!arrastreTabla) return
    const mover = (e: MouseEvent) => {
      const inicio = arrastreTablaRef.current
      if (!inicio) return
      onMoverTabla(inicio.tabla, {
        x: Math.round(inicio.x + (e.clientX - inicio.clientX) / zoom),
        y: Math.round(inicio.y + (e.clientY - inicio.clientY) / zoom),
      })
    }
    const soltar = () => { arrastreTablaRef.current = null; setArrastreTabla(null) }
    window.addEventListener('mousemove', mover)
    window.addEventListener('mouseup', soltar)
    return () => {
      window.removeEventListener('mousemove', mover)
      window.removeEventListener('mouseup', soltar)
    }
  }, [arrastreTabla, zoom, onMoverTabla])

  useEffect(() => {
    if (!paneo) return
    const mover = (e: MouseEvent) => {
      const zona = zonaRef.current
      const p = paneoRef.current
      if (zona && p) fijarScroll(zona, p.left - (e.clientX - p.clientX), p.top - (e.clientY - p.clientY))
    }
    const soltar = () => { paneoRef.current = null; setPaneo(null) }
    window.addEventListener('mousemove', mover)
    window.addEventListener('mouseup', soltar)
    return () => {
      window.removeEventListener('mousemove', mover)
      window.removeEventListener('mouseup', soltar)
    }
  }, [paneo])

  useEffect(() => {
    // En un campo del editor, las teclas son del campo: el espacio es un
    // espacio y el "0" es un cero, no un gesto del lienzo.
    const escribiendo = (t: EventTarget | null) => {
      const el = t as HTMLElement | null
      return !!el?.tagName && (/^(INPUT|TEXTAREA|SELECT|BUTTON|A)$/.test(el.tagName) || el.isContentEditable)
    }
    const abajo = (e: KeyboardEvent) => {
      if (escribiendo(e.target)) return
      if (e.code === 'Space') {
        if (!espacioRef.current) { espacioRef.current = true; setEspacio(true) }
        e.preventDefault()
        return
      }
      if (e.ctrlKey || e.metaKey || e.altKey) return
      if (['+', '=', '-', '_', '0', 'f', 'F'].includes(e.key)) tocadoRef.current = true
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

  /**
   * Encuadre automatico, con el hueco ya medido, MIENTRAS el usuario no haya
   * tocado la vista. El hueco cambia solo al abrirse la pantalla (aparece el
   * editor de abajo, se acomoda la ventana): encuadrar una sola vez, antes de
   * eso, dejaba medio esquema afuera. En cuanto alguien hace zoom, arrastra o
   * se desplaza, la vista es suya y no se le mueve mas.
   */
  const tocadoRef = useRef(false)
  const tocar = () => { tocadoRef.current = true }
  useEffect(() => {
    if (tocadoRef.current || !medido || tablas.length === 0) return
    encuadrar()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [medido, tablas.length, tam.w, tam.h])

  // Llevar la vista hasta la tabla elegida desde la busqueda o el teclado,
  // solo si no se ve entera: si ya se ve, moverse desorienta.
  useEffect(() => {
    if (!enfocar || !seleccionada) return
    const c = cajas[seleccionada]
    const zona = zonaRef.current
    if (!c || !zona) return
    const izq = area.x + scrollRef.current.left / zoom
    const arr = area.y + scrollRef.current.top / zoom
    const visible = c.x >= izq && c.y >= arr && c.x + c.width <= izq + tam.w / zoom
      && c.y + c.height <= arr + (tam.h - ALTO_CONTROLES) / zoom
    if (!visible) aplicarAncla({ x: c.x + c.width / 2, y: c.y + c.height / 2, px: tam.w / 2, py: tam.h / 2 }, area, zoom)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [enfocar])

  /** Las FK, calculadas una vez por dibujo. */
  const lineas = useMemo(() => {
    const salida: { clave: string; ruta: Ruta; unica: boolean; admiteNull: boolean; onDelete: string | null;
      esClave: boolean; desde: string; hasta: string }[] = []
    for (const t of tablas) {
      const origen = cajas[t.nombre]
      if (!origen) continue
      t.columnas.forEach((c, i) => {
        if (!c.referencia) return
        const destinoTabla = porNombre.get(c.referencia)
        const destino = cajas[c.referencia]
        if (!destinoTabla || !destino) return
        salida.push({
          clave: t.nombre + '.' + c.nombre,
          ruta: rutaDeFk(origen, i, destino, filaDeLaClave(destinoTabla), t.nombre === c.referencia),
          unica: c.unique || (c.pk && t.columnas.filter((x) => x.pk).length === 1),
          admiteNull: c.nullable,
          onDelete: c.onDelete ?? null,
          esClave: c.pk,
          desde: t.nombre,
          hasta: c.referencia,
        })
      })
    }
    return salida
  }, [tablas, cajas, porNombre])

  const trama = TRAMA * zoom

  return (
    <div className="lienzo-marco fis-lienzo">
      <div ref={zonaRef}
           className={paneo ? 'lienzo-zona paneando' : espacio ? 'lienzo-zona con-mano' : 'lienzo-zona'}
           // Cualquier gesto sobre la hoja --incluida una barra de desplazamiento--
           // es del usuario: a partir de ahi no se re-encuadra solo.
           onMouseDown={tocar}
           onScroll={(e) => { scrollRef.current = { left: e.currentTarget.scrollLeft, top: e.currentTarget.scrollTop } }}>
        <svg ref={svgRef} className="lienzo"
             width={area.width * zoom} height={area.height * zoom}
             viewBox={`${area.x} ${area.y} ${area.width} ${area.height}`}
             style={{ backgroundSize: `${trama}px ${trama}px`, backgroundPosition: `${-area.x * zoom}px ${-area.y * zoom}px` }}
             onMouseDown={(e) => {
               if (paneoRef.current) return
               if (e.target === svgRef.current || e.button === 1 || espacioRef.current) iniciarPaneo(e)
             }}>
          {lineas.map((l) => {
            const activa = l.desde === seleccionada || l.hasta === seleccionada
            const atenuada = resaltadas !== null && !resaltadas.has(l.desde) && !resaltadas.has(l.hasta)
            return (
              <g key={l.clave} className={'fis-fk' + (activa ? ' activa' : '')} opacity={atenuada ? 0.2 : 1}>
                <polyline points={l.ruta.puntos.map((p) => `${p.x},${p.y}`).join(' ')} fill="none" />
                <Extremo punto={l.ruta.puntos[0]} lado={l.ruta.ladoOrigen} muchos={!l.unica} opcional />
                <Extremo punto={l.ruta.puntos[l.ruta.puntos.length - 1]} lado={l.ruta.ladoDestino}
                         muchos={false} opcional={l.admiteNull} />
                {/* Solo donde dice algo: RESTRICT es lo normal, y en una clave
                    heredada o de entidad debil el CASCADE va de suyo. */}
                {l.onDelete && l.onDelete !== 'RESTRICT' && !l.esClave && (() => {
                  const e = lugarDeEtiqueta(l.ruta.puntos[0], l.ruta.ladoOrigen)
                  return (
                    <text className={'fis-fk-borrado ' + (l.onDelete === 'CASCADE' ? 'cascada' : 'nulo')}
                          x={e.x} y={e.y} textAnchor={e.ancla}>
                      {l.onDelete === 'CASCADE' ? 'CASCADE' : 'SET NULL'}
                    </text>
                  )
                })()}
              </g>
            )
          })}

          {tablas.map((t) => {
            const c = cajas[t.nombre]
            if (!c) return null
            const elegida = t.nombre === seleccionada
            const atenuada = resaltadas !== null && !resaltadas.has(t.nombre)
            const derivada = !t.claseId
            return (
              <g key={t.nombre} className={'fis-tabla' + (arrastreTabla?.tabla === t.nombre ? ' arrastrando' : '')}
                 opacity={atenuada ? 0.3 : 1}
                 onMouseDown={(e) => iniciarArrastreTabla(e, t.nombre, c)}
                 onClick={() => onSeleccionar(t.nombre)}>
                <title>{`${t.nombre} — viene de ${t.origen}`}</title>
                <rect x={c.x} y={c.y} width={c.width} height={c.height} rx={6}
                      className={'fis-tabla-cuerpo' + (derivada ? ' derivada' : '')} />
                <path d={`M${c.x},${c.y + ALTO_CABECERA} V${c.y + 6} Q${c.x},${c.y} ${c.x + 6},${c.y}`
                  + ` H${c.x + c.width - 6} Q${c.x + c.width},${c.y} ${c.x + c.width},${c.y + 6} V${c.y + ALTO_CABECERA} Z`}
                      className={'fis-tabla-cabecera' + (derivada ? ' derivada' : '')} />
                <text x={c.x + 10} y={c.y + 19} className="fis-tabla-nombre">{t.nombre}</text>
                {bloqueadas.has(t.nombre) && (
                  <circle cx={c.x + c.width - 12} cy={c.y + 15} r={4} className="fis-tabla-bloqueo" />
                )}
                {t.columnas.map((col, i) => {
                  const y = c.y + ALTO_CABECERA + i * ALTO_FILA
                  const marcas = marcasDe(col)
                  return (
                    <g key={col.nombre}>
                      {col.pk && <text x={c.x + 8} y={y + 14} className="fis-tag pk">PK</text>}
                      {col.referencia && <text x={c.x + (col.pk ? 24 : 8)} y={y + 14} className="fis-tag fk">FK</text>}
                      <text x={c.x + 44} y={y + 14} className={'fis-col-nombre' + (col.nullable ? '' : ' nn')}>{col.nombre}</text>
                      <text x={c.x + c.width - 8} y={y + 14} textAnchor="end" className="fis-col-tipo">
                        {col.tipo}{marcas ? ' ' + marcas : ''}
                      </text>
                    </g>
                  )
                })}
                {elegida && (
                  <rect x={c.x - 3} y={c.y - 3} width={c.width + 6} height={c.height + 6} rx={8}
                        className="fis-tabla-elegida" />
                )}
              </g>
            )
          })}
        </svg>
      </div>

      <div className="fis-leyenda">
        <span><b className="pk">PK</b> clave primaria</span>
        <span><b className="fk">FK</b> clave foránea</span>
        <span>pata de gallo: muchos · barra: uno · círculo: puede faltar</span>
        <span className="tenue">Arrastrá una tabla para ordenar esta vista sin modificar el UML.</span>
      </div>

      <div className="zoom-ctrl" onMouseDown={tocar}>
        <button type="button" onClick={() => escalar(1 / PASO_ZOOM)} title="Alejar (tecla −, o Ctrl + rueda)">−</button>
        <button type="button" className="zoom-nivel" onClick={cienPorCiento} title="Volver al 100% (tecla 0)">
          {Math.round(zoom * 100)}%
        </button>
        <button type="button" onClick={() => escalar(PASO_ZOOM)} title="Acercar (tecla +, o Ctrl + rueda)">+</button>
        <span className="zoom-sep" />
        <button type="button" onClick={encuadrar} title="Ver todo el esquema (tecla F)">⤢</button>
        <span className="zoom-sep" />
        <button type="button" onClick={() => { onRestablecer(); tocadoRef.current = false }}
                title="Restablecer la distribución física">↺</button>
      </div>
    </div>
  )
}

/**
 * El simbolo de un extremo de la FK, en notacion pata de gallo: tres puntas si
 * son muchos, una barra si es uno, y un circulo si puede faltar. Se dibuja
 * sobre el tramo que sale de la tabla, mirando hacia afuera.
 */
function Extremo({ punto, lado, muchos, opcional }: {
  punto: Punto
  lado: Lado
  muchos: boolean
  opcional: boolean
}) {
  const { x, y } = punto
  if (lado === 'arriba' || lado === 'abajo') {
    const d = lado === 'abajo' ? 1 : -1
    return (
      <g className="fis-fk-extremo">
        {muchos
          ? <path d={`M${x},${y + d * 10} L${x - 6},${y} M${x},${y + d * 10} L${x + 6},${y}`} />
          : <path d={`M${x - 6},${y + d * 8} H${x + 6}`} />}
        {opcional
          ? <circle cx={x} cy={y + d * 16} r={3.5} />
          : <path d={`M${x - 6},${y + d * 14} H${x + 6}`} />}
      </g>
    )
  }
  const d = lado === 'derecha' ? 1 : -1
  return (
    <g className="fis-fk-extremo">
      {muchos
        ? <path d={`M${x + d * 10},${y} L${x},${y - 6} M${x + d * 10},${y} L${x},${y + 6}`} />
        : <path d={`M${x + d * 8},${y - 6} V${y + 6}`} />}
      {opcional
        ? <circle cx={x + d * 16} cy={y} r={3.5} />
        : <path d={`M${x + d * 14},${y - 6} V${y + 6}`} />}
    </g>
  )
}

/** Donde va la etiqueta del ON DELETE: pegada al extremo de la FK, del lado de afuera. */
function lugarDeEtiqueta(p: Punto, lado: Lado): { x: number; y: number; ancla: 'start' | 'end' } {
  switch (lado) {
    case 'derecha': return { x: p.x + 26, y: p.y - 6, ancla: 'start' }
    case 'izquierda': return { x: p.x - 26, y: p.y - 6, ancla: 'end' }
    case 'arriba': return { x: p.x + 8, y: p.y - 24, ancla: 'start' }
    case 'abajo': return { x: p.x + 8, y: p.y + 30, ancla: 'start' }
  }
}
