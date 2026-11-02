import { Client } from '@stomp/stompjs'
import SockJS from 'sockjs-client'
import { describe, expect, it } from 'vitest'
import { ErrorDeApi, crearCliente, type Cliente } from '../auth/cliente'
import { ACTUAL, crearApiVersiones, type ApiVersiones } from './api'
import type { DiffVersiones, MergePreview } from './tipos'

/**
 * E2E de versiones CONTRA UN BACKEND VIVO que ya tenga el frente B. Apagado por defecto.
 *
 *   BACKEND_REAL=http://localhost:8081 npx vitest run src/versiones/e2eVersiones.test.ts
 *
 * Si el backend no tiene las rutas de versiones (404), el recorrido se salta con un aviso
 * en vez de fallar. Crea cuentas, un proyecto y diagramas de prueba nuevos en cada corrida
 * (la base se ensucia con "E2E versiones <hora>").
 */
const origen = (globalThis as unknown as { process?: { env: Record<string, string | undefined> } }).process?.env.BACKEND_REAL

const randomUUID = (): string => globalThis.crypto.randomUUID()
const PASS = 'Prueba12345'
const unico = Date.now().toString(36)

describe.skipIf(!origen)('E2E de versiones contra el backend real', () => {
  const fetchAbsoluto = ((entrada: RequestInfo | URL, init?: RequestInit) => fetch(origen + String(entrada), init)) as typeof fetch

  const cuenta = async (alias: string) => {
    const datos = { email: `e2e.${alias}.${unico}@prueba.local`, password: PASS, fullName: `E2E ${alias}` }
    const anon = crearCliente(() => null, fetchAbsoluto)
    const t = await anon.enviar<{ token: string; userId: string }>('POST', '/auth/register', datos)
    return { ...datos, token: t.token, userId: t.userId, cliente: crearCliente(() => t.token, fetchAbsoluto) }
  }

  /** Un canal STOMP para mandar comandos al bus. El estado se lee por REST. */
  const canal = async (token: string, diagramaId: string) => {
    const client = new Client({
      webSocketFactory: () => new SockJS(origen + '/ws') as unknown as WebSocket,
      connectHeaders: { Authorization: 'Bearer ' + token },
      reconnectDelay: 0,
    })
    await new Promise<void>((ok, mal) => {
      client.onConnect = () => ok()
      client.onStompError = (f) => mal(new Error(String(f.headers['message'])))
      client.onWebSocketError = () => mal(new Error('no se pudo abrir el WebSocket'))
      client.activate()
    })
    return {
      enviar: (type: string, payload: Record<string, unknown>) =>
        client.publish({
          destination: `/app/diagram/${diagramaId}/op`,
          body: JSON.stringify({ opId: randomUUID(), diagramId: diagramaId, baseSeq: 0, type, origin: 'MOUSE', payload }),
        }),
      cerrar: () => client.deactivate(),
    }
  }

  interface Snap { currentSeq: number; classes: { id: string; name: string; attributes: { id: string; name: string }[] }[] }
  const snapshot = (c: Cliente, id: string) => c.obtener<Snap>(`/diagrams/${id}`)
  /** Espera (con tope) a que el estado cumpla la condicion: el bus es asincrono. */
  const hasta = async (c: Cliente, id: string, cumple: (s: Snap) => boolean): Promise<Snap> => {
    let s = await snapshot(c, id)
    for (let i = 0; i < 40 && !cumple(s); i++) {
      await new Promise((r) => setTimeout(r, 150))
      s = await snapshot(c, id)
    }
    expect(cumple(s), 'el estado esperado nunca llego: ' + JSON.stringify(s.classes.map((x) => x.name))).toBe(true)
    return s
  }
  const claseNombrada = (s: Snap, n: string) => s.classes.find((c) => c.name === n)

  it('recorre commit → diff → restore → rama → conflicto → merge', async () => {
    const dueno = await cuenta('dueno')
    const editor = await cuenta('editor')
    const p = await dueno.cliente.enviar<{ id: string }>('POST', '/projects', { name: `E2E versiones ${unico}` })
    await dueno.cliente.enviar('POST', `/projects/${p.id}/members`, { email: editor.email, role: 'EDITOR' })
    const d = await dueno.cliente.enviar<{ id: string }>('POST', `/projects/${p.id}/diagrams`, { name: 'Biblioteca' })
    const api: ApiVersiones = crearApiVersiones(dueno.cliente)

    // ¿El backend tiene el frente B? Si no, no hay nada que recorrer.
    const hay = await api.listar(d.id).then(() => true, (e: unknown) => !(e instanceof ErrorDeApi && e.estado === 404))
    if (!hay) {
      console.warn('[E2E versiones] el backend no tiene /versions (404): se salta el recorrido')
      return
    }
    expect(await api.listar(d.id)).toEqual([])

    // 1. Modelo inicial por el bus: Libro(titulo) y Autor(nombre).
    const bus = await canal(dueno.token, d.id)
    const libro = randomUUID(), autor = randomUUID()
    bus.enviar('CREATE_CLASS', { classId: libro, name: 'Libro', x: 100, y: 100 })
    bus.enviar('CREATE_CLASS', { classId: autor, name: 'Autor', x: 400, y: 100 })
    await hasta(dueno.cliente, d.id, (s) => s.classes.length === 2)
    bus.enviar('ADD_ATTRIBUTE', { attributeId: randomUUID(), classId: libro, name: 'titulo', dataType: 'String', ordinal: 0 })
    bus.enviar('ADD_ATTRIBUTE', { attributeId: randomUUID(), classId: autor, name: 'nombre', dataType: 'String', ordinal: 0 })
    await hasta(dueno.cliente, d.id, (s) => s.classes.every((c) => c.attributes.length === 1))

    // 2. Commit v1: nombre repetido = 409; el Observador/ajeno no escribe.
    const v1 = await api.commit(d.id, { name: 'v1', message: 'inicial' })
    expect(v1.name).toBe('v1')
    await expect(api.commit(d.id, { name: 'v1', message: '' })).rejects.toMatchObject({ estado: 409 })
    const comoEditor = crearApiVersiones(editor.cliente)
    expect((await comoEditor.listar(d.id)).map((v) => v.name)).toEqual(['v1'])

    // 3. Cambios y diff v1 ↔ actual, en la forma del contrato.
    bus.enviar('RENAME_CLASS', { classId: autor, name: 'Escritor' })
    bus.enviar('ADD_ATTRIBUTE', { attributeId: randomUUID(), classId: libro, name: 'isbn', dataType: 'String', ordinal: 1 })
    await hasta(dueno.cliente, d.id, (s) => !!claseNombrada(s, 'Escritor') && claseNombrada(s, 'Libro')!.attributes.length === 2)
    const diff: DiffVersiones = await api.diff(d.id, v1.id, ACTUAL)
    expect(diff.desde.versionId).toBe(v1.id)
    expect(diff.hasta.versionId).toBeNull()
    expect(diff.clases.modificadas.map((c) => c.nombre)).toContain('Escritor')
    expect(diff.atributos.agregadas.map((a) => a.nombre)).toContain('isbn')
    expect(diff.atributos.agregadas.find((a) => a.nombre === 'isbn')).toMatchObject({ clase: 'Libro' })

    // 4. Restore: vuelve a v1 y todo el diagrama lo ve por el bus.
    const aplicado = await dueno.cliente.enviar<{ cambios: number }>('POST', `/diagrams/${d.id}/versions/${v1.id}/restore`)
    expect(aplicado.cambios).toBeGreaterThan(0)
    const restaurado = await hasta(dueno.cliente, d.id, (s) => !!claseNombrada(s, 'Autor'))
    expect(claseNombrada(restaurado, 'Libro')!.attributes.map((a) => a.name)).toEqual(['titulo'])
    expect((await api.diff(d.id, v1.id, ACTUAL)).clases.modificadas).toEqual([])

    // 5. Rama desde v1. Ids fisicos distintos, mismo contenido.
    const rama = await dueno.cliente.enviar<{ diagramId: string; name: string }>('POST', `/diagrams/${d.id}/branches`, { name: 'idea', fromVersionId: v1.id })
    expect(rama.name).toBe('idea')
    const ramasDe = await api.ramas(d.id)
    expect(ramasDe.origen).toBeNull()
    expect(ramasDe.ramas.map((r) => r.name)).toContain('idea')
    const deLaRama = await api.ramas(rama.diagramId)
    expect(deLaRama.origen).toMatchObject({ diagramId: d.id })
    const enRama = await hasta(dueno.cliente, rama.diagramId, (s) => s.classes.length === 2)
    expect(enRama.classes.map((c) => c.name).sort()).toEqual(['Autor', 'Libro'])
    expect(claseNombrada(enRama, 'Libro')!.id).not.toBe(libro)

    // 6. Choque: la rama renombra Libro→Titulo y main renombra Libro→Obra. Ademas la rama agrega una clase.
    const busRama = await canal(dueno.token, rama.diagramId)
    busRama.enviar('RENAME_CLASS', { classId: claseNombrada(enRama, 'Libro')!.id, name: 'Titulo' })
    busRama.enviar('CREATE_CLASS', { classId: randomUUID(), name: 'Prestamo', x: 250, y: 300 })
    await hasta(dueno.cliente, rama.diagramId, (s) => !!claseNombrada(s, 'Titulo') && !!claseNombrada(s, 'Prestamo'))
    bus.enviar('RENAME_CLASS', { classId: libro, name: 'Obra' })
    await hasta(dueno.cliente, d.id, (s) => !!claseNombrada(s, 'Obra'))

    // 7. Preview: 1 conflicto (nombre de la clase), y lo limpio de la rama aparece como suyo.
    const preview: MergePreview = await api.previewMerge(d.id, rama.diagramId)
    expect(preview.conflictos).toHaveLength(1)
    expect(preview.conflictos[0]).toMatchObject({ elementId: libro, tipo: 'clase', campo: 'name', base: 'Libro', nuestro: 'Obra', suyo: 'Titulo' })
    expect(preview.suyos.clases.agregadas.map((c) => c.nombre)).toContain('Prestamo')

    // 8. Sin resolver: 409 con la lista de conflictos y NADA cambia.
    const sinResolver = await api.merge(d.id, { fromDiagramId: rama.diagramId, resoluciones: [] }).then(() => null, (e: unknown) => e)
    expect(sinResolver).toBeInstanceOf(ErrorDeApi)
    expect((sinResolver as ErrorDeApi).estado).toBe(409)
    expect(claseNombrada(await snapshot(dueno.cliente, d.id), 'Prestamo')).toBeUndefined()

    // 9. Con la resolucion "suyo": gana Titulo y llega Prestamo.
    await api.merge(d.id, { fromDiagramId: rama.diagramId, resoluciones: [{ elementId: libro, campo: 'name', eleccion: 'suyo' }] })
    const final = await hasta(dueno.cliente, d.id, (s) => !!claseNombrada(s, 'Prestamo'))
    expect(claseNombrada(final, 'Titulo')!.id).toBe(libro)
    expect(claseNombrada(final, 'Obra')).toBeUndefined()
    expect(final.classes.map((c) => c.name).sort()).toEqual(['Autor', 'Prestamo', 'Titulo'])

    // 10. Despues del merge ya no hay conflictos pendientes entre los dos.
    expect((await api.previewMerge(d.id, rama.diagramId)).conflictos).toEqual([])

    // 11. Nombre repetido: las dos partes crean una clase "Reserva" (ids distintos). Es un conflicto
    // resoluble, no un 422 al aplicar. Con "nuestro" el cambio de la rama no entra.
    bus.enviar('CREATE_CLASS', { classId: randomUUID(), name: 'Reserva', x: 600, y: 300 })
    busRama.enviar('CREATE_CLASS', { classId: randomUUID(), name: 'Reserva', x: 600, y: 300 })
    await hasta(dueno.cliente, d.id, (x) => !!claseNombrada(x, 'Reserva'))
    await hasta(dueno.cliente, rama.diagramId, (x) => !!claseNombrada(x, 'Reserva'))
    const repetido = await api.previewMerge(d.id, rama.diagramId)
    expect(repetido.puedeMergear).toBe(false)
    const choque = repetido.conflictos.find((c) => c.motivo === 'nombre repetido')
    expect(choque, JSON.stringify(repetido.conflictos)).toBeDefined()
    expect(choque).toMatchObject({ campo: 'name', tipo: 'clase' })
    expect(choque!.sugerido).toBeTruthy()
    await api.merge(d.id, { fromDiagramId: rama.diagramId, resoluciones: [{ elementId: choque!.elementId, campo: 'name', eleccion: 'nuestro' }] })
    const sinDuplicar = await snapshot(dueno.cliente, d.id)
    expect(sinDuplicar.classes.filter((c) => c.name === 'Reserva')).toHaveLength(1)

    // 12. Traer cambios del origen a la rama (el origen sale de GET /branches).
    const traerDelOrigen = await api.previewMerge(rama.diagramId, deLaRama.origen!.diagramId)
    expect(traerDelOrigen.conflictos).toBeDefined()

    bus.cerrar()
    busRama.cerrar()
  }, 90_000)

  it('restaurar encima de una clase que otro tiene bloqueada da 409 con quien la tiene', async () => {
    const dueno = await cuenta('dueno3')
    const editor = await cuenta('editor3')
    const p = await dueno.cliente.enviar<{ id: string }>('POST', '/projects', { name: `E2E lock ${unico}` })
    await dueno.cliente.enviar('POST', `/projects/${p.id}/members`, { email: editor.email, role: 'EDITOR' })
    const d = await dueno.cliente.enviar<{ id: string }>('POST', `/projects/${p.id}/diagrams`, { name: 'Con lock' })
    const api = crearApiVersiones(dueno.cliente)
    const hay = await api.listar(d.id).then(() => true, (e: unknown) => !(e instanceof ErrorDeApi && e.estado === 404))
    if (!hay) return

    const busDueno = await canal(dueno.token, d.id)
    const clase = randomUUID()
    busDueno.enviar('CREATE_CLASS', { classId: clase, name: 'Libro', x: 100, y: 100 })
    await hasta(dueno.cliente, d.id, (s) => s.classes.length === 1)
    const v1 = await api.commit(d.id, { name: 'v1', message: '' })
    busDueno.enviar('RENAME_CLASS', { classId: clase, name: 'Obra' })
    await hasta(dueno.cliente, d.id, (s) => !!claseNombrada(s, 'Obra'))

    // El editor selecciona la clase: toma su lock por STOMP, como la interfaz.
    const clienteEditor = new Client({
      webSocketFactory: () => new SockJS(origen + '/ws') as unknown as WebSocket,
      connectHeaders: { Authorization: 'Bearer ' + editor.token },
      reconnectDelay: 0,
    })
    await new Promise<void>((ok, mal) => {
      clienteEditor.onConnect = () => ok()
      clienteEditor.onStompError = (f) => mal(new Error(String(f.headers['message'])))
      clienteEditor.activate()
    })
    clienteEditor.publish({
      destination: `/app/diagram/${d.id}/lock`,
      body: JSON.stringify({ elementId: clase, userName: 'E2E editor', held: true }),
    })
    await new Promise((r) => setTimeout(r, 800))

    const error = await api.restaurar(d.id, v1.id).then(() => null, (e: unknown) => e)
    expect(error).toBeInstanceOf(ErrorDeApi)
    expect((error as ErrorDeApi).estado).toBe(409)
    expect((error as ErrorDeApi).message).toContain('E2E editor')
    // Nada cambio: la clase sigue llamandose Obra.
    expect(claseNombrada(await snapshot(dueno.cliente, d.id), 'Obra')).toBeDefined()

    // Cuando el editor la suelta, restaurar funciona.
    clienteEditor.publish({
      destination: `/app/diagram/${d.id}/unlock`,
      body: JSON.stringify({ elementId: clase, userName: 'E2E editor', held: false }),
    })
    await new Promise((r) => setTimeout(r, 800))
    await api.restaurar(d.id, v1.id)
    await hasta(dueno.cliente, d.id, (s) => !!claseNombrada(s, 'Libro'))

    busDueno.cerrar()
    await clienteEditor.deactivate()
  }, 60_000)

  it('un Observador lee pero no escribe versiones', async () => {
    const dueno = await cuenta('dueno2')
    const obs = await cuenta('obs')
    const p = await dueno.cliente.enviar<{ id: string }>('POST', '/projects', { name: `E2E observador ${unico}` })
    await dueno.cliente.enviar('POST', `/projects/${p.id}/members`, { email: obs.email, role: 'VIEWER' })
    const d = await dueno.cliente.enviar<{ id: string }>('POST', `/projects/${p.id}/diagrams`, { name: 'Solo lectura' })
    const hay = await crearApiVersiones(dueno.cliente).listar(d.id).then(() => true, (e: unknown) => !(e instanceof ErrorDeApi && e.estado === 404))
    if (!hay) return

    const comoObs = crearApiVersiones(obs.cliente)
    expect(await comoObs.listar(d.id)).toEqual([])
    await expect(comoObs.commit(d.id, { name: 'x', message: '' })).rejects.toMatchObject({ estado: 403 })
    await expect(comoObs.crearRama(d.id, { name: 'r', fromVersionId: randomUUID() })).rejects.toMatchObject({ estado: 403 })
  }, 30_000)
})
