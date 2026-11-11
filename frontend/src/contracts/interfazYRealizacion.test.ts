import { describe, expect, it } from 'vitest'
import { aplicarOperacion, estadoVacio } from '../canvas/applyOperation'
import { esInterfaz, ESTEREOTIPO_INTERFAZ } from './command'
import type { AppliedOperation, CommandType } from './command'

/**
 * Contrato UML 2.5.1 v1, rebanada (a): la interfaz es un estereotipo y REALIZATION
 * un tipo de relacion nuevo (D-15). Espejo de InterfazYRealizacionTest.java.
 */
const op = (type: CommandType, payload: Record<string, unknown>, seq = 1): AppliedOperation => ({
  opId: `op${seq}`, seq, type, origin: 'MOUSE', payload, userId: null, ts: '',
})

describe('interfaz', () => {
  it('reconoce el estereotipo sin distinguir mayusculas ni espacios', () => {
    expect(esInterfaz({ stereotype: 'interface' })).toBe(true)
    expect(esInterfaz({ stereotype: ' Interface ' })).toBe(true)
    expect(ESTEREOTIPO_INTERFAZ).toBe('interface')
  })

  it('una clase comun, una enumeracion u otro texto no son interfaz', () => {
    expect(esInterfaz({})).toBe(false)
    expect(esInterfaz({ stereotype: null })).toBe(false)
    expect(esInterfaz({ stereotype: 'enumeration' })).toBe(false)
    expect(esInterfaz({ stereotype: 'interfaces' })).toBe(false)
  })

  it('CREATE_CLASS con el estereotipo crea una interfaz', () => {
    const estado = aplicarOperacion(estadoVacio('d'),
      op('CREATE_CLASS', { classId: 'repo', name: 'Repositorio', stereotype: 'interface' }))
    expect(esInterfaz(estado.classes[0])).toBe(true)
  })
})

describe('REALIZATION', () => {
  it('CREATE_RELATION conserva el tipo y los extremos', () => {
    const estado = aplicarOperacion(estadoVacio('d'),
      op('CREATE_RELATION', { relationId: 'r1', sourceClassId: 'impl', targetClassId: 'repo', kind: 'REALIZATION' }))
    expect(estado.relationships[0]).toMatchObject({ kind: 'REALIZATION', sourceClassId: 'impl', targetClassId: 'repo' })
  })

  it('UPDATE_RELATION cambia el tipo a REALIZATION', () => {
    const conRelacion = aplicarOperacion(estadoVacio('d'),
      op('CREATE_RELATION', { relationId: 'r1', sourceClassId: 'impl', targetClassId: 'repo', kind: 'ASSOCIATION' }))
    const estado = aplicarOperacion(conRelacion, op('UPDATE_RELATION', { relationId: 'r1', kind: 'REALIZATION' }, 2))
    expect(estado.relationships[0].kind).toBe('REALIZATION')
  })
})
