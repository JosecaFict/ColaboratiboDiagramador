import { describe, expect, it } from 'vitest'
import type { CommandType, UmlMethod } from './command'

/**
 * Contrato UML 2.5.1 v1, rebanada (b): isStatic e isAbstract del metodo y UPDATE_METHOD (D-15).
 * Aqui solo viven los TIPOS: aplicar la operacion en el cliente es de Pedro (applyOperation.ts).
 */
describe('tipos del metodo', () => {
  it('UPDATE_METHOD es un tipo de comando', () => {
    const tipo: CommandType = 'UPDATE_METHOD'
    expect(tipo).toBe('UPDATE_METHOD')
  })

  it('las banderas son opcionales: un metodo de un snapshot anterior sigue siendo un UmlMethod', () => {
    const anterior: UmlMethod = {
      id: 'm', classId: 'c', name: 'area', returnType: 'double', params: [], visibility: 'PUBLIC', ordinal: 0,
    }
    const nuevo: UmlMethod = { ...anterior, isStatic: true, isAbstract: false }
    expect(anterior.isStatic ?? false).toBe(false)
    expect(nuevo.isStatic).toBe(true)
  })

  it.todo('ADD_METHOD aplicado en el cliente conserva isStatic e isAbstract (Pedro: applyOperation.ts copia campo por campo)')
  it.todo('deshacer REMOVE_METHOD en el cliente conserva isStatic e isAbstract (Pedro: historialLocal.agregarMetodo)')
})
