import { describe, expect, it, vi } from 'vitest'
import { crearCliente } from '../auth/cliente'
import { ACTUAL, crearApiSimulada, crearApiVersiones } from './api'
import type { SnapshotDeVersion } from './tipos'

const clase = (id: string, name: string) => ({
  id, diagramId: 'd1', name, isAbstract: false, weak: false, erKind: 'STRONG' as const,
  inheritanceStrategy: 'JOINED' as const, attributes: [], methods: [],
})

describe('crearApiVersiones (rutas del contrato)', () => {
  const armar = () => {
    const base = vi.fn(async () => new Response('[]', { status: 200 }))
    return { base, api: crearApiVersiones(crearCliente(() => 'tok', base as unknown as typeof fetch)) }
  }
  const ultima = (base: ReturnType<typeof armar>['base']) => {
    const [url, init] = base.mock.calls.at(-1) as unknown as [string, RequestInit]
    return { url, metodo: init.method, cuerpo: init.body ? JSON.parse(String(init.body)) : undefined }
  }

  it('lee el historial, una version y el diff', async () => {
    const { api, base } = armar()
    await api.listar('d1')
    expect(ultima(base)).toMatchObject({ url: '/api/v1/diagrams/d1/versions', metodo: 'GET' })
    await api.version('d1', 'v9')
    expect(ultima(base).url).toBe('/api/v1/diagrams/d1/versions/v9')
    await api.diff('d1', 'v1', ACTUAL)
    expect(ultima(base).url).toBe('/api/v1/diagrams/d1/diff?from=v1&to=actual')
  })

  it('commit, restore, ramas y merge usan el verbo y el cuerpo del contrato', async () => {
    const { api, base } = armar()
    await api.commit('d1', { name: 'v1', message: 'inicial' })
    expect(ultima(base)).toEqual({ url: '/api/v1/diagrams/d1/versions', metodo: 'POST', cuerpo: { name: 'v1', message: 'inicial' } })
    await api.restaurar('d1', 'v1')
    expect(ultima(base)).toMatchObject({ url: '/api/v1/diagrams/d1/versions/v1/restore', metodo: 'POST' })
    await api.crearRama('d1', { name: 'idea', fromVersionId: 'v1' })
    expect(ultima(base)).toEqual({ url: '/api/v1/diagrams/d1/branches', metodo: 'POST', cuerpo: { name: 'idea', fromVersionId: 'v1' } })
    await api.ramas('d1')
    expect(ultima(base)).toMatchObject({ url: '/api/v1/diagrams/d1/branches', metodo: 'GET' })
    await api.previewMerge('d1', 'd2')
    expect(ultima(base)).toEqual({ url: '/api/v1/diagrams/d1/merge/preview', metodo: 'POST', cuerpo: { fromDiagramId: 'd2' } })
    await api.merge('d1', { fromDiagramId: 'd2', resoluciones: [{ elementId: 'c1', campo: 'name', eleccion: 'suyo' }] })
    expect(ultima(base).cuerpo).toEqual({ fromDiagramId: 'd2', resoluciones: [{ elementId: 'c1', campo: 'name', eleccion: 'suyo' }] })
  })

  it('un 409 por lock ajeno llega como error legible', async () => {
    const base = vi.fn(async () => new Response(JSON.stringify({ mensaje: 'Ana tiene bloqueada Libro' }), { status: 409 }))
    const api = crearApiVersiones(crearCliente(() => 't', base as unknown as typeof fetch))
    await expect(api.restaurar('d1', 'v1')).rejects.toMatchObject({ estado: 409, message: 'Ana tiene bloqueada Libro' })
  })
})

