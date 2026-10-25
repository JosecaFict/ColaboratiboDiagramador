import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react'
import App from './App'
import { interpretarAviso, type AvisoDeAcceso } from './auth/avisos'
import { ErrorDeApi, crearCliente, envolverFetch } from './auth/cliente'
import { hashDe, parsearHash, proyectoDe, type Ruta } from './auth/ruta'
import {
  almacenDeSesion, borrarSesion, guardarSesion, leerSesion, type Sesion,
} from './auth/sesion'
import { MembresiaClient } from './collab/membresiaClient'
import { Acceso } from './proyectos/Acceso'
import { MisProyectos } from './proyectos/MisProyectos'
import { PantallaProyecto } from './proyectos/PantallaProyecto'
import type { ProyectoDetalle } from './proyectos/tipos'

const MENSAJE_SESION_VENCIDA = 'Tu sesión venció. Iniciá sesión de nuevo.'
const SEGUNDOS_DEL_AVISO = 9

/**
 * La raiz de la aplicacion (ADR-026): quien esta conectado, en que pantalla esta
 * y que hacer cuando su acceso cambia.
 *
 *  - La SESION vive en `sessionStorage`: cada pestana es un usuario, y F5 no la pierde.
 *  - La RUTA vive en el hash (`#/p/{proyecto}/d/{diagrama}`): recargar no saca a
 *    nadie del diagrama, y un enlace pegado lleva al mismo lugar.
 *  - Un 401 en CUALQUIER llamada con sesion --de esta pantalla o de las del editor--
 *    cierra la sesion y vuelve al login, sin perder la ruta: al volver a entrar, se
 *    retoma donde se estaba.
 *  - Los AVISOS privados del servidor adaptan la pantalla en caliente: el rol nuevo,
 *    o "te quitaron" y a Mis proyectos con un mensaje.
 *
 * `App` es el editor de UN diagrama y recibe todo de aca: ya no pide un usuario de
 * prueba a `/dev/bootstrap`.
 */
