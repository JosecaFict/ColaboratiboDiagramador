import { useEffect, useMemo, useState } from 'react'
import { v4 as uuid } from 'uuid'
import type { EstadoModelo } from '../canvas/applyOperation'
import type { CambioDelLote } from '../importacion/propuesta'
import type { EntradaHistorialIA, EstadoIA, MensajeIA, ModoIA, PropuestaIA, RecomendacionesIA } from './tipos'
import { arreglosDirectos } from './arreglosDirectos'
import { elegidas, ordenarRecomendaciones, pendientesComoRecomendaciones, seleccionInicial, textoDeRecomendaciones } from './recomendaciones'

export const MODOS: { id: ModoIA; nombre: string; descripcion: string }[] = [
  { id: 'CONSULTAR', nombre: 'Preguntar', descripcion: 'Explica el modelo sin cambiarlo' },
  { id: 'REVISAR', nombre: 'Revisar', descripcion: 'Detecta riesgos y decisiones débiles' },
  { id: 'DISENAR', nombre: 'Diseñar', descripcion: 'Propone una estructura UML nueva' },
  { id: 'MODIFICAR', nombre: 'Modificar', descripcion: 'Convierte el pedido en cambios precisos' },
  { id: 'RECOMENDAR', nombre: 'Recomendar', descripcion: 'Revisa el diagrama y propone mejoras aplicables' },
]

/** En Recomendar, el texto es el requisito a verificar; este pedido revisa solo la estructura. */
export const REVISION_SIN_REQUISITO = 'Revisa el diagrama actual'

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

interface Opciones {
  diagramId: string
  token: string
  modelo: EstadoModelo
  onAplicar: (comandos: CambioDelLote[], descripcion: string) => void
}

/**
 * Estado y acciones del asistente, compartidos por la vista de IA y el chat flotante:
 * vive en App para que la conversacion no se pierda al cambiar de vista o cerrar el chat.
 */
