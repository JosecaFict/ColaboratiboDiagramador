import { useEffect, useMemo, useState } from 'react'
import { v4 as uuid } from 'uuid'
import type { EstadoModelo } from '../canvas/applyOperation'
import type { CambioDelLote } from '../importacion/propuesta'
import type { EntradaHistorialIA, EstadoIA, MensajeIA, ModoIA, PropuestaIA } from './tipos'

interface Props {
  diagramId: string
  token: string
  modelo: EstadoModelo
  conectado: boolean
  puedeEditar: boolean
  usuario: string
  onAplicar: (comandos: CambioDelLote[], descripcion: string) => void
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

export function VistaIA({ diagramId, token, modelo, conectado, puedeEditar, usuario, onAplicar }: Props) {
  const [modo, setModo] = useState<ModoIA>('CONSULTAR')
  const [texto, setTexto] = useState('')
  const [mensajes, setMensajes] = useState<MensajeIA[]>([])
  const [consultando, setConsultando] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [seleccion, setSeleccion] = useState<Record<string, boolean>>({})
  const [ediciones, setEdiciones] = useState<Record<string, Record<string, unknown>>>({})
  const [aplicadas, setAplicadas] = useState<Set<string>>(new Set())
  const [estadoIA, setEstadoIA] = useState<EstadoIA | null>(null)
  const [cargandoHistorial, setCargandoHistorial] = useState(true)
  const [modeloIA, setModeloIA] = useState(() => localStorage.getItem('colabmodel.ai.modelo') ?? '')

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

  useEffect(() => {
    let vivo = true
    setCargandoHistorial(true)
    fetch(`/api/v1/diagrams/${diagramId}/ai/historial`, { headers: { Authorization: `Bearer ${token}` } })
      .then(async (r) => {
        if (!r.ok) throw new Error(`No se pudo cargar el historial de IA (HTTP ${r.status}).`)
        return r.json() as Promise<EntradaHistorialIA[]>
      })
      .then((entradas) => {
        if (!vivo) return
        setMensajes(entradas.flatMap((entrada) => [
          { id: `${entrada.id}-usuario`, autor: 'usuario' as const, texto: entrada.prompt,
            modo: entrada.modo, usuario: entrada.userName },
          { id: `${entrada.id}-asistente`, autor: 'asistente' as const,
            texto: textoDeRespuesta(entrada.propuesta, entrada.modo), modo: entrada.modo,
            propuesta: entrada.propuesta },
        ]))
        setAplicadas(new Set(entradas.filter((e) => e.aplicada).map((e) => clavePropuesta(e.propuesta))))
      })
      .catch((e) => { if (vivo) setError(e instanceof Error ? e.message : 'No se pudo cargar el historial de IA.') })
      .finally(() => { if (vivo) setCargandoHistorial(false) })
    return () => { vivo = false }
  }, [diagramId, token])

  const relaciones = modelo.relationships.length
  const atributos = useMemo(() => modelo.classes.reduce((n, c) => n + c.attributes.length, 0), [modelo.classes])
  const ultimaPropuesta = [...mensajes].reverse().find((m) => m.propuesta?.aceptados.length)?.propuesta

  const enviar = async (pedido = texto) => {
    const limpio = pedido.trim()
    if (!limpio || consultando) return
    const mensaje: MensajeIA = { id: uuid(), autor: 'usuario', texto: limpio, modo }
    setMensajes((m) => [...m, mensaje])
    setTexto('')
    setConsultando(true)
    setError(null)
    try {
      const r = await fetch(`/api/v1/diagrams/${diagramId}/ai/propuestas`, {
        method: 'POST',
        headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
        body: JSON.stringify({ prompt: limpio, origin: 'TEXT', modo, modelo: modeloIA || undefined }),
      })
      const cuerpo = await r.json().catch(() => null)
      if (!r.ok) throw new Error(cuerpo?.mensaje ?? `La IA respondió con HTTP ${r.status}.`)
      const propuesta = cuerpo as PropuestaIA
      setMensajes((m) => [...m, {
        id: uuid(), autor: 'asistente', modo, propuesta,
        texto: textoDeRespuesta(propuesta, modo),
      }])
      setSeleccion(Object.fromEntries(propuesta.aceptados.map((_, i) => [String(i), true])))
      setEdiciones(Object.fromEntries(propuesta.aceptados.map((c, i) => [String(i), { ...c.payload }])))
    } catch (e) {
      setError(e instanceof Error ? e.message : 'No se pudo consultar al asistente.')
    } finally {
      setConsultando(false)
    }
  }

  const aplicar = () => {
    if (!ultimaPropuesta) return
    const comandos = ultimaPropuesta.aceptados
      .filter((_, i) => seleccion[String(i)] !== false)
      .map((c, i) => ({ type: c.tipo, payload: ediciones[String(i)] ?? c.payload }))
    if (!comandos.length) return
    onAplicar(comandos, `Propuesta IA: ${MODOS.find((m) => m.id === modo)?.nombre}`)
    setAplicadas((a) => new Set(a).add(clavePropuesta(ultimaPropuesta)))
    if (ultimaPropuesta.interaccionId) {
      void fetch(`/api/v1/diagrams/${diagramId}/ai/historial/${ultimaPropuesta.interaccionId}/aplicada`, {
        method: 'PATCH', headers: { Authorization: `Bearer ${token}` },
      }).then((r) => { if (!r.ok) throw new Error(`HTTP ${r.status}`) })
        .catch(() => setError('Los cambios se enviaron, pero no se pudo actualizar el estado del historial de IA.'))
    }
  }

  const yaAplicada = ultimaPropuesta ? aplicadas.has(clavePropuesta(ultimaPropuesta)) : false
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
        <div className="ia-privacidad"><span className="ia-punto" />
          {estadoIA ? <><strong>{modeloIA || estadoIA.modelo}</strong><br/><small>{estadoIA.local ? `Ollama local · ${estadoIA.contexto} tokens` : `Proveedor ${estadoIA.proveedor}`}</small></> : <><span>Consultando modelo…</span><br/></>}
          <small>{estadoIA?.local ? ' · El diagrama permanece en tu equipo.' : ''}</small>
        </div>
      </aside>

      <div className="ia-conversacion">
        <header><div><h2>Trabajá sobre el modelo</h2><p>Pregunta, revisa y convierte decisiones en cambios verificables.</p></div><span className={conectado ? 'ia-linea activo' : 'ia-linea'}>{conectado ? 'En directo' : 'Sin conexión'}</span></header>
        <div className="ia-mensajes" aria-live="polite">
          {cargandoHistorial && mensajes.length === 0 && <div className="ia-pensando"><span/><span/><span/> Recuperando la conversación del diagrama…</div>}
          {!cargandoHistorial && mensajes.length === 0 && <div className="ia-vacio"><IconoIA/><h3>El contexto ya está preparado</h3><p>La IA conoce clases, atributos, métodos y relaciones de este diagrama.</p><div className="ia-sugerencias">{INICIALES[modo].map((s) => <button key={s} onClick={() => void enviar(s)}>{s}</button>)}</div></div>}
          {mensajes.map((m) => <article key={m.id} className={`ia-mensaje ${m.autor}`}>
            <div className="ia-avatar">{m.autor === 'usuario' ? (m.usuario ?? usuario).slice(0, 1).toUpperCase() : <IconoIA/>}</div>
            <div><div className="ia-autor">{m.autor === 'usuario' ? (m.usuario ?? usuario) : 'ColabModel IA'} <span>{MODOS.find((x) => x.id === m.modo)?.nombre}</span>{m.propuesta?.modelo && <span>{m.propuesta.modelo}</span>}</div><p>{m.texto}</p>
              {!!m.propuesta?.rechazados.length && <small className="ia-advertencia">{m.propuesta.rechazados.length} sugerencia(s) no pasaron la validación del servidor.</small>}
              {!!m.propuesta?.advertencias?.length && <small className="ia-advertencia">La auditoría automática encontró {m.propuesta.advertencias.length} punto(s) para revisar.</small>}
            </div>
          </article>)}
          {consultando && <div className="ia-pensando"><span/><span/><span/> Analizando el modelo y sus relaciones…</div>}
        </div>
        {error && <div className="ia-error" role="alert"><strong>No se pudo completar la consulta.</strong><span>{error}</span></div>}
        <form className="ia-compositor" onSubmit={(e) => { e.preventDefault(); void enviar() }}>
          <textarea value={texto} onChange={(e) => setTexto(e.target.value)} rows={3}
            placeholder={`${MODOS.find((m) => m.id === modo)?.nombre}: escribí una instrucción sobre el diagrama…`}
            onKeyDown={(e) => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); void enviar() } }}/>
          <div><span>Enter envía · Shift + Enter agrega una línea</span><button disabled={!texto.trim() || consultando || !conectado}>Enviar</button></div>
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
                <input type="checkbox" checked={activo} disabled={yaAplicada}
                  onChange={(e) => setSeleccion((s) => ({ ...s, [String(i)]: e.target.checked }))}/>
                <span><code>{c.tipo}</code><strong>{c.resumen}</strong></span>
              </label>
              {activo && !yaAplicada && <div className="ia-edicion">
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
          <div className="ia-aplicar"><button onClick={aplicar} disabled={!puedeEditar || !conectado || yaAplicada || !ultimaPropuesta.aceptados.some((_, i) => seleccion[String(i)] !== false)}>{yaAplicada ? 'Propuesta aplicada' : 'Aplicar seleccionados'}</button><small>{puedeEditar ? 'Se enviarán como una sola operación colaborativa.' : 'Tu rol es de solo lectura.'}</small></div>
        </>}
      </aside>
    </section>
  )
}
