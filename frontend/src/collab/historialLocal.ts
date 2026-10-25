import type {
  CommandRejection, CommandType, EstadoEsperado, MarcasDeDeshacer, Punto, UmlAttribute, UmlClass,
  UmlMethod, UmlRelationship,
} from '../contracts/command'
import { aplicarOperacion, type EstadoModelo } from '../canvas/applyOperation'

/**
 * DESHACER / REHACER LOCAL (ADR-027, ADR-029, ADR-030).
 *
 * Cada PESTANA deshace lo suyo, no "la ultima operacion del diagrama", que
 * puede ser de otro. Es por sesion y no por usuario por la misma razon que los
 * locks (ADR-004): dos pestanas de la misma persona son dos editores.
 *
 * El undo no tiene camino propio: es un comando COMUN del catalogo por el bus
 * (ADR-002), con opId y seq nuevos. Para una edicion es el mismo comando con
 * el valor anterior; para crear es borrar, para borrar es volver a crear con
 * los MISMOS ids, y para un lote es otro lote con los inversos al reves.
 * Lleva dos marcas:
 *   - `deshace`: la operacion que revierte (auditoria, la valida el servidor).
 *   - `esperado`: como tiene que estar el objetivo. Si otro lo cambio despues,
 *     el servidor rechaza en vez de pisarlo, y aca se avisa con el motivo.
 *
 * El inverso se captura AL EMITIR, del estado que el cliente tiene: cuando la
 * operacion vuelva, lo anterior (el nombre viejo, los atributos de la clase
 * borrada) ya no esta. La correccion no depende de esa captura sino de
 * `esperado`, que se evalua en el hilo unico del diagrama.
 *
 * Nada entra a la pila hasta que el servidor CONFIRMA la operacion: el cliente
 * no aplica en forma optimista, y un comando rechazado no es algo deshacible.
 *
 * Vive en memoria: recargar la pagina vacia las pilas (recorte declarado).
 */

/** Un comando listo para mandar, con su `esperado` ya puesto. Falta `deshace`. */
export interface Paso {
  type: CommandType
  payload: Record<string, unknown>
}

export interface Entrada {
  /** Lo que se ve en el boton: "mover Libro". */
  etiqueta: string
  /** Lo que rehace: el comando original, con la precondicion de estar deshecho. */
  hacer: Paso
  /** Lo que deshace: el inverso, con la precondicion de seguir como se dejo. */
  deshacer: Paso
  /** La operacion que dejo al elemento como esta: a la que apunta `deshace`. */
  opId: string
}

export type EntradaSinConfirmar = Omit<Entrada, 'opId'>

export interface ComandoDeHistorial {
  opId: string
  type: CommandType
  payload: Record<string, unknown> & Partial<MarcasDeDeshacer>
}

/**
 * Lo enviado que el servidor todavia no confirmo ni rechazo. Un deshacer o
 * rehacer guarda la entrada ENTERA, con el opId al que apunta: si un lock lo
 * rechaza, vuelve a la pila tal cual y se puede reintentar.
 */
type Pendiente =
  | { sentido: 'nuevo'; entrada: EntradaSinConfirmar | null; invalidaRehacer: boolean }
  | { sentido: 'deshacer' | 'rehacer'; entrada: Entrada }

/**
 * Comandos que editan en el lugar: su inverso es el mismo comando. Si no dan
 * entrada es porque no cambian nada (un blur en el panel), y eso no se lleva
 * el rehacer.
 */
const EN_SITIO: ReadonlySet<CommandType> = new Set<CommandType>([
  'MOVE_ELEMENT', 'RESIZE_ELEMENT',
  'RENAME_CLASS', 'UPDATE_ATTRIBUTE', 'UPDATE_RELATION', 'SET_ER_FLAG', 'SET_MODELING_DECISION',
])
export const editaEnSitio = (t: CommandType) => EN_SITIO.has(t)

/**
 * Las claves que el servidor sabe comparar de un atributo, un metodo y una
 * relacion (PrecondicionDeDeshacer.volcarAtributo / volcarRelacion). Un
 * `esperado` con otra clave no coincidiria nunca.
 */
const CAMPOS_ATRIBUTO = ['name', 'dataType', 'visibility', 'length', 'pk', 'required', 'unique',
  'defaultValue', 'ordinal', 'multivalued', 'derived', 'partialKey', 'minValue', 'maxValue'] as const
