// ============================================================================
// CONTRATO NUCLEO DEL SISTEMA (ADR-002)
//
// ESPEJO EXACTO de backend/.../modelcore/command/*.java
// Si cambia alla, cambia aca. No se interpreta: se copia.
// ============================================================================

/** Productor del comando. Cinco modalidades, un solo bus. */
export type Origin = 'MOUSE' | 'TEXT' | 'VOICE' | 'IMAGE' | 'XMI_IMPORT'

/**
 * Catalogo CERRADO (ADR-014).
 * No existe ningun comando de generacion masiva de diagramas: esa ausencia
 * es lo que hace estructural la restriccion "la IA no disena, solo edita".
 */
export type CommandType =
  | 'CREATE_CLASS' | 'RENAME_CLASS' | 'DELETE_CLASS'
  | 'ADD_ATTRIBUTE' | 'UPDATE_ATTRIBUTE' | 'REMOVE_ATTRIBUTE'
  | 'ADD_METHOD' | 'REMOVE_METHOD'
  | 'CREATE_RELATION' | 'UPDATE_RELATION' | 'DELETE_RELATION'
  | 'SET_ER_FLAG' | 'SET_MODELING_DECISION'
  /** Confirma el esquema fisico revisado (ADR-032). No toca el modelo. */
  | 'CONFIRM_SCHEMA'
  | 'MOVE_ELEMENT' | 'RESIZE_ELEMENT'
  | 'BATCH'

export interface Command {
  /** UUID generado por el CLIENTE -> idempotencia ante reintentos. */
  opId: string
  diagramId: string
  /** Numero de secuencia que el cliente tenia al emitir. */
  baseSeq: number
  type: CommandType
  origin: Origin
  payload: Record<string, unknown>
}

/**
 * Marcas opcionales de un DESHACER (ADR-027). Viajan dentro del payload, no en
 * el Command: un undo es un comando comun del catalogo cerrado, y `origin`
 * sigue diciendo la modalidad (un Ctrl+Z es MOUSE). Espejo de lo que valida
 * collab/PrecondicionDeDeshacer.java.
 *
 * Las aceptan los 17 comandos (PrecondicionDeDeshacer). Crear y borrar llevan
 * sus propias claves: {eliminada} / {existe} para restaurar con el mismo id, y
 * el contenido de la clase ({attributeIds, methodIds, relationIds}) para
 * borrarla. En un BATCH, `deshace` va en el lote y `esperado` en cada cambio.
 */
export interface MarcasDeDeshacer {
  /** opId de la operacion PROPIA que se revierte. Rehacer apunta al undo. */
  deshace: string
  /**
   * Como tiene que estar el objetivo para que el comando aplique, con las
   * mismas claves que el payload ({x, y}, {name}, {dataType}, {value}...).
   * Solo se comparan las que vienen: si otro cambio OTRO campo, no importa.
   * Si cambio uno de estos, el servidor rechaza con STALE en vez de pisarlo.
   */
  esperado: EstadoEsperado
}

export type EstadoEsperado = Record<string, unknown>

/** Operacion ya aceptada y secuenciada por el servidor. */
export interface AppliedOperation {
  opId: string
  seq: number
  type: CommandType
  origin: Origin
  payload: Record<string, unknown>
  userId: string | null
  ts: string
}

export type RejectionReason =
  | 'LOCKED_BY_OTHER' | 'STALE' | 'INVALID' | 'FORBIDDEN' | 'NOT_FOUND'

export interface CommandRejection {
  opId: string
  reason: RejectionReason
  message: string
}

export interface PresenceMessage {
  userId: string
  userName: string
  x: number
  y: number
  selectedElementId: string | null
}

/**
 * Estado REAL del lease despues de cada pedido, no el pedido en si.
 * Con `held: false` no hay dueno, asi que `userId` y `userName` llegan en null.
 */
export interface LockMessage {
  elementId: string
  userId: string | null
  userName: string | null
  held: boolean
}

// ---------------------------------------------------------------- METAMODELO
export type RelationKind =
  | 'ASSOCIATION' | 'AGGREGATION' | 'COMPOSITION' | 'GENERALIZATION' | 'DEPENDENCY'

