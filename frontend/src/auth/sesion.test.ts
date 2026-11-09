import { describe, expect, it } from 'vitest'
import {
  CLAVE_DE_SESION, almacenDeSesion, avisarCierreAlServidor, borrarSesion, guardarSesion, leerSesion, type Almacen,
} from './sesion'

/** Un almacen en memoria: lo minimo de `Storage`. */
function almacen(): Almacen & { datos: Map<string, string> } {
  const datos = new Map<string, string>()
  return {
    datos,
    getItem: (k) => datos.get(k) ?? null,
    setItem: (k, v) => { datos.set(k, v) },
    removeItem: (k) => { datos.delete(k) },
  }
}

const sesion = { token: 'jwt', userId: 'u1', fullName: 'Ana' }

describe('sesion', () => {
  it('guarda y recupera la sesion', () => {
    const a = almacen()

    guardarSesion(a, sesion)

    expect(leerSesion(a)).toEqual(sesion)
  })

  it('cerrar la sesion la borra', () => {
    const a = almacen()
    guardarSesion(a, sesion)

    borrarSesion(a)

    expect(leerSesion(a)).toBeNull()
    expect(a.datos.has(CLAVE_DE_SESION)).toBe(false)
  })

  it('un contenido roto o incompleto vale como no tener sesion, sin romper', () => {
    const a = almacen()
    for (const roto of ['{', 'null', '{"token":1}', '{"token":"t"}', '[]', '']) {
      a.setItem(CLAVE_DE_SESION, roto)
      expect(leerSesion(a), roto).toBeNull()
    }
  })

  it('sin almacen (modo privado, datos bloqueados) todo sigue andando sin sesion persistida', () => {
    expect(leerSesion(null)).toBeNull()
    expect(() => guardarSesion(null, sesion)).not.toThrow()
    expect(() => borrarSesion(null)).not.toThrow()
  })

  it('un almacen que revienta al escribir no tumba el inicio de sesion', () => {
    const roto: Almacen = {
      getItem: () => { throw new Error('bloqueado') },
      setItem: () => { throw new Error('bloqueado') },
      removeItem: () => { throw new Error('bloqueado') },
    }

    expect(leerSesion(roto)).toBeNull()
    expect(() => guardarSesion(roto, sesion)).not.toThrow()
    expect(() => borrarSesion(roto)).not.toThrow()
  })

  it('dos pestanas son dos usuarios: cada una con su almacen', () => {
    const pestanaA = almacen()
    const pestanaB = almacen()

    guardarSesion(pestanaA, { ...sesion, fullName: 'Ana' })
    guardarSesion(pestanaB, { ...sesion, userId: 'u2', fullName: 'Beto' })

    expect(leerSesion(pestanaA)?.fullName).toBe('Ana')
    expect(leerSesion(pestanaB)?.fullName).toBe('Beto')
  })

  it('en un entorno sin navegador no hay almacen', () => {
    // Estas pruebas corren en Node: no existe `window`.
    expect(almacenDeSesion()).toBeNull()
  })

  it('cerrar sesion se lo avisa al servidor con el token que se cierra', async () => {
    const llamadas: [string, RequestInit | undefined][] = []
    const base = (async (url: string, init?: RequestInit) => {
      llamadas.push([url, init])
      return new Response(null, { status: 204 })
    }) as typeof fetch

    await avisarCierreAlServidor('jwt-de-ana', base)

    expect(llamadas).toHaveLength(1)
    expect(llamadas[0][0]).toBe('/api/v1/auth/logout')
    expect(llamadas[0][1]?.method).toBe('POST')
    expect(llamadas[0][1]?.headers).toEqual({ Authorization: 'Bearer jwt-de-ana' })
  })

  it('sin red, cerrar sesion igual termina sin error', async () => {
    const caido = (async () => { throw new TypeError('Failed to fetch') }) as typeof fetch

    await expect(avisarCierreAlServidor('jwt', caido)).resolves.toBeUndefined()
  })
})
