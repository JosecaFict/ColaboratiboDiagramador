import { v4 as uuid } from 'uuid'
import type { EstadoModelo } from '../canvas/applyOperation'
import type { ComandoAceptadoIA } from './tipos'

/**
 * Arreglos de un hallazgo estructural que no necesitan al modelo: el usuario elige
 * una opcion y el panel arma el comando exacto contra el diagrama real. El modelo
 * local, ante "corrige X", inventaba relaciones inexistentes (turismo, 2026-11-08).
 */
export interface ArregloDirecto {
  etiqueta: string
  cambios: ComandoAceptadoIA[]
}

const normal = (s: string) => s.normalize('NFD').replace(/\p{M}/gu, '').toLowerCase()
// Las palabras de "forma" (Detalle, Asignacion...) no indican de que concepto se trata.
const GENERICAS = new Set(['detalle', 'linea', 'item', 'asignacion', 'registro', 'tipo', 'estado'])
const palabras = (nombre: string) => nombre.split(/(?=[A-Z])/).map(normal)
  .filter((p) => p.length > 3 && !GENERICAS.has(p))

export function arreglosDirectos(hallazgo: string, modelo: EstadoModelo): ArregloDirecto[] {
  const porNombre = (n: string) => modelo.classes.find((c) => c.name === n)
  const nombre = (id: string) => modelo.classes.find((c) => c.id === id)?.name ?? '?'
  const vecinas = (id: string) => new Set(modelo.relationships.flatMap((r) =>
    r.sourceClassId === id ? [r.targetClassId] : r.targetClassId === id ? [r.sourceClassId] : []))

  const crear = (origenId: string, destinoId: string, mOrigen: string, mDestino: string): ComandoAceptadoIA => ({
    tipo: 'CREATE_RELATION',
    payload: { relationId: uuid(), sourceClassId: origenId, targetClassId: destinoId, kind: 'ASSOCIATION',
      sourceMultiplicity: mOrigen, targetMultiplicity: mDestino },
    resumen: `Crear relacion ASSOCIATION entre ${nombre(origenId)} y ${nombre(destinoId)} [${mOrigen}] — [${mDestino}]`,
  })

  // Clase asociativa o suelta: falta un extremo. Se ofrecen las clases no conectadas,
  // primero las que comparten una palabra con su nombre (AsignacionServicio -> ServicioTuristico).
  const sinExtremo = hallazgo.match(/clase asociativa (\w+) no conecta/i)
    ?? hallazgo.match(/posible clase asociativa (\w+) solo tiene/i)
    ?? hallazgo.match(/Clase desconectada: (\w+)/i)
    ?? hallazgo.match(/La clase (\w+) queda desconectada/i)
  if (sinExtremo) {
    const clase = porNombre(sinExtremo[1])
    if (!clase) return []
    const ya = vecinas(clase.id)
    const propias = palabras(clase.name)
    // Despues de las que comparten una palabra, las que suelen completar ese tipo de clase:
    // un detalle referencia un concepto de catalogo; un titular, a una persona.
    const complemento = /^titular/i.test(clase.name) ? /^(cliente|usuario|persona|propietario)/i
      : /^(detalle|linea|item|asignacion)/i.test(clase.name) ? /^(producto|servicio|articulo|insumo|material|actividad)/i : null
    return modelo.classes
      .filter((c) => c.id !== clase.id && !ya.has(c.id))
      .map((c) => ({ c, afinidad: palabras(c.name).some((p) => propias.includes(p)) ? 0
        : complemento?.test(c.name) ? 1 : 2 }))
      .sort((a, b) => a.afinidad - b.afinidad || a.c.name.localeCompare(b.c.name))
      .map(({ c }) => ({ etiqueta: `Conectar con ${c.name}`, cambios: [crear(clase.id, c.id, '0..*', '1')] }))
  }

  // Titular no identifica al titular mediante una asociacion con [Cliente]: una unica respuesta.
  const titular = hallazgo.match(/(\w+) no identifica al titular mediante una asociacion con \[(\w+)/)
  if (titular) {
    const t = porNombre(titular[1]), persona = porNombre(titular[2])
    return t && persona && !vecinas(t.id).has(persona.id)
      ? [{ etiqueta: `Conectar con ${persona.name}`, cambios: [crear(t.id, persona.id, '0..*', '1')] }] : []
  }

  // DetalleReserva no identifica su cabecera Reserva: una unica respuesta.
  const cabecera = hallazgo.match(/(\w+) no identifica su cabecera (\w+)/)
  if (cabecera) {
    const detalle = porNombre(cabecera[1]), dueno = porNombre(cabecera[2])
    return detalle && dueno ? [{ etiqueta: `Conectar con ${dueno.name}`, cambios: [crear(dueno.id, detalle.id, '1', '1..*')] }] : []
  }

  // Itinerario no identifica el paquete o la salida: el usuario elige el dueño.
  const dueno = hallazgo.match(/(\w+) no identifica el paquete o la salida/)
  if (dueno) {
    const hijo = porNombre(dueno[1])
    if (!hijo) return []
    return modelo.classes.filter((c) => /^(paquete|salida)/i.test(c.name) && !vecinas(hijo.id).has(c.id))
      .map((c) => ({ etiqueta: `Pertenece a ${c.name}`, cambios: [crear(c.id, hijo.id, '1', '0..*')] }))
  }

  // Reserva no permite multiples viajeros: la relacion directa pasa a 1..* junto al viajero.
  const viajeros = hallazgo.match(/(\w+) no permite representar multiples viajeros/)
  if (viajeros) {
    const reserva = porNombre(viajeros[1])
    if (!reserva) return []
    return modelo.relationships.flatMap((r) => {
      const otroId = r.sourceClassId === reserva.id ? r.targetClassId : r.targetClassId === reserva.id ? r.sourceClassId : null
      if (!otroId || !/^(viajero|participante|pasajero)/i.test(nombre(otroId))) return []
      const reservaEsOrigen = r.sourceClassId === reserva.id
      const payload = { relationId: r.id,
        sourceMultiplicity: reservaEsOrigen ? '0..*' : '1..*', targetMultiplicity: reservaEsOrigen ? '1..*' : '0..*' }
      return [{ etiqueta: `Varios ${nombre(otroId)} por ${reserva.name}`, cambios: [{ tipo: 'UPDATE_RELATION' as const, payload,
        resumen: `Cambiar ${reserva.name} - ${nombre(otroId)}: una ${reserva.name} lleva 1..* ${nombre(otroId)}` }] }]
    })
  }
  return []
}
