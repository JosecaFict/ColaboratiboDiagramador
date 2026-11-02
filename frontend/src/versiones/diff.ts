import type { UmlAttribute, UmlClass, UmlMethod, UmlRelationship } from '../contracts/command'
import {
  GRUPOS, TIPO_DE_GRUPO,
  type CambioDeCampo, type DiffVersiones, type ElementoDelDiff, type ElementoModificado,
  type ExtremoDelDiff, type GrupoDelDiff, type NombreDeGrupo, type SnapshotDeVersion, type TipoDeElemento,
} from './tipos'

/**
 * Diff entre dos fotos del modelo, en el cliente.
 *
 * El diff REAL lo calcula el servidor (`GET /diagrams/{id}/diff`). Esta
 * logica existe por tres razones: el modo simulado (mientras el backend no
 * este), el merge a tres bandas simulado, y los tests, que fijan QUE se
 * considera un cambio. Los campos comparados son los del modelo UML; la
 * geometria (`layout`) no cuenta: mover una caja no es un cambio de modelo.
 */

/** Los campos que cuentan como "cambio" de cada tipo de elemento. */
export const CAMPOS: Record<TipoDeElemento, readonly string[]> = {
  clase: ['name', 'stereotype', 'isAbstract', 'weak', 'erKind', 'inheritanceStrategy'],
  atributo: [
    'name', 'dataType', 'visibility', 'length', 'pk', 'required', 'unique', 'defaultValue',
    'ordinal', 'multivalued', 'derived', 'partialKey', 'parentAttributeId', 'minValue', 'maxValue',
  ],
  metodo: ['name', 'returnType', 'params', 'visibility', 'ordinal'],
  relacion: [
    'sourceClassId', 'targetClassId', 'kind', 'name', 'sourceRole', 'targetRole',
    'sourceMultiplicity', 'targetMultiplicity', 'identifying', 'sourceParticipation',
    'targetParticipation', 'degree',
  ],
}

/** Un elemento del modelo visto de forma plana, con lo necesario para mostrarlo. */
export interface ElementoPlano {
  id: string
  tipo: TipoDeElemento
  nombre: string
  claseId?: string
  clase?: string
  /** El objeto tal cual, para leer sus campos y para reconstruir el modelo. */
  dato: Record<string, unknown>
}

export type ModeloPlano = Record<NombreDeGrupo, Map<string, ElementoPlano>>

const vacio = (v: unknown): unknown => (v === undefined ? null : v)

/** Igualdad estructural de valores JSON (parametros de un metodo, por ejemplo). `undefined` y `null` son lo mismo. */
export function iguales(a: unknown, b: unknown): boolean {
  a = vacio(a); b = vacio(b)
  if (a === b) return true
  if (typeof a !== 'object' || typeof b !== 'object' || a === null || b === null) return false
  if (Array.isArray(a) !== Array.isArray(b)) return false
  const ka = Object.keys(a as object), kb = Object.keys(b as object)
  if (ka.length !== kb.length) return false
  return ka.every((k) => iguales((a as Record<string, unknown>)[k], (b as Record<string, unknown>)[k]))
}

/** Aplana clases (con sus atributos y metodos) y relaciones en cuatro mapas por id. */
export function aplanar(s: Pick<SnapshotDeVersion, 'classes' | 'relationships'>): ModeloPlano {
  const m: ModeloPlano = { clases: new Map(), atributos: new Map(), metodos: new Map(), relaciones: new Map() }
  const nombreDeClase = new Map(s.classes.map((c) => [c.id, c.name]))
  for (const c of s.classes) {
    m.clases.set(c.id, { id: c.id, tipo: 'clase', nombre: c.name, dato: c as unknown as Record<string, unknown> })
    for (const a of c.attributes ?? []) {
      m.atributos.set(a.id, {
        id: a.id, tipo: 'atributo', nombre: a.name, claseId: c.id, clase: c.name,
        dato: a as unknown as Record<string, unknown>,
      })
    }
    for (const x of c.methods ?? []) {
      m.metodos.set(x.id, {
        id: x.id, tipo: 'metodo', nombre: x.name, claseId: c.id, clase: c.name,
        dato: x as unknown as Record<string, unknown>,
      })
    }
  }
  for (const r of s.relationships) {
    const origen = nombreDeClase.get(r.sourceClassId) ?? '?'
    const destino = nombreDeClase.get(r.targetClassId) ?? '?'
    m.relaciones.set(r.id, {
      id: r.id, tipo: 'relacion', nombre: r.name || `${origen} → ${destino}`,
      dato: r as unknown as Record<string, unknown>,
    })
  }
  return m
}

/** Los campos de `tipo` que difieren entre dos versiones del mismo elemento. */
export function cambiosEntre(
  tipo: TipoDeElemento, antes: Record<string, unknown>, despues: Record<string, unknown>,
): CambioDeCampo[] {
  const cambios: CambioDeCampo[] = []
  for (const campo of CAMPOS[tipo]) {
    if (!iguales(antes[campo], despues[campo])) {
      cambios.push({ campo, antes: vacio(antes[campo]), despues: vacio(despues[campo]) })
    }
  }
  return cambios
}

const resumen = (e: ElementoPlano): ElementoDelDiff =>
  e.claseId === undefined
    ? { id: e.id, nombre: e.nombre }
    : { id: e.id, nombre: e.nombre, claseId: e.claseId, clase: e.clase }

