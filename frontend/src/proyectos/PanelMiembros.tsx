import { useCallback, useEffect, useState, type FormEvent } from 'react'
import type { Cliente } from '../auth/cliente'
import { ETIQUETA_DE_ROL, ROLES_ASIGNABLES, permisosDe } from '../auth/permisos'
import type { Sesion } from '../auth/sesion'
import { Confirmar } from './Confirmar'
import type { Miembro, ProyectoDetalle } from './tipos'

interface Props {
  api: Cliente
  proyecto: ProyectoDetalle
  sesion: Sesion
  /** Sube cuando algo ajeno pudo cambiar la lista de miembros. */
  revision: number
  /** El propietario cambio o me fui: hay que volver a pedir el proyecto. */
  alCambiar: () => void
  alAbandonar: () => void
}

type Pendiente =
  | { tipo: 'quitar'; miembro: Miembro }
  | { tipo: 'transferir'; miembro: Miembro }
  | { tipo: 'abandonar' }

/**
 * El panel "Miembros": visible para los tres roles; solo el propietario invita
 * por email, cambia roles, quita y transfiere, y todo lo destructivo pide
 * confirmacion. EDITOR y VIEWER ven "Abandonar proyecto" (ADR-026).
 *
 * Las opciones se muestran segun `permisosDe(rol)`, pero el servidor es quien
 * decide: un cambio de rol ajeno se ve aca sin recargar --`revision` sube con
 * el aviso privado-- y, si el permiso ya no esta, el servidor rechaza igual.
 */
