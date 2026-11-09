import { hace, type EntradaLegible } from './describir'

interface Props {
  entradas: EntradaLegible[]
  /** Para el "hace 2 min": lo pasa quien redibuja cada tanto. */
  ahora: number
  vacio: string
  /** Cuantas mostrar; el resto queda en el log del servidor. */
  max?: number
}

const inicial = (nombre: string) => nombre.trim().charAt(0).toUpperCase() || '?'

/**
 * Quien cambio que y cuando, en una frase. El `#seq` y el tipo de comando no se
 * pierden: quedan en el title de cada linea, para quien rastrea el log.
 */
export function ListaHistorial({ entradas, ahora, vacio, max = 40 }: Props) {
  if (entradas.length === 0) return <p className="tenue">{vacio}</p>
  return (
    <ul className="hist">
      {entradas.slice(0, max).map((e) => (
        <li key={e.opId} title={e.tecnico}>
          <span className={e.propia ? 'hist-avatar propia' : 'hist-avatar'} aria-hidden>{inicial(e.autor)}</span>
          <div className="hist-cuerpo">
            <p className="hist-texto">
              <strong>{e.autor}</strong> {e.texto}
            </p>
            <p className="hist-meta">
              {hace(e.ts, ahora)}
              {e.origen && <span className="hist-origen">{e.origen}</span>}
              {e.deshace && <span className="hist-origen" title="Fue un deshacer o rehacer">↶ deshacer</span>}
            </p>
          </div>
        </li>
      ))}
    </ul>
  )
}
