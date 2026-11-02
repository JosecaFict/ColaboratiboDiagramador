/**
 * Resultado de mandar un lote de la IA por el bus. "aceptado" quiere decir que
 * volvió la operación con su seq; nada antes de eso cuenta como aplicado (T-04).
 */
export type ResultadoDeLote =
  | { estado: 'aceptado'; opId: string }
  | { estado: 'rechazado'; opId: string; mensaje: string }
  | { estado: 'sin-confirmar'; opId: string }

/**
 * Lotes enviados que esperan la respuesta del bus. App manda el BATCH y avisa
 * aquí cuando vuelve la operación (onOp) o el rechazo privado (onError).
 *
 * El plazo es mayor que el del bus (10 s). Si vence, la propuesta queda sin
 * marcar en vez de marcarse a ciegas.
 */
export class EsperaDeLotes {
  private pendientes = new Map<string, { resolver: (r: ResultadoDeLote) => void; plazo: ReturnType<typeof setTimeout> }>()

  constructor(private plazoMs = 15000) {}

  esperar(opId: string): Promise<ResultadoDeLote> {
    return new Promise((resolver) => {
      const plazo = setTimeout(() => this.terminar(opId, { estado: 'sin-confirmar', opId }), this.plazoMs)
      this.pendientes.set(opId, { resolver, plazo })
    })
  }

  /** @returns true si el opId era un lote que se esperaba. */
  confirmar(opId: string): boolean {
    return this.terminar(opId, { estado: 'aceptado', opId })
  }

  /** @returns true si el opId era un lote que se esperaba. */
  rechazar(opId: string, mensaje: string): boolean {
    return this.terminar(opId, { estado: 'rechazado', opId, mensaje })
  }

  espera(opId: string) { return this.pendientes.has(opId) }

  private terminar(opId: string, r: ResultadoDeLote): boolean {
    const p = this.pendientes.get(opId)
    if (!p) return false
    clearTimeout(p.plazo)
    this.pendientes.delete(opId)
    p.resolver(r)
    return true
  }
}
