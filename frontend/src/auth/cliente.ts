/**
 * Como habla la interfaz con la API REST (ADR-026).
 *
 * Dos piezas separadas a proposito:
 *
 *  - `envolverFetch` vigila TODA llamada que lleva sesion. Un 401 con token
 *    significa "tu sesion murio" (vencio, o la cuenta ya no existe) y cierra la
 *    sesion, sea quien sea el que hizo el pedido: una pantalla nueva, el
 *    snapshot del diagrama, la resincronizacion del WebSocket o la lectura de
 *    una imagen. Vigilar `fetch` y no cada llamada es lo que garantiza que
 *    ninguna se quede afuera.
 *  - `crearCliente` es la comodidad para las pantallas nuevas: JSON de ida y de
 *    vuelta, y el `mensaje` del servidor convertido en un error legible.
 *
 * Un 401 SIN token no es una sesion muerta: es un login equivocado, y no cierra
 * nada. Y equivocarse de contrasena al cambiarla es 400, no 401, por lo mismo.
 */

/** Un error de la API, con el codigo HTTP y el texto que el servidor mando (o uno por defecto). */
export class ErrorDeApi extends Error {
  constructor(readonly estado: number, mensaje: string) {
    super(mensaje)
    this.name = 'ErrorDeApi'
  }
}

const url = (entrada: RequestInfo | URL): string =>
  typeof entrada === 'string' ? entrada : entrada instanceof URL ? entrada.pathname : entrada.url

/** Una llamada a nuestra API que llevaba el token de una sesion. */
function llevabaSesion(entrada: RequestInfo | URL, init?: RequestInit): boolean {
  if (!url(entrada).includes('/api/')) return false
  const encabezados = new Headers(init?.headers ?? (entrada instanceof Request ? entrada.headers : undefined))
  return encabezados.has('Authorization')
}

/**
 * Devuelve un `fetch` que avisa cuando una llamada con sesion recibe 401.
 * La respuesta se entrega igual: quien hizo el pedido sigue viendo el 401.
 */
export function envolverFetch(base: typeof fetch, alSesionVencida: () => void): typeof fetch {
  return async (entrada, init) => {
    const respuesta = await base(entrada, init)
    if (respuesta.status === 401 && llevabaSesion(entrada, init)) alSesionVencida()
    return respuesta
  }
}

/** El texto para el usuario: el `mensaje` del servidor, o uno por defecto segun el codigo. */
export async function leerMensaje(respuesta: Response): Promise<string> {
  try {
    const cuerpo = await respuesta.json()
    if (cuerpo && typeof cuerpo.mensaje === 'string' && cuerpo.mensaje) return cuerpo.mensaje
  } catch {
    /* sin cuerpo JSON: se usa el texto por defecto */
  }
  if (respuesta.status === 401) return 'Tu sesión no es válida o venció'
  if (respuesta.status === 403) return 'No tenés permiso para hacer esto'
  if (respuesta.status === 404) return 'No se encontró lo que buscabas'
  if (respuesta.status >= 500) return 'El servidor no pudo completar la operación. Probá de nuevo'
  return `La solicitud falló (${respuesta.status})`
}

export interface Cliente {
  obtener<T>(ruta: string): Promise<T>
  enviar<T = void>(metodo: 'POST' | 'PATCH' | 'DELETE', ruta: string, cuerpo?: unknown): Promise<T>
}

/**
 * @param obtenerToken el token de la sesion de ESTA pestana, en el momento de cada llamada
 * @param base         el `fetch` a usar; por defecto el global, que es el ya vigilado
 */
export function crearCliente(obtenerToken: () => string | null, base?: typeof fetch): Cliente {
  const llamar = async <T>(metodo: string, ruta: string, cuerpo?: unknown): Promise<T> => {
    const encabezados: Record<string, string> = {
      // `*/*` es lo que manda fetch por defecto, y con jackson-dataformat-xml en el
      // servidor (lo usa XMI) conviene pedir JSON explicitamente.
      Accept: 'application/json',
    }
    const token = obtenerToken()
    if (token) encabezados.Authorization = 'Bearer ' + token
    if (cuerpo !== undefined) encabezados['Content-Type'] = 'application/json'

    const pedir = base ?? ((entrada, init) => globalThis.fetch(entrada, init))
    const respuesta = await pedir('/api/v1' + ruta, {
      method: metodo,
      headers: encabezados,
      body: cuerpo === undefined ? undefined : JSON.stringify(cuerpo),
    })
    if (!respuesta.ok) throw new ErrorDeApi(respuesta.status, await leerMensaje(respuesta))
    if (respuesta.status === 204) return undefined as T
    return respuesta.json() as Promise<T>
  }

  return {
    obtener: (ruta) => llamar('GET', ruta),
    enviar: (metodo, ruta, cuerpo) => llamar(metodo, ruta, cuerpo),
  }
}
