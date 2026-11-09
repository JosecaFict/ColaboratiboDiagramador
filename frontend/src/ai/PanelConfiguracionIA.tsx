import { useEffect, useRef, useState } from 'react'
import type { Rol } from '../auth/permisos'
import {
  CONFIGURACION_POR_DEFECTO, DETALLES_IA, MAX_INSTRUCCIONES, ROLES_IA,
  errorDeBorrador, guardarConfiguracion, hayCambios, leerConfiguracion, puedeEditarConfiguracion,
  resolverGuardado, type AvisoDePanel, type ConfiguracionIA, type DetalleIA, type RolIA,
} from './configuracion'

/** Lo que dibuja el panel. Sin hooks: se prueba con HTML estático. */
export interface VistaDelPanel {
  /** null mientras no se leyó (o si la lectura falló). */
  guardada: ConfiguracionIA | null
  borrador: ConfiguracionIA
  cargando: boolean
  errorDeCarga: string | null
  guardando: boolean
  aviso: AvisoDePanel | null
  puedeEditar: boolean
}

interface Acciones {
  onCambiar: (parcial: Partial<ConfiguracionIA>) => void
  onGuardar: () => void
  onDescartar: () => void
  onReintentar: () => void
}

const nombreDeRol = (id: string) => ROLES_IA.find((r) => r.id === id)?.nombre ?? id
const nombreDeDetalle = (id: string) => DETALLES_IA.find((d) => d.id === id)?.nombre ?? id

export function resumenDeConfiguracion(c: ConfiguracionIA | null): string {
  return c ? `${nombreDeRol(c.rol)} · ${nombreDeDetalle(c.detalle)}` : 'Sin leer'
}

export function VistaPanelConfiguracion({ v, a }: { v: VistaDelPanel; a: Acciones }) {
  const { borrador, guardada } = v
  const cambios = guardada !== null && hayCambios(guardada, borrador)
  const largo = borrador.instrucciones.trim().length
  const errorLocal = errorDeBorrador(borrador)
  const bloqueado = !v.puedeEditar || v.guardando || guardada === null
  return (
    <div className="ia-config" aria-label="Configuración de la IA del proyecto">
      {v.cargando && <small className="ia-config-estado">Leyendo la configuración…</small>}
      {v.errorDeCarga && <div className="ia-config-error" role="alert">
        <span>{v.errorDeCarga}</span>
        <button type="button" onClick={a.onReintentar}>Reintentar</button>
      </div>}
      {!v.puedeEditar && !v.errorDeCarga && !v.cargando &&
        <small className="ia-config-estado">Solo el propietario puede cambiarla. La ves como la usa el equipo.</small>}

      <label>Rol de la IA
        <select value={borrador.rol} disabled={bloqueado}
          onChange={(e) => a.onCambiar({ rol: e.target.value as RolIA })}>
          {ROLES_IA.map((r) => <option key={r.id} value={r.id}>{r.nombre} · {r.descripcion}</option>)}
        </select>
      </label>
      <label>Nivel de detalle
        <select value={borrador.detalle} disabled={bloqueado}
          onChange={(e) => a.onCambiar({ detalle: e.target.value as DetalleIA })}>
          {DETALLES_IA.map((d) => <option key={d.id} value={d.id}>{d.nombre}</option>)}
        </select>
      </label>
      <label>Instrucciones del equipo
        <textarea rows={4} value={borrador.instrucciones} disabled={bloqueado}
          placeholder={v.puedeEditar ? 'Por ejemplo: usá nombres en español y claves UUID.' : 'Sin instrucciones.'}
          onChange={(e) => a.onCambiar({ instrucciones: e.target.value })} />
        <small className={errorLocal ? 'ia-config-contador excedido' : 'ia-config-contador'}>
          {largo} / {MAX_INSTRUCCIONES}
        </small>
      </label>
      <small className="ia-config-nota">Son preferencias de estilo: no cambian lo que la IA puede hacer ni sus reglas.</small>

      {v.aviso && <div className={v.aviso.tipo === 'ok' ? 'ia-config-aviso ok' : 'ia-config-aviso mal'}
        role={v.aviso.tipo === 'ok' ? 'status' : 'alert'}>{v.aviso.texto}</div>}

      {v.puedeEditar && <div className="ia-config-acciones">
        <button type="button" onClick={a.onGuardar}
          disabled={bloqueado || !cambios || errorLocal !== null}>{v.guardando ? 'Guardando…' : 'Guardar'}</button>
        <button type="button" className="secundario" onClick={a.onDescartar}
          disabled={bloqueado || !cambios}>Descartar</button>
      </div>}
    </div>
  )
}

