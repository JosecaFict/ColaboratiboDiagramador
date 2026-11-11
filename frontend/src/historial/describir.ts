import type { AppliedOperation, CommandType, Origin, RelationKind } from '../contracts/command'
import { ESTEREOTIPO_EMBEBIBLE, ESTEREOTIPO_ENUM } from '../contracts/command'
import type { EstadoModelo } from '../canvas/applyOperation'

/**
 * EL HISTORIAL EN PALABRAS DE PERSONA.
 *
 * El log del servidor guarda `#12 CREATE_CLASS MOUSE`: sirve para depurar, no
 * para que el equipo sepa que paso. Esto lo traduce a "Daniel creo la clase
 * «Pedido» · hace 2 min". Es una funcion pura: no toca el modelo ni la red.
 *
 * Los nombres salen de dos lados porque ninguno alcanza solo:
 *   - el modelo ACTUAL tiene los nombres de lo que sigue existiendo;
 *   - los payloads de las operaciones cargadas tienen los de lo que ya se borro
 *     y los nombres viejos de lo que se renombro.
 */

/** Mover y redimensionar no son cambios del modelo: no entran al historial. */
const GEOMETRIA = new Set<CommandType>(['MOVE_ELEMENT', 'RESIZE_ELEMENT'])

export const esGeometria = (op: Pick<AppliedOperation, 'type' | 'payload'>): boolean => {
  if (op.type !== 'BATCH') return GEOMETRIA.has(op.type)
  const dentro = comandosDe(op.payload)
  return dentro.length > 0 && dentro.every((c) => GEOMETRIA.has(c.type))
}

interface Paso { type: CommandType; payload: Record<string, unknown> }

const comandosDe = (payload: Record<string, unknown>): Paso[] =>
  (payload.comandos as Paso[] | undefined) ?? []

/** Lo que se sabe de cada id del diagrama, vivo o borrado. */
export interface Indice {
  clases: Map<string, string>
  atributos: Map<string, { nombre: string; claseId: string }>
  metodos: Map<string, { nombre: string; claseId: string }>
  relaciones: Map<string, { origen: string; destino: string }>
  /** opId de un RENAME_CLASS -> el nombre que la clase tenia antes. */
  nombreAnterior: Map<string, string>
}

/**
 * Arma el indice. Las operaciones pueden venir en cualquier orden: se recorren
 * por `seq` para saber que nombre tenia una clase ANTES de cada renombre.
 */
export function indiceDeNombres(
  modelo: EstadoModelo,
  ops: AppliedOperation[],
  /** Clases que esta pestana vio alguna vez: da el nombre de una que ya se borro. */
  clasesVistas?: ReadonlyMap<string, string>,
): Indice {
  const indice: Indice = {
    clases: new Map(), atributos: new Map(), metodos: new Map(),
    relaciones: new Map(), nombreAnterior: new Map(),
  }
  const visitar = (opId: string, paso: Paso) => {
    const p = paso.payload
    switch (paso.type) {
      case 'CREATE_CLASS':
        indice.clases.set(String(p.classId), String(p.name))
        break
      case 'RENAME_CLASS': {
        const antes = indice.clases.get(String(p.classId))
        if (antes && antes !== String(p.name)) indice.nombreAnterior.set(opId, antes)
        indice.clases.set(String(p.classId), String(p.name))
        break
      }
      case 'ADD_ATTRIBUTE':
        indice.atributos.set(String(p.attributeId), { nombre: String(p.name), claseId: String(p.classId) })
        break
      case 'ADD_METHOD':
        indice.metodos.set(String(p.methodId), { nombre: String(p.name), claseId: String(p.classId) })
        break
      case 'CREATE_RELATION':
        indice.relaciones.set(String(p.relationId),
          { origen: String(p.sourceClassId), destino: String(p.targetClassId) })
        break
      case 'BATCH':
        for (const c of comandosDe(p)) visitar(opId, c)
        break
      default:
        break
    }
  }
  for (const op of [...ops].sort((a, b) => a.seq - b.seq)) visitar(op.opId, op)

  // Lo visto solo completa: no pisa lo que dijeron las operaciones, porque un
  // nombre visto puede ser posterior a la operacion que se esta describiendo.
  for (const [id, nombre] of clasesVistas ?? []) {
    if (!indice.clases.has(id)) indice.clases.set(id, nombre)
  }

  // El modelo actual manda sobre lo que dijeron las operaciones: es lo ultimo.
  for (const c of modelo.classes) {
    indice.clases.set(c.id, c.name)
    for (const a of c.attributes) indice.atributos.set(a.id, { nombre: a.name, claseId: c.id })
    for (const m of c.methods) indice.metodos.set(m.id, { nombre: m.name, claseId: c.id })
  }
  for (const r of modelo.relationships) {
    indice.relaciones.set(r.id, { origen: r.sourceClassId, destino: r.targetClassId })
  }
  return indice
}

