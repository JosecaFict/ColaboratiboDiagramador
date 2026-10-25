import type { EstadoModelo } from '../canvas/applyOperation'
import type { CommandType, RelationKind } from '../contracts/command'
import type { SugerenciaAtributo } from '../panel/PanelPropiedades'

// ============================================================================
// PROPUESTA DE IMPORTACION: espejo de interop/PropuestaDeImportacion.java
//
// El backend LEE y propone; nunca escribe. El usuario revisa aca, y lo que
// confirma se convierte en comandos que entran como UN BATCH por el bus
// (ADR-002): mismos permisos, locks y secuencia que cualquier otro cambio.
// ============================================================================

export interface AtributoPropuesto {
  nombre: string
  tipoLeido: string | null
  tipo: string
  pk: boolean
  requerido: boolean
  unico: boolean
  multivaluado: boolean
  derivado: boolean
  claveParcial: boolean
  yaExiste: boolean
  sugerencia: SugerenciaAtributo
}

export interface ParametroPropuesto {
  nombre: string
  tipo: string | null
}

/** Un metodo del archivo, tal como lo trajo (REGLA 6: no se descarta). */
export interface MetodoPropuesto {
  nombre: string
  tipoRetorno: string | null
  parametros: ParametroPropuesto[]
  yaExiste: boolean
}

/** Donde estaba la clase en el diagrama del archivo (EA o UML DI), en sus coordenadas. */
export interface PosicionLeida {
  x: number
  y: number
  ancho: number
  alto: number
}

export interface ClasePropuesta {
  clave: string
  id: string
  nombre: string
  estereotipo: string | null
  abstracta: boolean
  idExistente: string | null
  atributos: AtributoPropuesto[]
  metodos: MetodoPropuesto[]
  /** null si el archivo no la dibuja: va a la grilla. */
  posicion?: PosicionLeida | null
}

export interface RelacionPropuesta {
  claveOrigen: string
  claveDestino: string
  tipo: RelationKind
  nombre: string | null
  multiplicidadOrigen: string | null
  multiplicidadDestino: string | null
  rolOrigen: string | null
  rolDestino: string | null
  identificadora: boolean
}

export interface PropuestaDeImportacion {
  formato: string
  fuente: string
  clases: ClasePropuesta[]
  relaciones: RelacionPropuesta[]
  advertencias: string[]
}

// ---------------------------------------------------------------- REVISION

/** Lo que el usuario puede cambiar de cada atributo antes de aplicar. */
export interface AtributoEnRevision extends AtributoPropuesto {
  incluir: boolean
  /** Con rol RELACION: crear la relacion en vez del atributo. */
  comoRelacion: boolean
}

export interface MetodoEnRevision extends MetodoPropuesto {
  incluir: boolean
}

export interface ClaseEnRevision extends Omit<ClasePropuesta, 'atributos' | 'metodos'> {
  incluir: boolean
  atributos: AtributoEnRevision[]
  metodos: MetodoEnRevision[]
}

export interface RelacionEnRevision extends RelacionPropuesta {
  incluir: boolean
}

export interface Revision {
  formato: string
  fuente: string
  clases: ClaseEnRevision[]
  relaciones: RelacionEnRevision[]
  advertencias: string[]
}

/**
 * Los valores por defecto de la revision son las SUGERENCIAS, no decisiones:
 * todo se puede destildar o cambiar antes de aplicar.
 */
export function revisionDe(p: PropuestaDeImportacion): Revision {
  return {
    formato: p.formato,
    fuente: p.fuente,
    advertencias: p.advertencias,
    relaciones: p.relaciones.map((r) => ({ ...r, incluir: true })),
    clases: p.clases.map((c) => ({
      ...c,
      incluir: true,
      atributos: c.atributos.map((a) => ({
        ...a,
        // Lo que ya esta en la clase existente, o la identidad que la
        // herramienta ya cubre con su id UUID, arranca destildado.
        incluir: !a.yaExiste && a.sugerencia.rol !== 'IDENTIDAD',
        comoRelacion: a.sugerencia.rol === 'RELACION' && !!a.sugerencia.claseRelacionadaId,
      })),
      // REGLA 6: los metodos no se descartan, arrancan tildados salvo que la
      // clase existente ya tenga uno con ese nombre.
      metodos: c.metodos.map((m) => ({ ...m, incluir: !m.yaExiste })),
    })),
  }
}

// ------------------------------------------------------- REVISION -> COMANDOS

/** Espejo de Lote.TOPE_IMPORTACION: el servidor rechaza un lote mas grande. */
export const TOPE_IMPORTACION = 500

export interface CambioDelLote {
  type: CommandType
  payload: Record<string, unknown>
}

