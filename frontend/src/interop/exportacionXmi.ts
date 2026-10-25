/**
 * Exportar el diagrama como XMI: para Enterprise Architect (ADR-031) o
 * XMI 2.5.1 / UML 2.5.1 estandar (ADR-013, ADR-022, ADR-024).
 *
 * Exportar es una LECTURA: `GET /diagrams/{id}/xmi`, sin lock, sin log y sin
 * comando nuevo en el catalogo cerrado (ADR-014). Por eso tambien lo puede
 * hacer un Observador, igual que descargar el codigo generado.
 *
 * El servidor declara lo que UML 2.5.1 puro no puede llevarse (decisiones
 * A-1..A-4, SINGLE_TABLE, estereotipos, `unique`...) dentro del propio archivo,
 * en `xmi:Documentation`, y la cantidad en la cabecera `X-Colabmodel-Perdidas`.
 * La lista se lee del archivo y no se le pide aparte al servidor: asi lo que
 * se muestra es exactamente lo que dice el archivo que se va a guardar.
 */

/**
 * Para quien se escribe (ADR-031). 'ea': XMI 2.1 con la Extension de
 * Enterprise Architect, que es lo unico que le hace dibujar el diagrama al
 * importarlo. 'estandar': XMI 2.5.1 / UML 2.5.1 puro (ADR-024), sin
 * disposicion, para Papyrus, MagicDraw o cualquier otra herramienta.
 */
export type PerfilXmi = 'ea' | 'estandar'

export interface XmiExportado {
  /** El archivo tal cual lo mando el servidor: se guarda sin volver a pedirlo. */
  contenido: Blob
  nombreArchivo: string
  perdidas: string[]
  perfil: PerfilXmi
}

const PREFIJO = /^\s*Perdidas declaradas \([^)]*\):\s*/

/** Lo que EscritorXmi escribio como perdidas en `xmi:Documentation`. */
export function perdidasDelXmi(xmi: string): string[] {
  const doc = /<xmi:Documentation\b[^>]*>([\s\S]*?)<\/xmi:Documentation>/.exec(xmi)
  if (!doc) return []
  const texto = desescapar(doc[1]).replace(PREFIJO, '').trim()
  return texto ? texto.split(' | ').map((p) => p.trim()).filter(Boolean) : []
}

/**
 * El nombre del diagrama como nombre de archivo: sin los caracteres que
 * Windows no acepta, para que "Biblioteca: v2" no aborte la descarga.
 */
export function nombreDeArchivo(nombreDiagrama: string, perfil: PerfilXmi = 'estandar'): string {
  const limpio = nombreDiagrama.replace(/[\\/:*?"<>|]+/g, ' ').replace(/\s+/g, ' ').trim()
  // El dialogo "Import Package from XMI" de EA filtra por *.xml de entrada.
  return (limpio || 'diagrama') + (perfil === 'ea' ? '.xml' : '.xmi')
}

export async function exportarXmi(diagramId: string, nombreDiagrama: string,
                                  auth: Record<string, string>, perfil: PerfilXmi = 'ea'): Promise<XmiExportado> {
  const r = await fetch(`/api/v1/diagrams/${diagramId}/xmi?perfil=${perfil}`, { headers: auth })
  if (!r.ok) {
    const cuerpo = await r.json().catch(() => null)
    throw new Error(cuerpo?.mensaje ?? `No se pudo exportar (HTTP ${r.status}).`)
  }
  const texto = await r.text()
  return {
    contenido: new Blob([texto], { type: 'application/xml' }),
    nombreArchivo: nombreDeArchivo(nombreDiagrama, perfil),
    perdidas: perdidasDelXmi(texto),
    perfil,
  }
}

/**
 * El XMI viaja con el JWT en la cabecera, asi que no alcanza un enlace: se
 * suelta como blob, igual que el zip del generador.
 */
export function guardar(exportado: XmiExportado) {
  const url = URL.createObjectURL(exportado.contenido)
  const a = document.createElement('a')
  a.href = url
  a.download = exportado.nombreArchivo
  // Firefox no dispara la descarga de un enlace que no esta en el documento.
  document.body.appendChild(a)
  a.click()
  a.remove()
  // Diferido: revocar en la misma vuelta le saca el blob a la descarga.
  setTimeout(() => URL.revokeObjectURL(url), 60_000)
}

function desescapar(s: string): string {
  return s.replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"')
    .replace(/&apos;/g, "'").replace(/&#(\d+);/g, (_, n) => String.fromCharCode(Number(n)))
    .replace(/&amp;/g, '&')
}
