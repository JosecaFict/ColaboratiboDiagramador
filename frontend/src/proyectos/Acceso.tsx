import { useEffect, useRef, useState, type FormEvent, type KeyboardEvent, type ReactNode } from 'react'
import { leerMensaje } from '../auth/cliente'
import type { Sesion } from '../auth/sesion'
import {
  LARGO_MINIMO_DE_CONTRASENA, cumpleLargoMinimo, primerCampoConError, validarAcceso,
  type DatosDeAcceso, type ErroresDeAcceso, type ModoDeAcceso,
} from '../auth/validacion'
import { Ilustracion, Logo } from './MarcaDeAcceso'

interface Props {
  /** Por ejemplo "Tu sesión venció": se muestra arriba del formulario. */
  aviso: string | null
  onSesion: (sesion: Sesion) => void
}

const ICONO = { width: 16, height: 16, viewBox: '0 0 24 24', fill: 'none', stroke: 'currentColor',
                strokeWidth: 1.8, strokeLinecap: 'round', strokeLinejoin: 'round', 'aria-hidden': true } as const

const IconoUsuario = () => <svg {...ICONO}><circle cx="12" cy="8" r="4" /><path d="M4 21a8 8 0 0 1 16 0" /></svg>
const IconoCorreo = () => <svg {...ICONO}><rect x="3" y="5" width="18" height="14" rx="2" /><path d="m3 7 9 6 9-6" /></svg>
const IconoCandado = () => <svg {...ICONO}><rect x="5" y="11" width="14" height="9" rx="2" /><path d="M8 11V8a4 4 0 0 1 8 0v3" /></svg>
const IconoAlerta = () => <svg {...ICONO}><circle cx="12" cy="12" r="9" /><path d="M12 7.5v5M12 16.2v.1" /></svg>

/** Una fila del formulario: etiqueta, icono, el campo, y su error debajo. */
function Campo({ id, etiqueta, icono, error, children, extra }: {
  id: string; etiqueta: string; icono: ReactNode; error?: string; children: ReactNode; extra?: ReactNode
}) {
  return (
    <div className={'acceso-campo' + (error ? ' invalido' : '')}>
      <label htmlFor={id}>{etiqueta}</label>
      <div className="acceso-entrada">
        <span className="acceso-icono">{icono}</span>
        {children}
      </div>
      {error && <p className="acceso-campo-error" id={id + '-error'}>{error}</p>}
      {extra}
    </div>
  )
}

/**
 * Iniciar sesion y Registro contra `/auth/login` y `/auth/register` (ADR-026).
 *
 * Ninguno de los dos lleva token, asi que un 401 aca --"El email o la contrasena no
 * son correctos"-- es un error del formulario y NO una sesion muerta: el vigilante de
 * `fetch` solo mira las llamadas que llevaban `Authorization`.
 *
 * La validacion se hace aca, con mensajes propios y el foco en el campo exacto, en vez
 * de los globitos del navegador (`noValidate`); el servidor valida lo mismo y sigue
 * siendo quien decide. Un error del servidor que es de UN campo --el email ya esta
 * registrado-- se muestra en ese campo y no en un cartel aparte.
 */
