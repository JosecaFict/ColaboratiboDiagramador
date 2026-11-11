import { describe, expect, it } from 'vitest'
import type { UmlAttribute } from './command'

/**
 * Contrato UML 2.5.1 v1, rebanada (c): isStatic y readOnly del atributo (D-15).
 * Aqui solo viven los TIPOS: aplicar la operacion en el cliente es de Pedro (applyOperation.ts).
 */
describe('tipos del atributo', () => {
  it('las banderas son opcionales: un atributo de un snapshot anterior sigue siendo un UmlAttribute', () => {
    const anterior: UmlAttribute = {
      id: 'a', classId: 'c', name: 'total', dataType: 'int', visibility: 'PRIVATE',
      pk: false, required: false, unique: false, ordinal: 0,
      multivalued: false, derived: false, partialKey: false,
    }
    const nuevo: UmlAttribute = { ...anterior, isStatic: true, readOnly: true }
    expect(anterior.isStatic ?? false).toBe(false)
    expect(anterior.readOnly ?? false).toBe(false)
    expect(nuevo.isStatic && nuevo.readOnly).toBe(true)
  })

  it.todo('ADD_ATTRIBUTE y UPDATE_ATTRIBUTE aplicados en el cliente conservan isStatic y readOnly (Pedro: applyOperation.ts)')
  it.todo('deshacer REMOVE_ATTRIBUTE en el cliente conserva isStatic y readOnly (Pedro: historialLocal)')
})
