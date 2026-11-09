import { useEffect, useMemo, useRef, useState } from 'react'
import { v4 as uuid } from 'uuid'
import type { EstadoModelo } from '../canvas/applyOperation'
import type { CambioDelLote } from '../importacion/propuesta'
import type { ResultadoDeLote } from './esperaDeLotes'
import { aplicarPropuesta, pedirMarcaDeAplicada } from './aplicacion'
import { arreglosDirectos, pendienteVigente } from './arreglosDirectos'
import { inicialDe, mensajeDeFalloDePropuesta, mensajesDeChat, textoTrasFallo, type MensajeDeChat, type PedidoEnCurso } from './chat'
import { fusionarEntrada, origenDelLote, reconciliarHistorial, type AvisoIA } from './historial'
import { revisarParaGenerar, type AmbiguedadPendiente, type ResumenCodegen } from './listoParaGenerar'
import { elegidas, ordenarRecomendaciones, pendientesComoRecomendaciones, seleccionInicial, textoDeRecomendaciones } from './recomendaciones'
import type { EntradaHistorialIA, EstadoIA, ModoIA, PropuestaIA, RecomendacionesIA } from './tipos'
import { AVISO_SIN_VOZ, idiomaDeDictado, mensajeDeErrorDeVoz, reconocedorDisponible, textoReconocido, type ReconocedorDeVoz } from './voz'

/**
 * Estado y acciones del asistente, compartidos por la vista completa de IA y el chat flotante.
 * Vive en App: la conversacion no se pierde al cambiar de vista ni al cerrar el chat.
 *
 * La conversacion es la del equipo (Diego, T-09/T-10/T-14): el historial del servidor, con
 * autor por userId, sincronizado en vivo con las demas pestañas y aplicado en dos pasos (lote +
 * marca con opId). A eso se suman mensajes que solo existen en esta pestaña: las recomendaciones,
 * los pendientes de Diseñar, la revision para generar y las propuestas de arreglos directos.
 */

export interface OpcionesDeLoteIA {
  origin: 'TEXT' | 'VOICE'
  /** El turno del historial que aplica el lote: viaja en el payload como `interaccionIA`. */
  interaccionId?: string
}

/** Un mensaje de la conversacion: del historial compartido o local de esta pestaña. */
export type MensajeAsistente = MensajeDeChat & { recomendaciones?: RecomendacionesIA; local?: boolean }

interface MensajeLocal { mensaje: MensajeAsistente; creadoEn: number }

export const MODOS: { id: ModoIA; nombre: string; descripcion: string }[] = [
  { id: 'CONSULTAR', nombre: 'Preguntar', descripcion: 'Explica el modelo sin cambiarlo' },
  { id: 'REVISAR', nombre: 'Revisar', descripcion: 'Detecta riesgos y decisiones débiles' },
  { id: 'DISENAR', nombre: 'Diseñar', descripcion: 'Propone una estructura UML nueva' },
  { id: 'MODIFICAR', nombre: 'Modificar', descripcion: 'Convierte el pedido en cambios precisos' },
  { id: 'RECOMENDAR', nombre: 'Recomendar', descripcion: 'Revisa el diagrama y propone mejoras aplicables' },
]

/** En Recomendar, el texto es el requisito a verificar; este pedido revisa solo la estructura. */
export const REVISION_SIN_REQUISITO = 'Revisa el diagrama actual'

const PREFIJO_SIN_RESOLVER = 'Sin resolver por la IA, revisalo antes de aplicar: '

export function textoDeRespuesta(propuesta: PropuestaIA, modo: ModoIA) {
  const fallback = propuesta.aceptados.length
    ? `Preparé ${propuesta.aceptados.length} cambio${propuesta.aceptados.length === 1 ? '' : 's'} para revisar.`
    : 'No encontré cambios aplicables para este pedido.'
  const respuesta = propuesta.notaDelModelo?.trim()
  const esperabaCambios = (modo === 'DISENAR' || modo === 'MODIFICAR') && !propuesta.aceptados.length
  return esperabaCambios
    ? `La IA respondió, pero no generó cambios aplicables. Vuelve a intentarlo con una instrucción más concreta.${respuesta ? `\n\nRespuesta recibida:\n${respuesta}` : ''}`
    : respuesta || fallback
}

