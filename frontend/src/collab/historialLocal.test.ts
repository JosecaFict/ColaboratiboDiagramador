import { describe, expect, it } from 'vitest'
import { aplicarOperacion, estadoVacio, type EstadoModelo } from '../canvas/applyOperation'
import type { UmlClass } from '../contracts/command'
import { entradaDe, entradaDeDecision, HistorialLocal, type EntradaSinConfirmar } from './historialLocal'

/**
 * Deshacer local (ADR-027 a ADR-030). El servidor decide si un undo pisa a
 * alguien (DeshacerConcurrenteTest); aca se fija que el cliente arme el
 * comando correcto y que sus pilas solo cambien con lo que el servidor acepto.
 */

const libro = (): UmlClass => ({
  id: 'libro', diagramId: 'd', name: 'Libro', isAbstract: false, weak: false, erKind: 'STRONG',
  inheritanceStrategy: 'JOINED', methods: [],
  attributes: [{
    id: 'paginas', classId: 'libro', name: 'paginas', dataType: 'string', visibility: 'PRIVATE',
    pk: false, required: false, unique: false, ordinal: 0, multivalued: false, derived: false,
    partialKey: false, minValue: null, maxValue: null,
  }],
})

const conLibroEn = (x: number, y: number): EstadoModelo => ({
  ...estadoVacio('d'),
  classes: [libro()],
  relationships: [{
    id: 'rel', diagramId: 'd', sourceClassId: 'libro', targetClassId: 'libro', kind: 'ASSOCIATION',
    sourceMultiplicity: '1', targetMultiplicity: '0..*', identifying: false, degree: 2,
  }],
  layout: { libro: { elementId: 'libro', x, y, width: 170, height: 60 } },
})

const moverLibro = (h: HistorialLocal, opId: string, desde: [number, number], hasta: [number, number]) => {
  const payload = { elementId: 'libro', elementKind: 'CLASS', x: hasta[0], y: hasta[1] }
  h.enviado(opId, entradaDe(conLibroEn(...desde), 'MOVE_ELEMENT', payload))
}

/** Lo que manda un Ctrl+Z y un Ctrl+Y de esa entrada (sin `deshace`, que se pone al enviar). */
const deshacer = (e: EntradaSinConfirmar | null) => e?.deshacer
const rehacer = (e: EntradaSinConfirmar | null) => e?.hacer

describe('entradaDe: geometria', () => {
  it('captura donde estaba la clase antes de moverla', () => {
    const e = entradaDe(conLibroEn(100, 100), 'MOVE_ELEMENT', { elementId: 'libro', x: 300, y: 300 })
    expect(e?.etiqueta).toBe('mover Libro')
    expect(deshacer(e)?.payload).toMatchObject({ x: 100, y: 100, esperado: { x: 300, y: 300 } })
    expect(rehacer(e)?.payload).toMatchObject({ x: 300, y: 300, esperado: { x: 100, y: 100 } })
  })

  it('una linea sin quiebres guardados vuelve a recta con una lista vacia', () => {
    const e = entradaDe(conLibroEn(0, 0), 'MOVE_ELEMENT',
      { elementId: 'rel', elementKind: 'RELATION', waypoints: [{ x: 10, y: 20 }] })
    expect(deshacer(e)?.payload).toMatchObject({ waypoints: [] })
    expect(e?.etiqueta).toBe('acomodar la linea Libro–Libro')
  })

  it('soltar la clase donde estaba no deja un paso vacio', () => {
    expect(entradaDe(conLibroEn(100, 100), 'MOVE_ELEMENT', { elementId: 'libro', x: 100, y: 100 })).toBeNull()
  })

  it('redimensionar recuerda el tamano anterior', () => {
    const e = entradaDe(conLibroEn(0, 0), 'RESIZE_ELEMENT', { elementId: 'libro', width: 300, height: 90 })
    expect(deshacer(e)?.payload).toMatchObject({ width: 170, height: 60 })
  })
})

