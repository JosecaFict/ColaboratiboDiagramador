import { describe, expect, it } from 'vitest'
import type { RelationKind, UmlClass, UmlRelationship } from '../contracts/command'
import { construirReificacion, esNaM, nombreSugerido } from './reificar'

/**
 * Convertir una N:M en una clase intermedia es una TRANSFORMACIÓN del modelo, y
 * eso la hace peligrosa de una forma concreta: borra una relación que existía. Lo
 * que se fija acá es que lo que sale es un solo lote atómico, con la dirección
 * correcta de las dos relaciones nuevas, y que cuando algo no cierra no sale nada.
 */
describe('reificar una relacion N:M', () => {
  it('emite UN lote con la clase, las dos relaciones y el borrado de la N:M', () => {
    const { comando, motivo, classId } = reificar(relacionNaM(), 'Prestamo')

    expect(motivo).toBeNull()
    expect(comando!.type).toBe('BATCH')
    const dentro = comando!.payload.comandos as { type: string; payload: Record<string, unknown> }[]
    expect(dentro.map((c) => c.type)).toEqual([
      'CREATE_CLASS', 'CREATE_RELATION', 'CREATE_RELATION', 'DELETE_RELATION',
    ])
    expect(dentro[0].payload.classId).toBe(classId)
    expect(dentro[0].payload.name).toBe('Prestamo')
    // El borrado va ULTIMO: si algo anterior falla, la N:M original sigue ahi.
    expect(dentro[3].payload.relationId).toBe('r1')
  })

  it('pone a la intermedia del lado "muchos" en las dos relaciones', () => {
    // Es la mitad del sentido de la transformacion: la Regla 3 pone la FK en el
    // lado "muchos", asi que las DOS claves foraneas tienen que caer en la tabla
    // intermedia. Al reves, las FK irian a las clases y el esquema diria otra cosa.
    const dentro = pasos(reificar(relacionNaM(), 'Prestamo'))
    const nuevas = dentro.filter((c) => c.type === 'CREATE_RELATION')

    expect(nuevas).toHaveLength(2)
    for (const r of nuevas) {
      expect(r.payload.sourceMultiplicity).toBe('1')
      expect(r.payload.targetMultiplicity).toBe('0..*')
      expect(r.payload.targetClassId).toBe(dentro[0].payload.classId)
      expect(r.payload.kind).toBe('ASSOCIATION')
    }
    expect(nuevas.map((r) => r.payload.sourceClassId)).toEqual(['c1', 'c2'])
  })

  it('arrastra los roles, que son los que nombran las FK', () => {
    const dentro = pasos(reificar(
      { ...relacionNaM(), sourceRole: 'alumno', targetRole: 'libro' }, 'Prestamo'))
    const nuevas = dentro.filter((c) => c.type === 'CREATE_RELATION')

    expect(nuevas.map((r) => r.payload.sourceRole)).toEqual(['alumno', 'libro'])
  })

  it('en una N:M reflexiva inventa dos roles distintos', () => {
    // Sin roles distintos, las dos FK de la intermedia se llamarian igual y el
    // esquema no compila. Es el mismo problema que ya tenia la tabla puente
    // reflexiva, y se resuelve igual.
    const reflexiva: UmlRelationship = { ...relacionNaM(), targetClassId: 'c1' }
    const nuevas = pasos(reificar(reflexiva, 'Requisito'))
      .filter((c) => c.type === 'CREATE_RELATION')

    expect(nuevas.map((r) => r.payload.sourceRole)).toEqual(['origen', 'destino'])
    expect(new Set(nuevas.map((r) => r.payload.sourceRole)).size).toBe(2)
  })

  it('con PK compuesta, marca la clase debil y las dos relaciones identificadoras', () => {
    // Es la forma clasica de la tabla puente --VENDE(NroV, CodP, cantidad,
    // precio)-- y la unica que impide repetir el mismo par sin que nadie agregue
    // un UNIQUE a mano. La PK compuesta sale de la Regla 11: entidad debil
    // identificada por sus duenios.
    const dentro = pasos(reificar(relacionNaM(), 'Vende', true))

    expect(dentro.map((c) => c.type)).toEqual([
      'CREATE_CLASS', 'SET_ER_FLAG', 'CREATE_RELATION', 'CREATE_RELATION', 'DELETE_RELATION',
    ])
    expect(dentro[1].payload).toMatchObject({ targetType: 'CLASS', flag: 'weak', value: true })
    expect(dentro[1].payload.targetId).toBe(dentro[0].payload.classId)
    for (const r of dentro.filter((c) => c.type === 'CREATE_RELATION')) {
      expect(r.payload.identifying).toBe(true)
    }
  })

  it('sin PK compuesta, la clase lleva su propio id y nada es identificador', () => {
    const dentro = pasos(reificar(relacionNaM(), 'Vende', false))

    expect(dentro.some((c) => c.type === 'SET_ER_FLAG')).toBe(false)
    for (const r of dentro.filter((c) => c.type === 'CREATE_RELATION')) {
      expect(r.payload.identifying).toBe(false)
    }
  })

  it('no toca nada si la relacion no es N:M', () => {
    const unoAMuchos: UmlRelationship = { ...relacionNaM(), sourceMultiplicity: '1' }
    const { comando, motivo } = reificar(unoAMuchos, 'Prestamo')

    expect(comando).toBeNull()
    expect(motivo).toContain('N:M')
  })

  it('no manda un lote que el servidor va a rechazar por nombre repetido', () => {
    // Lo rechazaria el backend y se caeria el lote ENTERO, incluido el borrado
    // de la N:M. Atajarlo aca convierte "no se aplico nada" en una correccion de
    // una palabra.
    const { comando, motivo } = reificar(relacionNaM(), 'estudiante')

    expect(comando).toBeNull()
    expect(motivo).toContain('Ya hay una clase')
  })

  it('exige un nombre', () => {
    expect(reificar(relacionNaM(), '   ').motivo).toContain('nombre')
  })

  it('sugiere el nombre de la relacion, y si no tiene, los dos pegados', () => {
    expect(nombreSugerido(relacionNaM(), clase('c1', 'Estudiante'), clase('c2', 'Libros')))
      .toBe('EstudianteLibros')
    expect(nombreSugerido({ ...relacionNaM(), name: 'ficha de prestamo' },
      clase('c1', 'Estudiante'), clase('c2', 'Libros'))).toBe('FichaDePrestamo')
  })

  it('reconoce que una generalizacion no se reifica', () => {
    expect(esNaM({ ...relacionNaM(), kind: 'GENERALIZATION' as RelationKind })).toBe(false)
  })
})

