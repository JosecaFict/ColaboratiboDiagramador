import type { Command, UmlClass, UmlRelationship } from '../contracts/command'

/**
 * Convierte una relación N:M en una CLASE INTERMEDIA de verdad, dibujada en el
 * canvas y conectada a las dos clases.
 *
 * POR QUÉ HACE FALTA. Una N:M ya derivaba su tabla puente, y contestar A-4 como
 * lectura OO le ponía `id` surrogado y le hacía emitir las cinco capas. Pero esa
 * clase asociativa vivía solo en lo DERIVADO: en el diagrama seguía habiendo una
 * línea entre dos cajas, así que no se podía arrastrar, ni conectarle una tercera
 * clase, ni —sobre todo— ponerle atributos. Y los atributos son el motivo por el
 * que una N:M se reifica: en este metamodelo un atributo pertenece siempre a una
 * clase (`classId`), nunca a una relación, así que `fechaPrestamo` no tenía dónde
 * ir mientras el préstamo fuera una línea.
 *
 * QUÉ EMITE: un solo `BATCH` con cuatro pasos —crear la clase, dos relaciones
 * 1:N hacia ella y borrar la N:M original—. Todos son comandos que ya estaban en
 * el catálogo cerrado (ADR-014): esto NO agrega un comando de transformación
 * masiva, es la misma edición que haría una persona con el mouse, en un sobre.
 * Y por ser un `BATCH` es atómica (ADR-016): si el nombre choca o alguien tiene
 * tomada una de las clases, no queda la clase nueva sin sus relaciones ni —peor—
 * el modelo sin la N:M original.
 *
 * LA DIRECCIÓN DE LAS DOS RELACIONES NO ES ARBITRARIA. La intermedia va del lado
 * "muchos" en las dos, porque la Regla 3 pone la FK en ese lado: así las dos
 * claves foráneas caen en la tabla intermedia, que es exactamente la tabla puente
 * que la N:M derivaba antes. Si se invirtiera, las FK irían a las clases y el
 * esquema diría otra cosa.
 */

export interface Reificacion {
  comando: Command | null
  /** Por qué no se pudo, para mostrarlo en el panel. null si salió bien. */
  motivo: string | null
  /** El id de la clase nueva, para poder seleccionarla al volver. */
  classId: string | null
}

export interface DatosDeReificacion {
  relacion: UmlRelationship
  origen: UmlClass | undefined
  destino: UmlClass | undefined
  nombre: string
  /**
   * Si la clase intermedia se identifica por sus dos FK en vez de por un `id`.
   *
   * Es la forma clásica de la tabla puente —`VENDE(NroV, CodP, cantidad, precio)`—
   * y la que impide que la misma nota venda dos veces el mismo producto sin que
   * nadie agregue un UNIQUE a mano. Se consigue marcando la clase como DÉBIL y
   * sus dos relaciones como IDENTIFICADORAS (Regla 11).
   */
  pkCompuesta: boolean
  /** Dónde cae la caja nueva, ya en coordenadas del canvas. */
  x: number
  y: number
  diagramId: string
  baseSeq: number
  /** Nombres de clase que ya existen, para no mandar un lote que se va a rechazar. */
  nombresUsados: string[]
  nuevoId: () => string
}

/** Una N:M es la única que se puede reificar: es la única que necesita tabla aparte. */
export function esNaM(relacion: UmlRelationship): boolean {
  if (relacion.kind === 'GENERALIZATION') return false
  return muchos(relacion.sourceMultiplicity) && muchos(relacion.targetMultiplicity)
}

function muchos(multiplicidad: string | null | undefined): boolean {
  return Boolean(multiplicidad && multiplicidad.includes('*'))
}

/**
 * El nombre que se propone, que es el que el usuario va a querer el 90 % de las
 * veces: el de la relación si tiene uno ("Prestamo"), y si no, los dos nombres
 * pegados ("EstudianteLibros").
 */
export function nombreSugerido(relacion: UmlRelationship,
                               origen: UmlClass | undefined,
                               destino: UmlClass | undefined): string {
  const porRelacion = (relacion.name ?? '').trim()
  if (porRelacion) return pascal(porRelacion)
  return pascal(origen?.name ?? 'Clase') + pascal(destino?.name ?? 'Clase')
}