function diffDeGrupo(tipo: TipoDeElemento, a: Map<string, ElementoPlano>, b: Map<string, ElementoPlano>): GrupoDelDiff {
  const g: GrupoDelDiff = { agregadas: [], eliminadas: [], modificadas: [] }
  for (const [id, despues] of b) {
    const antes = a.get(id)
    if (!antes) { g.agregadas.push(resumen(despues)); continue }
    const cambios = cambiosEntre(tipo, antes.dato, despues.dato)
    // Cambiar de clase (atributo movido) tambien es un cambio, aunque `classId` no sea un campo editable.
    if (antes.claseId !== despues.claseId) {
      cambios.push({ campo: 'classId', antes: antes.clase ?? antes.claseId ?? null, despues: despues.clase ?? despues.claseId ?? null })
    }
    if (cambios.length > 0) g.modificadas.push({ ...resumen(despues), cambios } satisfies ElementoModificado)
  }
  for (const [id, antes] of a) if (!b.has(id)) g.eliminadas.push(resumen(antes))
  return g
}

/** Lo que cambio al pasar de `desde` a `hasta`, agrupado como lo manda el servidor. */
export function calcularDiffEntreModelos(
  desde: Pick<SnapshotDeVersion, 'classes' | 'relationships'>,
  hasta: Pick<SnapshotDeVersion, 'classes' | 'relationships'>,
): Omit<DiffVersiones, 'desde' | 'hasta'> {
  const a = aplanar(desde), b = aplanar(hasta)
  return {
    clases: diffDeGrupo('clase', a.clases, b.clases),
    atributos: diffDeGrupo('atributo', a.atributos, b.atributos),
    metodos: diffDeGrupo('metodo', a.metodos, b.metodos),
    relaciones: diffDeGrupo('relacion', a.relaciones, b.relaciones),
  }
}

export function calcularDiff(
  desde: Pick<SnapshotDeVersion, 'classes' | 'relationships'>, extremoDesde: ExtremoDelDiff,
  hasta: Pick<SnapshotDeVersion, 'classes' | 'relationships'>, extremoHasta: ExtremoDelDiff,
): DiffVersiones {
  return { desde: extremoDesde, hasta: extremoHasta, ...calcularDiffEntreModelos(desde, hasta) }
}

type ConGrupos = Pick<DiffVersiones, NombreDeGrupo>

/** Cuantos elementos cambiaron en total (para el "sin diferencias" y las insignias). */
export function totalDeCambios(d: ConGrupos): number {
  return GRUPOS.reduce((n, g) => n + d[g].agregadas.length + d[g].eliminadas.length + d[g].modificadas.length, 0)
}

export const estaVacio = (d: ConGrupos): boolean => totalDeCambios(d) === 0

export type EstadoDeCambio = 'agregado' | 'eliminado' | 'modificado'

/**
 * Para resaltar en el lienzo: id de CLASE o de RELACION -> que le paso.
 *
 * Un atributo o metodo agregado/eliminado/modificado marca a su clase como
 * `modificado` (la caja cambio por dentro). Las clases eliminadas ya no estan en
 * el lienzo actual, asi que no se pueden resaltar alli: el diff las lista.
 */
export function resaltadosParaLienzo(d: ConGrupos): Record<string, EstadoDeCambio> {
  const r: Record<string, EstadoDeCambio> = {}
  for (const x of d.clases.agregadas) r[x.id] = 'agregado'
  for (const x of d.clases.eliminadas) r[x.id] = 'eliminado'
  for (const x of d.clases.modificadas) r[x.id] = 'modificado'
  for (const g of ['atributos', 'metodos'] as const) {
    for (const x of [...d[g].agregadas, ...d[g].eliminadas, ...d[g].modificadas]) {
      if (x.claseId && !r[x.claseId]) r[x.claseId] = 'modificado'
    }
  }
  for (const x of d.relaciones.agregadas) r[x.id] = 'agregado'
  for (const x of d.relaciones.eliminadas) r[x.id] = 'eliminado'
  for (const x of d.relaciones.modificadas) r[x.id] = 'modificado'
  return r
}

export const ETIQUETA_DE_GRUPO: Record<NombreDeGrupo, string> = {
  clases: 'Clases', atributos: 'Atributos', metodos: 'Métodos', relaciones: 'Relaciones',
}

export const TIPO_POR_GRUPO = TIPO_DE_GRUPO

/** El valor de un campo, legible en una celda del diff. */
export function mostrarValor(v: unknown, campo?: string): string {
  if (v === null || v === undefined || v === '') return '—'
  if (campo === 'waypoints' && Array.isArray(v) && v.length === 0) return 'recta'
  if (typeof v === 'string') return v
  if (typeof v === 'boolean') return v ? 'sí' : 'no'
  if (Array.isArray(v) && v.every((p) => p && typeof p === 'object' && 'name' in p)) {
    return v.length === 0 ? '()' : '(' + v.map((p: { name: string; type?: string }) => p.type ? `${p.name}: ${p.type}` : p.name).join(', ') + ')'
  }
  // Los quiebres de una linea: una lista de puntos {x, y}.
  if (Array.isArray(v) && v.every((p) => p && typeof p === 'object' && 'x' in p && 'y' in p)) {
    return v.length === 0 ? 'recta' : `${v.length} ${v.length === 1 ? 'punto' : 'puntos'}`
  }
  return typeof v === 'object' ? JSON.stringify(v) : String(v)
}

// Los tipos se reexportan para que quien arma un modelo de prueba no tenga que ir a command.ts.
export type { UmlAttribute, UmlClass, UmlMethod, UmlRelationship }