const CAMPOS_METODO = ['name', 'returnType', 'visibility'] as const
const CAMPOS_RELACION = ['kind', 'name', 'sourceRole', 'targetRole', 'sourceMultiplicity',
  'targetMultiplicity', 'identifying', 'sourceParticipation', 'targetParticipation'] as const

/**
 * El inverso de un comando, calculado ANTES de enviarlo, con su etiqueta.
 *
 * null si no hay nada que deshacer (el objetivo no existe o el comando lo deja
 * igual) o si no se sabe: SET_MODELING_DECISION necesita la decision vigente,
 * que no vive en el modelo del canvas (ver entradaDeDecision), y un lote que
 * la trae adentro tampoco se deshace.
 */
export function entradaDe(
  estado: EstadoModelo, type: CommandType, p: Record<string, unknown>,
): EntradaSinConfirmar | null {
  const clase = (id: unknown) => estado.classes.find((c) => c.id === id)
  const atributo = (id: unknown): [UmlAttribute, UmlClass] | null => {
    for (const c of estado.classes) {
      const a = c.attributes.find((x) => x.id === id)
      if (a) return [a, c]
    }
    return null
  }
  const metodo = (id: unknown): [UmlMethod, UmlClass] | null => {
    for (const c of estado.classes) {
      const m = c.methods.find((x) => x.id === id)
      if (m) return [m, c]
    }
    return null
  }
  const relacion = (id: unknown) => estado.relationships.find((r) => r.id === id)
  const extremos = (r: UmlRelationship | undefined) =>
    r ? `${clase(r.sourceClassId)?.name ?? '?'}–${clase(r.targetClassId)?.name ?? '?'}` : ''

  switch (type) {
    case 'MOVE_ELEMENT':
    case 'RESIZE_ELEMENT': {
      const base = { elementId: p.elementId, elementKind: p.elementKind }
      const prev = estado.layout[p.elementId as string]
      if (type === 'MOVE_ELEMENT' && Array.isArray(p.waypoints)) {
        // Una linea sin quiebres guardados es una lista vacia: asi la compara
        // el servidor, y deshacer el primer quiebre la vuelve a recta.
        return enSitio(type, `acomodar la linea ${extremos(relacion(p.elementId))}`, base,
          { waypoints: (prev?.waypoints ?? []).map(punto) },
          { waypoints: (p.waypoints as Punto[]).map(punto) })
      }
      if (!prev) return null
      const nombre = clase(p.elementId)?.name ?? 'el elemento'
      return type === 'MOVE_ELEMENT'
        ? enSitio(type, `mover ${nombre}`, base, { x: prev.x, y: prev.y }, { x: p.x, y: p.y })
        : enSitio(type, `redimensionar ${nombre}`, base,
          { width: prev.width, height: prev.height }, { width: p.width, height: p.height })
    }

    case 'RENAME_CLASS': {
      const c = clase(p.classId)
      if (!c) return null
      return enSitio(type, `renombrar ${c.name} a ${String(p.name)}`, { classId: p.classId },
        { name: c.name }, { name: p.name })
    }

    case 'UPDATE_ATTRIBUTE': {
      const encontrado = atributo(p.attributeId)
      if (!encontrado) return null
      const [a, c] = encontrado
      const { antes, despues } = soloLoCambiado(p, 'attributeId', a as unknown as Record<string, unknown>)
      return enSitio(type, `editar ${c.name}.${a.name}`, { attributeId: p.attributeId }, antes, despues)
    }

    case 'UPDATE_RELATION': {
      const r = relacion(p.relationId)
      if (!r) return null
      const { antes, despues } = soloLoCambiado(p, 'relationId', r as unknown as Record<string, unknown>)
      return enSitio(type, `editar la relacion ${extremos(r)}`, { relationId: p.relationId }, antes, despues)
    }

    case 'SET_ER_FLAG': {
      // Cada bandera viaja con su propia clave (ver CommandApplier): se
      // recuerda con la misma para que el inverso sea el mismo comando.
      const clave = p.flag === 'erKind' ? 'erKind' : p.flag === 'inheritanceStrategy' ? 'strategy' : 'value'
      const base = { targetType: p.targetType, targetId: p.targetId, flag: p.flag }
      const nuevo = p[clave] ?? (clave === 'value' ? true : null)   // el servidor asume true
      if (p.targetType === 'CLASS') {
        const c = clase(p.targetId)
        if (!c) return null
        const vigente = clave === 'erKind' ? c.erKind : clave === 'strategy' ? c.inheritanceStrategy : c.weak
        const que = clave === 'strategy' ? 'la herencia' : String(p.flag)
        return enSitio(type, `cambiar ${que} de ${c.name}`, base, { [clave]: vigente }, { [clave]: nuevo })
      }
      const encontrado = atributo(p.targetId)
      if (!encontrado) return null
      const [a, c] = encontrado
      const vigente = (a as unknown as Record<string, unknown>)[p.flag as string] ?? false
      return enSitio(type, `marcar ${String(p.flag)} en ${c.name}.${a.name}`, base,
        { value: vigente }, { value: nuevo })
    }

    // ------------------------------------------ CREAR Y BORRAR (ADR-030)

    // Deshacer crear es borrar, pero solo si la clase sigue vacia como la
    // deje: si otro le agrego algo, borrarla se llevaria su trabajo.
    case 'CREATE_CLASS':
      return {
        etiqueta: `crear ${String(p.name)}`,
        hacer: { type, payload: { ...p, esperado: { eliminada: true } } },
        deshacer: { type: 'DELETE_CLASS', payload: {
          classId: p.classId,
          esperado: { name: p.name, attributeIds: [], methodIds: [], relationIds: [] },
        } },
      }

    // Borrar destruye los atributos y metodos en el servidor: lo que permite
    // deshacerlo es lo que el cliente ve AHORA, antes de mandar el borrado.
    case 'DELETE_CLASS': {
      const c = clase(p.classId)
      if (!c) return null
      const relaciones = estado.relationships.filter(
        (r) => r.sourceClassId === c.id || r.targetClassId === c.id)
      return {
        etiqueta: `borrar ${c.name}`,
        hacer: { type, payload: { classId: c.id, esperado: contenidoDe(c, relaciones) } },
        deshacer: lote(`Deshacer borrar ${c.name}`, restaurarClase(estado, c, relaciones)),
      }
    }

    case 'ADD_ATTRIBUTE':
      return {
        etiqueta: `agregar ${nombreDe(clase(p.classId))}.${String(p.name)}`,
        hacer: { type, payload: { ...p, esperado: { existe: false } } },
        deshacer: { type: 'REMOVE_ATTRIBUTE',
          payload: { attributeId: p.attributeId, esperado: elegir(p, CAMPOS_ATRIBUTO) } },
      }

    case 'REMOVE_ATTRIBUTE': {
      const encontrado = atributo(p.attributeId)
      if (!encontrado) return null
      const [a, c] = encontrado
      return {
        etiqueta: `quitar ${c.name}.${a.name}`,
        hacer: { type, payload: { attributeId: a.id, esperado: elegir(a, CAMPOS_ATRIBUTO) } },
        deshacer: agregarAtributo(a),
      }
    }

    case 'ADD_METHOD':
      return {
        etiqueta: `agregar ${nombreDe(clase(p.classId))}.${String(p.name)}()`,
        hacer: { type, payload: { ...p, esperado: { existe: false } } },
        deshacer: { type: 'REMOVE_METHOD',
          payload: { methodId: p.methodId, esperado: elegir(p, CAMPOS_METODO) } },
      }

    case 'REMOVE_METHOD': {
      const encontrado = metodo(p.methodId)
      if (!encontrado) return null
      const [m, c] = encontrado
      return {
        etiqueta: `quitar ${c.name}.${m.name}()`,
        hacer: { type, payload: { methodId: m.id, esperado: elegir(m, CAMPOS_METODO) } },
        deshacer: agregarMetodo(m),
      }
    }

    case 'CREATE_RELATION': {
      const nueva = { sourceClassId: p.sourceClassId, targetClassId: p.targetClassId } as UmlRelationship
      return {
        etiqueta: `relacionar ${extremos(nueva)}`,
        hacer: { type, payload: { ...p, esperado: { eliminada: true } } },
        deshacer: { type: 'DELETE_RELATION',
          payload: { relationId: p.relationId, esperado: elegir(p, CAMPOS_RELACION) } },
      }
    }

    case 'DELETE_RELATION': {
      const r = relacion(p.relationId)
      if (!r) return null
      const pasos = restaurarRelacion(estado, r)
      return {
        etiqueta: `borrar la relacion ${extremos(r)}`,
        hacer: { type, payload: { relationId: r.id, esperado: elegir(r, CAMPOS_RELACION) } },
        deshacer: pasos.length === 1 ? pasos[0] : lote(`Deshacer borrar la relacion ${extremos(r)}`, pasos),
      }
    }

    // ------------------------------------------------ LOTE (ADR-030)

    // El inverso de cada cambio se calcula sobre el estado que el cambio
    // encontro --el que dejaron los anteriores del mismo lote--, asi que se
    // pliega con el mismo espejo que usa el canvas. Deshacer es aplicar esos
    // inversos al reves; todo o nada, como el lote original.
    case 'BATCH': {
      const comandos = (p.comandos ?? []) as Paso[]
      const hacer: Paso[] = []
      const deshacer: Paso[] = []
      let est = estado
      for (const c of comandos) {
        const e = entradaDe(est, c.type, c.payload)
        if (!e) {
          if (editaEnSitio(c.type) && c.type !== 'SET_MODELING_DECISION') {
            // No cambia nada: no hay que deshacerlo, pero sigue en el lote.
            est = plegar(est, c)
            continue
          }
          return null
        }
        hacer.push(...aplanar(e.hacer))
        deshacer.unshift(...aplanar(e.deshacer))
        est = plegar(est, c)
      }
      if (hacer.length === 0) return null
      const descripcion = String(p.descripcion || 'lote')
      return {
        etiqueta: descripcion,
        hacer: lote(descripcion, hacer),
        deshacer: lote(`Deshacer: ${descripcion}`, deshacer),
      }
    }

    // Necesita la decision vigente: entradaDeDecision.
    case 'SET_MODELING_DECISION':
    // Confirmar la base deja constancia de que en ESE momento se reviso ESE
    // DDL (ADR-032). Lo contrario de confirmar no es desconfirmar --el hecho
    // ocurrio-- sino volver a confirmar con el esquema que haya quedado.
    case 'CONFIRM_SCHEMA':
      return null
  }
}