export interface Plan {
  comandos: CambioDelLote[]
  conteo: { clases: number; atributos: number; relaciones: number; metodos: number }
  /** Lo que no se va a aplicar y por que: se muestra antes de confirmar. */
  avisos: string[]
}

const clave = (s: string) => s.trim().toLowerCase()

/**
 * Convierte lo revisado en los cambios del BATCH.
 *
 * Funcion pura y separada de la pantalla a proposito: es la parte con reglas
 * (que ya existe, a quien apunta cada relacion, que se omite) y se prueba sin
 * montar nada. Se calcula contra el modelo ACTUAL del cliente, no contra el
 * del momento en que se leyo el archivo: otro pudo crear una clase mientras
 * el usuario revisaba.
 */
export function planDeImportacion(r: Revision, modelo: EstadoModelo, nuevoId: () => string): Plan {
  const comandos: CambioDelLote[] = []
  const avisos: string[] = []
  const conteo = { clases: 0, atributos: 0, relaciones: 0, metodos: 0 }
  const existentePorNombre = new Map(modelo.classes.map((c) => [clave(c.name), c]))

  // 1. A que clase del diagrama corresponde cada clave del archivo.
  const idDeClave = new Map<string, string>()
  const idReservadoAReal = new Map<string, string>()
  for (const c of r.clases) {
    const existente = existentePorNombre.get(clave(c.nombre))
    if (existente) {
      idDeClave.set(c.clave, existente.id)
      idReservadoAReal.set(c.id, existente.id)
    } else if (c.incluir) {
      idDeClave.set(c.clave, c.id)
      idReservadoAReal.set(c.id, c.id)
    }
  }

  // 2. Las clases nuevas, debajo de lo que ya esta dibujado. Las que el
  //    archivo dibuja conservan su disposicion (ADR-031); el resto, en grilla.
  const piso = Object.values(modelo.layout).reduce((max, l) => Math.max(max, l.y + l.height), 0)
  const base = piso === 0 ? 120 : piso + 120
  const nuevas = r.clases.filter((c) => c.incluir && !existentePorNombre.has(clave(c.nombre)))
  const lugarDe = disposicion(nuevas, base)
  let lugar = 0
  for (const c of nuevas) {
    const dibujada = lugarDe.cajas.get(c.id)
    const caja = dibujada ?? {
      x: 160 + (lugar % 4) * 220, y: lugarDe.debajo + Math.floor(lugar / 4) * 200, width: undefined,
    }
    if (!dibujada) lugar++
    comandos.push({
      type: 'CREATE_CLASS',
      payload: {
        classId: c.id, name: c.nombre.trim(), stereotype: c.estereotipo, isAbstract: c.abstracta,
        x: caja.x, y: caja.y, ...(caja.width ? { width: caja.width } : {}),
      },
    })
    conteo.clases++
  }

  // 3. Relaciones del archivo (van antes que las que salen de atributos FK,
  //    para no duplicar una que el archivo ya declara).
  const pares = new Set<string>()
  const par = (a: string, b: string) => [a, b].sort().join('|')
  for (const rel of r.relaciones) {
    if (!rel.incluir) continue
    const origen = idDeClave.get(rel.claveOrigen)
    const destino = idDeClave.get(rel.claveDestino)
    if (!origen || !destino) {
      avisos.push(`Una relacion ${rel.claveOrigen} → ${rel.claveDestino} no se crea: una de sus clases no se importa.`)
      continue
    }
    pares.add(par(origen, destino))
    comandos.push({
      type: 'CREATE_RELATION',
      payload: {
        relationId: nuevoId(), sourceClassId: origen, targetClassId: destino, kind: rel.tipo,
        name: rel.nombre, sourceMultiplicity: rel.multiplicidadOrigen, targetMultiplicity: rel.multiplicidadDestino,
        sourceRole: rel.rolOrigen, targetRole: rel.rolDestino, identifying: rel.identificadora,
      },
    })
    conteo.relaciones++
  }

  // 4. Atributos, o la relacion que en realidad son.
  for (const c of r.clases) {
    const idClase = idDeClave.get(c.clave)
    if (!c.incluir || !idClase) continue
    const existente = existentePorNombre.get(clave(c.nombre))
    const yaTiene = new Set((existente?.attributes ?? []).map((a) => clave(a.name)))
    let ordinal = existente?.attributes.length ?? 0

    for (const a of c.atributos) {
      if (!a.incluir) continue
      if (a.comoRelacion && a.sugerencia.claseRelacionadaId) {
        const destino = idReservadoAReal.get(a.sugerencia.claseRelacionadaId) ?? a.sugerencia.claseRelacionadaId
        const destinoEsValido = modelo.classes.some((x) => x.id === destino)
          || comandos.some((x) => x.type === 'CREATE_CLASS' && x.payload.classId === destino)
        if (!destinoEsValido) {
          avisos.push(`${c.nombre}.${a.nombre} no se convierte en relacion: ${a.sugerencia.claseRelacionada} no se importa.`)
          continue
        }
        if (pares.has(par(idClase, destino))) continue   // el archivo ya la declara
        pares.add(par(idClase, destino))
        // idMaterial en Prestamo: muchos prestamos, un material. La FK la
        // deriva la Regla 3 del lado "muchos".
        comandos.push({
          type: 'CREATE_RELATION',
          payload: {
            relationId: nuevoId(), sourceClassId: idClase, targetClassId: destino, kind: 'ASSOCIATION',
            sourceMultiplicity: '0..*', targetMultiplicity: '1',
          },
        })
        conteo.relaciones++
        continue
      }
      if (yaTiene.has(clave(a.nombre))) {
        avisos.push(`${c.nombre}.${a.nombre} ya existe en el diagrama: no se agrega de nuevo.`)
        continue
      }
      yaTiene.add(clave(a.nombre))
      comandos.push({
        type: 'ADD_ATTRIBUTE',
        payload: {
          attributeId: nuevoId(), classId: idClase, name: a.nombre.trim(), dataType: a.tipo,
          pk: a.pk, required: a.requerido, unique: a.unico, multivalued: a.multivaluado,
          derived: a.derivado, partialKey: a.claveParcial, ordinal: ordinal++,
        },
      })
      conteo.atributos++
    }
  }

  // 5. Metodos (REGLA 6): no van al DDL, pero si al Service generado.
  for (const c of r.clases) {
    const idClase = idDeClave.get(c.clave)
    if (!c.incluir || !idClase) continue
    const existente = existentePorNombre.get(clave(c.nombre))
    const yaTiene = new Set((existente?.methods ?? []).map((m) => clave(m.name)))
    let ordinal = existente?.methods.length ?? 0

    for (const m of c.metodos) {
      if (!m.incluir) continue
      if (yaTiene.has(clave(m.nombre))) {
        avisos.push(`${c.nombre}.${m.nombre}() ya existe en el diagrama: no se agrega de nuevo.`)
        continue
      }
      yaTiene.add(clave(m.nombre))
      comandos.push({
        type: 'ADD_METHOD',
        payload: {
          methodId: nuevoId(), classId: idClase, name: m.nombre.trim(),
          returnType: m.tipoRetorno ?? 'void', params: m.parametros.map((p) => ({ name: p.nombre, type: p.tipo ?? 'string' })),
          ordinal: ordinal++,
        },
      })
      conteo.metodos++
    }
  }

  return { comandos, conteo, avisos }
}