const TIPO_DE_RELACION: Record<RelationKind, string> = {
  ASSOCIATION: 'asociación',
  AGGREGATION: 'agregación',
  COMPOSITION: 'composición',
  GENERALIZATION: 'herencia',
  DEPENDENCY: 'dependencia',
  REALIZATION: 'realización',
}

const CAMPO_DE_ATRIBUTO: Record<string, string> = {
  name: 'el nombre', dataType: 'el tipo', pk: 'la clave primaria', required: 'si es obligatorio',
  unique: 'si es único', length: 'el largo', visibility: 'la visibilidad',
  defaultValue: 'el valor por defecto', minValue: 'el mínimo', maxValue: 'el máximo',
  multivalued: 'si es multivaluado', derived: 'si es derivado', partialKey: 'la clave parcial',
}

const CAMPO_DE_RELACION: Record<string, string> = {
  name: 'el nombre', kind: 'el tipo', sourceMultiplicity: 'la multiplicidad',
  targetMultiplicity: 'la multiplicidad', sourceRole: 'el rol', targetRole: 'el rol',
  identifying: 'si es identificadora', sourceParticipation: 'la participación',
  targetParticipation: 'la participación',
}

/** Claves del payload que no dicen QUE se cambio, sino a que o como. */
const CLAVES_DE_CONTROL = new Set(['attributeId', 'relationId', 'classId', 'deshace', 'esperado'])

/** "el nombre y el tipo", sin repetir ("la multiplicidad" de los dos extremos es una). */
function camposCambiados(payload: Record<string, unknown>, nombres: Record<string, string>): string {
  const vistos = [...new Set(Object.keys(payload)
    .filter((k) => !CLAVES_DE_CONTROL.has(k))
    .map((k) => nombres[k])
    .filter(Boolean))]
  if (vistos.length === 0) return ''
  if (vistos.length === 1) return vistos[0]
  return `${vistos.slice(0, -1).join(', ')} y ${vistos[vistos.length - 1]}`
}

const comillas = (s: string) => `«${s}»`

/**
 * La frase SIN el autor: "creó la clase «Pedido»". El autor lo pone quien la
 * muestra, porque en el panel de una clase va distinto que en el general.
 */