/**
 * La decision vigente no esta en EstadoModelo (las ambiguedades se piden
 * aparte), asi que quien la conoce la pasa. `antes` es la lectura que regia,
 * aunque fuera la por defecto: deshacer la deja escrita explicita, que da el
 * mismo esquema (degradar, declarado en ADR-027).
 */
export function entradaDeDecision(
  elementId: string, ambiguityKind: string, antes: string, despues: string, etiqueta: string,
): EntradaSinConfirmar | null {
  return antes === despues ? null
    : enSitio('SET_MODELING_DECISION', etiqueta, { elementId, ambiguityKind }, { chosen: antes }, { chosen: despues })
}

// ------------------------------------------------------------- AYUDAS

/** Una edicion en el lugar: el mismo comando, con el valor de un lado y el otro como esperado. */
function enSitio(type: CommandType, etiqueta: string, base: Record<string, unknown>,
                 antes: EstadoEsperado, despues: EstadoEsperado): EntradaSinConfirmar | null {
  if (iguales(antes, despues)) return null
  return {
    etiqueta,
    hacer: { type, payload: { ...base, ...despues, esperado: antes } },
    deshacer: { type, payload: { ...base, ...antes, esperado: despues } },
  }
}

/**
 * De un UPDATE_*, solo los campos que el comando trae: son los unicos que el
 * undo revierte y los unicos que el servidor compara. Si otro cambio OTRO
 * campo del mismo elemento, lo suyo queda.
 */
