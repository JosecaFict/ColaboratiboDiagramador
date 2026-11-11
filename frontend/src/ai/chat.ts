import type { ImagenMeta } from '../contracts/imagenes'
import type { EntradaHistorialIA, ModoIA, PropuestaIA } from './tipos'

/**
 * La Vista IA como chat grupal (T-10): el historial del diagrama se lee como una
 * conversación del equipo con un desarrollador más. Todo lo de este archivo es
 * puro, para probarlo sin navegador.
 *
 * Quién escribió qué se decide por `userId`, nunca por el nombre: dos personas
 * pueden llamarse igual y un nombre puede cambiar. El nombre es solo la etiqueta.
 */

export interface MensajeDeChat {
  id: string
  autor: 'usuario' | 'asistente'
  /** Lo escribió esta persona (o la IA le responde a ella): va del lado propio. */
  propio: boolean
  nombre: string
  /** Nombre para mostrar: distingue a dos personas con el mismo nombre y marca el propio. */
  etiqueta: string
  inicial: string
  texto: string
  modo: ModoIA
  propuesta?: PropuestaIA
  /** Solo en la respuesta de la IA: a quién le contesta. */
  respondeA?: { nombre: string; tuyo: boolean }
  /** El pedido propio que todavía espera respuesta. */
  enviando?: boolean
  /** La imagen de un turno IMAGEN (o de su pedido en curso): la miniatura va en el mensaje del usuario. */
  imagen?: ImagenMeta
}

export interface PedidoEnCurso {
  id: string
  texto: string
  modo: ModoIA
  imagen?: ImagenMeta
}

export const NOMBRE_DE_USUARIO_ELIMINADO = 'Usuario eliminado'

export function inicialDe(nombre: string): string {
  const limpio = nombre.trim()
  return limpio ? Array.from(limpio)[0].toUpperCase() : '?'
}

/**
 * Convierte el historial en mensajes. `textoDeRespuesta` arma lo que dice la IA
 * (la vista ya lo tiene); aquí solo se decide autor, lado y destinatario.
 */
export function mensajesDeChat(
  entradas: EntradaHistorialIA[],
  pedidoEnCurso: PedidoEnCurso | null,
  miUserId: string,
  miNombre: string,
  textoDeRespuesta: (propuesta: PropuestaIA, modo: ModoIA) => string,
): MensajeDeChat[] {
  const nombreDe = (e: EntradaHistorialIA) => e.userName?.trim() || NOMBRE_DE_USUARIO_ELIMINADO
  const esMia = (e: EntradaHistorialIA) => e.userId !== null && e.userId === miUserId

  // Dos personas distintas con el mismo nombre se numeran por orden de aparición.
  const idsPorNombre = new Map<string, string[]>()
  for (const e of entradas) {
    if (e.userId === null) continue
    const ids = idsPorNombre.get(nombreDe(e)) ?? []
    if (!ids.includes(e.userId)) ids.push(e.userId)
    idsPorNombre.set(nombreDe(e), ids)
  }
  const etiquetaDe = (e: EntradaHistorialIA) => {
    const nombre = nombreDe(e)
    const ids = idsPorNombre.get(nombre) ?? []
    const base = ids.length > 1 && e.userId !== null ? `${nombre} (${ids.indexOf(e.userId) + 1})` : nombre
    return esMia(e) ? `${base} · vos` : base
  }

  const mensajes = entradas.flatMap<MensajeDeChat>((e) => {
    const nombre = nombreDe(e)
    const propio = esMia(e)
    return [
      {
        id: `${e.id}-usuario`, autor: 'usuario', propio, nombre, etiqueta: etiquetaDe(e), inicial: inicialDe(nombre),
        texto: e.prompt, modo: e.modo, ...(e.imagen ? { imagen: e.imagen } : {}),
      },
      {
        id: `${e.id}-asistente`, autor: 'asistente', propio, nombre: 'ColabModel IA', etiqueta: 'ColabModel IA',
        inicial: 'IA', texto: textoDeRespuesta(e.propuesta, e.modo), modo: e.modo, propuesta: e.propuesta,
        respondeA: { nombre, tuyo: propio },
      },
    ]
  })

  if (pedidoEnCurso) {
    mensajes.push({
      id: pedidoEnCurso.id, autor: 'usuario', propio: true, nombre: miNombre, etiqueta: `${miNombre} · vos`,
      inicial: inicialDe(miNombre), texto: pedidoEnCurso.texto, modo: pedidoEnCurso.modo, enviando: true,
      ...(pedidoEnCurso.imagen ? { imagen: pedidoEnCurso.imagen } : {}),
    })
  }
  return mensajes
}

/** "responde a vos" / "responde a Beto": a quién le contesta la IA. */
export function textoDeDestinatario(r: { nombre: string; tuyo: boolean }): string {
  return r.tuyo ? 'responde a vos' : `responde a ${r.nombre}`
}

/**
 * Si el pedido falla (la IA no responde, no entra en el contexto, se cae la red)
 * lo escrito vuelve al cuadro, salvo que ya se haya escrito otra cosa mientras tanto.
 */
export function textoTrasFallo(actual: string, intentado: string): string {
  return actual.trim() ? actual : intentado
}

/** Lo que se muestra cuando POST /ai/propuestas no sale bien. El 422 trae su propio {mensaje}. */
export function mensajeDeFalloDePropuesta(estado: number, cuerpo: { mensaje?: string } | null): string {
  if (cuerpo?.mensaje) return cuerpo.mensaje
  if (estado === 422) return 'El pedido y el diagrama no entran en el contexto del modelo. Acortá el pedido e intentá de nuevo.'
  return `La IA respondió con HTTP ${estado}.`
}