export function describir(op: Pick<AppliedOperation, 'opId' | 'type' | 'origin' | 'payload'>, indice: Indice): string {
  const p = op.payload
  const clase = (id: unknown) => indice.clases.get(String(id))
  const claseO = (id: unknown, alternativa = 'una clase') => {
    const n = clase(id)
    return n ? comillas(n) : alternativa
  }
  const extremos = (relationId: unknown, origen?: unknown, destino?: unknown) => {
    const r = indice.relaciones.get(String(relationId))
    const a = clase(origen ?? r?.origen)
    const b = clase(destino ?? r?.destino)
    return a && b ? `${comillas(a)} — ${comillas(b)}` : ''
  }
  const atributo = (id: unknown) => {
    const a = indice.atributos.get(String(id))
    if (!a) return 'un atributo'
    const c = clase(a.claseId)
    return comillas(c ? `${c}.${a.nombre}` : a.nombre)
  }

  switch (op.type) {
    case 'CREATE_CLASS': {
      const estereotipo = String(p.stereotype ?? '').toLowerCase()
      const que = estereotipo === ESTEREOTIPO_ENUM ? 'la enumeración'
        : estereotipo === ESTEREOTIPO_EMBEBIBLE ? 'el valor embebido' : 'la clase'
      return `creó ${que} ${comillas(String(p.name))}`
    }
    case 'RENAME_CLASS': {
      const antes = indice.nombreAnterior.get(op.opId)
      return antes
        ? `renombró ${comillas(antes)} a ${comillas(String(p.name))}`
        : `renombró una clase a ${comillas(String(p.name))}`
    }
    case 'DELETE_CLASS':
      return `borró la clase ${claseO(p.classId, '')}`.trimEnd()
    case 'ADD_ATTRIBUTE':
      return `agregó el atributo ${comillas(String(p.name))} a ${claseO(p.classId)}`
    case 'UPDATE_ATTRIBUTE': {
      const campos = camposCambiados(p, CAMPO_DE_ATRIBUTO)
      return campos ? `cambió ${campos} de ${atributo(p.attributeId)}` : `editó ${atributo(p.attributeId)}`
    }
    case 'REMOVE_ATTRIBUTE':
      return `quitó el atributo ${atributo(p.attributeId)}`
    case 'ADD_METHOD':
      return `agregó el método ${comillas(String(p.name) + '()')} a ${claseO(p.classId)}`
    case 'REMOVE_METHOD': {
      const m = indice.metodos.get(String(p.methodId))
      return m ? `quitó el método ${comillas(m.nombre + '()')} de ${claseO(m.claseId)}` : 'quitó un método'
    }
    case 'CREATE_RELATION': {
      const tipo = TIPO_DE_RELACION[p.kind as RelationKind] ?? 'relación'
      const entre = extremos(p.relationId, p.sourceClassId, p.targetClassId)
      return entre ? `relacionó ${entre} (${tipo})` : `creó una ${tipo}`
    }
    case 'UPDATE_RELATION': {
      const entre = extremos(p.relationId)
      const campos = camposCambiados(p, CAMPO_DE_RELACION)
      const cual = entre ? `la relación ${entre}` : 'una relación'
      return campos ? `cambió ${campos} de ${cual}` : `editó ${cual}`
    }
    case 'DELETE_RELATION': {
      const entre = extremos(p.relationId)
      return entre ? `borró la relación ${entre}` : 'borró una relación'
    }
    case 'SET_ER_FLAG':
      return p.flag === 'inheritanceStrategy'
        ? `cambió cómo se mapea la herencia de ${claseO(p.targetId)}`
        : p.targetType === 'CLASS'
          ? `cambió una anotación de ${claseO(p.targetId)}`
          : `cambió una anotación de ${atributo(p.targetId)}`
    case 'SET_MODELING_DECISION':
      return 'tomó una decisión de mapeo'
    case 'CONFIRM_SCHEMA':
      return 'confirmó el esquema de la base de datos'
    case 'MOVE_ELEMENT':
      return 'movió un elemento'
    case 'RESIZE_ELEMENT':
      return 'cambió el tamaño de un elemento'
    case 'BATCH': {
      const pasos = comandosDe(p).filter((c) => !GEOMETRIA.has(c.type))
      const descripcion = typeof p.descripcion === 'string' && p.descripcion.trim() ? p.descripcion.trim() : null
      if (pasos.length === 1 && !descripcion) return describir({ ...op, type: pasos[0].type, payload: pasos[0].payload }, indice)
      const cuantos = `${pasos.length} ${pasos.length === 1 ? 'cambio' : 'cambios'}`
      const porOrigen: Partial<Record<Origin, string>> = {
        IMAGE: `importó desde una imagen (${cuantos})`,
        XMI_IMPORT: `importó un modelo (${cuantos})`,
      }
      return porOrigen[op.origin]
        ?? (descripcion ? `${primeraMinuscula(descripcion)} (${cuantos})` : `aplicó ${cuantos} de una vez`)
    }
    default:
      return 'hizo un cambio'
  }
}

