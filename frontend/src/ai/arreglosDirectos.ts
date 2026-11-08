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
  /** La opcion mas probable: viene preelegida, el usuario puede cambiarla antes de aplicar. */
  sugerido?: boolean
}

// Registros que se acumulan en el tiempo: del lado de uno de ellos va 0..*.
const REGISTRO = /^(prestamo|venta|compra|pedido|pago|reserva|movimiento|transferencia|cita|inscripcion|factura|cuota|control|tratamiento|aplicacion|vacunacion|diagnostico|consulta|evaluacion)/i

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
    // Turismo 2026-11-08: un paso del itinerario visita un destino o hace una actividad,
    // y el chat ofrecia antes Cancelacion y Pago.
    const complemento = /^titular/i.test(clase.name) ? /^(cliente|usuario|persona|propietario)/i
      : /^(detalle|paso|linea|item)(itinerario|ruta|recorrido)/i.test(clase.name) ? /^(destino|actividad|parada|lugar|servicio)/i
      : /^(detalle|linea|item|asignacion)/i.test(clase.name) ? /^(producto|servicio|articulo|insumo|material|actividad)/i : null
    // Los registros (pagos, cancelaciones, reservas...) casi nunca son el extremo que le falta a un detalle.
    return modelo.classes
      .filter((c) => c.id !== clase.id && !ya.has(c.id))
      .map((c) => ({ c, afinidad: palabras(c.name).some((p) => propias.includes(p)) ? 0
        : complemento?.test(c.name) ? 1 : REGISTRO.test(c.name) || /^cancelacion/i.test(c.name) ? 3 : 2 }))
      .sort((a, b) => a.afinidad - b.afinidad || a.c.name.localeCompare(b.c.name))
      .map(({ c, afinidad }, i) => ({ etiqueta: `Conectar con ${c.name}`, cambios: [crear(clase.id, c.id, '0..*', '1')],
        sugerido: i === 0 && afinidad < 2 }))
  }

  // Ganaderia 2026-11-08: "PadreAnimal duplica una entidad existente como rol familiar". El padre es
  // un Animal: la clase sobra y su lugar lo ocupa una asociacion recursiva Animal-Animal con rol padre.
  const rolFamiliar = hallazgo.match(/(\w+) duplica una entidad existente como rol familiar; usa una asociacion recursiva con rol (\w+)/)
  if (rolFamiliar) {
    const duplicada = porNombre(rolFamiliar[1]), rol = rolFamiliar[2].toLowerCase()
    const base = duplicada && modelo.classes.find((c) => c.id !== duplicada.id
      && c.name.toLowerCase() === duplicada.name.toLowerCase().replace(/^(padre|madre)/, ''))
    if (!duplicada || !base) return []
    const yaTieneRol = modelo.relationships.some((r) => r.sourceClassId === base.id && r.targetClassId === base.id
      && [r.sourceRole, r.targetRole].some((x) => (x ?? '').toLowerCase() === rol))
    return [{ etiqueta: `Usar ${base.name} con rol ${rol} y quitar ${duplicada.name}`, cambios: [
      ...(yaTieneRol ? [] : [{ tipo: 'CREATE_RELATION' as const, payload: { relationId: uuid(), sourceClassId: base.id,
        targetClassId: base.id, kind: 'ASSOCIATION', name: rol === 'padre' ? 'tienePadre' : 'tieneMadre',
        sourceRole: 'descendientes', targetRole: rol, sourceMultiplicity: '0..*', targetMultiplicity: '0..1' },
        resumen: `Crear relacion ${base.name} [0..*] — [0..1] ${base.name} con rol ${rol}` }]),
      { tipo: 'DELETE_CLASS' as const, payload: { classId: duplicada.id }, resumen: `Borrar la clase ${duplicada.name}` },
    ] }]
  }

  // Prestamo no identifica al cliente prestatario: una unica respuesta.
  const prestatario = hallazgo.match(/(\w+) no identifica al cliente prestatario/)
  if (prestatario) {
    const prestamo = porNombre(prestatario[1]), cliente = modelo.classes.find((c) => /^cliente$/i.test(c.name))
    return prestamo && cliente && !vecinas(prestamo.id).has(cliente.id)
      ? [{ etiqueta: `Conectar con ${cliente.name}`, cambios: [crear(prestamo.id, cliente.id, '0..*', '1')] }] : []
  }

  // Transferencia no registra el importe: se agrega el dato, una unica respuesta.
  const importe = hallazgo.match(/(\w+) no registra el importe/)
  if (importe) {
    const clase = porNombre(importe[1])
    return clase ? [{ etiqueta: `Agregar importe (decimal) a ${clase.name}`, cambios: [{ tipo: 'ADD_ATTRIBUTE', payload: {
      attributeId: uuid(), classId: clase.id, name: 'importe', dataType: 'decimal', required: true, unique: false, pk: false },
      resumen: `Agregar atributo importe (decimal) a ${clase.name}` }] }] : []
  }

  // La relacion Prestamo [1] - [1] Cliente es uno a uno: se elige de que lado van varios.
  const unoAUno = hallazgo.match(/relacion (\w+) \[1\] - \[1\] (\w+) es uno a uno/)
  if (unoAUno) {
    const a = porNombre(unoAUno[1]), b = porNombre(unoAUno[2])
    const r = a && b && modelo.relationships.find((x) => (x.sourceClassId === a.id && x.targetClassId === b.id)
      || (x.sourceClassId === b.id && x.targetClassId === a.id))
    if (!a || !b || !r) return []
    const varios = (muchos: string, uno: string) => ({
      etiqueta: `Un ${nombre(uno)} tiene varios ${nombre(muchos)}`,
      cambios: [{ tipo: 'UPDATE_RELATION' as const,
        payload: { relationId: r.id, sourceMultiplicity: r.sourceClassId === muchos ? '0..*' : '1',
          targetMultiplicity: r.targetClassId === muchos ? '0..*' : '1' },
        resumen: `Cambiar ${nombre(muchos)} - ${nombre(uno)}: un ${nombre(uno)} tiene 0..* ${nombre(muchos)}` }],
    })
    // Primero el registro que se acumula (un Cliente tiene varios Prestamo).
    const opciones = REGISTRO.test(b.name) && !REGISTRO.test(a.name) ? [varios(b.id, a.id), varios(a.id, b.id)]
      : [varios(a.id, b.id), varios(b.id, a.id)]
    const hayRegistro = REGISTRO.test(a.name) !== REGISTRO.test(b.name)
    return opciones.map((o, i) => ({ ...o, sugerido: i === 0 && hayRegistro }))
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
    // El paquete es el dueño mas general del itinerario: se sugiere, la salida queda como alternativa.
    return modelo.classes.filter((c) => /^(paquete|salida)/i.test(c.name) && !vecinas(hijo.id).has(c.id))
      .sort((x, y) => (/^paquete/i.test(x.name) ? 0 : 1) - (/^paquete/i.test(y.name) ? 0 : 1))
      .map((c, i) => ({ etiqueta: `Pertenece a ${c.name}`, cambios: [crear(c.id, hijo.id, '1', '0..*')], sugerido: i === 0 }))
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

/**
 * Si un pendiente de Diseñar sigue siendo cierto en el diagrama real. La auditoria queda
 * guardada con el diseño: al recargar, un pendiente ya corregido (con un arreglo directo o
 * a mano) volvia a aparecer. Lo que no se sabe comprobar se considera vigente.
 */
export function pendienteVigente(hallazgo: string, modelo: EstadoModelo): boolean {
  const porNombre = (n: string) => modelo.classes.find((c) => c.name === n)
  const nombre = (id: string) => modelo.classes.find((c) => c.id === id)?.name ?? ''
  const relaciones = (id: string) => modelo.relationships.filter((r) => r.sourceClassId === id || r.targetClassId === id)
  const vecinas = (id: string) => new Set(relaciones(id).map((r) => r.sourceClassId === id ? r.targetClassId : r.sourceClassId))
  const unidas = (a?: { id: string }, b?: { id: string }) => !!a && !!b && vecinas(a.id).has(b.id)

  let m = hallazgo.match(/clase asociativa (\w+) no conecta/i) ?? hallazgo.match(/posible clase asociativa (\w+) solo tiene/i)
  if (m) { const c = porNombre(m[1]); return !c || vecinas(c.id).size < 2 }
  m = hallazgo.match(/Clase desconectada: (\w+)/i) ?? hallazgo.match(/La clase (\w+) queda desconectada/i)
  if (m) { const c = porNombre(m[1]); return !c || relaciones(c.id).length === 0 }
  m = hallazgo.match(/(\w+) no identifica su cabecera (\w+)/)
  if (m) return !unidas(porNombre(m[1]), porNombre(m[2]))
  m = hallazgo.match(/(\w+) duplica una entidad existente como rol familiar/)
  if (m) return !!porNombre(m[1])
  m = hallazgo.match(/(\w+) no identifica al titular mediante una asociacion con \[(\w+)/)
  if (m) return !unidas(porNombre(m[1]), porNombre(m[2]))
  m = hallazgo.match(/(\w+) no identifica al cliente prestatario/)
  if (m) return !unidas(porNombre(m[1]), modelo.classes.find((c) => /^cliente$/i.test(c.name)))
  m = hallazgo.match(/(\w+) no identifica el paquete o la salida/)
  if (m) { const c = porNombre(m[1]); return !c || ![...vecinas(c.id)].some((v) => /^(paquete|salida)/i.test(nombre(v))) }
  m = hallazgo.match(/(\w+) no registra el importe/)
  if (m) { const c = porNombre(m[1]); return !c || !c.attributes.some((a) => /monto|importe|valor/i.test(a.name)) }
  m = hallazgo.match(/relacion (\w+) \[1\] - \[1\] (\w+) es uno a uno/)
  if (m) {
    const a = porNombre(m[1]), b = porNombre(m[2])
    return !a || !b || modelo.relationships.some((r) => ((r.sourceClassId === a.id && r.targetClassId === b.id)
      || (r.sourceClassId === b.id && r.targetClassId === a.id)) && r.sourceMultiplicity === '1' && r.targetMultiplicity === '1')
  }
  m = hallazgo.match(/(\w+) no permite representar multiples viajeros/)
  if (m) {
    const c = porNombre(m[1])
    return !c || !relaciones(c.id).some((r) => {
      const otro = r.sourceClassId === c.id ? r.targetClassId : r.sourceClassId
      const junto = r.sourceClassId === c.id ? r.targetMultiplicity : r.sourceMultiplicity
      return /^(viajero|participante|pasajero)/i.test(nombre(otro)) && (junto ?? '').includes('*')
    })
  }
  return true
}
