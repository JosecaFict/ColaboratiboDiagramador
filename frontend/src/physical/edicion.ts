import type { CommandType, RelationKind, UmlAttribute, UmlClass, UmlRelationship } from '../contracts/command'
import type { ColumnaFisica, TablaFisica } from './esquema'

/**
 * EDITAR LA VISTA FISICA ES EDITAR EL MODELO (ADR-032).
 *
 * Cada cambio en una tabla se traduce a un comando del catalogo que ya existe
 * sobre el elemento que produjo esa columna: renombrar `fecha_devolucion` es
 * un UPDATE_ATTRIBUTE sobre `FichaPrestamo.fechaDevolucion`, y poner un FK en
 * CASCADE es volver composicion la relacion. No hay una segunda verdad ni un
 * comando nuevo: al volver a Clases se ve el mismo cambio (ADR-002, ADR-003).
 *
 * Funciones PURAS a proposito: la pantalla solo decide que mostrar; que
 * comando corresponde a cada gesto se prueba aca, sin React.
 */

/** Un comando listo para el bus, y lo que significa en el modelo conceptual. */
export interface Cambio {
  type: CommandType
  payload: Record<string, unknown>
  /** En palabras del UML: "FichaPrestamo.fechaDevolucion pasa a ser obligatorio". */
  efecto: string
}

/** null = no hay nada que cambiar; `error` = lo escrito no se puede aplicar. */
export type Resultado = Cambio | { error: string } | null

export const esError = (r: Resultado): r is { error: string } => r !== null && 'error' in r

export type Extremo = 'SOURCE' | 'TARGET'

/**
 * Lo escrito como nombre de columna o tabla, en snake_case.
 *
 * Es solo para interpretar lo que se tipeo: el nombre real lo vuelve a
 * derivar el backend con la Regla 8 (palabras reservadas, 63 bytes).
 */
export function aSnake(texto: string): string {
  return texto
    .replace(/ñ/g, 'ni').replace(/Ñ/g, 'Ni')
    .normalize('NFD').replace(/[\u0300-\u036f]/g, '')
    .replace(/([a-z0-9])([A-Z])/g, '$1_$2')
    .replace(/[^A-Za-z0-9]+/g, '_')
    .replace(/_+/g, '_')
    .replace(/^_+|_+$/g, '')
    .toLowerCase()
}

/** fecha_devolucion -> fechaDevolucion: el nombre del atributo que da esa columna. */
export const aCamel = (snake: string): string =>
  snake.replace(/_([a-z0-9])/g, (_m, c: string) => c.toUpperCase())

/** ficha_prestamo -> FichaPrestamo: el nombre de la clase que da esa tabla. */
export const aPascal = (snake: string): string => {
  const camel = aCamel(snake)
  return camel.charAt(0).toUpperCase() + camel.slice(1)
}

// ------------------------------------------------------------------- TIPOS

/** Un tipo que se puede elegir para una columna, y el tipo UML que lo produce. */
export interface TipoFisico {
  /** Valor del selector: el tipo UML (o el nombre de la enumeracion). */
  uml: string
  /** Lo que se ve: el tipo de PostgreSQL. */
  etiqueta: string
}

/**
 * Espejo de TipoSql.java, leido al reves: se elige el tipo de PostgreSQL y se
 * guarda el tipo UML que lo produce. Sin `float`: el dinero va en numeric.
 */
export const TIPOS_FISICOS: TipoFisico[] = [
  { uml: 'string', etiqueta: 'varchar(n)' },
  { uml: 'text', etiqueta: 'text' },
  { uml: 'int', etiqueta: 'integer' },
  { uml: 'long', etiqueta: 'bigint' },
  { uml: 'decimal', etiqueta: 'numeric(19,2)' },
  { uml: 'boolean', etiqueta: 'boolean' },
  { uml: 'date', etiqueta: 'date' },
  { uml: 'datetime', etiqueta: 'timestamptz' },
  { uml: 'uuid', etiqueta: 'uuid' },
]

/** Los que admiten rango (Regla 9): mismo criterio que el panel de Clases. */
export const NUMERICOS = ['int', 'integer', 'long', 'decimal']

/** PostgreSQL no acepta un varchar mas largo. */
export const LARGO_MAXIMO = 10485760

/** El tipo UML normalizado a una opcion del selector (`integer` -> `int`). */
export function tipoDelSelector(dataType: string): string {
  const t = (dataType ?? '').toLowerCase()
  if (t === 'integer') return 'int'
  if (t === 'bool') return 'boolean'
  return TIPOS_FISICOS.some((x) => x.uml === t) ? t : dataType
}

// ---------------------------------------------------------------- COLUMNAS

const ref = (c: UmlClass, a: UmlAttribute) => `${c.name}.${a.name}`

