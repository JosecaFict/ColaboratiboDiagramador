import { describe, expect, it } from 'vitest'
import { aplicarOperacion, estadoVacio, type EstadoModelo } from '../canvas/applyOperation'
import type { AppliedOperation, UmlClass } from '../contracts/command'
import type { SugerenciaAtributo } from '../panel/PanelPropiedades'
import { planDeImportacion, revisionDe, type PropuestaDeImportacion } from './propuesta'

let n = 0
const nuevoId = () => `id-${++n}`

const sug = (rol: SugerenciaAtributo['rol'], extra: Partial<SugerenciaAtributo> = {}): SugerenciaAtributo => ({
  rol, dataType: 'string', claseRelacionada: null, claseRelacionadaId: null, confianza: 'ALTA', motivo: '', ...extra,
})

const atributo = (nombre: string, tipo: string, rol: SugerenciaAtributo['rol'] = 'ATRIBUTO',
                  extra: Partial<SugerenciaAtributo> = {}) => ({
  nombre, tipoLeido: null, tipo, pk: false, requerido: false, unico: false, multivaluado: false,
  derivado: false, claveParcial: false, yaExiste: false, sugerencia: sug(rol, extra),
})

/** Cliente y Pedido, con una relacion 1 -> 0..* y un Pedido.cliente_id que es FK. */
const propuesta = (): PropuestaDeImportacion => ({
  formato: 'JSON', fuente: 'm.json', advertencias: [],
  clases: [
    { clave: 'c', id: 'nuevo-cliente', nombre: 'Cliente', estereotipo: null, abstracta: false, idExistente: null,
      atributos: [atributo('nombre', 'string')], metodos: [] },
    { clave: 'p', id: 'nuevo-pedido', nombre: 'Pedido', estereotipo: null, abstracta: false, idExistente: null,
      atributos: [
        atributo('fecha', 'date'),
        atributo('cliente_id', 'uuid', 'RELACION', { claseRelacionada: 'Cliente', claseRelacionadaId: 'nuevo-cliente' }),
        atributo('id', 'uuid', 'IDENTIDAD'),
      ], metodos: [] },
  ],
  relaciones: [],
})

const conClase = (nombre: string, atributos: string[] = []): EstadoModelo => {
  const c: UmlClass = {
    id: 'existente-' + nombre, diagramId: 'd', name: nombre, isAbstract: false, weak: false, erKind: 'STRONG',
    inheritanceStrategy: 'JOINED', methods: [],
    attributes: atributos.map((a, i) => ({
      id: 'a' + i, classId: 'existente-' + nombre, name: a, dataType: 'string',
      visibility: 'PRIVATE', pk: false, required: false,
      unique: false, ordinal: i, multivalued: false, derived: false, partialKey: false,
    })),
  }
  return { diagramId: 'd', classes: [c], relationships: [],
    layout: { [c.id]: { elementId: c.id, x: 100, y: 300, width: 170, height: 80 } } }
}

describe('revisionDe: los valores por defecto son sugerencias', () => {
  it('la identidad arranca destildada y la FK se propone como relacion', () => {
    const r = revisionDe(propuesta())
    const [fecha, fk, id] = r.clases[1].atributos
    expect(fecha.incluir).toBe(true)
    expect(fk.comoRelacion).toBe(true)
    expect(id.incluir).toBe(false)
  })
})

