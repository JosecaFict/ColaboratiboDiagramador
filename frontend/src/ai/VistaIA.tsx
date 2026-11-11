import { useMemo, useState } from 'react'
import type { EstadoModelo } from '../canvas/applyOperation'
import type { CambioDelLote } from '../importacion/propuesta'
import type { Rol } from '../auth/permisos'
import type { ResultadoDeLote } from './esperaDeLotes'
import type { AvisoIA } from './historial'
import { textoDeDestinatario } from './chat'
import { PanelConfiguracionIA } from './PanelConfiguracionIA'
import { etiquetaOrigen } from './recomendaciones'
import type { ModoIA } from './tipos'
import { MODOS, REVISION_SIN_REQUISITO, useAsistenteIA, type AsistenteIA, type OpcionesDeLoteIA } from './useAsistenteIA'
import { AVISO_SIN_VOZ } from './voz'

export type { OpcionesDeLoteIA } from './useAsistenteIA'

interface Comunes {
  modelo: EstadoModelo
  conectado: boolean
  puedeEditar: boolean
  /** El proyecto del diagrama: la configuracion de la IA es del proyecto (Diego). */
  proyectoId: string
  token: string
  /** Mi rol en el proyecto: solo el OWNER edita la configuracion de la IA. */
  rol: Rol
}

/** Con el asistente compartido de App (la conversacion es la misma que la del chat flotante). */
interface ConAsistente extends Comunes { asistente: AsistenteIA }

/** Autonoma: la vista crea su propio asistente (asi la montan las pruebas y quien no comparte estado). */
interface Autonoma extends Comunes {
  asistente?: undefined
  diagramId: string
  usuarioId: string
  usuario: string
  onAplicar: (comandos: CambioDelLote[], descripcion: string, opciones: OpcionesDeLoteIA) => Promise<ResultadoDeLote>
  suscribirAvisos?: (alAviso: (aviso: AvisoIA) => void) => () => void
}

const INICIALES: Record<ModoIA, string[]> = {
  CONSULTAR: ['¿Qué representa este modelo?', 'Explícame sus relaciones principales'],
  REVISAR: ['Revisa claves y cardinalidades', 'Busca problemas de normalización'],
  DISENAR: ['Diseña el núcleo de una biblioteca', 'Propón clases para registrar préstamos'],
  MODIFICAR: ['Agrega una clase Pago relacionada con Pedido', 'Añade fechas de auditoría'],
  RECOMENDAR: [REVISION_SIN_REQUISITO],
  IMAGEN: [],
}

export function IconoIA() {
  return <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 3.5 13.7 9l5.3 1.7-5.3 1.7L12 18l-1.7-5.6L5 10.7 10.3 9 12 3.5Z"/><path d="m18 15 .8 2.2L21 18l-2.2.8L18 21l-.8-2.2L15 18l2.2-.8L18 15Z"/></svg>
}

export function VistaIA(props: ConAsistente | Autonoma) {
  return props.asistente ? <ContenidoVistaIA {...props} asistente={props.asistente} /> : <VistaIAAutonoma {...props} />
}

function VistaIAAutonoma(props: Autonoma) {
  const asistente = useAsistenteIA({ diagramId: props.diagramId, token: props.token, modelo: props.modelo,
    conectado: props.conectado, usuarioId: props.usuarioId, usuario: props.usuario, onAplicar: props.onAplicar,
    suscribirAvisos: props.suscribirAvisos })
  return <ContenidoVistaIA {...props} asistente={asistente} />
}

