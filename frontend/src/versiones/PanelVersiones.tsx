import { useCallback, useEffect, useMemo, useState } from 'react'
import { ErrorDeApi } from '../auth/cliente'
import { Confirmar } from '../proyectos/Confirmar'
import { ACTUAL, type ApiVersiones } from './api'
import { VistaDiff } from './VistaDiff'
import { VistaMerge } from './VistaMerge'
import { resaltadosParaLienzo, type EstadoDeCambio } from './diff'
import type { DiffVersiones, MergePreview, RamasDelDiagrama, Resolucion, Version } from './tipos'

interface Props {
  diagramaId: string
  api: ApiVersiones
  /** Commit, restaurar, crear rama y mergear: solo OWNER y EDITOR (permisos.editarModelo). */
  puedeEscribir: boolean
  /** Sube cada vez que el servidor anuncia un commit en /topic/diagram/{id}/versions. */
  revision: number
  onResaltar: (r: Record<string, EstadoDeCambio> | null) => void
  onAbrirRama: (diagramaId: string) => void
  onCerrar: () => void
}

type Pestana = 'historial' | 'ramas'
type Pantalla =
  | { tipo: 'lista' }
  | { tipo: 'diff'; diff: DiffVersiones }
  | { tipo: 'merge'; origen: { diagramId: string; name: string }; preview: MergePreview }

/**
 * Un 404 de Spring no trae `mensaje`: con un backend sin el frente de versiones
 * el usuario leeria "No se encontro lo que buscabas", que no explica nada.
 */
export const mensajeDe = (e: unknown): string => {
  if (e instanceof ErrorDeApi && e.estado === 404) {
    return 'El servidor no tiene disponible esta función de versiones (404). Puede que esta versión del backend todavía no la incluya.'
  }
  return e instanceof Error ? e.message : 'No se pudo completar la operación.'
}

/** El diagrama cambio entre el calculo y la aplicacion: el servidor no aplico nada y pide volver a intentar. */
export const esReintentable = (e: unknown): boolean =>
  e instanceof ErrorDeApi && e.estado === 409 && /intentar/i.test(e.message)

export const fechaCorta = (iso: string): string => {
  const f = new Date(iso)
  return Number.isNaN(f.getTime()) ? iso : f.toLocaleString('es', { dateStyle: 'short', timeStyle: 'short' })
}

/**
 * El panel de versiones (ADR-037): historial con commit y restaurar, diff entre
 * dos versiones o version ↔ actual, ramas y merge. Todo lo que escribe
 * se oculta o se deshabilita para el Observador; el servidor lo rechaza igual.
 */