describe('entradaDe: semantica (escalon 2)', () => {
  it('renombrar recuerda el nombre viejo', () => {
    const e = entradaDe(conLibroEn(0, 0), 'RENAME_CLASS', { classId: 'libro', name: 'Libros' })
    expect(deshacer(e)).toEqual({ type: 'RENAME_CLASS',
      payload: { classId: 'libro', name: 'Libro', esperado: { name: 'Libros' } } })
    expect(e?.etiqueta).toBe('renombrar Libro a Libros')
  })

  it('de un atributo recuerda SOLO los campos que el comando cambia', () => {
    const e = entradaDe(conLibroEn(0, 0), 'UPDATE_ATTRIBUTE', { attributeId: 'paginas', dataType: 'int' })
    expect(deshacer(e)?.payload).toEqual({ attributeId: 'paginas', dataType: 'string', esperado: { dataType: 'int' } })
    expect(e?.etiqueta).toBe('editar Libro.paginas')
  })

  it('un campo que no tenia valor se recuerda como null, no como ausente', () => {
    const e = entradaDe(conLibroEn(0, 0), 'UPDATE_ATTRIBUTE', { attributeId: 'paginas', minValue: 10 })
    expect(deshacer(e)?.payload).toMatchObject({ minValue: null })
  })

  it('de una relacion, igual: solo lo cambiado', () => {
    const e = entradaDe(conLibroEn(0, 0), 'UPDATE_RELATION', { relationId: 'rel', targetMultiplicity: '1..*' })
    expect(deshacer(e)?.payload).toEqual({ relationId: 'rel', targetMultiplicity: '0..*',
      esperado: { targetMultiplicity: '1..*' } })
  })

  it('cada bandera se recuerda con la clave con que viaja', () => {
    const pk = entradaDe(conLibroEn(0, 0), 'SET_ER_FLAG',
      { targetType: 'ATTRIBUTE', targetId: 'paginas', flag: 'pk', value: true })
    expect(deshacer(pk)?.payload).toMatchObject({ value: false, esperado: { value: true } })

    const herencia = entradaDe(conLibroEn(0, 0), 'SET_ER_FLAG',
      { targetType: 'CLASS', targetId: 'libro', flag: 'inheritanceStrategy', strategy: 'SINGLE_TABLE' })
    expect(deshacer(herencia)?.payload).toMatchObject({ strategy: 'JOINED', esperado: { strategy: 'SINGLE_TABLE' } })
  })

  it('el undo de un atributo es el mismo UPDATE con el valor viejo y las marcas', () => {
    const h = new HistorialLocal()
    h.enviado('op1', entradaDe(conLibroEn(0, 0), 'UPDATE_ATTRIBUTE', { attributeId: 'paginas', dataType: 'int' }))
    h.confirmar('op1')
    expect(h.deshacer('op2')).toEqual({
      opId: 'op2', type: 'UPDATE_ATTRIBUTE',
      payload: { attributeId: 'paginas', dataType: 'string', deshace: 'op1', esperado: { dataType: 'int' } },
    })
  })

  it('una decision vuelve a la lectura que regia, aunque fuera la por defecto', () => {
    const e = entradaDeDecision('rel', 'COMPOSITION', 'DATA', 'OO', 'decidir A-1')
    expect(deshacer(e)).toEqual({ type: 'SET_MODELING_DECISION',
      payload: { elementId: 'rel', ambiguityKind: 'COMPOSITION', chosen: 'DATA', esperado: { chosen: 'OO' } } })
    expect(entradaDeDecision('rel', 'COMPOSITION', 'OO', 'OO', 'x')).toBeNull()
  })
})

