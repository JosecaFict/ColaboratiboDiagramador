import { describe, expect, it } from 'vitest'
import type { EstadoModelo } from '../canvas/applyOperation'
import type { UmlAttribute, UmlClass, UmlRelationship } from '../contracts/command'
import { identificadorJava, revisarParaGenerar } from './listoParaGenerar'

const atributo = (id: string, name: string, dataType: string, pk = false) =>
  ({ id, classId: '', name, dataType, pk, required: pk, unique: pk }) as unknown as UmlAttribute
const clase = (id: string, name: string, attributes: UmlAttribute[]): UmlClass =>
  ({ id, diagramId: 'd', name, attributes, methods: [] }) as unknown as UmlClass
const rel = (id: string, s: string, t: string, ms: string | null, mt: string | null): UmlRelationship =>
  ({ id, diagramId: 'd', sourceClassId: s, targetClassId: t, kind: 'ASSOCIATION', sourceMultiplicity: ms,
    targetMultiplicity: mt, identifying: false, degree: 2 }) as UmlRelationship

const sano: EstadoModelo = { diagramId: 'd', layout: {},
  classes: [clase('c', 'Cliente', [atributo('c1', 'idCliente', 'uuid', true), atributo('c2', 'nombre', 'string')]),
    clase('v', 'Venta', [atributo('v1', 'idVenta', 'uuid', true), atributo('v2', 'fecha', 'date')])],
  relationships: [rel('r', 'c', 'v', '1', '0..*')] }

