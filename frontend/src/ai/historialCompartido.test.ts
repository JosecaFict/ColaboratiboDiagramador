import { afterEach, describe, expect, it, vi } from 'vitest'
import { EsperaDeLotes } from './esperaDeLotes'
import { fusionarEntrada, origenDelLote, reconciliarHistorial, ultimaConCambios } from './historial'
import type { EntradaHistorialIA, PropuestaIA } from './tipos'

const propuesta = (parcial: Partial<PropuestaIA> = {}): PropuestaIA => ({
  diagramId: 'd1', origin: 'TEXT', rechazados: [], descartadosPorTope: [], advertencias: [],
  propuestosPorElModelo: 1, notaDelModelo: 'Listo', modelo: 'qwen3:8b',
  aceptados: [{ tipo: 'CREATE_CLASS', payload: { name: 'Pago' }, resumen: 'Crear Pago' }],
  ...parcial,
})

const entrada = (id: string, parcial: Partial<EntradaHistorialIA> = {}): EntradaHistorialIA => ({
  id, userId: 'u1', userName: 'Beto', modo: 'MODIFICAR', modelo: 'qwen3:8b', prompt: `pedido ${id}`,
  respuesta: 'Listo', propuesta: propuesta({ interaccionId: id }), aplicada: false,
  creadaEn: '2026-11-01T10:00:00Z', ...parcial,
})

describe('historial compartido de la IA', () => {
  it('agrega al final lo que otra pestaña pidió', () => {
    const r = fusionarEntrada([entrada('a')], entrada('b'))
    expect(r.map((e) => e.id)).toEqual(['a', 'b'])
  })

  it('la misma entrada por HTTP y por el aviso queda una sola vez y en su lugar', () => {
    const local = entrada('b', { userName: 'yo' })
    const delAviso = entrada('b', { userName: 'Beto', creadaEn: '2026-11-01T10:00:05Z' })
    const r = fusionarEntrada(fusionarEntrada([entrada('a'), local, entrada('c')], delAviso), local)
    expect(r.map((e) => e.id)).toEqual(['a', 'b', 'c'])
    expect(r).toHaveLength(3)
  })

  it('la marca de aplicada llega por el aviso y no se pierde con un aviso viejo', () => {
    const aplicada = fusionarEntrada([entrada('a')], entrada('a', { aplicada: true }))
    expect(aplicada[0].aplicada).toBe(true)
    // El aviso NUEVA de la misma entrada llega atrasado: no la desmarca.
    expect(fusionarEntrada(aplicada, entrada('a'))[0].aplicada).toBe(true)
  })

  it('al recargar se fusiona con lo ya visto: se conserva lo aplicado y lo que llegó por el tópico (T-14)', () => {
    const actuales = [entrada('a', { aplicada: true }), entrada('local')]
    const r = reconciliarHistorial(actuales, [entrada('a'), entrada('b')])
    expect(r.map((e) => [e.id, e.aplicada])).toEqual([['a', true], ['b', false], ['local', false]])
  })

  it('el panel muestra la última propuesta con cambios, sea de quien sea', () => {
    const consulta = entrada('c', { propuesta: propuesta({ aceptados: [], interaccionId: 'c' }) })
    expect(ultimaConCambios([entrada('a'), entrada('b', { userName: 'Ana' }), consulta])?.id).toBe('b')
    expect(ultimaConCambios([consulta])).toBeUndefined()
  })

  it('un pedido dictado se aplica como VOICE y uno escrito como TEXT', () => {
    expect(origenDelLote(propuesta({ origin: 'VOICE' }))).toBe('VOICE')
    expect(origenDelLote(propuesta({ origin: 'TEXT' }))).toBe('TEXT')
  })
})

describe('espera del lote de la IA', () => {
  afterEach(() => { vi.useRealTimers() })

  it('se resuelve como aceptado solo cuando vuelve la operación', async () => {
    const espera = new EsperaDeLotes()
    const resultado = espera.esperar('op-1')
    expect(espera.confirmar('otra')).toBe(false)
    expect(espera.espera('op-1')).toBe(true)
    expect(espera.confirmar('op-1')).toBe(true)
    await expect(resultado).resolves.toEqual({ estado: 'aceptado', opId: 'op-1' })
    expect(espera.espera('op-1')).toBe(false)
  })

  it('un rechazo del bus se informa y no cuenta como aplicado', async () => {
    const espera = new EsperaDeLotes()
    const resultado = espera.esperar('op-2')
    expect(espera.rechazar('op-2', 'Ese elemento lo esta editando Ana')).toBe(true)
    // Una confirmación posterior del mismo opId ya no cambia nada.
    expect(espera.confirmar('op-2')).toBe(false)
    await expect(resultado).resolves.toEqual({
      estado: 'rechazado', opId: 'op-2', mensaje: 'Ese elemento lo esta editando Ana',
    })
  })

  it('si el bus no responde a tiempo queda sin confirmar, no aplicado', async () => {
    vi.useFakeTimers()
    const espera = new EsperaDeLotes(1000)
    const resultado = espera.esperar('op-3')
    vi.advanceTimersByTime(1000)
    await expect(resultado).resolves.toEqual({ estado: 'sin-confirmar', opId: 'op-3' })
    expect(espera.confirmar('op-3')).toBe(false)
  })
})
