import { v4 as uuid } from 'uuid'
import type { Command, CommandType, RelationKind, Visibility } from '../contracts/command'
import type { Caja } from '../contracts/vision'

// ============================================================================
// DE LO QUE EL USUARIO CONFIRMO, A COMANDOS DEL BUS
//
// La propuesta de vision usa `ref` ("c1", "r1") porque NADA EXISTE TODAVIA; el
// modelo solo entiende UUID. Este modulo es el unico lugar donde se cruza esa
// frontera.
//
// Su entrada NO es la propuesta: es lo que quedo despues del panel de
// confirmacion. No tienen la misma forma, y esa diferencia es el punto -- el
// usuario le puso nombre a las cajas, descarto una entera, corrigio un tipo de
// relacion y destildo dos atributos mal leidos.
//
// Es una FUNCION PURA a proposito: entra una estructura, salen comandos. Se
// puede probar sin navegador, sin backend y sin imagen, que es lo unico que
// hace verificable la parte mas delicada del camino de vision.
// ============================================================================

export interface AtributoConfirmado {
  nombre: string
  dataType: string
  visibility?: Visibility
  pk?: boolean
  required?: boolean
}

export interface ClaseConfirmada {
  /** Ref DENTRO de la propuesta ("c1"). Las relaciones se refieren asi. */
  ref: string
  nombre: string
  /** Coordenadas del canvas que ya calculo el detector. */
  lienzo: Caja
  estereotipo?: string | null
  atributos: AtributoConfirmado[]
}

export interface RelacionConfirmada {
  ref: string
  origenRef: string
  destinoRef: string
  kind: RelationKind
  nombre?: string | null
  origenMultiplicidad?: string | null
  destinoMultiplicidad?: string | null
}

/** Lo que el panel deja despues de aceptar, corregir y descartar. */
export interface DeteccionConfirmada {
  clases: ClaseConfirmada[]
  relaciones: RelacionConfirmada[]
}

export interface Traduccion {
  /** El BATCH listo para el bus, o null si no quedo nada que aterrizar. */
  comando: Command | null
  /** Lo que no se pudo traducir, con el motivo. Se muestra, no se calla. */
  descartes: string[]
  /** Ref de la propuesta -> UUID acunado. Util para resaltar en el canvas. */
  idsPorRef: Record<string, string>
}

/** Mismo tope que `CommandApplier.MAX_POR_LOTE`. Si cambia alla, cambia aca. */
export const MAX_POR_LOTE = 500

interface ComandoDeLote {
  type: CommandType
  payload: Record<string, unknown>
}

/**
 * @param nuevoId inyectable solo para que los tests sean deterministas
 */
export function traducir(
  confirmada: DeteccionConfirmada,
  diagramId: string,
  baseSeq: number,
  nuevoId: () => string = uuid,
): Traduccion {
  const comandos: ComandoDeLote[] = []
  const descartes: string[] = []
  const idsPorRef: Record<string, string> = {}
  const nombresUsados = new Set<string>()

  // --- 1. Clases. Primero, porque CREATE_RELATION valida que existan. ---
  for (const clase of confirmada.clases) {
    const nombre = clase.nombre?.trim() ?? ''
    if (!nombre) {
      // Pasa cuando no hay nivel 2 y nadie escribio el nombre a mano. Inventar
      // "Clase1" seria meter basura en el modelo con la firma del usuario.
      descartes.push(`${clase.ref}: se descarto porque no tiene nombre.`)
      continue
    }
    const clave = nombre.toLocaleLowerCase()
    if (nombresUsados.has(clave)) {
      // El backend rechaza el nombre repetido y con el se cae el lote ENTERO.
      // Atajarlo aca convierte "no se aplico nada" en "esta clase no entro".
      descartes.push(`${clase.ref}: ya hay otra clase llamada "${nombre}" en este lote.`)
      continue
    }
    nombresUsados.add(clave)

    const classId = nuevoId()
    idsPorRef[clase.ref] = classId
    comandos.push({
      type: 'CREATE_CLASS',
      payload: {
        classId,
        name: nombre,
        stereotype: clase.estereotipo ?? null,
        // La disposicion que tenia en la imagen. Sin esto todo aterriza
        // apilado en el origen y hay que reacomodar el diagrama a mano, que
        // era justo el trabajo que leer la imagen venia a ahorrar.
        x: clase.lienzo.x,
        y: clase.lienzo.y,
        width: clase.lienzo.ancho,
        height: clase.lienzo.alto,
      },
    })

    // --- 2. Atributos, despues de SU clase. ---
    let ordinal = 0
    for (const atributo of clase.atributos) {
      const nombreAtributo = atributo.nombre?.trim() ?? ''
      if (!nombreAtributo) {
        descartes.push(`${clase.ref}: se salteo un atributo sin nombre.`)
        continue
      }
      comandos.push({
        type: 'ADD_ATTRIBUTE',
        payload: {
          attributeId: nuevoId(),
          classId,
          name: nombreAtributo,
          dataType: atributo.dataType || 'string',
          visibility: atributo.visibility ?? 'PRIVATE',
          pk: atributo.pk ?? false,
          required: atributo.required ?? false,
          ordinal: ordinal++,
        },
      })
    }
  }

  // --- 3. Relaciones, al final: sus dos extremos ya existen. ---
  for (const relacion of confirmada.relaciones) {
    const origen = idsPorRef[relacion.origenRef]
    const destino = idsPorRef[relacion.destinoRef]
    if (!origen || !destino) {
      // Un extremo quedo afuera. NO se inventa la clase faltante para
      // "salvar" la relacion: el usuario decidio que esa caja no iba.
      const falta = !origen ? relacion.origenRef : relacion.destinoRef
      descartes.push(`${relacion.ref}: se descarto porque ${falta} no entro al lote.`)
      continue
    }
    comandos.push({
      type: 'CREATE_RELATION',
      payload: {
        relationId: nuevoId(),
        sourceClassId: origen,
        targetClassId: destino,
        kind: relacion.kind,
        name: relacion.nombre ?? null,
        sourceMultiplicity: relacion.origenMultiplicidad ?? null,
        targetMultiplicity: relacion.destinoMultiplicidad ?? null,
      },
    })
  }

  if (!comandos.length) {
    descartes.push('No quedo nada que aterrizar.')
    return { comando: null, descartes, idsPorRef }
  }
  if (comandos.length > MAX_POR_LOTE) {
    // El backend lo rechazaria igual; decirlo aca evita mandar el payload.
    descartes.push(
      `El lote da ${comandos.length} comandos y el maximo es ${MAX_POR_LOTE}. ` +
      'Hay que aterrizar la imagen por partes.',
    )
    return { comando: null, descartes, idsPorRef }
  }

  return {
    comando: {
      opId: nuevoId(),
      diagramId,
      baseSeq,
      type: 'BATCH',
      // La modalidad de entrada queda registrada en el log de operaciones: el
      // mismo bus que el mouse, distinto origen (ADR-002).
      origin: 'IMAGE',
      payload: { comandos },
    },
    descartes,
    idsPorRef,
  }
}
