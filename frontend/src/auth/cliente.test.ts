import { describe, expect, it, vi } from 'vitest'
import { ErrorDeApi, crearCliente, envolverFetch, leerMensaje } from './cliente'

/**
 * El manejo del 401 (ADR-026): un 401 en CUALQUIER llamada con sesion la cierra.
 * Un 401 sin token (un login equivocado) no cierra nada.
 */

const respuesta = (estado: number, cuerpo?: unknown) =>
  new Response(cuerpo === undefined ? null : JSON.stringify(cuerpo), {
    status: estado, headers: { 'Content-Type': 'application/json' },
  })

/** Un fetch de mentira que devuelve siempre `estado` y recuerda a que le pidieron. */
function fetchDe(estado: number, cuerpo?: unknown) {
  const llamadas: { entrada: string; init?: RequestInit }[] = []
  const base = vi.fn(async (entrada: RequestInfo | URL, init?: RequestInit) => {
    llamadas.push({ entrada: String(entrada), init })
    return respuesta(estado, cuerpo)
  }) as unknown as typeof fetch
  return { base, llamadas }
}

const conToken = { headers: { Authorization: 'Bearer abc' } }

/** El error con que termina una llamada; si no falla, la prueba falla. */
async function falla(llamada: Promise<unknown>): Promise<ErrorDeApi> {
  try {
    await llamada
  } catch (e) {
    return e as ErrorDeApi
  }
  throw new Error('la llamada tenia que fallar')
}

describe('envolverFetch: un 401 con sesion la cierra', () => {
  it('avisa que la sesion murio cuando una llamada con token recibe 401', async () => {
    const alVencer = vi.fn()
    const { base } = fetchDe(401, { mensaje: 'venció' })

    const r = await envolverFetch(base, alVencer)('/api/v1/projects', conToken)

    expect(alVencer).toHaveBeenCalledTimes(1)
    // Quien hizo el pedido igual ve el 401: no se le esconde.
    expect(r.status).toBe(401)
  })

  it('cubre CUALQUIER llamada a la API, tambien las que no usan el cliente nuevo', async () => {
    const alVencer = vi.fn()
    const envuelto = envolverFetch(fetchDe(401).base, alVencer)

    await envuelto('/api/v1/diagrams/x', conToken)                       // snapshot
    await envuelto('/api/v1/diagrams/x/operations?sinceSeq=3', conToken)  // resincronizacion
    await envuelto('/api/v1/diagrams/x/logical', conToken)                // Vista Logica
    await envuelto('/api/v1/diagrams/x/codegen', conToken)                // descarga del zip
    await envuelto(new Request('http://localhost/api/v1/projects', { headers: { Authorization: 'Bearer abc' } }))

    expect(alVencer).toHaveBeenCalledTimes(5)
  })

  it('un 401 SIN token (un login equivocado) no cierra nada', async () => {
    const alVencer = vi.fn()
    const envuelto = envolverFetch(fetchDe(401, { mensaje: 'El email o la contraseña no son correctos' }).base, alVencer)

    const r = await envuelto('/api/v1/auth/login', { method: 'POST', body: '{}' })

    expect(r.status).toBe(401)
    expect(alVencer).not.toHaveBeenCalled()
  })

  it('las respuestas que no son 401 no la cierran, ni siquiera un 403', async () => {
    for (const estado of [200, 204, 400, 403, 404, 409, 500]) {
      const alVencer = vi.fn()
      await envolverFetch(fetchDe(estado).base, alVencer)('/api/v1/projects', conToken)
      expect(alVencer, `estado ${estado}`).not.toHaveBeenCalled()
    }
  })

  it('un 401 de algo que no es nuestra API no cierra la sesion', async () => {
    const alVencer = vi.fn()

    await envolverFetch(fetchDe(401).base, alVencer)('https://otro-sitio.example/datos', conToken)

    expect(alVencer).not.toHaveBeenCalled()
  })

  it('pasa el pedido tal cual al fetch de abajo', async () => {
    const { base, llamadas } = fetchDe(200, [])

    await envolverFetch(base, vi.fn())('/api/v1/projects', { ...conToken, method: 'GET' })

    expect(llamadas).toHaveLength(1)
    expect(llamadas[0].entrada).toBe('/api/v1/projects')
    expect(llamadas[0].init?.method).toBe('GET')
  })
})

