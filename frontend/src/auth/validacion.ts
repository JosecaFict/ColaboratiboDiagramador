/**
 * Validacion del formulario de acceso, en el cliente (ADR-026).
 *
 * Solo ADELANTA lo que el servidor va a decir de todos modos --`RegistroRequest` y
 * `LoginRequest` validan lo mismo, con estos mismos textos--: sirve para senalar el
 * campo exacto sin esperar un viaje de red, y para no depender de los globitos de
 * validacion del navegador, que salen en el idioma del sistema y no dicen cual campo.
 * El servidor sigue siendo quien decide.
 *
 * Al INICIAR SESION no se exige el largo minimo: solo existe al crear la cuenta, y una
 * cuenta anterior a esa regla tiene que poder entrar.
 */
export type ModoDeAcceso = 'entrar' | 'registrar'

export interface DatosDeAcceso {
  nombre: string
  email: string
  password: string
}

export type ErroresDeAcceso = Partial<Record<keyof DatosDeAcceso, string>>

/** Igual que `@Size(min = 8)` de `AuthController.RegistroRequest`. */
export const LARGO_MINIMO_DE_CONTRASENA = 8

export const cumpleLargoMinimo = (password: string): boolean =>
  password.length >= LARGO_MINIMO_DE_CONTRASENA

/**
 * Algo@dominio.tld. Un poco mas estricto que el servidor (que acepta "a@b"): un
 * "ana@gmail" casi siempre es un dedo de menos, y avisarlo aca ahorra una cuenta
 * que nunca va a recibir nada.
 */
export const emailValido = (email: string): boolean => /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email.trim())

export function validarAcceso(modo: ModoDeAcceso, datos: DatosDeAcceso): ErroresDeAcceso {
  const errores: ErroresDeAcceso = {}

  if (modo === 'registrar' && !datos.nombre.trim()) errores.nombre = 'El nombre es obligatorio'

  if (!datos.email.trim()) errores.email = 'El email es obligatorio'
  else if (!emailValido(datos.email)) errores.email = 'El email no es válido'

  if (!datos.password) errores.password = 'La contraseña es obligatoria'
  else if (modo === 'registrar' && !cumpleLargoMinimo(datos.password)) {
    errores.password = `La contraseña debe tener al menos ${LARGO_MINIMO_DE_CONTRASENA} caracteres`
  }

  return errores
}

/** El campo al que llevar el foco: el primero con error, en el orden en que se ven. */
export function primerCampoConError(modo: ModoDeAcceso, errores: ErroresDeAcceso): keyof DatosDeAcceso | null {
  const orden: (keyof DatosDeAcceso)[] = modo === 'registrar' ? ['nombre', 'email', 'password'] : ['email', 'password']
  return orden.find((campo) => errores[campo]) ?? null
}
