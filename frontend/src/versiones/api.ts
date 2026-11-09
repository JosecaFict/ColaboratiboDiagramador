import type { Cliente } from '../auth/cliente'
import { calcularDiff } from './diff'
import { fusionar, previewDeMerge, sinResolver } from './merge'
import type {
  DiffVersiones, MergePreview, PedidoDeMerge, Rama, RamasDelDiagrama, SnapshotDeVersion, Version, VersionConSnapshot,
} from './tipos'

/**
 * Cliente tipado de la API de versiones (docs/DISENO-FASE-2.md, seccion B).
 *
 * La interfaz habla con `ApiVersiones` y no sabe si detras hay el servidor
 * (`crearApiVersiones`) o la memoria (`crearApiSimulada`). La simulada existe
 * para poder armar y probar la interfaz mientras el frente B no termina, y se
 * activa con `VITE_VERSIONES_SIMULADAS=1`.
 */
export interface ApiVersiones {
  listar(diagramaId: string): Promise<Version[]>
  commit(diagramaId: string, pedido: { name: string; message: string }): Promise<Version>
  version(diagramaId: string, versionId: string): Promise<VersionConSnapshot>
  /** `hasta` = id de una version, o `'actual'` para el estado vivo del diagrama. */
  diff(diagramaId: string, desde: string, hasta: string): Promise<DiffVersiones>
  restaurar(diagramaId: string, versionId: string): Promise<void>
  crearRama(diagramaId: string, pedido: { name: string; fromVersionId: string }): Promise<{ diagramId: string }>
  ramas(diagramaId: string): Promise<RamasDelDiagrama>
  previewMerge(diagramaId: string, fromDiagramId: string): Promise<MergePreview>
  merge(diagramaId: string, pedido: PedidoDeMerge): Promise<void>
}

export const ACTUAL = 'actual'

const base = (id: string) => `/diagrams/${encodeURIComponent(id)}`

/** Contra las rutas reales del backend. */
export function crearApiVersiones(cliente: Cliente): ApiVersiones {
  return {
    listar: (d) => cliente.obtener<Version[]>(`${base(d)}/versions`),
    commit: (d, p) => cliente.enviar<Version>('POST', `${base(d)}/versions`, p),
    version: (d, v) => cliente.obtener<VersionConSnapshot>(`${base(d)}/versions/${encodeURIComponent(v)}`),
    diff: (d, desde, hasta) =>
      cliente.obtener<DiffVersiones>(`${base(d)}/diff?from=${encodeURIComponent(desde)}&to=${encodeURIComponent(hasta)}`),
    restaurar: (d, v) => cliente.enviar('POST', `${base(d)}/versions/${encodeURIComponent(v)}/restore`),
    crearRama: (d, p) => cliente.enviar<{ diagramId: string }>('POST', `${base(d)}/branches`, p),
    ramas: (d) => cliente.obtener<RamasDelDiagrama>(`${base(d)}/branches`),
    previewMerge: (d, from) => cliente.enviar<MergePreview>('POST', `${base(d)}/merge/preview`, { fromDiagramId: from }),
    merge: (d, p) => cliente.enviar('POST', `${base(d)}/merge`, p),
  }
}

// ---------------------------------------------------------------------------------------------
// Simulada
// ---------------------------------------------------------------------------------------------

export interface OpcionesSimuladas {
  /** El modelo VIVO del diagrama que esta abierto en el editor y su `seq`. */
  actual: () => { diagramaId: string; seq: number; modelo: SnapshotDeVersion }
  /** Se llama cuando restaurar o mergear produce un modelo nuevo (en el real, un BATCH por el bus). */
  alCambiarModelo?: (diagramaId: string, modelo: SnapshotDeVersion) => void
  /** Nombre del autor de los commits. */
  autor?: { id: string; nombre: string }
  ahora?: () => Date
}

/**
 * Implementacion en memoria. Respeta las reglas del contrato que la interfaz
 * necesita: nombre de version unico por diagrama, historial del mas nuevo al
 * mas viejo, ramas que conservan los ids, merge que rechaza conflictos sin
 * resolver. NO persiste nada: recargar la pagina la reinicia.
 */
