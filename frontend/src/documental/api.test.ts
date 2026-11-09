import { describe, expect, it, vi } from 'vitest'
import { crearCliente } from '../auth/cliente'
import { consultaDeAlmacen, leerDocumental, normalizarDocumental } from './api'

describe('documental', () => {
  it('lee /documental y ordena las colecciones por nombre', async () => {
    const base = vi.fn(async () => new Response(JSON.stringify({
      colecciones: [
        { nombre: 'pedidos', entidad: 'Pedido', campos: [{ nombre: 'fecha', tipo: 'date', requerido: true }],
          incrustados: [{ campo: 'lineas', entidad: 'LineaPedido', multiple: true }],
          referencias: [{ campo: 'clienteId', coleccion: 'clientes', multiple: false }], discriminador: null },
        { nombre: 'clientes', entidad: 'Cliente', campos: [], incrustados: [], referencias: [], discriminador: '_tipo' },
      ],
      advertencias: ['algo'],
    }), { status: 200 }))
    const m = await leerDocumental(crearCliente(() => 't', base as unknown as typeof fetch), 'd1')
    expect((base.mock.calls[0] as unknown as [string])[0]).toBe('/api/v1/diagrams/d1/documental')
    expect(m.colecciones.map((c) => c.nombre)).toEqual(['clientes', 'pedidos'])
    expect(m.advertencias).toEqual(['algo'])
  })

  it('tolera campos ausentes', () => {
    const m = normalizarDocumental({ colecciones: [{ nombre: 'a', entidad: 'A' } as never] })
    expect(m.colecciones[0]).toEqual({ nombre: 'a', entidad: 'A', campos: [], incrustados: [], referencias: [], discriminador: null })
    expect(normalizarDocumental(null)).toEqual({ colecciones: [], advertencias: [] })
  })

  it('el selector pasa ?store= solo cuando no es PostgreSQL', () => {
    expect(consultaDeAlmacen('postgres')).toBe('')
    expect(consultaDeAlmacen('mongo')).toBe('?store=mongo')
    expect(consultaDeAlmacen('mongo', '&')).toBe('&store=mongo')
  })
})
