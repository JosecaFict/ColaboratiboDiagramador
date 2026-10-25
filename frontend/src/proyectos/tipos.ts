import type { Rol } from '../auth/permisos'

/**
 * Lo que devuelve la API de proyectos (ADR-026). ESPEJO de los records de
 * `GestionDeProyectos` y `GestionDeMiembros`. Los nombres de los campos son los
 * del JSON; los textos que ve el usuario los pone la interfaz.
 */
export interface Persona {
  userId: string
  email: string
  fullName: string
}

export interface DiagramaResumen {
  id: string
  name: string
  currentSeq: number
  createdAt: string
  updatedAt: string
}

/** Un proyecto en "Mis proyectos": con MI rol, quien es el propietario y cuantos diagramas tiene. */
export interface ProyectoResumen {
  id: string
  name: string
  myRole: Rol
  owner: Persona
  diagramCount: number
  createdAt: string
}

export interface ProyectoDetalle {
  id: string
  name: string
  myRole: Rol
  owner: Persona
  diagrams: DiagramaResumen[]
  createdAt: string
}

/** El propietario tambien aparece en la lista de miembros, con rol OWNER. */
export interface Miembro {
  userId: string
  email: string
  fullName: string
  role: Rol
  addedAt: string
}
