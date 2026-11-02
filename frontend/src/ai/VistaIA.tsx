import { useEffect, useMemo, useRef, useState } from 'react'
import { v4 as uuid } from 'uuid'
import type { EstadoModelo } from '../canvas/applyOperation'
import type { CambioDelLote } from '../importacion/propuesta'
import type { ResultadoDeLote } from './esperaDeLotes'
import type { Rol } from '../auth/permisos'
import { mensajesDeChat, mensajeDeFalloDePropuesta, textoDeDestinatario, textoTrasFallo, type PedidoEnCurso } from './chat'
import { aplicarPropuesta, pedirMarcaDeAplicada } from './aplicacion'
import { fusionarEntrada, origenDelLote, reconciliarHistorial, ultimaConCambios, type AvisoIA } from './historial'
import { PanelConfiguracionIA } from './PanelConfiguracionIA'
import type { EntradaHistorialIA, EstadoIA, ModoIA, PropuestaIA } from './tipos'
import {
  AVISO_SIN_VOZ, idiomaDeDictado, mensajeDeErrorDeVoz, reconocedorDisponible, textoReconocido,
  type ReconocedorDeVoz,
} from './voz'

export interface OpcionesDeLoteIA {
  origin: 'TEXT' | 'VOICE'
  /** El turno del historial que aplica el lote: viaja en el payload como `interaccionIA`. */
  interaccionId?: string
}

interface Props {
  diagramId: string
  /** El proyecto del diagrama: la configuración de la IA es del proyecto. */
  proyectoId: string
  token: string
  modelo: EstadoModelo
  conectado: boolean
  puedeEditar: boolean
  /** Quién soy, por id: decide qué mensajes son míos. El nombre es solo la etiqueta. */
  usuarioId: string
  usuario: string
  /** Mi rol en el proyecto: solo el OWNER edita la configuración de la IA. */
  rol: Rol
  /** Manda el BATCH y resuelve cuando el bus lo acepta o lo rechaza. */
  onAplicar: (comandos: CambioDelLote[], descripcion: string, opciones: OpcionesDeLoteIA) => Promise<ResultadoDeLote>
  /** Avisos de /topic/diagram/{id}/ai: lo que piden y aplican las demás pestañas. */
  suscribirAvisos?: (alAviso: (aviso: AvisoIA) => void) => () => void
}

const MODOS: { id: ModoIA; nombre: string; descripcion: string }[] = [
  { id: 'CONSULTAR', nombre: 'Preguntar', descripcion: 'Explica el modelo sin cambiarlo' },
  { id: 'REVISAR', nombre: 'Revisar', descripcion: 'Detecta riesgos y decisiones débiles' },
  { id: 'DISENAR', nombre: 'Diseñar', descripcion: 'Propone una estructura UML nueva' },
  { id: 'MODIFICAR', nombre: 'Modificar', descripcion: 'Convierte el pedido en cambios precisos' },
]

const INICIALES: Record<ModoIA, string[]> = {
  CONSULTAR: ['¿Qué representa este modelo?', 'Explícame sus relaciones principales'],
  REVISAR: ['Revisa claves y cardinalidades', 'Busca problemas de normalización'],
  DISENAR: ['Diseña el núcleo de una biblioteca', 'Propón clases para registrar préstamos'],
  MODIFICAR: ['Agrega una clase Pago relacionada con Pedido', 'Añade fechas de auditoría'],
}

function IconoIA() {
  return <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 3.5 13.7 9l5.3 1.7-5.3 1.7L12 18l-1.7-5.6L5 10.7 10.3 9 12 3.5Z"/><path d="m18 15 .8 2.2L21 18l-2.2.8L18 21l-.8-2.2L15 18l2.2-.8L18 15Z"/></svg>
}