export function PanelVersiones({ diagramaId, api, puedeEscribir, revision, onResaltar, onAbrirRama, onCerrar }: Props) {
  const [pestana, setPestana] = useState<Pestana>('historial')
  const [pantalla, setPantalla] = useState<Pantalla>({ tipo: 'lista' })
  const [versiones, setVersiones] = useState<Version[] | null>(null)
  const [ramas, setRamas] = useState<RamasDelDiagrama | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [ocupado, setOcupado] = useState(false)

  const [nombre, setNombre] = useState('')
  const [mensaje, setMensaje] = useState('')
  const [elegidas, setElegidas] = useState<string[]>([])
  const [restaurando, setRestaurando] = useState<Version | null>(null)
  const [ramificando, setRamificando] = useState<Version | null>(null)
  const [nombreRama, setNombreRama] = useState('')

  const nombres = useMemo(() => Object.fromEntries((versiones ?? []).map((v) => [v.id, v.name])), [versiones])

  const cargar = useCallback(async () => {
    try {
      const [v, r] = await Promise.all([api.listar(diagramaId), api.ramas(diagramaId)])
      setVersiones(v); setRamas(r); setError(null)
    } catch (e) {
      setError(mensajeDe(e))
    }
  }, [api, diagramaId])

  // `revision` cambia cuando alguien hace commit: el historial se actualiza sin recargar.
  useEffect(() => { void cargar() }, [cargar, revision])

  /** La ultima accion que fallo porque el diagrama avanzo mientras se preparaba (409): se puede repetir tal cual. */
  const [reintento, setReintento] = useState<(() => Promise<void>) | null>(null)
  const correr = async (accion: () => Promise<void>) => {
    setOcupado(true); setError(null); setReintento(null)
    try {
      await accion()
    } catch (e) {
      setError(mensajeDe(e))
      if (esReintentable(e)) setReintento(() => () => correr(accion))
    } finally {
      setOcupado(false)
    }
  }

  const hacerCommit = () => correr(async () => {
    await api.commit(diagramaId, { name: nombre.trim(), message: mensaje.trim() })
    setNombre(''); setMensaje('')
    await cargar()
  })

  const compararConActual = (v: Version) => correr(async () => {
    setPantalla({ tipo: 'diff', diff: await api.diff(diagramaId, v.id, ACTUAL) })
  })

  const compararDos = () => correr(async () => {
    // La mas vieja primero: el diff se lee "de lo anterior a lo posterior".
    const [a, b] = elegidas.map((id) => versiones!.find((v) => v.id === id)!).sort((x, y) => x.seq - y.seq || x.createdAt.localeCompare(y.createdAt))
    setPantalla({ tipo: 'diff', diff: await api.diff(diagramaId, a.id, b.id) })
  })

  const alternar = (id: string) =>
    setElegidas((e) => e.includes(id) ? e.filter((x) => x !== id) : [...e.slice(-1), id])

  const crearRama = () => correr(async () => {
    const { diagramId } = await api.crearRama(diagramaId, { name: nombreRama.trim(), fromVersionId: ramificando!.id })
    setRamificando(null); setNombreRama('')
    await cargar()
    onAbrirRama(diagramId)
  })

  const verMerge = (origen: { diagramId: string; name: string }) => correr(async () => {
    setPantalla({ tipo: 'merge', origen, preview: await api.previewMerge(diagramaId, origen.diagramId) })
  })

  const aplicarMerge = (origen: { diagramId: string; name: string }, resoluciones: Resolucion[]) => correr(async () => {
    await api.merge(diagramaId, { fromDiagramId: origen.diagramId, resoluciones })
    setPantalla({ tipo: 'lista' })
    await cargar()
  })

  const resaltar = (diff: DiffVersiones) => { onResaltar(resaltadosParaLienzo(diff)); onCerrar() }

  const otrasRamas = ramas?.ramas ?? []
  const origenDeEsta = ramas?.origen ?? null
  const esteEsRama = origenDeEsta ? ramas?.actual : undefined

  return (
    <div className="modal-fondo" onClick={onCerrar}>
      <div className="modal modal-ancho panel-versiones" role="dialog" aria-label="Versiones" onClick={(e) => e.stopPropagation()}>
        <header>
          <strong>Versiones</strong>
          {esteEsRama && <span className="tenue">rama «{esteEsRama.name}»</span>}
          <button className="modal-cerrar" onClick={onCerrar} title="Cerrar">×</button>
        </header>

        {error && (
          <p className="modal-error" role="alert">
            {error}
            {reintento && <button className="tb-btn" disabled={ocupado} onClick={() => { void reintento() }}>Reintentar</button>}
          </p>
        )}

        {pantalla.tipo === 'diff' && (
          <>
            <VistaDiff diff={pantalla.diff} nombres={nombres} onResaltar={() => resaltar(pantalla.diff)} />
            <footer><button className="tb-btn" onClick={() => setPantalla({ tipo: 'lista' })}>Volver al historial</button></footer>
          </>
        )}

        {pantalla.tipo === 'merge' && (
          <VistaMerge
            preview={pantalla.preview}
            nombreDeOrigen={pantalla.origen.name}
            puedeAplicar={puedeEscribir}
            aplicando={ocupado}
            error={null}
            onAplicar={(r) => aplicarMerge(pantalla.origen, r)}
            onVolver={() => setPantalla({ tipo: 'lista' })}
          />
        )}

        {pantalla.tipo === 'lista' && (
          <>
            <nav className="pv-pestanas">
              <button className={pestana === 'historial' ? 'activa' : ''} onClick={() => setPestana('historial')}>
                Historial {versiones ? `(${versiones.length})` : ''}
              </button>
              <button className={pestana === 'ramas' ? 'activa' : ''} onClick={() => setPestana('ramas')}>
                Ramas {ramas ? `(${otrasRamas.length})` : ''}
              </button>
            </nav>

            {pestana === 'historial' && (
              <>
                {puedeEscribir ? (
                  <form className="pv-commit" onSubmit={(e) => { e.preventDefault(); if (nombre.trim()) void hacerCommit() }}>
                    <input value={nombre} onChange={(e) => setNombre(e.target.value)} placeholder="Nombre de la versión (ej. v1-entrega)"
                           maxLength={80} aria-label="Nombre de la versión" />
                    <input value={mensaje} onChange={(e) => setMensaje(e.target.value)} placeholder="Mensaje: qué cambia"
                           aria-label="Mensaje" />
                    <button className="tb-btn tb-primario" type="submit" disabled={ocupado || !nombre.trim()}>Guardar versión</button>
                  </form>
                ) : (
                  <p className="tenue">Como Observador podés ver el historial y comparar, pero no guardar ni restaurar versiones.</p>
                )}

                {elegidas.length === 2 && (
                  <p><button className="tb-btn" disabled={ocupado} onClick={compararDos}>Comparar las 2 marcadas</button></p>
                )}

                {versiones === null && !error && <p className="tenue">Cargando…</p>}
                {versiones?.length === 0 && <p className="tenue">Todavía no hay versiones guardadas.</p>}
                <ul className="pv-lista">
                  {versiones?.map((v) => (
                    <li key={v.id}>
                      <input type="checkbox" checked={elegidas.includes(v.id)} onChange={() => alternar(v.id)}
                             aria-label={'Marcar ' + v.name + ' para comparar'} />
                      <div className="pv-info">
                        <strong>{v.name}</strong> <span className="tenue">#{v.seq}</span>
                        {v.message && <div className="pv-mensaje">{v.message}</div>}
                        <div className="tenue pv-meta">{v.authorName ?? v.authorId ?? 'desconocido'} · {fechaCorta(v.createdAt)}</div>
                      </div>
                      <div className="pv-acciones">
                        <button className="tb-btn" disabled={ocupado} onClick={() => compararConActual(v)}>Comparar con actual</button>
                        {puedeEscribir && <button className="tb-btn" disabled={ocupado} onClick={() => setRamificando(v)}>Crear rama</button>}
                        {puedeEscribir && <button className="tb-btn" disabled={ocupado} onClick={() => setRestaurando(v)}>Restaurar</button>}
                      </div>
                    </li>
                  ))}
                </ul>
              </>
            )}

            {pestana === 'ramas' && (
              <>
                {esteEsRama && origenDeEsta && (
                  <div className="pv-origen">
                    <p className="tenue">
                      Este diagrama es la rama «{esteEsRama.name}», nacida de «{origenDeEsta.name}»
                      {origenDeEsta.forkedFromVersionName ? ` (versión ${origenDeEsta.forkedFromVersionName})` : ''}.
                    </p>
                    <p>
                      <button className="tb-btn" onClick={() => onAbrirRama(origenDeEsta.diagramId)}>Abrir «{origenDeEsta.name}»</button>
                      <button className="tb-btn" disabled={ocupado}
                              onClick={() => verMerge({ diagramId: origenDeEsta.diagramId, name: origenDeEsta.name })}
                              title="Traer a esta rama lo que cambió en el diagrama de origen">
                        Traer cambios de «{origenDeEsta.name}»…
                      </button>
                    </p>
                  </div>
                )}
                {ramas === null && !error && <p className="tenue">Cargando…</p>}
                {ramas && otrasRamas.length === 0 && (
                  <p className="tenue">No hay ramas. Creá una desde una versión del historial.</p>
                )}
                <ul className="pv-lista">
                  {otrasRamas.map((r) => (
                    <li key={r.diagramId}>
                      <div className="pv-info">
                        <strong>{r.name}</strong>
                        <div className="tenue pv-meta">
                          {r.forkedFromDiagramId === diagramaId
                            ? `nació de la versión ${r.forkedFromVersionId ? (nombres[r.forkedFromVersionId] ?? '—') : '—'}`
                            : 'diagrama de origen'}
                        </div>
                      </div>
                      <div className="pv-acciones">
                        <button className="tb-btn" onClick={() => onAbrirRama(r.diagramId)}>Abrir</button>
                        <button className="tb-btn" disabled={ocupado} onClick={() => verMerge(r)}
                                title="Ver qué se trae de esa rama a este diagrama antes de aplicar">
                          Merge a este diagrama…
                        </button>
                      </div>
                    </li>
                  ))}
                </ul>
              </>
            )}
          </>
        )}

        {ramificando && (
          <div className="modal-fondo" onClick={() => setRamificando(null)}>
            <form className="modal modal-confirmar" onClick={(e) => e.stopPropagation()}
                  onSubmit={(e) => { e.preventDefault(); if (nombreRama.trim()) void crearRama() }}>
              <header><strong>Crear rama desde «{ramificando.name}»</strong></header>
              <p className="modal-resumen">
                La rama es un diagrama nuevo de este proyecto con el modelo de esa versión. Los cambios que hagas
                ahí no tocan este diagrama hasta que los traigas con un merge.
              </p>
              <input autoFocus value={nombreRama} onChange={(e) => setNombreRama(e.target.value)} placeholder="Nombre de la rama"
                     aria-label="Nombre de la rama" />
              <footer>
                <button type="submit" className="tb-btn tb-primario" disabled={ocupado || !nombreRama.trim()}>Crear y abrir</button>
                <button type="button" className="tb-btn" onClick={() => setRamificando(null)}>Cancelar</button>
              </footer>
            </form>
          </div>
        )}

        {restaurando && (
          <Confirmar
            titulo={`Restaurar «${restaurando.name}»`}
            mensaje={
              <>
                El diagrama vuelve al modelo de la versión <strong>{restaurando.name}</strong> (#{restaurando.seq}). Lo que
                cambió después se pierde del modelo actual, aunque queda en el historial de operaciones. Se ve en
                vivo para todos los que lo tengan abierto. Si alguien está editando un elemento afectado, no se restaura.
                Guardá una versión antes si querés poder volver.
              </>
            }
            etiqueta="Restaurar"
            peligro
            onConfirmar={async () => {
              await api.restaurar(diagramaId, restaurando.id)
              setRestaurando(null)
              onResaltar(null)
              await cargar()
            }}
            onCancelar={() => setRestaurando(null)}
          />
        )}
      </div>
    </div>
  )
}