/**
 * El atributo va con `classId` aunque el backend lo identifique por su id:
 * es lo que usa el bus para revisar el lock de la clase (nivel 2, ADR-004).
 */
const actualizar = (c: UmlClass, a: UmlAttribute, cambios: Record<string, unknown>) =>
  ({ attributeId: a.id, classId: c.id, ...cambios })

export function renombrarTabla(clase: UmlClass, texto: string): Resultado {
  const snake = aSnake(texto)
  if (!snake) return { error: 'La tabla necesita un nombre.' }
  const nombre = aPascal(snake)
  if (nombre === clase.name || aSnake(clase.name) === snake) return null
  return {
    type: 'RENAME_CLASS',
    payload: { classId: clase.id, name: nombre },
    efecto: `La clase ${clase.name} pasa a llamarse ${nombre}.`,
  }
}

export function renombrarColumna(clase: UmlClass, a: UmlAttribute, texto: string): Resultado {
  const snake = aSnake(texto)
  if (!snake) return { error: 'La columna necesita un nombre.' }
  // Si la columna no cambia, no se toca el atributo: renombrar "Año" a "anio"
  // le borraria la enie al UML sin que nadie lo pidiera.
  if (snake === aSnake(a.name)) return null
  const nombre = aCamel(snake)
  if (clase.attributes.some((x) => x.id !== a.id && aSnake(x.name) === snake)) {
    return { error: `${clase.name} ya tiene una columna ${snake}.` }
  }
  return {
    type: 'UPDATE_ATTRIBUTE',
    payload: actualizar(clase, a, { name: nombre }),
    efecto: `${ref(clase, a)} pasa a llamarse ${nombre}.`,
  }
}

/**
 * Cambiar el tipo. Si deja de ser numerico, el rango se borra en el MISMO
 * comando: el bus rechaza un rango sobre un texto (Regla 9), y mandarlo en dos
 * pasos dejaria el primero rechazado.
 */
export function cambiarTipo(clase: UmlClass, a: UmlAttribute, uml: string): Resultado {
  if (uml === tipoDelSelector(a.dataType)) return null
  const cambios: Record<string, unknown> = { dataType: uml }
  const teniaRango = a.minValue != null || a.maxValue != null
  if (teniaRango && !NUMERICOS.includes(uml)) {
    cambios.minValue = null
    cambios.maxValue = null
  }
  const etiqueta = TIPOS_FISICOS.find((t) => t.uml === uml)?.etiqueta ?? `varchar con CHECK (${uml})`
  return {
    type: 'UPDATE_ATTRIBUTE',
    payload: actualizar(clase, a, cambios),
    efecto: `${ref(clase, a)} pasa a ser ${uml}: en la base, ${etiqueta}`
      + (cambios.minValue === null ? ', y pierde su rango.' : '.'),
  }
}

/** El largo de un varchar. Vacio = el de siempre, varchar(255). */
export function cambiarLargo(clase: UmlClass, a: UmlAttribute, texto: string): Resultado {
  const limpio = texto.trim()
  const largo = limpio === '' ? null : Number(limpio)
  if (largo !== null && (!Number.isInteger(largo) || largo < 1 || largo > LARGO_MAXIMO)) {
    return { error: `El largo tiene que ser un entero entre 1 y ${LARGO_MAXIMO}.` }
  }
  if (largo === (a.length ?? null)) return null
  return {
    type: 'UPDATE_ATTRIBUTE',
    payload: actualizar(clase, a, { length: largo }),
    efecto: `${ref(clase, a)} queda como varchar(${largo ?? 255}).`,
  }
}

export function cambiarObligatorio(clase: UmlClass, a: UmlAttribute, obligatorio: boolean): Resultado {
  if (obligatorio === a.required) return null
  return {
    type: 'UPDATE_ATTRIBUTE',
    payload: actualizar(clase, a, { required: obligatorio }),
    efecto: `${ref(clase, a)} ${obligatorio ? 'pasa a ser obligatorio (NOT NULL)' : 'deja de ser obligatorio'}.`,
  }
}

export function cambiarUnico(clase: UmlClass, a: UmlAttribute, unico: boolean): Resultado {
  if (unico === a.unique) return null
  return {
    type: 'UPDATE_ATTRIBUTE',
    payload: actualizar(clase, a, { unique: unico }),
    efecto: `${ref(clase, a)} ${unico ? 'no puede repetirse (UNIQUE)' : 'puede repetirse'}.`,
  }
}

