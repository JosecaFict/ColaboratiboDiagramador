import { describe, expect, it } from 'vitest'
import { aplicarOperacion, estadoVacio, type EstadoModelo } from '../canvas/applyOperation'
import type { AppliedOperation, CommandType, Origin } from '../contracts/command'
import { describir, entradasLegibles, esGeometria, hace, indiceDeNombres, tocaClase } from './describir'

let seq = 0
const op = (type: CommandType, payload: Record<string, unknown>, origin: Origin = 'MOUSE', userId = 'ana'): AppliedOperation => ({
  opId: `op-${++seq}`, seq, type, origin, payload, userId, ts: '2026-11-08T10:00:00Z',
})

/** Aplica las operaciones y devuelve el modelo al final, como lo tendria la pestana. */
const modeloTras = (ops: AppliedOperation[]): EstadoModelo => ops.reduce(aplicarOperacion, estadoVacio('d'))

describe('describir: el historial en palabras de persona', () => {
  const crearPedido = op('CREATE_CLASS', { classId: 'pedido', name: 'Pedido', x: 0, y: 0 })
  const crearCliente = op('CREATE_CLASS', { classId: 'cliente', name: 'Cliente', x: 0, y: 0 })
  const total = op('ADD_ATTRIBUTE', { attributeId: 'total', classId: 'pedido', name: 'total', dataType: 'Double' })
  const relacion = op('CREATE_RELATION', {
    relationId: 'r1', sourceClassId: 'pedido', targetClassId: 'cliente', kind: 'ASSOCIATION',
  })
  const ops = [crearPedido, crearCliente, total, relacion]
  const indice = indiceDeNombres(modeloTras(ops), ops)

  it('nombra lo que se creo', () => {
    expect(describir(crearPedido, indice)).toBe('creó la clase «Pedido»')
    expect(describir(total, indice)).toBe('agregó el atributo «total» a «Pedido»')
    expect(describir(relacion, indice)).toBe('relacionó «Pedido» — «Cliente» (asociación)')
  })

  it('distingue la interfaz y la realizacion', () => {
    const i = op('CREATE_CLASS', { classId: 'repo', name: 'Repositorio', stereotype: 'interface' })
    expect(describir(i, indice)).toBe('creó la interfaz «Repositorio»')
    const r = op('CREATE_RELATION', { relationId: 'r2', sourceClassId: 'pedido', targetClassId: 'cliente', kind: 'REALIZATION' })
    expect(describir(r, indice)).toBe('relacionó «Pedido» — «Cliente» (realización)')
  })

  it('distingue enumeraciones y valores embebidos', () => {
    const e = op('CREATE_CLASS', { classId: 'e', name: 'Estado', stereotype: 'enumeration' })
    expect(describir(e, indice)).toBe('creó la enumeración «Estado»')
  })

  it('dice que campo de un atributo cambio, sin repetir', () => {
    const cambio = op('UPDATE_ATTRIBUTE', { attributeId: 'total', dataType: 'Integer', pk: true, esperado: {} })
    expect(describir(cambio, indice)).toBe('cambió el tipo y la clave primaria de «Pedido.total»')
    const multiplicidades = op('UPDATE_RELATION', { relationId: 'r1', sourceMultiplicity: '1', targetMultiplicity: '0..*' })
    expect(describir(multiplicidades, indice)).toBe('cambió la multiplicidad de la relación «Pedido» — «Cliente»')
  })

  it('recuerda el nombre anterior de una clase renombrada', () => {
    const renombre = op('RENAME_CLASS', { classId: 'pedido', name: 'Orden' })
    const todas = [...ops, renombre]
    expect(describir(renombre, indiceDeNombres(modeloTras(todas), todas))).toBe('renombró «Pedido» a «Orden»')
  })

  it('nombra una clase borrada si la creacion esta entre las operaciones cargadas', () => {
    const borrar = op('DELETE_CLASS', { classId: 'cliente' })
    const todas = [...ops, borrar]
    expect(describir(borrar, indiceDeNombres(modeloTras(todas), todas))).toBe('borró la clase «Cliente»')
  })

  it('usa las clases vistas en la pestana cuando la creacion es mas vieja que lo cargado', () => {
    const borrar = op('DELETE_CLASS', { classId: 'vieja' })
    const i = indiceDeNombres(estadoVacio('d'), [borrar], new Map([['vieja', 'Factura']]))
    expect(describir(borrar, i)).toBe('borró la clase «Factura»')
    expect(describir(borrar, indiceDeNombres(estadoVacio('d'), [borrar]))).toBe('borró la clase')
  })

  it('resume un lote por su origen', () => {
    const lote = op('BATCH', { comandos: [
      { type: 'CREATE_CLASS', payload: { classId: 'a', name: 'A' } },
      { type: 'CREATE_CLASS', payload: { classId: 'b', name: 'B' } },
      { type: 'MOVE_ELEMENT', payload: { elementId: 'a', x: 1, y: 1 } },
    ] }, 'IMAGE')
    expect(describir(lote, indice)).toBe('importó desde una imagen (2 cambios)')
    const uno = op('BATCH', { comandos: [{ type: 'CREATE_CLASS', payload: { classId: 'c', name: 'C' } }] })
    expect(describir(uno, indice)).toBe('creó la clase «C»')
  })
})