export function Acceso({ aviso, onSesion }: Props) {
  const [modo, setModo] = useState<ModoDeAcceso>('entrar')
  const [datos, setDatos] = useState<DatosDeAcceso>({ nombre: '', email: '', password: '' })
  const [errores, setErrores] = useState<ErroresDeAcceso>({})
  const [error, setError] = useState<string | null>(null)
  const [enviando, setEnviando] = useState(false)
  const [verPassword, setVerPassword] = useState(false)
  const [mayusculas, setMayusculas] = useState(false)

  const registrando = modo === 'registrar'
  const campos = {
    nombre: useRef<HTMLInputElement>(null),
    email: useRef<HTMLInputElement>(null),
    password: useRef<HTMLInputElement>(null),
  }
  const tabs = { entrar: useRef<HTMLButtonElement>(null), registrar: useRef<HTMLButtonElement>(null) }

  // Al abrir y al cambiar de pestana con el mouse, el foco va al primer campo que se ve.
  // Con las flechas NO: quien recorre las pestanas con el teclado tiene que quedarse en
  // ellas, y mandarlo a un campo le impediria llegar a la otra.
  const enfocarAlCambiar = useRef(true)
  useEffect(() => {
    if (enfocarAlCambiar.current) (registrando ? campos.nombre : campos.email).current?.focus()
    enfocarAlCambiar.current = true
  }, [registrando])

  const cambiar = (campo: keyof DatosDeAcceso, valor: string) => {
    setDatos((d) => ({ ...d, [campo]: valor }))
    // Se deja de senalar un campo apenas se lo toca: el error ya cumplio.
    if (errores[campo]) setErrores((e) => ({ ...e, [campo]: undefined }))
    setError(null)
  }

  const cambiarModo = (m: ModoDeAcceso) => {
    setModo(m)
    setErrores({})
    setError(null)
    setVerPassword(false)
  }

  /** Flechas izquierda y derecha entre las dos pestanas, como en cualquier tablist. */
  const alTeclearPestana = (e: KeyboardEvent) => {
    if (e.key !== 'ArrowLeft' && e.key !== 'ArrowRight') return
    e.preventDefault()
    const otro: ModoDeAcceso = registrando ? 'entrar' : 'registrar'
    enfocarAlCambiar.current = false
    cambiarModo(otro)
    tabs[otro].current?.focus()
  }

  const alTeclearPassword = (e: KeyboardEvent<HTMLInputElement>) => {
    setMayusculas(e.getModifierState('CapsLock'))
  }

  const enviar = async (e: FormEvent) => {
    e.preventDefault()
    if (enviando) return

    const faltas = validarAcceso(modo, datos)
    setErrores(faltas)
    setError(null)
    const primero = primerCampoConError(modo, faltas)
    if (primero) {
      campos[primero].current?.focus()
      return
    }

    setEnviando(true)
    try {
      const r = await fetch(registrando ? '/api/v1/auth/register' : '/api/v1/auth/login', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
        body: JSON.stringify(registrando
          ? { email: datos.email.trim(), password: datos.password, fullName: datos.nombre.trim() }
          : { email: datos.email.trim(), password: datos.password }),
      })
      if (!r.ok) {
        const mensaje = await leerMensaje(r)
        if (r.status === 409) {
          // "Ese email ya está registrado": es del campo email, no del formulario.
          setErrores({ email: mensaje })
          campos.email.current?.focus()
        } else {
          setError(mensaje)
          // Credenciales malas: la contrasena es lo que hay que reescribir.
          if (r.status === 401) campos.password.current?.select()
        }
        return
      }
      const d = await r.json()
      onSesion({ token: d.token, userId: d.userId, fullName: d.fullName })
    } catch {
      setError('No se pudo contactar el servidor. Revisá que el backend esté arriba.')
    } finally {
      setEnviando(false)
    }
  }

  const descripcion = (campo: keyof DatosDeAcceso, ayuda?: string) =>
    [errores[campo] ? `acceso-${campo}-error` : null, ayuda ?? null].filter(Boolean).join(' ') || undefined

  return (
    <div className="acceso-pantalla">
      <section className="acceso-marca">
        <div className="acceso-logo">
          <Logo />
          <strong>ColabModel</strong>
        </div>
        <p className="acceso-lema">Modelá datos en equipo, y llevalos a un backend.</p>
        <Ilustracion />
        <ul className="acceso-puntos">
          <li>Editá el mismo diagrama UML con tu equipo: cada cambio aparece al instante.</li>
          <li>Cada proyecto tiene sus roles: Propietario, Modelador y Observador.</li>
          <li>Generá el proyecto Spring Boot desde el mismo modelo.</li>
        </ul>
      </section>

      <main className="acceso-lado">
        <div className="acceso-caja">
          <div className="acceso-logo acceso-logo-chico">
            <Logo tamano={26} />
            <strong>ColabModel</strong>
          </div>

          <div className="acceso-modos" role="tablist" aria-label="Acceso" onKeyDown={alTeclearPestana}>
            <button type="button" role="tab" id="acceso-tab-entrar" ref={tabs.entrar}
                    aria-selected={!registrando} aria-controls="acceso-formulario"
                    tabIndex={!registrando ? 0 : -1}
                    className={!registrando ? 'activa' : ''} onClick={() => cambiarModo('entrar')}>
              Iniciar sesión
            </button>
            <button type="button" role="tab" id="acceso-tab-registrar" ref={tabs.registrar}
                    aria-selected={registrando} aria-controls="acceso-formulario"
                    tabIndex={registrando ? 0 : -1}
                    className={registrando ? 'activa' : ''} onClick={() => cambiarModo('registrar')}>
              Crear cuenta
            </button>
          </div>

          <form className="acceso" id="acceso-formulario" role="tabpanel" noValidate onSubmit={enviar}
                aria-labelledby={registrando ? 'acceso-tab-registrar' : 'acceso-tab-entrar'}>
            <h1>{registrando ? 'Creá tu cuenta' : 'Te damos la bienvenida'}</h1>
            <p className="acceso-sub">
              {registrando
                ? 'Un minuto y ya podés empezar a modelar con tu equipo.'
                : 'Entrá con tu cuenta para abrir tus proyectos.'}
            </p>

            {aviso && (
              <div className="acceso-aviso" role="status">
                <IconoAlerta />
                <span>{aviso}</span>
              </div>
            )}

            {registrando && (
              <Campo id="acceso-nombre" etiqueta="Nombre completo" icono={<IconoUsuario />} error={errores.nombre}>
                <input id="acceso-nombre" ref={campos.nombre} value={datos.nombre} maxLength={160}
                       placeholder="Ana Pérez" autoComplete="name"
                       aria-invalid={!!errores.nombre} aria-describedby={descripcion('nombre')}
                       onChange={(e) => cambiar('nombre', e.target.value)} />
              </Campo>
            )}

            <Campo id="acceso-email" etiqueta="Email" icono={<IconoCorreo />} error={errores.email}>
              <input id="acceso-email" ref={campos.email} type="email" value={datos.email}
                     placeholder="nombre@correo.com" autoComplete="email" inputMode="email"
                     autoCapitalize="none" spellCheck={false}
                     aria-invalid={!!errores.email} aria-describedby={descripcion('email')}
                     onChange={(e) => cambiar('email', e.target.value)} />
            </Campo>

            <Campo id="acceso-password" etiqueta="Contraseña" icono={<IconoCandado />} error={errores.password}
                   extra={<>
                     {registrando && (
                       <p id="acceso-password-ayuda"
                          className={'acceso-requisito' + (cumpleLargoMinimo(datos.password) ? ' cumple' : '')}>
                         <span aria-hidden="true">{cumpleLargoMinimo(datos.password) ? '✓' : '○'}</span>{' '}
                         {LARGO_MINIMO_DE_CONTRASENA} o más caracteres
                       </p>
                     )}
                     {mayusculas && <p className="acceso-mayusculas" role="status">Bloq Mayús está activado.</p>}
                   </>}>
              <input id="acceso-password" ref={campos.password} type={verPassword ? 'text' : 'password'}
                     value={datos.password} autoComplete={registrando ? 'new-password' : 'current-password'}
                     aria-invalid={!!errores.password}
                     aria-describedby={descripcion('password', registrando ? 'acceso-password-ayuda' : undefined)}
                     onChange={(e) => cambiar('password', e.target.value)}
                     onKeyDown={alTeclearPassword} onKeyUp={alTeclearPassword}
                     onBlur={() => setMayusculas(false)} />
              <button type="button" className="acceso-ver" aria-pressed={verPassword}
                      aria-controls="acceso-password" onClick={() => setVerPassword((v) => !v)}>
                {verPassword ? 'Ocultar' : 'Mostrar'}
              </button>
            </Campo>

            {error && (
              <div className="acceso-error" role="alert">
                <IconoAlerta />
                <span>{error}</span>
              </div>
            )}

            <button type="submit" className="acceso-enviar" disabled={enviando}>
              {enviando && <span className="acceso-spinner" aria-hidden="true" />}
              {enviando
                ? (registrando ? 'Creando tu cuenta...' : 'Entrando...')
                : (registrando ? 'Crear cuenta' : 'Entrar')}
            </button>

            <p className="acceso-cambio">
              {registrando ? '¿Ya tenés una cuenta?' : '¿Todavía no tenés cuenta?'}{' '}
              <button type="button" className="acceso-enlace" onClick={() => cambiarModo(registrando ? 'entrar' : 'registrar')}>
                {registrando ? 'Iniciá sesión' : 'Creá una'}
              </button>
            </p>
          </form>
        </div>
      </main>
    </div>
  )
}
