import { useCallback, useEffect, useState, type FormEvent } from 'react'
import type { Cliente } from '../auth/cliente'
import { permisosDe } from '../auth/permisos'
import type { Sesion } from '../auth/sesion'
import { Confirmar } from './Confirmar'
import { Encabezado, InsigniaDeRol } from './Encabezado'
import type { ProyectoResumen } from './tipos'

interface Props {
  api: Cliente
  sesion: Sesion
  /** Sube cuando algo ajeno pudo cambiar la lista (un aviso de acceso, una reconexion). */
  revision: number
  alAbrir: (proyectoId: string) => void
  alCerrarSesion: () => void
}

/**
 * "Mis proyectos": los propios y aquellos donde soy miembro, con mi rol.
 *
 * Renombrar y eliminar son del propietario (`administrarProyecto`); eliminar
 * pide escribir el nombre, porque se lleva los diagramas y no se deshace. Los
 * botones solo REFLEJAN el permiso: si el servidor dijera que no, el error se
 * muestra igual.
 */
export function MisProyectos({ api, sesion, revision, alAbrir, alCerrarSesion }: Props) {
  const [proyectos, setProyectos] = useState<ProyectoResumen[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [nombreNuevo, setNombreNuevo] = useState('')
  const [creando, setCreando] = useState(false)
  const [renombrando, setRenombrando] = useState<{ id: string; nombre: string } | null>(null)
  const [aEliminar, setAEliminar] = useState<ProyectoResumen | null>(null)

  const cargar = useCallback(async () => {
    try {
      setProyectos(await api.obtener<ProyectoResumen[]>('/projects'))
      setError(null)
    } catch (e) {
      setError(e instanceof Error ? e.message : 'No se pudieron cargar los proyectos.')
    }
  }, [api])

  useEffect(() => { void cargar() }, [cargar, revision])

  const crear = async (e: FormEvent) => {
    e.preventDefault()
    const nombre = nombreNuevo.trim()
    if (!nombre) return
    setCreando(true)
    try {
      const nuevo = await api.enviar<{ id: string }>('POST', '/projects', { name: nombre })
      setNombreNuevo('')
      alAbrir(nuevo.id)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'No se pudo crear el proyecto.')
    } finally {
      setCreando(false)
    }
  }

  const guardarNombre = async (e: FormEvent) => {
    e.preventDefault()
    if (!renombrando || !renombrando.nombre.trim()) return
    try {
      await api.enviar('PATCH', `/projects/${renombrando.id}`, { name: renombrando.nombre.trim() })
      setRenombrando(null)
      await cargar()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'No se pudo renombrar el proyecto.')
    }
  }

  return (
    <div className="pantalla">
      <Encabezado sesion={sesion} migas={[{ texto: 'Mis proyectos' }]} alCerrarSesion={alCerrarSesion} />

      <main className="pantalla-cuerpo">
        <section className="pantalla-seccion">
          <div className="seccion-cabecera">
            <h2>Mis proyectos</h2>
          </div>

          <form className="proyecto-nuevo" onSubmit={crear}>
            <input placeholder="Nombre del proyecto nuevo" value={nombreNuevo} maxLength={160}
                   onChange={(e) => setNombreNuevo(e.target.value)} aria-label="Nombre del proyecto nuevo" />
            <button type="submit" className="tb-btn tb-primario" disabled={creando || !nombreNuevo.trim()}>
              Crear proyecto
            </button>
          </form>

          {error && <div className="modal-error" onClick={() => setError(null)}>{error}</div>}

          {proyectos === null && !error && <p className="tenue">Cargando...</p>}
          {proyectos?.length === 0 && (
            <p className="tenue vacio">
              Todavía no tenés proyectos. Creá el primero: nace con un diagrama listo para modelar.
            </p>
          )}

          <ul className="proyectos">
            {proyectos?.map((p) => {
              const puede = permisosDe(p.myRole)
              return (
                <li key={p.id} className="proyecto-tarjeta">
                  <div className="proyecto-principal">
                    {renombrando?.id === p.id ? (
                      <form className="proyecto-renombrar" onSubmit={guardarNombre}>
                        <input autoFocus value={renombrando.nombre} maxLength={160}
                               onChange={(e) => setRenombrando({ id: p.id, nombre: e.target.value })}
                               onKeyDown={(e) => { if (e.key === 'Escape') setRenombrando(null) }}
                               aria-label="Nuevo nombre" />
                        <button type="submit" className="tb-btn" disabled={!renombrando.nombre.trim()}>Guardar</button>
                        <button type="button" className="tb-btn" onClick={() => setRenombrando(null)}>Cancelar</button>
                      </form>
                    ) : (
                      <button className="proyecto-nombre" onClick={() => alAbrir(p.id)}>{p.name}</button>
                    )}
                    <InsigniaDeRol rol={p.myRole} />
                  </div>
                  <div className="proyecto-meta tenue">
                    {p.myRole === 'OWNER' ? 'Propio' : `Propietario: ${p.owner.fullName}`}
                    {' · '}{p.diagramCount} {p.diagramCount === 1 ? 'diagrama' : 'diagramas'}
                  </div>
                  <div className="proyecto-acciones">
                    <button className="tb-btn" onClick={() => alAbrir(p.id)}>Abrir</button>
                    {puede.administrarProyecto && (
                      <>
                        <button className="tb-btn" onClick={() => setRenombrando({ id: p.id, nombre: p.name })}>
                          Renombrar
                        </button>
                        <button className="tb-btn tb-peligro" onClick={() => setAEliminar(p)}>Eliminar</button>
                      </>
                    )}
                  </div>
                </li>
              )
            })}
          </ul>
        </section>
      </main>

      {aEliminar && (
        <Confirmar
          titulo="Eliminar proyecto"
          peligro
          etiqueta="Eliminar para siempre"
          escribir={aEliminar.name}
          mensaje={<>
            Se va a eliminar <strong>{aEliminar.name}</strong> con sus {aEliminar.diagramCount}{' '}
            {aEliminar.diagramCount === 1 ? 'diagrama' : 'diagramas'} y todas sus clases. Los demás
            miembros pierden el acceso ahora mismo. <strong>No se puede deshacer.</strong>
          </>}
          onCancelar={() => setAEliminar(null)}
          onConfirmar={async () => {
            await api.enviar('DELETE', `/projects/${aEliminar.id}`)
            setAEliminar(null)
            await cargar()
          }}
        />
      )}
    </div>
  )
}
