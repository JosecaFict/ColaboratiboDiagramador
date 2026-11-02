import { renderToStaticMarkup } from 'react-dom/server'
import { describe, expect, it } from 'vitest'
import { VistaDocumental } from '../documental/VistaDocumental'
import { VistaDiff } from './VistaDiff'
import { VistaMerge } from './VistaMerge'
import type { DiffVersiones, MergePreview } from './tipos'

const grupoVacio = () => ({ agregadas: [], eliminadas: [], modificadas: [] })
const diff = (parcial: Partial<DiffVersiones> = {}): DiffVersiones => ({
  desde: { versionId: 'v1', seq: 10 }, hasta: { versionId: null, seq: 12 },
  clases: grupoVacio(), atributos: grupoVacio(), metodos: grupoVacio(), relaciones: grupoVacio(), ...parcial,
})

describe('VistaDiff', () => {
  it('rotula los extremos y avisa cuando no hay diferencias', () => {
    const html = renderToStaticMarkup(<VistaDiff diff={diff()} nombres={{ v1: 'entrega' }} />)
    expect(html).toContain('entrega (#10)')
    expect(html).toContain('el estado actual (#12)')
    expect(html).toContain('Sin diferencias')
  })

  it('agrupa por tipo y pinta agregado / eliminado / modificado', () => {
    const html = renderToStaticMarkup(
      <VistaDiff nombres={{}} onResaltar={() => {}} diff={diff({
        clases: { agregadas: [{ id: 'c3', nombre: 'Prestamo' }], eliminadas: [], modificadas: [] },
        atributos: {
          agregadas: [], eliminadas: [{ id: 'a2', nombre: 'isbn', claseId: 'c1', clase: 'Libro' }],
          modificadas: [{ id: 'a1', nombre: 'titulo', claseId: 'c1', clase: 'Libro', cambios: [{ campo: 'required', antes: false, despues: true }] }],
        },
      })} />)
    expect(html).toContain('Clases')
    expect(html).toContain('Atributos')
    expect(html).not.toContain('Métodos')           // un grupo sin cambios no se dibuja
    expect(html).toContain('diff-agregado')
    expect(html).toContain('diff-eliminado')
    expect(html).toContain('diff-modificado')
    expect(html).toContain('en Libro')
    expect(html).toContain('<del>no</del>')
    expect(html).toContain('<ins>sí</ins>')
    expect(html).toContain('Resaltar en el lienzo')
  })
})

describe('VistaMerge', () => {
  const preview: MergePreview = {
    nuestros: { clases: grupoVacio(), atributos: grupoVacio(), metodos: grupoVacio(), relaciones: grupoVacio() },
    suyos: {
      clases: { agregadas: [{ id: 'c3', nombre: 'Nueva' }], eliminadas: [], modificadas: [] },
      atributos: grupoVacio(), metodos: grupoVacio(), relaciones: grupoVacio(),
    },
    conflictos: [{ elementId: 'c1', tipo: 'clase', campo: 'name', base: 'Libro', nuestro: 'Obra', suyo: 'Titulo' }],
  }
  const props = { nombreDeOrigen: 'idea', aplicando: false, error: null, onAplicar: () => {}, onVolver: () => {} }

  it('no deja aplicar mientras haya conflictos sin elegir', () => {
    const html = renderToStaticMarkup(<VistaMerge preview={preview} puedeAplicar {...props} />)
    expect(html).toContain('1 sin resolver de 1')
    expect(html).toMatch(/<button[^>]*disabled[^>]*>Aplicar merge/)
    expect(html).toContain('Obra')
    expect(html).toContain('Titulo')
  })

  it('un Observador no puede aplicar aunque no haya conflictos', () => {
    const html = renderToStaticMarkup(<VistaMerge preview={{ ...preview, conflictos: [] }} puedeAplicar={false} {...props} />)
    expect(html).toMatch(/<button[^>]*disabled[^>]*>Aplicar merge/)
  })

  it('sin conflictos y con cambios, se puede aplicar', () => {
    const html = renderToStaticMarkup(<VistaMerge preview={{ ...preview, conflictos: [] }} puedeAplicar {...props} />)
    expect(html).not.toMatch(/<button[^>]*disabled[^>]*>Aplicar merge/)
  })
})

