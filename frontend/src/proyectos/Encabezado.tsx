import type { Sesion } from '../auth/sesion'
import { ETIQUETA_DE_ROL, type Rol } from '../auth/permisos'

/** La insignia del rol del usuario en un proyecto. */
export function InsigniaDeRol({ rol }: { rol: Rol }) {
  return <span className={'insignia insignia-' + rol} title={'Tu rol: ' + ETIQUETA_DE_ROL[rol]}>{ETIQUETA_DE_ROL[rol]}</span>
}

interface Props {
  sesion: Sesion
  /** El camino hasta donde esta el usuario; el ultimo es la pantalla actual. */
  migas: { texto: string; href?: string }[]
  alCerrarSesion: () => void
}

/** La barra de las pantallas de proyectos: donde estoy, quien soy, y como salir. */
export function Encabezado({ sesion, migas, alCerrarSesion }: Props) {
  return (
    <header className="barra">
      <strong>ColabModel</strong>
      <nav className="migas" aria-label="Ubicación">
        {migas.map((m, i) => (
          <span key={i}>
            {i > 0 && <span className="migas-sep">›</span>}
            {m.href ? <a href={m.href}>{m.texto}</a> : <span className="migas-actual">{m.texto}</span>}
          </span>
        ))}
      </nav>
      <span className="usuario">{sesion.fullName}</span>
      <button onClick={alCerrarSesion}>Cerrar sesión</button>
    </header>
  )
}
