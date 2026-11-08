import { describe, expect, it } from 'vitest'
import type { EstadoModelo } from '../canvas/applyOperation'
import type { UmlClass, UmlRelationship } from '../contracts/command'
import { arreglosDirectos } from './arreglosDirectos'

const clase = (id: string): UmlClass => ({ id, diagramId: 'd', name: id, isAbstract: false, weak: false,
  erKind: 'STRONG', inheritanceStrategy: 'SINGLE_TABLE', attributes: [], methods: [] } as unknown as UmlClass)
const rel = (id: string, s: string, t: string, ms: string, mt: string): UmlRelationship => ({ id, diagramId: 'd',
  sourceClassId: s, targetClassId: t, kind: 'ASSOCIATION', sourceMultiplicity: ms, targetMultiplicity: mt,
  identifying: false, degree: 2 })

// Turismo, prueba desde la interfaz 2026-11-08.
const turismo: EstadoModelo = {
  diagramId: 'd', layout: {},
  classes: ['Destino', 'ServicioTuristico', 'Paquete', 'Salida', 'Itinerario', 'DetalleItinerario', 'Reserva',
    'Viajero', 'AsignacionServicio'].map(clase),
  relationships: [rel('r1', 'Salida', 'AsignacionServicio', '1', '0..*'), rel('r2', 'Itinerario', 'DetalleItinerario', '1', '0..*'),
    rel('r3', 'Reserva', 'Viajero', '0..*', '1'), rel('r4', 'Paquete', 'Salida', '1', '0..*')],
}

describe('arreglos directos', () => {
  it('una asociativa incompleta ofrece primero la clase que comparte su nombre', () => {
    const opciones = arreglosDirectos('La clase asociativa AsignacionServicio no conecta sus dos extremos o roles.', turismo)
    expect(opciones[0].etiqueta).toBe('Conectar con ServicioTuristico')
    expect(opciones.map((o) => o.etiqueta)).not.toContain('Conectar con Salida') // ya conectada
    expect(opciones[0].cambios[0]).toMatchObject({ tipo: 'CREATE_RELATION', payload: {
      sourceClassId: 'AsignacionServicio', targetClassId: 'ServicioTuristico', sourceMultiplicity: '0..*', targetMultiplicity: '1' } })
  })

  it('varios viajeros por reserva actualiza la relacion existente', () => {
    const [opcion] = arreglosDirectos('Reserva no permite representar multiples viajeros participantes; distingue al titular', turismo)
    expect(opcion.cambios[0]).toMatchObject({ tipo: 'UPDATE_RELATION',
      payload: { relationId: 'r3', sourceMultiplicity: '0..*', targetMultiplicity: '1..*' } })
  })

  it('un itinerario sin dueño ofrece paquete o salida', () => {
    const opciones = arreglosDirectos('Itinerario no identifica el paquete o la salida al que pertenece', turismo)
    expect(opciones.map((o) => o.etiqueta)).toEqual(['Pertenece a Paquete', 'Pertenece a Salida'])
  })

  it('banco: un titular sin cliente ofrece conectarlo con Cliente, primero en la lista', () => {
    const banco: EstadoModelo = { diagramId: 'd', layout: {},
      classes: ['Cliente', 'Cuenta', 'Titular', 'Movimiento'].map(clase),
      relationships: [rel('t1', 'Titular', 'Cuenta', '0..*', '1')] }
    const [unico] = arreglosDirectos('Titular no identifica al titular mediante una asociacion con [Cliente].', banco)
    expect(unico.cambios[0].payload).toMatchObject({ sourceClassId: 'Titular', targetClassId: 'Cliente', targetMultiplicity: '1' })
    expect(arreglosDirectos('La clase asociativa Titular no conecta sus dos extremos o roles.', banco)[0].etiqueta)
      .toBe('Conectar con Cliente')
  })

  it('turismo: un detalle sin concepto ofrece primero el servicio', () => {
    const conDetalle: EstadoModelo = { ...turismo, classes: [...turismo.classes, clase('DetalleReserva')],
      relationships: [...turismo.relationships, rel('r9', 'Reserva', 'DetalleReserva', '1', '0..*')] }
    expect(arreglosDirectos('La clase asociativa DetalleReserva no conecta sus dos extremos o roles.', conDetalle)[0].etiqueta)
      .toBe('Conectar con ServicioTuristico')
  })

  it('un hallazgo desconocido no inventa arreglos', () => {
    expect(arreglosDirectos('Los creditos requeridos por cada plan no tienen contexto', turismo)).toEqual([])
  })
})
