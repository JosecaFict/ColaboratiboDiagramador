import type { UmlAttribute, UmlClass, UmlMethod, UmlRelationship } from '../contracts/command'
import { aplanar, CAMPOS, calcularDiffEntreModelos, iguales, type ElementoPlano } from './diff'
import {
  CAMPO_EXISTENCIA, GRUPOS, TIPO_DE_GRUPO,
  type Conflicto, type Eleccion, type MergePreview, type NombreDeGrupo, type Resolucion, type SnapshotDeVersion,
} from './tipos'

type Modelo = Pick<SnapshotDeVersion, 'classes' | 'relationships'>

/**
 * Merge a tres bandas en el cliente.
 *
 * El merge REAL lo hace el servidor (`/merge/preview` y `/merge`). Esto sirve
 * para el modo simulado, y para que los tests fijen las reglas que la interfaz
 * da por ciertas:
 *
 *  - un cambio de un solo lado se aplica solo (cambio limpio);
 *  - si los dos lados tocaron el MISMO campo del MISMO elemento con valores
 *    distintos, es un conflicto: hay que elegir "nuestro" o "suyo";
 *  - si los dos lo dejaron igual, no hay conflicto;
 *  - si un lado borra un elemento y el otro lo modifica, es un conflicto de
 *    `existencia`: "nuestro" / "suyo" decide si el elemento queda o se va;
 *  - dos lados que agregan el mismo id (no deberia pasar: los ids son uuid)
 *    se toman como el mismo elemento y se comparan campo a campo.
 */
export function previewDeMerge(base: Modelo, nuestro: Modelo, suyo: Modelo): MergePreview {
  return {
    nuestros: calcularDiffEntreModelos(base, nuestro),
    suyos: calcularDiffEntreModelos(base, suyo),
    conflictos: detectarConflictos(base, nuestro, suyo),
  }
}

export function detectarConflictos(base: Modelo, nuestro: Modelo, suyo: Modelo): Conflicto[] {
  const b = aplanar(base), n = aplanar(nuestro), s = aplanar(suyo)
  const conflictos: Conflicto[] = []
  for (const g of GRUPOS) {
    const tipo = TIPO_DE_GRUPO[g]
    const ids = new Set([...b[g].keys(), ...n[g].keys(), ...s[g].keys()])
    for (const id of ids) {
      const eb = b[g].get(id), en = n[g].get(id), es = s[g].get(id)

      // Borrado de un lado y modificado del otro.
      if (eb && (!en !== !es)) {
        const quedo = (en ?? es)!
        if (!iguales(proyectar(tipo, quedo), proyectar(tipo, eb))) {
          conflictos.push({
            elementId: id, tipo, campo: CAMPO_EXISTENCIA,
            base: 'existe', nuestro: en ? 'existe' : 'eliminado', suyo: es ? 'existe' : 'eliminado',
          })
        }
        continue
      }
      if (!en || !es) continue   // agregado o borrado de un solo lado, o borrado de los dos: limpio

      for (const campo of CAMPOS[tipo]) {
        const vb = eb?.dato[campo], vn = en.dato[campo], vs = es.dato[campo]
        if (iguales(vn, vs)) continue                    // los dos lo dejaron igual
        if (eb && iguales(vb, vn)) continue              // solo cambio el suyo
        if (eb && iguales(vb, vs)) continue              // solo cambio el nuestro
        conflictos.push({ elementId: id, tipo, campo, base: vb ?? null, nuestro: vn ?? null, suyo: vs ?? null })
      }
    }
  }
  return conflictos
}

const proyectar = (tipo: ReturnType<typeof tipoDe>, e: ElementoPlano): Record<string, unknown> =>
  Object.fromEntries(CAMPOS[tipo].map((c) => [c, e.dato[c] ?? null]))
const tipoDe = (g: NombreDeGrupo) => TIPO_DE_GRUPO[g]

export const claveDeConflicto = (c: Pick<Conflicto, 'elementId' | 'campo'>): string => `${c.elementId}|${c.campo}`

/** Conflictos que todavia no tienen una eleccion. */
export function sinResolver(conflictos: Conflicto[], resoluciones: Resolucion[]): Conflicto[] {
  const hechas = new Set(resoluciones.map(claveDeConflicto))
  return conflictos.filter((c) => !hechas.has(claveDeConflicto(c)))
}

/** Pone (o cambia) la eleccion de un conflicto, sin tocar las demas. */
export function elegir(resoluciones: Resolucion[], c: Pick<Conflicto, 'elementId' | 'campo'>, eleccion: Eleccion): Resolucion[] {
  const clave = claveDeConflicto(c)
  return [...resoluciones.filter((r) => claveDeConflicto(r) !== clave), { elementId: c.elementId, campo: c.campo, eleccion }]
}

