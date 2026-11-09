import { useMemo, useState } from 'react'
import { elegir, elegirTodos, sinResolver } from './merge'
import { mostrarValor, totalDeCambios } from './diff'
import { etiquetaDeCampo, VistaDiff } from './VistaDiff'
import { CAMPO_EXISTENCIA, type MergePreview, type Resolucion } from './tipos'

interface Props {
  preview: MergePreview
  /** Nombre de la rama de la que se trae el cambio. */
  nombreDeOrigen: string
  puedeAplicar: boolean
  aplicando: boolean
  error: string | null
  onAplicar: (resoluciones: Resolucion[]) => void
  onVolver: () => void
}

const TIPO: Record<string, string> = { clase: 'Clase', atributo: 'Atributo', metodo: 'Método', relacion: 'Relación' }
const ESTADO_EXISTENCIA: Record<string, string> = { existe: 'se queda', eliminado: 'se elimina' }

const valor = (campo: string, v: unknown) =>
  campo === CAMPO_EXISTENCIA && typeof v === 'string' ? (ESTADO_EXISTENCIA[v] ?? v) : mostrarValor(v, campo)

/**
 * Merge a tres bandas: primero lo que cada lado cambio, despues los conflictos,
 * uno por uno, con "nuestro" / "suyo". Aplicar se habilita cuando no queda
 * ninguno sin elegir. No hay valor por defecto: elegir es una decision.
 */
export function VistaMerge({ preview, nombreDeOrigen, puedeAplicar, aplicando, error, onAplicar, onVolver }: Props) {
  const [resoluciones, setResoluciones] = useState<Resolucion[]>([])
  const pendientes = useMemo(() => sinResolver(preview.conflictos, resoluciones), [preview, resoluciones])
  const eleccionDe = (id: string, campo: string) =>
    resoluciones.find((r) => r.elementId === id && r.campo === campo)?.eleccion
  const nada = totalDeCambios(preview.suyos) === 0

  return (
    <div className="merge">
      <p className="diff-titulo">
        Traer los cambios de <strong>{nombreDeOrigen}</strong> a este diagrama
      </p>

      {nada && preview.conflictos.length === 0 ? (
        <p className="tenue">La rama no tiene cambios nuevos para traer.</p>
      ) : (
        <>
          <details open>
            <summary>Lo que se trae de {nombreDeOrigen} ({totalDeCambios(preview.suyos)})</summary>
            <VistaDiff
              diff={{ desde: { versionId: null, seq: 0 }, hasta: { versionId: null, seq: 0 }, ...preview.suyos }}
              nombres={{}}
            />
          </details>
          <details>
            <summary>Lo que cambió en este diagrama desde el punto en común ({totalDeCambios(preview.nuestros)})</summary>
            <VistaDiff
              diff={{ desde: { versionId: null, seq: 0 }, hasta: { versionId: null, seq: 0 }, ...preview.nuestros }}
              nombres={{}}
            />
          </details>
        </>
      )}

      {preview.conflictos.length > 0 && (
        <section className="merge-conflictos">
          <h4>
            Conflictos
            <span className="diff-cuenta">{pendientes.length} sin resolver de {preview.conflictos.length}</span>
          </h4>
          <p className="tenue">
            Los dos lados tocaron lo mismo. Elegí con cuál te quedás; lo que no está acá se une solo.
          </p>
          <p>
            <button className="tb-btn" onClick={() => setResoluciones(elegirTodos(preview.conflictos, 'nuestro'))}>
              Quedarme con lo nuestro en todos
            </button>
            <button className="tb-btn" onClick={() => setResoluciones(elegirTodos(preview.conflictos, 'suyo'))}>
              Quedarme con lo suyo en todos
            </button>
          </p>
          <ul>
            {preview.conflictos.map((c) => {
              const e = eleccionDe(c.elementId, c.campo)
              return (
                <li key={c.elementId + '|' + c.campo} className="merge-conflicto">
                  <div className="merge-que">
                    <strong>{TIPO[c.tipo] ?? c.tipo}</strong>
                    {c.nombre && <> «{c.nombre}»{c.clase ? <span className="tenue"> en {c.clase}</span> : null}</>}
                    {' · '}{etiquetaDeCampo(c.campo)}
                    {c.motivo
                      ? <span className="tenue"> ({c.motivo})</span>
                      : <span className="tenue"> (antes: {valor(c.campo, c.base)})</span>}
                  </div>
                  {c.sugerido && (
                    <p className="tenue merge-sugerido">
                      Con «suyo», el elemento nuevo entra como «{c.sugerido}»; con «nuestro», ese cambio no entra.
                    </p>
                  )}
                  <div className="merge-opciones" role="radiogroup" aria-label={'Conflicto en ' + etiquetaDeCampo(c.campo)}>
                    <button role="radio" aria-checked={e === 'nuestro'}
                            className={'merge-op' + (e === 'nuestro' ? ' elegida' : '')}
                            onClick={() => setResoluciones((r) => elegir(r, c, 'nuestro'))}>
                      <span>Nuestro</span><strong>{valor(c.campo, c.nuestro)}</strong>
                    </button>
                    <button role="radio" aria-checked={e === 'suyo'}
                            className={'merge-op' + (e === 'suyo' ? ' elegida' : '')}
                            onClick={() => setResoluciones((r) => elegir(r, c, 'suyo'))}>
                      <span>Suyo</span><strong>{valor(c.campo, c.suyo)}</strong>
                    </button>
                  </div>
                </li>
              )
            })}
          </ul>
        </section>
      )}

      {error && <p className="modal-error">{error}</p>}
      <footer className="merge-pie">
        <button className="tb-btn tb-primario"
                disabled={!puedeAplicar || aplicando || pendientes.length > 0 || (nada && preview.conflictos.length === 0)}
                title={!puedeAplicar ? 'Tu rol no permite modificar el diagrama' : undefined}
                onClick={() => onAplicar(resoluciones)}>
          {aplicando ? 'Aplicando…' : 'Aplicar merge'}
        </button>
        <button className="tb-btn" onClick={onVolver} disabled={aplicando}>Volver</button>
        {pendientes.length > 0 && <span className="tenue">Faltan {pendientes.length} por resolver</span>}
      </footer>
    </div>
  )
}
