import { describe, expect, it } from 'vitest'
import type { UmlAttribute, UmlClass, UmlMethod, UmlRelationship } from '../contracts/command'
import { calcularDiff, calcularDiffEntreModelos, estaVacio, mostrarValor, resaltadosParaLienzo, totalDeCambios } from './diff'
import { claveDeConflicto, detectarConflictos, elegir, elegirTodos, fusionar, previewDeMerge, sinResolver } from './merge'
import { CAMPO_EXISTENCIA } from './tipos'

const attr = (id: string, classId: string, name: string, extra: Partial<UmlAttribute> = {}): UmlAttribute => ({
  id, classId, name, dataType: 'String', visibility: 'PRIVATE', pk: false, required: false, unique: false,
  ordinal: 0, multivalued: false, derived: false, partialKey: false, ...extra,
})
const metodo = (id: string, classId: string, name: string, extra: Partial<UmlMethod> = {}): UmlMethod => ({
  id, classId, name, returnType: 'void', params: [], visibility: 'PUBLIC', ordinal: 0, ...extra,
})
const clase = (id: string, name: string, attributes: UmlAttribute[] = [], methods: UmlMethod[] = [],
               extra: Partial<UmlClass> = {}): UmlClass => ({
  id, diagramId: 'd', name, isAbstract: false, weak: false, erKind: 'STRONG',
  inheritanceStrategy: 'JOINED', attributes, methods, ...extra,
})
const rel = (id: string, a: string, b: string, extra: Partial<UmlRelationship> = {}): UmlRelationship => ({
  id, diagramId: 'd', sourceClassId: a, targetClassId: b, kind: 'ASSOCIATION', identifying: false, degree: 2, ...extra,
})

const base = () => ({
  classes: [
    clase('c1', 'Libro', [attr('a1', 'c1', 'titulo'), attr('a2', 'c1', 'isbn', { ordinal: 1 })], [metodo('m1', 'c1', 'prestar')]),
    clase('c2', 'Autor', [attr('a3', 'c2', 'nombre')]),
  ],
  relationships: [rel('r1', 'c1', 'c2', { sourceMultiplicity: '0..*', targetMultiplicity: '1' })],
})
type M = ReturnType<typeof base>
const copia = (m: M): M => structuredClone(m)
const clasePorNombre = (m: M, n: string) => m.classes.find((c) => c.name === n)!

describe('calcularDiff', () => {
  it('dos modelos iguales no tienen diferencias', () => {
    const d = calcularDiffEntreModelos(base(), base())
    expect(estaVacio(d)).toBe(true)
    expect(totalDeCambios(d)).toBe(0)
  })

  it('agrupa lo agregado, lo eliminado y lo modificado por clases, atributos, metodos y relaciones', () => {
    const b = base()
    const h = copia(b)
    h.classes.push(clase('c3', 'Prestamo'))                                      // clase agregada
    clasePorNombre(h, 'Autor').name = 'Escritor'                                 // clase modificada
    clasePorNombre(h, 'Libro').attributes = [attr('a1', 'c1', 'titulo', { required: true })]  // a1 modificado, a2 eliminado
    clasePorNombre(h, 'Libro').methods.push(metodo('m2', 'c1', 'devolver'))      // metodo agregado
    h.relationships = [rel('r2', 'c3', 'c1')]                                    // r1 eliminada, r2 agregada

    const d = calcularDiffEntreModelos(b, h)
    expect(d.clases.agregadas.map((x) => x.nombre)).toEqual(['Prestamo'])
    expect(d.clases.modificadas).toEqual([
      expect.objectContaining({ id: 'c2', nombre: 'Escritor', cambios: [{ campo: 'name', antes: 'Autor', despues: 'Escritor' }] }),
    ])
    expect(d.atributos.eliminadas).toEqual([{ id: 'a2', nombre: 'isbn', claseId: 'c1', clase: 'Libro' }])
    expect(d.atributos.modificadas[0].cambios).toEqual([{ campo: 'required', antes: false, despues: true }])
    expect(d.metodos.agregadas).toEqual([{ id: 'm2', nombre: 'devolver', claseId: 'c1', clase: 'Libro' }])
    expect(d.relaciones.eliminadas.map((x) => x.id)).toEqual(['r1'])
    expect(d.relaciones.agregadas.map((x) => x.id)).toEqual(['r2'])
  })

  it('una relacion sin nombre se muestra como "Origen → Destino"', () => {
    const b = base()
    const h = copia(b)
    h.relationships = []
    const d = calcularDiffEntreModelos(b, h)
    expect(d.relaciones.eliminadas[0].nombre).toBe('Libro → Autor')
  })

  it('null y undefined son lo mismo; los parametros de un metodo se comparan por valor', () => {
    const b = base()
    const h = copia(b)
    delete (h.classes[0].attributes[0] as Partial<UmlAttribute>).length
    h.classes[0].attributes[0].length = null
    expect(estaVacio(calcularDiffEntreModelos(b, h))).toBe(true)

    h.classes[0].methods[0].params = [{ name: 'socio', type: 'Socio' }]
    const d = calcularDiffEntreModelos(b, h)
    expect(d.metodos.modificadas[0].cambios[0].campo).toBe('params')
  })

  it('mover un atributo de clase cuenta como cambio de classId', () => {
    const b = base()
    const h = copia(b)
    const a = h.classes[0].attributes.pop()!
    h.classes[1].attributes.push({ ...a, classId: 'c2' })
    const d = calcularDiffEntreModelos(b, h)
    expect(d.atributos.modificadas[0].cambios).toContainEqual({ campo: 'classId', antes: 'Libro', despues: 'Autor' })
  })

  it('arma los extremos desde/hasta, con null para "el estado actual"', () => {
    const d = calcularDiff(base(), { versionId: 'v1', seq: 10 }, base(), { versionId: null, seq: 12 })
    expect(d.desde).toEqual({ versionId: 'v1', seq: 10 })
    expect(d.hasta).toEqual({ versionId: null, seq: 12 })
  })
})

