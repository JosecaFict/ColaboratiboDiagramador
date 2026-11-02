import type { ResultadoDeLote } from './esperaDeLotes'
import type { EntradaHistorialIA } from './tipos'

/**
 * Aplicar una propuesta son DOS pasos con dueños distintos (T-04, corregido en T-14):
 *
 *   1. mandar el BATCH por el bus y esperar a que lo acepte;
 *   2. pedir al servidor la marca de "aplicada", con el opId de ese BATCH.
 *
 * El paso 1 cambia el diagrama; el 2 solo cambia el historial. Si el 1 ya se
 * aceptó y el 2 falla (500, red), NO se vuelve al paso 1: cada llamada a
 * `enviarLoteIA` crea un opId nuevo, así que mandar otro lote aplicaría los
 * mismos cambios dos veces. Lo que se reintenta es solo la marca, con el opId
 * ya confirmado.
 */

export type ResultadoDeAplicar =
  /** El bus rechazó el lote: no se aplicó nada, y se puede corregir y volver a intentar. */
  | { tipo: 'rechazado'; mensaje: string }
  /** El bus no respondió a tiempo: no se marca; hay que revisar el diagrama antes de reintentar. */
  | { tipo: 'sin-confirmar' }
  /** Lote aceptado y marca puesta. */
  | { tipo: 'marcada'; opId: string; entrada: EntradaHistorialIA }
  /** Lote aceptado, pero la marca falló: se reintenta solo la marca con este opId. */
  | { tipo: 'aplicada-sin-marca'; opId: string; mensaje: string }
  /** Lote aceptado de una propuesta que no tiene turno en el historial: no hay marca que pedir. */
  | { tipo: 'aplicada-sin-turno'; opId: string }

export interface DatosDeAplicar {
  /** El turno del historial que aplica el lote. Sin él no hay marca. */
  interaccionId: string | undefined
  /** El opId de un lote YA aceptado cuya marca quedó pendiente; si hay, no se manda otro lote. */
  confirmada: string | undefined
  enviarLote: () => Promise<ResultadoDeLote>
  /** PATCH de "aplicada" con el opId; lanza si falla. */
  marcar: (opId: string) => Promise<EntradaHistorialIA>
}

export async function aplicarPropuesta(d: DatosDeAplicar): Promise<ResultadoDeAplicar> {
  let opId = d.confirmada
  if (opId === undefined) {
    const lote = await d.enviarLote()
    if (lote.estado === 'rechazado') return { tipo: 'rechazado', mensaje: lote.mensaje }
    if (lote.estado === 'sin-confirmar') return { tipo: 'sin-confirmar' }
    opId = lote.opId
  }
  if (!d.interaccionId) return { tipo: 'aplicada-sin-turno', opId }
  try {
    return { tipo: 'marcada', opId, entrada: await d.marcar(opId) }
  } catch (e) {
    return { tipo: 'aplicada-sin-marca', opId, mensaje: e instanceof Error ? e.message : 'sin respuesta' }
  }
}

/** PATCH /api/v1/diagrams/{id}/ai/historial/{interaccion}/aplicada con {opId}. */
export async function pedirMarcaDeAplicada(
  diagramId: string, token: string, interaccionId: string, opId: string, f: typeof fetch = fetch,
): Promise<EntradaHistorialIA> {
  const r = await f(`/api/v1/diagrams/${diagramId}/ai/historial/${interaccionId}/aplicada`, {
    method: 'PATCH',
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({ opId }),
  })
  if (!r.ok) throw new Error(`HTTP ${r.status}`)
  return await r.json() as EntradaHistorialIA
}
