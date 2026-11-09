/**
 * Dictado con la Web Speech API. Solo convierte voz en el texto del pedido:
 * el pedido sigue el mismo camino que uno escrito y el BATCH sale con origen VOICE.
 *
 * Chrome y Edge la exponen como `webkitSpeechRecognition`. Firefox no la tiene.
 * Ahí el botón se desactiva y se explica por qué.
 */

export interface ResultadoDeVoz {
  readonly isFinal: boolean
  readonly length: number
  [indice: number]: { transcript: string }
}

export interface EventoDeVoz {
  readonly resultIndex: number
  readonly results: { readonly length: number; [indice: number]: ResultadoDeVoz }
}

export interface ReconocedorDeVoz {
  lang: string
  continuous: boolean
  interimResults: boolean
  maxAlternatives: number
  onresult: ((e: EventoDeVoz) => void) | null
  onerror: ((e: { error: string }) => void) | null
  onend: (() => void) | null
  start(): void
  stop(): void
  abort(): void
}

export type ConstructorDeReconocedor = new () => ReconocedorDeVoz

export function reconocedorDisponible(ventana: unknown = typeof window === 'undefined' ? undefined : window): ConstructorDeReconocedor | null {
  if (!ventana) return null
  const w = ventana as { SpeechRecognition?: ConstructorDeReconocedor; webkitSpeechRecognition?: ConstructorDeReconocedor }
  return w.SpeechRecognition ?? w.webkitSpeechRecognition ?? null
}

/** Todo lo reconocido hasta ahora, provisional incluido, como un solo texto. */
export function textoReconocido(e: EventoDeVoz): string {
  const partes: string[] = []
  for (let i = 0; i < e.results.length; i++) partes.push(e.results[i][0]?.transcript ?? '')
  return partes.join(' ').replace(/\s+/g, ' ').trim()
}

/** Idioma del dictado: el del navegador si es español; si no, español. */
export function idiomaDeDictado(idioma: string | undefined): string {
  return idioma && idioma.toLowerCase().startsWith('es') ? idioma : 'es-ES'
}

export function mensajeDeErrorDeVoz(codigo: string): string | null {
  switch (codigo) {
    case 'aborted': return null
    case 'no-speech': return 'No se escuchó nada. Probá de nuevo o escribí el pedido.'
    case 'audio-capture': return 'No se encontró un micrófono.'
    case 'not-allowed':
    case 'service-not-allowed': return 'El navegador no dio permiso para usar el micrófono.'
    case 'network': return 'El dictado necesita conexión a internet en este navegador.'
    default: return `El dictado falló (${codigo}). Podés escribir el pedido.`
  }
}

export const AVISO_SIN_VOZ = 'Este navegador no permite dictar: usá Chrome o Edge, o escribí el pedido.'