const clavePropuesta = (propuesta: PropuestaIA) => propuesta.interaccionId ?? JSON.stringify(propuesta)
const tiempo = (fecha: string) => { const t = Date.parse(fecha); return Number.isNaN(t) ? 0 : t }
const leerModeloGuardado = () => { try { return localStorage.getItem('colabmodel.ai.modelo') ?? '' } catch { return '' } }

interface Opciones {
  diagramId: string
  token: string
  modelo: EstadoModelo
  conectado: boolean
  /** Quien soy, por id: decide que mensajes son mios. El nombre es solo la etiqueta. */
  usuarioId: string
  usuario: string
  /** Manda el BATCH y resuelve cuando el bus lo acepta o lo rechaza. */
  onAplicar: (comandos: CambioDelLote[], descripcion: string, opciones: OpcionesDeLoteIA) => Promise<ResultadoDeLote>
  /** Avisos de /topic/diagram/{id}/ai: lo que piden y aplican las demas pestañas. */
  suscribirAvisos?: (alAviso: (aviso: AvisoIA) => void) => () => void
}

export function useAsistenteIA({ diagramId, token, modelo, conectado, usuarioId, usuario, onAplicar, suscribirAvisos }: Opciones) {
  const [modo, setModo] = useState<ModoIA>('CONSULTAR')
  const [texto, setTexto] = useState('')
  /** El historial compartido del diagrama, tal como lo guarda el servidor. */
  const [entradas, setEntradas] = useState<EntradaHistorialIA[]>([])
  /** Mensajes de esta pestaña que no son turnos del historial (recomendaciones, arreglos directos...). */
  const [locales, setLocales] = useState<MensajeLocal[]>([])
  /** El pedido propio que todavia espera respuesta: aun no es una entrada del historial. */
  const [pendiente, setPendiente] = useState<PedidoEnCurso | null>(null)
  const [consultando, setConsultando] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [seleccion, setSeleccion] = useState<Record<string, boolean>>({})
  const [ediciones, setEdiciones] = useState<Record<string, Record<string, unknown>>>({})
  /** Clave de la propuesta cuyo lote espera la respuesta del bus. */
  const [aplicando, setAplicando] = useState<string | null>(null)
  /** Propuestas sin turno en el historial (locales o respuestas viejas) que este cliente ya aplico. */
  const [aplicadasSinTurno, setAplicadasSinTurno] = useState<Set<string>>(new Set())
  /** Turno -> opId de un lote YA aceptado cuya marca fallo: solo se reintenta la marca (T-14). */
  const [marcaPendiente, setMarcaPendiente] = useState<Record<string, string>>({})
  const [estadoIA, setEstadoIA] = useState<EstadoIA | null>(null)
  const [cargandoHistorial, setCargandoHistorial] = useState(true)
  const [modeloIA, setModeloIA] = useState(leerModeloGuardado)
  /** TEXT o VOICE: el pedido del cuadro de texto se dicto o se escribio. */
  const [origenPedido, setOrigenPedido] = useState<'TEXT' | 'VOICE'>('TEXT')
  const [dictando, setDictando] = useState(false)
  const [recargas, setRecargas] = useState(0)
  const [seleccionRec, setSeleccionRec] = useState<Set<number>>(new Set())
  const [panel, setPanel] = useState<'cambios' | 'recomendaciones'>('cambios')
  const [accionesEditadas, setAccionesEditadas] = useState<Record<number, string>>({})
  const [arreglosElegidos, setArreglosElegidos] = useState<Record<number, number>>({})
  const reconocedorRef = useRef<ReconocedorDeVoz | null>(null)
  /** Lo que hay escrito ahora mismo: el cierre de `enviar` ve el de cuando se llamo. */
  const textoRef = useRef(texto)
  textoRef.current = texto
  const montadoRef = useRef(true)
  const estuvoConectadoRef = useRef(conectado)
  const diagramaDeEntradasRef = useRef(diagramId)
  const vozDisponible = useMemo(() => reconocedorDisponible() !== null, [])

  useEffect(() => {
    montadoRef.current = true
    return () => { montadoRef.current = false; reconocedorRef.current?.abort() }
  }, [])

  useEffect(() => {
    let vivo = true
    fetch(`/api/v1/diagrams/${diagramId}/ai/estado`, { headers: { Authorization: `Bearer ${token}` } })
      .then((r) => r.ok ? r.json() : null)
      .then((estado) => { if (vivo && estado) {
        const recibido = estado as EstadoIA
        setEstadoIA(recibido)
        setModeloIA((actual) => actual && recibido.modelos.some((m) => m.id === actual) ? actual : recibido.modelo)
      } })
      .catch(() => undefined)
    return () => { vivo = false }
  }, [diagramId, token])

  // Al reconectar se vuelve a pedir el historial: durante el corte pudieron perderse avisos del topico.
  useEffect(() => {
    if (conectado && !estuvoConectadoRef.current) setRecargas((n) => n + 1)
    estuvoConectadoRef.current = conectado
  }, [conectado])

  useEffect(() => {
    let vivo = true
    // Un diagrama distinto empieza de cero: fusionar con el anterior mezclaria historiales.
    if (diagramaDeEntradasRef.current !== diagramId) {
      diagramaDeEntradasRef.current = diagramId
      setEntradas([])
      setLocales([])
      setMarcaPendiente({})
    }
    setCargandoHistorial(true)
    fetch(`/api/v1/diagrams/${diagramId}/ai/historial`, { headers: { Authorization: `Bearer ${token}` } })
      .then(async (r) => {
        if (!r.ok) throw new Error(`No se pudo cargar el historial de IA (HTTP ${r.status}).`)
        return r.json() as Promise<EntradaHistorialIA[]>
      })
      .then((delServidor) => { if (vivo) setEntradas((actuales) => reconciliarHistorial(actuales, delServidor)) })
      .catch((e) => { if (vivo) setError(e instanceof Error ? e.message : 'No se pudo cargar el historial de IA.') })
      .finally(() => { if (vivo) setCargandoHistorial(false) })
    return () => { vivo = false }
  }, [diagramId, token, recargas])

  // Sincronia entre pestañas y usuarios (T-04): lo que otro pide o aplica llega por el topico.
  useEffect(() => {
    if (!suscribirAvisos) return
    return suscribirAvisos((aviso) => setEntradas((actuales) => fusionarEntrada(actuales, aviso.entrada)))
  }, [suscribirAvisos])

  // La conversacion: el historial del equipo y, intercalados por fecha, los mensajes locales.
  const mensajes = useMemo<MensajeAsistente[]>(() => {
    const delHistorial = mensajesDeChat(entradas, null, usuarioId, usuario, textoDeRespuesta)
    const cuando = new Map(entradas.map((e) => [e.id, tiempo(e.creadaEn)]))
    const todos = [
      ...delHistorial.map((m, i) => ({ m: m as MensajeAsistente, t: cuando.get(m.id.replace(/-(usuario|asistente)$/, '')) ?? 0, i })),
      ...locales.map((l, i) => ({ m: l.mensaje, t: l.creadoEn, i: delHistorial.length + i })),
    ].sort((a, b) => a.t - b.t || a.i - b.i).map((x) => x.m)
    if (pendiente) todos.push({ id: pendiente.id, autor: 'usuario', propio: true, nombre: usuario, etiqueta: `${usuario} · vos`,
      inicial: inicialDe(usuario), texto: pendiente.texto, modo: pendiente.modo, enviando: true })
    return todos
  }, [entradas, locales, pendiente, usuarioId, usuario])

  /** La propuesta que se muestra para aplicar: la mas reciente con cambios, del historial o local. */
  const ultima = useMemo(() => {
    let mejor: { propuesta: PropuestaIA; modo: ModoIA; entrada?: EntradaHistorialIA; t: number } | undefined
    for (const e of entradas) if (e.propuesta.aceptados.length && (!mejor || tiempo(e.creadaEn) >= mejor.t))
      mejor = { propuesta: e.propuesta, modo: e.modo, entrada: e, t: tiempo(e.creadaEn) }
    for (const l of locales) if (l.mensaje.propuesta?.aceptados.length && (!mejor || l.creadoEn >= mejor.t))
      mejor = { propuesta: l.mensaje.propuesta, modo: l.mensaje.modo, t: l.creadoEn }
    return mejor
  }, [entradas, locales])
  const ultimaPropuesta = ultima?.propuesta
  const ultimaEntrada = ultima?.entrada
  const claveUltima = ultimaPropuesta ? clavePropuesta(ultimaPropuesta) : null

  // La seleccion y las ediciones son de UNA propuesta: si llega otra (propia o ajena), se empieza de nuevo.
  useEffect(() => {
    if (!ultimaPropuesta) { setSeleccion({}); setEdiciones({}); return }
    setSeleccion(Object.fromEntries(ultimaPropuesta.aceptados.map((_, i) => [String(i), true])))
    setEdiciones(Object.fromEntries(ultimaPropuesta.aceptados.map((c, i) => [String(i), { ...c.payload }])))
    setPanel('cambios')
  }, [claveUltima])

  const ultimasRecomendaciones = [...mensajes].reverse().find((m) => m.recomendaciones)?.recomendaciones
  const recomendacionesOrdenadas = useMemo(
    () => ordenarRecomendaciones(ultimasRecomendaciones?.recomendaciones ?? []), [ultimasRecomendaciones])
  // Los arreglos ya armados (revision para generar) mandan; si no, se deducen del hallazgo.
  const opcionesPorRecomendacion = useMemo(() => new Map(recomendacionesOrdenadas.map((r) =>
    [r.id, ultimasRecomendaciones?.arreglos?.[r.id] ?? arreglosDirectos(r.titulo, modelo)])),
  [recomendacionesOrdenadas, ultimasRecomendaciones, modelo])

  // Solo los "sin resolver" que siguen siendo ciertos en el diagrama actual (pueden haberse corregido despues).
  const pendientesVigentes = useMemo(() => (ultimaPropuesta?.advertencias ?? [])
    .filter((a) => a.startsWith(PREFIJO_SIN_RESOLVER) && pendienteVigente(a.slice(PREFIJO_SIN_RESOLVER.length), modelo)),
  [ultimaPropuesta, modelo])

  const agregarLocal = (mensaje: Omit<MensajeAsistente, 'propio' | 'nombre' | 'etiqueta' | 'inicial'>) => {
    const deUsuario = mensaje.autor === 'usuario'
    setLocales((l) => [...l, { creadoEn: Date.now() + l.length / 1000, mensaje: { ...mensaje, local: true, propio: true,
      nombre: deUsuario ? usuario : 'ColabModel IA', etiqueta: deUsuario ? `${usuario} · vos` : 'ColabModel IA',
      inicial: deUsuario ? inicialDe(usuario) : 'IA' } }])
  }

  const pedirIA = async (url: string, cuerpo: unknown) => {
    const r = await fetch(`/api/v1/diagrams/${diagramId}/ai/${url}`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
      body: JSON.stringify(cuerpo),
    })
    const respuesta = await r.json().catch(() => null)
    if (!r.ok) throw new Error(mensajeDeFalloDePropuesta(r.status, respuesta))
    return respuesta
  }

  /** Un turno nuevo del historial: el aviso del topico trae la misma entrada y la que llegue segunda la reemplaza. */
  const registrarTurno = (prompt: string, modoTurno: ModoIA, propuesta: PropuestaIA) =>
    setEntradas((actuales) => fusionarEntrada(actuales, {
      id: propuesta.interaccionId ?? uuid(), userId: usuarioId, userName: usuario, modo: modoTurno,
      modelo: propuesta.modelo, prompt, respuesta: propuesta.notaDelModelo, propuesta,
      aplicada: false, creadaEn: new Date().toISOString(),
    }))

  /** Se preelige el arreglo cuando es la unica respuesta o la opcion sugerida; el usuario la revisa antes de aplicar. */
  const unicosArreglos = (ids: number[], titulos: string[], armados?: RecomendacionesIA['arreglos']) =>
    Object.fromEntries(ids.flatMap((id, i) => ((o) => o.length === 1 || o[0]?.sugerido)(
      armados?.[id] ?? arreglosDirectos(titulos[i], modelo)) ? [[id, 0]] : [])) as Record<number, number>

  const mostrarRecomendaciones = (r: RecomendacionesIA, texto: string, seleccionadas: Set<number>) => {
    agregarLocal({ id: uuid(), autor: 'asistente', modo: 'RECOMENDAR', recomendaciones: r, texto })
    setSeleccionRec(seleccionadas)
    setAccionesEditadas({})
    setArreglosElegidos(unicosArreglos(r.recomendaciones.map((x) => x.id), r.recomendaciones.map((x) => x.titulo), r.arreglos))
    setPanel('recomendaciones')
  }

  const escribir = (valor: string) => {
    setTexto(valor)
    if (!valor.trim()) setOrigenPedido('TEXT')
  }

  /** Dictado por voz del navegador (Diego): lo dicho se suma al cuadro y el pedido sale como VOICE. */
  const dictar = () => {
    if (dictando) { reconocedorRef.current?.stop(); return }
    const Reconocedor = reconocedorDisponible()
    if (!Reconocedor) { setError(AVISO_SIN_VOZ); return }
    const r = new Reconocedor()
    r.lang = idiomaDeDictado(typeof navigator === 'undefined' ? undefined : navigator.language)
    r.continuous = false
    r.interimResults = true
    r.maxAlternatives = 1
    const previo = textoRef.current.trim()
    r.onresult = (e) => {
      const dicho = textoReconocido(e)
      if (!dicho) return
      setTexto(previo ? `${previo} ${dicho}` : dicho)
      setOrigenPedido('VOICE')
    }
    r.onerror = (e) => { const m = mensajeDeErrorDeVoz(e.error); if (m) setError(m) }
    r.onend = () => { reconocedorRef.current = null; if (montadoRef.current) setDictando(false) }
    reconocedorRef.current = r
    setError(null)
    try {
      r.start()
      setDictando(true)
    } catch {
      reconocedorRef.current = null
      setError('No se pudo iniciar el dictado. Podés escribir el pedido.')
    }
  }

  /**
   * @param modoPedido el modo a usar; por defecto el elegido en la vista. El chat lo pasa segun la intencion.
   * @param origen TEXT o VOICE: las sugerencias de la interfaz salen como TEXT.
   */
  const enviar = async (pedido = texto, modoPedido: ModoIA = modo, origen: 'TEXT' | 'VOICE' = origenPedido) => {
    const limpio = pedido.trim()
    const recomendar = modoPedido === 'RECOMENDAR'
    if ((!limpio && !recomendar) || consultando) return
    // Lo dictado ya va en el pedido: un resultado que llegue despues de stop() no vuelve a llenar el cuadro.
    const reconocedor = reconocedorRef.current
    if (reconocedor) { reconocedor.onresult = null; reconocedor.stop() }
    setTexto('')
    setOrigenPedido('TEXT')
    setConsultando(true)
    setError(null)
    try {
      if (recomendar) {
        // Recomendar no deja turno en el historial: la revision es de esta pestaña.
        agregarLocal({ id: uuid(), autor: 'usuario', modo: modoPedido, texto: limpio || REVISION_SIN_REQUISITO })
        // El texto, si lo hay, es el requisito cuyas reglas se verifican contra el diagrama.
        const requisito = limpio && limpio !== REVISION_SIN_REQUISITO ? limpio : undefined
        const recomendaciones = await pedirIA('recomendaciones',
          { requisito, modelo: modeloIA || undefined }) as RecomendacionesIA
        mostrarRecomendaciones(recomendaciones, textoDeRecomendaciones(recomendaciones),
          seleccionInicial(recomendaciones.recomendaciones))
      } else {
        setPendiente({ id: uuid(), texto: limpio, modo: modoPedido })
        const propuesta = await pedirIA('propuestas',
          { prompt: limpio, origin: origen, modo: modoPedido, modelo: modeloIA || undefined }) as PropuestaIA
        registrarTurno(limpio, modoPedido, propuesta)
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : 'No se pudo consultar al asistente.')
      // Lo escrito no se pierde: vuelve al cuadro (con su origen) si no se escribio otra cosa (Diego).
      if (!recomendar && textoTrasFallo(textoRef.current, limpio) === limpio) { setTexto(limpio); setOrigenPedido(origen) }
    } finally {
      setPendiente(null)
      setConsultando(false)
    }
  }

  /** Las recomendaciones elegidas pasan a una propuesta MODIFICAR; nada se aplica sin confirmar. */
  const convertirRecomendaciones = async () => {
    // La accion editada es la decision del usuario ("conecta Itinerario con Paquete").
    const lista = elegidas(recomendacionesOrdenadas, seleccionRec)
      .map((r) => ({ ...r, accion: accionesEditadas[r.id]?.trim() || r.accion }))
    if (!lista.length || consultando) return
    // Con un arreglo directo elegido, el comando se arma aqui contra el diagrama real:
    // el modelo solo recibe lo que no tiene una opcion concreta.
    const directos = lista.flatMap((r) => {
      const i = arreglosElegidos[r.id]
      return i === undefined ? [] : (opcionesPorRecomendacion.get(r.id)?.[i]?.cambios ?? [])
    })
    // En la revision para generar, lo que no tiene arreglo directo es informativo: no se manda al modelo.
    const paraIA = ultimasRecomendaciones?.sinIA ? [] : lista.filter((r) => arreglosElegidos[r.id] === undefined)
    const pedido = `Aplicar ${lista.length} ${lista.length === 1 ? 'recomendación' : 'recomendaciones'}: ${lista.map((r) => r.titulo).join('; ')}`
    if (!paraIA.length && !directos.length) {
      agregarLocal({ id: uuid(), autor: 'asistente', modo: 'RECOMENDAR',
        texto: 'Esos puntos no tienen un arreglo directo: revisalos a mano en el diagrama.' })
      return
    }
    if (!paraIA.length) {
      agregarLocal({ id: uuid(), autor: 'usuario', modo: 'RECOMENDAR', texto: pedido })
      const propuesta: PropuestaIA = { diagramId, origin: 'TEXT', aceptados: directos, rechazados: [], descartadosPorTope: [],
        advertencias: [], propuestosPorElModelo: 0, modelo: 'arreglo directo',
        notaDelModelo: `Preparé ${directos.length} cambio${directos.length === 1 ? '' : 's'} con los arreglos que elegiste, sin usar el modelo. Revisalos y aplicalos.` }
      agregarLocal({ id: uuid(), autor: 'asistente', modo: 'MODIFICAR', propuesta, texto: textoDeRespuesta(propuesta, 'MODIFICAR') })
      return
    }
    setPendiente({ id: uuid(), texto: pedido, modo: 'MODIFICAR' })
    setConsultando(true)
    setError(null)
    try {
      const propuesta = await pedirIA('recomendaciones/propuesta',
        { recomendaciones: paraIA, origin: 'TEXT', modelo: modeloIA || undefined }) as PropuestaIA
      // Es un turno del historial (el servidor lo registra y lo avisa a los demas); los arreglos directos
      // elegidos en la misma tanda viajan en el mismo lote.
      registrarTurno(pedido, 'MODIFICAR', directos.length ? { ...propuesta, aceptados: [...directos, ...propuesta.aceptados] } : propuesta)
    } catch (e) {
      setError(e instanceof Error ? e.message : 'No se pudieron convertir las recomendaciones en cambios.')
    } finally {
      setPendiente(null)
      setConsultando(false)
    }
  }

  /** Lo que Diseñar dejo "sin resolver" pasa a tarjetas: el usuario elige, precisa y convierte. */
  const corregirPendientes = () => {
    if (!ultimaPropuesta?.advertencias?.length) return
    if (!pendientesVigentes.length) {
      agregarLocal({ id: uuid(), autor: 'asistente', modo: 'RECOMENDAR',
        texto: 'Los puntos que Diseñar dejó pendientes ya están resueltos en el diagrama. Si querés, pedime "Revisa el diagrama".' })
      return
    }
    const pendientes = pendientesComoRecomendaciones(pendientesVigentes)
    mostrarRecomendaciones(pendientes, pendientes.resumen, new Set(pendientes.recomendaciones.map((r) => r.id)))
  }

  /**
   * "¿Esta listo para generar?": junta lo que dice el generador (/codegen/resumen), las ambiguedades
   * sin decidir y las comprobaciones del diagrama. Los arreglos son directos, sin IA.
   */
  const revisarGeneracion = async () => {
    if (consultando) return
    agregarLocal({ id: uuid(), autor: 'usuario', modo: 'RECOMENDAR', texto: '¿Está listo para generar?' })
    setConsultando(true)
    setError(null)
    try {
      const leer = async <T,>(url: string): Promise<T | null> => {
        const r = await fetch(`/api/v1/diagrams/${diagramId}/${url}`, { headers: { Authorization: `Bearer ${token}` } })
        return r.ok ? r.json() as Promise<T> : null
      }
      const [resumen, ambiguedades] = await Promise.all([
        leer<ResumenCodegen>('codegen/resumen').catch(() => null),
        leer<AmbiguedadPendiente[]>('ambiguities').catch(() => null)])
      const revision = revisarParaGenerar(modelo, resumen, ambiguedades)
      // Solo se preseleccionan los puntos con arreglo directo: lo informativo no se "convierte".
      mostrarRecomendaciones(revision, revision.resumen,
        new Set(revision.recomendaciones.filter((r) => revision.arreglos?.[r.id]).map((r) => r.id)))
    } finally {
      setConsultando(false)
    }
  }

  /**
   * Aplicar son dos pasos (Diego, T-04/T-14): mandar UN BATCH por el bus y, con la operacion aceptada,
   * pedir la marca con su opId. Si el lote YA se acepto y solo fallo la marca, no se vuelve a mandar
   * el lote (aplicaria los cambios dos veces): se reintenta unicamente la marca.
   */
  const aplicar = async () => {
    if (!ultimaPropuesta || !claveUltima || aplicando) return
    const propuesta = ultimaPropuesta
    const turno = propuesta.interaccionId
    const confirmada = turno ? marcaPendiente[turno] : undefined
    const comandos = propuesta.aceptados
      .map((c, i) => ({ c, i }))
      .filter(({ i }) => seleccion[String(i)] !== false)
      .map(({ c, i }) => ({ type: c.tipo, payload: ediciones[String(i)] ?? c.payload }))
    if (!confirmada && !comandos.length) return
    setAplicando(claveUltima)
    setError(null)
    const nombreModo = MODOS.find((m) => m.id === ultima?.modo)?.nombre ?? 'cambios'
    const resultado = await aplicarPropuesta({
      interaccionId: turno,
      confirmada,
      enviarLote: () => onAplicar(comandos, `Propuesta IA: ${nombreModo}`, { origin: origenDelLote(propuesta), interaccionId: turno }),
      marcar: (opId) => pedirMarcaDeAplicada(diagramId, token, turno as string, opId),
    })
    if (!montadoRef.current) return
    switch (resultado.tipo) {
      case 'rechazado':
        setError(`El servidor rechazó los cambios y no se aplicó ninguno. ${resultado.mensaje}`)
        break
      case 'sin-confirmar':
        setError('El servidor no confirmó los cambios a tiempo. Revisá el diagrama antes de volver a aplicarlos.')
        break
      case 'aplicada-sin-turno':
        setAplicadasSinTurno((a) => new Set(a).add(clavePropuesta(propuesta)))
        break
      case 'aplicada-sin-marca':
        if (turno) setMarcaPendiente((m) => ({ ...m, [turno]: resultado.opId }))
        setError('Los cambios YA se aplicaron al diagrama, pero no se pudo registrar la marca en el historial de IA. Tocá «Reintentar la marca»: no se vuelven a enviar los cambios.')
        break
      case 'marcada':
        if (turno) setMarcaPendiente((m) => { const { [turno]: _, ...resto } = m; return resto })
        setEntradas((actuales) => fusionarEntrada(actuales, resultado.entrada))
        break
    }
    setAplicando(null)
  }

  const turnoUltimo = ultimaPropuesta?.interaccionId
  /** El lote se acepto pero falta la marca en el historial: se ve como aplicada, con el reintento a mano. */
  const soloFaltaLaMarca = !ultimaEntrada?.aplicada && !!turnoUltimo && marcaPendiente[turnoUltimo] !== undefined
  const yaAplicada = !!ultimaEntrada?.aplicada || soloFaltaLaMarca || (claveUltima !== null && aplicadasSinTurno.has(claveUltima))
  const enviandoLote = claveUltima !== null && aplicando === claveUltima
  const editar = (indice: number, campo: string, valor: unknown) => setEdiciones((actual) => ({
    ...actual,
    [String(indice)]: { ...(actual[String(indice)] ?? {}), [campo]: valor },
  }))
  const nombreDeClase = (id: unknown, fallback: string) => {
    const buscado = String(id ?? '')
    const existente = modelo.classes.find((x) => x.id === buscado)?.name
    if (existente) return existente
    const indice = ultimaPropuesta?.aceptados.findIndex((c) =>
      c.tipo === 'CREATE_CLASS' && String(c.payload.classId ?? '') === buscado) ?? -1
    return indice >= 0 ? String(ediciones[String(indice)]?.name ?? ultimaPropuesta?.aceptados[indice].payload.name ?? fallback) : fallback
  }
  const cambiarModeloIA = (id: string) => {
    setModeloIA(id)
    try { localStorage.setItem('colabmodel.ai.modelo', id) } catch { /* sin almacenamiento: solo esta sesion */ }
  }

  return {
    modo, setModo, texto, escribir, mensajes, consultando, error, seleccion, setSeleccion, ediciones,
    estadoIA, cargandoHistorial, modeloIA, cambiarModeloIA, seleccionRec, setSeleccionRec, panel, setPanel,
    accionesEditadas, setAccionesEditadas, arreglosElegidos, setArreglosElegidos,
    ultimaPropuesta, ultimasRecomendaciones, recomendacionesOrdenadas, opcionesPorRecomendacion, pendientesVigentes,
    enviar, convertirRecomendaciones, corregirPendientes, revisarGeneracion, aplicar, yaAplicada, soloFaltaLaMarca,
    enviandoLote, editar, nombreDeClase, dictar, dictando, vozDisponible, origenPedido,
  }
}

export type AsistenteIA = ReturnType<typeof useAsistenteIA>
