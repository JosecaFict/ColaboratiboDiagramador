/**
 * La sesion del usuario, guardada en `sessionStorage` y NO en `localStorage`
 * (ADR-026).
 *
 * `sessionStorage` es de cada pestana: dos pestanas pueden tener dos usuarios
 * distintos a la vez, que es lo que hace falta para probar la colaboracion
 * (una persona editando y otra mirando) sin abrir dos navegadores. Con
 * `localStorage` la segunda pestana heredaria la sesion de la primera. Y
 * recargar la pestana (F5) la conserva, asi que no saca a nadie del diagrama.
 *
 * El almacen se recibe como parametro para poder probarlo sin navegador.
 */
export interface Sesion {
  token: string
  userId: string
  fullName: string
}

/** Lo minimo que se usa de `Storage`. */
export interface Almacen {
  getItem(clave: string): string | null
  setItem(clave: string, valor: string): void
  removeItem(clave: string): void
}

export const CLAVE_DE_SESION = 'colabmodel.sesion'

/**
 * El `sessionStorage` del navegador, o null si no se puede usar (modo privado
 * estricto, datos bloqueados): en ese caso la sesion vive solo en memoria y se
 * pierde al recargar, pero la aplicacion sigue andando.
 */
export function almacenDeSesion(): Almacen | null {
  try {
    return typeof window === 'undefined' ? null : window.sessionStorage
  } catch {
    return null
  }
}

export function leerSesion(almacen: Almacen | null): Sesion | null {
  if (!almacen) return null
  try {
    const crudo = almacen.getItem(CLAVE_DE_SESION)
    if (!crudo) return null
    const s = JSON.parse(crudo) as Partial<Sesion>
    return typeof s.token === 'string' && typeof s.userId === 'string' && typeof s.fullName === 'string'
      ? { token: s.token, userId: s.userId, fullName: s.fullName }
      : null
  } catch {
    return null   // JSON roto: como si no hubiera sesion
  }
}

export function guardarSesion(almacen: Almacen | null, sesion: Sesion): void {
  try {
    almacen?.setItem(CLAVE_DE_SESION, JSON.stringify(sesion))
  } catch {
    /* sin almacen: la sesion queda en memoria */
  }
}

export function borrarSesion(almacen: Almacen | null): void {
  try {
    almacen?.removeItem(CLAVE_DE_SESION)
  } catch {
    /* nada que borrar */
  }
}
