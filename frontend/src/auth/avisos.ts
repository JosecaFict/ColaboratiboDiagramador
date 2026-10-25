import { ETIQUETA_DE_ROL, type Rol } from './permisos'
import { proyectoDe, type Ruta } from './ruta'

/**
 * El aviso privado que llega por `/user/queue/membresia` cuando cambia el acceso
 * de este usuario a un proyecto (ADR-026). ESPEJO de `AvisoDeAcceso.java`.
 *
 * `role` ausente = ya no tiene acceso (lo quitaron, se fue, o el proyecto dejo
 * de existir).
 */
export type Motivo =
  | 'AGREGADO' | 'ROL_CAMBIADO' | 'PROPIEDAD_RECIBIDA' | 'PROPIEDAD_CEDIDA'
  | 'QUITADO' | 'ABANDONO' | 'PROYECTO_ELIMINADO' | 'DIAGRAMA_ELIMINADO'

export interface AvisoDeAcceso {
  projectId: string
  projectName: string
  reason: Motivo
  role?: Rol
  diagramId?: string
}

/** Lo que la interfaz tiene que hacer con un aviso. */
export interface Efecto {
  /** El rol nuevo en el proyecto que se esta mirando; ausente = no cambia. */
  rol?: Rol
  /** Adonde llevar al usuario, si lo que estaba mirando dejo de estar a su alcance. */
  irA?: Ruta
  /** El texto para mostrar. */
  mensaje?: string
  /** Volver a pedir las listas (proyectos, miembros, diagramas). */
  refrescar: boolean
}

/**
 * Decide, sin tocar la pantalla, que hacer con un aviso segun donde esta el
 * usuario. Es una funcion pura para poder probarla: el recorrido "me quitan y
 * vuelvo a Mis proyectos con un mensaje" no depende de un navegador.
 */
export function interpretarAviso(aviso: AvisoDeAcceso, ruta: Ruta): Efecto {
  const enEsteProyecto = proyectoDe(ruta) === aviso.projectId
  const nombre = `«${aviso.projectName}»`

  switch (aviso.reason) {
    case 'PROYECTO_ELIMINADO':
      return {
        refrescar: true,
        ...(enEsteProyecto && { irA: { tipo: 'proyectos' } as Ruta }),
        mensaje: `El proyecto ${nombre} fue eliminado por su propietario.`,
      }
    case 'QUITADO':
      return {
        refrescar: true,
        ...(enEsteProyecto && { irA: { tipo: 'proyectos' } as Ruta }),
        mensaje: `Te quitaron del proyecto ${nombre}.`,
      }
    case 'ABANDONO':
      return {
        refrescar: true,
        ...(enEsteProyecto && { irA: { tipo: 'proyectos' } as Ruta }),
        mensaje: enEsteProyecto ? `Abandonaste el proyecto ${nombre}.` : undefined,
      }
    case 'DIAGRAMA_ELIMINADO': {
      const eraElAbierto = ruta.tipo === 'diagrama' && ruta.diagramaId === aviso.diagramId
      return {
        refrescar: enEsteProyecto,
        ...(eraElAbierto && { irA: { tipo: 'proyecto', proyectoId: aviso.projectId } as Ruta }),
        mensaje: eraElAbierto ? 'El propietario eliminó el diagrama que estabas mirando.' : undefined,
      }
    }
    case 'AGREGADO':
      return {
        refrescar: true,
        mensaje: `Te agregaron al proyecto ${nombre} como ${aviso.role ? ETIQUETA_DE_ROL[aviso.role] : 'miembro'}.`,
      }
    case 'PROPIEDAD_RECIBIDA':
      return {
        refrescar: true,
        ...(enEsteProyecto && { rol: aviso.role }),
        mensaje: `Ahora sos el propietario de ${nombre}.`,
      }
    case 'PROPIEDAD_CEDIDA':
      return {
        refrescar: true,
        ...(enEsteProyecto && { rol: aviso.role }),
        mensaje: `Transferiste la propiedad de ${nombre}: ahora sos ${aviso.role ? ETIQUETA_DE_ROL[aviso.role] : 'miembro'}.`,
      }
    case 'ROL_CAMBIADO':
      return {
        refrescar: true,
        ...(enEsteProyecto && { rol: aviso.role }),
        mensaje: enEsteProyecto
          ? aviso.role === 'VIEWER'
            ? `Ahora sos Observador de ${nombre}: el diagrama pasó a solo lectura.`
            : `Ahora sos ${aviso.role ? ETIQUETA_DE_ROL[aviso.role] : 'miembro'} de ${nombre}: ya podés editar.`
          : undefined,
      }
  }
}