describe('crearCliente', () => {
  it('firma con el token de la sesion en el momento de cada llamada', async () => {
    let token: string | null = 'uno'
    const { base, llamadas } = fetchDe(200, {})
    const api = crearCliente(() => token, base)

    await api.obtener('/projects')
    token = 'dos'
    await api.obtener('/projects')

    const cabecera = (i: number) => (llamadas[i].init?.headers as Record<string, string>).Authorization
    expect(cabecera(0)).toBe('Bearer uno')
    expect(cabecera(1)).toBe('Bearer dos')
  })

  it('sin sesion no manda Authorization, y siempre pide JSON', async () => {
    const { base, llamadas } = fetchDe(200, {})

    await crearCliente(() => null, base).obtener('/projects')

    const h = llamadas[0].init?.headers as Record<string, string>
    expect(h.Authorization).toBeUndefined()
    expect(h.Accept).toBe('application/json')
  })

  it('manda el cuerpo como JSON y devuelve lo que contesta el servidor', async () => {
    const { base, llamadas } = fetchDe(201, { id: 'p1' })

    const r = await crearCliente(() => 't', base).enviar<{ id: string }>('POST', '/projects', { name: 'Biblioteca' })

    expect(r).toEqual({ id: 'p1' })
    expect(llamadas[0].entrada).toBe('/api/v1/projects')
    expect(llamadas[0].init?.method).toBe('POST')
    expect(llamadas[0].init?.body).toBe('{"name":"Biblioteca"}')
    expect((llamadas[0].init?.headers as Record<string, string>)['Content-Type']).toBe('application/json')
  })

  it('un 204 no intenta leer un cuerpo que no hay', async () => {
    const { base } = fetchDe(204)

    await expect(crearCliente(() => 't', base).enviar('DELETE', '/projects/p1')).resolves.toBeUndefined()
  })

  it('un error trae el codigo y el mensaje en espanol del servidor', async () => {
    const { base } = fetchDe(409, { mensaje: 'Ese usuario ya es miembro del proyecto' })

    const error = await falla(crearCliente(() => 't', base).enviar('POST', '/projects/p/members', {}))

    expect(error).toBeInstanceOf(ErrorDeApi)
    expect(error.estado).toBe(409)
    expect(error.message).toBe('Ese usuario ya es miembro del proyecto')
  })

  it('un 401 por el cliente, ya envuelto, avisa que la sesion murio', async () => {
    const alVencer = vi.fn()
    const vigilado = envolverFetch(fetchDe(401, { mensaje: 'Tu sesión venció' }).base, alVencer)

    const error = await falla(crearCliente(() => 't', vigilado).obtener('/projects'))

    expect(alVencer).toHaveBeenCalledTimes(1)
    expect(error).toBeInstanceOf(ErrorDeApi)
    expect(error.estado).toBe(401)
  })
})

describe('leerMensaje', () => {
  it('sin cuerpo JSON usa un texto por defecto segun el codigo', async () => {
    expect(await leerMensaje(new Response('no es json', { status: 403 }))).toBe('No tenés permiso para hacer esto')
    expect(await leerMensaje(new Response(null, { status: 404 }))).toBe('No se encontró lo que buscabas')
    expect(await leerMensaje(new Response(null, { status: 500 }))).toContain('El servidor no pudo')
    expect(await leerMensaje(new Response(null, { status: 418 }))).toBe('La solicitud falló (418)')
  })

  it('un cuerpo JSON sin `mensaje` (el de Spring por defecto) tambien cae en el texto por defecto', async () => {
    expect(await leerMensaje(respuesta(500, { timestamp: 'x', status: 500, error: 'Internal Server Error' })))
      .toContain('El servidor no pudo')
  })
})
