import type { ModoIA } from './tipos'

/**
 * CORREGIR y GENERAR no son modos del backend: el chat los resuelve con los pendientes del
 * ultimo diseño y con la revision previa al generador de Spring Boot.
 */
export type Intencion = ModoIA | 'CORREGIR' | 'GENERAR'

const normal = (s: string) => s.normalize('NFD').replace(/\p{M}/gu, '').toLowerCase().trim()

/**
 * Lo que el usuario quiere, a partir de como lo escribe. Es solo una sugerencia: el chat
 * la muestra antes de enviar y el usuario puede cambiarla. Ante la duda, CONSULTAR,
 * que nunca propone cambios.
 */
export function detectarIntencion(texto: string): Intencion {
  const t = normal(texto)
  if (!t) return 'CONSULTAR'
  // Solo un pedido corto es una orden de corregir: un requisito largo puede decir "corregir errores de facturas".
  if ((t.length < 80 && /\b(corrig|arregl|repar|soluciona)\w*\b.*\b(error|pendiente|problema|falla|aviso)/.test(t))
      || /^(corrige|corregi|arregla|arreglalo|repara)\b/.test(t)) return 'CORREGIR'
  if (t.length < 120 && (/\b(listo|lista|preparado|preparada) para generar\b/.test(t)
      || /\b(puedo|se puede|podemos) generar\b/.test(t)
      || /^genera(r|me)? (el |la |los )?(backend|codigo|spring|proyecto|base de datos|api)\b/.test(t))) return 'GENERAR'
  if (/^(agrega|anade|quita|elimina|borra|cambia|renombra|conecta|une|mueve|reemplaza|pon|crea (la|una) (clase|relacion))\b/.test(t))
    return 'MODIFICAR'
  if (/^(disena|modela|crea (el|un) (modelo|diagrama)|genera (el|un) (modelo|diagrama))/.test(t)
      || /\bdisena(me)? (un|el|una|desde)\b/.test(t)
      // Un requisito completo ("Actua como Arquitecto... diseña desde cero el modelo...") pide diseñar.
      || (!t.startsWith('¿') && /\b(disena|modela|construye)\b/.test(t) && /\b(modelo|diagrama|clases|uml)\b/.test(t))
      || (t.length > 300 && /\b(clases|atributos|relaciones|entidades)\b/.test(t))) return 'DISENAR'
  if (/^(revisa|recomienda|verifica|valida|controla|audita)\b/.test(t)
      || /\b(que (le )?falta|esta (bien|correcto|completo)|hay (errores|problemas))\b/.test(t)) return 'RECOMENDAR'
  return 'CONSULTAR'
}

export const NOMBRE_INTENCION: Record<Intencion, string> = {
  CONSULTAR: 'Preguntar', REVISAR: 'Revisar', DISENAR: 'Diseñar', MODIFICAR: 'Modificar',
  RECOMENDAR: 'Revisar y recomendar', CORREGIR: 'Corregir pendientes', GENERAR: '¿Listo para generar?',
}
