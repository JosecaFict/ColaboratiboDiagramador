import type {
  AppliedOperation, CommandType, ElementLayout, ErKind, NotationView, Participation,
  UmlAttribute, UmlClass, UmlMethod, UmlRelationship,
} from '../contracts/command'

/**
 * ESPEJO del efecto que aplica el backend (collab/CommandApplier.java).
 *
 * El servidor es la fuente de verdad; esto solo reconstruye en el cliente lo
 * que ya paso por el bus, para no depender del snapshot GET /diagrams/{id}
 * (todavia pendiente). Misma logica, dos lenguajes: si cambia alla, cambia aca.
 */
export interface EstadoModelo {
  diagramId: string
  classes: UmlClass[]
  relationships: UmlRelationship[]
  layout: Record<string, ElementLayout>
}

export const estadoVacio = (diagramId: string): EstadoModelo =>
  ({ diagramId, classes: [], relationships: [], layout: {} })

/** Lo que devuelve GET /diagrams/{id}: espejo de DiagramSnapshot.java. */
export interface SnapshotDiagrama {
  id: string
  name: string
  currentSeq: number
  defaultView: NotationView
  classes: UmlClass[]
  relationships: UmlRelationship[]
  layout: ElementLayout[]
}

/**
 * Arrancar desde el snapshot en vez de re-aplicar todo el log: el costo pasa
 * a ser el TAMAÑO del modelo y no el de su historia.
 */
export const estadoDesdeSnapshot = (s: SnapshotDiagrama): EstadoModelo => ({
  diagramId: s.id,
  classes: s.classes,
  relationships: s.relationships,
  layout: Object.fromEntries(s.layout.map((l) => [l.elementId, l])),
})

