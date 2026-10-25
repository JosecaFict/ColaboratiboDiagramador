import { describe, expect, it } from 'vitest'
import type { UmlAttribute, UmlClass, UmlRelationship } from '../contracts/command'
import {
  aCamel, aPascal, aSnake, agregarColumna, cambiarLargo, cambiarObligatorio, cambiarObligatorioFk,
  cambiarOnDelete, cambiarRango, cambiarTipo, esError, renombrarColumna, renombrarFk, renombrarTabla,
  type Cambio,
} from './edicion'

/**
 * Editar la Vista Fisica es editar el modelo (ADR-032).
 *
 * Estos tests fijan QUE comando manda cada gesto. Si alguno mandara algo que
 * el modelo no dijo --una relacion que pasa a 1:1 por tildar NOT NULL, un
 * rango que el bus rechaza--, la tabla que se ve dejaria de ser la que se
 * genera.
 */
const atributo = (over: Partial<UmlAttribute> = {}): UmlAttribute => ({
  id: 'a1', classId: 'c1', name: 'fechaDevolucion', dataType: 'date', visibility: 'PRIVATE',
  length: null, pk: false, required: false, unique: false, defaultValue: null, ordinal: 0,
  multivalued: false, derived: false, partialKey: false, parentAttributeId: null,
  minValue: null, maxValue: null, ...over,
})

const clase = (attributes: UmlAttribute[] = [atributo()]): UmlClass => ({
  id: 'c1', diagramId: 'd', name: 'FichaPrestamo', stereotype: null, isAbstract: false, weak: false,
  erKind: 'STRONG', inheritanceStrategy: 'JOINED', attributes, methods: [],
})

const relacion = (over: Partial<UmlRelationship> = {}): UmlRelationship => ({
  id: 'r1', diagramId: 'd', sourceClassId: 'persona', targetClassId: 'c1', kind: 'ASSOCIATION',
  name: null, sourceRole: null, targetRole: null, sourceMultiplicity: '1', targetMultiplicity: '0..*',
  identifying: false, sourceParticipation: null, targetParticipation: null, degree: 2, ...over,
})

const persona: UmlClass = { ...clase([]), id: 'persona', name: 'Persona' }

const cambio = (r: ReturnType<typeof renombrarColumna>): Cambio => {
  if (r === null || esError(r)) throw new Error('se esperaba un cambio y llego ' + JSON.stringify(r))
  return r
}

describe('nombres', () => {
  it('lo escrito se lleva a snake_case como la Regla 8, con la enie transliterada', () => {
    expect(aSnake('Fecha Devolución')).toBe('fecha_devolucion')
    expect(aSnake('fechaDevolucion')).toBe('fecha_devolucion')
    expect(aSnake('Año')).toBe('anio')
  })

  it('una columna da un atributo en camelCase y una tabla una clase en PascalCase', () => {
    expect(aCamel('fecha_devolucion')).toBe('fechaDevolucion')
    expect(aPascal('ficha_prestamo')).toBe('FichaPrestamo')
  })
})