function ContenidoVistaIA({ asistente, modelo, conectado, puedeEditar, proyectoId, token, rol }: ConAsistente) {
  const {
    modo, setModo, texto, escribir, mensajes, consultando, error, seleccion, setSeleccion, ediciones,
    estadoIA, cargandoHistorial, modeloIA, cambiarModeloIA, seleccionRec, setSeleccionRec, panel, setPanel,
    accionesEditadas, setAccionesEditadas, arreglosElegidos, setArreglosElegidos,
    ultimaPropuesta, ultimasRecomendaciones, recomendacionesOrdenadas, opcionesPorRecomendacion,
    enviar, convertirRecomendaciones, corregirPendientes, aplicar, yaAplicada, soloFaltaLaMarca, enviandoLote,
    editar, nombreDeClase, dictar, dictando, vozDisponible, origenPedido,
  } = asistente
  const [configAbierta, setConfigAbierta] = useState(false)
  const [resumenConfig, setResumenConfig] = useState('')
  const relaciones = modelo.relationships.length
  const atributos = useMemo(() => modelo.classes.reduce((n, c) => n + c.attributes.length, 0), [modelo.classes])

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
          <select id="modelo-local" value={modeloIA} disabled={consultando} onChange={(e) => cambiarModeloIA(e.target.value)}>
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
          {!cargandoHistorial && mensajes.length === 0 && <div className="ia-vacio"><IconoIA/><h3>El contexto ya está preparado</h3><p>La IA conoce clases, atributos, métodos y relaciones de este diagrama.</p><div className="ia-sugerencias">{INICIALES[modo].map((s) => <button key={s} onClick={() => void enviar(s, modo, 'TEXT')}>{s}</button>)}</div></div>}
          {mensajes.map((m) => <article key={m.id}
            className={`ia-mensaje ${m.autor} ${m.propio ? 'propio' : 'ajeno'}${m.enviando ? ' enviando' : ''}`}>
            <div className="ia-avatar" aria-hidden="true">{m.autor === 'usuario' ? m.inicial : <IconoIA/>}</div>
            <div><div className="ia-autor">{m.etiqueta}
              {m.respondeA && <span className="ia-destinatario">{textoDeDestinatario(m.respondeA)}</span>}
              <span>{m.modo === 'IMAGEN' ? 'Imagen' : MODOS.find((x) => x.id === m.modo)?.nombre}</span>{m.propuesta?.modelo && <span>{m.propuesta.modelo}</span>}</div><p>{m.texto}</p>
              {!!m.propuesta?.rechazados.length && <small className="ia-advertencia">{m.propuesta.rechazados.length} sugerencia(s) no pasaron la validación del servidor.</small>}
              {!!m.propuesta?.advertencias?.length && <small className="ia-advertencia">La auditoría automática encontró {m.propuesta.advertencias.length} punto(s) para revisar.</small>}
            </div>
          </article>)}
          {consultando && <div className="ia-pensando"><span/><span/><span/> Analizando el modelo y sus relaciones…</div>}
        </div>
        {error && <div className="ia-error" role="alert"><strong>Algo no salió bien.</strong><span>{error}</span></div>}
        <form className="ia-compositor" onSubmit={(e) => { e.preventDefault(); void enviar() }}>
          <textarea value={texto} onChange={(e) => escribir(e.target.value)} rows={3}
            placeholder={modo === 'RECOMENDAR'
              ? 'Recomendar: pegá el requisito para verificarlo regla por regla, o dejalo vacío para revisar solo la estructura…'
              : `${MODOS.find((m) => m.id === modo)?.nombre}: escribí una instrucción sobre el diagrama…`}
            onKeyDown={(e) => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); void enviar() } }}/>
          <div>
            <span>
              {dictando ? 'Escuchando… hablá y volvé a tocar el micrófono para terminar'
                : vozDisponible ? 'Enter envía · Shift + Enter agrega una línea' : AVISO_SIN_VOZ}
              {origenPedido === 'VOICE' && !dictando && <> · <strong className="ia-dictado">Pedido dictado</strong></>}
            </span>
            <button type="button" className={dictando ? 'ia-microfono activo' : 'ia-microfono'}
              onClick={dictar} disabled={!vozDisponible || consultando}
              style={{ marginLeft: 'auto', marginRight: 6, background: dictando ? 'var(--peligro)' : 'transparent',
                color: dictando ? 'var(--sobre-acento)' : 'var(--ia-lila)', boxShadow: 'inset 0 0 0 1px currentColor' }}
              aria-pressed={dictando} aria-label={dictando ? 'Terminar el dictado' : 'Dictar el pedido'}
              title={vozDisponible ? (dictando ? 'Terminar el dictado' : 'Dictar el pedido') : AVISO_SIN_VOZ}>
              {dictando ? 'Detener' : 'Dictar'}
            </button>
            <button type="submit" disabled={(!texto.trim() && modo !== 'RECOMENDAR') || consultando || !conectado}>
              {modo === 'RECOMENDAR' ? 'Revisar' : 'Enviar'}</button>
          </div>
        </form>
      </div>

      <aside className="ia-propuesta">
        {ultimasRecomendaciones ? <header className="ia-pestanas" role="tablist">
          <button role="tab" aria-selected={panel === 'recomendaciones'} className={panel === 'recomendaciones' ? 'activo' : ''}
            onClick={() => setPanel('recomendaciones')}>Recomendaciones <span>{recomendacionesOrdenadas.length}</span></button>
          <button role="tab" aria-selected={panel === 'cambios'} className={panel === 'cambios' ? 'activo' : ''}
            onClick={() => setPanel('cambios')}>Cambios <span>{ultimaPropuesta?.aceptados.length ?? 0}</span></button>
        </header> : <header><h3>Cambios propuestos</h3><span>{ultimaPropuesta?.aceptados.length ?? 0}</span></header>}
        {ultimasRecomendaciones && panel === 'recomendaciones' && <>
          {!recomendacionesOrdenadas.length && <div className="ia-propuesta-vacia"><p>No hay recomendaciones: el diagrama pasó la revisión.</p></div>}
          <div className="ia-recomendaciones">{recomendacionesOrdenadas.map((r) => {
            const marcada = seleccionRec.has(r.id)
            return <div key={r.id} className={`ia-recomendacion ${r.origen === 'VERIFICADO' ? 'verificada' : 'sugerida'} ${marcada ? '' : 'inactivo'}`}>
              <label className="ia-cambio-cabecera">
                <input type="checkbox" checked={marcada} onChange={(e) => setSeleccionRec((s) => {
                  const nueva = new Set(s)
                  if (e.target.checked) nueva.add(r.id); else nueva.delete(r.id)
                  return nueva
                })}/>
                <span>
                  <span className="ia-etiquetas">
                    <em className={`ia-gravedad ${r.gravedad.toLowerCase()}`}>{r.gravedad}</em>
                    <em className="ia-origen">{etiquetaOrigen(r)}</em>
                  </span>
                  <strong>{r.titulo}</strong>
                </span>
              </label>
              {r.problema && r.problema !== r.titulo && <p>{r.problema}</p>}
              {!!opcionesPorRecomendacion.get(r.id)?.length && <label className="ia-accion"><b>Arreglo directo (sin IA)</b>
                <select value={arreglosElegidos[r.id] ?? ''} disabled={!marcada} onChange={(e) => setArreglosElegidos((a) => {
                  const nuevo = { ...a }
                  if (e.target.value === '') delete nuevo[r.id]; else nuevo[r.id] = Number(e.target.value)
                  return nuevo
                })}>
                  <option value="">— elegí una opción (o dejá que la IA lo intente, menos confiable) —</option>
                  {opcionesPorRecomendacion.get(r.id)!.map((o, i) => <option key={o.etiqueta} value={i}>{o.etiqueta}{o.sugerido ? ' (sugerido)' : ''}</option>)}
                </select>
              </label>}
              {r.accion && arreglosElegidos[r.id] === undefined && !ultimasRecomendaciones.sinIA && <label className="ia-accion"><b>Acción para la IA</b>
                <textarea rows={2} value={accionesEditadas[r.id] ?? r.accion} disabled={!marcada}
                  onChange={(e) => setAccionesEditadas((a) => ({ ...a, [r.id]: e.target.value }))}/>
              </label>}
              {!!r.elementos.length && <small>Afecta a: {r.elementos.join(', ')}</small>}
            </div>
          })}</div>
          {!!ultimasRecomendaciones.verificaciones.length && <details className="ia-auditoria">
            <summary><strong>Reglas del requisito ({ultimasRecomendaciones.verificaciones.filter((v) => v.estado === 'CUMPLE').length}/{ultimasRecomendaciones.verificaciones.length} se cumplen)</strong></summary>
            <ul>{ultimasRecomendaciones.verificaciones.map((v) => <li key={v.numero}>
              {v.estado === 'CUMPLE' ? '✔' : v.estado === 'NO_CUMPLE' ? '✘' : '?'} R{v.numero}: {v.regla}</li>)}</ul>
          </details>}
          <div className="ia-aplicar"><button onClick={() => void convertirRecomendaciones()}
            disabled={!puedeEditar || !conectado || consultando || !seleccionRec.size}>
            Convertir {seleccionRec.size || ''} en cambios</button>
            <small>{puedeEditar ? 'La IA prepara los cambios; los revisás en la pestaña Cambios antes de aplicar.' : 'Tu rol es de solo lectura.'}</small></div>
        </>}
        {modo === 'RECOMENDAR' && !ultimasRecomendaciones && !consultando && <div className="ia-propuesta-vacia">
          <p><strong>Recomendar revisa el diagrama aplicado.</strong></p>
          <p>Pegá el requisito en el cuadro central para verificarlo regla por regla, o dejalo vacío para revisar solo la estructura, y pulsá <strong>Revisar</strong>.</p>
          <small>Las recomendaciones aparecerán aquí como tarjetas para elegir y convertir en cambios.</small>
        </div>}
        {!(modo === 'RECOMENDAR' && !ultimasRecomendaciones) && panel === 'cambios' && !ultimaPropuesta && <div className="ia-propuesta-vacia"><p>Cuando la IA sugiera cambios, aparecerán aquí antes de tocar el modelo.</p><small>Nada se aplica sin tu confirmación.</small></div>}
        {!(modo === 'RECOMENDAR' && !ultimasRecomendaciones) && panel === 'cambios' && ultimaPropuesta && <>
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
          {!!ultimaPropuesta.advertencias?.length && <div className="ia-auditoria"><strong>Auditoría automática</strong><ul>{ultimaPropuesta.advertencias.map((a) => <li key={a}>{a}</li>)}</ul>
            <button className="ia-corregir" onClick={corregirPendientes} disabled={!yaAplicada || !puedeEditar || consultando}>Corregir pendientes</button>
            {!yaAplicada && <small>Aplicá primero la propuesta: los pendientes se corrigen sobre el diagrama.</small>}
          </div>}
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
