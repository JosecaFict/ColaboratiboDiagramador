import { Client, type IMessage } from '@stomp/stompjs'
import SockJS from 'sockjs-client'
import type {
  AppliedOperation, Command, CommandRejection, LockMessage, PresenceMessage,
} from '../contracts/command'

/** Forma cruda que devuelve GET /diagrams/{id}/operations (entidad JPA, no AppliedOperation). */
export interface OperacionHistorica {
  opId: string; seq: number; opType: AppliedOperation['type']; origin: AppliedOperation['origin']
  payload: string; userId: string | null; createdAt: string
}

export const historicaAAplicada = (h: OperacionHistorica): AppliedOperation => ({
  opId: h.opId, seq: h.seq, type: h.opType, origin: h.origin,
  payload: JSON.parse(h.payload), userId: h.userId, ts: h.createdAt,
})

/**
 * Cliente del plano de datos.
 *
 * Todo cambio sale de aca como un Command y entra como AppliedOperation con
 * su `seq`. El cliente es responsable de aplicar las operaciones EN ORDEN y
 * SIN HUECOS (EC-08):
 *
 *   - Si llega el `seq` siguiente, se aplica.
 *   - Si llega uno repetido o viejo, se descarta.
 *   - Si llega uno salteado, NO se aplica: se guarda, se piden al servidor las
 *     que faltan y recien entonces se aplica todo en orden.
 *   - Al conectar y al RECONECTAR se pregunta que se perdio, sin esperar a que
 *     la proxima operacion delate el hueco. Si nadie edita despues de un corte,
 *     esa proxima operacion podria no llegar nunca.
 *
 * Antes se detectaba el hueco, se mostraba "Resincronizando..." y no se hacia
 * nada: la operacion salteada se aplicaba igual y el modelo quedaba con un
 * agujero que nadie iba a notar.
 */
export class ColabClient {
  private client: Client
  private ultimoSeq = 0
  private conectado = false
  private resincronizando = false
  /** Operaciones que llegaron mientras faltaban anteriores: esperan su turno. */
  private enEspera: AppliedOperation[] = []
  private reintento: ReturnType<typeof setTimeout> | null = null

  constructor(
    private diagramId: string,
    private token: string,
    private handlers: {
      onOp?: (op: AppliedOperation) => void
      /** true mientras se recuperan operaciones perdidas; false al terminar. */
      onResincronizando?: (activo: boolean) => void
      onPresence?: (p: PresenceMessage) => void
      onLock?: (l: LockMessage) => void
      onError?: (r: CommandRejection) => void
      onEstado?: (conectado: boolean) => void
      /**
       * El servidor corto el canal con un frame ERROR: lo tipico es un SUBSCRIBE
       * negado porque el usuario ya no es miembro del proyecto (ADR-026).
       */
      onCanalRechazado?: (motivo: string) => void
    } = {}
  ) {
    this.client = new Client({
      webSocketFactory: () => new SockJS('/ws') as unknown as WebSocket,
      connectHeaders: { Authorization: 'Bearer ' + this.token },
      reconnectDelay: 2000,
      onConnect: () => {
        this.conectado = true
        this.handlers.onEstado?.(true)
        this.suscribir()
        // Entre el snapshot y la suscripcion, o durante un corte, pudieron
        // pasar operaciones que ya no van a volver a emitirse.
        void this.resincronizar()
      },
      onWebSocketClose: () => {
        this.conectado = false
        this.handlers.onEstado?.(false)
      },
      onStompError: (frame) =>
        this.handlers.onCanalRechazado?.(String(frame.headers['message'] ?? '')),
    })
  }

  private suscribir() {
    const base = `/topic/diagram/${this.diagramId}`

    this.client.subscribe(`${base}/ops`, (m: IMessage) => this.recibir(JSON.parse(m.body)))

    this.client.subscribe(`${base}/presence`, (m) =>
      this.handlers.onPresence?.(JSON.parse(m.body)))

    this.client.subscribe(`${base}/locks`, (m) =>
      this.handlers.onLock?.(JSON.parse(m.body)))

    // Cola de usuario: Spring la resuelve por sesion, el rechazo lo ve SOLO
    // quien lo provoco (antes era un /topic publico que cualquiera podia leer).
    this.client.subscribe('/user/queue/errors', (m) =>
      this.handlers.onError?.(JSON.parse(m.body)))
  }