export function useAsistenteIA({ diagramId, token, modelo, onAplicar }: Opciones) {
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
  const [modeloIA, setModeloIA] = useState(() => {
    try { return localStorage.getItem('colabmodel.ai.modelo') ?? '' } catch { return '' }
  })
  const [seleccionRec, setSeleccionRec] = useState<Set<number>>(new Set())
  const [panel, setPanel] = useState<'cambios' | 'recomendaciones'>('cambios')
  const [accionesEditadas, setAccionesEditadas] = useState<Record<number, string>>({})
  const [arreglosElegidos, setArreglosElegidos] = useState<Record<number, number>>({})

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

  const ultimaPropuesta = [...mensajes].reverse().find((m) => m.propuesta?.aceptados.length)?.propuesta
  const ultimasRecomendaciones = [...mensajes].reverse().find((m) => m.recomendaciones)?.recomendaciones
  const recomendacionesOrdenadas = useMemo(
    () => ordenarRecomendaciones(ultimasRecomendaciones?.recomendaciones ?? []), [ultimasRecomendaciones])
  const opcionesPorRecomendacion = useMemo(() => new Map(recomendacionesOrdenadas.map((r) =>
    [r.id, arreglosDirectos(r.titulo, modelo)])), [recomendacionesOrdenadas, modelo])

  const pedirIA = async (url: string, cuerpo: unknown) => {
    const r = await fetch(`/api/v1/diagrams/${diagramId}/ai/${url}`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
      body: JSON.stringify(cuerpo),
    })
    const respuesta = await r.json().catch(() => null)
    if (!r.ok) throw new Error(respuesta?.mensaje ?? `La IA respondió con HTTP ${r.status}.`)
    return respuesta
  }

  const recibirPropuesta = (propuesta: PropuestaIA, modoRespuesta: ModoIA) => {
    setMensajes((m) => [...m, {
      id: uuid(), autor: 'asistente', modo: modoRespuesta, propuesta,
      texto: textoDeRespuesta(propuesta, modoRespuesta),
    }])
    setSeleccion(Object.fromEntries(propuesta.aceptados.map((_, i) => [String(i), true])))
    setEdiciones(Object.fromEntries(propuesta.aceptados.map((c, i) => [String(i), { ...c.payload }])))
    setPanel('cambios')
  }

  /** Se preelige el arreglo cuando es la unica respuesta o la opcion sugerida; el usuario la revisa antes de aplicar. */
  const unicosArreglos = (ids: number[], titulos: string[]) => Object.fromEntries(ids.flatMap((id, i) =>
    ((o) => o.length === 1 || o[0]?.sugerido)(arreglosDirectos(titulos[i], modelo)) ? [[id, 0]] : [])) as Record<number, number>

  /** @param modoPedido el modo a usar; por defecto el elegido en la vista. El chat lo pasa segun la intencion. */
  const enviar = async (pedido = texto, modoPedido: ModoIA = modo) => {
    const limpio = pedido.trim()
    const recomendar = modoPedido === 'RECOMENDAR'
    if ((!limpio && !recomendar) || consultando) return
    const mensaje: MensajeIA = { id: uuid(), autor: 'usuario', texto: limpio || REVISION_SIN_REQUISITO, modo: modoPedido }
    setMensajes((m) => [...m, mensaje])
    setTexto('')
    setConsultando(true)
    setError(null)
    try {
      if (recomendar) {
        // El texto, si lo hay, es el requisito cuyas reglas se verifican contra el diagrama.
        const requisito = limpio && limpio !== REVISION_SIN_REQUISITO ? limpio : undefined
        const recomendaciones = await pedirIA('recomendaciones',
          { requisito, modelo: modeloIA || undefined }) as RecomendacionesIA
        setMensajes((m) => [...m, {
          id: uuid(), autor: 'asistente', modo: modoPedido, recomendaciones,
          texto: textoDeRecomendaciones(recomendaciones),
        }])
        setSeleccionRec(seleccionInicial(recomendaciones.recomendaciones))
        setAccionesEditadas({})
        setArreglosElegidos(unicosArreglos(recomendaciones.recomendaciones.map((r) => r.id),
          recomendaciones.recomendaciones.map((r) => r.titulo)))
        setPanel('recomendaciones')
      } else {
        recibirPropuesta(await pedirIA('propuestas',
          { prompt: limpio, origin: 'TEXT', modo: modoPedido, modelo: modeloIA || undefined }) as PropuestaIA, modoPedido)
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : 'No se pudo consultar al asistente.')
    } finally {
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
    const paraIA = lista.filter((r) => arreglosElegidos[r.id] === undefined)
    setMensajes((m) => [...m, { id: uuid(), autor: 'usuario', modo: 'RECOMENDAR',
      texto: `Aplicar ${lista.length} ${lista.length === 1 ? 'recomendación' : 'recomendaciones'}: ${lista.map((r) => r.titulo).join('; ')}` }])
    if (!paraIA.length) {
      recibirPropuesta({ diagramId, origin: 'TEXT', aceptados: directos, rechazados: [], descartadosPorTope: [],
        advertencias: [], propuestosPorElModelo: 0, modelo: 'arreglo directo',
        notaDelModelo: `Preparé ${directos.length} cambio${directos.length === 1 ? '' : 's'} con los arreglos que elegiste, sin usar el modelo. Revisalos y aplicalos.` },
      'MODIFICAR')
      return
    }
    setConsultando(true)
    setError(null)
    try {
      const propuesta = await pedirIA('recomendaciones/propuesta',
        { recomendaciones: paraIA, origin: 'TEXT', modelo: modeloIA || undefined }) as PropuestaIA
      recibirPropuesta(directos.length ? { ...propuesta, aceptados: [...directos, ...propuesta.aceptados] } : propuesta, 'MODIFICAR')
    } catch (e) {
      setError(e instanceof Error ? e.message : 'No se pudieron convertir las recomendaciones en cambios.')
    } finally {
      setConsultando(false)
    }
  }

  /** Lo que Diseñar dejo "sin resolver" pasa a tarjetas: el usuario elige, precisa y convierte. */
  const corregirPendientes = () => {
    if (!ultimaPropuesta?.advertencias?.length) return
    const pendientes = pendientesComoRecomendaciones(ultimaPropuesta.advertencias)
    setMensajes((m) => [...m, { id: uuid(), autor: 'asistente', modo: 'RECOMENDAR',
      recomendaciones: pendientes, texto: pendientes.resumen }])
    setSeleccionRec(new Set(pendientes.recomendaciones.map((r) => r.id)))
    setAccionesEditadas({})
    setArreglosElegidos(unicosArreglos(pendientes.recomendaciones.map((r) => r.id), pendientes.recomendaciones.map((r) => r.titulo)))
    setPanel('recomendaciones')
  }

  const aplicar = () => {
    if (!ultimaPropuesta) return
    const comandos = ultimaPropuesta.aceptados
      .filter((_, i) => seleccion[String(i)] !== false)
      .map((c, i) => ({ type: c.tipo, payload: ediciones[String(i)] ?? c.payload }))
    if (!comandos.length) return
    const modoPropuesta = [...mensajes].reverse().find((m) => m.propuesta === ultimaPropuesta)?.modo ?? modo
    onAplicar(comandos, `Propuesta IA: ${MODOS.find((m) => m.id === modoPropuesta)?.nombre}`)
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
  const cambiarModeloIA = (id: string) => {
    setModeloIA(id)
    try { localStorage.setItem('colabmodel.ai.modelo', id) } catch { /* sin almacenamiento: solo esta sesion */ }
  }

  return {
    modo, setModo, texto, setTexto, mensajes, consultando, error, seleccion, setSeleccion, ediciones,
    estadoIA, cargandoHistorial, modeloIA, cambiarModeloIA, seleccionRec, setSeleccionRec, panel, setPanel,
    accionesEditadas, setAccionesEditadas, arreglosElegidos, setArreglosElegidos,
    ultimaPropuesta, ultimasRecomendaciones, recomendacionesOrdenadas, opcionesPorRecomendacion,
    enviar, convertirRecomendaciones, corregirPendientes, aplicar, yaAplicada, editar, nombreDeClase,
  }
}

export type AsistenteIA = ReturnType<typeof useAsistenteIA>
