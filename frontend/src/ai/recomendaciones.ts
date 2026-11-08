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

const PREFIJO_SIN_RESOLVER = 'Sin resolver por la IA, revisalo antes de aplicar: '

/**
 * Lo que Diseñar dejo pendiente, como tarjetas para elegir y corregir. Son hallazgos
 * del validador del backend, por eso se marcan como verificados. Si no hay ninguno
 * "sin resolver", se usan los avisos de la auditoria automatica.
 */
export function pendientesComoRecomendaciones(advertencias: string[]): RecomendacionesIA {
  const sinResolver = advertencias.filter((a) => a.startsWith(PREFIJO_SIN_RESOLVER))
    .map((a) => a.slice(PREFIJO_SIN_RESOLVER.length).trim())
  const textos = sinResolver.length ? sinResolver : advertencias
  const recomendaciones: RecomendacionIA[] = [...new Set(textos)].map((texto, i) => ({
    id: i + 1, titulo: texto, gravedad: sinResolver.length ? 'ALTA' : 'MEDIA', categoria: 'ESTRUCTURA',
    regla: '', elementos: [], problema: '', accion: `Corrige en el diagrama: ${texto}`, origen: 'VERIFICADO',
  }))
  return {
    diagramId: '', modelo: null, verificaciones: [], hallazgosEstructurales: textos, recomendaciones,
    resumen: `Diseñar dejó ${recomendaciones.length} punto${recomendaciones.length === 1 ? '' : 's'} pendiente${recomendaciones.length === 1 ? '' : 's'}. `
      + 'Si alguno tiene más de una solución, escribí en su acción cuál querés (por ejemplo "conecta Itinerario con Paquete").',
  }
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