describe('el historial de una clase', () => {
  const ops = [
    op('CREATE_CLASS', { classId: 'pedido', name: 'Pedido' }),
    op('CREATE_CLASS', { classId: 'cliente', name: 'Cliente' }),
    op('ADD_ATTRIBUTE', { attributeId: 'total', classId: 'pedido', name: 'total' }),
    op('CREATE_RELATION', { relationId: 'r1', sourceClassId: 'pedido', targetClassId: 'cliente', kind: 'ASSOCIATION' }),
  ]
  const editarTotal = op('UPDATE_ATTRIBUTE', { attributeId: 'total', name: 'importe' })
  const indice = indiceDeNombres(modeloTras(ops), ops)

  it('incluye sus atributos y relaciones, no los de otras', () => {
    expect(ops.filter((o) => tocaClase(o, 'pedido', indice)).map((o) => o.type))
      .toEqual(['CREATE_CLASS', 'ADD_ATTRIBUTE', 'CREATE_RELATION'])
    expect(tocaClase(editarTotal, 'pedido', indice)).toBe(true)
    expect(tocaClase(editarTotal, 'cliente', indice)).toBe(false)
  })
})

describe('lo que no entra al historial', () => {
  it('mover y redimensionar no son cambios del modelo', () => {
    expect(esGeometria(op('MOVE_ELEMENT', { elementId: 'a', x: 1, y: 1 }))).toBe(true)
    expect(esGeometria(op('BATCH', { comandos: [{ type: 'MOVE_ELEMENT', payload: {} }] }))).toBe(true)
    expect(esGeometria(op('BATCH', { comandos: [{ type: 'CREATE_CLASS', payload: {} }] }))).toBe(false)
  })

  it('arma las entradas con autor, origen y la marca de deshacer', () => {
    const ops = [
      op('CREATE_CLASS', { classId: 'a', name: 'A' }, 'VOICE', 'yo'),
      op('MOVE_ELEMENT', { elementId: 'a', x: 1, y: 1 }),
      op('DELETE_CLASS', { classId: 'a', deshace: 'op-x' }, 'MOUSE', 'otra'),
    ]
    const entradas = entradasLegibles(ops, indiceDeNombres(estadoVacio('d'), ops),
      (id) => ({ nombre: id === 'yo' ? 'Diego' : 'Ana', propio: id === 'yo' }))
    expect(entradas.map((e) => [e.autor, e.texto, e.origen, e.deshace, e.propia])).toEqual([
      ['Diego', 'creó la clase «A»', 'voz', false, true],
      ['Ana', 'borró la clase «A»', null, true, false],
    ])
    expect(entradas[0].tecnico).toMatch(/^#\d+ CREATE_CLASS$/)
  })
})

describe('hace', () => {
  const ahora = Date.parse('2026-11-08T12:00:00Z')
  it('dice cuanto paso en palabras', () => {
    expect(hace('2026-11-08T11:59:30Z', ahora)).toBe('hace unos segundos')
    expect(hace('2026-11-08T11:55:00Z', ahora)).toBe('hace 5 min')
    expect(hace('2026-11-08T09:00:00Z', ahora)).toBe('hace 3 h')
    expect(hace('2026-11-07T10:00:00Z', ahora)).toBe('ayer')
    expect(hace('no es una fecha', ahora)).toBe('')
  })
})