function textoDeRespuesta(propuesta: PropuestaIA, modo: ModoIA) {
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

const leerModeloGuardado = () => {
  try { return localStorage.getItem('colabmodel.ai.modelo') ?? '' } catch { return '' }
}

export function VistaIA({ diagramId, proyectoId, token, modelo, conectado, puedeEditar, usuarioId, usuario, rol, onAplicar, suscribirAvisos }: Props) {
  const [modo, setModo] = useState<ModoIA>('CONSULTAR')
  const [texto, setTexto] = useState('')
  /** El historial compartido del diagrama, tal como lo guarda el servidor. */
  const [entradas, setEntradas] = useState<EntradaHistorialIA[]>([])
  /** El pedido propio que todavía espera respuesta: aún no es una entrada del historial. */
  const [pendiente, setPendiente] = useState<PedidoEnCurso | null>(null)
  const [consultando, setConsultando] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [seleccion, setSeleccion] = useState<Record<string, boolean>>({})
  const [ediciones, setEdiciones] = useState<Record<string, Record<string, unknown>>>({})
  /** Clave de la propuesta cuyo lote espera la respuesta del bus. */
  const [aplicando, setAplicando] = useState<string | null>(null)
  /** Propuestas sin turno en el historial (respuestas viejas) que este cliente ya aplicó. */
  const [aplicadasSinTurno, setAplicadasSinTurno] = useState<Set<string>>(new Set())
  /**
   * Turno → opId del lote que el bus YA aceptó pero cuya marca de "aplicada" falló.
   * Mientras esté aquí no se vuelve a mandar el lote: solo se reintenta la marca (T-14).
   */
  const [marcaPendiente, setMarcaPendiente] = useState<Record<string, string>>({})
  const [estadoIA, setEstadoIA] = useState<EstadoIA | null>(null)
  const [cargandoHistorial, setCargandoHistorial] = useState(true)
  const [modeloIA, setModeloIA] = useState(leerModeloGuardado)
  /** TEXT o VOICE: el pedido del cuadro de texto se dictó o se escribió. */
  const [origenPedido, setOrigenPedido] = useState<'TEXT' | 'VOICE'>('TEXT')
  const [dictando, setDictando] = useState(false)
  const [recargas, setRecargas] = useState(0)
  const [configAbierta, setConfigAbierta] = useState(false)
  const [resumenConfig, setResumenConfig] = useState('')
  const reconocedorRef = useRef<ReconocedorDeVoz | null>(null)
  /** Lo que hay escrito ahora mismo: el cierre de `enviar` ve el de cuando se llamó. */
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

  // Al reconectar se vuelve a pedir el historial: durante el corte pudieron
  // perderse avisos del tópico, que no se reenvían.
  useEffect(() => {
    if (conectado && !estuvoConectadoRef.current) setRecargas((n) => n + 1)
    estuvoConectadoRef.current = conectado
  }, [conectado])

  useEffect(() => {
    let vivo = true
    // Un diagrama distinto empieza de cero: fusionar con el anterior mezclaría historiales.
    if (diagramaDeEntradasRef.current !== diagramId) {
      diagramaDeEntradasRef.current = diagramId
      setEntradas([])
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

  // Sincronía entre pestañas y usuarios (T-04): lo que otro pide o aplica llega por el tópico.
  useEffect(() => {
    if (!suscribirAvisos) return
    return suscribirAvisos((aviso) => setEntradas((actuales) => fusionarEntrada(actuales, aviso.entrada)))
  }, [suscribirAvisos])

  // El historial como conversación del equipo: autor por userId, lado propio o ajeno.
  const mensajes = useMemo(
    () => mensajesDeChat(entradas, pendiente, usuarioId, usuario, textoDeRespuesta),
    [entradas, pendiente, usuarioId, usuario],
  )

  const relaciones = modelo.relationships.length
  const atributos = useMemo(() => modelo.classes.reduce((n, c) => n + c.attributes.length, 0), [modelo.classes])
  const ultimaEntrada = ultimaConCambios(entradas)
  const ultimaPropuesta = ultimaEntrada?.propuesta
  const claveUltima = ultimaPropuesta ? clavePropuesta(ultimaPropuesta) : null

  // La selección y las ediciones son de UNA propuesta. Si llega otra (propia o
  // de otro usuario), se empieza de nuevo con todos los cambios marcados.
  useEffect(() => {
    if (!ultimaPropuesta) { setSeleccion({}); setEdiciones({}); return }
    setSeleccion(Object.fromEntries(ultimaPropuesta.aceptados.map((_, i) => [String(i), true])))
    setEdiciones(Object.fromEntries(ultimaPropuesta.aceptados.map((c, i) => [String(i), { ...c.payload }])))
  }, [claveUltima])

  const escribir = (valor: string) => {
    setTexto(valor)
    if (!valor.trim()) setOrigenPedido('TEXT')
  }

  const dictar = () => {
    if (dictando) { reconocedorRef.current?.stop(); return }
    const Reconocedor = reconocedorDisponible()
    if (!Reconocedor) { setError(AVISO_SIN_VOZ); return }
    const r = new Reconocedor()
    r.lang = idiomaDeDictado(typeof navigator === 'undefined' ? undefined : navigator.language)
    r.continuous = false
    r.interimResults = true
    r.maxAlternatives = 1
    const previo = texto.trim()
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

  /** Las sugerencias son texto escrito por la interfaz: salen como TEXT. */
  const enviar = async (pedido = texto, origen: 'TEXT' | 'VOICE' = origenPedido) => {
    const limpio = pedido.trim()
    if (!limpio || consultando) return
    // Lo dictado ya va en el pedido: un resultado que el navegador entregue
    // despues de stop() no tiene que volver a llenar el cuadro.
    const reconocedor = reconocedorRef.current
    if (reconocedor) {
      reconocedor.onresult = null
      reconocedor.stop()
    }
    setPendiente({ id: uuid(), texto: limpio, modo })
    setTexto('')
    setOrigenPedido('TEXT')
    setConsultando(true)
    setError(null)
    try {
      const r = await fetch(`/api/v1/diagrams/${diagramId}/ai/propuestas`, {
        method: 'POST',
        headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
        body: JSON.stringify({ prompt: limpio, origin: origen, modo, modelo: modeloIA || undefined }),
      })
      const cuerpo = await r.json().catch(() => null)
      if (!r.ok) throw new Error(mensajeDeFalloDePropuesta(r.status, cuerpo))
      const propuesta = cuerpo as PropuestaIA
      // El aviso del tópico trae la misma entrada; la que llegue segunda la reemplaza.
      setEntradas((actuales) => fusionarEntrada(actuales, {
        id: propuesta.interaccionId ?? uuid(), userId: usuarioId, userName: usuario, modo,
        modelo: propuesta.modelo, prompt: limpio, respuesta: propuesta.notaDelModelo, propuesta,
        aplicada: false, creadaEn: new Date().toISOString(),
      }))
    } catch (e) {
      setError(e instanceof Error ? e.message : 'No se pudo consultar al asistente.')
      // Lo escrito no se pierde: vuelve al cuadro (con su origen) si no se escribió otra cosa.
      const vuelve = textoTrasFallo(textoRef.current, limpio)
      if (vuelve === limpio) { setTexto(limpio); setOrigenPedido(origen) }
    } finally {
      setPendiente(null)
      setConsultando(false)
    }
  }

  /**
   * Aplicar son dos pasos: mandar UN BATCH por el bus y, con la operación aceptada,
   * pedir la marca con su opId. Un rechazo deja la propuesta sin marcar y lista para
   * reintentar. Si el lote YA se aceptó y solo falló la marca, no se vuelve a mandar
   * el lote (aplicaría los cambios dos veces): se reintenta únicamente la marca (T-14).
   */
  const aplicar = async () => {
    if (!ultimaPropuesta || !claveUltima || aplicando) return
    const propuesta = ultimaPropuesta
    const turno = propuesta.interaccionId
    const confirmada = turno ? marcaPendiente[turno] : undefined
    const comandos = ultimaPropuesta.aceptados
      .map((c, i) => ({ c, i }))
      .filter(({ i }) => seleccion[String(i)] !== false)
      .map(({ c, i }) => ({ type: c.tipo, payload: ediciones[String(i)] ?? c.payload }))
    if (!confirmada && !comandos.length) return
    setAplicando(claveUltima)
    setError(null)
    const nombreModo = MODOS.find((m) => m.id === ultimaEntrada?.modo)?.nombre ?? 'cambios'
    const resultado = await aplicarPropuesta({
      interaccionId: turno,
      confirmada,
      enviarLote: () => onAplicar(comandos, `Propuesta IA: ${nombreModo}`,
        { origin: origenDelLote(propuesta), interaccionId: turno }),
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
  /** El lote se aceptó pero falta la marca en el historial: se ve como aplicada, con el reintento a mano. */
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

  return (
    <section className="ia-vista" aria-label="Asistente de modelado">
      <aside className="ia-contexto">
        <div className="ia-marca"><span><IconoIA /></span><div><strong>ColabModel IA</strong><small>Contexto del diagrama</small></div></div>
        <div className="ia-metricas" aria-label="Contexto disponible">
          <div><strong>{modelo.classes.length}</strong><span>clases</span></div>
          <div><strong>{atributos}</strong><span>atributos</span></div>
          <div><strong>{relaciones}</strong><span>relaciones</span></div>
        </div>
        <h3>Modo de trabajo</h3>
        <div className="ia-modos">
          {MODOS.map((m) => <button key={m.id} className={modo === m.id ? 'activo' : ''}
            onClick={() => setModo(m.id)} aria-pressed={modo === m.id}>
            <strong>{m.nombre}</strong><span>{m.descripcion}</span>
          </button>)}
        </div>
        {!!estadoIA?.modelos.length && <div className="ia-selector-modelo">
          <label htmlFor="modelo-local">Modelo local</label>
          <select id="modelo-local" value={modeloIA} disabled={consultando} onChange={(e) => {
            setModeloIA(e.target.value)
            localStorage.setItem('colabmodel.ai.modelo', e.target.value)
          }}>
            {estadoIA.modelos.map((m) => <option key={m.id} value={m.id}>{m.nombre}{m.recomendado ? ' · recomendado' : ''}</option>)}
          </select>
          {estadoIA.modelos.find((m) => m.id === modeloIA) && <small>
            {estadoIA.modelos.find((m) => m.id === modeloIA)?.descripcion}
            {' · '}{estadoIA.modelos.find((m) => m.id === modeloIA)?.think ? 'razonamiento activo' : 'respuesta directa'}
          </small>}
        </div>}
        <div className="ia-configuracion">
          <button type="button" className="ia-config-cabecera" aria-expanded={configAbierta}
            onClick={() => setConfigAbierta((a) => !a)}>
            <strong>Configuración de la IA</strong>
            <small>{resumenConfig || 'Rol, detalle e instrucciones'}</small>
          </button>
          {configAbierta && <PanelConfiguracionIA proyectoId={proyectoId} token={token} rol={rol}
            onResumen={setResumenConfig} />}
        </div>
        <div className="ia-privacidad"><span className="ia-punto" />
          {estadoIA ? <><strong>{modeloIA || estadoIA.modelo}</strong><br/><small>{estadoIA.local ? `Ollama local · ${estadoIA.contexto} tokens` : `Proveedor ${estadoIA.proveedor}`}</small></> : <><span>Consultando modelo…</span><br/></>}
          <small>{estadoIA?.local ? ' · El diagrama permanece en tu equipo.' : ''}</small>
        </div>
      </aside>

      <div className="ia-conversacion">
        <header><div><h2>Trabajá sobre el modelo</h2><p>Pregunta, revisa y convierte decisiones en cambios verificables.</p></div><span className={conectado ? 'ia-linea activo' : 'ia-linea'}>{conectado ? 'En directo' : 'Sin conexión'}</span></header>
        <div className="ia-mensajes" aria-live="polite">
          {cargandoHistorial && mensajes.length === 0 && <div className="ia-pensando"><span/><span/><span/> Recuperando la conversación del diagrama…</div>}
          {!cargandoHistorial && mensajes.length === 0 && <div className="ia-vacio"><IconoIA/><h3>El contexto ya está preparado</h3><p>La IA conoce clases, atributos, métodos y relaciones de este diagrama.</p><div className="ia-sugerencias">{INICIALES[modo].map((s) => <button key={s} onClick={() => void enviar(s, 'TEXT')}>{s}</button>)}</div></div>}
          {mensajes.map((m) => <article key={m.id}
            className={`ia-mensaje ${m.autor} ${m.propio ? 'propio' : 'ajeno'}${m.enviando ? ' enviando' : ''}`}>
            <div className="ia-avatar" aria-hidden="true">{m.autor === 'usuario' ? m.inicial : <IconoIA/>}</div>
            <div><div className="ia-autor">{m.etiqueta}
              {m.respondeA && <span className="ia-destinatario">{textoDeDestinatario(m.respondeA)}</span>}
              <span>{MODOS.find((x) => x.id === m.modo)?.nombre}</span>{m.propuesta?.modelo && <span>{m.propuesta.modelo}</span>}</div><p>{m.texto}</p>
              {!!m.propuesta?.rechazados.length && <small className="ia-advertencia">{m.propuesta.rechazados.length} sugerencia(s) no pasaron la validación del servidor.</small>}
              {!!m.propuesta?.advertencias?.length && <small className="ia-advertencia">La auditoría automática encontró {m.propuesta.advertencias.length} punto(s) para revisar.</small>}
            </div>
          </article>)}
          {consultando && <div className="ia-pensando"><span/><span/><span/> Analizando el modelo y sus relaciones…</div>}
        </div>
        {error && <div className="ia-error" role="alert"><strong>Algo no salió bien.</strong><span>{error}</span></div>}
        <form className="ia-compositor" onSubmit={(e) => { e.preventDefault(); void enviar() }}>
          <textarea value={texto} onChange={(e) => escribir(e.target.value)} rows={3}
            placeholder={`${MODOS.find((m) => m.id === modo)?.nombre}: escribí una instrucción sobre el diagrama…`}
            onKeyDown={(e) => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); void enviar() } }}/>
          <div>
            <span>
              {dictando ? 'Escuchando… hablá y volvé a tocar el micrófono para terminar'
                : vozDisponible ? 'Enter envía · Shift + Enter agrega una línea' : AVISO_SIN_VOZ}
              {origenPedido === 'VOICE' && !dictando && <> · <strong className="ia-dictado">Pedido dictado</strong></>}
            </span>
            <button type="button" className={dictando ? 'ia-microfono activo' : 'ia-microfono'}
              onClick={dictar} disabled={!vozDisponible || consultando}
              style={{ marginLeft: 'auto', marginRight: 6, background: dictando ? '#c2410c' : 'transparent',
                color: dictando ? '#fff' : 'var(--ia-lila)', boxShadow: 'inset 0 0 0 1px currentColor' }}
              aria-pressed={dictando} aria-label={dictando ? 'Terminar el dictado' : 'Dictar el pedido'}
              title={vozDisponible ? (dictando ? 'Terminar el dictado' : 'Dictar el pedido') : AVISO_SIN_VOZ}>
              {dictando ? 'Detener' : 'Dictar'}
            </button>
            <button type="submit" disabled={!texto.trim() || consultando || !conectado}>Enviar</button>
          </div>
        </form>
      </div>

      <aside className="ia-propuesta">
        <header><h3>Cambios propuestos</h3><span>{ultimaPropuesta?.aceptados.length ?? 0}</span></header>
        {!ultimaPropuesta && <div className="ia-propuesta-vacia"><p>Cuando la IA sugiera cambios, aparecerán aquí antes de tocar el modelo.</p><small>Nada se aplica sin tu confirmación.</small></div>}
        {ultimaPropuesta && <>
          <div className="ia-cambios">{ultimaPropuesta.aceptados.map((c, i) => {
            const p = ediciones[String(i)] ?? c.payload
            const activo = seleccion[String(i)] !== false
            const claseOrigen = nombreDeClase(p.sourceClassId, 'origen')
            const claseDestino = nombreDeClase(p.targetClassId, 'destino')
            return <div className={`ia-cambio ${activo ? '' : 'inactivo'}`} key={`${c.tipo}-${i}`}>
              <label className="ia-cambio-cabecera">
                <input type="checkbox" checked={activo} disabled={yaAplicada || enviandoLote}
                  onChange={(e) => setSeleccion((s) => ({ ...s, [String(i)]: e.target.checked }))}/>
                <span><code>{c.tipo}</code><strong>{c.resumen}</strong></span>
              </label>
              {activo && !yaAplicada && !enviandoLote && <div className="ia-edicion">
                {(c.tipo === 'CREATE_CLASS' || c.tipo === 'ADD_ATTRIBUTE') &&
                  <label>Nombre<input value={String(p.name ?? '')} onChange={(e) => editar(i, 'name', e.target.value)}/></label>}
                {c.tipo === 'ADD_ATTRIBUTE' && <>
                  <label>Tipo<select value={String(p.dataType ?? 'string')} onChange={(e) => editar(i, 'dataType', e.target.value)}>
                    {['string', 'text', 'int', 'long', 'decimal', 'boolean', 'date', 'datetime', 'uuid'].map((t) => <option key={t}>{t}</option>)}
                  </select></label>
                  <div className="ia-banderas">
                    {(['required', 'unique', 'pk'] as const).map((campo) => <label key={campo}>
                      <input type="checkbox" checked={p[campo] === true} onChange={(e) => editar(i, campo, e.target.checked)}/>{campo}
                    </label>)}
                  </div>
                </>}
                {c.tipo === 'CREATE_RELATION' && <>
                  <label>Relación<select value={String(p.kind ?? 'ASSOCIATION')} onChange={(e) => editar(i, 'kind', e.target.value)}>
                    {['ASSOCIATION', 'AGGREGATION', 'COMPOSITION', 'GENERALIZATION', 'DEPENDENCY'].map((t) => <option key={t}>{t}</option>)}
                  </select></label>
                  <label>Junto a {claseOrigen}<input value={String(p.sourceMultiplicity ?? '')} placeholder="1" onChange={(e) => editar(i, 'sourceMultiplicity', e.target.value)}/></label>
                  <label>Junto a {claseDestino}<input value={String(p.targetMultiplicity ?? '')} placeholder="0..*" onChange={(e) => editar(i, 'targetMultiplicity', e.target.value)}/></label>
                  <small>El valor junto a cada clase indica cuántas instancias de esa clase puede tener una instancia del otro extremo.</small>
                </>}
              </div>}
            </div>
          })}</div>
          {!!ultimaPropuesta.descartadosPorTope.length && <p className="ia-limite">El límite seguro dejó {ultimaPropuesta.descartadosPorTope.length} cambios fuera.</p>}
          {!!ultimaPropuesta.advertencias?.length && <div className="ia-auditoria"><strong>Auditoría automática</strong><ul>{ultimaPropuesta.advertencias.map((a) => <li key={a}>{a}</li>)}</ul></div>}
          <div className="ia-aplicar"><button onClick={() => void aplicar()}
            disabled={!puedeEditar || !conectado || enviandoLote
              || (soloFaltaLaMarca ? false : yaAplicada || !ultimaPropuesta.aceptados.some((_, i) => seleccion[String(i)] !== false))}>
            {soloFaltaLaMarca ? (enviandoLote ? 'Registrando la marca…' : 'Reintentar la marca')
              : yaAplicada ? 'Propuesta aplicada' : enviandoLote ? 'Esperando confirmación…' : 'Aplicar seleccionados'}</button>
            <small>{!puedeEditar ? 'Tu rol es de solo lectura.'
              : soloFaltaLaMarca ? 'Los cambios ya se aplicaron al diagrama. Falta registrar la marca en el historial.'
              : yaAplicada ? 'El servidor aceptó estos cambios.' : 'Se enviarán como una sola operación colaborativa.'}</small></div>
        </>}
      </aside>
    </section>
  )
}