describe('resaltadosParaLienzo', () => {
  it('marca clases y relaciones; un atributo cambiado marca a su clase como modificada', () => {
    const b = base()
    const h = copia(b)
    h.classes.push(clase('c3', 'Prestamo'))
    h.classes[0].attributes[0].name = 'nombre'
    h.relationships = []
    const r = resaltadosParaLienzo(calcularDiffEntreModelos(b, h))
    expect(r).toEqual({ c3: 'agregado', c1: 'modificado', r1: 'eliminado' })
  })

  it('el estado de la propia clase manda sobre el de sus atributos', () => {
    const b = base()
    const h = copia(b)
    h.classes[0].name = 'Obra'
    h.classes[0].attributes[0].name = 'x'
    expect(resaltadosParaLienzo(calcularDiffEntreModelos(b, h)).c1).toBe('modificado')
  })
})

describe('mostrarValor', () => {
  it('formatea nulos, booleanos y parametros', () => {
    expect(mostrarValor(null)).toBe('—')
    expect(mostrarValor('')).toBe('—')
    expect(mostrarValor(true)).toBe('sí')
    expect(mostrarValor([{ name: 'a', type: 'int' }])).toBe('(a: int)')
    expect(mostrarValor(3)).toBe('3')
  })
})

describe('merge a tres bandas', () => {
  it('los cambios de un solo lado no son conflicto y se aplican solos', () => {
    const b = base()
    const nuestro = copia(b)
    nuestro.classes[0].name = 'Obra'
    const suyo = copia(b)
    suyo.classes[1].name = 'Escritor'
    suyo.classes.push(clase('c3', 'Prestamo'))

    expect(detectarConflictos(b, nuestro, suyo)).toEqual([])
    const m = fusionar(b, nuestro, suyo, [])
    expect(m.classes.map((c) => c.name)).toEqual(['Obra', 'Escritor', 'Prestamo'])
  })

  it('el mismo campo con valores distintos es conflicto; con el mismo valor no', () => {
    const b = base()
    const nuestro = copia(b)
    nuestro.classes[0].name = 'Obra'
    const suyo = copia(b)
    suyo.classes[0].name = 'Titulo'
    const c = detectarConflictos(b, nuestro, suyo)
    expect(c).toEqual([{ elementId: 'c1', tipo: 'clase', campo: 'name', base: 'Libro', nuestro: 'Obra', suyo: 'Titulo' }])

    suyo.classes[0].name = 'Obra'
    expect(detectarConflictos(b, nuestro, suyo)).toEqual([])
  })

  it('campos distintos del mismo elemento se combinan sin conflicto', () => {
    const b = base()
    const nuestro = copia(b)
    nuestro.classes[0].attributes[0].required = true
    const suyo = copia(b)
    suyo.classes[0].attributes[0].dataType = 'Text'
    expect(detectarConflictos(b, nuestro, suyo)).toEqual([])
    const a = fusionar(b, nuestro, suyo, []).classes[0].attributes[0]
    expect(a.required).toBe(true)
    expect(a.dataType).toBe('Text')
  })

  it('respeta la eleccion nuestro/suyo por conflicto', () => {
    const b = base()
    const nuestro = copia(b)
    nuestro.classes[0].attributes[0].name = 'titulo_n'
    nuestro.classes[1].name = 'Escritor_n'
    const suyo = copia(b)
    suyo.classes[0].attributes[0].name = 'titulo_s'
    suyo.classes[1].name = 'Escritor_s'

    const conflictos = detectarConflictos(b, nuestro, suyo)
    expect(conflictos).toHaveLength(2)
    let res = elegir([], { elementId: 'a1', campo: 'name' }, 'suyo')
    res = elegir(res, { elementId: 'c2', campo: 'name' }, 'nuestro')
    const m = fusionar(b, nuestro, suyo, res)
    expect(m.classes[0].attributes[0].name).toBe('titulo_s')
    expect(m.classes[1].name).toBe('Escritor_n')
  })

  it('un conflicto sin eleccion se queda con lo nuestro', () => {
    const b = base()
    const nuestro = copia(b); nuestro.classes[0].name = 'N'
    const suyo = copia(b); suyo.classes[0].name = 'S'
    expect(fusionar(b, nuestro, suyo, []).classes[0].name).toBe('N')
  })

  it('borrar de un lado algo que el otro no toco: se borra, sin conflicto', () => {
    const b = base()
    const nuestro = copia(b)
    nuestro.classes[0].attributes = nuestro.classes[0].attributes.filter((a) => a.id !== 'a2')
    const suyo = copia(b)
    expect(detectarConflictos(b, nuestro, suyo)).toEqual([])
    expect(fusionar(b, nuestro, suyo, []).classes[0].attributes.map((a) => a.id)).toEqual(['a1'])
    // y al reves: lo borra el otro lado
    expect(fusionar(b, suyo, nuestro, []).classes[0].attributes.map((a) => a.id)).toEqual(['a1'])
  })

  it('borrado de un lado y modificado del otro es un conflicto de existencia', () => {
    const b = base()
    const nuestro = copia(b)
    nuestro.classes[1].attributes = []                    // nosotros borramos a3
    const suyo = copia(b)
    suyo.classes[1].attributes[0].name = 'apellido'       // ellos lo renombran

    const c = detectarConflictos(b, nuestro, suyo)
    expect(c).toEqual([{
      elementId: 'a3', tipo: 'atributo', campo: CAMPO_EXISTENCIA, base: 'existe', nuestro: 'eliminado', suyo: 'existe',
    }])
    expect(fusionar(b, nuestro, suyo, []).classes[1].attributes).toEqual([])
    const conSuyo = fusionar(b, nuestro, suyo, elegir([], c[0], 'suyo'))
    expect(conSuyo.classes[1].attributes.map((a) => a.name)).toEqual(['apellido'])
  })

  it('borrar una clase se lleva sus relaciones', () => {
    const b = base()
    const nuestro = copia(b)
    nuestro.classes = nuestro.classes.filter((c) => c.id !== 'c2')
    const suyo = copia(b)
    const m = fusionar(b, nuestro, suyo, [])
    expect(m.classes.map((c) => c.id)).toEqual(['c1'])
    expect(m.relationships).toEqual([])
  })

  it('el preview junta el diff de cada lado y los conflictos', () => {
    const b = base()
    const nuestro = copia(b); nuestro.classes[0].name = 'N'
    const suyo = copia(b); suyo.classes[0].name = 'S'; suyo.classes.push(clase('c3', 'Nueva'))
    const p = previewDeMerge(b, nuestro, suyo)
    expect(p.nuestros.clases.modificadas).toHaveLength(1)
    expect(p.suyos.clases.agregadas.map((c) => c.nombre)).toEqual(['Nueva'])
    expect(p.conflictos).toHaveLength(1)
  })
})

describe('resoluciones', () => {
  const conflictos = [
    { elementId: 'x', tipo: 'clase' as const, campo: 'name', base: 'a', nuestro: 'b', suyo: 'c' },
    { elementId: 'y', tipo: 'clase' as const, campo: 'erKind', base: 'a', nuestro: 'b', suyo: 'c' },
  ]

  it('sinResolver devuelve los que no tienen eleccion', () => {
    const res = elegir([], conflictos[0], 'suyo')
    expect(sinResolver(conflictos, res).map(claveDeConflicto)).toEqual(['y|erKind'])
    expect(sinResolver(conflictos, elegirTodos(conflictos, 'nuestro'))).toEqual([])
  })

  it('elegir dos veces el mismo conflicto reemplaza la eleccion en vez de duplicarla', () => {
    let res = elegir([], conflictos[0], 'suyo')
    res = elegir(res, conflictos[0], 'nuestro')
    expect(res).toEqual([{ elementId: 'x', campo: 'name', eleccion: 'nuestro' }])
  })
})
