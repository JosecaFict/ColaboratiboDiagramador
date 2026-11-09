import { describe, expect, it, vi } from 'vitest'
import { aplicarPropuesta, type ResultadoDeAplicar } from './aplicacion'
import { fusionarEntrada, reconciliarHistorial } from './historial'
import type { ResultadoDeLote } from './esperaDeLotes'
import type { EntradaHistorialIA, PropuestaIA } from './tipos'

// T-14: las dos regresiones que pidió Codex en el repaso de T-04 (f1d846c).

const propuesta = (id: string): PropuestaIA => ({
  diagramId: 'd1', origin: 'TEXT', rechazados: [], descartadosPorTope: [], advertencias: [],
  propuestosPorElModelo: 1, notaDelModelo: 'Listo', modelo: 'qwen3:8b', interaccionId: id,
  aceptados: [{ tipo: 'CREATE_CLASS', payload: { name: 'Pago' }, resumen: 'Crear Pago' }],
})

const entrada = (id: string, creadaEn: string, parcial: Partial<EntradaHistorialIA> = {}): EntradaHistorialIA => ({
  id, userId: 'u1', userName: 'Beto', modo: 'MODIFICAR', modelo: 'qwen3:8b', prompt: `pedido ${id}`,
  respuesta: 'Listo', propuesta: propuesta(id), aplicada: false, creadaEn, ...parcial,
})

const a = entrada('a', '2026-11-02T10:00:00Z')
const b = entrada('b', '2026-11-02T10:01:00Z')

describe('P2-1 · un GET de historial atrasado no borra lo que llegó por STOMP', () => {
  it('el GET pidió [a]; mientras tanto llegó NUEVA de b; al resolver el GET sobreviven a y b', () => {
    // Antes del GET la vista ya tenía b (por el tópico); el servidor respondió con la instantánea [a].
    const antes = [b]
    const despues = reconciliarHistorial(antes, [a])
    expect(despues.map((e) => e.id)).toEqual(['a', 'b'])
  })

  it('un APLICADA que llegó mientras tanto no se pierde con una instantánea que aún dice false', () => {
    const conMarca = fusionarEntrada([a, b], { ...a, aplicada: true })
    const despues = reconciliarHistorial(conMarca, [a, b])
    expect(despues.find((e) => e.id === 'a')?.aplicada).toBe(true)
    expect(despues.map((e) => e.id)).toEqual(['a', 'b'])
  })

  it('NUEVA y APLICADA juntas: el GET atrasado conserva las dos cosas', () => {
    let vista: EntradaHistorialIA[] = [a]
    vista = fusionarEntrada(vista, b)                                   // NUEVA de b
    vista = fusionarEntrada(vista, { ...b, aplicada: true })            // APLICADA de b
    const despues = reconciliarHistorial(vista, [a])                    // el GET se resuelve con la foto vieja
    expect(despues.map((e) => [e.id, e.aplicada])).toEqual([['a', false], ['b', true]])
  })

  it('al reconectar, el GET trae lo perdido y nada de lo visible desaparece', () => {
    const c = entrada('c', '2026-11-02T10:02:00Z')
    const durante = [a, b]                                              // lo que se veía al cortarse
    const despues = reconciliarHistorial(durante, [a, b, c])            // c se perdió durante el corte
    expect(despues.map((e) => e.id)).toEqual(['a', 'b', 'c'])
  })

  it('los datos del servidor mandan sobre los locales, pero "aplicada" nunca se desmarca', () => {
    const local = entrada('a', '2026-11-02T10:00:00Z', { userName: 'yo', aplicada: true })
    const servidor = entrada('a', '2026-11-02T10:00:00Z', { userName: 'Beto', aplicada: false })
    const [r] = reconciliarHistorial([local], [servidor])
    expect(r.userName).toBe('Beto')
    expect(r.aplicada).toBe(true)
  })

  it('una entrada local con la hora del cliente adelantada queda en su lugar por fecha', () => {
    const c = entrada('c', '2026-11-02T10:02:00Z')
    const despues = reconciliarHistorial([c], [a, b])
    expect(despues.map((e) => e.id)).toEqual(['a', 'b', 'c'])
  })

  it('con fechas iguales el desempate es por id, igual que el servidor', () => {
    const x = entrada('x2', '2026-11-02T10:00:00Z')
    const y = entrada('x1', '2026-11-02T10:00:00Z')
    expect(reconciliarHistorial([x], [y]).map((e) => e.id)).toEqual(['x1', 'x2'])
  })

  it('no repite una entrada que llegó por los dos caminos', () => {
    expect(reconciliarHistorial([a, b], [a, b]).map((e) => e.id)).toEqual(['a', 'b'])
  })

  it('una lista vacía del servidor no borra lo que ya se vio', () => {
    expect(reconciliarHistorial([a], []).map((e) => e.id)).toEqual(['a'])
  })
})

