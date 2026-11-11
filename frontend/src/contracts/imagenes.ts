// ============================================================================
// CONTRATO DEL CHAT CON IMAGENES (docs/DISENO-CHAT-IMAGENES.md, secciones 4 y 5.4)
//
// ESPEJO de backend/.../ai/imagenes/ImagenMeta.java y de PropuestaDeIA.LecturaDeImagen.
// No se interpreta: se copia. `jackson.default-property-inclusion: non_null`:
// un campo nulo NO viene en el JSON, por eso lo que "puede ser nulo" es opcional aqui.
// ============================================================================

import type { Caja, DeteccionPropuesta, RolDeTexto } from './vision'

/** Metadatos de una imagen guardada. Nunca lleva bytes ni URL: el cliente arma la ruta. */
export interface ImagenMeta {
  id: string
  /** Falta si la cuenta de quien la subio se borro. */
  subidaPorId?: string
  /** "Usuario eliminado" si la cuenta se borro. */
  subidaPorNombre: string
  tipo: 'image/png' | 'image/jpeg'
  ancho: number
  alto: number
  bytes: number
  /** ISO-8601 */
  creadaEn: string
  eliminada: boolean
  eliminadaEn?: string
}

export interface UsoDeImagenes { cantidad: number; bytes: number; maxCantidad: number; maxBytes: number }
export interface GaleriaDeImagenes { imagenes: ImagenMeta[]; uso: UsoDeImagenes }

export type TipoDeDuda =
  | 'SIN_LECTURA_DE_TEXTO' | 'CLASE_SIN_NOMBRE' | 'RELACION_SIN_TIPO' | 'TEXTO_VACIO'
  | 'TEXTO_INCIERTO' | 'CLASE_POCO_CLARA' | 'RELACION_POCO_CLARA'

export interface DudaDeLectura {
  tipo: TipoDeDuda
  elementoRef?: string
  recorteRef?: string
  rol?: RolDeTexto
  leido?: string
  confianza: number
  motivo: string
}

export interface OpcionesDeReproceso {
  giroGrados?: number
  recorte?: RecorteDeImagen
}

/** Recorte en pixeles de la imagen GUARDADA (la que el usuario ve). */
export type RecorteDeImagen = Pick<Caja, 'x' | 'y' | 'ancho' | 'alto'>

export interface LecturaDeImagen {
  deteccion: DeteccionPropuesta
  dudas: DudaDeLectura[]
  dudasOmitidas: number
  enriquecedoresAplicados: string[]
  enriquecedoresFallidos: string[]
  opciones: OpcionesDeReproceso
}

/** Lo que el servidor difunde por /topic/diagram/{id}/ai para las imagenes (seccion 6). */
export type TipoDeAvisoDeImagen = 'IMAGEN_SUBIDA' | 'IMAGEN_ELIMINADA'

/** Limites que el cliente comprueba antes de subir (los mismos de ImagenDeEntrada). */
export const MAX_BYTES_DE_IMAGEN = 8 * 1024 * 1024
export const TIPOS_DE_IMAGEN = ['image/png', 'image/jpeg'] as const
export const LADO_MINIMO_DE_RECORTE = 32