describe('crearApiSimulada', () => {
  const armar = () => {
    const estado = { diagramaId: 'd1', seq: 5, modelo: { classes: [clase('c1', 'Libro')], relationships: [] } as SnapshotDeVersion }
    const cambios: SnapshotDeVersion[] = []
    const api = crearApiSimulada({
      actual: () => estado,
      alCambiarModelo: (_, m) => { estado.modelo = m; cambios.push(m) },
      ahora: () => new Date('2026-11-01T10:00:00Z'),
    })
    return { estado, api, cambios }
  }

  it('el historial va del mas nuevo al mas viejo y el nombre es unico', async () => {
    const { estado, api } = armar()
    await api.commit('d1', { name: 'v1', message: 'uno' })
    estado.seq = 9
    await api.commit('d1', { name: 'v2', message: 'dos' })
    expect((await api.listar('d1')).map((v) => v.name)).toEqual(['v2', 'v1'])
    await expect(api.commit('d1', { name: 'v1', message: '' })).rejects.toThrow(/Ya existe/)
    await expect(api.commit('d1', { name: '  ', message: '' })).rejects.toThrow(/nombre/)
    expect((await api.listar('d1'))[0].parentVersionId).not.toBeNull()
  })

  it('el diff versión↔actual ve lo que se cambió después del commit', async () => {
    const { estado, api } = armar()
    const v = await api.commit('d1', { name: 'v1', message: '' })
    estado.seq = 8
    estado.modelo = { ...estado.modelo, classes: [...estado.modelo.classes, clase('c2', 'Autor')] }
    const d = await api.diff('d1', v.id, ACTUAL)
    expect(d.desde).toEqual({ versionId: v.id, seq: 5 })
    expect(d.hasta).toEqual({ versionId: null, seq: 8 })
    expect(d.clases.agregadas.map((c) => c.nombre)).toEqual(['Autor'])
  })

  it('restaurar devuelve el modelo de la versión', async () => {
    const { estado, api, cambios } = armar()
    const v = await api.commit('d1', { name: 'v1', message: '' })
    estado.modelo = { classes: [], relationships: [] }
    await api.restaurar('d1', v.id)
    expect(cambios.at(-1)?.classes.map((c) => c.name)).toEqual(['Libro'])
  })

  it('rama + merge: los ids se conservan, los conflictos se exigen y se aplican las elecciones', async () => {
    const { estado, api } = armar()
    const v = await api.commit('d1', { name: 'v1', message: '' })
    const { diagramId } = await api.crearRama('d1', { name: 'idea', fromVersionId: v.id })

    const r = await api.ramas('d1')
    expect(r.origen).toBeNull()
    expect(r.actual.name).toBe('main')
    expect(r.ramas).toEqual([{ diagramId, name: 'idea', forkedFromDiagramId: 'd1', forkedFromVersionId: v.id }])
    const deLaRama = await api.ramas(diagramId)
    expect(deLaRama.origen).toMatchObject({ diagramId: 'd1', name: 'main' })
    expect(deLaRama.actual.name).toBe('idea')
    await expect(api.crearRama('d1', { name: 'idea', fromVersionId: v.id })).rejects.toThrow(/Ya existe/)

    // Nosotros renombramos el mismo campo que la rama (la rama se edita "desde afuera" via el almacen).
    const rama = await api.version('d1', v.id)
    expect(rama.snapshot.classes[0].id).toBe('c1')

    // Sin cambios en la rama, el preview solo trae lo nuestro.
    estado.modelo = { classes: [{ ...estado.modelo.classes[0], name: 'Obra' }], relationships: [] }
    const p = await api.previewMerge('d1', diagramId)
    expect(p.conflictos).toEqual([])
    expect(p.nuestros.clases.modificadas).toHaveLength(1)
    expect(p.suyos.clases.modificadas).toHaveLength(0)
    await api.merge('d1', { fromDiagramId: diagramId, resoluciones: [] })
    expect(estado.modelo.classes[0].name).toBe('Obra')
  })

  it('el depurador simulado permite provocar un conflicto de merge', async () => {
    const { estado, api } = armar()
    const v = await api.commit('d1', { name: 'v1', message: '' })
    const { diagramId } = await api.crearRama('d1', { name: 'idea', fromVersionId: v.id })
    expect(api.depurar.ramas()).toEqual([{ diagramId, name: 'idea', clases: ['Libro'] }])
    expect(api.depurar.renombrarClase(diagramId, 'Libro', 'Titulo')).toBe(true)
    expect(api.depurar.agregarClase(diagramId, 'Nueva')).toBe(true)
    estado.modelo = { classes: [{ ...estado.modelo.classes[0], name: 'Obra' }], relationships: [] }

    const p = await api.previewMerge('d1', diagramId)
    expect(p.conflictos).toHaveLength(1)
    await expect(api.merge('d1', { fromDiagramId: diagramId, resoluciones: [] })).rejects.toThrow(/Faltan resolver 1/)
    await api.merge('d1', { fromDiagramId: diagramId, resoluciones: [{ elementId: 'c1', campo: 'name', eleccion: 'suyo' }] })
    expect(estado.modelo.classes.map((c) => c.name)).toEqual(['Titulo', 'Nueva'])
  })

  it('merge entre diagramas sin base común falla con un mensaje claro', async () => {
    const { api } = armar()
    await expect(api.previewMerge('d1', 'otro')).rejects.toThrow()
  })
})
