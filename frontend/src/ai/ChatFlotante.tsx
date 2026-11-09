import { useEffect, useMemo, useRef, useState } from 'react'
import { IconoIA } from './VistaIA'
import { detectarIntencion, NOMBRE_INTENCION, type Intencion } from './intencion'
import { REVISION_SIN_REQUISITO, type AsistenteIA } from './useAsistenteIA'
import { etiquetaOrigen } from './recomendaciones'
import { AVISO_SIN_VOZ } from './voz'

interface Props {
  asistente: AsistenteIA
  conectado: boolean
  puedeEditar: boolean
  /** Abre la vista completa de IA para revisar o editar cambio por cambio. */
  onVerDetalle: () => void
  /** Abre la revision del generador de Spring Boot (la misma del boton Generar del editor). */
  onGenerar: () => void
}

const INTENCIONES: Intencion[] = ['CONSULTAR', 'DISENAR', 'RECOMENDAR', 'MODIFICAR', 'CORREGIR', 'GENERAR']

/**
 * Asistente flotante sobre el diagrama. Comparte la conversacion con la vista de IA:
 * mismo estado (useAsistenteIA), mismos endpoints, mismas protecciones. El diagrama sigue
 * visible y nada se aplica sin que el usuario pulse Aplicar.
 */
export function ChatFlotante({ asistente, conectado, puedeEditar, onVerDetalle, onGenerar }: Props) {
  const {
    mensajes, consultando, error, enviar, ultimaPropuesta, ultimasRecomendaciones, recomendacionesOrdenadas,
    opcionesPorRecomendacion, arreglosElegidos, setArreglosElegidos, seleccionRec, setSeleccionRec,
    convertirRecomendaciones, corregirPendientes, revisarGeneracion, aplicar, yaAplicada, estadoIA, modeloIA,
  } = asistente
  const [abierto, setAbierto] = useState(false)
  // El cuadro de texto es el del asistente: lo dictado por voz (Diego) aparece aqui y el pedido sale como VOICE.
  const { texto, escribir: setTexto, dictar, dictando, vozDisponible, origenPedido } = asistente
  const [intencionManual, setIntencionManual] = useState<Intencion | null>(null)
  const [vistos, setVistos] = useState(mensajes.length)
  const lista = useRef<HTMLDivElement>(null)

  const intencion = intencionManual ?? detectarIntencion(texto)
  // Solo los pendientes que siguen siendo ciertos en el diagrama actual.
  const pendientes = asistente.pendientesVigentes.length
  const sinVer = abierto ? 0 : mensajes.slice(vistos).filter((m) => m.autor === 'asistente').length
  // La propuesta y las recomendaciones activas se muestran como tarjetas debajo de su mensaje.
  const idPropuesta = useMemo(() => [...mensajes].reverse().find((m) => m.propuesta === ultimaPropuesta)?.id,
    [mensajes, ultimaPropuesta])
  const idRecomendaciones = useMemo(() => [...mensajes].reverse().find((m) => m.recomendaciones === ultimasRecomendaciones)?.id,
    [mensajes, ultimasRecomendaciones])

  useEffect(() => { if (abierto) setVistos(mensajes.length) }, [abierto, mensajes.length])
  // El historial que llega al abrir el diagrama no es "nuevo": solo cuentan las respuestas posteriores.
  const historialContado = useRef(false)
  useEffect(() => {
    if (asistente.cargandoHistorial || historialContado.current) return
    historialContado.current = true
    setVistos(mensajes.length)
  }, [asistente.cargandoHistorial, mensajes.length])
  useEffect(() => { lista.current?.scrollTo({ top: lista.current.scrollHeight }) }, [abierto, mensajes.length, consultando])

  // Ctrl+I abre y cierra el asistente desde cualquier parte del editor.
  useEffect(() => {
    const alTeclear = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'i') { e.preventDefault(); setAbierto((a) => !a) }
    }
    window.addEventListener('keydown', alTeclear)
    return () => window.removeEventListener('keydown', alTeclear)
  }, [])

  const mandar = (pedido = texto, elegida: Intencion = intencion) => {
    if (consultando) return
    if (elegida === 'GENERAR') {
      void revisarGeneracion()
    } else if (elegida === 'CORREGIR') {
      // Corregir trabaja sobre lo que dejo el ultimo diseño; si no queda nada, se revisa el diagrama.
      if (pendientes && yaAplicada) corregirPendientes()
      else void enviar(REVISION_SIN_REQUISITO, 'RECOMENDAR')
    } else if (elegida === 'RECOMENDAR') {
      // "Revisa el diagrama" es una orden, no un requisito: solo un texto largo se verifica regla por regla.
      const requisito = pedido.trim().length > 80 ? pedido : REVISION_SIN_REQUISITO
      void enviar(requisito, 'RECOMENDAR')
    } else {
      if (!pedido.trim()) return
      void enviar(pedido, elegida)
    }
    setTexto('')
    setIntencionManual(null)
  }

  const elegirArreglo = (id: number, indice: number) => {
    setArreglosElegidos({ ...arreglosElegidos, [id]: indice })
    setSeleccionRec(new Set(seleccionRec).add(id))
  }
  const alternar = (id: number) => {
    const nueva = new Set(seleccionRec)
    if (nueva.has(id)) nueva.delete(id); else nueva.add(id)
    setSeleccionRec(nueva)
  }

  if (!abierto) {
    return <button className="chat-boton" onClick={() => setAbierto(true)} title="Asistente (Ctrl+I)" aria-label="Abrir asistente">
      <IconoIA />{sinVer > 0 && <span className="chat-aviso">{sinVer}</span>}
    </button>
  }

  return (
    <section className="chat-flotante" aria-label="Asistente de modelado">
      <header>
        <span className="chat-marca"><IconoIA /></span>
        <div><strong>Asistente</strong><small>{modeloIA || estadoIA?.modelo || 'modelo local'}{conectado ? '' : ' · sin conexión'}</small></div>
        <button onClick={onVerDetalle} title="Abrir la vista completa de IA">Vista completa</button>
        <button onClick={() => setAbierto(false)} aria-label="Cerrar asistente">✕</button>
      </header>

      <div className="chat-mensajes" ref={lista} aria-live="polite">
        {!mensajes.length && <div className="chat-bienvenida">
          <p>Contame qué querés hacer con el diagrama. Por ejemplo:</p>
          {['Diseña un sistema de ventas con clientes, productos y garantías', 'Revisa el diagrama', 'Corrige los errores',
            '¿Qué representa este modelo?', '¿Está listo para generar?'].map((s) => <button key={s} onClick={() => mandar(s, detectarIntencion(s))}>{s}</button>)}
        </div>}

        {mensajes.slice(-40).map((m) => <div key={m.id}
          className={`chat-mensaje ${m.autor}${m.autor === 'usuario' && !m.propio ? ' ajeno' : ''}${m.enviando ? ' enviando' : ''}`}>
          {/* La conversacion es del equipo (Diego): lo que piden los demas se ve con su nombre. */}
          {m.autor === 'usuario' && !m.propio && <small className="chat-autor">{m.etiqueta}</small>}
          {m.autor === 'asistente' && m.respondeA && !m.respondeA.tuyo && <small className="chat-autor">responde a {m.respondeA.nombre}</small>}
          <p>{m.texto}</p>

          {m.id === idPropuesta && ultimaPropuesta && <div className="chat-tarjeta">
            <strong>{ultimaPropuesta.aceptados.length} cambio{ultimaPropuesta.aceptados.length === 1 ? '' : 's'} propuesto{ultimaPropuesta.aceptados.length === 1 ? '' : 's'}</strong>
            {!!ultimaPropuesta.rechazados.length && <small>{ultimaPropuesta.rechazados.length} sugerencia(s) descartadas por el servidor.</small>}
            {!yaAplicada && <div className="chat-acciones">
              <button className="principal" onClick={aplicar} disabled={!puedeEditar || !conectado}>Aplicar</button>
              <button onClick={onVerDetalle}>Revisar uno por uno</button>
            </div>}
            {yaAplicada && <small>✔ Aplicado al diagrama.</small>}
            {!!pendientes && <div className="chat-pendientes">
              <span>⚠ Quedó {pendientes} punto{pendientes === 1 ? '' : 's'} sin resolver.</span>
              <button onClick={corregirPendientes} disabled={!yaAplicada || !puedeEditar}>Corregir ahora</button>
              {!yaAplicada && <small>Aplicá primero los cambios.</small>}
            </div>}
          </div>}

          {m.id === idRecomendaciones && (!!recomendacionesOrdenadas.length || !!ultimasRecomendaciones?.generacion) && <div className="chat-tarjeta">
            {recomendacionesOrdenadas.map((r) => {
              const opciones = opcionesPorRecomendacion.get(r.id) ?? []
              return <div key={r.id} className={`chat-recomendacion ${seleccionRec.has(r.id) ? '' : 'inactiva'}`}>
                <label><input type="checkbox" checked={seleccionRec.has(r.id)} onChange={() => alternar(r.id)}/>
                  <em className={`ia-gravedad ${r.gravedad.toLowerCase()}`}>{r.gravedad}</em>
                  <span>{r.titulo}</span></label>
                <small>{etiquetaOrigen(r)}</small>
                {!!opciones.length && <div className="chat-opciones">
                  {opciones.slice(0, 4).map((o, i) => <button key={o.etiqueta}
                    className={arreglosElegidos[r.id] === i ? 'elegida' : ''} onClick={() => elegirArreglo(r.id, i)}>
                    {o.etiqueta}{o.sugerido ? ' ★' : ''}</button>)}
                </div>}
                {!opciones.length && <small>{ultimasRecomendaciones?.sinIA ? 'Para revisar a mano en el diagrama.' : 'Sin arreglo directo: lo intentará la IA con la acción sugerida.'}</small>}
              </div>
            })}
            <div className="chat-acciones">
              {(!ultimasRecomendaciones?.generacion || Object.keys(ultimasRecomendaciones.arreglos ?? {}).length > 0) &&
                <button className={ultimasRecomendaciones?.generacion?.bloqueantes === 0 ? '' : 'principal'}
                  onClick={() => void convertirRecomendaciones()}
                  disabled={!puedeEditar || !conectado || consultando || !seleccionRec.size}>
                  Preparar {seleccionRec.size} cambio{seleccionRec.size === 1 ? '' : 's'}</button>}
              {/* Sin bloqueantes, el generador puede correr: se abre su revision (archivos, base, descarga). */}
              {ultimasRecomendaciones?.generacion?.bloqueantes === 0 &&
                <button className="principal" onClick={onGenerar} disabled={!conectado}>Generar Spring Boot</button>}
              {!!ultimasRecomendaciones?.generacion?.bloqueantes &&
                <small>Corregí los puntos marcados ALTA y volvé a preguntar "¿Está listo para generar?".</small>}
            </div>
          </div>}
        </div>)}

        {consultando && <div className="chat-mensaje asistente pensando"><span/><span/><span/></div>}
        {error && <div className="chat-error" role="alert">{error}</div>}
      </div>

      <form className="chat-entrada" onSubmit={(e) => { e.preventDefault(); mandar() }}>
        <div className="chat-intencion">
          <span>Lo haré como:</span>
          {/* Sin texto todavia no hay intencion que mostrar: "Preguntar" por defecto confundia. */}
          <select value={!texto.trim() && !intencionManual ? '' : intencion}
            onChange={(e) => setIntencionManual(e.target.value ? e.target.value as Intencion : null)}>
            {!texto.trim() && !intencionManual && <option value="">según lo que escribas</option>}
            {INTENCIONES.map((i) => <option key={i} value={i}>{NOMBRE_INTENCION[i]}</option>)}
          </select>
        </div>
        <textarea rows={2} value={texto}
          placeholder={dictando ? 'Escuchando… hablá y tocá el micrófono para terminar' : 'Escribí o dictá tu pedido… (Enter envía)'}
          onChange={(e) => setTexto(e.target.value)}
          onKeyDown={(e) => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); mandar() } }}/>
        <div className="chat-botones">
          <button type="button" className={dictando ? 'chat-microfono activo' : 'chat-microfono'} onClick={dictar}
            disabled={!vozDisponible || consultando} aria-pressed={dictando}
            aria-label={dictando ? 'Terminar el dictado' : 'Dictar el pedido'}
            title={vozDisponible ? (dictando ? 'Terminar el dictado' : 'Dictar el pedido') : AVISO_SIN_VOZ}>
            {dictando ? '■' : '🎤'}</button>
          <button disabled={consultando || !conectado || (!texto.trim() && !['RECOMENDAR', 'CORREGIR', 'GENERAR'].includes(intencion))}>Enviar</button>
        </div>
        {origenPedido === 'VOICE' && !dictando && <small className="chat-dictado">Pedido dictado por voz</small>}
      </form>
    </section>
  )
}
