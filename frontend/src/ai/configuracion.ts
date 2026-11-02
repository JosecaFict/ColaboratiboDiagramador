import type { Rol } from '../auth/permisos'

/**
 * Configuración de la IA del proyecto (T-09, "Contrato de configuración").
 *
 *   GET y PUT /api/v1/projects/{projectId}/ia/configuracion  →  200 con
 *   { rol: MENTOR|SENIOR|REVISOR, detalle: BREVE|NORMAL|DETALLADO, instrucciones }
 *
 * GET: cualquier miembro. PUT: solo el OWNER. Sin fila guardada, GET devuelve los
 * valores por defecto. PUT inválido → 400 {mensaje}; no miembro o sin permiso → 403.
 *
 * La interfaz solo refleja el permiso; decide el servidor. Un 403 al guardar no
 * cuenta como guardado y no pierde el borrador.
 */

export type RolIA = 'MENTOR' | 'SENIOR' | 'REVISOR'
export type DetalleIA = 'BREVE' | 'NORMAL' | 'DETALLADO'

export interface ConfiguracionIA {
  rol: RolIA
  detalle: DetalleIA
  instrucciones: string
}

export const MAX_INSTRUCCIONES = 1000

export const CONFIGURACION_POR_DEFECTO: ConfiguracionIA = { rol: 'MENTOR', detalle: 'NORMAL', instrucciones: '' }

export const ROLES_IA: { id: RolIA; nombre: string; descripcion: string }[] = [
  { id: 'MENTOR', nombre: 'Mentor', descripcion: 'Explica y acompaña' },
  { id: 'SENIOR', nombre: 'Senior', descripcion: 'Directo, va al punto' },
  { id: 'REVISOR', nombre: 'Revisor', descripcion: 'Busca fallas y riesgos' },
]

export const DETALLES_IA: { id: DetalleIA; nombre: string }[] = [
  { id: 'BREVE', nombre: 'Breve' },
  { id: 'NORMAL', nombre: 'Normal' },
  { id: 'DETALLADO', nombre: 'Detallado' },
]

export type ResultadoDeConfiguracion =
  | { estado: 'ok'; configuracion: ConfiguracionIA }
  | { estado: 'prohibido'; mensaje: string }
  | { estado: 'invalido'; mensaje: string }
  | { estado: 'error'; mensaje: string }

type Fetch = typeof fetch

const url = (proyectoId: string) => `/api/v1/projects/${proyectoId}/ia/configuracion`

async function cuerpoDe(r: Response): Promise<{ mensaje?: string } | null> {
  try { return await r.json() } catch { return null }
}

function esConfiguracion(x: unknown): x is ConfiguracionIA {
  const c = x as ConfiguracionIA | null
  return !!c && typeof c.rol === 'string' && typeof c.detalle === 'string' && typeof c.instrucciones === 'string'
}

async function resolver(r: Response, accion: string): Promise<ResultadoDeConfiguracion> {
  const cuerpo = await cuerpoDe(r)
  if (r.ok) {
    return esConfiguracion(cuerpo)
      ? { estado: 'ok', configuracion: cuerpo }
      : { estado: 'error', mensaje: `El servidor respondió algo inesperado al ${accion}.` }
  }
  if (r.status === 403) {
    return { estado: 'prohibido', mensaje: cuerpo?.mensaje ?? 'No tenés permiso para esto en este proyecto.' }
  }
  if (r.status === 400) {
    return { estado: 'invalido', mensaje: cuerpo?.mensaje ?? 'La configuración no es válida.' }
  }
  return { estado: 'error', mensaje: cuerpo?.mensaje ?? `No se pudo ${accion} (HTTP ${r.status}).` }
}

export async function leerConfiguracion(
  proyectoId: string, token: string, f: Fetch = fetch,
): Promise<ResultadoDeConfiguracion> {
  try {
    return await resolver(await f(url(proyectoId), { headers: { Authorization: `Bearer ${token}` } }), 'leer la configuración')
  } catch {
    return { estado: 'error', mensaje: 'No se pudo leer la configuración de la IA: sin conexión con el servidor.' }
  }
}

export async function guardarConfiguracion(
  proyectoId: string, token: string, c: ConfiguracionIA, f: Fetch = fetch,
): Promise<ResultadoDeConfiguracion> {
  try {
    const r = await f(url(proyectoId), {
      method: 'PUT',
      headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({ rol: c.rol, detalle: c.detalle, instrucciones: c.instrucciones.trim() }),
    })
    return await resolver(r, 'guardar la configuración')
  } catch {
    return { estado: 'error', mensaje: 'No se pudo guardar: sin conexión con el servidor. Tu borrador sigue aquí.' }
  }
}

/** Solo el propietario edita; el servidor lo vuelve a comprobar. */
export const puedeEditarConfiguracion = (rol: Rol): boolean => rol === 'OWNER'

/** El servidor guarda las instrucciones sin espacios en los bordes: se compara igual. */
const normalizada = (c: ConfiguracionIA) => ({ ...c, instrucciones: c.instrucciones.trim() })

export function hayCambios(guardada: ConfiguracionIA, borrador: ConfiguracionIA): boolean {
  const a = normalizada(guardada)
  const b = normalizada(borrador)
  return a.rol !== b.rol || a.detalle !== b.detalle || a.instrucciones !== b.instrucciones
}

/** Lo que el servidor rechazaría con 400, avisado antes de mandar. */
export function errorDeBorrador(b: ConfiguracionIA): string | null {
  if (b.instrucciones.trim().length > MAX_INSTRUCCIONES) {
    return `Las instrucciones pasan de ${MAX_INSTRUCCIONES} caracteres.`
  }
  return null
}

export interface AvisoDePanel { tipo: 'ok' | 'mal'; texto: string }

export interface EstadoDeGuardado {
  guardada: ConfiguracionIA
  borrador: ConfiguracionIA
  aviso: AvisoDePanel | null
}

/**
 * Qué queda en pantalla cuando vuelve el PUT. Solo `ok` cambia lo guardado; todo
 * lo demás deja el borrador como estaba y lo dice. `enviado` es lo que se mandó:
 * si mientras tanto se siguió escribiendo, esos cambios nuevos no se pisan.
 */
export function resolverGuardado(
  actual: EstadoDeGuardado, enviado: ConfiguracionIA, resultado: ResultadoDeConfiguracion,
): EstadoDeGuardado {
  switch (resultado.estado) {
    case 'ok': {
      const siguioEscribiendo = hayCambios(enviado, actual.borrador)
      return {
        guardada: resultado.configuracion,
        borrador: siguioEscribiendo ? actual.borrador : resultado.configuracion,
        aviso: { tipo: 'ok', texto: 'Configuración guardada. Vale para todo el equipo desde el próximo pedido.' },
      }
    }
    case 'prohibido':
      return { ...actual, aviso: { tipo: 'mal', texto: `No se guardó: ${resultado.mensaje} Tu borrador sigue aquí.` } }
    case 'invalido':
      return { ...actual, aviso: { tipo: 'mal', texto: `No se guardó: ${resultado.mensaje} Tu borrador sigue aquí.` } }
    case 'error':
      return { ...actual, aviso: { tipo: 'mal', texto: `No se guardó: ${resultado.mensaje}` } }
  }
}