describe('columnas', () => {
  it('renombrar la columna renombra el atributo, y el comando lleva la clase para el lock', () => {
    const c = cambio(renombrarColumna(clase(), atributo(), 'fecha_entrega'))

    expect(c.type).toBe('UPDATE_ATTRIBUTE')
    expect(c.payload).toEqual({ attributeId: 'a1', classId: 'c1', name: 'fechaEntrega' })
    expect(c.efecto).toContain('FichaPrestamo.fechaDevolucion')
  })

  it('escribir el mismo nombre de columna no toca el atributo (no le borra la enie)', () => {
    expect(renombrarColumna(clase([atributo({ name: 'Año' })]), atributo({ name: 'Año' }), 'anio')).toBeNull()
  })

  it('no deja dos columnas con el mismo nombre en la tabla', () => {
    const otra = atributo({ id: 'a2', name: 'fechaEntrega' })
    expect(esError(renombrarColumna(clase([atributo(), otra]), atributo(), 'fecha_entrega'))).toBe(true)
  })

  it('pasar a un tipo no numerico borra el rango en el MISMO comando', () => {
    const conRango = atributo({ name: 'monto', dataType: 'decimal', minValue: 0, maxValue: 100 })
    const c = cambio(cambiarTipo(clase([conRango]), conRango, 'text'))

    expect(c.payload).toMatchObject({ dataType: 'text', minValue: null, maxValue: null })
  })

  it('entre dos tipos numericos el rango se conserva', () => {
    const conRango = atributo({ name: 'monto', dataType: 'int', minValue: 0 })
    const c = cambio(cambiarTipo(clase([conRango]), conRango, 'decimal'))

    expect(c.payload).not.toHaveProperty('minValue')
  })

  it('el largo de un varchar se valida antes de mandarlo', () => {
    const texto = atributo({ name: 'titulo', dataType: 'string' })
    expect(esError(cambiarLargo(clase([texto]), texto, 'cien'))).toBe(true)
    expect(esError(cambiarLargo(clase([texto]), texto, '0'))).toBe(true)
    expect(cambio(cambiarLargo(clase([texto]), texto, '120')).payload).toMatchObject({ length: 120 })
    // Vacio es volver al de siempre.
    const conLargo = atributo({ name: 'titulo', dataType: 'string', length: 80 })
    expect(cambio(cambiarLargo(clase([conLargo]), conLargo, '')).payload).toMatchObject({ length: null })
  })

  it('NOT NULL es obligatorio en el UML', () => {
    const c = cambio(cambiarObligatorio(clase(), atributo(), true))
    expect(c.payload).toEqual({ attributeId: 'a1', classId: 'c1', required: true })
    expect(cambiarObligatorio(clase(), atributo(), false)).toBeNull()
  })

  it('un rango imposible no llega al bus', () => {
    const n = atributo({ name: 'nota', dataType: 'int' })
    expect(esError(cambiarRango(clase([n]), n, '100', '0'))).toBe(true)
    expect(cambio(cambiarRango(clase([n]), n, '0', '100')).payload)
      .toMatchObject({ minValue: '0', maxValue: '100' })
  })

  it('agregar una columna es agregar un atributo al final, sin repetir el id surrogado', () => {
    const c = cambio(agregarColumna(clase(), 'nuevo', 'observacion', 'text', true, false))

    expect(c.type).toBe('ADD_ATTRIBUTE')
    expect(c.payload).toMatchObject({ attributeId: 'nuevo', classId: 'c1', name: 'observacion',
      dataType: 'text', required: true, ordinal: 1 })
    expect(esError(agregarColumna(clase(), 'x', 'id', 'uuid', true, false))).toBe(true)
  })

  it('renombrar la tabla renombra la clase', () => {
    const c = cambio(renombrarTabla(clase(), 'prestamo'))
    expect(c).toMatchObject({ type: 'RENAME_CLASS', payload: { classId: 'c1', name: 'Prestamo' } })
    expect(renombrarTabla(clase(), 'ficha_prestamo')).toBeNull()
  })
})

describe('claves foraneas', () => {
  it('el nombre de la FK es el rol del extremo apuntado', () => {
    const c = cambio(renombrarFk(relacion(), 'SOURCE', persona, 'prestatario_id'))
    expect(c.payload).toEqual({ relationId: 'r1', sourceRole: 'prestatario' })
  })

  it('volver al nombre de la clase quita el rol en vez de escribirlo', () => {
    const conRol = relacion({ sourceRole: 'prestatario' })
    const c = cambio(renombrarFk(conRol, 'SOURCE', persona, 'persona_id'))
    expect(c.payload).toEqual({ relationId: 'r1', sourceRole: null })
  })

  it('NOT NULL en la FK es la multiplicidad del extremo apuntado', () => {
    const c = cambio(cambiarObligatorioFk(relacion(), 'SOURCE', persona, false))
    expect(c.payload).toEqual({ relationId: 'r1', sourceMultiplicity: '0..1' })
  })

  it('sin multiplicidades, escribe las dos para que siga siendo 1:N y no pase a 1:1', () => {
    const sinNada = relacion({ sourceMultiplicity: null, targetMultiplicity: null })
    const c = cambio(cambiarObligatorioFk(sinNada, 'SOURCE', persona, true))
    expect(c.payload).toEqual({ relationId: 'r1', sourceMultiplicity: '1', targetMultiplicity: '0..*' })
  })

  it('ON DELETE cambia el tipo de relacion, que es lo que lo decide', () => {
    expect(cambio(cambiarOnDelete(relacion(), 'CASCADE')).payload)
      .toEqual({ relationId: 'r1', kind: 'COMPOSITION' })
    expect(cambio(cambiarOnDelete(relacion(), 'SET NULL')).payload)
      .toEqual({ relationId: 'r1', kind: 'AGGREGATION' })
    expect(cambiarOnDelete(relacion(), 'RESTRICT')).toBeNull()
  })
})