/** "Quedarme con lo nuestro / lo suyo" para todos los conflictos de una vez. */
export function elegirTodos(conflictos: Conflicto[], eleccion: Eleccion): Resolucion[] {
  return conflictos.map((c) => ({ elementId: c.elementId, campo: c.campo, eleccion }))
}

/**
 * El modelo que queda al aplicar el merge de `suyo` sobre `nuestro`, con `base`
 * como ancestro comun. Un conflicto sin eleccion se queda con lo nuestro (el
 * servidor, en cambio, lo rechazaria: la interfaz no deja llegar a ese punto).
 */
export function fusionar(base: Modelo, nuestro: Modelo, suyo: Modelo, resoluciones: Resolucion[]): Modelo {
  const b = aplanar(base), n = aplanar(nuestro), s = aplanar(suyo)
  const elegida = new Map(resoluciones.map((r) => [claveDeConflicto(r), r.eleccion]))

  const resultado: Record<NombreDeGrupo, Map<string, Record<string, unknown> & { __claseId?: string }>> = {
    clases: new Map(), atributos: new Map(), metodos: new Map(), relaciones: new Map(),
  }

  for (const g of GRUPOS) {
    const tipo = TIPO_DE_GRUPO[g]
    const ids = new Set([...b[g].keys(), ...n[g].keys(), ...s[g].keys()])
    for (const id of ids) {
      const eb = b[g].get(id), en = n[g].get(id), es = s[g].get(id)
      let dato: (Record<string, unknown> & { __claseId?: string }) | null = null

      if (!en && !es) continue                                   // borrado por los dos
      if (eb && (!en || !es)) {
        const quedo = (en ?? es)!
        const modificado = !iguales(proyectar(tipo, quedo), proyectar(tipo, eb))
        if (!modificado) continue                               // lo borro uno y el otro no lo toco: se borra
        const eleccion = elegida.get(claveDeConflicto({ elementId: id, campo: CAMPO_EXISTENCIA })) ?? 'nuestro'
        const ganador = eleccion === 'nuestro' ? en : es         // el lado elegido: o lo tiene, o lo borro
        if (!ganador) continue
        dato = { ...ganador.dato, __claseId: ganador.claseId }
      } else if (en && es) {
        dato = { ...en.dato, __claseId: en.claseId }
        for (const campo of CAMPOS[tipo]) {
          const vb = eb?.dato[campo], vn = en.dato[campo], vs = es.dato[campo]
          if (iguales(vn, vs)) continue
          const soloSuyo = eb && iguales(vb, vn)
          const soloNuestro = eb && iguales(vb, vs)
          if (soloNuestro) continue
          if (soloSuyo) { dato[campo] = vs; continue }
          dato[campo] = (elegida.get(claveDeConflicto({ elementId: id, campo })) ?? 'nuestro') === 'suyo' ? vs : vn
        }
      } else {
        const solo = (en ?? es)!                                 // agregado de un solo lado
        dato = { ...solo.dato, __claseId: solo.claseId }
      }
      resultado[g].set(id, dato)
    }
  }

  return reconstruir(resultado, nuestro, suyo)
}

function reconstruir(
  r: Record<NombreDeGrupo, Map<string, Record<string, unknown> & { __claseId?: string }>>,
  nuestro: Modelo, suyo: Modelo,
): Modelo {
  const orden = new Map<string, number>()
  ;[...nuestro.classes, ...suyo.classes].forEach((c) => { if (!orden.has(c.id)) orden.set(c.id, orden.size) })

  const quitarMarca = <T>(d: Record<string, unknown> & { __claseId?: string }): T => {
    const { __claseId: _, ...resto } = d
    return resto as T
  }

  const classes: UmlClass[] = [...r.clases.values()].map((d) => ({
    ...quitarMarca<UmlClass>(d), attributes: [] as UmlAttribute[], methods: [] as UmlMethod[],
  }))
  const porId = new Map(classes.map((c) => [c.id, c]))
  for (const d of r.atributos.values()) {
    const clase = d.__claseId ? porId.get(d.__claseId) : undefined
    if (clase) clase.attributes.push({ ...quitarMarca<UmlAttribute>(d), classId: clase.id })
  }
  for (const d of r.metodos.values()) {
    const clase = d.__claseId ? porId.get(d.__claseId) : undefined
    if (clase) clase.methods.push({ ...quitarMarca<UmlMethod>(d), classId: clase.id })
  }
  for (const c of classes) {
    c.attributes.sort((x, y) => x.ordinal - y.ordinal)
    c.methods.sort((x, y) => x.ordinal - y.ordinal)
  }
  classes.sort((x, y) => (orden.get(x.id) ?? 0) - (orden.get(y.id) ?? 0))

  // Una relacion cuyo extremo se borro no puede quedar colgando.
  const relationships = [...r.relaciones.values()]
    .map((d) => quitarMarca<UmlRelationship>(d))
    .filter((rel) => porId.has(rel.sourceClassId) && porId.has(rel.targetClassId))
  return { classes, relationships }
}