// --------------------------------------------------------------------- UTILES

function relacionNaM(): UmlRelationship {
  return {
    id: 'r1',
    diagramId: 'd1',
    sourceClassId: 'c1',
    targetClassId: 'c2',
    kind: 'ASSOCIATION',
    name: null,
    sourceMultiplicity: '0..*',
    targetMultiplicity: '0..*',
    identifying: false,
    degree: 2,
  }
}

function clase(id: string, name: string): UmlClass {
  return {
    id,
    diagramId: 'd1',
    name,
    isAbstract: false,
    weak: false,
    erKind: 'NONE' as UmlClass['erKind'],
    inheritanceStrategy: 'JOINED' as UmlClass['inheritanceStrategy'],
    attributes: [],
    methods: [],
  }
}

function reificar(relacion: UmlRelationship, nombre: string, pkCompuesta = false) {
  let n = 0
  return construirReificacion({
    relacion,
    origen: clase('c1', 'Estudiante'),
    destino: clase('c2', 'Libros'),
    nombre,
    pkCompuesta,
    x: 300,
    y: 400,
    diagramId: 'd1',
    baseSeq: 7,
    nombresUsados: ['Estudiante', 'Libros'],
    nuevoId: () => `id-${++n}`,
  })
}

function pasos(resultado: ReturnType<typeof construirReificacion>) {
  return resultado.comando!.payload.comandos as
    { type: string; payload: Record<string, unknown> }[]
}
