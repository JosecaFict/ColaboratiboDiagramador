/**
 * Espejo de EsquemaFisico.java y de la verificacion (VerificadorDeEsquema).
 * El esquema se deriva en el backend; aca solo se describe lo que llega.
 */
export interface ColumnaFisica {
  nombre: string
  tipo: string
  pk: boolean
  nullable: boolean
  unique: boolean
  /** Tabla a la que apunta, si es clave foranea. */
  referencia?: string | null
  onDelete?: string | null
  restriccionFk?: string | null
  /** La condicion del CHECK, ya escrita como va al DDL. */
  check?: string | null
  /** El atributo del modelo que la produjo: editar la columna es editarlo (ADR-032). */
  atributoId?: string | null
  /** La relacion que produjo la FK. */
  relacionId?: string | null
  /** En una FK de asociacion, el extremo apuntado: su multiplicidad y su rol. */
  extremo?: 'SOURCE' | 'TARGET' | null
}

export interface TablaFisica {
  nombre: string
  origen: string
  columnas: ColumnaFisica[]
  clavePrimaria: string[]
  indices: { nombre: string; columna: string }[]
  /** Quien le apunta: el inverso de las FK, para navegar en los dos sentidos. */
  referidaPor: string[]
  /** La clase que la produjo; null en una tabla puente o de multivaluado. */
  claseId?: string | null
}

/** Lo que el equipo confirmo antes de generar (ADR-032). */
export interface ConfirmacionEsquema {
  hash: string
  seq: number
  por: string | null
  en: string
  /** El esquema de hoy es exactamente el confirmado. */
  vigente: boolean
  /** El DDL confirmado, solo si ya no esta vigente: para mostrar que cambio. */
  scriptConfirmado?: string | null
}

export interface EsquemaFisico {
  dialecto: string
  resumen: {
    tablas: number
    columnas: number
    clavesForaneas: number
    indices: number
    restricciones: number
  }
  tablas: TablaFisica[]
  /** El DDL completo, verbatim: es el mismo archivo que sale en el zip. */
  script: string
  advertencias: string[]
  /** SHA-256 del script: lo que se verifica, se confirma y se compara al generar. */
  hash: string
  confirmacion?: ConfirmacionEsquema | null
}

/** Espejo de VerificadorDeEsquema.Verificacion. */
export interface ResultadoVerificacion {
  ok: boolean
  sentencias: number
  tablas: number
  clavesForaneas: number
  indices: number
  restricciones: number
  milisegundos: number
  error?: string | null
  /** Linea del SCRIPT donde fallo, desde 1. */
  linea?: number | null
  sentencia?: string | null
}

/** Espejo de DiagramRestController.VerificacionDelEsquema. */
export interface VerificacionDelEsquema {
  /** El esquema que se verifico: solo se puede confirmar ESE. */
  hash: string
  seq: number
  resultado: ResultadoVerificacion
}
