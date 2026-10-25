// ============================================================================
// CONTRATO DE VISION (nivel 1 y nivel 2, ADR-012)
//
// ESPEJO EXACTO de backend/.../vision/DeteccionPropuesta.java
// Si cambia alla, cambia aca. No se interpreta: se copia.
//
// Esto es una PROPUESTA, no una escritura. Nada de lo que hay aca existe en el
// diagrama: el panel lo muestra para que una persona confirme o descarte, y
// recien entonces se emiten Commands con origin 'IMAGE' por el bus de siempre
// (ADR-002). No hay un segundo camino de escritura.
//
// OJO CON LOS null. El backend serializa con `default-property-inclusion:
// non_null`, asi que los campos vacios NO VIENEN en el JSON en vez de venir en
// null. Por eso `texto`, `nombre`, `figura` y `recorteNombre` son opcionales:
// que falten es el estado NORMAL mientras no haya nivel 2 cargado, no un error.
// ============================================================================

import type { RelationKind } from './command'

/** Rectangulo en pixeles de la imagen de TRABAJO (la que el backend escalo). */
export interface Caja {
  x: number
  y: number
  ancho: number
  alto: number
}

/** Que se espera encontrar dentro de un recorte. */
export type RolDeTexto =
  | 'NOMBRE_CLASE' | 'ESTEREOTIPO' | 'ATRIBUTO' | 'METODO'
  | 'CARDINALIDAD' | 'ROL' | 'NOMBRE_RELACION'

/** Un pedacito de imagen donde hay tinta pero todavia no se sabe que dice. */
export interface RecorteDeTexto {
  ref: string
  rol: RolDeTexto
  caja: Caja
  /** Ausente hasta que el nivel 2 lo lea. */
  texto?: string
  /** 0..1; 0 mientras no haya texto. */
  confianza: number
}

export type Extremo = 'ORIGEN' | 'DESTINO'

/** El extremo de una linea, donde vive el rombo o el triangulo. */
export interface RecorteDeSimbolo {
  ref: string
  extremo: Extremo
  caja: Caja
  /** Ausente hasta que el nivel 2 lo clasifique. */
  figura?: RelationKind
  confianza: number
}

export interface ClaseDetectada {
  /** Identificador DENTRO de la propuesta ("c1"). No es un UUID: nada existe todavia. */
  ref: string
  caja: Caja
  /** La misma caja en coordenadas del canvas: conserva la disposicion de la imagen. */
  lienzo: Caja
  nombre?: string
  recorteNombre?: RecorteDeTexto
  atributos: RecorteDeTexto[]
  metodos: RecorteDeTexto[]
  /** 1 = caja sin dividir; 3 = nombre / atributos / metodos. */
  compartimentos: number
  confianza: number
}

export interface RelacionDetectada {
  ref: string
  /** Refs de ClaseDetectada. Iguales entre si = reflexiva, que es valida. */
  origenRef: string
  destinoRef: string
  /** ASSOCIATION mientras el nivel 2 no lea los extremos: es la lectura conservadora. */
  kind: RelationKind
  simboloOrigen: RecorteDeSimbolo
  simboloDestino: RecorteDeSimbolo
  cardinalidadOrigen: RecorteDeTexto
  cardinalidadDestino: RecorteDeTexto
  confianza: number
}

/**
 * La imagen se roto para enderezarla.
 *
 * CAMBIA EL MARCO DE REFERENCIA: todas las Caja de la propuesta estan en la
 * imagen ya enderezada. Para dibujar encima de la foto original hay que
 * deshacer la rotacion --se roto alrededor del centro, de `anchoPrevio` x
 * `altoPrevio` hacia `anchoTrabajo` x `altoTrabajo`-- y despues dividir por
 * `escala`. Lo mas simple es mostrar la imagen ya enderezada.
 */
export interface Enderezado {
  grados: number
  anchoPrevio: number
  altoPrevio: number
  ganancia: number
}

export interface MetricasDeteccion {
  anchoOriginal: number
  altoOriginal: number
  /** El marco donde viven TODAS las Caja: despues de reescalar y de enderezar. */
  anchoTrabajo: number
  altoTrabajo: number
  /** Factor aplicado al original. Para dibujar sobre la imagen original, DIVIDIR por el. */
  escala: number
  /** Ausente si no hizo falta rotar. */
  enderezado?: Enderezado
  umbral: number
  /**
   * 'global' con el umbral unico de Otsu, o 'adaptativa' cuando la imagen tenia
   * tanto gris intermedio que hubo que calcular un umbral por ventana. Con
   * umbral adaptativo, `umbral` solo gobierna las zonas lisas.
   */
  binarizacion: string
  segmentos: number
  /**
   * Relaciones que salieron de ajustar una recta sobre la tinta que el analisis
   * de corridas no explico: las diagonales y las punteadas. Son las menos
   * seguras de la propuesta.
   */
  rectasSueltas: number
  milis: number
  /** Mucho gris intermedio: conviene una captura digital antes que una foto. */
  pareceFoto: boolean
  detector: string
}

export interface DeteccionPropuesta {
  clases: ClaseDetectada[]
  relaciones: RelacionDetectada[]
  /** Lo que no se puede mapear. Doctrina de la Regla 10: se lista, nunca se calla. */
  avisos: string[]
  metricas: MetricasDeteccion
}

/**
 * POST multipart a /api/v1/diagrams/{id}/vision/deteccion
 *
 * Pide permiso de ESCRITURA aunque no escriba nada: un VIEWER no podria
 * aterrizar la propuesta, y decodificar un binario de afuera no es trabajo que
 * convenga hacer para quien no va a poder usarlo.
 */
export async function detectarDesdeImagen(
  diagramId: string,
  archivo: File,
  token: string,
): Promise<DeteccionPropuesta> {
  const cuerpo = new FormData()
  cuerpo.append('imagen', archivo)

  const respuesta = await fetch(`/api/v1/diagrams/${diagramId}/vision/deteccion`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${token}` },
    body: cuerpo,
  })

  if (!respuesta.ok) {
    // El backend contesta 400 con {error, mensaje} para toda entrada mala:
    // formato, tamano, cantidad de pixeles. Es texto para mostrar tal cual.
    const detalle = await respuesta.json().catch(() => null)
    throw new Error(detalle?.mensaje ?? `La deteccion fallo (${respuesta.status})`)
  }
  return respuesta.json()
}
