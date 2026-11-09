import type { ElementLayout, UmlClass, UmlRelationship } from '../contracts/command'

/**
 * Contratos del frente B (ADR-037), tal como los fija docs/DISENO-FASE-2.md.
 *
 * Lo que el documento NO fija (la forma exacta de `Version` y de `Rama`) esta
 * marcado como SUPUESTO: son los nombres de las columnas de `V8__versiones.sql`
 * en camelCase, que es como habla el resto de la API (`currentSeq`, `createdAt`).
 * Si el backend manda otra cosa, se cambia SOLO este archivo y `api.ts`.
 */

/** Una version guardada (commit). SUPUESTO: forma del JSON. */
export interface Version {
  id: string
  seq: number
  name: string
  message: string
  authorId: string | null
  /** Si el servidor lo resuelve; si no, la interfaz muestra el id. */
  authorName?: string | null
  parentVersionId: string | null
  createdAt: string
}

/** Una version con la foto del modelo (GET /versions/{vid}). */
export interface VersionConSnapshot extends Version {
  snapshot: SnapshotDeVersion
}

/** Lo que guarda `snapshot jsonb`: el mismo modelo que `GET /diagrams/{id}`, sin el `seq`. */
export interface SnapshotDeVersion {
  classes: UmlClass[]
  relationships: UmlRelationship[]
  layout?: ElementLayout[]
}

/** Una rama es un diagrama del mismo proyecto que nacio de una version. SUPUESTO: forma del JSON. */
export interface Rama {
  diagramId: string
  /** `branch_name`; `main` por defecto. */
  name: string
  forkedFromDiagramId: string | null
  forkedFromVersionId: string | null
}

/** De que diagrama nacio este (para traer sus cambios); `null` en un diagrama raiz. */
export interface OrigenDeRama {
  diagramId: string
  name: string
  diagramName?: string
  forkedFromVersionId?: string | null
  forkedFromVersionName?: string | null
}

/** GET /branches: el propio diagrama, de donde viene, y las ramas que nacieron de el. */
export interface RamasDelDiagrama {
  actual: Rama
  origen: OrigenDeRama | null
  ramas: Rama[]
}

export type TipoDeElemento = 'clase' | 'atributo' | 'metodo' | 'relacion'

/** Un elemento agregado o eliminado. En atributos y metodos lleva ademas su clase. */
export interface ElementoDelDiff {
  id: string
  nombre: string
  claseId?: string
  clase?: string
}

export interface CambioDeCampo {
  campo: string
  antes: unknown
  despues: unknown
}

export interface ElementoModificado extends ElementoDelDiff {
  cambios: CambioDeCampo[]
}

export interface GrupoDelDiff {
  agregadas: ElementoDelDiff[]
  eliminadas: ElementoDelDiff[]
  modificadas: ElementoModificado[]
}

export interface ExtremoDelDiff {
  /** `null` = el estado actual del diagrama. */
  versionId: string | null
  seq: number
}

/** Forma del diff (GET /diagrams/{id}/diff). */
export interface DiffVersiones {
  desde: ExtremoDelDiff
  hasta: ExtremoDelDiff
  clases: GrupoDelDiff
  atributos: GrupoDelDiff
  metodos: GrupoDelDiff
  relaciones: GrupoDelDiff
}

export const GRUPOS = ['clases', 'atributos', 'metodos', 'relaciones'] as const
export type NombreDeGrupo = typeof GRUPOS[number]

export const TIPO_DE_GRUPO: Record<NombreDeGrupo, TipoDeElemento> = {
  clases: 'clase', atributos: 'atributo', metodos: 'metodo', relaciones: 'relacion',
}

/** Un campo que cambio de los dos lados a la vez (o se borro de un lado y se toco del otro). */
export interface Conflicto {
  elementId: string
  tipo: TipoDeElemento
  campo: string
  base: unknown
  nuestro: unknown
  suyo: unknown
  /** Lo manda el backend: como se llama el elemento (y su clase, si es atributo o metodo). */
  nombre?: string
  clase?: string
  /** "nombre repetido": dos elementos nuevos se llaman igual. Con "suyo" entra como `sugerido`. */
  motivo?: string
  sugerido?: string
}

export type Eleccion = 'nuestro' | 'suyo'

export interface Resolucion {
  elementId: string
  campo: string
  eleccion: Eleccion
}

/** Campo especial de un conflicto de existencia: uno lo borro y el otro lo modifico. */
export const CAMPO_EXISTENCIA = 'existencia'

/** Merge a tres bandas (POST /merge/preview): el diff de cada lado contra la base comun, mas los conflictos. */
export interface MergePreview {
  base?: ExtremoDelDiff | null
  origen?: OrigenDeRama | null
  puedeMergear?: boolean
  nuestros: Omit<DiffVersiones, 'desde' | 'hasta'> & Partial<Pick<DiffVersiones, 'desde' | 'hasta'>>
  suyos: Omit<DiffVersiones, 'desde' | 'hasta'> & Partial<Pick<DiffVersiones, 'desde' | 'hasta'>>
  conflictos: Conflicto[]
}

export interface PedidoDeMerge {
  fromDiagramId: string
  resoluciones: Resolucion[]
}

/** Lo que dice el servidor cuando un lock ajeno frena un restore o un merge (409). */
export interface BloqueoAjeno { elementId?: string; usuario?: string }
