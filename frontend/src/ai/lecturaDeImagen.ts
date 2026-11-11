import type { RelationKind } from '../contracts/command'
import type { DudaDeLectura, LecturaDeImagen } from '../contracts/imagenes'
import { traducir, type ClaseConfirmada, type DeteccionConfirmada, type RelacionConfirmada } from '../vision/traductor'

/**
 * De la lectura de un turno IMAGEN a los comandos del BATCH (contrato §5.3): el servidor NO arma
 * comandos, los arma el cliente con traductor.ts (sin cambiarlo) a partir de la deteccion y de lo
 * que el usuario corrigio. Las dudas son informativas: corregir una no bloquea aplicar, y una clase
 * sin nombre que nadie corrige simplemente no entra (el traductor la descarta y lo dice).
 */

/** Correcciones del usuario sobre una lectura, por ref de la propuesta ("c1", "r1"). */
export interface CorreccionesDeLectura {
  nombres: Record<string, string>
  tipos: Record<string, RelationKind>
  /** Refs descartados a mano. */
  descartados: Record<string, boolean>
}

export const SIN_CORRECCIONES: CorreccionesDeLectura = { nombres: {}, tipos: {}, descartados: {} }

/** Una multiplicidad vacia no es un valor: es "sin especificar". */
const multiplicidad = (t?: string): string | null => (t?.trim() ? t.trim() : null)

export function confirmarLectura(lectura: LecturaDeImagen, c: CorreccionesDeLectura = SIN_CORRECCIONES): DeteccionConfirmada {
  const { deteccion } = lectura
  const clases: ClaseConfirmada[] = deteccion.clases
    .filter((k) => !c.descartados[k.ref])
    .map((k) => ({
      ref: k.ref,
      nombre: (c.nombres[k.ref] ?? k.nombre ?? '').trim(),
      lienzo: k.lienzo,
      atributos: k.atributos.filter((a) => a.texto?.trim()).map((a) => ({ nombre: a.texto!.trim(), dataType: 'string' })),
    }))
    .filter((k) => k.nombre)
  const relaciones: RelacionConfirmada[] = deteccion.relaciones
    .filter((r) => !c.descartados[r.ref])
    .map((r) => ({
      ref: r.ref, origenRef: r.origenRef, destinoRef: r.destinoRef, kind: c.tipos[r.ref] ?? r.kind,
      origenMultiplicidad: multiplicidad(r.cardinalidadOrigen?.texto),
      destinoMultiplicidad: multiplicidad(r.cardinalidadDestino?.texto),
    }))
  return { clases, relaciones }
}

/** Los comandos del lote, listos para onAplicar. `comandos` vacio = no hay nada que aplicar. */
export function comandosDeLectura(lectura: LecturaDeImagen, c: CorreccionesDeLectura = SIN_CORRECCIONES) {
  const t = traducir(confirmarLectura(lectura, c), '0'.repeat(8) + '-0000-0000-0000-' + '0'.repeat(12), 0)
  const comandos = t.comando ? (t.comando.payload as { comandos: { type: string; payload: Record<string, unknown> }[] }).comandos : []
  return { comandos, descartes: t.descartes }
}

/** Resumen de una lectura para el mensaje de la IA cuando el servidor no mando texto. */
export function resumenDeLectura(lectura: LecturaDeImagen): string {
  const k = lectura.deteccion.clases.length
  const r = lectura.deteccion.relaciones.length
  const d = lectura.dudas.length + lectura.dudasOmitidas
  const base = `Leí ${k} clase${k === 1 ? '' : 's'} y ${r} relación${r === 1 ? '' : 'es'}`
  return d ? `${base}; ${d} cosa${d === 1 ? '' : 's'} con dudas.` : `${base}.`
}

/** Texto de una duda para la lista del chat. */
export function textoDeDuda(d: DudaDeLectura): string {
  const donde = d.elementoRef ? ` (${d.elementoRef})` : ''
  switch (d.tipo) {
    case 'SIN_LECTURA_DE_TEXTO': return 'No se pudo leer ningún texto de la imagen: los nombres hay que escribirlos a mano.'
    case 'CLASE_SIN_NOMBRE': return `Una clase quedó sin nombre${donde}.`
    case 'RELACION_SIN_TIPO': return `No se distinguió el tipo de una relación${donde}: quedó como asociación.`
    case 'TEXTO_VACIO': return `Un texto no se pudo leer${donde}.`
    case 'TEXTO_INCIERTO': return `Lectura incierta${donde}: «${d.leido ?? ''}».`
    case 'CLASE_POCO_CLARA': return `Una clase se ve poco clara${donde}.`
    case 'RELACION_POCO_CLARA': return `Una relación se ve poco clara${donde}.`
  }
}
