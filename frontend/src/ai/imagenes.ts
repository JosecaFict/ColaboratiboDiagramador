import {
  LADO_MINIMO_DE_RECORTE, MAX_BYTES_DE_IMAGEN, TIPOS_DE_IMAGEN,
  type ImagenMeta, type RecorteDeImagen, type UsoDeImagenes,
} from '../contracts/imagenes'

/**
 * Imagenes del chat (contrato §2). Todo lo puro (validar, fusionar la galeria, decidir
 * quien borra) esta aqui para probarlo sin navegador. Las llamadas llevan el token en la
 * cabecera Authorization: NINGUNA URL lleva token (ni en la ruta ni en la consulta).
 */

export const rutaDeImagenes = (diagramId: string) => `/api/v1/diagrams/${diagramId}/ai/imagenes`
export const rutaDeImagen = (diagramId: string, id: string, miniatura = false) =>
  `${rutaDeImagenes(diagramId)}/${id}${miniatura ? '/miniatura' : ''}`

/** Aviso ANTES de subir: el servidor lo rechazaria igual (400), pero asi no se gasta la subida. */
export function validarArchivoDeImagen(archivo: { type: string; size: number }): string | null {
  if (!(TIPOS_DE_IMAGEN as readonly string[]).includes(archivo.type)) {
    return 'Solo se aceptan imágenes PNG o JPEG.'
  }
  if (archivo.size <= 0) return 'El archivo está vacío.'
  if (archivo.size > MAX_BYTES_DE_IMAGEN) {
    return `La imagen pesa ${Math.ceil(archivo.size / 1024)} KB; el máximo es de ${MAX_BYTES_DE_IMAGEN / 1024 / 1024} MB.`
  }
  return null
}

interface OrigenDeArchivos {
  files?: ArrayLike<File> | null
  items?: ArrayLike<{ kind: string; getAsFile(): File | null }> | null
}

/** Los archivos de un pegado (Ctrl+V) o de un arrastre, en el orden en que llegaron. */
export function archivosDeImagen(origen: OrigenDeArchivos): File[] {
  const deArchivos = Array.from(origen.files ?? [])
  if (deArchivos.length) return deArchivos
  return Array.from(origen.items ?? []).filter((i) => i.kind === 'file').flatMap((i) => i.getAsFile() ?? [])
}

export interface EstadoDeGaleria { imagenes: ImagenMeta[]; uso: UsoDeImagenes | null }

/** Agrega por id (idempotente: el mismo aviso o una respuesta HTTP repetida no duplican). */
export function agregarImagen(g: EstadoDeGaleria, meta: ImagenMeta): EstadoDeGaleria {
  if (meta.eliminada) return marcarEliminadaEnGaleria(g, meta)
  const i = g.imagenes.findIndex((x) => x.id === meta.id)
  if (i >= 0) return { ...g, imagenes: g.imagenes.map((x, j) => (j === i ? { ...x, ...meta } : x)) }
  return {
    imagenes: [meta, ...g.imagenes],
    uso: g.uso && { ...g.uso, cantidad: g.uso.cantidad + 1, bytes: g.uso.bytes + meta.bytes },
  }
}

/** Marca eliminada (no la quita: asi el turno y la galeria coinciden) y libera su cupo una sola vez. */
export function marcarEliminadaEnGaleria(g: EstadoDeGaleria, meta: ImagenMeta): EstadoDeGaleria {
  const previa = g.imagenes.find((x) => x.id === meta.id)
  const estabaViva = !!previa && !previa.eliminada
  const marcada: ImagenMeta = { ...(previa ?? meta), eliminada: true, eliminadaEn: meta.eliminadaEn ?? previa?.eliminadaEn }
  return {
    imagenes: previa ? g.imagenes.map((x) => (x.id === meta.id ? marcada : x)) : [marcada, ...g.imagenes],
    uso: g.uso && estabaViva
      ? { ...g.uso, cantidad: Math.max(0, g.uso.cantidad - 1), bytes: Math.max(0, g.uso.bytes - marcada.bytes) }
      : g.uso,
  }
}

/** Solo las vivas, mas nuevas primero (el servidor ya las manda asi; los avisos se anteponen). */
export const imagenesVivas = (imagenes: ImagenMeta[]) => imagenes.filter((i) => !i.eliminada)