describe('planDeImportacion', () => {
  it('crea las clases, sus atributos y convierte la FK en relacion', () => {
    const plan = planDeImportacion(revisionDe(propuesta()), estadoVacio('d'), nuevoId)

    expect(plan.conteo).toEqual({ clases: 2, atributos: 2, relaciones: 1, metodos: 0 })
    const relacion = plan.comandos.find((c) => c.type === 'CREATE_RELATION')!
    // Muchos pedidos, un cliente: la FK queda del lado de Pedido.
    expect(relacion.payload).toMatchObject({
      sourceClassId: 'nuevo-pedido', targetClassId: 'nuevo-cliente', sourceMultiplicity: '0..*', targetMultiplicity: '1',
    })
    expect(plan.comandos.some((c) => c.type === 'ADD_ATTRIBUTE' && c.payload.name === 'cliente_id')).toBe(false)
  })

  it('las clases van antes que los atributos que las usan', () => {
    const tipos = planDeImportacion(revisionDe(propuesta()), estadoVacio('d'), nuevoId).comandos.map((c) => c.type)
    expect(tipos.indexOf('ADD_ATTRIBUTE')).toBeGreaterThan(tipos.lastIndexOf('CREATE_CLASS'))
  })

  it('una clase que ya existe no se crea: se le agregan solo los atributos que le faltan', () => {
    const p = propuesta()
    p.clases[0].atributos.push(atributo('email', 'string'))
    const plan = planDeImportacion(revisionDe(p), conClase('cliente', ['Nombre']), nuevoId)

    expect(plan.comandos.filter((c) => c.type === 'CREATE_CLASS').map((c) => c.payload.name)).toEqual(['Pedido'])
    const agregados = plan.comandos.filter((c) => c.type === 'ADD_ATTRIBUTE' && c.payload.classId === 'existente-cliente')
    expect(agregados.map((c) => c.payload.name)).toEqual(['email'])
    // Sigue la numeracion de la clase existente.
    expect(agregados[0].payload.ordinal).toBe(1)
    expect(plan.avisos.some((a) => a.includes('ya existe'))).toBe(true)
  })

  it('la FK hacia una clase existente apunta a su id real, no al reservado', () => {
    const plan = planDeImportacion(revisionDe(propuesta()), conClase('Cliente'), nuevoId)
    const relacion = plan.comandos.find((c) => c.type === 'CREATE_RELATION')!
    expect(relacion.payload.targetClassId).toBe('existente-Cliente')
  })

  it('si el archivo ya declara la relacion, la FK no la duplica', () => {
    const p = propuesta()
    p.relaciones.push({ claveOrigen: 'c', claveDestino: 'p', tipo: 'ASSOCIATION', nombre: null,
      multiplicidadOrigen: '1', multiplicidadDestino: '0..*', rolOrigen: null, rolDestino: null, identificadora: false })
    const plan = planDeImportacion(revisionDe(p), estadoVacio('d'), nuevoId)
    expect(plan.conteo.relaciones).toBe(1)
  })

  it('lo destildado no entra, y una relacion hacia una clase omitida se avisa', () => {
    const r = revisionDe(propuesta())
    r.clases[0].incluir = false
    const plan = planDeImportacion(r, estadoVacio('d'), nuevoId)

    expect(plan.comandos.some((c) => c.payload.name === 'Cliente')).toBe(false)
    expect(plan.avisos.some((a) => a.includes('no se convierte en relacion'))).toBe(true)
  })

  it('las clases nuevas se ubican debajo de lo que ya esta dibujado', () => {
    const plan = planDeImportacion(revisionDe(propuesta()), conClase('Otra'), nuevoId)
    const ys = plan.comandos.filter((c) => c.type === 'CREATE_CLASS').map((c) => c.payload.y as number)
    // La existente llega hasta y = 300 + 80.
    expect(Math.min(...ys)).toBeGreaterThan(380)
  })

  it('las clases que el archivo dibuja conservan su disposicion (ADR-031)', () => {
    const p = propuesta()
    // Como en EA: Cliente a la izquierda, Pedido 300 px a la derecha y 100 mas abajo, cajas de 100 px.
    p.clases[0].posicion = { x: 50, y: 40, ancho: 100, alto: 70 }
    p.clases[1].posicion = { x: 350, y: 140, ancho: 100, alto: 70 }
    const plan = planDeImportacion(revisionDe(p), estadoVacio('d'), nuevoId)
    const [cliente, pedido] = plan.comandos.filter((c) => c.type === 'CREATE_CLASS').map((c) => c.payload)

    expect(cliente).toMatchObject({ x: 160, y: 120, width: 170 })
    // Se agranda en la misma proporcion que el ancho (170 / 100), para no encimarlas.
    expect(pedido).toMatchObject({ x: 160 + 300 * 1.7, y: 120 + 100 * 1.7 })
  })

  it('lo que el archivo no dibuja va a la grilla, debajo de lo dibujado', () => {
    const p = propuesta()
    p.clases[0].posicion = { x: 0, y: 0, ancho: 170, alto: 200 }
    const plan = planDeImportacion(revisionDe(p), estadoVacio('d'), nuevoId)
    const [cliente, pedido] = plan.comandos.filter((c) => c.type === 'CREATE_CLASS').map((c) => c.payload)

    expect(cliente).toMatchObject({ x: 160, y: 120 })
    expect(pedido.y as number).toBeGreaterThanOrEqual(120 + 200)
    expect(pedido).not.toHaveProperty('width')
  })
})

describe('metodos (REGLA 6): no se descartan', () => {
  const conMetodo = () => {
    const p = propuesta()
    p.clases[1].metodos = [
      { nombre: 'reservar', tipoRetorno: 'boolean', parametros: [{ nombre: 'dias', tipo: 'int' }], yaExiste: false },
    ]
    return p
  }

  it('un metodo arranca tildado y entra como ADD_METHOD en el mismo BATCH', () => {
    const r = revisionDe(conMetodo())
    expect(r.clases[1].metodos[0].incluir).toBe(true)

    const plan = planDeImportacion(r, estadoVacio('d'), nuevoId)
    const metodo = plan.comandos.find((c) => c.type === 'ADD_METHOD')!
    expect(metodo.payload).toMatchObject({
      classId: 'nuevo-pedido', name: 'reservar', returnType: 'boolean',
      params: [{ name: 'dias', type: 'int' }],
    })
    expect(plan.conteo.metodos).toBe(1)
  })

  it('un metodo que la clase existente ya tiene arranca destildado y no se duplica', () => {
    const p = conMetodo()
    p.clases[1].metodos[0] = { ...p.clases[1].metodos[0], yaExiste: true }
    const r = revisionDe(p)
    expect(r.clases[1].metodos[0].incluir).toBe(false)

    const plan = planDeImportacion(r, estadoVacio('d'), nuevoId)
    expect(plan.comandos.some((c) => c.type === 'ADD_METHOD')).toBe(false)
  })
})

describe('BATCH en el espejo del cliente', () => {
  it('aplica todos los cambios del lote como una sola operacion', () => {
    const plan = planDeImportacion(revisionDe(propuesta()), estadoVacio('d'), nuevoId)
    const op: AppliedOperation = {
      opId: 'lote', seq: 7, type: 'BATCH', origin: 'XMI_IMPORT', userId: null, ts: '',
      payload: { descripcion: 'm.json', comandos: plan.comandos },
    }

    const estado = aplicarOperacion(estadoVacio('d'), op)

    expect(estado.classes.map((c) => c.name)).toEqual(['Cliente', 'Pedido'])
    expect(estado.classes[1].attributes.map((a) => a.name)).toEqual(['fecha'])
    expect(estado.relationships).toHaveLength(1)
  })
})