/** Ancho de una clase en ColabModel (el default de CREATE_CLASS). */
const ANCHO_CLASE = 170

/**
 * Traslada el diagrama del archivo al lienzo: la clase mas arriba a la
 * izquierda queda en (160, base) y el resto guarda su distancia. EA dibuja
 * cajas mas angostas y mas bajas que las nuestras (90-150 px contra 170, y
 * sin el alto de la fila de cada atributo), asi que todo se agranda en la
 * misma proporcion -entre x1 y x2- para que al ensancharlas no se encimen.
 */
function disposicion(nuevas: ClasePropuesta[], base: number) {
  const cajas = new Map<string, { x: number; y: number; width: number }>()
  const dibujadas = nuevas.filter((c) => c.posicion)
  if (dibujadas.length === 0) return { cajas, debajo: base }

  const anchos = dibujadas.map((c) => c.posicion!.ancho).filter((a) => a > 0).sort((a, b) => a - b)
  const mediana = anchos.length ? anchos[Math.floor(anchos.length / 2)] : ANCHO_CLASE
  const escala = Math.min(2, Math.max(1, ANCHO_CLASE / mediana))
  const minX = Math.min(...dibujadas.map((c) => c.posicion!.x))
  const minY = Math.min(...dibujadas.map((c) => c.posicion!.y))
  let fondo = base
  for (const c of dibujadas) {
    const p = c.posicion!
    const y = Math.round(base + (p.y - minY) * escala)
    cajas.set(c.id, {
      x: Math.round(160 + (p.x - minX) * escala),
      y,
      width: Math.max(ANCHO_CLASE, Math.round(p.ancho * escala)),
    })
    fondo = Math.max(fondo, y + Math.round(p.alto * escala))
  }
  return { cajas, debajo: fondo + 160 }
}
