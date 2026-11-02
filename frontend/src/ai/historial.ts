import type { EntradaHistorialIA, PropuestaIA } from './tipos'

/** Lo que el servidor difunde por /topic/diagram/{id}/ai (AvisosDeIA). */
export interface AvisoIA {
  tipo: 'NUEVA' | 'APLICADA'
  entrada: EntradaHistorialIA
}

/**
 * Agrega o reemplaza una entrada por id, sin cambiar el orden de las que ya
 * estaban. La misma entrada puede llegar por la respuesta HTTP y por el aviso,
 * en cualquier orden. "aplicada" solo pasa de false a true: un aviso NUEVA
 * atrasado no desmarca lo que ya se aplicó.
 */
export function fusionarEntrada(entradas: EntradaHistorialIA[], nueva: EntradaHistorialIA): EntradaHistorialIA[] {
  const i = entradas.findIndex((e) => e.id === nueva.id)
  if (i < 0) return [...entradas, nueva]
  const previa = entradas[i]
  const fusionada = { ...nueva, aplicada: previa.aplicada || nueva.aplicada }
  return entradas.map((e, j) => (j === i ? fusionada : e))
}

/**
 * La lista completa del servidor (al cargar o al reconectar) se FUSIONA con lo que
 * ya se ve; no lo reemplaza (T-14). El GET puede terminar después de que llegó por
 * el tópico una entrada o una marca más nuevas que su instantánea: reemplazar las
 * borraría hasta recargar.
 *
 * Por id: lo del servidor manda, salvo `aplicada`, que solo pasa de false a true.
 * Lo que solo se ve localmente (llegó por el tópico) se conserva. El orden es el del
 * servidor: fecha de creación y, a igual fecha, id.
 */
export function reconciliarHistorial(actuales: EntradaHistorialIA[], delServidor: EntradaHistorialIA[]): EntradaHistorialIA[] {
  const aplicadas = new Set(actuales.filter((e) => e.aplicada).map((e) => e.id))
  const delServidorId = new Set(delServidor.map((e) => e.id))
  const fusionadas = delServidor.map((e) => (aplicadas.has(e.id) ? { ...e, aplicada: true } : e))
  const soloLocales = actuales.filter((e) => !delServidorId.has(e.id))
  return ordenarCronologicamente([...fusionadas, ...soloLocales])
}

function ordenarCronologicamente(entradas: EntradaHistorialIA[]): EntradaHistorialIA[] {
  const tiempo = (e: EntradaHistorialIA) => {
    const t = Date.parse(e.creadaEn)
    return Number.isNaN(t) ? 0 : t
  }
  return entradas
    .map((e, i) => ({ e, i }))
    .sort((x, y) => tiempo(x.e) - tiempo(y.e) || (x.e.id < y.e.id ? -1 : x.e.id > y.e.id ? 1 : x.i - y.i))
    .map(({ e }) => e)
}

/** La última entrada con cambios para revisar: es la que se muestra en el panel derecho. */
export function ultimaConCambios(entradas: EntradaHistorialIA[]): EntradaHistorialIA | undefined {
  for (let i = entradas.length - 1; i >= 0; i--) if (entradas[i].propuesta.aceptados.length) return entradas[i]
  return undefined
}

/** El BATCH sale con el origen del pedido: un pedido dictado se aplica como VOICE. */
export function origenDelLote(propuesta: PropuestaIA): 'TEXT' | 'VOICE' {
  return propuesta.origin === 'VOICE' ? 'VOICE' : 'TEXT'
}