function soloLoCambiado(p: Record<string, unknown>, id: string, vigente: Record<string, unknown>) {
  const antes: EstadoEsperado = {}
  const despues: EstadoEsperado = {}
  for (const [k, v] of Object.entries(p)) {
    if (k === id || k === 'deshace' || k === 'esperado') continue
    antes[k] = vigente[k] ?? null
    despues[k] = v ?? null
  }
  return { antes, despues }
}

/** Lo que el servidor mira antes de borrar: si cambio, borrar pisaria a alguien. */
function contenidoDe(c: UmlClass, relaciones: UmlRelationship[]): EstadoEsperado {
  return {
    name: c.name,
    attributeIds: c.attributes.map((a) => a.id).sort(),
    methodIds: c.methods.map((m) => m.id).sort(),
    relationIds: relaciones.map((r) => r.id).sort(),
  }
}

/**
 * Volver a crear la clase borrada con los MISMOS ids: asi las decisiones de
 * mapeo y cualquier referencia siguen apuntando a ella. La clase va primero
 * (sin ella no hay donde agregar), los atributos padres antes que sus hijos, y
 * las relaciones al final, cuando sus dos extremos ya existen.
 */
function restaurarClase(estado: EstadoModelo, c: UmlClass, relaciones: UmlRelationship[]): Paso[] {
  const l = estado.layout[c.id]
  const pasos: Paso[] = [{ type: 'CREATE_CLASS', payload: {
    classId: c.id, name: c.name, stereotype: c.stereotype ?? null, isAbstract: c.isAbstract,
    weak: c.weak, erKind: c.erKind,
    x: l?.x ?? 200, y: l?.y ?? 200, width: l?.width ?? 170, height: l?.height ?? 60,
    esperado: { eliminada: true },
  } }]
  if (c.inheritanceStrategy !== 'JOINED') {
    pasos.push({ type: 'SET_ER_FLAG', payload: {
      targetType: 'CLASS', targetId: c.id, flag: 'inheritanceStrategy', strategy: c.inheritanceStrategy,
    } })
  }
  const padresPrimero = [...c.attributes].sort((a, b) =>
    Number(!!a.parentAttributeId) - Number(!!b.parentAttributeId) || a.ordinal - b.ordinal)
  pasos.push(...padresPrimero.map(agregarAtributo))
  pasos.push(...c.methods.map(agregarMetodo))
  for (const r of relaciones) pasos.push(...restaurarRelacion(estado, r))
  return pasos
}