export default function Raiz() {
  const almacen = useMemo(() => almacenDeSesion(), [])
  const [sesion, setSesion] = useState<Sesion | null>(() => leerSesion(almacen))
  const [ruta, setRuta] = useState<Ruta>(() => parsearHash(window.location.hash))
  const [detalle, setDetalle] = useState<ProyectoDetalle | null>(null)
  const [mensaje, setMensaje] = useState<string | null>(null)
  const [avisoDeAcceso, setAvisoDeAcceso] = useState<string | null>(null)
  /** Sube cuando algo ajeno pudo cambiar lo que se muestra: un aviso, una reconexion. */
  const [revision, setRevision] = useState(0)

  // Los callbacks del WebSocket se crean una sola vez: leen esto de refs.
  const sesionRef = useRef(sesion)
  sesionRef.current = sesion
  const rutaRef = useRef(ruta)
  rutaRef.current = ruta

  /** Todas las llamadas de las pantallas nuevas: firman con el token de ESTA pestana. */
  const api = useMemo(() => crearCliente(() => sesionRef.current?.token ?? null), [])

  const navegar = useCallback((destino: Ruta) => {
    const hash = hashDe(destino)
    if (window.location.hash === hash) setRuta(destino)   // hashchange no se dispara si no cambia
    else window.location.hash = hash
  }, [])

  const cerrarSesion = useCallback((motivo: string | null = null) => {
    borrarSesion(almacen)
    setSesion(null)
    setDetalle(null)
    setMensaje(motivo)
    setAvisoDeAcceso(null)
  }, [almacen])

  const iniciarSesion = (s: Sesion) => {
    guardarSesion(almacen, s)
    setSesion(s)
    setMensaje(null)
  }

  // Hash -> ruta (boton "atras", enlaces, F5).
  useEffect(() => {
    const alCambiar = () => setRuta(parsearHash(window.location.hash))
    window.addEventListener('hashchange', alCambiar)
    return () => window.removeEventListener('hashchange', alCambiar)
  }, [])

  // El vigilante del 401. Se pone UNA vez y cubre todo `fetch`, tambien el del editor.
  // Es un efecto de LAYOUT a proposito: React corre los efectos de los hijos antes que
  // los del padre, asi que con `useEffect` la primera lista de proyectos --que pide un
  // hijo apenas se monta-- saldria sin vigilar. Los de layout corren antes que
  // cualquier efecto normal, de todo el arbol.
  useLayoutEffect(() => {
    const original = window.fetch
    window.fetch = envolverFetch(original.bind(window), () => {
      if (sesionRef.current) cerrarSesion(MENSAJE_SESION_VENCIDA)
    })
    return () => { window.fetch = original }
  }, [cerrarSesion])

  // Un aviso desaparece solo: no tiene que quedar tapando la pantalla.
  useEffect(() => {
    if (!avisoDeAcceso) return
    const t = setTimeout(() => setAvisoDeAcceso(null), SEGUNDOS_DEL_AVISO * 1000)
    return () => clearTimeout(t)
  }, [avisoDeAcceso])

  /** Un aviso privado del servidor: el acceso de este usuario a un proyecto cambio. */
  const alAviso = useCallback((aviso: AvisoDeAcceso) => {
    const efecto = interpretarAviso(aviso, rutaRef.current)
    if (efecto.mensaje) setAvisoDeAcceso(efecto.mensaje)
    if (efecto.rol) {
      // En el acto, sin esperar a volver a pedir el proyecto: el editor pasa a solo
      // lectura (o vuelve a poder editar) sin recargar.
      setDetalle((d) => (d && d.id === aviso.projectId ? { ...d, myRole: efecto.rol! } : d))
    }
    if (efecto.irA) navegar(efecto.irA)
    if (efecto.refrescar) setRevision((n) => n + 1)
  }, [navegar])

  // El canal privado de avisos: una conexion por pestana, mientras haya sesion.
  useEffect(() => {
    if (!sesion) return
    const canal = new MembresiaClient(sesion.token, {
      onAviso: alAviso,
      // Lo que llego durante un corte no se reenvia: al reconectar se vuelve a mirar.
      onConectado: (reconexion) => { if (reconexion) setRevision((n) => n + 1) },
      // Un CONNECT rechazado suele ser un token muerto: una llamada cualquiera lo confirma
      // (y el vigilante del 401 cierra la sesion). Si no lo era, sigue reintentando.
      onRechazo: () => { void api.obtener('/me').catch(() => undefined) },
    })
    canal.activar()
    return () => canal.desactivar()
  }, [sesion?.token, alAviso, api])

  // El proyecto que se esta mirando: su rol, su nombre, sus diagramas.
  const proyectoId = proyectoDe(ruta)
  useEffect(() => {
    if (!sesion || !proyectoId) { setDetalle(null); return }
    let vivo = true
    api.obtener<ProyectoDetalle>(`/projects/${proyectoId}`)
      .then((d) => { if (vivo) setDetalle(d) })
      .catch((e) => {
        if (!vivo) return
        if (e instanceof ErrorDeApi && (e.estado === 403 || e.estado === 404)) {
          // Sin acceso, o ya no existe: no se distingue, para no revelar que ids existen.
          setAvisoDeAcceso('No tenés acceso a ese proyecto, o ya no existe.')
          setDetalle(null)
          navegar({ tipo: 'proyectos' })
        } else if (!(e instanceof ErrorDeApi && e.estado === 401)) {
          setAvisoDeAcceso(e instanceof Error ? e.message : 'No se pudo abrir el proyecto.')
        }
      })
    return () => { vivo = false }
  }, [sesion?.token, proyectoId, revision, api, navegar])

  // Un enlace a un diagrama que ya no esta (lo eliminaron): se vuelve al proyecto, con un aviso.
  const diagramaFaltante = ruta.tipo === 'diagrama' && !!detalle && detalle.id === proyectoId
    && !detalle.diagrams.some((d) => d.id === ruta.diagramaId)
  useEffect(() => {
    if (!diagramaFaltante || !proyectoId) return
    setAvisoDeAcceso('Ese diagrama ya no existe.')
    navegar({ tipo: 'proyecto', proyectoId })
  }, [diagramaFaltante, proyectoId, navegar])

  const refrescar = () => setRevision((n) => n + 1)
  const abrirProyecto = (id: string) => navegar({ tipo: 'proyecto', proyectoId: id })
  const alCerrarSesion = () => cerrarSesion(null)

  const abandonar = () => {
    setAvisoDeAcceso(`Abandonaste el proyecto «${detalle?.name ?? ''}».`)
    navegar({ tipo: 'proyectos' })
    refrescar()
  }

  const aviso = avisoDeAcceso && (
    <div className="toast-aviso" role="status" onClick={() => setAvisoDeAcceso(null)}>
      {avisoDeAcceso}
      <button aria-label="Cerrar aviso">×</button>
    </div>
  )

  if (!sesion) return <Acceso aviso={mensaje} onSesion={iniciarSesion} />

  // Lo que se mira todavia no llego, o es de otro proyecto que se acaba de dejar.
  const cargando = !detalle || detalle.id !== proyectoId

  let pantalla
  if (ruta.tipo === 'proyectos') {
    pantalla = (
      <MisProyectos api={api} sesion={sesion} revision={revision}
                    alAbrir={abrirProyecto} alCerrarSesion={alCerrarSesion} />
    )
  } else if (cargando) {
    pantalla = <div className="pantalla-cargando tenue">Abriendo el proyecto...</div>
  } else if (ruta.tipo === 'proyecto') {
    pantalla = (
      <PantallaProyecto api={api} sesion={sesion} proyecto={detalle} revision={revision}
                        alRefrescar={refrescar} alAbandonar={abandonar} alCerrarSesion={alCerrarSesion} />
    )
  } else {
    const diagrama = detalle.diagrams.find((d) => d.id === ruta.diagramaId)
    if (!diagrama) {
      pantalla = <div className="pantalla-cargando tenue">Abriendo el diagrama...</div>
    } else {
      pantalla = (
        <App key={diagrama.id}
             token={sesion.token} usuario={sesion}
             proyecto={{ id: detalle.id, nombre: detalle.name }}
             diagrama={{ id: diagrama.id, nombre: diagrama.name }}
             rol={detalle.myRole}
             alVolver={() => navegar({ tipo: 'proyecto', proyectoId: detalle.id })}
             alCerrarSesion={alCerrarSesion}
             alRevalidarAcceso={refrescar} />
      )
    }
  }

  return <>{pantalla}{aviso}</>
}