describe('entradaDe: crear y borrar (escalon 3)', () => {
  it('deshacer crear es borrar, solo si la clase sigue vacia', () => {
    const e = entradaDe(estadoVacio('d'), 'CREATE_CLASS', { classId: 'c1', name: 'Socio', x: 10, y: 20 })
    expect(deshacer(e)).toEqual({ type: 'DELETE_CLASS', payload: { classId: 'c1',
      esperado: { name: 'Socio', attributeIds: [], methodIds: [], relationIds: [] } } })
    // Rehacer vuelve a crear con el MISMO id: el servidor resucita la fila.
    expect(rehacer(e)?.payload).toMatchObject({ classId: 'c1', name: 'Socio', esperado: { eliminada: true } })
  })

  it('deshacer borrar restaura la clase entera con sus mismos ids, en un solo lote', () => {
    const estado = conLibroEn(40, 50)
    estado.classes[0].inheritanceStrategy = 'SINGLE_TABLE'
    estado.classes[0].methods = [{ id: 'm1', classId: 'libro', name: 'prestar', returnType: 'void',
      params: [], visibility: 'PUBLIC', ordinal: 0 }]
    estado.layout.rel = { elementId: 'rel', x: 0, y: 0, width: 0, height: 0, waypoints: [{ x: 5, y: 6 }] }

    const e = entradaDe(estado, 'DELETE_CLASS', { classId: 'libro' })
    const lote = deshacer(e)!
    expect(lote.type).toBe('BATCH')
    const cambios = lote.payload.comandos as { type: string; payload: Record<string, unknown> }[]
    expect(cambios.map((c) => c.type)).toEqual([
      'CREATE_CLASS', 'SET_ER_FLAG', 'ADD_ATTRIBUTE', 'ADD_METHOD', 'CREATE_RELATION', 'MOVE_ELEMENT'])
    expect(cambios[0].payload).toMatchObject({ classId: 'libro', name: 'Libro', x: 40, y: 50,
      esperado: { eliminada: true } })
    expect(cambios[2].payload).toMatchObject({ attributeId: 'paginas', dataType: 'string', esperado: { existe: false } })
    expect(cambios[5].payload).toMatchObject({ elementId: 'rel', waypoints: [{ x: 5, y: 6 }] })

    // Rehacer el borrado solo pasa si nadie toco lo que se restauro.
    expect(rehacer(e)?.payload).toEqual({ classId: 'libro', esperado: {
      name: 'Libro', attributeIds: ['paginas'], methodIds: ['m1'], relationIds: ['rel'] } })
  })

  it('agregar y quitar un atributo son inversos, y quitar exige que nadie lo haya editado', () => {
    const agregar = entradaDe(conLibroEn(0, 0), 'ADD_ATTRIBUTE',
      { classId: 'libro', attributeId: 'isbn', name: 'isbn', dataType: 'string', minValue: '' })
    expect(deshacer(agregar)).toEqual({ type: 'REMOVE_ATTRIBUTE',
      payload: { attributeId: 'isbn', esperado: { name: 'isbn', dataType: 'string', minValue: '' } } })
    expect(agregar?.etiqueta).toBe('agregar Libro.isbn')

    const quitar = entradaDe(conLibroEn(0, 0), 'REMOVE_ATTRIBUTE', { attributeId: 'paginas' })
    expect(deshacer(quitar)?.type).toBe('ADD_ATTRIBUTE')
    expect(deshacer(quitar)?.payload).toMatchObject({ classId: 'libro', attributeId: 'paginas', name: 'paginas' })
  })

  it('una relacion borrada vuelve con sus quiebres', () => {
    const estado = conLibroEn(0, 0)
    estado.layout.rel = { elementId: 'rel', x: 0, y: 0, width: 0, height: 0, waypoints: [{ x: 1, y: 2 }] }
    const e = entradaDe(estado, 'DELETE_RELATION', { relationId: 'rel' })
    expect(deshacer(e)?.type).toBe('BATCH')

    const sinQuiebres = entradaDe(conLibroEn(0, 0), 'DELETE_RELATION', { relationId: 'rel' })
    expect(deshacer(sinQuiebres)?.type).toBe('CREATE_RELATION')
  })
})

