import { useMemo, useState, type ReactNode } from 'react'
import type { RelationKind, UmlClass, UmlRelationship } from '../contracts/command'
import { NotacionRelacion } from '../toolbar/NotacionRelacion'
import {
  LETRA, NOMBRE_DE_TIPO, nodosDeClases, nodosDeRelaciones, type TipoDeElemento,
} from './arbol'

/**
 * EL PANEL IZQUIERDO, como en StarUML: el arbol del proyecto arriba y la
 * paleta abajo.
 *
 * La paleta solo ofrece lo que el catalogo cerrado de comandos sabe crear
 * (ADR-014). Por eso no hay "Interfaz" ni "Realizacion": el metamodelo no los
 * tiene, y un boton que el servidor rechaza es peor que no tenerlo.
 */

/** Lo que esta armado en la paleta: nada (seleccionar) o un tipo de relacion. */
export type Herramienta = { tipo: 'SELECCIONAR' } | { tipo: 'RELACION'; kind: RelationKind } | { tipo: 'ASOCIATIVA' }

const ELEMENTOS: { tipo: TipoDeElemento; ayuda: string; icono: string }[] = [
  { tipo: 'CLASE', icono: '▭', ayuda: 'Una entidad con su propia tabla' },
  { tipo: 'ABSTRACTA', icono: '⬚', ayuda: 'Una clase que no se instancia: solo la heredan otras' },
  { tipo: 'ENUM', icono: '☰', ayuda: 'Una lista cerrada de valores: se usa como tipo de un atributo' },
  { tipo: 'VALOR', icono: '▣', ayuda: 'Un valor embebido (@Embeddable): sin tabla propia, vive dentro de quien lo usa' },
]

const RELACIONES: { kind: RelationKind; nombre: string }[] = [
  { kind: 'ASSOCIATION', nombre: 'asociación' },
  { kind: 'AGGREGATION', nombre: 'agregación' },
  { kind: 'COMPOSITION', nombre: 'composición' },
  { kind: 'GENERALIZATION', nombre: 'herencia' },
  { kind: 'DEPENDENCY', nombre: 'dependencia' },
]

const PLACEHOLDER: Record<TipoDeElemento, string> = {
  CLASE: 'Nombre de la clase', ABSTRACTA: 'Nombre de la clase abstracta',
  ENUM: 'Nombre de la enumeración', VALOR: 'Nombre del valor (ej. Direccion)',
}

interface Props {
  proyecto: string
  clases: UmlClass[]
  relaciones: UmlRelationship[]
  /** Clase o relacion seleccionada en el lienzo. */
  seleccionada: string | null
  onSeleccionarClase: (id: string) => void
  onSeleccionarRelacion: (id: string) => void
  /** Sin permiso o sin conexion, la paleta se ve pero no crea. */
  puedeEditar: boolean
  onCrear: (tipo: TipoDeElemento, nombre: string) => void
  herramienta: Herramienta
  onHerramienta: (h: Herramienta) => void
}

