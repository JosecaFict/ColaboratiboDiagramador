import { ErrorDeApi, type Cliente } from '../auth/cliente'
import type { Almacen, Coleccion, ModeloDocumental } from './tipos'

/**
 * Tolera lo que el contrato permite omitir: un arreglo ausente es un arreglo
 * vacio, y las colecciones salen ordenadas por nombre para que dos lecturas
 * del mismo modelo se dibujen igual.
 */
export function normalizarDocumental(crudo: Partial<ModeloDocumental> | null | undefined): ModeloDocumental {
  const colecciones: Coleccion[] = (crudo?.colecciones ?? []).map((c) => ({
    nombre: c.nombre,
    entidad: c.entidad,
    campos: c.campos ?? [],
    incrustados: c.incrustados ?? [],
    referencias: c.referencias ?? [],
    discriminador: c.discriminador ?? null,
  }))
  colecciones.sort((a, b) => a.nombre.localeCompare(b.nombre))
  return { colecciones, advertencias: crudo?.advertencias ?? [] }
}

export async function leerDocumental(cliente: Cliente, diagramaId: string): Promise<ModeloDocumental> {
  return normalizarDocumental(await cliente.obtener<ModeloDocumental>(`/diagrams/${encodeURIComponent(diagramaId)}/documental`))
}

/** Un 404 sin cuerpo es un backend que todavia no tiene `/documental`; se dice asi y no "no se encontro". */
export const mensajeDocumental = (e: unknown): string =>
  e instanceof ErrorDeApi && e.estado === 404
    ? 'El servidor no tiene disponible la vista documental (404). Puede que esta versión del backend todavía no la incluya.'
    : e instanceof Error ? e.message : 'No se pudo derivar la vista documental.'

/** Sufijo de consulta para el generador: `postgres` es el valor por defecto del servidor, asi que no se manda. */
export const consultaDeAlmacen = (almacen: Almacen, prefijo: '?' | '&' = '?'): string =>
  almacen === 'postgres' ? '' : `${prefijo}store=${almacen}`
