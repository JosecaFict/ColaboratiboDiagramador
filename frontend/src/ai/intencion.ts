import type { ModoIA } from './tipos'

/** CORREGIR no es un modo del backend: el chat lo resuelve con los pendientes del ultimo diseño. */
export type Intencion = ModoIA | 'CORREGIR'

const normal = (s: string) => s.normalize('NFD').replace(/\p{M}/gu, '').toLowerCase().trim()

/**
 * Lo que el usuario quiere, a partir de como lo escribe. Es solo una sugerencia: el chat
 * la muestra antes de enviar y el usuario puede cambiarla. Ante la duda, CONSULTAR,
 * que nunca propone cambios.
 */
export function detectarIntencion(texto: string): Intencion {
  const t = normal(texto)
  if (!t) return 'CONSULTAR'
  if (/(corrig|arregl|repar|soluciona)\w*\b.*\b(error|pendiente|problema|falla|aviso)/.test(t)
      || /^(corrige|corregi|arregla|arregla?lo|repara)\b/.test(t)) return 'CORREGIR'
  if (/^(disena|diseña|modela|crea (el|un) (modelo|diagrama)|genera (el|un) (modelo|diagrama))/.test(t)
      || /\b(disena|diseña)(me)? (un|el|una)\b/.test(t)) return 'DISENAR'
  if (/^(revisa|recomienda|verifica|valida|controla|audita)\b/.test(t)
      || /\b(que (le )?falta|esta (bien|correcto|completo)|hay (errores|problemas))\b/.test(t)) return 'RECOMENDAR'
  if (/^(agrega|anade|añade|quita|elimina|borra|cambia|renombra|conecta|une|mueve|reemplaza|pon|crea (la|una) (clase|relacion))\b/.test(t))
    return 'MODIFICAR'
  return 'CONSULTAR'
}

export const NOMBRE_INTENCION: Record<Intencion, string> = {
  CONSULTAR: 'Preguntar', REVISAR: 'Revisar', DISENAR: 'Diseñar', MODIFICAR: 'Modificar',
  RECOMENDAR: 'Revisar y recomendar', CORREGIR: 'Corregir pendientes',
}
