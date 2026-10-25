import { useEffect, useRef, useState } from 'react'
import { v4 as uuid } from 'uuid'
import { Canvas, type NotacionCardinalidad } from './canvas/Canvas'
import { PanelRelacion } from './panel/PanelRelacion'
import { construirReificacion } from './panel/reificar'
import {
  aplicarOperacion, estadoDesdeSnapshot, estadoVacio,
  type EstadoModelo, type SnapshotDiagrama,
} from './canvas/applyOperation'
import { Toolbar } from './toolbar/Toolbar'
import { VistaLogica, type ModeloLogico } from './logical/VistaLogica'
import { PanelGeneracion, type ResumenGeneracion } from './codegen/PanelGeneracion'
import { VistaFisica, type EsquemaFisico, type VerificacionDelEsquema } from './physical/VistaFisica'
import type { Cambio } from './physical/edicion'
import { PanelVision } from './vision/PanelVision'
import { traducir, type DeteccionConfirmada } from './vision/traductor'
import { detectarDesdeImagen, type DeteccionPropuesta } from './contracts/vision'
import {
  PanelAmbiguedades, type Ambiguedad, type Lectura,
} from './ambiguity/PanelAmbiguedades'
import { ColabClient, historicaAAplicada, type OperacionHistorica } from './collab/wsClient'
import {
  editaEnSitio, entradaDe, entradaDeDecision, HistorialLocal, type EntradaSinConfirmar,
} from './collab/historialLocal'
import { PanelPropiedades, type NuevoMetodo } from './panel/PanelPropiedades'
import { PanelImportacion, type EstadoEnvio } from './importacion/PanelImportacion'
import { PanelExportacion } from './interop/PanelExportacion'
import { exportarXmi, guardar, type PerfilXmi, type XmiExportado } from './interop/exportacionXmi'
import type { Plan, PropuestaDeImportacion } from './importacion/propuesta'
import { esEmbebible, esEnumeracion } from './contracts/command'
import type {
  AppliedOperation, CommandType, NotationView, RelationKind, UmlRelationship,
} from './contracts/command'
import { ETIQUETA_DE_ROL, esSoloLectura, type Rol } from './auth/permisos'
import type { Sesion } from './auth/sesion'
import { VistaIA } from './ai/VistaIA'

/**
 * La vista Chen se saco de circulacion: se trabaja solo con UML.
 *
 * Las anotaciones ER (multivaluado, derivado, clave parcial, entidad debil)
 * NO se fueron con ella: se editan desde el panel de propiedades y siguen
 * alimentando las reglas 11 y 12 de la Vista Logica.
 */
type VistaEditor = NotationView | 'AI'
const VISTAS: { id: VistaEditor; label: string; nota: string }[] = [
  { id: 'CLASS',    label: 'Clases (UML)',   nota: 'Metodos, visibilidad, herencia' },
  { id: 'LOGICAL',  label: 'Logico',         nota: 'Tablas, PK y FK derivadas de las 12 reglas de mapeo' },
  { id: 'PHYSICAL', label: 'Fisico (DDL)',   nota: 'PostgreSQL: se edita, se verifica y se confirma antes de generar' },
  { id: 'AI',       label: 'IA',             nota: 'Consulta, revisa y propone cambios con contexto del modelo' },
]

/** Una decision puede llegar sola o dentro de un lote importado. */
const tocaDecisiones = (op: AppliedOperation) => op.type === 'SET_MODELING_DECISION'
  || (op.type === 'BATCH' && ((op.payload.comandos as { type: string }[]) ?? [])
    .some((c) => c.type === 'SET_MODELING_DECISION'))

/**
 * Comandos que NO pueden cambiar el esquema derivado.
 *
 * Mover o redimensionar es geometria (nivel 3 del ADR-004): no altera ni una
 * columna. Importa porque MOVE_ELEMENT llega con estrangulamiento de 50 ms
 * mientras alguien arrastra: si las Vistas Logica y Fisica se re-pidieran con
 * cada uno, un arrastre ajeno disparaba ~20 derivaciones por segundo contra la
 * base, y cada una recorre el modelo entero.
 */
const SOLO_GEOMETRIA = new Set(['MOVE_ELEMENT', 'RESIZE_ELEMENT'])

const tocaEsquema = (op: AppliedOperation): boolean => {
  if (op.type === 'BATCH') {
    const dentro = (op.payload.comandos as { type: string }[]) ?? []
    return dentro.some((c) => !SOLO_GEOMETRIA.has(c.type))
  }
  return !SOLO_GEOMETRIA.has(op.type)
}

/**
 * Lo que el editor recibe de `Raiz` (ADR-026). Ya no le pide un usuario de
 * prueba a `/dev/bootstrap`: la sesion, el proyecto, el diagrama y el rol salen
 * del login y de la navegacion.
 */
interface Props {
  token: string
  usuario: Sesion
  proyecto: { id: string; nombre: string }
  diagrama: { id: string; nombre: string }
  /**
   * El rol de este usuario en el proyecto. Puede CAMBIAR con el diagrama abierto
   * (un aviso privado del servidor): la interfaz se adapta sin recargar. Solo la
   * refleja --deshabilita lo que no se puede hacer--; quien decide es el servidor,
   * que rechaza el comando de un Observador aunque la interfaz se equivoque.
   */
  rol: Rol
  alVolver: () => void
  alCerrarSesion: () => void
  /** El canal rechazo una suscripcion: quiza ya no es miembro. Que la raiz lo compruebe. */
  alRevalidarAcceso: () => void
}

