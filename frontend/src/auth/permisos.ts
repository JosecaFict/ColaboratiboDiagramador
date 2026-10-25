/**
 * Que puede hacer cada rol dentro de un proyecto (ADR-026).
 *
 * ESPEJO de `AccesoAProyecto.permite` del backend, igual que `applyOperation.ts`
 * lo es de `CommandApplier`. Sirve para una sola cosa: que la interfaz muestre
 * u oculte lo que el servidor de todos modos va a aceptar o rechazar. La
 * autoridad es siempre el servidor: si este archivo se quedara viejo, lo peor
 * que pasa es un boton de mas o de menos, nunca un permiso que no existe.
 *
 * Si la tabla cambia, cambia en los dos lados. `permisos.test.ts` y
 * `AccesoAProyectoTest` fijan la MISMA tabla, celda por celda.
 */
export type Rol = 'OWNER' | 'EDITOR' | 'VIEWER'

export const ETIQUETA_DE_ROL: Record<Rol, string> = {
  OWNER: 'Propietario',
  EDITOR: 'Modelador',
  VIEWER: 'Observador',
}

export interface Permisos {
  /** Ver el proyecto, sus diagramas y miembros; leer diagrama, Logica, Fisica y ambiguedades; bajar el codigo. */
  leer: boolean
  /** Editar el modelo, importar, IA, vision y confirmar el esquema. */
  editarModelo: boolean
  /** Crear y renombrar diagramas. */
  crearDiagramas: boolean
  eliminarDiagramas: boolean
  /** Renombrar o eliminar el proyecto. */
  administrarProyecto: boolean
  /** Agregar miembros, cambiar su rol, quitarlos. */
  administrarMiembros: boolean
  transferirPropiedad: boolean
  abandonar: boolean
}

const SIN_ACCESO: Permisos = {
  leer: false, editarModelo: false, crearDiagramas: false, eliminarDiagramas: false,
  administrarProyecto: false, administrarMiembros: false, transferirPropiedad: false, abandonar: false,
}

/** `null` o `undefined` = todavia no se sabe el rol, o ya no tiene acceso: nada habilitado. */
export function permisosDe(rol: Rol | null | undefined): Permisos {
  switch (rol) {
    case 'OWNER':
      return {
        leer: true, editarModelo: true, crearDiagramas: true, eliminarDiagramas: true,
        administrarProyecto: true, administrarMiembros: true, transferirPropiedad: true,
        abandonar: false,   // el propietario primero tiene que transferir la propiedad
      }
    case 'EDITOR':
      return { ...SIN_ACCESO, leer: true, editarModelo: true, crearDiagramas: true, abandonar: true }
    case 'VIEWER':
      return { ...SIN_ACCESO, leer: true, abandonar: true }
    default:
      return SIN_ACCESO
  }
}

/** Los roles que se pueden asignar a un miembro: el propietario no, se transfiere. */
export const ROLES_ASIGNABLES: Exclude<Rol, 'OWNER'>[] = ['EDITOR', 'VIEWER']

/** El modo Observador: el diagrama se ve, pero no se toca. */
export const esSoloLectura = (rol: Rol | null | undefined): boolean => !permisosDe(rol).editarModelo