interface Props {
  proyectoId: string
  token: string
  rol: Rol
  /** Avisa el resumen vigente (para el encabezado plegado). */
  onResumen?: (resumen: string) => void
}

/**
 * Lee la configuración al abrirse y deja editarla solo al OWNER. Un 403, un 400 o
 * un corte al guardar no cuentan como guardado y no tocan el borrador.
 */
export function PanelConfiguracionIA({ proyectoId, token, rol, onResumen }: Props) {
  const [guardada, setGuardada] = useState<ConfiguracionIA | null>(null)
  const [borrador, setBorrador] = useState<ConfiguracionIA>(CONFIGURACION_POR_DEFECTO)
  const [cargando, setCargando] = useState(true)
  const [errorDeCarga, setErrorDeCarga] = useState<string | null>(null)
  const [guardando, setGuardando] = useState(false)
  const [aviso, setAviso] = useState<AvisoDePanel | null>(null)
  const [lecturas, setLecturas] = useState(0)
  const montado = useRef(true)
  const borradorRef = useRef(borrador)
  borradorRef.current = borrador
  const guardadaRef = useRef<ConfiguracionIA | null>(null)

  useEffect(() => { montado.current = true; return () => { montado.current = false } }, [])

  useEffect(() => {
    let vivo = true
    setCargando(true)
    setErrorDeCarga(null)
    leerConfiguracion(proyectoId, token).then((r) => {
      if (!vivo) return
      setCargando(false)
      if (r.estado === 'ok') {
        const previa = guardadaRef.current
        guardadaRef.current = r.configuracion
        setGuardada(r.configuracion)
        // Una relectura no pisa lo que se está escribiendo.
        setBorrador((actual) => (previa === null || !hayCambios(previa, actual) ? r.configuracion : actual))
        onResumen?.(resumenDeConfiguracion(r.configuracion))
      } else {
        setErrorDeCarga(r.mensaje)
      }
    })
    return () => { vivo = false }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [proyectoId, token, lecturas])

  const guardar = async () => {
    if (!guardada || guardando || !puedeEditarConfiguracion(rol)) return
    const enviado = borradorRef.current
    if (errorDeBorrador(enviado)) return
    setGuardando(true)
    setAviso(null)
    const resultado = await guardarConfiguracion(proyectoId, token, enviado)
    if (!montado.current) return
    const siguiente = resolverGuardado({ guardada, borrador: borradorRef.current, aviso: null }, enviado, resultado)
    guardadaRef.current = siguiente.guardada
    setGuardada(siguiente.guardada)
    setBorrador(siguiente.borrador)
    setAviso(siguiente.aviso)
    if (resultado.estado === 'ok') onResumen?.(resumenDeConfiguracion(resultado.configuracion))
    setGuardando(false)
  }

  return (
    <VistaPanelConfiguracion
      v={{ guardada, borrador, cargando, errorDeCarga, guardando, aviso, puedeEditar: puedeEditarConfiguracion(rol) }}
      a={{
        onCambiar: (parcial) => { setAviso(null); setBorrador((b) => ({ ...b, ...parcial })) },
        onGuardar: () => void guardar(),
        onDescartar: () => { if (guardada) { setBorrador(guardada); setAviso(null) } },
        onReintentar: () => setLecturas((n) => n + 1),
      }}
    />
  )
}