  private recibir(op: AppliedOperation) {
    if (this.resincronizando) { this.enEspera.push(op); return }
    if (op.seq <= this.ultimoSeq) return                  // repetida o ya aplicada
    if (op.seq > this.ultimoSeq + 1) {                    // HUECO: faltan anteriores
      this.enEspera.push(op)
      void this.resincronizar()
      return
    }
    this.aplicar(op)
  }

  private aplicar(op: AppliedOperation) {
    this.ultimoSeq = op.seq
    this.handlers.onOp?.(op)
  }

  /**
   * Trae del log todo lo posterior al ultimo `seq` aplicado.
   *
   * Se usa el log y no el snapshot porque recupera EXACTAMENTE lo que falta
   * sin tirar el estado local, y porque las operaciones recuperadas pasan por
   * el mismo camino que las en vivo: el panel de operaciones y el modelo quedan
   * coherentes entre si.
   */
  async resincronizar() {
    if (this.resincronizando) return
    this.resincronizando = true
    this.handlers.onResincronizando?.(true)
    let progreso = false
    let fallo = false
    try {
      const r = await fetch(
        `/api/v1/diagrams/${this.diagramId}/operations?sinceSeq=${this.ultimoSeq}`,
        { headers: { Authorization: 'Bearer ' + this.token } })
      if (!r.ok) throw new Error(String(r.status))
      const perdidas: OperacionHistorica[] = await r.json()
      for (const h of perdidas) {
        if (h.seq !== this.ultimoSeq + 1) continue
        this.aplicar(historicaAAplicada(h))
        progreso = true
      }
    } catch {
      fallo = true
    } finally {
      this.resincronizando = false
      this.handlers.onResincronizando?.(false)
    }

    if (fallo) {
      // Lo que esta en espera NO se descarta: se reintenta. Aplicarlo ahora
      // seria volver a dejar un agujero en el modelo.
      this.handlers.onError?.({ opId: '', reason: 'STALE',
        message: 'No se pudieron recuperar operaciones perdidas. Reintentando...' })
      if (this.reintento) clearTimeout(this.reintento)
      this.reintento = setTimeout(() => { if (this.conectado) void this.resincronizar() }, 3000)
      return
    }

    const espera = this.enEspera.sort((a, b) => a.seq - b.seq)
    this.enEspera = []
    for (const op of espera) {
      if (op.seq <= this.ultimoSeq) continue
      if (op.seq === this.ultimoSeq + 1) { this.aplicar(op); continue }
      // Sigue faltando algo que el log tampoco trajo. Reintentar sin haber
      // avanzado entraria en un bucle: se avisa y se espera al proximo intento.
      if (!progreso) {
        this.enEspera.push(op)
        this.handlers.onError?.({ opId: '', reason: 'STALE',
          message: `Falta la operacion #${this.ultimoSeq + 1} y el servidor no la devolvio. Recarga la pagina.` })
        return
      }
      this.enEspera.push(op)
      void this.resincronizar()
      return
    }
  }

  activar() { this.client.activate() }
  desactivar() {
    if (this.reintento) clearTimeout(this.reintento)
    this.client.deactivate()
  }
  estaConectado() { return this.conectado }
  seqActual() { return this.ultimoSeq }
  fijarSeq(seq: number) { this.ultimoSeq = seq }

  /** El userId ya NO viaja aca: el servidor lo toma del JWT validado en el CONNECT. */
  enviarComando(command: Command) {
    this.client.publish({
      destination: `/app/diagram/${this.diagramId}/op`,
      body: JSON.stringify(command),
    })
  }

  /** Nivel 4: efimero. Nunca toca el modelo. */
  enviarPresencia(p: PresenceMessage) {
    if (!this.conectado) return
    this.client.publish({
      destination: `/app/diagram/${this.diagramId}/presence`,
      body: JSON.stringify(p),
    })
  }

  /** Igual que enviarComando: el userId lo pone el servidor desde el JWT. */
  bloquear(elementId: string, userName: string) {
    this.client.publish({
      destination: `/app/diagram/${this.diagramId}/lock`,
      body: JSON.stringify({ elementId, userName, held: true }),
    })
  }

  desbloquear(elementId: string, userName: string) {
    this.client.publish({
      destination: `/app/diagram/${this.diagramId}/unlock`,
      body: JSON.stringify({ elementId, userName, held: false }),
    })
  }
}