export interface DepuradorSimulado {
  /** Los diagramas-rama que existen en la simulacion, con los nombres de sus clases. */
  ramas(): { diagramId: string; name: string; clases: string[] }[]
  /** Edita una rama "desde afuera" (en la simulacion no hay otra forma de tocarla). Devuelve false si no la encontro. */
  renombrarClase(diagramId: string, nombreActual: string, nombreNuevo: string): boolean
  agregarClase(diagramId: string, nombre: string): boolean
  quitarClase(diagramId: string, nombre: string): boolean
}

export function crearApiSimulada(opciones: OpcionesSimuladas): ApiVersiones & { depurar: DepuradorSimulado } {
  const versiones = new Map<string, VersionConSnapshot[]>()
  const ramas = new Map<string, Rama>()                 // clave: diagramId de la rama
  const modelos = new Map<string, { seq: number; modelo: SnapshotDeVersion }>()   // diagramas que NO son el abierto
  const autor = opciones.autor ?? { id: 'u-simulado', nombre: 'Yo (simulado)' }
  let contador = 0
  const ahora = () => (opciones.ahora?.() ?? new Date()).toISOString()
  const copia = <T>(x: T): T => structuredClone(x)

  const estadoDe = (diagramaId: string) => {
    const a = opciones.actual()
    if (a.diagramaId === diagramaId) return { seq: a.seq, modelo: a.modelo }
    const m = modelos.get(diagramaId)
    if (!m) throw new Error('No se encontró el diagrama')
    return m
  }
  const guardarModelo = (diagramaId: string, modelo: SnapshotDeVersion) => {
    const actual = opciones.actual()
    if (actual.diagramaId === diagramaId) opciones.alCambiarModelo?.(diagramaId, copia(modelo))
    else modelos.set(diagramaId, { seq: (modelos.get(diagramaId)?.seq ?? 0) + 1, modelo: copia(modelo) })
  }
  const lista = (d: string) => versiones.get(d) ?? []
  const buscar = (d: string, v: string): VersionConSnapshot => {
    const x = lista(d).find((e) => e.id === v)
    if (!x) throw new Error('No se encontró esa versión')
    return x
  }
  const sinSnapshot = ({ snapshot: _, ...v }: VersionConSnapshot): Version => v

  const instantanea = (m: SnapshotDeVersion): SnapshotDeVersion => copia(m)
  const extremo = (d: string, ref: string) => {
    if (ref === ACTUAL) return { modelo: estadoDe(d).modelo, extremo: { versionId: null, seq: estadoDe(d).seq } }
    const v = buscar(d, ref)
    return { modelo: v.snapshot, extremo: { versionId: v.id, seq: v.seq } }
  }

  /** La version de la que nacio una rama, para usarla como base comun del merge. */
  const baseComun = (destino: string, origen: string): SnapshotDeVersion => {
    const r = ramas.get(origen)
    if (r?.forkedFromDiagramId === destino && r.forkedFromVersionId) return buscar(destino, r.forkedFromVersionId).snapshot
    const r2 = ramas.get(destino)
    if (r2?.forkedFromDiagramId === origen && r2.forkedFromVersionId) return buscar(origen, r2.forkedFromVersionId).snapshot
    throw new Error('Estos diagramas no tienen una base común')
  }

  const depurar: DepuradorSimulado = {
    ramas: () => [...ramas.values()].map((r) => ({
      diagramId: r.diagramId, name: r.name, clases: (modelos.get(r.diagramId)?.modelo.classes ?? []).map((c) => c.name),
    })),
    renombrarClase(id, actual, nuevo) {
      const m = modelos.get(id); const c = m?.modelo.classes.find((x) => x.name === actual)
      if (!c) return false
      c.name = nuevo; return true
    },
    agregarClase(id, nombre) {
      const m = modelos.get(id)
      if (!m) return false
      m.modelo.classes.push({
        id: `c-sim-${++contador}`, diagramId: id, name: nombre, isAbstract: false, weak: false, erKind: 'STRONG',
        inheritanceStrategy: 'JOINED', attributes: [], methods: [],
      })
      return true
    },
    quitarClase(id, nombre) {
      const m = modelos.get(id); if (!m) return false
      const antes = m.modelo.classes.length
      m.modelo.classes = m.modelo.classes.filter((c) => c.name !== nombre)
      return m.modelo.classes.length < antes
    },
  }

  return {
    depurar,
    async listar(d) {
      return lista(d).map(sinSnapshot).sort((a, b) => b.seq - a.seq || b.createdAt.localeCompare(a.createdAt))
    },

    async commit(d, { name, message }) {
      const nombre = name.trim()
      if (!nombre) throw new Error('La versión necesita un nombre')
      if (lista(d).some((v) => v.name === nombre)) throw new Error(`Ya existe una versión llamada “${nombre}”`)
      const { seq, modelo } = estadoDe(d)
      const anteriores = lista(d)
      const v: VersionConSnapshot = {
        id: `v-sim-${++contador}`, seq, name: nombre, message: message.trim(),
        authorId: autor.id, authorName: autor.nombre,
        parentVersionId: anteriores.length ? anteriores[anteriores.length - 1].id : null,
        createdAt: ahora(), snapshot: instantanea(modelo),
      }
      versiones.set(d, [...anteriores, v])
      return sinSnapshot(v)
    },

    async version(d, v) { return copia(buscar(d, v)) },

    async diff(d, desde, hasta) {
      const a = extremo(d, desde), b = extremo(d, hasta)
      return calcularDiff(a.modelo, a.extremo, b.modelo, b.extremo)
    },

    async restaurar(d, v) { guardarModelo(d, buscar(d, v).snapshot) },

    async crearRama(d, { name, fromVersionId }) {
      const nombre = name.trim()
      if (!nombre) throw new Error('La rama necesita un nombre')
      if (nombre === 'main' || [...ramas.values()].some((r) => r.name === nombre && r.forkedFromDiagramId === d)) {
        throw new Error(`Ya existe una rama llamada “${nombre}”`)
      }
      const v = buscar(d, fromVersionId)
      const diagramId = `d-sim-rama-${++contador}`
      // Los ids de los elementos se CONSERVAN: sin eso no hay merge.
      modelos.set(diagramId, { seq: v.seq, modelo: instantanea(v.snapshot) })
      ramas.set(diagramId, { diagramId, name: nombre, forkedFromDiagramId: d, forkedFromVersionId: v.id })
      return { diagramId }
    },

    async ramas(d) {
      const propia = ramas.get(d)
      const origenDe = propia?.forkedFromDiagramId ? ramas.get(propia.forkedFromDiagramId) : undefined
      return {
        actual: copia(propia ?? { diagramId: d, name: 'main', forkedFromDiagramId: null, forkedFromVersionId: null }),
        origen: propia?.forkedFromDiagramId
          ? { diagramId: propia.forkedFromDiagramId, name: origenDe?.name ?? 'main', forkedFromVersionId: propia.forkedFromVersionId }
          : null,
        ramas: [...ramas.values()].filter((r) => r.forkedFromDiagramId === d).map(copia),
      }
    },

    async previewMerge(d, from) {
      const b = baseComun(d, from)
      return previewDeMerge(b, estadoDe(d).modelo, estadoDe(from).modelo)
    },

    async merge(d, { fromDiagramId, resoluciones }) {
      const b = baseComun(d, fromDiagramId)
      const nuestro = estadoDe(d).modelo, suyo = estadoDe(fromDiagramId).modelo
      const pendientes = sinResolver(previewDeMerge(b, nuestro, suyo).conflictos, resoluciones)
      if (pendientes.length > 0) throw new Error(`Faltan resolver ${pendientes.length} conflicto(s)`)
      guardarModelo(d, fusionar(b, nuestro, suyo, resoluciones))
    },
  }
}

/** ¿Usar la API simulada? Solo si se pidio explicitamente al arrancar la interfaz. */
export const usarApiSimulada = (): boolean => {
  try {
    return (import.meta as unknown as { env?: Record<string, string> }).env?.VITE_VERSIONES_SIMULADAS === '1'
  } catch {
    return false
  }
}