/**
 * ¿Se muestra el boton de borrar? Quien la subio (si todavia edita) o el OWNER. El servidor
 * decide de verdad (403); esto solo evita ofrecer lo que seguro sera rechazado. Un VIEWER nunca.
 */
export function puedeBorrarImagen(
  imagen: ImagenMeta, quien: { userId: string; puedeEditar: boolean; esPropietario: boolean },
): boolean {
  if (imagen.eliminada || !quien.puedeEditar) return false
  return quien.esPropietario || (!!imagen.subidaPorId && imagen.subidaPorId === quien.userId)
}

/** Validacion del reproceso, con los mismos mensajes del servidor (§5.2). */
export function validarReproceso(
  imagen: Pick<ImagenMeta, 'ancho' | 'alto'>, giroGrados: number | undefined, recorte: RecorteDeImagen | undefined,
): string | null {
  if (giroGrados !== undefined) {
    const ok = Number.isInteger(giroGrados) && giroGrados >= -180 && giroGrados <= 180
      && (Math.abs(giroGrados) <= 10 || giroGrados % 90 === 0)
    if (!ok) return 'El giro debe ser de hasta 10° o un múltiplo de 90°'
  }
  if (recorte) {
    const { x, y, ancho, alto } = recorte
    const enteros = [x, y, ancho, alto].every(Number.isInteger)
    if (!enteros || x < 0 || y < 0 || ancho < LADO_MINIMO_DE_RECORTE || alto < LADO_MINIMO_DE_RECORTE
      || x + ancho > imagen.ancho || y + alto > imagen.alto) {
      return `El recorte tiene que estar dentro de la imagen (${imagen.ancho} × ${imagen.alto}) y medir al menos ${LADO_MINIMO_DE_RECORTE} px`
    }
  }
  return null
}

/** "girada 90°, recortada": lo que se pidio, para mostrarlo junto a la lectura. */
export function textoDeOpciones(o: { giroGrados?: number; recorte?: RecorteDeImagen } | undefined): string {
  if (!o) return ''
  const partes: string[] = []
  if (o.giroGrados) partes.push(`girada ${o.giroGrados}°`)
  if (o.recorte) partes.push('recortada')
  return partes.join(', ')
}

export const formatoDeBytes = (bytes: number) =>
  bytes >= 1024 * 1024 ? `${(bytes / 1024 / 1024).toFixed(1)} MB` : `${Math.max(1, Math.round(bytes / 1024))} KB`

async function mensajeDe(r: Response, porDefecto: string): Promise<string> {
  const cuerpo = await r.json().catch(() => null) as { mensaje?: string } | null
  return cuerpo?.mensaje ?? `${porDefecto} (HTTP ${r.status}).`
}

const auth = (token: string) => ({ Authorization: `Bearer ${token}` })

export async function subirImagen(diagramId: string, token: string, archivo: File): Promise<ImagenMeta> {
  const cuerpo = new FormData()
  cuerpo.append('imagen', archivo)
  const r = await fetch(rutaDeImagenes(diagramId), { method: 'POST', headers: auth(token), body: cuerpo })
  if (!r.ok) throw new Error(await mensajeDe(r, 'No se pudo subir la imagen'))
  return r.json()
}

export async function listarImagenes(diagramId: string, token: string): Promise<{ imagenes: ImagenMeta[]; uso: UsoDeImagenes }> {
  const r = await fetch(rutaDeImagenes(diagramId), { headers: auth(token) })
  if (!r.ok) throw new Error(await mensajeDe(r, 'No se pudo cargar la galería de imágenes'))
  return r.json()
}

export async function borrarImagen(diagramId: string, token: string, id: string): Promise<ImagenMeta> {
  const r = await fetch(rutaDeImagen(diagramId, id), { method: 'DELETE', headers: auth(token) })
  if (!r.ok) throw new Error(await mensajeDe(r, 'No se pudo eliminar la imagen'))
  return r.json()
}

/** Bytes (o miniatura) como Blob, con Authorization: el <img src> directo no puede mandar la cabecera. */
export async function pedirBytesDeImagen(diagramId: string, token: string, id: string, miniatura: boolean): Promise<Blob> {
  const r = await fetch(rutaDeImagen(diagramId, id, miniatura), { headers: auth(token) })
  if (!r.ok) throw new Error(r.status === 404 ? 'La imagen ya no existe.' : `No se pudo cargar la imagen (HTTP ${r.status}).`)
  return r.blob()
}