const aceptado = (opId: string): ResultadoDeLote => ({ estado: 'aceptado', opId })

describe('P2-2 · un BATCH aceptado no se vuelve a mandar si falla la marca', () => {
  /** Un "bus" que numera los lotes y un "servidor" cuyo PATCH se puede hacer fallar. */
  const montar = (fallosDeMarca: number) => {
    let n = 0
    let restantes = fallosDeMarca
    const lotes: string[] = []
    const marcas: string[] = []
    const enviarLote = vi.fn(async (): Promise<ResultadoDeLote> => { const opId = `op-${++n}`; lotes.push(opId); return aceptado(opId) })
    const marcar = vi.fn(async (opId: string) => {
      marcas.push(opId)
      if (restantes-- > 0) throw new Error('HTTP 500')
      return a
    })
    return { enviarLote, marcar, lotes, marcas }
  }

  it('BATCH aceptado + PATCH 500: queda aplicada sin marca y NO se pide otro lote', async () => {
    const m = montar(1)
    const r1 = await aplicarPropuesta({ interaccionId: 'a', confirmada: undefined, enviarLote: m.enviarLote, marcar: m.marcar })
    expect(r1).toMatchObject({ tipo: 'aplicada-sin-marca', opId: 'op-1' })
    expect(m.lotes).toEqual(['op-1'])

    // Segundo clic: se reintenta SOLO la marca, con el mismo opId.
    const r2 = await aplicarPropuesta({ interaccionId: 'a', confirmada: opIdDe(r1), enviarLote: m.enviarLote, marcar: m.marcar })
    expect(r2).toMatchObject({ tipo: 'marcada', opId: 'op-1' })
    expect(m.lotes).toEqual(['op-1'])                // un solo BATCH en toda la secuencia
    expect(m.marcas).toEqual(['op-1', 'op-1'])       // PATCH500, PATCH ok: mismo opId
  })

  it('aunque la marca siga fallando varias veces, el lote nunca se repite', async () => {
    const m = montar(3)
    let confirmada: string | undefined
    for (let i = 0; i < 4; i++) {
      const r = await aplicarPropuesta({ interaccionId: 'a', confirmada, enviarLote: m.enviarLote, marcar: m.marcar })
      confirmada = opIdDe(r) ?? confirmada
    }
    expect(m.lotes).toEqual(['op-1'])
    expect(m.marcas).toEqual(['op-1', 'op-1', 'op-1', 'op-1'])
  })

  it('un rechazo del bus SÍ permite corregir y volver a intentar, con un lote nuevo', async () => {
    let n = 0
    const enviarLote = vi.fn(async (): Promise<ResultadoDeLote> =>
      ++n === 1 ? { estado: 'rechazado', opId: 'op-1', mensaje: 'FORBIDDEN' } : aceptado('op-2'))
    const marcar = vi.fn(async () => a)
    const r1 = await aplicarPropuesta({ interaccionId: 'a', confirmada: undefined, enviarLote, marcar })
    expect(r1).toMatchObject({ tipo: 'rechazado', mensaje: 'FORBIDDEN' })
    expect(marcar).not.toHaveBeenCalled()             // no se marca antes de la confirmación
    const r2 = await aplicarPropuesta({ interaccionId: 'a', confirmada: opIdDe(r1), enviarLote, marcar })
    expect(r2).toMatchObject({ tipo: 'marcada', opId: 'op-2' })
    expect(enviarLote).toHaveBeenCalledTimes(2)
  })

  it('sin confirmar a tiempo tampoco se marca, y se puede reintentar', async () => {
    const enviarLote = vi.fn(async (): Promise<ResultadoDeLote> => ({ estado: 'sin-confirmar', opId: 'op-1' }))
    const marcar = vi.fn(async () => a)
    const r = await aplicarPropuesta({ interaccionId: 'a', confirmada: undefined, enviarLote, marcar })
    expect(r.tipo).toBe('sin-confirmar')
    expect(marcar).not.toHaveBeenCalled()
    expect(opIdDe(r)).toBeUndefined()
  })

  it('sin turno en el historial no hay marca que pedir, pero el lote aceptado cuenta como aplicado', async () => {
    const marcar = vi.fn(async () => a)
    const r = await aplicarPropuesta({ interaccionId: undefined, confirmada: undefined, enviarLote: async () => aceptado('op-9'), marcar })
    expect(r).toMatchObject({ tipo: 'aplicada-sin-turno', opId: 'op-9' })
    expect(marcar).not.toHaveBeenCalled()
  })
})

/** El opId que la vista guarda para reintentar solo la marca. */
function opIdDe(r: ResultadoDeAplicar): string | undefined {
  return r.tipo === 'aplicada-sin-marca' ? r.opId : undefined
}
