import { useState, type FormEvent } from 'react'
import type { Cliente } from '../auth/cliente'
import { ETIQUETA_DE_ROL, permisosDe } from '../auth/permisos'
import type { Sesion } from '../auth/sesion'
import { hashDe } from '../auth/ruta'
import { Confirmar } from './Confirmar'
import { Encabezado, InsigniaDeRol } from './Encabezado'
import { PanelMiembros } from './PanelMiembros'
import type { DiagramaResumen, ProyectoDetalle } from './tipos'

interface Props {
  api: Cliente
  sesion: Sesion
  proyecto: ProyectoDetalle
  revision: number
  /** Volver a pedir el proyecto (cambio de diagramas, de propietario o de rol). */
  alRefrescar: () => void
  alAbandonar: () => void
  alCerrarSesion: () => void
}

/**
 * Un proyecto: la lista de sus diagramas y el panel de miembros (ADR-026).
 *
 * Crear y renombrar diagramas es del propietario y del Modelador; eliminarlos,
 * solo del propietario. El Observador ve la lista y puede abrir cada diagrama
 * en modo solo lectura.
 */
export function PantallaProyecto({ api, sesion, proyecto, revision, alRefrescar, alAbandonar, alCerrarSesion }: Props) {
  const puede = permisosDe(proyecto.myRole)
  const [nombreNuevo, setNombreNuevo] = useState('')
  const [creando, setCreando] = useState(false)
  const [renombrando, setRenombrando] = useState<{ id: string; nombre: string } | null>(null)
  const [aEliminar, setAEliminar] = useState<DiagramaResumen | null>(null)
  const [error, setError] = useState<string | null>(null)

  const crear = async (e: FormEvent) => {
    e.preventDefault()
    const nombre = nombreNuevo.trim()
    if (!nombre) return
    setCreando(true)
    setError(null)
    try {
      await api.enviar('POST', `/projects/${proyecto.id}/diagrams`, { name: nombre })
      setNombreNuevo('')
      alRefrescar()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'No se pudo crear el diagrama.')
    } finally {
      setCreando(false)
    }
  }

  const guardarNombre = async (e: FormEvent) => {
    e.preventDefault()
    if (!renombrando || !renombrando.nombre.trim()) return
    try {
      await api.enviar('PATCH', `/diagrams/${renombrando.id}`, { name: renombrando.nombre.trim() })
      setRenombrando(null)
      alRefrescar()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'No se pudo renombrar el diagrama.')
    }
  }

  const enlace = (d: DiagramaResumen) => hashDe({ tipo: 'diagrama', proyectoId: proyecto.id, diagramaId: d.id })

  return (
    <div className="pantalla">
      <Encabezado sesion={sesion} alCerrarSesion={alCerrarSesion}
                  migas={[{ texto: 'Mis proyectos', href: '#/proyectos' }, { texto: proyecto.name }]} />

      <main className="pantalla-cuerpo pantalla-dos-columnas">
        <section className="pantalla-seccion">
          <div className="seccion-cabecera">
            <h2>{proyecto.name}</h2>
            <InsigniaDeRol rol={proyecto.myRole} />
          </div>
          <p className="tenue">
            Propietario: {proyecto.owner.fullName}
            {proyecto.myRole === 'VIEWER' && ' · Como Observador podés ver los diagramas y bajar el código, pero no editar.'}
          </p>

          <div className="seccion-cabecera"><h3>Diagramas</h3></div>

          {puede.crearDiagramas && (
            <form className="proyecto-nuevo" onSubmit={crear}>
              <input placeholder="Nombre del diagrama nuevo" value={nombreNuevo} maxLength={160}
                     onChange={(e) => setNombreNuevo(e.target.value)} aria-label="Nombre del diagrama nuevo" />
              <button type="submit" className="tb-btn tb-primario" disabled={creando || !nombreNuevo.trim()}>
                Crear diagrama
              </button>
            </form>
          )}

          {error && <div className="modal-error" onClick={() => setError(null)}>{error}</div>}

          {proyecto.diagrams.length === 0 && (
            <p className="tenue vacio">
              Este proyecto no tiene diagramas.
              {puede.crearDiagramas ? ' Creá uno para empezar a modelar.' : ' Pedile al propietario que cree uno.'}
            </p>
          )}

          <ul className="proyectos">
            {proyecto.diagrams.map((d) => (
              <li key={d.id} className="proyecto-tarjeta">
                <div className="proyecto-principal">
                  {renombrando?.id === d.id ? (
                    <form className="proyecto-renombrar" onSubmit={guardarNombre}>
                      <input autoFocus value={renombrando.nombre} maxLength={160}
                             onChange={(e) => setRenombrando({ id: d.id, nombre: e.target.value })}
                             onKeyDown={(e) => { if (e.key === 'Escape') setRenombrando(null) }}
                             aria-label="Nuevo nombre" />
                      <button type="submit" className="tb-btn" disabled={!renombrando.nombre.trim()}>Guardar</button>
                      <button type="button" className="tb-btn" onClick={() => setRenombrando(null)}>Cancelar</button>
                    </form>
                  ) : (
                    <a className="proyecto-nombre" href={enlace(d)}>{d.name}</a>
                  )}
                </div>
                <div className="proyecto-meta tenue">
                  {d.currentSeq} {d.currentSeq === 1 ? 'operación' : 'operaciones'}
                </div>
                <div className="proyecto-acciones">
                  <a className="tb-btn" href={enlace(d)}>
                    {puede.editarModelo ? 'Abrir' : 'Ver'}
                  </a>
                  {puede.crearDiagramas && (
                    <button className="tb-btn" onClick={() => setRenombrando({ id: d.id, nombre: d.name })}>
                      Renombrar
                    </button>
                  )}
                  {puede.eliminarDiagramas && (
                    <button className="tb-btn tb-peligro" onClick={() => setAEliminar(d)}>Eliminar</button>
                  )}
                </div>
              </li>
            ))}
          </ul>
          <p className="tenue">
            Tu rol en este proyecto es <strong>{ETIQUETA_DE_ROL[proyecto.myRole]}</strong>.
          </p>
        </section>

        <PanelMiembros api={api} proyecto={proyecto} sesion={sesion} revision={revision}
                       alCambiar={alRefrescar} alAbandonar={alAbandonar} />
      </main>

      {aEliminar && (
        <Confirmar
          titulo="Eliminar diagrama" peligro etiqueta="Eliminar diagrama" escribir={aEliminar.name}
          mensaje={<>
            Se elimina <strong>{aEliminar.name}</strong> con todas sus clases, relaciones y su historial de
            operaciones. Quien lo tenga abierto vuelve a esta pantalla. <strong>No se puede deshacer.</strong>
          </>}
          onCancelar={() => setAEliminar(null)}
          onConfirmar={async () => {
            await api.enviar('DELETE', `/diagrams/${aEliminar.id}`)
            setAEliminar(null)
            alRefrescar()
          }}
        />
      )}
    </div>
  )
}
