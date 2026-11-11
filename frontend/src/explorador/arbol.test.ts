import { describe, expect, it } from 'vitest'
import type { UmlClass, UmlRelationship } from '../contracts/command'
import { nodosDeClases, nodosDeRelaciones, tipoDe } from './arbol'

const clase = (id: string, name: string, extra: Partial<UmlClass> = {}): UmlClass => ({
  id, diagramId: 'd', name, isAbstract: false, weak: false, erKind: 'STRONG',
  inheritanceStrategy: 'JOINED', attributes: [], methods: [], ...extra,
})

describe('arbol del proyecto', () => {
  const pedido = clase('p', 'Pedido', {
    attributes: [
      { id: 'b', classId: 'p', name: 'total', dataType: 'Double', visibility: 'PRIVATE', pk: false, required: false,
        unique: false, ordinal: 1, multivalued: false, derived: false, partialKey: false },
      { id: 'a', classId: 'p', name: 'id', dataType: 'Long', visibility: 'PRIVATE', pk: true, required: true,
        unique: true, ordinal: 0, multivalued: false, derived: false, partialKey: false },
    ],
    methods: [{ id: 'm', classId: 'p', name: 'calcular', returnType: 'Double', params: [], visibility: 'PUBLIC', ordinal: 0 }],
  })
  const persona = clase('x', 'persona', { isAbstract: true })
  const estado = clase('e', 'Estado', { stereotype: 'enumeration' })
  const direccion = clase('v', 'Direccion', { stereotype: 'embeddable' })

  it('reconoce el tipo de cada elemento, como la paleta', () => {
    expect([pedido, persona, estado, direccion].map(tipoDe)).toEqual(['CLASE', 'ABSTRACTA', 'ENUM', 'VALOR'])
  })

  it('una clase con estereotipo interface es una INTERFAZ y se lista como tal', () => {
    const repo = clase('r', 'Repositorio', { stereotype: 'interface' })
    expect(tipoDe(repo)).toBe('INTERFAZ')
    expect(tipoDe(clase('r2', 'Otra', { stereotype: ' Interface ' }))).toBe('INTERFAZ')
    expect(nodosDeClases([repo])[0].tipo).toBe('INTERFAZ')
  })

  it('una realizacion se lista con sus extremos y conserva su tipo', () => {
    const repo = clase('r', 'Repositorio', { stereotype: 'interface' })
    const r = { id: 'z', diagramId: 'd', sourceClassId: 'p', targetClassId: 'r', kind: 'REALIZATION' } as UmlRelationship
    const [nodo] = nodosDeRelaciones([r], [pedido, repo])
    expect(nodo.kind).toBe('REALIZATION')
    expect(nodo.texto).toBe('Pedido — Repositorio')
  })

  it('ordena por nombre sin mirar mayusculas y lista los miembros en su orden', () => {
    const nodos = nodosDeClases([pedido, persona, estado, direccion])
    expect(nodos.map((n) => n.nombre)).toEqual(['Direccion', 'Estado', 'Pedido', 'persona'])
    expect(nodos.find((n) => n.id === 'p')!.miembros).toEqual(['- id: Long', '- total: Double', '+ calcular(): Double'])
  })

  it('nombra las relaciones por sus extremos', () => {
    const r: UmlRelationship = {
      id: 'r', diagramId: 'd', sourceClassId: 'p', targetClassId: 'x', kind: 'ASSOCIATION',
      name: null, sourceRole: null, targetRole: null, sourceMultiplicity: '0..*', targetMultiplicity: '1',
      identifying: false, sourceParticipation: null, targetParticipation: null, degree: 2,
    } as UmlRelationship
    expect(nodosDeRelaciones([r], [pedido, persona])[0].texto).toBe('Pedido — persona')
    expect(nodosDeRelaciones([{ ...r, name: 'hace' }], [pedido, persona])[0].texto).toBe('hace (Pedido — persona)')
  })
})