/** "" = sin limite de ese lado. Los numeros viajan como texto: el backend los valida. */
export function cambiarRango(clase: UmlClass, a: UmlAttribute, minimo: string, maximo: string): Resultado {
  const min = minimo.trim() === '' ? null : minimo.trim()
  const max = maximo.trim() === '' ? null : maximo.trim()
  for (const v of [min, max]) {
    if (v !== null && Number.isNaN(Number(v))) return { error: `"${v}" no es un numero.` }
  }
  if (min !== null && max !== null && Number(min) > Number(max)) {
    return { error: 'El minimo no puede ser mayor que el maximo.' }
  }
  const igual = (x: string | null, y: number | null | undefined) =>
    (x === null ? null : Number(x)) === (y ?? null)
  if (igual(min, a.minValue) && igual(max, a.maxValue)) return null
  const texto = [min !== null ? `>= ${min}` : null, max !== null ? `<= ${max}` : null]
    .filter(Boolean).join(' y ')
  return {
    type: 'UPDATE_ATTRIBUTE',
    payload: actualizar(clase, a, { minValue: min, maxValue: max }),
    efecto: texto ? `${ref(clase, a)} queda con CHECK ${texto}.` : `${ref(clase, a)} queda sin rango.`,
  }
}

export function quitarColumna(clase: UmlClass, a: UmlAttribute): Cambio {
  return {
    type: 'REMOVE_ATTRIBUTE',
    payload: { attributeId: a.id, classId: clase.id },
    efecto: `Se quita ${ref(clase, a)} del modelo.`,
  }
}

export function agregarColumna(clase: UmlClass, id: string, texto: string, uml: string,
                               obligatorio: boolean, unico: boolean): Resultado {
  const snake = aSnake(texto)
  if (!snake) return { error: 'La columna necesita un nombre.' }
  if (snake === 'id') return { error: 'El id ya lo pone la Regla 1: toda tabla lleva su id uuid.' }
  if (clase.attributes.some((x) => aSnake(x.name) === snake)) {
    return { error: `${clase.name} ya tiene una columna ${snake}.` }
  }
  const nombre = aCamel(snake)
  return {
    type: 'ADD_ATTRIBUTE',
    payload: {
      attributeId: id, classId: clase.id, name: nombre, dataType: uml,
      required: obligatorio, unique: unico, ordinal: clase.attributes.length,
    },
    efecto: `${clase.name} gana el atributo ${nombre}: ${uml}.`,
  }
}

// --------------------------------------------------------- CLAVES FORANEAS

const campoMultiplicidad = (e: Extremo) => (e === 'SOURCE' ? 'sourceMultiplicity' : 'targetMultiplicity')
const campoRol = (e: Extremo) => (e === 'SOURCE' ? 'sourceRole' : 'targetRole')
const otro = (e: Extremo): Extremo => (e === 'SOURCE' ? 'TARGET' : 'SOURCE')

/**
 * El nombre de la FK sale del ROL del extremo al que apunta (Regla 3):
 * `prestatario_id` es el rol "prestatario". Volver al nombre de la clase es
 * quitar el rol, no escribirlo: un rol igual a la clase no dice nada en el UML.
 */
export function renombrarFk(r: UmlRelationship, extremo: Extremo, apuntada: UmlClass,
                            texto: string): Resultado {
  const snake = aSnake(texto).replace(/_id$/, '')
  if (!snake) return { error: 'La clave foranea necesita un nombre.' }
  const actual = r[campoRol(extremo)]
  const porDefecto = aSnake(apuntada.name)
  if (snake === aSnake(actual || apuntada.name)) return null
  const rol = snake === porDefecto ? null : aCamel(snake)
  return {
    type: 'UPDATE_RELATION',
    payload: { relationId: r.id, [campoRol(extremo)]: rol },
    efecto: rol
      ? `El extremo ${apuntada.name} de la relacion toma el rol "${rol}": la columna queda ${snake}_id.`
      : `El extremo ${apuntada.name} de la relacion pierde su rol: la columna vuelve a ${porDefecto}_id.`,
  }
}

/**
 * NOT NULL en una FK es la multiplicidad del extremo apuntado: "1" o "0..1".
 *
 * Si la relacion no declaraba ninguna multiplicidad, la Regla 3 la leia como
 * 1:N. Se escriben las DOS para que siga siendo 1:N: con una sola, el otro
 * extremo dejaria de ser "muchos" y la relacion pasaria a 1:1 sin que nadie
 * lo pidiera.
 */
export function cambiarObligatorioFk(r: UmlRelationship, extremo: Extremo, apuntada: UmlClass,
                                     obligatoria: boolean): Resultado {
  const actual = r[campoMultiplicidad(extremo)] ?? null
  const nueva = obligatoria ? '1' : '0..1'
  if (actual === nueva) return null
  const payload: Record<string, unknown> = { relationId: r.id, [campoMultiplicidad(extremo)]: nueva }
  if (r[campoMultiplicidad(otro(extremo))] == null) payload[campoMultiplicidad(otro(extremo))] = '0..*'
  return {
    type: 'UPDATE_RELATION',
    payload,
    efecto: `El extremo ${apuntada.name} pasa a ${nueva}: `
      + (obligatoria ? 'la FK queda NOT NULL.' : 'la FK admite NULL.'),
  }
}