describe('listo para generar', () => {
  it('un diagrama sano esta listo y no tiene bloqueantes', () => {
    const r = revisarParaGenerar(sano, { advertencias: [], correcciones: [] }, [])
    expect(r.recomendaciones).toEqual([])
    expect(r.generacion).toEqual({ bloqueantes: 0 })
    expect(r.resumen).toContain('listo para generar')
  })

  it('detecta lo que impide generar y trae el arreglo directo', () => {
    const roto: EstadoModelo = { ...sano,
      classes: [clase('c', 'Cliente', [atributo('c2', 'nombre', 'string'), atributo('c3', 'class', 'string')]),
        clase('v', 'Ventá', [atributo('v1', 'idVenta', 'uuid', true), atributo('v2', 'monto', 'dinero')])],
      relationships: [rel('r', 'c', 'v', '1', null)] }
    const r = revisarParaGenerar(roto, null, null)
    const titulos = r.recomendaciones.map((x) => x.titulo)
    expect(titulos).toEqual(expect.arrayContaining([
      'La clase Cliente no tiene clave primaria.',
      'El atributo Cliente.class no es un nombre valido en Java.',
      'El nombre de clase "Ventá" no es valido en Java.',
      'El atributo Ventá.monto usa el tipo "dinero", que el generador no conoce.',
      'La relacion Cliente - Ventá no define multiplicidad en ambos extremos.']))
    expect(r.generacion!.bloqueantes).toBe(5)
    const sinPk = r.recomendaciones.find((x) => x.titulo.startsWith('La clase Cliente no tiene'))!
    expect(r.arreglos![sinPk.id][0].cambios[0]).toMatchObject({ tipo: 'ADD_ATTRIBUTE',
      payload: { classId: 'c', name: 'idCliente', dataType: 'uuid', pk: true } })
    const renombre = r.recomendaciones.find((x) => x.titulo.startsWith('El nombre de clase'))!
    expect(r.arreglos![renombre.id][0].cambios[0]).toMatchObject({ tipo: 'RENAME_CLASS', payload: { classId: 'v', name: 'Venta' } })
  })

  it('lo del generador y las ambiguedades sin decidir no bloquean', () => {
    const r = revisarParaGenerar(sano, { advertencias: ['Los metodos no se generan.'],
      correcciones: [{ relationId: 'r', type: 'UPDATE_RELATION', name: 'tieneVentas', descripcion: 'Cliente-Venta pasa a tieneVentas' }] },
    [{ elementId: 'r', titulo: 'Cliente N:M Venta', decidida: false }, { elementId: 'x', titulo: 'Otra', decidida: true }])
    expect(r.generacion!.bloqueantes).toBe(0)
    expect(r.recomendaciones.map((x) => x.gravedad)).toEqual(['MEDIA', 'MEDIA', 'BAJA'])
    expect(r.arreglos![1][0].cambios[0].payload).toMatchObject({ relationId: 'r', name: 'tieneVentas' })
  })

  it('un tipo con el nombre de una clase es una enumeracion, no un error', () => {
    const conEnum: EstadoModelo = { ...sano, classes: [...sano.classes,
      clase('e', 'EstadoVenta', [atributo('e1', 'idEstadoVenta', 'uuid', true), atributo('e2', 'nombre', 'string')])] }
    conEnum.classes[1].attributes.push(atributo('v3', 'estado', 'EstadoVenta'))
    expect(revisarParaGenerar(conEnum, null, null).recomendaciones.map((x) => x.titulo))
      .not.toContain('El atributo Venta.estado usa el tipo "EstadoVenta", que el generador no conoce.')
  })

  it('academico real: periodo como texto, creditos en el catalogo y un unico prerrequisito', () => {
    const academico: EstadoModelo = { diagramId: 'd', layout: {},
      classes: [
        clase('pa', 'PlanAcademico', [atributo('pa1', 'id', 'uuid', true), atributo('pa2', 'nombreVersion', 'string')]),
        clase('pe', 'PeriodoAcademico', [atributo('pe1', 'id', 'uuid', true), atributo('pe2', 'nombre', 'string')]),
        clase('de', 'DetallePlan', [atributo('de1', 'id', 'uuid', true), atributo('de2', 'caracter', 'string')]),
        clase('as', 'Asignatura', [atributo('as1', 'id', 'uuid', true), atributo('as2', 'creditos', 'int'), atributo('as3', 'cargaHoraria', 'int')]),
        clase('pr', 'Prerrequisito', [atributo('pr1', 'id', 'uuid', true), atributo('pr2', 'periodoMinimo', 'int')]),
        clase('ad', 'AsignacionDocente', [atributo('ad1', 'id', 'uuid', true), atributo('ad2', 'periodoAcademico', 'string')]),
      ],
      relationships: [rel('r1', 'pa', 'de', '1', '0..*'), rel('r2', 'de', 'as', '0..*', '1'), rel('r3', 'de', 'pr', '0..*', '1'),
        rel('r4', 'pr', 'as', '0..*', '1'), rel('r5', 'ad', 'de', '0..*', '1')] }
    const r = revisarParaGenerar(academico, null, null)
    const buscar = (inicio: string) => r.recomendaciones.find((x) => x.titulo.startsWith(inicio))!

    const periodo = buscar('AsignacionDocente.periodoAcademico guarda como texto')
    expect(r.arreglos![periodo.id][0].cambios.map((c) => c.tipo)).toEqual(['CREATE_RELATION', 'REMOVE_ATTRIBUTE'])

    const creditos = buscar('creditos, cargaHoraria estan en Asignatura')
    expect(r.arreglos![creditos.id][0].etiqueta).toBe('Pasar creditos, cargaHoraria a DetallePlan')
    expect(r.arreglos![creditos.id][0].cambios.map((c) => c.tipo)).toEqual(['ADD_ATTRIBUTE', 'REMOVE_ATTRIBUTE', 'ADD_ATTRIBUTE', 'REMOVE_ATTRIBUTE'])

    const prerrequisito = buscar('DetallePlan - Prerrequisito obliga')
    expect(r.arreglos![prerrequisito.id][0].cambios[0].payload).toMatchObject({ relationId: 'r3', sourceMultiplicity: '1', targetMultiplicity: '0..*' })
    expect(r.recomendaciones.some((x) => x.titulo.startsWith('Prerrequisito - Asignatura'))).toBe(false)
  })

  it('arma identificadores Java validos', () => {
    expect(identificadorJava('Año de ingreso', false)).toBe('anoDeIngreso')
    expect(identificadorJava('class', false)).toBe('classValor')
    expect(identificadorJava('Ventá', true)).toBe('Venta')
    expect(identificadorJava('String', true)).toBe('StringEntidad')
  })
})