describe('VistaDocumental', () => {
  it('dibuja colecciones con campos, incrustados, referencias y advertencias', () => {
    const html = renderToStaticMarkup(<VistaDocumental error={null} modelo={{
      advertencias: ['LineaPedido también se referencia'],
      colecciones: [{
        nombre: 'pedidos', entidad: 'Pedido', discriminador: null,
        campos: [{ nombre: 'fecha', tipo: 'date', requerido: true }],
        incrustados: [{ campo: 'lineas', entidad: 'LineaPedido', multiple: true }],
        referencias: [{ campo: 'clienteId', coleccion: 'clientes', multiple: false }],
      }],
    }} />)
    expect(html).toContain('pedidos')
    expect(html).toContain('[LineaPedido]')
    expect(html).toContain('→ clientes')
    expect(html).toContain('LineaPedido también se referencia')
  })

  it('muestra el error y el estado de carga', () => {
    expect(renderToStaticMarkup(<VistaDocumental error="No se encontró" modelo={null} />)).toContain('No se encontró')
    expect(renderToStaticMarkup(<VistaDocumental error={null} modelo={null} />)).toContain('Derivando')
  })
})

describe('conflictos con nombre, clase y decisiones', () => {
  const grupos = { clases: grupoVacio(), atributos: grupoVacio(), metodos: grupoVacio(), relaciones: grupoVacio() }
  const props = { nombreDeOrigen: 'idea', puedeAplicar: true, aplicando: false, error: null, onAplicar: () => {}, onVolver: () => {} }

  it('muestra el nombre del elemento, su clase y una etiqueta legible para decision.X', () => {
    const html = renderToStaticMarkup(<VistaMerge {...props} preview={{
      nuestros: grupos, suyos: grupos,
      conflictos: [
        { elementId: 'a1', tipo: 'atributo', campo: 'dataType', base: 'String', nuestro: 'Text', suyo: 'int', nombre: 'titulo', clase: 'Libro' },
        { elementId: 'r1', tipo: 'relacion', campo: 'decision.COMPOSITION', base: null, nuestro: 'COMPOSITION', suyo: 'AGGREGATION', nombre: 'Libro → Autor' },
      ],
    }} />)
    expect(html).toContain('«titulo»')
    expect(html).toContain('en Libro')
    expect(html).toContain('decisión: composición')
  })

  it('el movimiento de una caja se lee como posición, no como "x"', () => {
    const html = renderToStaticMarkup(
      <VistaDiff nombres={{}} diff={diff({
        clases: { agregadas: [], eliminadas: [], modificadas: [
          { id: 'c1', nombre: 'Libro', cambios: [{ campo: 'x', antes: 100, despues: 150 }, { campo: 'width', antes: 170, despues: 200 }] },
        ] },
        relaciones: { agregadas: [], eliminadas: [], modificadas: [
          { id: 'r1', nombre: 'Libro → Autor', cambios: [{ campo: 'waypoints', antes: [], despues: [{ x: 1, y: 2 }, { x: 3, y: 4 }] }] },
        ] },
      })} />)
    expect(html).toContain('posición x')
    expect(html).toContain('ancho')
    expect(html).toContain('quiebres de la línea')
    expect(html).toContain('recta')
    expect(html).toContain('2 puntos')
  })
})

describe('nombre repetido y reintento', () => {
  const grupos = { clases: grupoVacio(), atributos: grupoVacio(), metodos: grupoVacio(), relaciones: grupoVacio() }

  it('explica el motivo y que pasa con cada eleccion', () => {
    const html = renderToStaticMarkup(<VistaMerge nombreDeOrigen="idea" puedeAplicar aplicando={false} error={null}
      onAplicar={() => {}} onVolver={() => {}} preview={{
        nuestros: grupos, suyos: grupos,
        conflictos: [{ elementId: 'c9', tipo: 'clase', campo: 'name', base: null, nuestro: null, suyo: 'Prestamo',
          nombre: 'Prestamo', motivo: 'nombre repetido', sugerido: 'Prestamo_2' }],
      }} />)
    expect(html).toContain('nombre repetido')
    expect(html).toContain('Prestamo_2')
    expect(html).toContain('ese cambio no entra')
  })

  it('solo se ofrece reintentar el 409 que pide volver a intentar', async () => {
    const { esReintentable } = await import('./PanelVersiones')
    const { ErrorDeApi } = await import('../auth/cliente')
    expect(esReintentable(new ErrorDeApi(409, 'El diagrama cambio mientras se preparaba el cambio (seq 3 -> 5). Volve a intentar: no se aplico nada.'))).toBe(true)
    expect(esReintentable(new ErrorDeApi(409, 'Faltan resolver 2 conflictos'))).toBe(false)
    expect(esReintentable(new ErrorDeApi(403, 'Volve a intentar'))).toBe(false)
    expect(esReintentable(new Error('Volve a intentar'))).toBe(false)
  })
})