export default function App({ token, usuario, proyecto, diagrama, rol, alVolver, alCerrarSesion, alRevalidarAcceso }: Props) {
  const diagramId = diagrama.id
  /** El modo Observador: se ve todo, no se edita nada. */
  const soloLectura = esSoloLectura(rol)
  const soloLecturaRef = useRef(soloLectura)
  soloLecturaRef.current = soloLectura
  const [vista, setVista] = useState<VistaEditor>('CLASS')
  const [conectado, setConectado] = useState(false)
  const [resincronizando, setResincronizando] = useState(false)
  const [fisica, setFisica] = useState<EsquemaFisico | null>(null)
  const [leyendoImagen, setLeyendoImagen] = useState(false)
  const [propuestaVision, setPropuestaVision] = useState<DeteccionPropuesta | null>(null)
  const [errorVision, setErrorVision] = useState<string | null>(null)
  const [aplicandoVision, setAplicandoVision] = useState(false)
  const [modelo, setModelo] = useState<EstadoModelo>(estadoVacio(''))
  const [ops, setOps] = useState<AppliedOperation[]>([])
  const [locks, setLocks] = useState<Record<string, string>>({})
  const [error, setError] = useState<string | null>(null)
  const [tipoRelacion, setTipoRelacion] = useState<RelationKind>('ASSOCIATION')
  const [herramientaRelacion, setHerramientaRelacion] = useState(false)
  const [relacionSeleccionada, setRelacionSeleccionada] = useState<string | null>(null)
  const [notacionCardinalidad, setNotacionCardinalidad] = useState<NotacionCardinalidad>('UML')
  const [logica, setLogica] = useState<ModeloLogico | null>(null)
  // Las decisiones no viven en EstadoModelo, asi que su llegada no
  // cambia `modelo` y nada se recargaria solo. Este contador es el aviso.
  const [revisionDecisiones, setRevisionDecisiones] = useState(0)
  /** Sube solo cuando el esquema derivado PUEDE haber cambiado. */
  const [revisionEsquema, setRevisionEsquema] = useState(0)
  const [generando, setGenerando] = useState(false)
  const [resumen, setResumen] = useState<ResumenGeneracion | null>(null)
  const [errorGeneracion, setErrorGeneracion] = useState<string | null>(null)
  const [descargando, setDescargando] = useState(false)
  const [verAmbiguedades, setVerAmbiguedades] = useState(false)
  const [ambiguedades, setAmbiguedades] = useState<Ambiguedad[] | null>(null)
  const [errorAmbiguedades, setErrorAmbiguedades] = useState<string | null>(null)
  const [seleccionada, setSeleccionada] = useState<string | null>(null)
  const [importando, setImportando] = useState(false)
  const [envioImportacion, setEnvioImportacion] = useState<EstadoEnvio | null>(null)
  /** undefined = cerrado; null = escribiendose; luego el archivo o el error. */
  const [exportacion, setExportacion] = useState<XmiExportado | null | undefined>(undefined)
  const [perfilXmi, setPerfilXmi] = useState<PerfilXmi>('ea')
  const [errorExportacion, setErrorExportacion] = useState<string | null>(null)
  const [confirmando, setConfirmando] = useState(false)
  const [errorConfirmacion, setErrorConfirmacion] = useState<string | null>(null)
  /** opId del CONFIRM_SCHEMA que se espera: su rechazo se muestra en la Vista Fisica. */
  const confirmacionEsperadaRef = useRef<string | null>(null)
  /**
   * opId del lote importado que se espera. Los callbacks del WebSocket se
   * crean una sola vez, asi que leen esto de un ref y no de un estado.
   */
  const loteEsperadoRef = useRef<string | null>(null)
  const seleccionadaRef = useRef<string | null>(null)
  const clienteRef = useRef<ColabClient | null>(null)
  /**
   * El nombre con que aparece el bloqueo de una clase ("La esta editando ...").
   * Lleva un numero de PESTANA a proposito: el lock es de la sesion, no del
   * usuario, asi que dos pestanas de la misma persona son dos escritores
   * distintos (EC-01) y cada una tiene que poder distinguir quien la bloquea.
   */
  const userName = useRef<string>(`${usuario.fullName} #${Math.floor(Math.random() * 900 + 100)}`)
  const origenRelacionRef = useRef<string | null>(null)
  const tokenRef = useRef<string>(token)
  /**
   * Deshacer/rehacer de ESTA pestana (ADR-027). Vive en un ref porque los
   * callbacks del WebSocket se crean una sola vez; el contador solo avisa que
   * hay que redibujar los botones. App se monta de nuevo con cada diagrama
   * (key en Raiz), asi que las pilas nunca cruzan de un diagrama a otro.
   */
  const [, setRevisionHistorial] = useState(0)
  const historialRef = useRef<HistorialLocal | null>(null)
  if (!historialRef.current) historialRef.current = new HistorialLocal(100, () => setRevisionHistorial((n) => n + 1))
  const historial = historialRef.current

  /** Toda lectura REST va firmada: el snapshot, la Vista Logica y el generador. */
  const auth = () => ({ Authorization: 'Bearer ' + tokenRef.current })

  useEffect(() => {
    let vivo = true

    async function conectar() {
      // El diagrama y el usuario vienen de la navegacion y del login: ya no se
      // le pide un usuario de prueba a /dev/bootstrap.
      const auth = { headers: { Authorization: 'Bearer ' + token } }

      // El estado inicial sale del SNAPSHOT, no de re-aplicar todo el log:
      // entrar cuesta lo que mide el modelo, no lo que mide su historia.
      const respuesta = await fetch(`/api/v1/diagrams/${diagramId}`, auth)
      if (!vivo) return
      if (!respuesta.ok) {
        // 403: ya no es miembro (o el diagrama se elimino). Que la raiz lo
        // compruebe y lo lleve a donde corresponda, con su mensaje.
        if (respuesta.status === 403 || respuesta.status === 404) alRevalidarAcceso()
        else setError(`No se pudo abrir el diagrama (HTTP ${respuesta.status}).`)
        return
      }
      const snapshot: SnapshotDiagrama = await respuesta.json()
      setModelo(estadoDesdeSnapshot(snapshot))

      // El log solo se usa para el panel (las ultimas) y para resincronizar
      // tras un hueco en la secuencia (EC-08).
      const rHistorial = await fetch(
        `/api/v1/diagrams/${diagramId}/operations?sinceSeq=${Math.max(0, snapshot.currentSeq - 40)}`, auth)
      const historial: OperacionHistorica[] = rHistorial.ok ? await rHistorial.json() : []
      if (!vivo) return
      setOps(historial.map(historicaAAplicada).reverse())   // la mas reciente arriba

      const c = new ColabClient(diagramId, token, {
        onEstado: setConectado,
        // El servidor cerro el canal con un error: lo tipico es un SUBSCRIBE
        // negado porque ya no es miembro. La raiz lo comprueba por REST.
        onCanalRechazado: alRevalidarAcceso,
        onOp: (op) => {
          // Solo lo que el servidor acepto entra a la pila de deshacer.
          historialRef.current?.confirmar(op.opId)
          setOps((prev) => [op, ...prev].slice(0, 40))
          setModelo((prev) => aplicarOperacion(prev, op))
          if (tocaEsquema(op)) setRevisionEsquema((n) => n + 1)
          if (tocaDecisiones(op)) setRevisionDecisiones((n) => n + 1)
          if (op.opId === loteEsperadoRef.current) {
            loteEsperadoRef.current = null
            setEnvioImportacion({ tipo: 'aplicado', seq: op.seq })
          }
          if (op.opId === confirmacionEsperadaRef.current) {
            confirmacionEsperadaRef.current = null
            setConfirmando(false)
          }
        },
        // Antes esto solo mostraba "Resincronizando..." y no resincronizaba.
        // Ahora el cliente recupera lo perdido solo; aca solo se muestra.
        onResincronizando: setResincronizando,
        onLock: (l) =>
          setLocks((prev) => {
            const next = { ...prev }
            if (l.held && l.userName) next[l.elementId] = l.userName
            else delete next[l.elementId]
            return next
          }),
        onError: (r) => {
          // El rechazo de una importacion se muestra en su propia pantalla,
          // junto a lo que se estaba revisando, no en la barra general.
          // Primero: todo rechazo propio deja de estar "en viaje" en el historial.
          // Un deshacer rechazado dice QUE no se pudo deshacer, no solo por que.
          const deHistorial = historialRef.current?.rechazar(r)
          if (r.opId && r.opId === loteEsperadoRef.current) {
            loteEsperadoRef.current = null
            setEnvioImportacion({ tipo: 'rechazado', mensaje: `${r.reason}: ${r.message}` })
            return
          }
          if (deHistorial) { setError(deHistorial); return }
          // Lo mismo con la confirmacion de la base: el motivo va junto al
          // boton que se apreto (el esquema cambio, PostgreSQL lo rechaza).
          if (r.opId && r.opId === confirmacionEsperadaRef.current) {
            confirmacionEsperadaRef.current = null
            setConfirmando(false)
            setErrorConfirmacion(`No se confirmó: ${r.message}`)
            return
          }
          setError(`${r.reason}: ${r.message}`)
        },
      })
      // El seq del SNAPSHOT, no el del bootstrap: si entre los dos pedidos
      // hubo operaciones, el snapshot ya las trae y recuperarlas otra vez
      // las duplicaria.
      c.fijarSeq(snapshot.currentSeq)
      c.activar()
      clienteRef.current = c
    }

    conectar().catch(() =>
      setError('No se pudo contactar el backend. Corre: docker compose up -d && mvn spring-boot:run'))

    return () => { vivo = false; clienteRef.current?.desactivar() }
  }, [diagramId, token])

  /**
   * Las ambiguedades se recalculan cuando cambia el modelo (una composicion
   * nueva es una pregunta nueva) y cuando alguien decide en otra pantalla.
   */
  useEffect(() => {
    if (!diagramId) return
    let vivo = true
    fetch(`/api/v1/diagrams/${diagramId}/ambiguities`, { headers: auth() })
      .then((r) => r.json())
      .then((a) => { if (vivo) { setAmbiguedades(a); setErrorAmbiguedades(null) } })
      .catch(() => { if (vivo) setErrorAmbiguedades('No se pudieron revisar las ambiguedades.') })
    return () => { vivo = false }
  }, [diagramId, revisionEsquema, revisionDecisiones])

  useEffect(() => {
    const alTeclear = (e: KeyboardEvent) => { if (e.key === 'Escape') cancelarRelacion() }
    window.addEventListener('keydown', alTeclear)
    return () => window.removeEventListener('keydown', alTeclear)
  }, [])

  // La Vista Logica se deriva en el servidor: misma derivacion que despues
  // usara el generador. Se vuelve a pedir cuando el modelo cambia, para que
  // no muestre un esquema viejo mientras otro edita en la vista de Clases.
  useEffect(() => {
    if (vista !== 'LOGICAL' || !diagramId) return
    let vivo = true
    fetch(`/api/v1/diagrams/${diagramId}/logical`, { headers: auth() })
      .then((r) => r.json())
      .then((m) => { if (vivo) setLogica(m) })
      .catch(() => { if (vivo) setError('No se pudo derivar la vista logica.') })
    return () => { vivo = false }
  }, [vista, diagramId, revisionEsquema, revisionDecisiones])

  // El lienzo y sus herramientas son de la vista de Clases. La Logica es de
  // solo lectura; la Fisica se edita tabla por tabla, por el mismo bus y con
  // el mismo lock por clase (ADR-032).
  const editable = vista === 'CLASS'
  const puedeEditarModelo = !soloLectura
  /**
   * Se puede editar cuando la vista es interactiva Y el rol lo permite. Un
   * Observador ve todo --tambien la vista de Clases, y puede seleccionar una
   * clase para leerla--, pero no tiene herramientas, ni arrastre, ni conector.
   */
  const puedeEditar = editable && puedeEditarModelo
  useEffect(() => {
    // Cambiar de vista suelta lo que estaba tomado. Sin esto, irse de Clases
    // deja la clase bloqueada y el latido le renueva el lease para siempre:
    // nadie mas la puede tocar mientras vos mirás otra cosa.
    cancelarRelacion()
    seleccionar(null)
    setErrorConfirmacion(null)
  }, [vista])

  // El rol cambio con el diagrama abierto (aviso privado del servidor).
  useEffect(() => {
    if (soloLectura) {
      // Pasaron a Observador: la herramienta de relacion se apaga. El servidor ya
      // le solto los locks; la clase que tenia abierta queda en solo lectura.
      cancelarRelacion()
      return
    }
    // Volvio a poder escribir con una clase abierta: retoma su lock. Sin esto
    // editaria sin exclusion mutua (nadie lo tiene, asi que el servidor no lo frena).
    if (seleccionadaRef.current) clienteRef.current?.bloquear(seleccionadaRef.current, userName.current)
  }, [soloLectura])

  /**
   * La Vista Fisica se pide al entrar y cada vez que el modelo cambia, igual
   * que la Logica: son dos lecturas de la misma derivacion (ADR-003).
   */
  useEffect(() => {
    if (vista !== 'PHYSICAL' || !diagramId) return
    let vigente = true
    fetch(`/api/v1/diagrams/${diagramId}/physical`, { headers: auth() })
      .then((r) => (r.ok ? r.json() : null))
      .then((e) => { if (vigente) setFisica(e) })
      .catch(() => { if (vigente) setFisica(null) })
    return () => { vigente = false }
  }, [vista, diagramId, revisionEsquema, revisionDecisiones])

  /**
   * El inverso se calcula ACA, antes de enviar: cuando vuelva la operacion, el
   * valor anterior ya no esta en el modelo. Toda edicion propia se registra,
   * aunque no se sepa deshacer: al confirmarse invalida el rehacer.
   *
   * @param deshacible para lo que el modelo del canvas no sabe (una decision)
   */
  const enviar = (type: CommandType, payload: Record<string, unknown>,
                  deshacible?: EntradaSinConfirmar | null) => {
    const cliente = clienteRef.current
    if (!cliente || !diagramId) return
    if (deshacible === undefined) deshacible = entradaDe(modelo, type, payload)
    const opId = uuid()
    // Una edicion en el lugar sin inverso es un cambio que no cambia nada (un
    // blur en el panel): no se lleva el rehacer.
    historial.enviado(opId, deshacible, deshacible !== null || !editaEnSitio(type))
    cliente.enviarComando({
      opId, diagramId, baseSeq: cliente.seqActual(), type, origin: 'MOUSE', payload,
    })
  }

  /**
   * Ctrl+Z (ADR-027): el mismo comando con el valor anterior, por el bus, con
   * seq nuevo. Sale como MOUSE porque `origin` es la modalidad del pedido; que
   * es un undo lo dice la marca `deshace` del payload.
   */
  const deshacerOrehacer = (sentido: 'deshacer' | 'rehacer') => {
    const cliente = clienteRef.current
    if (!cliente || !diagramId || !cliente.estaConectado() || soloLecturaRef.current) return
    const c = sentido === 'deshacer' ? historial.deshacer(uuid()) : historial.rehacer(uuid())
    if (!c) return
    setError(null)
    cliente.enviarComando({
      opId: c.opId, diagramId, baseSeq: cliente.seqActual(), type: c.type, origin: 'MOUSE', payload: c.payload,
    })
  }
  const deshacerRef = useRef(deshacerOrehacer)
  deshacerRef.current = deshacerOrehacer

  /**
   * Los mismos atajos que draw.io. Dentro de un campo de texto no se tocan:
   * ahi Ctrl+Z deshace lo tipeado, que todavia no es ninguna operacion.
   */
  useEffect(() => {
    const alTeclear = (e: KeyboardEvent) => {
      if (!(e.ctrlKey || e.metaKey) || e.altKey) return
      const t = e.target as HTMLElement | null
      if (t && (t.isContentEditable || ['INPUT', 'TEXTAREA', 'SELECT'].includes(t.tagName))) return
      const tecla = e.key.toLowerCase()
      if (tecla === 'z' && !e.shiftKey) { e.preventDefault(); deshacerRef.current('deshacer') }
      else if (tecla === 'y' || (tecla === 'z' && e.shiftKey)) { e.preventDefault(); deshacerRef.current('rehacer') }
    }
    window.addEventListener('keydown', alTeclear)
    return () => window.removeEventListener('keydown', alTeclear)
  }, [])

  /** Pasar a Observador: lo recordado ya no se va a poder aplicar. */
  useEffect(() => { if (soloLectura) historial.vaciar() }, [soloLectura])

  /**
   * Ejecutar el DDL en PostgreSQL y deshacerlo (ADR-032). Es una lectura: no
   * toca el modelo ni la base, asi que va por REST como la Vista Fisica.
   */
  const verificarEsquema = async (): Promise<VerificacionDelEsquema> => {
    const r = await fetch(`/api/v1/diagrams/${diagramId}/physical/verificacion`,
      { method: 'POST', headers: auth() })
    if (!r.ok) throw new Error(String(r.status))
    return r.json()
  }

  /**
   * Confirmar SI es una escritura: queda para todos, con autor. Va por el bus
   * como cualquier otra (ADR-002), con el hash de lo que se reviso: si el
   * esquema cambio mientras tanto, el servidor no confirma.
   */
  const confirmarEsquema = (hash: string) => {
    const cliente = clienteRef.current
    if (!cliente || !diagramId) return
    const opId = uuid()
    confirmacionEsperadaRef.current = opId
    setConfirmando(true)
    setErrorConfirmacion(null)
    cliente.enviarComando({
      opId, diagramId, baseSeq: cliente.seqActual(), type: 'CONFIRM_SCHEMA', origin: 'MOUSE',
      payload: { ddlHash: hash },
    })
  }

  /** Un cambio hecho en una tabla: un comando del catalogo, por el mismo bus que el mouse. */
  const enviarCambio = (c: Cambio) => enviar(c.type, c.payload)

  /**
   * Leer el archivo es REST y no escribe nada (el backend solo propone).
   * Lo confirmado vuelve por el bus como un BATCH: ver enviarLote.
   */
  const pedirPropuesta = async (archivo: File): Promise<PropuestaDeImportacion> => {
    const form = new FormData()
    form.append('archivo', archivo)
    const r = await fetch(`/api/v1/diagrams/${diagramId}/importaciones/propuesta`,
      { method: 'POST', headers: auth(), body: form })
    if (r.ok) return r.json()
    const cuerpo = await r.json().catch(() => null)
    throw new Error(cuerpo?.mensaje ?? `No se pudo leer el archivo (HTTP ${r.status}).`)
  }

  /**
   * Toda la importacion es UNA operacion (ADR-016): un seq, una transaccion.
   * Si un cambio del medio falla, el servidor no aplica ninguno.
   */
  const enviarLote = (plan: Plan, descripcion: string) => {
    const cliente = clienteRef.current
    if (!cliente || !diagramId) return
    const opId = uuid()
    loteEsperadoRef.current = opId
    const payload = { descripcion, comandos: plan.comandos }
    // Una importacion entera se deshace con un Ctrl+Z (ADR-030): un lote inverso.
    historial.enviado(opId, entradaDe(modelo, 'BATCH', payload))
    setEnvioImportacion({ tipo: 'enviando' })
    cliente.enviarComando({
      opId, diagramId, baseSeq: cliente.seqActual(), type: 'BATCH', origin: 'XMI_IMPORT', payload,
    })
  }

  const enviarLoteIA = (comandos: Plan['comandos'], descripcion: string) => {
    const cliente = clienteRef.current
    if (!cliente || !diagramId || !comandos.length) return
    const opId = uuid()
    let nuevas = 0
    const comandosConLayout = comandos.map((comando) => {
      if (comando.type !== 'CREATE_CLASS') return comando
      const indice = modelo.classes.length + nuevas++
      return {
        ...comando,
        payload: {
          ...comando.payload,
          x: 160 + (indice % 4) * 220,
          y: 120 + Math.floor(indice / 4) * 180,
        },
      }
    })
    const payload = { descripcion, comandos: comandosConLayout }
    historial.enviado(opId, entradaDe(modelo, 'BATCH', payload))
    cliente.enviarComando({
      opId, diagramId, baseSeq: cliente.seqActual(), type: 'BATCH', origin: 'TEXT', payload,
    })
  }

  const cerrarImportacion = () => {
    loteEsperadoRef.current = null
    setEnvioImportacion(null)
    setImportando(false)
  }

  const crearClase = (nombre: string, estereotipo?: string) => {
    const n = modelo.classes.length
    enviar('CREATE_CLASS', {
      classId: uuid(), name: nombre, stereotype: estereotipo ?? null,
      x: 160 + (n % 4) * 220, y: 120 + Math.floor(n / 4) * 180,
    })
  }

  /**
   * Mover es nivel 3 (ADR-004): last-writer-wins, sin lease. Por eso se puede
   * arrastrar una clase que otro tiene abierta sin pelearle el lock.
   */
  const mover = (elementId: string, x: number, y: number) => {
    enviar('MOVE_ELEMENT', { elementId, elementKind: 'CLASS', x, y })
  }

  /** Mismo comando que mover una caja: los quiebres son geometria (ADR-014). */
  const moverQuiebres = (relationId: string, puntos: { x: number; y: number }[]) => {
    enviar('MOVE_ELEMENT', { elementId: relationId, elementKind: 'RELATION', waypoints: puntos })
  }

  /**
   * Generar NO es un comando (ADR-014): no escribe nada, no toma lock y no
   * entra al log. Es una lectura del modelo por REST, como la Vista Logica.
   *
   * Va en dos pasos a proposito: primero el resumen --que dice cuantas
   * entidades salen y que quedo sin soportar-- y recien despues el zip. Un
   * archivo descargado no tiene forma de advertir nada a tiempo.
   */
  /**
   * Leer un diagrama de una imagen (ADR-016).
   *
   * La deteccion es una LECTURA: no toma lock, no entra al log y no agrega
   * ningun comando al catalogo cerrado. Lo que devuelve son candidatos, y
   * recien lo que el usuario confirme se convierte en escritura.
   */
  const leerImagen = async (archivo: File) => {
    if (!diagramId) return
    setLeyendoImagen(true)
    setPropuestaVision(null)
    setErrorVision(null)
    try {
      setPropuestaVision(await detectarDesdeImagen(diagramId, archivo, tokenRef.current))
    } catch (e) {
      setErrorVision(e instanceof Error ? e.message : 'No se pudo leer la imagen.')
    }
  }

  /**
   * El aterrizaje: un solo BATCH, por el mismo bus que el mouse (ADR-002).
   *
   * Se vuelve a traducir aca y no se reusa la vista previa del panel: esa se
   * calcula con ids de relleno para poder contar los pasos sin gastar UUID.
   */
  const aplicarVision = (confirmada: DeteccionConfirmada) => {
    const cliente = clienteRef.current
    if (!cliente || !diagramId) return
    const { comando, descartes } = traducir(confirmada, diagramId, cliente.seqActual())
    if (!comando) {
      setErrorVision(descartes.join(' '))
      return
    }
    setAplicandoVision(true)
    historial.enviado(comando.opId, entradaDe(modelo, comando.type, comando.payload))
    cliente.enviarComando(comando)
    // El BATCH vuelve por el WebSocket como cualquier operacion y el canvas lo
    // pliega solo: no hay que tocar el estado a mano.
    setAplicandoVision(false)
    setLeyendoImagen(false)
    setPropuestaVision(null)
  }

  /**
   * Exportar es una LECTURA (ADR-022): no pasa por el bus ni toma lock, asi
   * que tambien la hace un Observador. Se pide una vez; guardar reusa ese
   * mismo archivo, que es el que mostro sus perdidas.
   */
  const abrirExportacion = async (perfil: PerfilXmi = perfilXmi) => {
    if (!diagramId) return
    setPerfilXmi(perfil)
    setExportacion(null)
    setErrorExportacion(null)
    try {
      setExportacion(await exportarXmi(diagramId, diagrama.nombre, auth(), perfil))
    } catch (e) {
      setErrorExportacion(e instanceof Error ? e.message : 'No se pudo exportar.')
    }
  }

  /**
   * La N:M se convierte en una clase intermedia dibujada en el canvas.
   *
   * Un solo BATCH (ADR-016): crear la clase, las dos relaciones 1:N hacia ella y
   * borrar la N:M. Todo o nada, porque a mitad de camino el modelo quedaria sin
   * la relacion original Y sin la clase que la reemplaza.
   *
   * La posicion se calcula ACA y no en el panel porque el layout vive en este
   * estado: la caja nueva va en el medio de las dos clases y mas abajo, que es
   * donde la pondria cualquiera a mano.
   */
  const reificar = (relacion: UmlRelationship, nombre: string,
                    pkCompuesta: boolean): string | null => {
    const cliente = clienteRef.current
    if (!cliente || !diagramId) return 'Todavia no hay conexion.'

    const a = modelo.layout[relacion.sourceClassId]
    const b = modelo.layout[relacion.targetClassId]
    const medioX = Math.round(((a?.x ?? 120) + (b?.x ?? 420)) / 2)
    const medioY = Math.round(((a?.y ?? 120) + (b?.y ?? 120)) / 2)

    const { comando, motivo, classId } = construirReificacion({
      relacion,
      origen: modelo.classes.find((c) => c.id === relacion.sourceClassId),
      destino: modelo.classes.find((c) => c.id === relacion.targetClassId),
      nombre,
      pkCompuesta,
      x: medioX,
      y: medioY + 170,
      diagramId,
      baseSeq: cliente.seqActual(),
      nombresUsados: modelo.classes.map((c) => c.name),
      nuevoId: uuid,
    })
    if (!comando) return motivo

    cliente.enviarComando(comando)
    // El lote vuelve por el WebSocket y el canvas lo pliega solo. Se deja
    // seleccionada la clase nueva: es lo que el usuario va a querer tocar
    // enseguida, porque la reifico para ponerle atributos.
    setRelacionSeleccionada(null)
    if (classId) setSeleccionada(classId)
    return null
  }

  const abrirGeneracion = async () => {
    if (!diagramId) return
    setGenerando(true)
    setResumen(null)
    setErrorGeneracion(null)
    try {
      const r = await fetch(`/api/v1/diagrams/${diagramId}/codegen/resumen`, { headers: auth() })
      if (!r.ok) throw new Error(String(r.status))
      setResumen(await r.json())
    } catch {
      setErrorGeneracion('No se pudo derivar el proyecto. Revisa que el backend siga arriba.')
    }
  }

  /**
   * El zip viaja con el JWT en la cabecera, asi que no alcanza un enlace:
   * hay que traerlo con fetch y soltarlo como blob.
   *
   * Con la base confirmada se pide el zip DE ESA base: si alguien edito entre
   * la confirmacion y el clic, el servidor responde 409 en vez de entregar
   * otra cosa (ADR-032).
   */
  const descargarZip = async () => {
    if (!diagramId || !resumen) return
    setDescargando(true)
    try {
      const confirmada = resumen.confirmacion?.vigente ? `?esquema=${resumen.hashEsquema}` : ''
      const r = await fetch(`/api/v1/diagrams/${diagramId}/codegen${confirmada}`, { headers: auth() })
      if (r.status === 409) {
        setErrorGeneracion('La base cambió desde que se confirmó: revisala otra vez en la Vista Física.')
        return
      }
      if (!r.ok) throw new Error(String(r.status))
      const url = URL.createObjectURL(await r.blob())
      const a = document.createElement('a')
      a.href = url
      a.download = resumen.archivo
      // Firefox no dispara la descarga de un enlace que no esta en el documento.
      document.body.appendChild(a)
      a.click()
      a.remove()
      // El revoke va diferido: hacerlo en la misma vuelta del bucle de eventos
      // le saca el blob de abajo a una descarga que recien arranca.
      setTimeout(() => URL.revokeObjectURL(url), 60_000)
      setGenerando(false)
    } catch {
      setErrorGeneracion('La descarga fallo. Revisa que el backend siga arriba.')
    } finally {
      setDescargando(false)
    }
  }

  const aplicarCorreccionesPreGeneracion = () => {
    if (!resumen?.correcciones?.length) return
    enviarLoteIA(resumen.correcciones.map((c) => ({
      type: 'UPDATE_RELATION' as const,
      payload: { relationId: c.relationId, name: c.name },
    })), 'Correcciones seguras previas a generar Spring Boot')
    setGenerando(false)
  }

  /**
   * Contestar una ambiguedad es una escritura: entra por el bus como todas
   * (ADR-002), asi que se secuencia, se difunde y queda con su autor en el log.
   */
  const elegirLectura = (a: Ambiguedad, chosen: Lectura) => {
    if (a.decidida && a.elegida === chosen) return
    // `elegida` es la lectura que rige, decidida o por defecto: a esa vuelve.
    enviar('SET_MODELING_DECISION', { elementId: a.elementId, ambiguityKind: a.tipo, chosen },
      entradaDeDecision(a.elementId, a.tipo, a.elegida, chosen, `decidir "${a.titulo}"`))
  }

  const iniciarRelacion = () => setHerramientaRelacion(true)
  const cancelarRelacion = () => setHerramientaRelacion(false)

  const alSeleccionar = (elementId: string) => {
    setRelacionSeleccionada(null)
    seleccionar(elementId)
  }

  /** Soltar el conector sobre otra clase: ahi nace la relacion. */
  const conectar = (origenId: string, destinoId: string) => {
    enviar('CREATE_RELATION', {
      relationId: uuid(), sourceClassId: origenId, targetClassId: destinoId, kind: tipoRelacion,
    })
    setHerramientaRelacion(false)
  }

  /**
   * Una relacion es una entidad con su propio panel: se toma SU lease, igual
   * que el de una clase (ADR-028). Antes no se tomaba ninguno, asi que dos
   * sesiones editaban la misma multiplicidad a la vez sin enterarse.
   */
  const alSeleccionarRelacion = (relationId: string) => {
    seleccionar(relationId)
    setRelacionSeleccionada(relationId)
  }

  const cerrarRelacion = () => {
    seleccionar(null)
    setRelacionSeleccionada(null)
  }

  /**
   * El lease se mantiene MIENTRAS la clase (o la relacion) esta seleccionada, no unos
   * segundos sueltos: editar lleva mas que eso. La renovacion por latido es
   * la que pide ADR-004; sin ella el lease caduca a los 30s en plena edicion.
   */
  const seleccionar = (elementId: string | null) => {
    const anterior = seleccionadaRef.current
    // Un Observador puede seleccionar para LEER, pero no toma ni suelta locks:
    // tomar un lock es prepararse para escribir, y el servidor tampoco se lo da.
    if (anterior && anterior !== elementId && !soloLecturaRef.current) {
      clienteRef.current?.desbloquear(anterior, userName.current)
    }
    seleccionadaRef.current = elementId
    setSeleccionada(elementId)
    if (elementId && !soloLecturaRef.current) clienteRef.current?.bloquear(elementId, userName.current)
  }

  useEffect(() => {
    if (!seleccionada || soloLectura) return
    const latido = setInterval(
      () => clienteRef.current?.bloquear(seleccionada, userName.current), 10_000)
    return () => clearInterval(latido)
  }, [seleccionada, soloLectura])

  // Si lo seleccionado desaparece (lo borro otro), se suelta solo.
  useEffect(() => {
    if (seleccionada && !modelo.classes.some((c) => c.id === seleccionada)
      && !modelo.relationships.some((r) => r.id === seleccionada)) seleccionar(null)
  }, [modelo, seleccionada])

  const claseSeleccionada = modelo.classes.find((c) => c.id === seleccionada) ?? null
  const relacionElegida = modelo.relationships.find((r) => r.id === relacionSeleccionada) ?? null
  const duenioDelLock = seleccionada ? locks[seleccionada] : undefined
  const bloqueadaPorOtro = duenioDelLock && duenioDelLock !== userName.current ? duenioDelLock : null

  return (
    <div className="app">
      <header className="barra">
        <strong>ColabModel</strong>
        <nav className="migas" aria-label="Ubicación">
          <a href="#/proyectos">Mis proyectos</a>
          <span className="migas-sep">›</span>
          <a href={`#/p/${proyecto.id}`}>{proyecto.nombre}</a>
          <span className="migas-sep">›</span>
          <span className="migas-actual">{diagrama.nombre}</span>
        </nav>
        <span className={!conectado ? 'pill off' : resincronizando ? 'pill sync' : 'pill ok'}>
          <span className="dot" />{' '}
          {!conectado ? 'Sin conexion' : resincronizando ? 'Recuperando cambios...' : 'En directo'}
        </span>
        <span className={'insignia insignia-' + rol} title="Tu rol en este proyecto">{ETIQUETA_DE_ROL[rol]}</span>
        <span className="usuario">{usuario.fullName}</span>
        <button onClick={alVolver}>Volver al proyecto</button>
        <button onClick={alCerrarSesion}>Cerrar sesión</button>
      </header>

      {soloLectura && (
        <div className="aviso-solo-lectura" role="status">
          <strong>Solo lectura.</strong> Estás viendo este diagrama como {ETIQUETA_DE_ROL[rol]}: no podés
          editarlo, pero sí cambiar de vista y descargar el código.
        </div>
      )}

      <Toolbar
        disabled={!conectado || !diagramId || !puedeEditar}
        puedeGenerar={conectado && !!diagramId}
        onGenerar={abrirGeneracion}
        ambiguedadesPendientes={ambiguedades?.filter((a) => !a.decidida).length ?? 0}
        onAmbiguedades={() => setVerAmbiguedades(true)}
        onImportar={() => setImportando(true)}
        onExportar={() => { void abrirExportacion() }}
        tipoRelacion={tipoRelacion}
        onCambiarTipoRelacion={setTipoRelacion}
        modoRelacion={herramientaRelacion}
        onCrearClase={crearClase}
        onIniciarRelacion={iniciarRelacion}
        onCancelarRelacion={cancelarRelacion}
        onImagen={leerImagen}
        proximoDeshacer={historial.proximoDeshacer()}
        proximoRehacer={historial.proximoRehacer()}
        onDeshacer={() => deshacerOrehacer('deshacer')}
        onRehacer={() => deshacerOrehacer('rehacer')}
      />

      <nav className="pestanas">
        {VISTAS.map((v) => (
          <button key={v.id}
                  className={vista === v.id ? 'activa' : ''}
                  onClick={() => setVista(v.id)}
                  title={v.nota}>
            {v.label}
          </button>
        ))}
        <span className="nota">{VISTAS.find((v) => v.id === vista)?.nota}</span>
        {vista !== 'AI' && <label className="notacion" title="Solo cambia como se MUESTRAN: lo guardado no se toca">
          Cardinalidad
          <select value={notacionCardinalidad}
                  onChange={(e) => setNotacionCardinalidad(e.target.value as NotacionCardinalidad)}>
            <option value="UML">UML (0..*)</option>
            <option value="CHEN">Chen (0,N)</option>
          </select>
        </label>}
      </nav>

      {error && <div className="error" onClick={() => setError(null)}>{error}</div>}

      {verAmbiguedades && (
        <PanelAmbiguedades
          ambiguedades={ambiguedades}
          error={errorAmbiguedades}
          soloLectura={!puedeEditar}
          onElegir={elegirLectura}
          onCerrar={() => setVerAmbiguedades(false)}
        />
      )}

      {importando && (
        <PanelImportacion
          modelo={modelo}
          pedirPropuesta={pedirPropuesta}
          onAplicar={enviarLote}
          envio={envioImportacion}
          puedeAplicar={conectado && !!diagramId && puedeEditar}
          onCerrar={cerrarImportacion}
        />
      )}

      {leyendoImagen && (
        <PanelVision
          propuesta={propuestaVision}
          error={errorVision}
          aplicando={aplicandoVision}
          onAplicar={aplicarVision}
          onCerrar={() => { setLeyendoImagen(false); setPropuestaVision(null) }}
        />
      )}

      {exportacion !== undefined && (
        <PanelExportacion
          exportado={exportacion}
          error={errorExportacion}
          perfil={perfilXmi}
          onPerfil={(p) => { void abrirExportacion(p) }}
          onGuardar={() => { if (exportacion) guardar(exportacion); setExportacion(undefined) }}
          onCerrar={() => setExportacion(undefined)}
        />
      )}

      {generando && (
        <PanelGeneracion
          resumen={resumen}
          error={errorGeneracion}
          descargando={descargando}
          onDescargar={descargarZip}
          onAplicarCorrecciones={aplicarCorreccionesPreGeneracion}
          onIrAFisica={() => { setGenerando(false); setVista('PHYSICAL') }}
          onCerrar={() => setGenerando(false)}
        />
      )}

      <main className="cuerpo">
        <div className={puedeEditar ? 'lienzo-wrap' : 'lienzo-wrap solo-lectura'}>
          {vista === 'AI' ? (
            <VistaIA diagramId={diagramId} token={token} modelo={modelo} conectado={conectado}
                     puedeEditar={puedeEditarModelo} usuario={usuario.fullName} onAplicar={enviarLoteIA} />
          ) : vista === 'LOGICAL' ? (
            <VistaLogica modelo={logica} />
          ) : vista === 'PHYSICAL' ? (
            <VistaFisica
              diagramId={diagramId!}
              esquema={fisica}
              clases={modelo.classes}
              relaciones={modelo.relationships}
              layout={modelo.layout}
              locks={locks}
              yo={userName.current}
              puedeEditar={conectado && !!diagramId}
              onSeleccionarClase={seleccionar}
              onCambio={enviarCambio}
              onVerificar={verificarEsquema}
              onConfirmar={confirmarEsquema}
              confirmando={confirmando}
              errorConfirmacion={errorConfirmacion}
            />
          ) : (
            <Canvas view={vista} classes={modelo.classes} relationships={modelo.relationships}
                    layout={modelo.layout} locks={locks}
                    // Seleccionar es leer: un Observador puede. Mover, conectar y
                    // acomodar lineas son escritura: solo con permiso.
                    onSelect={editable ? alSeleccionar : undefined}
                    onMove={puedeEditar ? mover : undefined}
                    relacionSeleccionada={relacionSeleccionada}
                    onSelectRelation={editable ? alSeleccionarRelacion : undefined}
                    modoConector={puedeEditar && herramientaRelacion}
                    onConectar={puedeEditar ? conectar : undefined}
                    notacionCardinalidad={notacionCardinalidad}
                    onMoverQuiebres={puedeEditar ? moverQuiebres : undefined} />
          )}
        </div>

        <aside className="panel">
          {editable && relacionElegida && (
            <PanelRelacion
              relacion={relacionElegida}
              origen={modelo.classes.find((c) => c.id === relacionElegida.sourceClassId)}
              destino={modelo.classes.find((c) => c.id === relacionElegida.targetClassId)}
              soloLectura={soloLectura}
              bloqueadaPorOtro={bloqueadaPorOtro}
              onActualizar={(cambios) =>
                enviar('UPDATE_RELATION', { relationId: relacionElegida.id, ...cambios })}
              onBorrar={() => {
                enviar('DELETE_RELATION', { relationId: relacionElegida.id })
                cerrarRelacion()
              }}
              onReificar={(nombre, pkCompuesta) =>
                reificar(relacionElegida, nombre, pkCompuesta)}
              onCerrar={cerrarRelacion}
            />
          )}

          {editable && claseSeleccionada && (
            <PanelPropiedades
              clase={claseSeleccionada}
              esRaizDeJerarquia={
                // Tiene hijas y no tiene madre: es donde se elige la estrategia.
                modelo.relationships.some((r) => r.kind === 'GENERALIZATION'
                  && r.targetClassId === claseSeleccionada.id && r.sourceClassId !== r.targetClassId)
                && !modelo.relationships.some((r) => r.kind === 'GENERALIZATION'
                  && r.sourceClassId === claseSeleccionada.id && r.sourceClassId !== r.targetClassId)}
              onCambiarEstrategia={(strategy) => enviar('SET_ER_FLAG', {
                targetType: 'CLASS', targetId: claseSeleccionada.id,
                flag: 'inheritanceStrategy', strategy,
              })}
              enumeraciones={modelo.classes.filter(esEnumeracion).map((c) => c.name)
                .filter((n) => n !== claseSeleccionada.name)}
              embebibles={modelo.classes.filter(esEmbebible).map((c) => c.name)
                .filter((n) => n !== claseSeleccionada.name)}
              bloqueadaPorOtro={bloqueadaPorOtro}
              soloLecturaPorRol={soloLectura}
              onRenombrar={(nombre) => enviar('RENAME_CLASS', { classId: claseSeleccionada.id, name: nombre })}
              onAgregarAtributo={(nombre, dataType, extra) => enviar('ADD_ATTRIBUTE', {
                attributeId: uuid(), classId: claseSeleccionada.id, name: nombre, dataType,
                ordinal: claseSeleccionada.attributes.length, ...extra,
              })}
              sugerirAtributo={async (nombre) => {
                const r = await fetch(`/api/v1/diagrams/${diagramId}/sugerencias/atributo?claseId=`
                  + `${claseSeleccionada.id}&nombre=${encodeURIComponent(nombre)}`, { headers: auth() })
                return r.ok ? r.json() : null
              }}
              onRelacionarCon={(destinoId) => {
                // idMaterial en Prestamo: muchos prestamos, un material. La FK la
                // deriva la Regla 3 del lado "muchos".
                enviar('CREATE_RELATION', {
                  relationId: uuid(), sourceClassId: claseSeleccionada.id, targetClassId: destinoId,
                  kind: 'ASSOCIATION', sourceMultiplicity: '0..*', targetMultiplicity: '1',
                })
              }}
              onActualizarAtributo={(attributeId, cambios) =>
                enviar('UPDATE_ATTRIBUTE', { attributeId, ...cambios })}
              onQuitarAtributo={(attributeId) => enviar('REMOVE_ATTRIBUTE', { attributeId })}
              onAgregarMetodo={(m: NuevoMetodo) => enviar('ADD_METHOD', {
                methodId: uuid(), classId: claseSeleccionada.id, ...m,
                ordinal: claseSeleccionada.methods.length,
              })}
              onQuitarMetodo={(methodId) => enviar('REMOVE_METHOD', { methodId })}
              onBorrarClase={() => {
                enviar('DELETE_CLASS', { classId: claseSeleccionada.id })
                seleccionar(null)
              }}
              onCerrar={() => seleccionar(null)}
            />
          )}

          <h3>Operaciones</h3>
          <p className="tenue">
            Todo cambio entra aqui con su <code>seq</code>, venga del mouse, la voz,
            el texto, una foto o un XMI.
          </p>
          {ops.length === 0 && <p className="tenue">Sin operaciones todavia.</p>}
          <ul className="ops">
            {ops.map((o) => (
              <li key={o.opId}>
                <span className="seq">#{o.seq}</span>
                <span className="tipo">
                  {/* Un undo es una operacion mas del log; la marca lo delata. */}
                  {o.payload.deshace ? <span title={'Deshace la operacion ' + String(o.payload.deshace)}>↶ </span> : null}
                  {o.type}
                  {o.type === 'BATCH' && ` (${((o.payload.comandos as unknown[]) ?? []).length})`}
                </span>
                <span className={'origen o-' + o.origin}>{o.origin}</span>
              </li>
            ))}
          </ul>
        </aside>
      </main>
    </div>
  )
}