function pascal(texto: string): string {
  return texto
    .split(/[^0-9A-Za-zÁÉÍÓÚÜÑáéíóúüñ]+/)
    .filter(Boolean)
    .map((p) => p.charAt(0).toLocaleUpperCase() + p.slice(1))
    .join('')
}

export function construirReificacion(datos: DatosDeReificacion): Reificacion {
  const { relacion, origen, destino, nombre, nombresUsados, nuevoId } = datos

  if (!origen || !destino) {
    return falla('No se encuentran las dos clases de la relacion.')
  }
  if (!esNaM(relacion)) {
    return falla('Solo una relacion N:M se convierte en clase intermedia: '
      + 'ponele 0..* o 1..* en los dos extremos.')
  }
  const limpio = nombre.trim()
  if (!limpio) {
    return falla('La clase intermedia necesita un nombre.')
  }
  if (nombresUsados.some((n) => n.toLocaleLowerCase() === limpio.toLocaleLowerCase())) {
    // El backend rechazaria el lote entero por el nombre repetido; decirlo aca
    // convierte "no se aplico nada" en una correccion de una palabra.
    return falla(`Ya hay una clase llamada "${limpio}".`)
  }

  const classId = nuevoId()
  const reflexiva = relacion.sourceClassId === relacion.targetClassId

  const comandos: { type: string; payload: Record<string, unknown> }[] = [
    {
      type: 'CREATE_CLASS',
      payload: {
        classId,
        name: limpio,
        stereotype: null,
        // Entre las dos cajas y un poco abajo: es donde la dibuja cualquiera a
        // mano, y donde no tapa la linea que la origino.
        x: datos.x,
        y: datos.y,
        width: 170,
        height: 90,
      },
    },
    relacionHacia(classId, relacion.sourceClassId,
      reflexiva ? (relacion.sourceRole ?? 'origen') : relacion.sourceRole,
      datos.pkCompuesta, nuevoId),
    relacionHacia(classId, relacion.targetClassId,
      reflexiva ? (relacion.targetRole ?? 'destino') : relacion.targetRole,
      datos.pkCompuesta, nuevoId),
    {
      // Al final: si algo de lo anterior falla, la N:M original sigue ahi. Un
      // modelo sin la relacion y sin la clase intermedia seria peor que no haber
      // hecho nada.
      type: 'DELETE_RELATION',
      payload: { relationId: relacion.id },
    },
  ]

  if (datos.pkCompuesta) {
    // Debil: su identidad son las FK de sus dos duenios, no un id propio. Va
    // despues de CREATE_CLASS y antes de las relaciones solo por legibilidad del
    // lote; el aplicador los corre en orden dentro de la misma transaccion.
    comandos.splice(1, 0, {
      type: 'SET_ER_FLAG',
      payload: { targetType: 'CLASS', targetId: classId, flag: 'weak', value: true },
    })
  }

  return {
    comando: {
      opId: nuevoId(),
      diagramId: datos.diagramId,
      baseSeq: datos.baseSeq,
      type: 'BATCH',
      origin: 'MOUSE',
      payload: {
        descripcion: `Clase intermedia ${limpio} entre ${origen.name} y ${destino.name}`,
        comandos,
      },
    },
    motivo: null,
    classId,
  }
}

/**
 * Una asociación `1` → `0..*` desde la clase original hacia la intermedia.
 *
 * El ROL viaja desde la relación que se está reificando porque de ahí sale el
 * nombre de la columna FK (Regla 3). En una N:M reflexiva es obligatorio: sin
 * roles distintos, las dos FK se llamarían igual y el esquema no compila.
 */
function relacionHacia(classId: string, claseOriginal: string, rol: string | null | undefined,
                       identificadora: boolean, nuevoId: () => string) {
  return {
    type: 'CREATE_RELATION',
    payload: {
      relationId: nuevoId(),
      sourceClassId: claseOriginal,
      targetClassId: classId,
      kind: 'ASSOCIATION',
      name: null,
      // La intermedia es el lado "muchos": ahi va la FK (Regla 3).
      sourceMultiplicity: '1',
      targetMultiplicity: '0..*',
      sourceRole: rol ?? null,
      targetRole: null,
      // Identificadora: esta FK entra en la PK de la intermedia (Regla 11).
      identifying: identificadora,
    },
  }
}

function falla(motivo: string): Reificacion {
  return { comando: null, motivo, classId: null }
}
