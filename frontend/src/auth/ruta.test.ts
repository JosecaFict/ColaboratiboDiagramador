import { describe, expect, it } from 'vitest'
import { hashDe, parsearHash, proyectoDe, type Ruta } from './ruta'

const P = '11111111-2222-3333-4444-555555555555'
const D = 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee'

describe('navegacion por hash', () => {
  it('cada ruta vuelve a si misma: recargar no saca a nadie del diagrama', () => {
    const rutas: Ruta[] = [
      { tipo: 'proyectos' },
      { tipo: 'proyecto', proyectoId: P },
      { tipo: 'diagrama', proyectoId: P, diagramaId: D },
    ]
    for (const r of rutas) expect(parsearHash(hashDe(r))).toEqual(r)
  })

  it('las rutas tienen la forma del ADR-026', () => {
    expect(hashDe({ tipo: 'proyectos' })).toBe('#/proyectos')
    expect(hashDe({ tipo: 'diagrama', proyectoId: P, diagramaId: D })).toBe(`#/p/${P}/d/${D}`)
  })

  it('acepta una barra final', () => {
    expect(parsearHash(`#/p/${P}/`)).toEqual({ tipo: 'proyecto', proyectoId: P })
    expect(parsearHash(`#/p/${P}/d/${D}/`)).toEqual({ tipo: 'diagrama', proyectoId: P, diagramaId: D })
  })

  it('cualquier cosa que no sea una ruta conocida cae en Mis proyectos', () => {
    for (const h of ['', '#', '#/', '#/proyectos', '#/p/no-es-un-uuid', `#/p/${P}/d/`, `#/p/${P}/x/${D}`, '#/admin']) {
      expect(parsearHash(h), h).toEqual({ tipo: 'proyectos' })
    }
  })

  it('el proyecto de una ruta', () => {
    expect(proyectoDe({ tipo: 'proyectos' })).toBeNull()
    expect(proyectoDe({ tipo: 'proyecto', proyectoId: P })).toBe(P)
    expect(proyectoDe({ tipo: 'diagrama', proyectoId: P, diagramaId: D })).toBe(P)
  })
})
