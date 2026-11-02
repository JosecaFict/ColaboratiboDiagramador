/**
 * Contrato de `GET /api/v1/diagrams/{id}/documental` (docs/DISENO-FASE-2.md,
 * "A · Variante MongoDB"). Los tres roles pueden leerlo.
 */
export interface CampoDocumental {
  nombre: string
  tipo: string
  requerido: boolean
}

export interface Incrustado {
  campo: string
  entidad: string
  multiple: boolean
}

export interface ReferenciaDocumental {
  campo: string
  coleccion: string
  multiple: boolean
}

export interface Coleccion {
  nombre: string
  entidad: string
  campos: CampoDocumental[]
  incrustados: Incrustado[]
  referencias: ReferenciaDocumental[]
  /** Campo discriminador de una jerarquia (`_tipo`), o `null`. */
  discriminador: string | null
}

export interface ModeloDocumental {
  colecciones: Coleccion[]
  advertencias: string[]
}

/** El almacen para el que se genera el backend. Sin `store`, el servidor usa `postgres`. */
export type Almacen = 'postgres' | 'mongo'

export const ETIQUETA_DE_ALMACEN: Record<Almacen, string> = {
  postgres: 'PostgreSQL',
  mongo: 'MongoDB',
}
