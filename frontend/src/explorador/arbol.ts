import {
  SIMBOLO_VISIBILIDAD, esEmbebible, esEnumeracion, esInterfaz,
  type RelationKind, type UmlClass, type UmlRelationship,
} from '../contracts/command'

/**
 * Lo que muestra el arbol del proyecto, ya ordenado y con nombres. Funcion pura:
 * el componente solo dibuja.
 */

/** Que tipo de elemento es, como en la paleta. */
export type TipoDeElemento = 'CLASE' | 'ABSTRACTA' | 'INTERFAZ' | 'ENUM' | 'VALOR'

export const tipoDe = (c: UmlClass): TipoDeElemento =>
  esEnumeracion(c) ? 'ENUM' : esEmbebible(c) ? 'VALOR' : esInterfaz(c) ? 'INTERFAZ' : c.isAbstract ? 'ABSTRACTA' : 'CLASE'

/** La letra del icono, como la «C» de StarUML. */
export const LETRA: Record<TipoDeElemento, string> = { CLASE: 'C', ABSTRACTA: 'A', INTERFAZ: 'I', ENUM: 'E', VALOR: 'V' }

export const NOMBRE_DE_TIPO: Record<TipoDeElemento, string> = {
  CLASE: 'Clase', ABSTRACTA: 'Clase abstracta', INTERFAZ: 'Interfaz', ENUM: 'Enumeración', VALOR: 'Valor embebido',
}

export interface NodoClase {
  id: string
  nombre: string
  tipo: TipoDeElemento
  /** "- total: Double" o, en una enumeracion, el literal. */
  miembros: string[]
}

export interface NodoRelacion {
  id: string
  kind: RelationKind
  /** "Pedido — Cliente" */
  texto: string
}

const porNombre = (a: { nombre?: string; texto?: string }, b: { nombre?: string; texto?: string }) =>
  (a.nombre ?? a.texto ?? '').localeCompare(b.nombre ?? b.texto ?? '', 'es', { sensitivity: 'base' })

export function nodosDeClases(clases: UmlClass[]): NodoClase[] {
  return clases.map((c) => {
    const tipo = tipoDe(c)
    const atributos = [...c.attributes].sort((a, b) => a.ordinal - b.ordinal).map((a) =>
      tipo === 'ENUM' ? a.name : `${SIMBOLO_VISIBILIDAD[a.visibility] ?? ''} ${a.name}: ${a.dataType}`.trim())
    const metodos = [...c.methods].sort((a, b) => a.ordinal - b.ordinal).map((m) =>
      `${SIMBOLO_VISIBILIDAD[m.visibility] ?? ''} ${m.name}(): ${m.returnType}`.trim())
    return { id: c.id, nombre: c.name, tipo, miembros: [...atributos, ...metodos] }
  }).sort(porNombre)
}

export function nodosDeRelaciones(relaciones: UmlRelationship[], clases: UmlClass[]): NodoRelacion[] {
  const nombre = (id: string) => clases.find((c) => c.id === id)?.name ?? '?'
  return relaciones.map((r) => ({
    id: r.id,
    kind: r.kind,
    texto: r.name?.trim()
      ? `${r.name.trim()} (${nombre(r.sourceClassId)} — ${nombre(r.targetClassId)})`
      : `${nombre(r.sourceClassId)} — ${nombre(r.targetClassId)}`,
  })).sort(porNombre)
}