export function PanelMiembros({ api, proyecto, sesion, revision, alCambiar, alAbandonar }: Props) {
  const puede = permisosDe(proyecto.myRole)
  const [miembros, setMiembros] = useState<Miembro[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [email, setEmail] = useState('')
  const [rolNuevo, setRolNuevo] = useState<'EDITOR' | 'VIEWER'>('EDITOR')
  const [invitando, setInvitando] = useState(false)
  const [pendiente, setPendiente] = useState<Pendiente | null>(null)

  const cargar = useCallback(async () => {
    try {
      setMiembros(await api.obtener<Miembro[]>(`/projects/${proyecto.id}/members`))
    } catch (e) {
      setError(e instanceof Error ? e.message : 'No se pudo cargar la lista de miembros.')
    }
  }, [api, proyecto.id])

  useEffect(() => { void cargar() }, [cargar, revision])

  const invitar = async (e: FormEvent) => {
    e.preventDefault()
    if (!email.trim()) return
    setInvitando(true)
    setError(null)
    try {
      await api.enviar('POST', `/projects/${proyecto.id}/members`, { email: email.trim(), role: rolNuevo })
      setEmail('')
      await cargar()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'No se pudo agregar al miembro.')
    } finally {
      setInvitando(false)
    }
  }

  const cambiarRol = async (m: Miembro, role: string) => {
    setError(null)
    try {
      await api.enviar('PATCH', `/projects/${proyecto.id}/members/${m.userId}`, { role })
    } catch (err) {
      setError(err instanceof Error ? err.message : 'No se pudo cambiar el rol.')
    }
    await cargar()
  }

  return (
    <section className="panel-miembros">
      <div className="seccion-cabecera">
        <h2>Miembros</h2>
        <span className="tenue">{miembros?.length ?? '…'}</span>
      </div>

      {error && <div className="modal-error" onClick={() => setError(null)}>{error}</div>}

      <ul className="miembros">
        {miembros?.map((m) => {
          const soyYo = m.userId === sesion.userId
          const esElPropietario = m.role === 'OWNER'
          return (
            <li key={m.userId} className="miembro">
              <div className="miembro-datos">
                <strong>{m.fullName}{soyYo && <span className="tenue"> (vos)</span>}</strong>
                <span className="tenue">{m.email}</span>
              </div>
              {puede.administrarMiembros && !esElPropietario ? (
                <select value={m.role} aria-label={`Rol de ${m.fullName}`}
                        onChange={(e) => void cambiarRol(m, e.target.value)}>
                  {ROLES_ASIGNABLES.map((r) => <option key={r} value={r}>{ETIQUETA_DE_ROL[r]}</option>)}
                </select>
              ) : (
                <span className={'insignia insignia-' + m.role}>{ETIQUETA_DE_ROL[m.role]}</span>
              )}
              {puede.administrarMiembros && !esElPropietario && (
                <span className="miembro-acciones">
                  {puede.transferirPropiedad && (
                    <button className="tb-btn" onClick={() => setPendiente({ tipo: 'transferir', miembro: m })}
                            title="Pasarle la propiedad del proyecto">Transferir</button>
                  )}
                  <button className="tb-btn tb-peligro" onClick={() => setPendiente({ tipo: 'quitar', miembro: m })}>
                    Quitar
                  </button>
                </span>
              )}
            </li>
          )
        })}
      </ul>

      {puede.administrarMiembros && (
        <form className="miembro-invitar" onSubmit={invitar}>
          <h3>Agregar un miembro</h3>
          <p className="tenue">Tiene que estar registrado en ColabModel.</p>
          <div className="miembro-invitar-fila">
            <input type="email" placeholder="email@ejemplo.com" value={email}
                   onChange={(e) => setEmail(e.target.value)} aria-label="Email del nuevo miembro" />
            <select value={rolNuevo} onChange={(e) => setRolNuevo(e.target.value as 'EDITOR' | 'VIEWER')}
                    aria-label="Rol del nuevo miembro">
              {ROLES_ASIGNABLES.map((r) => <option key={r} value={r}>{ETIQUETA_DE_ROL[r]}</option>)}
            </select>
            <button type="submit" className="tb-btn tb-primario" disabled={invitando || !email.trim()}>
              Agregar
            </button>
          </div>
        </form>
      )}

      {puede.abandonar && (
        <div className="miembro-abandonar">
          <button className="tb-btn tb-peligro" onClick={() => setPendiente({ tipo: 'abandonar' })}>
            Abandonar proyecto
          </button>
        </div>
      )}
      {proyecto.myRole === 'OWNER' && (
        <p className="tenue miembro-nota">
          Sos el propietario: para abandonar el proyecto, primero transferí la propiedad a otro miembro.
        </p>
      )}

      {pendiente?.tipo === 'quitar' && (
        <Confirmar
          titulo="Quitar miembro" peligro etiqueta="Quitar del proyecto"
          mensaje={<>
            <strong>{pendiente.miembro.fullName}</strong> deja de tener acceso a <strong>{proyecto.name}</strong> ahora
            mismo: si está mirando un diagrama, se le cierra.
          </>}
          onCancelar={() => setPendiente(null)}
          onConfirmar={async () => {
            await api.enviar('DELETE', `/projects/${proyecto.id}/members/${pendiente.miembro.userId}`)
            setPendiente(null)
            await cargar()
          }}
        />
      )}
      {pendiente?.tipo === 'transferir' && (
        <Confirmar
          titulo="Transferir la propiedad" peligro etiqueta="Transferir"
          mensaje={<>
            <strong>{pendiente.miembro.fullName}</strong> pasa a ser el propietario de <strong>{proyecto.name}</strong>.
            Vos quedás como Modelador y <strong>no vas a poder revertirlo</strong>: solo el nuevo propietario puede
            devolvértela.
          </>}
          onCancelar={() => setPendiente(null)}
          onConfirmar={async () => {
            await api.enviar('POST', `/projects/${proyecto.id}/transfer`, { userId: pendiente.miembro.userId })
            setPendiente(null)
            alCambiar()
            await cargar()
          }}
        />
      )}
      {pendiente?.tipo === 'abandonar' && (
        <Confirmar
          titulo="Abandonar proyecto" peligro etiqueta="Abandonar"
          mensaje={<>
            Vas a perder el acceso a <strong>{proyecto.name}</strong>. Para volver, el propietario tiene que
            agregarte otra vez.
          </>}
          onCancelar={() => setPendiente(null)}
          onConfirmar={async () => {
            await api.enviar('DELETE', `/projects/${proyecto.id}/members/${sesion.userId}`)
            setPendiente(null)
            alAbandonar()
          }}
        />
      )}
    </section>
  )
}