function agregarAtributo(a: UmlAttribute): Paso {
  return { type: 'ADD_ATTRIBUTE', payload: {
    classId: a.classId, attributeId: a.id, name: a.name, dataType: a.dataType, visibility: a.visibility,
    length: a.length ?? null, pk: a.pk, required: a.required, unique: a.unique,
    defaultValue: a.defaultValue ?? null, ordinal: a.ordinal, multivalued: a.multivalued,
    derived: a.derived, partialKey: a.partialKey, parentAttributeId: a.parentAttributeId ?? null,
    minValue: a.minValue ?? null, maxValue: a.maxValue ?? null,
    esperado: { existe: false },
  } }
}

function agregarMetodo(m: UmlMethod): Paso {
  return { type: 'ADD_METHOD', payload: {
    classId: m.classId, methodId: m.id, name: m.name, returnType: m.returnType, params: m.params,
    visibility: m.visibility, ordinal: m.ordinal,
    esperado: { existe: false },
  } }
}

/** La relacion con su mismo id y, si tenia, sus quiebres: borrarla se los llevo. */
function restaurarRelacion(estado: EstadoModelo, r: UmlRelationship): Paso[] {
  const pasos: Paso[] = [{ type: 'CREATE_RELATION', payload: {
    relationId: r.id, sourceClassId: r.sourceClassId, targetClassId: r.targetClassId, kind: r.kind,
    name: r.name ?? null, sourceRole: r.sourceRole ?? null, targetRole: r.targetRole ?? null,
    sourceMultiplicity: r.sourceMultiplicity ?? null, targetMultiplicity: r.targetMultiplicity ?? null,
    identifying: r.identifying, sourceParticipation: r.sourceParticipation ?? null,
    targetParticipation: r.targetParticipation ?? null, degree: r.degree,
    esperado: { eliminada: true },
  } }]
  const quiebres = estado.layout[r.id]?.waypoints ?? []
  if (quiebres.length > 0) {
    pasos.push({ type: 'MOVE_ELEMENT',
      payload: { elementId: r.id, elementKind: 'RELATION', waypoints: quiebres.map(punto) } })
  }
  return pasos
}

/**
 * Un lote con `esperado` vacio: el servidor exige `esperado` junto a
 * `deshace`, y la precondicion real va en cada cambio de adentro.
 */
function lote(descripcion: string, comandos: Paso[]): Paso {
  return { type: 'BATCH', payload: { descripcion, esperado: {}, comandos } }
}

/** Un BATCH no puede contener otro (ADR-016): el inverso anidado se abre. */
function aplanar(paso: Paso): Paso[] {
  return paso.type === 'BATCH' ? (paso.payload.comandos as Paso[]) : [paso]
}

function plegar(estado: EstadoModelo, c: Paso): EstadoModelo {
  return aplicarOperacion(estado, {
    opId: '', seq: 0, type: c.type, origin: 'MOUSE', payload: c.payload, userId: null, ts: '',
  })
}

function elegir(origen: object, campos: readonly string[]): EstadoEsperado {
  const o = origen as Record<string, unknown>
  const r: EstadoEsperado = {}
  for (const k of campos) if (k in o) r[k] = o[k] ?? null
  return r
}

const nombreDe = (c: UmlClass | undefined) => c?.name ?? '?'

const punto = (p: Punto): Punto => ({ x: p.x, y: p.y })

const iguales = (a: EstadoEsperado, b: EstadoEsperado) => JSON.stringify(a) === JSON.stringify(b)