/** Lo que cada ON DELETE es en el UML: la regla de borrado ES el tipo de relacion. */
export const KIND_POR_ON_DELETE: Record<string, RelationKind> = {
  CASCADE: 'COMPOSITION',
  'SET NULL': 'AGGREGATION',
  RESTRICT: 'ASSOCIATION',
}

const NOMBRE_KIND: Record<string, string> = {
  COMPOSITION: 'composicion (rombo relleno): la parte no existe sin el todo',
  AGGREGATION: 'agregacion (rombo hueco): la parte sobrevive',
  ASSOCIATION: 'asociacion simple',
}

export function cambiarOnDelete(r: UmlRelationship, onDelete: string): Resultado {
  const kind = KIND_POR_ON_DELETE[onDelete]
  if (!kind) return { error: `ON DELETE ${onDelete} no corresponde a ningun tipo de relacion.` }
  if (kind === r.kind) return null
  return {
    type: 'UPDATE_RELATION',
    payload: { relationId: r.id, kind },
    efecto: `ON DELETE ${onDelete}: en Clases la relacion pasa a ser ${NOMBRE_KIND[kind]}.`,
  }
}

// ------------------------------------------------------------------- FILAS

/**
 * Que se puede editar de cada columna, y por que no cuando no se puede.
 *
 * Una columna es editable aca solo si sale de algo de ESTA tabla: un atributo
 * de su clase, o la FK de una asociacion. Lo demas lo produce otra parte del
 * modelo --la herencia, un valor embebido, la relacion identificadora-- y se
 * dice donde se cambia, en vez de ofrecer un control que el modelo no puede
 * expresar.
 */
export type Fila =
  | { tipo: 'atributo'; columna: ColumnaFisica; atributo: UmlAttribute }
  | { tipo: 'fk'; columna: ColumnaFisica; relacion: UmlRelationship; extremo: Extremo; apuntada: UmlClass }
  | { tipo: 'fija'; columna: ColumnaFisica; motivo: string }

export function clasificarFilas(tabla: TablaFisica, clases: UmlClass[],
                                relaciones: UmlRelationship[]): Fila[] {
  const clase = clases.find((c) => c.id === tabla.claseId) ?? null
  return tabla.columnas.map((columna): Fila => {
    if (!clase) {
      return { tipo: 'fija', columna,
        motivo: `Tabla derivada de ${tabla.origen}: se genera sola. Se cambia editando esa relacion o ese atributo en Clases.` }
    }
    if (columna.atributoId) {
      const duenia = clases.find((c) => c.attributes.some((a) => a.id === columna.atributoId))
      const atributo = duenia?.attributes.find((a) => a.id === columna.atributoId)
      if (!duenia || !atributo) {
        return { tipo: 'fija', columna, motivo: 'Su atributo ya no esta en el modelo: la tabla se esta actualizando.' }
      }
      if (duenia.id !== clase.id) {
        const porque = (duenia.stereotype ?? '').toLowerCase() === 'embeddable'
          ? `es del valor embebido ${duenia.name} (Regla 5), que pueden usar otras tablas`
          : `es de la subclase ${duenia.name}, guardada en esta tabla (SINGLE_TABLE)`
        return { tipo: 'fija', columna, motivo: `Viene de ${duenia.name}.${atributo.name}: ${porque}. Se edita en esa clase.` }
      }
      return { tipo: 'atributo', columna, atributo }
    }
    if (columna.relacionId && columna.extremo) {
      const relacion = relaciones.find((r) => r.id === columna.relacionId)
      const apuntadaId = relacion && (columna.extremo === 'SOURCE' ? relacion.sourceClassId : relacion.targetClassId)
      const apuntada = clases.find((c) => c.id === apuntadaId)
      if (relacion && apuntada) return { tipo: 'fk', columna, relacion, extremo: columna.extremo, apuntada }
    }
    if (columna.relacionId && columna.pk) {
      return { tipo: 'fija', columna,
        motivo: 'Es la clave de la tabla y apunta a otra: la hereda de su madre (Regla 4) o de su dueno (Regla 11). Se cambia con esa relacion en Clases.' }
    }
    if (columna.nombre === 'id' && columna.pk) {
      return { tipo: 'fija', columna, motivo: 'Identidad surrogada (Regla 1, ADR-007): toda tabla la lleva y no se edita.' }
    }
    return { tipo: 'fija', columna, motivo: 'La produce otra regla del mapeo (por ejemplo, el discriminador de SINGLE_TABLE).' }
  })
}
