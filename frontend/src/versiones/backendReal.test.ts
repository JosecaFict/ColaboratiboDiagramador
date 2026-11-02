import { describe, expect, it } from 'vitest'
import { ErrorDeApi, crearCliente } from '../auth/cliente'
import { leerDocumental, mensajeDocumental } from '../documental/api'
import { ACTUAL, crearApiVersiones } from './api'
import { mensajeDe } from './PanelVersiones'

/**
 * Prueba CONTRA UN BACKEND VIVO, apagada por defecto para que `npm test` no dependa de nada.
 *   BACKEND_REAL=http://localhost:8080 npx vitest run src/versiones/backendReal.test.ts
 * Crea (o reusa) una cuenta, un proyecto y un diagrama de prueba.
 */
const origen = (globalThis as unknown as { process?: { env: Record<string, string | undefined> } }).process?.env.BACKEND_REAL

describe.skipIf(!origen)('contra el backend real', () => {
  const fetchAbsoluto = ((entrada: RequestInfo | URL, init?: RequestInit) =>
    fetch(origen + String(entrada), init)) as typeof fetch

  const sesion = async () => {
    const credenciales = { email: 'd2.prueba@prueba.local', password: 'Prueba12345', fullName: 'D2 Prueba' }
    const anon = crearCliente(() => null, fetchAbsoluto)
    let t: { token: string }
    try { t = await anon.enviar<{ token: string }>('POST', '/auth/register', credenciales) }
    catch { t = await anon.enviar<{ token: string }>('POST', '/auth/login', credenciales) }
    const cliente = crearCliente(() => t.token, fetchAbsoluto)
    const p = await cliente.enviar<{ id: string }>('POST', '/projects', { name: 'D2 prueba automatica' })
    const d = await cliente.enviar<{ id: string }>('POST', `/projects/${p.id}/diagrams`, { name: 'Prueba' })
    return { cliente, diagramaId: d.id }
  }

  it('lo existente sigue funcionando: el diagrama se lee', async () => {
    const { cliente, diagramaId } = await sesion()
    const s = await cliente.obtener<{ id: string; currentSeq: number }>(`/diagrams/${diagramaId}`)
    expect(s.id).toBe(diagramaId)
  })

  it('los endpoints de versiones que el backend aun no tiene dan un error con mensaje claro', async () => {
    const { cliente, diagramaId } = await sesion()
    const api = crearApiVersiones(cliente)
    for (const llamada of [
      () => api.listar(diagramaId),
      () => api.ramas(diagramaId),
      () => api.diff(diagramaId, 'x', ACTUAL),
      () => api.commit(diagramaId, { name: 'v1', message: '' }),
    ]) {
      const error = await llamada().then(() => null, (e: unknown) => e)
      // Si el backend ya los tiene, este caso no aplica: la prueba solo fija el modo de fallar.
      if (!error) continue
      expect(error).toBeInstanceOf(ErrorDeApi)
      expect(mensajeDe(error)).not.toBe('')
      if ((error as ErrorDeApi).estado === 404) expect(mensajeDe(error)).toMatch(/no tiene disponible/)
    }
  })

  it('/documental ausente da un mensaje claro', async () => {
    const { cliente, diagramaId } = await sesion()
    const error = await leerDocumental(cliente, diagramaId).then(() => null, (e: unknown) => e)
    if (error) expect(mensajeDocumental(error)).toMatch(/vista documental/)
  })
})