export type ErKind = 'STRONG' | 'WEAK' | 'ASSOCIATIVE'
/** Regla 4: como se mapea una jerarquia. Se fija en la clase raiz. */
export type InheritanceStrategy = 'JOINED' | 'SINGLE_TABLE'
export type Participation = 'TOTAL' | 'PARTIAL'
export type NotationView = 'CLASS' | 'CHEN' | 'LOGICAL' | 'PHYSICAL'

export interface UmlAttribute {
  id: string
  classId: string
  name: string
  dataType: string
  /** UML 2.5.1: la Property lleva visibilidad, igual que la Operation. */
  visibility: Visibility
  length?: number | null
  pk: boolean
  required: boolean
  unique: boolean
  /** Faltaba en el espejo aunque el backend lo guarda desde la Fase 0. */
  defaultValue?: string | null
  ordinal: number
  // Anotaciones ER (se editan desde la vista Chen)
  multivalued: boolean
  derived: boolean
  partialKey: boolean
  parentAttributeId?: string | null
  /** Regla 9: limites de un atributo numerico. null = sin limite de ese lado. */
  minValue?: number | null
  maxValue?: number | null
}

/**
 * Estereotipo de una enumeracion. En UML es un clasificador: sus atributos son
 * los literales, y otra clase la usa como TIPO de un atributo. Espejo de
 * Enumeraciones.ESTEREOTIPO en el backend.
 */
export const ESTEREOTIPO_ENUM = 'enumeration'
export const esEnumeracion = (c: { stereotype?: string | null }) =>
  (c.stereotype ?? '').toLowerCase() === ESTEREOTIPO_ENUM

/**
 * Regla 5: clase de VALOR. No tiene tabla ni identidad; vive en las columnas de
 * quien la usa como tipo de un atributo o la compone 1..1. Espejo de
 * Incrustables.ESTEREOTIPO en el backend.
 */
export const ESTEREOTIPO_EMBEBIBLE = 'embeddable'
export const esEmbebible = (c: { stereotype?: string | null }) =>
  (c.stereotype ?? '').toLowerCase() === ESTEREOTIPO_EMBEBIBLE

export type Visibility = 'PUBLIC' | 'PRIVATE' | 'PROTECTED' | 'PACKAGE'

/** Simbolo UML de cada visibilidad, para dibujar delante de la firma. */
export const SIMBOLO_VISIBILIDAD: Record<Visibility, string> = {
  PUBLIC: '+', PRIVATE: '-', PROTECTED: '#', PACKAGE: '~',
}

export interface UmlParametro {
  name: string
  type: string
}

/**
 * REGLA 6: los metodos no se descartan. No van a la Entity ni al DDL --una
 * tabla guarda estado, no comportamiento-- pero si al Service generado.
 */
export interface UmlMethod {
  id: string
  classId: string
  name: string
  returnType: string
  params: UmlParametro[]
  visibility: Visibility
  ordinal: number
}

export interface UmlClass {
  id: string
  diagramId: string
  name: string
  stereotype?: string | null
  isAbstract: boolean
  weak: boolean
  erKind: ErKind
  inheritanceStrategy: InheritanceStrategy
  attributes: UmlAttribute[]
  methods: UmlMethod[]
}

export interface UmlRelationship {
  id: string
  diagramId: string
  sourceClassId: string
  targetClassId: string
  kind: RelationKind
  name?: string | null
  /** De aqui sale el nombre de la columna FK en el codigo generado (Regla 3). */
  sourceRole?: string | null
  targetRole?: string | null
  sourceMultiplicity?: string | null
  targetMultiplicity?: string | null
  identifying: boolean
  sourceParticipation?: Participation | null
  targetParticipation?: Participation | null
  degree: number
}

export interface Punto { x: number; y: number }

export interface ElementLayout {
  elementId: string
  x: number
  y: number
  width: number
  height: number
  /** Quiebres de una linea de relacion. Vacio o ausente = va recta. */
  waypoints?: Punto[]
}