export function Explorador({
  proyecto, clases, relaciones, seleccionada, onSeleccionarClase, onSeleccionarRelacion,
  puedeEditar, onCrear, herramienta, onHerramienta,
}: Props) {
  const nodos = useMemo(() => nodosDeClases(clases), [clases])
  const lineas = useMemo(() => nodosDeRelaciones(relaciones, clases), [relaciones, clases])
  const [abiertas, setAbiertas] = useState<Set<string>>(new Set())
  const [creando, setCreando] = useState<TipoDeElemento | null>(null)
  const [nombre, setNombre] = useState('')

  const alternar = (id: string) => setAbiertas((prev) => {
    const s = new Set(prev)
    if (s.has(id)) s.delete(id); else s.add(id)
    return s
  })

  const confirmar = () => {
    const n = nombre.trim()
    if (n && creando) onCrear(creando, n)
    setNombre('')
    setCreando(null)
  }

  const ayuda = herramienta.tipo === 'RELACION'
    ? 'Arrastrá de una clase a otra para relacionarlas. Esc o «Seleccionar» cancela.'
    : herramienta.tipo === 'ASOCIATIVA'
      ? 'Hacé clic en una relación muchos a muchos (0..* en los dos extremos): en su panel, «Clase intermedia» la convierte en una clase de asociación.'
      : 'Elegí un elemento para crearlo, o una relación y arrastrá de una clase a otra.'

  return (
    <aside className="explorador" aria-label="Proyecto y paleta">
      <Seccion titulo="Proyecto">
        <p className="ex-proyecto" title={proyecto}>{proyecto}</p>
        <Seccion titulo="Clases" cuenta={clases.length} anidada>
          {nodos.length === 0 && <p className="ex-vacio">Todavía no hay clases.</p>}
          <ul className="ex-arbol">
            {nodos.map((c) => (
              <li key={c.id}>
                <div className={seleccionada === c.id ? 'ex-nodo activo' : 'ex-nodo'}>
                  <button type="button" className="ex-chevron" aria-label={abiertas.has(c.id) ? 'Plegar' : 'Desplegar'}
                          aria-expanded={abiertas.has(c.id)} disabled={c.miembros.length === 0}
                          onClick={() => alternar(c.id)}>
                    {c.miembros.length > 0 ? (abiertas.has(c.id) ? '▾' : '▸') : ''}
                  </button>
                  <button type="button" className="ex-nombre" title={`${NOMBRE_DE_TIPO[c.tipo]} · clic para verla en el lienzo`}
                          onClick={() => onSeleccionarClase(c.id)}>
                    <span className={'ex-letra t-' + c.tipo}>{LETRA[c.tipo]}</span>
                    <span className={c.tipo === 'ABSTRACTA' ? 'ex-texto abstracta' : 'ex-texto'}>{c.nombre}</span>
                  </button>
                </div>
                {abiertas.has(c.id) && (
                  <ul className="ex-miembros">
                    {c.miembros.map((m, i) => <li key={i}><code>{m}</code></li>)}
                  </ul>
                )}
              </li>
            ))}
          </ul>
        </Seccion>
        <Seccion titulo="Relaciones" cuenta={relaciones.length} anidada>
          {lineas.length === 0 && <p className="ex-vacio">Todavía no hay relaciones.</p>}
          <ul className="ex-arbol">
            {lineas.map((r) => (
              <li key={r.id}>
                <button type="button" className={seleccionada === r.id ? 'ex-nodo ex-relacion activo' : 'ex-nodo ex-relacion'}
                        onClick={() => onSeleccionarRelacion(r.id)} title="Clic para editarla">
                  <span className="ex-mini"><NotacionRelacion kind={r.kind} /></span>
                  <span className="ex-texto">{r.texto}</span>
                </button>
              </li>
            ))}
          </ul>
        </Seccion>
      </Seccion>

      <Seccion titulo="Paleta" grande>
        <button type="button"
                className={herramienta.tipo === 'SELECCIONAR' ? 'ex-herramienta activa' : 'ex-herramienta'}
                onClick={() => onHerramienta({ tipo: 'SELECCIONAR' })}>
          <span className="ex-icono">↖</span> Seleccionar
        </button>

        <h4 className="ex-grupo">Elementos</h4>
        {creando ? (
          <form className="ex-crear" onSubmit={(e) => { e.preventDefault(); confirmar() }}>
            <input autoFocus value={nombre} placeholder={PLACEHOLDER[creando]}
                   onChange={(e) => setNombre(e.target.value)}
                   onKeyDown={(e) => { if (e.key === 'Escape') { setCreando(null); setNombre('') } }} />
            <div className="ex-crear-botones">
              <button type="submit" className="tb-btn tb-primario-chico" disabled={!nombre.trim()}>Crear</button>
              <button type="button" className="tb-btn" onClick={() => { setCreando(null); setNombre('') }}>Cancelar</button>
            </div>
          </form>
        ) : (
          ELEMENTOS.map((e) => (
            <button key={e.tipo} type="button" className="ex-herramienta" disabled={!puedeEditar}
                    title={e.ayuda}
                    onClick={() => { onHerramienta({ tipo: 'SELECCIONAR' }); setCreando(e.tipo) }}>
              <span className="ex-icono">{e.icono}</span> {NOMBRE_DE_TIPO[e.tipo]}
            </button>
          ))
        )}

        <h4 className="ex-grupo">Relaciones</h4>
        {RELACIONES.map((r) => {
          const activa = herramienta.tipo === 'RELACION' && herramienta.kind === r.kind
          return (
            <button key={r.kind} type="button" disabled={!puedeEditar}
                    className={activa ? 'ex-herramienta activa' : 'ex-herramienta'}
                    onClick={() => onHerramienta(activa ? { tipo: 'SELECCIONAR' } : { tipo: 'RELACION', kind: r.kind })}>
              <span className="ex-mini"><NotacionRelacion kind={r.kind} /></span> {r.nombre}
            </button>
          )
        })}
        <button type="button" disabled={!puedeEditar}
                className={herramienta.tipo === 'ASOCIATIVA' ? 'ex-herramienta activa' : 'ex-herramienta'}
                onClick={() => onHerramienta(herramienta.tipo === 'ASOCIATIVA' ? { tipo: 'SELECCIONAR' } : { tipo: 'ASOCIATIVA' })}>
          <span className="ex-icono">⊟</span> Clase de asociación
        </button>

        <p className="ex-ayuda" role="status">{puedeEditar ? ayuda : 'Solo lectura: la paleta no crea nada.'}</p>
      </Seccion>
    </aside>
  )
}

function Seccion({ titulo, cuenta, anidada = false, grande = false, children }: {
  titulo: string
  cuenta?: number
  anidada?: boolean
  grande?: boolean
  children: ReactNode
}) {
  const [abierta, setAbierta] = useState(true)
  return (
    <section className={['ex-seccion', anidada ? 'anidada' : '', grande ? 'grande' : ''].join(' ')}>
      <button type="button" className="ex-cabecera" aria-expanded={abierta} onClick={() => setAbierta(!abierta)}>
        <span className="ex-flecha">{abierta ? '▾' : '▸'}</span>
        <span className="ex-titulo">{titulo}</span>
        {cuenta !== undefined && <span className="ex-cuenta">{cuenta}</span>}
      </button>
      {abierta && <div className="ex-cuerpo">{children}</div>}
    </section>
  )
}
