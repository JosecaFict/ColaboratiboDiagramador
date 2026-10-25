/**
 * Navegacion por hash (ADR-026):
 *
 *   #/proyectos                     Mis proyectos
 *   #/p/{projectId}                 un proyecto: sus diagramas y miembros
 *   #/p/{projectId}/d/{diagramId}   un diagrama abierto
 *
 * Por hash y no por una libreria de rutas: son tres pantallas, y lo unico que
 * hace falta es que recargar (F5) o pegar el enlace no saque a nadie del
 * diagrama. `hashchange` y el boton "atras" del navegador funcionan solos.
 */
export type Ruta =
  | { tipo: 'proyectos' }
  | { tipo: 'proyecto'; proyectoId: string }
  | { tipo: 'diagrama'; proyectoId: string; diagramaId: string }

const UUID = '[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}'
const PROYECTO = new RegExp(`^#/p/(${UUID})/?$`)
const DIAGRAMA = new RegExp(`^#/p/(${UUID})/d/(${UUID})/?$`)

/** Cualquier hash que no sea una ruta conocida cae en "Mis proyectos". */
export function parsearHash(hash: string): Ruta {
  const diagrama = DIAGRAMA.exec(hash)
  if (diagrama) return { tipo: 'diagrama', proyectoId: diagrama[1], diagramaId: diagrama[2] }
  const proyecto = PROYECTO.exec(hash)
  if (proyecto) return { tipo: 'proyecto', proyectoId: proyecto[1] }
  return { tipo: 'proyectos' }
}

export function hashDe(ruta: Ruta): string {
  switch (ruta.tipo) {
    case 'proyectos': return '#/proyectos'
    case 'proyecto': return `#/p/${ruta.proyectoId}`
    case 'diagrama': return `#/p/${ruta.proyectoId}/d/${ruta.diagramaId}`
  }
}

/** El proyecto al que pertenece la ruta, si lo hay. */
export const proyectoDe = (ruta: Ruta): string | null =>
  ruta.tipo === 'proyectos' ? null : ruta.proyectoId