const primeraMinuscula = (s: string) => s.charAt(0).toLowerCase() + s.slice(1)

/** ¿La operacion toca esta clase? Para el historial del panel de propiedades. */
export function tocaClase(op: Pick<AppliedOperation, 'type' | 'payload'>, claseId: string, indice: Indice): boolean {
  const p = op.payload
  const de = (id: unknown, mapa: Map<string, { claseId: string }>) => mapa.get(String(id))?.claseId === claseId
  switch (op.type) {
    case 'CREATE_CLASS': case 'RENAME_CLASS': case 'DELETE_CLASS':
    case 'ADD_ATTRIBUTE': case 'ADD_METHOD':
      return p.classId === claseId
    case 'UPDATE_ATTRIBUTE': case 'REMOVE_ATTRIBUTE':
      return de(p.attributeId, indice.atributos)
    case 'REMOVE_METHOD':
      return de(p.methodId, indice.metodos)
    case 'CREATE_RELATION':
      return p.sourceClassId === claseId || p.targetClassId === claseId
    case 'UPDATE_RELATION': case 'DELETE_RELATION': {
      const r = indice.relaciones.get(String(p.relationId))
      return !!r && (r.origen === claseId || r.destino === claseId)
    }
    case 'SET_ER_FLAG':
      return p.targetType === 'CLASS' ? p.targetId === claseId : de(p.targetId, indice.atributos)
    case 'BATCH':
      return comandosDe(p).some((c) => !GEOMETRIA.has(c.type) && tocaClase(c, claseId, indice))
    default:
      return false
  }
}

/** "hace unos segundos", "hace 5 min", "hace 2 h", "ayer", "el 3/11". */
export function hace(ts: string, ahora: number): string {
  const momento = Date.parse(ts)
  if (Number.isNaN(momento)) return ''
  const segundos = Math.max(0, Math.round((ahora - momento) / 1000))
  if (segundos < 60) return 'hace unos segundos'
  const minutos = Math.floor(segundos / 60)
  if (minutos < 60) return `hace ${minutos} min`
  const horas = Math.floor(minutos / 60)
  if (horas < 24) return `hace ${horas} h`
  if (horas < 48) return 'ayer'
  const d = new Date(momento)
  return `el ${d.getDate()}/${d.getMonth() + 1}`
}

/** Por donde entro el cambio, en palabras. Lo hecho con el mouse no se aclara. */
export const ETIQUETA_DE_ORIGEN: Record<Origin, string | null> = {
  MOUSE: null,
  TEXT: 'texto',
  VOICE: 'voz',
  IMAGE: 'imagen',
  XMI_IMPORT: 'importación',
}

/** Una linea del historial, lista para mostrar. */
export interface EntradaLegible {
  opId: string
  seq: number
  autor: string
  /** El que mira la pantalla hizo este cambio. */
  propia: boolean
  texto: string
  ts: string
  origen: string | null
  /** Es un deshacer: se marca con ↶. */
  deshace: boolean
  /** Para quien quiere rastrearlo en el log: "#12 CREATE_CLASS". */
  tecnico: string
}

export function entradasLegibles(
  ops: AppliedOperation[],
  indice: Indice,
  autorDe: (userId: string | null) => { nombre: string; propio: boolean },
): EntradaLegible[] {
  return ops.filter((op) => !esGeometria(op)).map((op) => {
    const autor = autorDe(op.userId)
    return {
      opId: op.opId,
      seq: op.seq,
      autor: autor.nombre,
      propia: autor.propio,
      texto: describir(op, indice),
      ts: op.ts,
      origen: ETIQUETA_DE_ORIGEN[op.origin] ?? null,
      deshace: Boolean(op.payload.deshace),
      tecnico: `#${op.seq} ${op.type}`,
    }
  })
}
