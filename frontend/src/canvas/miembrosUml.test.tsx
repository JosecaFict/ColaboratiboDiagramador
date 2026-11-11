import { renderToStaticMarkup } from 'react-dom/server'
import { describe, expect, it } from 'vitest'
import type { ElementLayout, UmlClass } from '../contracts/command'
import { PanelPropiedades } from '../panel/PanelPropiedades'
import { Canvas } from './Canvas'
import { aplicarOperacion, estadoVacio } from './applyOperation'

/** Lo estatico, lo abstracto y lo de solo lectura se ven (UML 2.5.1, D-15 rebanadas b y c). */

const clase = (): UmlClass => ({
  id: 'c', diagramId: 'd', name: 'Figura', isAbstract: true, weak: false, erKind: 'STRONG',
  inheritanceStrategy: 'JOINED',
  attributes: [
    { id: 'a1', classId: 'c', name: 'contador', dataType: 'int', visibility: 'PRIVATE', pk: false, required: true,
      unique: false, ordinal: 0, multivalued: false, derived: false, partialKey: false, isStatic: true },
    { id: 'a2', classId: 'c', name: 'codigo', dataType: 'string', visibility: 'PRIVATE', pk: false, required: true,
      unique: false, ordinal: 1, multivalued: false, derived: false, partialKey: false, readOnly: true },
    { id: 'a3', classId: 'c', name: 'color', dataType: 'string', visibility: 'PRIVATE', pk: false, required: true,
      unique: false, ordinal: 2, multivalued: false, derived: false, partialKey: false },
  ],
  methods: [
    { id: 'm1', classId: 'c', name: 'crear', returnType: 'void', params: [], visibility: 'PUBLIC', ordinal: 0, isStatic: true },
    { id: 'm2', classId: 'c', name: 'area', returnType: 'double', params: [], visibility: 'PUBLIC', ordinal: 1, isAbstract: true },
    { id: 'm3', classId: 'c', name: 'pintar', returnType: 'void', params: [], visibility: 'PUBLIC', ordinal: 2 },
  ],
})
const layout: Record<string, ElementLayout> = { c: { elementId: 'c', x: 10, y: 10, width: 220, height: 200 } }

const textoDe = (html: string, contiene: string) =>
  html.match(new RegExp(`<text[^>]*>[^<]*${contiene}[^<]*`))?.[0] ?? ''

describe('Canvas: notacion UML de los miembros', () => {
  const html = renderToStaticMarkup(
    <Canvas view="CLASS" classes={[clase()]} relationships={[]} layout={layout} locks={{}} />)

  it('subraya el atributo y el metodo estaticos, y solo esos', () => {
    expect(textoDe(html, 'contador')).toContain('txt-estatico')
    expect(textoDe(html, 'crear')).toContain('txt-estatico')
    expect(textoDe(html, 'color')).not.toContain('txt-estatico')
    expect(textoDe(html, 'pintar')).not.toContain('txt-estatico')
  })

  it('pone en cursiva el metodo abstracto, y solo ese', () => {
    expect(textoDe(html, 'area')).toContain('abstracta')
    expect(textoDe(html, 'pintar')).not.toContain('abstracta')
  })

  it('escribe {readOnly} despues del atributo de solo lectura, y solo en ese', () => {
    expect(textoDe(html, 'codigo')).toContain('{readOnly}')
    expect(textoDe(html, 'color')).not.toContain('{readOnly}')
  })
})

describe('PanelPropiedades: casillas de los miembros', () => {
  const props = () => ({
    clase: clase(), enumeraciones: [], embebibles: [], esRaizDeJerarquia: false, onCambiarEstrategia: () => {},
    bloqueadaPorOtro: null, onRenombrar: () => {}, onAgregarAtributo: () => {},
    sugerirAtributo: async () => null, onRelacionarCon: () => {}, onActualizarAtributo: () => {},
    onQuitarAtributo: () => {}, onAgregarMetodo: () => {}, onQuitarMetodo: () => {},
    onActualizarMetodo: () => {}, onBorrarClase: () => {}, onCerrar: () => {},
  })

  it('ofrece estatico y abstracto en cada metodo, y estatico y solo lectura en cada atributo', () => {
    const html = renderToStaticMarkup(<PanelPropiedades {...props()} />)
    expect(html.match(/abstracto/g)?.length).toBe(3)       // una casilla por metodo
    expect(html.match(/estático/g)?.length).toBe(3 + 3)    // por metodo y por atributo
    expect(html.match(/solo lectura/g)?.length).toBeGreaterThanOrEqual(3)
  })
})

describe('aplicarOperacion: miembros UML', () => {
  const op = (type: string, payload: Record<string, unknown>) =>
    ({ opId: 'o', seq: 1, type, origin: 'MOUSE', userId: 'u', ts: '', payload }) as never

  it('UPDATE_METHOD cambia solo lo que trae y deja fuera las marcas de deshacer', () => {
    const e = { ...estadoVacio('d'), classes: [clase()] }
    const r = aplicarOperacion(e, op('UPDATE_METHOD', { methodId: 'm3', isStatic: true, esperado: { isStatic: false } }))
    const m = r.classes[0].methods.find((x) => x.id === 'm3')!
    expect(m).toMatchObject({ name: 'pintar', isStatic: true })
    expect('esperado' in m).toBe(false)
    expect(r.classes[0].methods.find((x) => x.id === 'm1')!.isStatic).toBe(true)
  })

  it('ADD_METHOD y ADD_ATTRIBUTE conservan las banderas nuevas', () => {
    let e = aplicarOperacion(estadoVacio('d'), op('CREATE_CLASS', { classId: 'c', name: 'X', x: 0, y: 0 }))
    e = aplicarOperacion(e, op('ADD_METHOD', { methodId: 'm', classId: 'c', name: 'f', isAbstract: true }))
    e = aplicarOperacion(e, op('ADD_ATTRIBUTE', { attributeId: 'a', classId: 'c', name: 'n', isStatic: true, readOnly: true }))
    expect(e.classes[0].methods[0].isAbstract).toBe(true)
    expect(e.classes[0].attributes[0]).toMatchObject({ isStatic: true, readOnly: true })
  })

  it('un diagrama anterior (sin banderas) no gana banderas', () => {
    let e = aplicarOperacion(estadoVacio('d'), op('CREATE_CLASS', { classId: 'c', name: 'X', x: 0, y: 0 }))
    e = aplicarOperacion(e, op('ADD_METHOD', { methodId: 'm', classId: 'c', name: 'f' }))
    expect('isStatic' in e.classes[0].methods[0]).toBe(false)
  })
})
