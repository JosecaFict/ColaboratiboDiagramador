import type { RecomendacionIA, RecomendacionesIA } from './tipos'

const PESO = { ALTA: 0, MEDIA: 1, BAJA: 2 } as const

/** Primero lo verificado por codigo y, dentro de cada origen, lo mas grave. */
export function ordenarRecomendaciones(lista: RecomendacionIA[]): RecomendacionIA[] {
  return [...lista].sort((a, b) =>
    (a.origen === b.origen ? 0 : a.origen === 'VERIFICADO' ? -1 : 1)
    || (PESO[a.gravedad] ?? 3) - (PESO[b.gravedad] ?? 3)
    || a.id - b.id)
}

/**
 * Se marcan de entrada solo los hallazgos verificados de gravedad alta o media:
 * una sugerencia del modelo puede ser incorrecta y el usuario decide si la quiere.
 */
export function seleccionInicial(lista: RecomendacionIA[]): Set<number> {
  return new Set(lista.filter((r) => r.origen === 'VERIFICADO' && r.gravedad !== 'BAJA').map((r) => r.id))
}

export function elegidas(lista: RecomendacionIA[], seleccion: Set<number>): RecomendacionIA[] {
  return lista.filter((r) => seleccion.has(r.id))
}

export const etiquetaOrigen = (r: RecomendacionIA) =>
  r.origen === 'VERIFICADO' ? 'Verificado' : 'Sugerencia del modelo'

export function textoDeRecomendaciones(r: RecomendacionesIA): string {
  const total = r.recomendaciones.length
  const verificadas = r.recomendaciones.filter((x) => x.origen === 'VERIFICADO').length
  const cabecera = r.resumen?.trim() || (total ? 'Revisé el diagrama.' : 'Revisé el diagrama y no encontré problemas.')
  if (!total) return cabecera
  return `${cabecera}\n\n${total} ${total === 1 ? 'recomendación' : 'recomendaciones'}: ${verificadas} verificada${verificadas === 1 ? '' : 's'} por el backend y ${total - verificadas} sugerida${total - verificadas === 1 ? '' : 's'} por el modelo.`
}