// ------------------------------------------------------------ PILAS

export class HistorialLocal {
  private pilaDeshacer: Entrada[] = []
  private pilaRehacer: Entrada[] = []
  private pendientes = new Map<string, Pendiente>()

  /**
   * @param tope     cuantos pasos se recuerdan; los mas viejos se olvidan
   * @param onCambio para que la interfaz redibuje los botones
   */
  constructor(private tope = 100, private onCambio: () => void = () => {}) {}

  /**
   * Una edicion propia salio al bus. `entrada` es null si no se sabe deshacer:
   * igual se registra, porque al confirmarse invalida el rehacer, como en
   * cualquier editor.
   *
   * @param invalidaRehacer false para una edicion que no cambia nada: el
   *        panel manda un UPDATE en cada blur, y sin esto hacer clic afuera
   *        despues de deshacer se llevaba el rehacer
   */
  enviado(opId: string, entrada: EntradaSinConfirmar | null, invalidaRehacer = true) {
    this.pendientes.set(opId, { sentido: 'nuevo', entrada, invalidaRehacer })
  }

  /**
   * Llego una operacion aplicada. Si es propia, pasa a su pila.
   * Las ajenas no tocan nada: no invalidan el rehacer, porque `esperado`
   * ya protege de pisarlas.
   */
  confirmar(opId: string): boolean {
    const p = this.pendientes.get(opId)
    if (!p) return false
    this.pendientes.delete(opId)
    if (p.sentido === 'nuevo') {
      if (p.invalidaRehacer) this.pilaRehacer = []
      if (p.entrada) this.apilar(this.pilaDeshacer, { ...p.entrada, opId })
    } else {
      const destino = p.sentido === 'deshacer' ? this.pilaRehacer : this.pilaDeshacer
      this.apilar(destino, { ...p.entrada, opId })
    }
    this.onCambio()
    return true
  }

  /**
   * El servidor rechazo algo propio.
   *
   * @return el mensaje a mostrar si era un deshacer o rehacer; null si era una
   *         edicion comun (esa ya la muestra quien la envio)
   */
  rechazar(r: CommandRejection): string | null {
    const p = this.pendientes.get(r.opId)
    if (!p) return null
    this.pendientes.delete(r.opId)
    if (p.sentido === 'nuevo') return null

    const verbo = p.sentido === 'deshacer' ? 'deshacer' : 'rehacer'
    // Un lock es transitorio: el paso se conserva para reintentarlo. Todo lo
    // demas (otro lo cambio, se borro) no va a mejorar: se descarta, y se dice.
    if (r.reason === 'LOCKED_BY_OTHER') {
      const origen = p.sentido === 'deshacer' ? this.pilaDeshacer : this.pilaRehacer
      origen.push(p.entrada)
    }
    this.onCambio()
    return `No se pudo ${verbo} "${p.entrada.etiqueta}": ${r.message}`
  }

  /** Arma el comando que revierte el ultimo paso propio, o null si no hay. */
  deshacer(nuevoOpId: string): ComandoDeHistorial | null {
    const e = this.pilaDeshacer.pop()
    if (!e) return null
    this.pendientes.set(nuevoOpId, { sentido: 'deshacer', entrada: e })
    this.onCambio()
    return { opId: nuevoOpId, type: e.deshacer.type, payload: { ...e.deshacer.payload, deshace: e.opId } }
  }

  /** Rehacer es deshacer el deshacer: apunta al opId del undo. */
  rehacer(nuevoOpId: string): ComandoDeHistorial | null {
    const e = this.pilaRehacer.pop()
    if (!e) return null
    this.pendientes.set(nuevoOpId, { sentido: 'rehacer', entrada: e })
    this.onCambio()
    return { opId: nuevoOpId, type: e.hacer.type, payload: { ...e.hacer.payload, deshace: e.opId } }
  }

  proximoDeshacer(): string | null { return this.pilaDeshacer[this.pilaDeshacer.length - 1]?.etiqueta ?? null }
  proximoRehacer(): string | null { return this.pilaRehacer[this.pilaRehacer.length - 1]?.etiqueta ?? null }

  /** Pasar a Observador: lo recordado ya no se puede aplicar. */
  vaciar() {
    this.pilaDeshacer = []
    this.pilaRehacer = []
    this.pendientes.clear()
    this.onCambio()
  }

  private apilar(pila: Entrada[], e: Entrada) {
    pila.push(e)
    if (pila.length > this.tope) pila.shift()
  }
}
