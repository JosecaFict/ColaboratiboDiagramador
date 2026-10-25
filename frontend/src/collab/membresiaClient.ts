import { Client } from '@stomp/stompjs'
import SockJS from 'sockjs-client'
import type { AvisoDeAcceso } from '../auth/avisos'

/**
 * El canal PRIVADO de este usuario (ADR-026): recibe un aviso cada vez que su
 * acceso a un proyecto cambia --le cambian el rol, lo quitan, eliminan el
 * proyecto--, para que la interfaz se adapte sin recargar.
 *
 * Es una conexion por pestana y vive mientras haya sesion, en cualquier
 * pantalla: alguien puede agregarte a un proyecto mientras miras la lista, y
 * quitarte de uno mientras miras otro. No es `ColabClient`: ese es del plano de
 * DATOS de UN diagrama (operaciones, locks, presencia) y solo existe con el
 * diagrama abierto.
 *
 * Los avisos que llegan mientras la conexion esta caida no se reenvian, asi
 * que al RECONECTAR se avisa (`onConectado(true)`) para que la pantalla vuelva a
 * pedir lo que muestra en vez de quedarse con un estado viejo.
 */
export class MembresiaClient {
  private client: Client
  private habiaConectado = false

  constructor(
    token: string,
    private handlers: {
      onAviso: (aviso: AvisoDeAcceso) => void
      /** `reconexion` es true si ya habia estado conectado antes. */
      onConectado?: (reconexion: boolean) => void
      /** El servidor cerro la conexion con un error (por ejemplo, token invalido). */
      onRechazo?: (motivo: string) => void
    },
  ) {
    this.client = new Client({
      webSocketFactory: () => new SockJS('/ws') as unknown as WebSocket,
      connectHeaders: { Authorization: 'Bearer ' + token },
      reconnectDelay: 3000,
      onConnect: () => {
        // Cola de usuario: Spring la resuelve por usuario, cada quien recibe solo lo suyo.
        this.client.subscribe('/user/queue/membresia', (m) =>
          this.handlers.onAviso(JSON.parse(m.body) as AvisoDeAcceso))
        this.handlers.onConectado?.(this.habiaConectado)
        this.habiaConectado = true
      },
      onStompError: (frame) => this.handlers.onRechazo?.(String(frame.headers['message'] ?? '')),
    })
  }

  activar() { this.client.activate() }
  desactivar() { void this.client.deactivate() }
}