describe('entradaDe: lote (escalon 4)', () => {
  const importacion = {
    descripcion: 'Importado de biblioteca.xmi',
    comandos: [
      { type: 'CREATE_CLASS', payload: { classId: 'socio', name: 'Socio' } },
      { type: 'ADD_ATTRIBUTE', payload: { classId: 'socio', attributeId: 'dni', name: 'dni' } },
      { type: 'CREATE_RELATION', payload: { relationId: 'r2', sourceClassId: 'socio', targetClassId: 'libro',
        kind: 'ASSOCIATION' } },
    ],
  }

  it('se deshace con un lote inverso, en orden reverso', () => {
    const e = entradaDe(conLibroEn(0, 0), 'BATCH', importacion)
    expect(e?.etiqueta).toBe('Importado de biblioteca.xmi')
    const cambios = deshacer(e)!.payload.comandos as { type: string }[]
    expect(cambios.map((c) => c.type)).toEqual(['DELETE_RELATION', 'REMOVE_ATTRIBUTE', 'DELETE_CLASS'])
    expect(deshacer(e)!.payload).toMatchObject({ esperado: {} })
  })

  it('rehacer vuelve a mandar los mismos cambios, cada uno con su precondicion', () => {
    const e = entradaDe(conLibroEn(0, 0), 'BATCH', importacion)
    const cambios = rehacer(e)!.payload.comandos as { type: string; payload: Record<string, unknown> }[]
    expect(cambios.map((c) => c.type)).toEqual(['CREATE_CLASS', 'ADD_ATTRIBUTE', 'CREATE_RELATION'])
    expect(cambios[0].payload).toMatchObject({ classId: 'socio', esperado: { eliminada: true } })
  })

  it('el inverso de cada cambio se calcula sobre lo que dejaron los anteriores del lote', () => {
    // Crear y despues mover: el undo del movimiento vuelve a donde la dejo el CREATE.
    const e = entradaDe(estadoVacio('d'), 'BATCH', { descripcion: 'x', comandos: [
      { type: 'CREATE_CLASS', payload: { classId: 'c', name: 'C', x: 10, y: 10 } },
      { type: 'MOVE_ELEMENT', payload: { elementId: 'c', x: 90, y: 90 } },
    ] })
    const cambios = deshacer(e)!.payload.comandos as { type: string; payload: Record<string, unknown> }[]
    expect(cambios[0]).toMatchObject({ type: 'MOVE_ELEMENT', payload: { x: 10, y: 10, esperado: { x: 90, y: 90 } } })
  })

  it('un borrado adentro de un lote no anida lotes: se aplana', () => {
    const e = entradaDe(conLibroEn(0, 0), 'BATCH', { descripcion: 'x', comandos: [
      { type: 'DELETE_CLASS', payload: { classId: 'libro' } },
    ] })
    const cambios = deshacer(e)!.payload.comandos as { type: string }[]
    expect(cambios.every((c) => c.type !== 'BATCH')).toBe(true)
    expect(cambios[0].type).toBe('CREATE_CLASS')
  })

  it('un lote con una decision de mapeo no se sabe deshacer y lo dice con null', () => {
    expect(entradaDe(conLibroEn(0, 0), 'BATCH', { descripcion: 'x', comandos: [
      { type: 'SET_MODELING_DECISION', payload: { elementId: 'rel', ambiguityKind: 'COMPOSITION', chosen: 'OO' } },
    ] })).toBeNull()
  })
})

describe('espejo (applyOperation)', () => {
  it('las marcas de un undo no terminan como campos del atributo', () => {
    const despues = aplicarOperacion(conLibroEn(0, 0), {
      opId: 'u', seq: 2, type: 'UPDATE_ATTRIBUTE', origin: 'MOUSE', userId: null, ts: '',
      payload: { attributeId: 'paginas', dataType: 'int', deshace: 'op1', esperado: { dataType: 'string' } },
    })
    const a = despues.classes[0].attributes[0] as unknown as Record<string, unknown>
    expect(a.dataType).toBe('int')
    expect(a).not.toHaveProperty('esperado')
    expect(a).not.toHaveProperty('deshace')
  })
})