export function aplicarOperacion(estado: EstadoModelo, op: AppliedOperation): EstadoModelo {
  const p = op.payload as Record<string, any>

  switch (op.type) {
    // Aterrizaje atomico de una foto o de una importacion XMI. El servidor lo
    // aplico como UNA operacion con UN seq; aca se pliega igual, comando por
    // comando, para que el canvas quede en el mismo estado sin pedir snapshot.
    case 'BATCH': {
      const comandos = (p.comandos ?? []) as { type: CommandType; payload: unknown }[]
      return comandos.reduce(
        (acc, c) => aplicarOperacion(acc, { ...op, type: c.type, payload: c.payload as Record<string, unknown> }),
        estado,
      )
    }

    case 'CREATE_CLASS': {
      // Idempotente: recuperar del log algo que ya vino en el snapshot o en
      // vivo no puede dibujar dos veces la misma clase.
      if (estado.classes.some((x) => x.id === p.classId)) return estado
      const c: UmlClass = {
        id: p.classId, diagramId: estado.diagramId, name: p.name,
        stereotype: p.stereotype ?? null, isAbstract: p.isAbstract ?? false,
        weak: p.weak ?? false, erKind: (p.erKind ?? 'STRONG') as ErKind,
        inheritanceStrategy: 'JOINED',
        attributes: [], methods: [],
      }
      const layout: ElementLayout = {
        elementId: p.classId, x: p.x ?? 200, y: p.y ?? 200,
        width: p.width ?? 170, height: p.height ?? 60,
      }
      return {
        ...estado,
        classes: [...estado.classes, c],
        layout: { ...estado.layout, [p.classId]: layout },
      }
    }

    case 'RENAME_CLASS':
      return conClase(estado, p.classId, (c) => ({ ...c, name: p.name }))

    case 'DELETE_CLASS': {
      const { [p.classId]: _omitido, ...layout } = estado.layout
      return {
        ...estado,
        classes: estado.classes.filter((c) => c.id !== p.classId),
        relationships: estado.relationships.filter(
          (r) => r.sourceClassId !== p.classId && r.targetClassId !== p.classId),
        layout,
      }
    }

    case 'ADD_ATTRIBUTE': {
      if (estado.classes.some((c) => c.attributes.some((x) => x.id === p.attributeId))) return estado
      const a: UmlAttribute = {
        id: p.attributeId, classId: p.classId, name: p.name,
        dataType: p.dataType ?? 'string', visibility: p.visibility ?? 'PRIVATE',
        length: p.length ?? null,
        pk: p.pk ?? false, required: p.required ?? false, unique: p.unique ?? false,
        defaultValue: p.defaultValue ?? null,
        ordinal: p.ordinal ?? 0, multivalued: p.multivalued ?? false,
        derived: p.derived ?? false, partialKey: p.partialKey ?? false,
        parentAttributeId: p.parentAttributeId ?? null,
        minValue: p.minValue ?? null, maxValue: p.maxValue ?? null,
      }
      return conClase(estado, p.classId, (c) => ({ ...c, attributes: [...c.attributes, a] }))
    }

    case 'UPDATE_ATTRIBUTE': {
      // `attributeId` identifica, no es un campo del atributo: fuera del merge.
      // Las marcas de deshacer (ADR-027) tampoco: son precondicion y auditoria,
      // y mezcladas el atributo terminaba con un campo `esperado`.
      const { attributeId, deshace: _d, esperado: _e, ...cambios } = p
      return conAtributo(estado, attributeId, (a) => ({ ...a, ...cambios }))
    }

    case 'REMOVE_ATTRIBUTE':
      return {
        ...estado,
        classes: estado.classes.map((c) => ({
          ...c, attributes: c.attributes.filter((a) => a.id !== p.attributeId),
        })),
      }

    case 'ADD_METHOD': {
      if (estado.classes.some((c) => c.methods.some((x) => x.id === p.methodId))) return estado
      const m: UmlMethod = {
        id: p.methodId, classId: p.classId, name: p.name,
        returnType: p.returnType ?? 'void', params: p.params ?? [],
        visibility: p.visibility ?? 'PUBLIC', ordinal: p.ordinal ?? 0,
      }
      return conClase(estado, p.classId, (c) => ({ ...c, methods: [...c.methods, m] }))
    }

    case 'REMOVE_METHOD':
      return {
        ...estado,
        classes: estado.classes.map((c) => ({
          ...c, methods: c.methods.filter((m) => m.id !== p.methodId),
        })),
      }

    case 'CREATE_RELATION': {
      if (estado.relationships.some((x) => x.id === p.relationId)) return estado
      const r: UmlRelationship = {
        id: p.relationId, diagramId: estado.diagramId,
        sourceClassId: p.sourceClassId, targetClassId: p.targetClassId,
        kind: p.kind, name: p.name ?? null, sourceRole: p.sourceRole ?? null, targetRole: p.targetRole ?? null,
        sourceMultiplicity: p.sourceMultiplicity ?? null, targetMultiplicity: p.targetMultiplicity ?? null,
        identifying: p.identifying ?? false,
        sourceParticipation: (p.sourceParticipation ?? null) as Participation | null,
        targetParticipation: (p.targetParticipation ?? null) as Participation | null,
        degree: p.degree ?? 2,
      }
      return { ...estado, relationships: [...estado.relationships, r] }
    }

    case 'UPDATE_RELATION': {
      const { relationId, deshace: _d, esperado: _e, ...cambios } = p
      return {
        ...estado,
        relationships: estado.relationships.map((r) => (r.id === relationId ? { ...r, ...cambios } : r)),
      }
    }

    case 'DELETE_RELATION':
      return { ...estado, relationships: estado.relationships.filter((r) => r.id !== p.relationId) }

    case 'SET_ER_FLAG': {
      if (p.targetType === 'CLASS') {
        return conClase(estado, p.targetId, (c) => ({
          ...c,
          weak: p.flag === 'weak' ? p.value : c.weak,
          erKind: p.flag === 'erKind' ? p.erKind : c.erKind,
          inheritanceStrategy: p.flag === 'inheritanceStrategy' ? p.strategy : c.inheritanceStrategy,
        }))
      }
      return conAtributo(estado, p.targetId, (a) => ({ ...a, [p.flag]: p.value }))
    }

    case 'MOVE_ELEMENT': {
      const prev = estado.layout[p.elementId]
      // Mover una caja y acomodar los quiebres de una linea entran por el
      // mismo comando: los distingue el payload (ver CommandApplier).
      // `deshace` y `esperado` (ADR-027) no se leen: son precondicion y
      // auditoria, y el servidor ya los evaluo antes de difundir.
      const l: ElementLayout = p.waypoints
        ? { elementId: p.elementId, x: prev?.x ?? 0, y: prev?.y ?? 0,
            width: prev?.width ?? 170, height: prev?.height ?? 60, waypoints: p.waypoints }
        : { elementId: p.elementId, x: p.x, y: p.y,
            width: prev?.width ?? 170, height: prev?.height ?? 60, waypoints: prev?.waypoints }
      return { ...estado, layout: { ...estado.layout, [p.elementId]: l } }
    }

    case 'RESIZE_ELEMENT': {
      // Igual que MOVE: las marcas de deshacer no forman parte del efecto.
      const prev = estado.layout[p.elementId]
      const l: ElementLayout = { elementId: p.elementId, x: prev?.x ?? 0, y: prev?.y ?? 0,
        width: p.width, height: p.height }
      return { ...estado, layout: { ...estado.layout, [p.elementId]: l } }
    }

    default:
      // SET_MODELING_DECISION no vive en EstadoModelo: las decisiones se
      // leen de /ambiguities, porque cambian el ESQUEMA DERIVADO y no el
      // modelo conceptual que dibuja el canvas. App.tsx las recarga al ver
      // pasar la operacion. CONFIRM_SCHEMA tampoco: la confirmacion viaja en
      // la Vista Fisica, que se vuelve a pedir con cada operacion.
      return estado
  }
}

function conClase(estado: EstadoModelo, classId: string, f: (c: UmlClass) => UmlClass): EstadoModelo {
  return { ...estado, classes: estado.classes.map((c) => (c.id === classId ? f(c) : c)) }
}

function conAtributo(estado: EstadoModelo, attributeId: string, f: (a: UmlAttribute) => UmlAttribute): EstadoModelo {
  return {
    ...estado,
    classes: estado.classes.map((c) => ({
      ...c, attributes: c.attributes.map((a) => (a.id === attributeId ? f(a) : a)),
    })),
  }
}
