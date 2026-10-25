import type { ElementLayout, UmlClass, UmlRelationship } from '../contracts/command'

/**
 * Modelo de prueba de Fase 0.
 *
 * Elegido a proposito para ejercitar los casos dificiles desde el dia 1:
 *   - entidad DEBIL con clave parcial (Ejemplar) -> PK compuesta -> 2FN real
 *   - atributo MULTIVALUADO (telefono) -> Regla 12 -> tabla propia (1FN)
 *   - atributo DERIVADO (edad)
 *   - relacion IDENTIFICADORA (Libro -- Ejemplar)
 *   - participacion TOTAL
 *   - METODO con parametros (Libro.prestar) -> Regla 6 -> stub en el Service
 */
const D = '00000000-0000-0000-0000-000000000001'

export const clasesDemo: UmlClass[] = [
  {
    id: 'c1', diagramId: D, name: 'Libro', isAbstract: false, weak: false, erKind: 'STRONG', inheritanceStrategy: 'JOINED',
    attributes: [
      { id: 'a1', classId: 'c1', name: 'isbn',   dataType: 'string', visibility: 'PRIVATE', pk: true,  required: true,  unique: true,  ordinal: 0, multivalued: false, derived: false, partialKey: false },
      { id: 'a2', classId: 'c1', name: 'titulo', dataType: 'string', visibility: 'PRIVATE', pk: false, required: true,  unique: false, ordinal: 1, multivalued: false, derived: false, partialKey: false },
      { id: 'a3', classId: 'c1', name: 'anio',   dataType: 'int', visibility: 'PRIVATE',    pk: false, required: false, unique: false, ordinal: 2, multivalued: false, derived: false, partialKey: false },
    ],
    methods: [
      { id: 'm1', classId: 'c1', name: 'prestar', returnType: 'boolean',
        params: [{ name: 'socio', type: 'uuid' }, { name: 'dias', type: 'int' }],
        visibility: 'PUBLIC', ordinal: 0 },
    ],
  },
  {
    id: 'c2', diagramId: D, name: 'Ejemplar', isAbstract: false, weak: true, erKind: 'WEAK', inheritanceStrategy: 'JOINED',
    attributes: [
      { id: 'a4', classId: 'c2', name: 'nroCopia', dataType: 'int', visibility: 'PRIVATE',    pk: false, required: true,  unique: false, ordinal: 0, multivalued: false, derived: false, partialKey: true },
      { id: 'a5', classId: 'c2', name: 'estado',   dataType: 'string', visibility: 'PRIVATE', pk: false, required: true,  unique: false, ordinal: 1, multivalued: false, derived: false, partialKey: false },
    ],
    methods: [],
  },
  {
    id: 'c3', diagramId: D, name: 'Socio', isAbstract: false, weak: false, erKind: 'STRONG', inheritanceStrategy: 'JOINED',
    attributes: [
      { id: 'a6', classId: 'c3', name: 'carnet',   dataType: 'string', visibility: 'PRIVATE', pk: true,  required: true,  unique: true,  ordinal: 0, multivalued: false, derived: false, partialKey: false },
      { id: 'a7', classId: 'c3', name: 'nombre',   dataType: 'string', visibility: 'PRIVATE', pk: false, required: true,  unique: false, ordinal: 1, multivalued: false, derived: false, partialKey: false },
      { id: 'a8', classId: 'c3', name: 'telefono', dataType: 'string', visibility: 'PRIVATE', pk: false, required: false, unique: false, ordinal: 2, multivalued: true,  derived: false, partialKey: false },
      { id: 'a9', classId: 'c3', name: 'edad',     dataType: 'int', visibility: 'PRIVATE',    pk: false, required: false, unique: false, ordinal: 3, multivalued: false, derived: true,  partialKey: false },
    ],
    methods: [],
  },
]

export const relacionesDemo: UmlRelationship[] = [
  {
    id: 'r1', diagramId: D, sourceClassId: 'c1', targetClassId: 'c2',
    kind: 'COMPOSITION', name: 'tiene',
    sourceRole: 'libro', targetRole: 'ejemplares',
    sourceMultiplicity: '1', targetMultiplicity: '1..*',
    identifying: true, sourceParticipation: 'PARTIAL', targetParticipation: 'TOTAL', degree: 2,
  },
  {
    id: 'r2', diagramId: D, sourceClassId: 'c3', targetClassId: 'c2',
    kind: 'ASSOCIATION', name: 'presta',
    sourceRole: 'socio', targetRole: 'ejemplar',
    sourceMultiplicity: '0..1', targetMultiplicity: '0..*',
    identifying: false, sourceParticipation: 'PARTIAL', targetParticipation: 'PARTIAL', degree: 2,
  },
]

export const layoutDemo: Record<string, ElementLayout> = {
  c1: { elementId: 'c1', x: 180, y: 120, width: 170, height: 60 },
  c2: { elementId: 'c2', x: 660, y: 130, width: 170, height: 60 },
  c3: { elementId: 'c3', x: 430, y: 450, width: 170, height: 60 },
}