describe('HistorialLocal', () => {
  it('nada se puede deshacer hasta que el servidor lo confirma', () => {
    const h = new HistorialLocal()
    moverLibro(h, 'op1', [100, 100], [300, 300])
    expect(h.proximoDeshacer()).toBeNull()

    h.confirmar('op1')
    expect(h.proximoDeshacer()).toBe('mover Libro')
  })

  it('un comando rechazado no queda como deshacible', () => {
    const h = new HistorialLocal()
    moverLibro(h, 'op1', [100, 100], [300, 300])
    expect(h.rechazar({ opId: 'op1', reason: 'INVALID', message: 'x' })).toBeNull()
    expect(h.confirmar('op1')).toBe(false)
    expect(h.proximoDeshacer()).toBeNull()
  })

  it('deshacer arma un MOVE comun que apunta a la operacion y exige donde quedo', () => {
    const h = new HistorialLocal()
    moverLibro(h, 'op1', [100, 100], [300, 300])
    h.confirmar('op1')

    const c = h.deshacer('op2')
    expect(c).toEqual({
      opId: 'op2', type: 'MOVE_ELEMENT',
      payload: { elementId: 'libro', elementKind: 'CLASS', x: 100, y: 100,
        deshace: 'op1', esperado: { x: 300, y: 300 } },
    })
  })

  it('rehacer apunta al undo y exige que siga donde lo dejo el undo', () => {
    const h = new HistorialLocal()
    moverLibro(h, 'op1', [100, 100], [300, 300])
    h.confirmar('op1')
    h.deshacer('op2')
    h.confirmar('op2')

    const c = h.rehacer('op3')
    expect(c?.payload).toMatchObject({ x: 300, y: 300, deshace: 'op2', esperado: { x: 100, y: 100 } })
    h.confirmar('op3')
    expect(h.deshacer('op4')?.payload.deshace).toBe('op3')
  })

  it('si otro lo movio encima, el paso se descarta y se dice por que', () => {
    const h = new HistorialLocal()
    moverLibro(h, 'op1', [100, 100], [300, 300])
    h.confirmar('op1')
    h.deshacer('op2')

    const msg = h.rechazar({ opId: 'op2', reason: 'STALE', message: 'Otra persona lo cambio despues que vos' })
    expect(msg).toContain('No se pudo deshacer "mover Libro"')
    expect(msg).toContain('Otra persona')
    expect(h.proximoDeshacer()).toBeNull()
    expect(h.proximoRehacer()).toBeNull()
  })

  it('un lock es transitorio: el paso vuelve a la pila intacto', () => {
    const h = new HistorialLocal()
    moverLibro(h, 'op1', [100, 100], [300, 300])
    h.confirmar('op1')
    h.deshacer('op2')
    h.rechazar({ opId: 'op2', reason: 'LOCKED_BY_OTHER', message: 'lo esta editando Beto' })

    expect(h.deshacer('op3')?.payload.deshace).toBe('op1')
  })

  it('una edicion propia nueva invalida el rehacer, aunque no se sepa deshacer', () => {
    const h = new HistorialLocal()
    moverLibro(h, 'op1', [100, 100], [300, 300])
    h.confirmar('op1')
    h.deshacer('op2')
    h.confirmar('op2')
    expect(h.proximoRehacer()).toBe('mover Libro')

    h.enviado('op3', null)   // p. ej. un RENAME_CLASS
    h.confirmar('op3')
    expect(h.proximoRehacer()).toBeNull()
  })

  it('un cambio que no cambia nada (un blur en el panel) no se lleva el rehacer', () => {
    const h = new HistorialLocal()
    moverLibro(h, 'op1', [100, 100], [300, 300])
    h.confirmar('op1')
    h.deshacer('op2')
    h.confirmar('op2')

    h.enviado('op3', null, false)
    h.confirmar('op3')
    expect(h.proximoRehacer()).toBe('mover Libro')
  })

  it('las operaciones ajenas no tocan las pilas', () => {
    const h = new HistorialLocal()
    moverLibro(h, 'op1', [100, 100], [300, 300])
    h.confirmar('op1')
    expect(h.confirmar('op-de-beto')).toBe(false)
    expect(h.proximoDeshacer()).toBe('mover Libro')
  })

  it('deshacer dos veces seguidas revierte en orden inverso', () => {
    const h = new HistorialLocal()
    moverLibro(h, 'op1', [100, 100], [300, 300])
    h.confirmar('op1')
    moverLibro(h, 'op2', [300, 300], [500, 500])
    h.confirmar('op2')

    expect(h.deshacer('u1')?.payload).toMatchObject({ x: 300, esperado: { x: 500 } })
    expect(h.deshacer('u2')?.payload).toMatchObject({ x: 100, esperado: { x: 300 } })
  })

  it('recuerda como mucho `tope` pasos', () => {
    const h = new HistorialLocal(2)
    for (const op of ['a', 'b', 'c']) { moverLibro(h, op, [0, 0], [1, 1]); h.confirmar(op) }
    expect(h.deshacer('u1')?.payload.deshace).toBe('c')
    expect(h.deshacer('u2')?.payload.deshace).toBe('b')
    expect(h.deshacer('u3')).toBeNull()
  })

  it('vaciar olvida todo, incluido lo que estaba en viaje', () => {
    const h = new HistorialLocal()
    moverLibro(h, 'op1', [100, 100], [300, 300])
    h.vaciar()
    expect(h.confirmar('op1')).toBe(false)
    expect(h.proximoDeshacer()).toBeNull()
  })
})
