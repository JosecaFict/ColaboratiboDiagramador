import { afterEach, describe, expect, it, vi } from 'vitest'
import type { AppliedOperation } from '../contracts/command'
import { ColabClient, type OperacionHistorica } from './wsClient'

/**
 * EC-08: el cliente aplica las operaciones en orden y sin huecos.
 *
 * Antes detectaba el hueco, mostraba "Resincronizando..." y aplicaba igual la
 * operacion salteada: el modelo quedaba con un agujero. Estos tests fijan el
 * comportamiento correcto sin necesitar un servidor: se inyectan operaciones
 * como si llegaran por el WebSocket y se simula el log con `fetch`.
 */

const op = (seq: number): AppliedOperation => ({
  opId: 'op-' + seq, seq, type: 'MOVE_ELEMENT', origin: 'MOUSE',
  payload: { elementId: 'x', x: seq, y: 0 }, userId: null, ts: '',
})

const historica = (seq: number): OperacionHistorica => ({
  opId: 'op-' + seq, seq, opType: 'MOVE_ELEMENT', origin: 'MOUSE',
  payload: JSON.stringify({ elementId: 'x', x: seq, y: 0 }), userId: null, createdAt: '',
})

/** Un cliente sin conectar, con el `seq` inicial y registro de lo aplicado. */
function cliente(seqInicial: number) {
  const aplicadas: number[] = []
  const errores: string[] = []
  const c = new ColabClient('diagrama', 'token', {
    onOp: (o) => aplicadas.push(o.seq),
    onError: (r) => errores.push(r.message),
  })
  c.fijarSeq(seqInicial)
  // `recibir` es lo que se ejecuta cuando llega un mensaje por /ops.
  const recibir = (o: AppliedOperation) => (c as unknown as { recibir: (o: AppliedOperation) => void }).recibir(o)
  return { c, aplicadas, errores, recibir }
}

/** Deja correr las promesas pendientes (el fetch simulado y lo que encadena). */
const esperar = () => new Promise((r) => setTimeout(r, 0))

afterEach(() => { vi.unstubAllGlobals() })

describe('ColabClient: orden y huecos', () => {
  it('aplica en orden las operaciones consecutivas', () => {
    const { aplicadas, recibir } = cliente(5)

    recibir(op(6)); recibir(op(7))

    expect(aplicadas).toEqual([6, 7])
  })

  it('descarta una operacion repetida o ya aplicada', () => {
    const { aplicadas, recibir } = cliente(5)

    recibir(op(6)); recibir(op(6)); recibir(op(4))

    expect(aplicadas).toEqual([6])
  })

  it('ante un hueco NO aplica la salteada: recupera las que faltan y aplica todo en orden', async () => {
    const fetch = vi.fn().mockResolvedValue({ ok: true, json: async () => [historica(6), historica(7)] })
    vi.stubGlobal('fetch', fetch)
    const { aplicadas, recibir } = cliente(5)

    recibir(op(8))                               // faltan la 6 y la 7
    expect(aplicadas).toEqual([])                // la 8 espera su turno
    await esperar()

    expect(fetch).toHaveBeenCalledWith(expect.stringContaining('sinceSeq=5'), expect.anything())
    expect(aplicadas).toEqual([6, 7, 8])
  })

  it('lo que llega mientras se recupera espera y no se duplica', async () => {
    let responder: (v: unknown) => void = () => {}
    vi.stubGlobal('fetch', vi.fn().mockReturnValue(new Promise((r) => { responder = r })))
    const { aplicadas, recibir } = cliente(5)

    recibir(op(8))                               // dispara la recuperacion
    recibir(op(9))                               // llega en vivo durante el fetch
    recibir(op(7))                               // y esta tambien vendra en el log
    responder({ ok: true, json: async () => [historica(6), historica(7)] })
    await esperar()

    expect(aplicadas).toEqual([6, 7, 8, 9])
  })

  it('si el servidor no responde, no aplica lo que esta en espera', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('sin red')))
    const { c, aplicadas, errores, recibir } = cliente(5)

    recibir(op(8))
    await esperar()

    // Aplicar la 8 igual seria volver a dejar un agujero en el modelo.
    expect(aplicadas).toEqual([])
    expect(errores[0]).toContain('Reintentando')
    c.desactivar()
  })
})
