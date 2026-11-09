import { permisosDe, type Rol } from '../auth/permisos'
export type Tema = 'claro' | 'oscuro'
export type PreferenciaTema = Tema | 'sistema'
export const CLAVE_TEMA = 'colabmodel.tema'
export interface AlmacenVisual { getItem(clave: string): string | null; setItem(clave: string, valor: string): void }
export type AccesoAlmacen = () => AlmacenVisual
export function preferenciaValida(valor: unknown): PreferenciaTema {
  return valor === 'claro' || valor === 'oscuro' ? valor : 'sistema'
}
export function resolverTema(preferencia: PreferenciaTema, oscuroDelSistema: boolean): Tema {
  return preferencia === 'sistema' ? oscuroDelSistema ? 'oscuro' : 'claro' : preferencia
}
export function leerTema(acceso: AccesoAlmacen): PreferenciaTema {
  try { return preferenciaValida(acceso().getItem(CLAVE_TEMA)) } catch { return 'sistema' }
}
export function guardarTema(acceso: AccesoAlmacen, valor: PreferenciaTema) {
  try { acceso().setItem(CLAVE_TEMA, valor) } catch { /* La vista sigue funcionando sin almacenamiento. */ }
}
export const SECCIONES = { propiedades: true, historial: true, versiones: false, panel: true } as const
export type Seccion = keyof typeof SECCIONES
export function leerSeccion(acceso: AccesoAlmacen, id: Seccion): boolean {
  try {
    const valor = acceso().getItem(`colabmodel.seccion.${id}`)
    return valor === 'true' ? true : valor === 'false' ? false : SECCIONES[id]
  } catch { return SECCIONES[id] }
}
export function guardarSeccion(acceso: AccesoAlmacen, id: Seccion, abierta: boolean) {
  try { acceso().setItem(`colabmodel.seccion.${id}`, String(abierta)) } catch { /* Preferencia opcional. */ }
}
export interface AccionMenu { id: string; texto: string; habilitada: boolean; atajo?: string }
export interface GrupoMenu { titulo: string; acciones: AccionMenu[] }
export interface ContextoMenu {
  conectado: boolean; diagrama: boolean; editable: boolean; deshacer: boolean; rehacer: boolean
}
export function menusPara(rol: Rol | null | undefined, c: ContextoMenu): GrupoMenu[] {
  const p = permisosDe(rol)
  // Generar el zip y exportar XMI son lecturas del modelo (ADR-022): también para Observador.
  // Leer el archivo de importación solo propone; aplicarlo escribe por el bus (ADR-016).
  // Por eso Importar/Desde imagen piden edición. Deshacer/rehacer también escriben (ADR-027).
  const leer = p.leer && c.conectado && c.diagrama
  const escribir = p.editarModelo && leer && c.editable
  return [
    { titulo: 'Archivo', acciones: [
      { id: 'importar', texto: 'Importar…', habilitada: escribir },
      { id: 'exportar', texto: 'Exportar XMI…', habilitada: leer },
      { id: 'imagen', texto: 'Desde imagen…', habilitada: escribir },
    ] },
    { titulo: 'Edición', acciones: [
      { id: 'deshacer', texto: 'Deshacer', atajo: 'Ctrl+Z', habilitada: escribir && c.deshacer },
      { id: 'rehacer', texto: 'Rehacer', atajo: 'Ctrl+Y', habilitada: escribir && c.rehacer },
    ] },
    { titulo: 'Ver', acciones: [
      { id: 'paleta', texto: 'Proyecto y paleta', habilitada: c.editable },
      { id: 'panel', texto: 'Panel de detalles', habilitada: true },
      { id: 'tema', texto: 'Cambiar tema', habilitada: true },
      { id: 'sistema', texto: 'Usar tema del sistema', habilitada: true },
    ] },
    { titulo: 'Modelo', acciones: [
      { id: 'ambiguedades', texto: 'Ambigüedades…', habilitada: leer },
      { id: 'generar', texto: 'Generar Spring Boot…', habilitada: leer },
      { id: 'versiones', texto: 'Versiones…', habilitada: p.leer },
    ] },
  ]
}
/** Navegación circular que saltea elementos deshabilitados. -1 si no hay ninguno. */
export function siguienteHabilitado(habilitados: boolean[], actual: number, delta: 1 | -1): number {
  for (let n = 1; n <= habilitados.length; n++) {
    const i = ((actual + n * delta) % habilitados.length + habilitados.length) % habilitados.length
    if (habilitados[i]) return i
  }
  return -1
}
