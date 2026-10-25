import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { esEnumeracion, type ElementLayout, type UmlClass, type UmlRelationship } from '../contracts/command'
import { DiagramaFisico } from './DiagramaFisico'
import { EditorDeTabla } from './EditorDeTabla'
import { diferencias, resumir } from './diferencias'
import type { Cambio } from './edicion'
import type { EsquemaFisico, TablaFisica, VerificacionDelEsquema } from './esquema'

export type {
  ColumnaFisica, ConfirmacionEsquema, EsquemaFisico, TablaFisica, VerificacionDelEsquema,
} from './esquema'

/**
 * Las líneas del script que hablan de UNA tabla.
 *
 * Se recorta EN EL CLIENTE, que ya tiene el script entero, en vez de pedirlo
 * al servidor. Mandarlo por tabla duplicaba el payload —medido: 26 KB para 17
 * tablas, y la respuesta pasaba de ~60 ms a ~120 ms— para transmitir algo que
 * ya estaba viajando. El costo de esta vista no es derivar el esquema: es
 * serializarlo.
 *
 * Se recorta en vez de volver a generar, así el fragmento no puede diferir del
 * archivo que se descarga ni del que sale en el zip.
 */
export function ddlDeTabla(script: string, tabla: string): string {
  const salida: string[] = []
  let dentroDelCreate = false

  for (const linea of script.split('\n')) {
    if (linea.startsWith(`CREATE TABLE ${tabla} (`)) {
      dentroDelCreate = true
      salida.push(linea)
      continue
    }
    if (dentroDelCreate) {
      salida.push(linea)
      if (linea.startsWith(');')) dentroDelCreate = false
      continue
    }
    // Las FK y los índices van al final del script, fuera del CREATE.
    if (linea.startsWith(`ALTER TABLE ${tabla} `)
        || (linea.startsWith('CREATE INDEX ') && linea.includes(` ON ${tabla} `))) {
      salida.push(linea)
    }
  }
  return salida.join('\n').trim()
}

/** Tres maneras de mirar el mismo esquema. */
type Modo = 'diagrama' | 'tablas' | 'script'

interface Props {
  diagramId: string
  esquema: EsquemaFisico | null
  clases: UmlClass[]
  relaciones: UmlRelationship[]
  /** La geometria del diagrama de Clases: el diagrama fisico la sigue. */
  layout: Record<string, ElementLayout>
  /** Con cuanto espacio arranca: una pantalla chica no tiene lugar para el diagrama. */
  modoInicial?: Modo
  /** elementId -> quien lo tiene tomado (nivel 2, ADR-004). */
  locks: Record<string, string>
  /** El nombre de esta sesion: el lock propio no bloquea. */
  yo: string
  puedeEditar: boolean
  /** Toma (o suelta, con null) el lock de la clase de la tabla que se edita. */
  onSeleccionarClase: (claseId: string | null) => void
  onCambio: (c: Cambio) => void
  onVerificar: () => Promise<VerificacionDelEsquema>
  onConfirmar: (hash: string) => void
  confirmando: boolean
  errorConfirmacion: string | null
}

/**
 * Vista Física: el esquema de PostgreSQL que sale del modelo, EDITABLE (ADR-032).
 *
 * PENSADA PARA MUCHAS TABLAS, que es el caso real. Un diagrama de 20 entidades
 * da un script de varios cientos de líneas, y volcarlo en un `<pre>` es
 * técnicamente correcto e inútil: nadie encuentra ahí en qué tabla está
 * `fecha_prestamo` ni quién referencia a `cliente`. Por eso la pantalla es un
 * índice navegable y el script queda como una pestaña más.
 *
 * Se edita A TRAVÉS del modelo conceptual: cada cambio en una tabla es un
 * comando sobre la clase, el atributo o la relación que la produjo, por el
 * mismo bus que el mouse en Clases. No hay un segundo esquema que mantener.
 *
 * Y antes de generar, la base se CONFIRMA: se ejecuta en PostgreSQL de verdad
 * (y se deshace), y lo confirmado queda con su huella. Si alguien edita
 * después, la pantalla lo dice y muestra qué cambió.
 */
export function VistaFisica({
  diagramId, esquema, clases, relaciones, layout, modoInicial = 'diagrama', locks, yo, puedeEditar,
  onSeleccionarClase, onCambio, onVerificar, onConfirmar, confirmando, errorConfirmacion,
}: Props) {
  const [filtro, setFiltro] = useState('')
  const [seleccionada, setSeleccionada] = useState<string | null>(null)
  const [modo, setModo] = useState<Modo>(modoInicial)
  const verScript = modo === 'script'
  const [copiado, setCopiado] = useState(false)
  const [verificacion, setVerificacion] = useState<VerificacionDelEsquema | null>(null)
  const [verificando, setVerificando] = useState(false)
  const [errorVerificacion, setErrorVerificacion] = useState<string | null>(null)
  const [verCambios, setVerCambios] = useState(false)
  const [lineaResaltada, setLineaResaltada] = useState<number | null>(null)
  const buscador = useRef<HTMLInputElement>(null)
  const claveLayoutFisico = `colabmodel:physical-layout:${diagramId}`
  const [posicionesFisicas, setPosicionesFisicas] = useState<Record<string, { x: number; y: number }>>(() => {
    try {
      return JSON.parse(localStorage.getItem(claveLayoutFisico) ?? '{}')
    } catch {
      return {}
    }
  })

  useEffect(() => {
    localStorage.setItem(claveLayoutFisico, JSON.stringify(posicionesFisicas))
  }, [claveLayoutFisico, posicionesFisicas])

  const moverTablaFisica = useCallback((tabla: string, posicion: { x: number; y: number }) => {
    setPosicionesFisicas((actual) => ({ ...actual, [tabla]: posicion }))
  }, [])

  const restablecerLayoutFisico = useCallback(() => setPosicionesFisicas({}), [])

  const tablas = esquema?.tablas ?? []

  /**
   * Filtra por nombre de tabla o de columna. Cuando lo que coincide es una
   * columna se dice cuál: si no, el resultado parece arbitrario.
   */
  const visibles = useMemo(() => {
    const q = filtro.trim().toLowerCase()
    if (!q) return tablas.map((t) => ({ tabla: t, porColumna: null as string | null }))
    return tablas
      .map((t) => {
        if (t.nombre.toLowerCase().includes(q)) return { tabla: t, porColumna: null }
        const col = t.columnas.find((c) => c.nombre.toLowerCase().includes(q))
        return col ? { tabla: t, porColumna: col.nombre } : null
      })
      .filter((x): x is { tabla: TablaFisica; porColumna: string | null } => x !== null)
  }, [tablas, filtro])

  // La clase de la tabla elegida. Sobrevive a un renombre: si se renombra la
  // tabla que se esta editando, la seleccion la sigue en vez de saltar a otra.
  const claseElegida = useRef<string | null>(null)
  const encontrada = tablas.find((t) => t.nombre === seleccionada)
  // Solo si la tabla esta: durante el renombre, por un instante, no esta.
  if (encontrada) claseElegida.current = encontrada.claseId ?? null

  // La primera visible queda elegida sola: con el buscador, escribir y tener
  // que hacer clic ademas es un paso de mas.
  useEffect(() => {
    if (!visibles.length) return
    if (seleccionada && visibles.some((v) => v.tabla.nombre === seleccionada)) return
    const renombrada = claseElegida.current
      ? visibles.find((v) => v.tabla.claseId === claseElegida.current) : undefined
    setSeleccionada((renombrada ?? visibles[0]).tabla.nombre)
  }, [visibles, seleccionada])

  // Cambiar de tabla suelta el lock de la anterior. El de la nueva se toma
  // recien al editarla: mirar una tabla no le bloquea la clase a nadie.
  const soltarRef = useRef(onSeleccionarClase)
  soltarRef.current = onSeleccionarClase
  // Por la clase, no por el nombre: renombrar la tabla que se edita no suelta el lock.
  const claseActual = claseElegida.current
  useEffect(() => { soltarRef.current(null) }, [claseActual])

  /**
   * Teclado. Con veinte tablas, apuntar con el mouse a cada una es lo que
   * hace lento el recorrido, no la red.
   *
   *   /             enfoca el buscador
   *   Esc           limpia el filtro
   *   arriba/abajo  cambia de tabla, tambien mientras se escribe en el buscador
   *
   * Dentro de un campo del editor las teclas son del campo: una flecha en un
   * selector de tipo elige otro tipo, no otra tabla.
   *
   * La lista vigente va por `ref` porque el listener se registra UNA vez: si
   * dependiera de `visibles`, habria que re-suscribirlo en cada tecla.
   */
  const visiblesRef = useRef(visibles)
  visiblesRef.current = visibles

  useEffect(() => {
    const alPulsar = (e: KeyboardEvent) => {
      const activo = document.activeElement
      const enBuscador = activo === buscador.current
      const enCampo = !enBuscador && (activo instanceof HTMLInputElement
        || activo instanceof HTMLSelectElement || activo instanceof HTMLTextAreaElement)
      if (enCampo) return
      if (e.key === '/' && !enBuscador) {
        e.preventDefault()
        buscador.current?.focus()
        return
      }
      if (e.key === 'Escape' && enBuscador) {
        setFiltro('')
        buscador.current?.blur()
        return
      }
      if (e.key !== 'ArrowDown' && e.key !== 'ArrowUp') return
      e.preventDefault()
      setSeleccionada((previa) => {
        const lista = visiblesRef.current
        if (!lista.length) return previa
        const i = lista.findIndex((v) => v.tabla.nombre === previa)
        const siguiente = e.key === 'ArrowDown'
          ? Math.min(lista.length - 1, i + 1)
          : Math.max(0, i - 1)
        return lista[siguiente].tabla.nombre
      })
    }
    window.addEventListener('keydown', alPulsar)
    return () => window.removeEventListener('keydown', alPulsar)
  }, [])

  const actual = tablas.find((t) => t.nombre === seleccionada) ?? null
  const enumeraciones = useMemo(() => clases.filter(esEnumeracion).map((c) => c.name), [clases])

  const irA = (nombre: string) => {
    setFiltro('')
    setModo((m) => (m === 'script' ? 'tablas' : m))
    setSeleccionada(nombre)
  }

  // Cada vez que cambia la elegida --clic, teclado, busqueda, una FK seguida--
  // el diagrama la trae a la vista si no se ve entera.
  const [enfocar, setEnfocar] = useState(0)
  useEffect(() => { setEnfocar((n) => n + 1) }, [seleccionada])

  const resaltadas = useMemo(
    () => (filtro.trim() ? new Set(visibles.map((v) => v.tabla.nombre)) : null), [filtro, visibles])
  const bloqueadas = useMemo(() => new Set(tablas
    .filter((t) => t.claseId && locks[t.claseId] && locks[t.claseId] !== yo).map((t) => t.nombre)),
  [tablas, locks, yo])

  const copiarTexto = async (texto: string) => {
    try {
      await navigator.clipboard.writeText(texto)
      setCopiado(true)
      setTimeout(() => setCopiado(false), 1800)
    } catch {
      // Sin permiso de portapapeles queda la descarga, que no lo necesita.
      setCopiado(false)
    }
  }

  const descargar = () => {
    if (!esquema) return
    const url = URL.createObjectURL(new Blob([esquema.script], { type: 'text/sql' }))
    const a = document.createElement('a')
    a.href = url
    a.download = 'esquema.sql'
    document.body.appendChild(a)
    a.click()
    a.remove()
    setTimeout(() => URL.revokeObjectURL(url), 0)
  }

  const verificar = async () => {
    setVerificando(true)
    setErrorVerificacion(null)
    try {
      const v = await onVerificar()
      setVerificacion(v)
      setLineaResaltada(v.resultado.ok ? null : v.resultado.linea ?? null)
    } catch {
      setErrorVerificacion('No se pudo verificar: revisá que el backend y PostgreSQL sigan arriba.')
    } finally {
      setVerificando(false)
    }
  }

  if (!esquema) {
    return <div className="pendiente"><p className="tenue">Derivando el esquema…</p></div>
  }
  if (!tablas.length) {
    return (
      <div className="pendiente">
        <p><strong>Todavía no hay nada que derivar</strong></p>
        <p className="tenue">Creá alguna clase en la vista de Clases y volvé.</p>
      </div>
    )
  }

  const confirmacion = esquema.confirmacion ?? null
  // Una verificacion vale para el esquema que se verifico: si despues alguien
  // edito, ya no dice nada sobre el de hoy.
  const verificacionVigente = verificacion !== null && verificacion.hash === esquema.hash
  const puedeConfirmar = puedeEditar && verificacionVigente && verificacion!.resultado.ok
    && !confirmacion?.vigente && !confirmando
  const duenioDelLock = actual?.claseId ? locks[actual.claseId] : undefined

  /** El mismo editor en las dos vistas: al lado del indice, o debajo del diagrama. */
  const editorDe = (t: TablaFisica) => (
    <EditorDeTabla
      key={t.nombre}
      tabla={t}
      clases={clases}
      relaciones={relaciones}
      bloqueadaPor={duenioDelLock && duenioDelLock !== yo ? duenioDelLock : null}
      puedeEditar={puedeEditar}
      enumeraciones={enumeraciones}
      onEditar={() => onSeleccionarClase(t.claseId ?? null)}
      onCambio={onCambio}
      onIr={irA}
      onCopiar={copiarTexto}
      ddl={ddlDeTabla(esquema.script, t.nombre)}
    />
  )

  return (
    <div className="fisica">
      <header className="fis-cabecera">
        <div>
          <strong>{esquema.dialecto}</strong>
          <span className="tenue">
            {' · '}{esquema.resumen.tablas} tablas
            {' · '}{esquema.resumen.columnas} columnas
            {' · '}{esquema.resumen.clavesForaneas} FK
            {' · '}{esquema.resumen.indices} índices
          </span>
        </div>
        <div className="fis-acciones">
          <button onClick={verificar} disabled={verificando}
                  title="Ejecuta el script en PostgreSQL dentro de una transacción y la deshace">
            {verificando ? 'Verificando…' : 'Verificar en PostgreSQL'}
          </button>
          <button className="fis-confirmar" onClick={() => onConfirmar(esquema.hash)} disabled={!puedeConfirmar}
                  title={confirmacion?.vigente ? 'Este esquema ya está confirmado'
                    : !verificacionVigente ? 'Primero verificalo en PostgreSQL'
                    : 'Deja este DDL como la base que se va a generar'}>
            {confirmando ? 'Confirmando…' : confirmacion?.vigente ? 'Confirmada ✓' : 'Confirmar esquema'}
          </button>
          <span className="fis-sep" />
          <span className="fis-modos" role="group" aria-label="Cómo ver el esquema">
            {(['diagrama', 'tablas', 'script'] as Modo[]).map((m) => (
              <button key={m} className={modo === m ? 'activa' : ''} onClick={() => setModo(m)}>
                {m === 'diagrama' ? 'Diagrama' : m === 'tablas' ? 'Tablas' : 'Script'}
              </button>
            ))}
          </span>
          <button onClick={() => copiarTexto(esquema.script)}>
            {copiado ? 'Copiado ✓' : 'Copiar SQL'}
          </button>
          <button onClick={descargar}>Descargar .sql</button>
        </div>
      </header>

      <EstadoDeLaBase confirmacion={confirmacion} verCambios={verCambios}
                      onVerCambios={() => setVerCambios(!verCambios)} />

      {confirmacion && !confirmacion.vigente && verCambios && confirmacion.scriptConfirmado && (
        <Diferencias antes={confirmacion.scriptConfirmado} despues={esquema.script} />
      )}

      {errorConfirmacion && <p className="fis-resultado mal">{errorConfirmacion}</p>}
      {errorVerificacion && <p className="fis-resultado mal">{errorVerificacion}</p>}
      {verificacion && (
        <ResultadoDeVerificacion verificacion={verificacion} vigente={verificacionVigente}
                                 onVerLinea={(l) => { setModo('script'); setLineaResaltada(l) }} />
      )}

      {esquema.advertencias.length > 0 && (
        // Plegable pero con la cuenta a la vista (Regla 10: se lista, no se
        // calla). En el diagrama arranca plegada para dejarle lugar al dibujo.
        <details className="advertencias fis-advertencias" open={modo !== 'diagrama'}>
          <summary>Lo que este modelo deja afuera ({esquema.advertencias.length})</summary>
          <ul>{esquema.advertencias.map((a, i) => <li key={i}>{a}</li>)}</ul>
        </details>
      )}

      {verScript ? (
        // El script tal cual: es el mismo archivo que sale en el zip, no una
        // version reconstruida para mostrar.
        <Script texto={esquema.script} lineaResaltada={verificacionVigente ? lineaResaltada : null} />
      ) : modo === 'diagrama' ? (
        <div className="fis-modo-diagrama">
          <div className="fis-diagrama-barra">
            <input
              ref={buscador}
              className="fis-buscador"
              value={filtro}
              placeholder="Buscar tabla o columna…  ( / )"
              onChange={(e) => setFiltro(e.target.value)}
            />
            {filtro.trim() && (
              <span className="tenue">{visibles.length} de {tablas.length} tablas coinciden</span>
            )}
            <span className="tenue fis-ayuda">Clic en una tabla para editarla abajo · ↑ ↓ recorren las tablas</span>
          </div>
          <div className="fis-diagrama">
            <DiagramaFisico tablas={tablas} layout={layout} seleccionada={seleccionada}
                            onSeleccionar={setSeleccionada} resaltadas={resaltadas}
                            bloqueadas={bloqueadas} enfocar={enfocar}
                            posiciones={posicionesFisicas} onMoverTabla={moverTablaFisica}
                            onRestablecer={restablecerLayoutFisico} />
          </div>
          <div className="fis-dock">
            {actual ? editorDe(actual) : <p className="tenue">Elegí una tabla del diagrama para verla y editarla.</p>}
          </div>
        </div>
      ) : (
        <div className="fis-cuerpo">
          <nav className="fis-indice">
            <input
              ref={buscador}
              className="fis-buscador"
              value={filtro}
              placeholder="Buscar tabla o columna…"
              onChange={(e) => setFiltro(e.target.value)}
            />
            {!visibles.length && <p className="tenue">Nada coincide con “{filtro}”.</p>}
            <ul>
              {visibles.map(({ tabla, porColumna }) => (
                <li key={tabla.nombre}>
                  <button
                    className={tabla.nombre === seleccionada ? 'activa' : ''}
                    onClick={() => setSeleccionada(tabla.nombre)}
                  >
                    <span className="fis-nombre">{tabla.nombre}</span>
                    {tabla.claseId && locks[tabla.claseId] && locks[tabla.claseId] !== yo && (
                      <span className="fis-candado" title={'La está editando ' + locks[tabla.claseId]}>●</span>
                    )}
                    <span className="tenue">{tabla.columnas.length}</span>
                    {porColumna && <em className="fis-coincidencia">{porColumna}</em>}
                  </button>
                </li>
              ))}
            </ul>
          </nav>

          {actual && editorDe(actual)}
        </div>
      )}
    </div>
  )
}

/** Si la base esta confirmada, por quien, y si sigue siendo la de hoy. */
function EstadoDeLaBase({ confirmacion, verCambios, onVerCambios }: {
  confirmacion: EsquemaFisico['confirmacion']
  verCambios: boolean
  onVerCambios: () => void
}) {
  if (!confirmacion) {
    return (
      <p className="fis-estado">
        <strong>Base sin confirmar.</strong> Revisala, verificala en PostgreSQL y confirmala: el
        backend se genera con la base confirmada.
      </p>
    )
  }
  const cuando = new Date(confirmacion.en).toLocaleString('es', { dateStyle: 'short', timeStyle: 'short' })
  if (confirmacion.vigente) {
    return (
      <p className="fis-estado ok">
        <strong>Base confirmada</strong> por {confirmacion.por ?? 'alguien del equipo'} el {cuando}{' '}
        <span className="tenue">(operación #{confirmacion.seq})</span>. Es la que va a llevar el backend generado.
      </p>
    )
  }
  return (
    <p className="fis-estado aviso">
      <strong>La base cambió desde que se confirmó</strong> (operación #{confirmacion.seq}, {cuando}).
      Revisala y volvé a confirmarla antes de generar.{' '}
      <button className="marca" onClick={onVerCambios}>{verCambios ? 'ocultar cambios' : 'ver qué cambió'}</button>
    </p>
  )
}

function ResultadoDeVerificacion({ verificacion, vigente, onVerLinea }: {
  verificacion: VerificacionDelEsquema
  vigente: boolean
  onVerLinea: (linea: number) => void
}) {
  const r = verificacion.resultado
  if (!vigente) {
    return <p className="fis-resultado tenue">El esquema cambió desde la última verificación: verificalo de nuevo.</p>
  }
  if (r.ok) {
    return (
      <p className="fis-resultado bien">
        PostgreSQL aceptó el script: creó {r.tablas} tablas, {r.clavesForaneas} FK, {r.indices} índices
        y {r.restricciones} restricciones en {r.milisegundos} ms, y se deshizo todo (ROLLBACK).
      </p>
    )
  }
  return (
    <div className="fis-resultado mal">
      <p>
        <strong>PostgreSQL rechazó el script{r.linea ? ` en la línea ${r.linea}` : ''}.</strong>{' '}
        {r.linea && <button className="marca" onClick={() => onVerLinea(r.linea!)}>ver en el script</button>}
      </p>
      <pre>{r.error}</pre>
      {r.sentencia && <pre className="fis-sentencia">{r.sentencia}</pre>}
    </div>
  )
}

/** El script con numeros de linea: el error de PostgreSQL se ubica por linea. */
function Script({ texto, lineaResaltada }: { texto: string; lineaResaltada: number | null }) {
  const contenedor = useRef<HTMLPreElement>(null)
  useEffect(() => {
    if (!lineaResaltada) return
    contenedor.current?.querySelector('.fis-linea.error')?.scrollIntoView({ block: 'center' })
  }, [lineaResaltada, texto])
  return (
    <pre className="fis-script" ref={contenedor}>
      {texto.split('\n').map((l, i) => (
        <div key={i} data-n={i + 1} className={i + 1 === lineaResaltada ? 'fis-linea error' : 'fis-linea'}>
          {l || ' '}
        </div>
      ))}
    </pre>
  )
}

function Diferencias({ antes, despues }: { antes: string; despues: string }) {
  const tramos = useMemo(() => resumir(diferencias(antes, despues)), [antes, despues])
  return (
    <pre className="fis-diff">
      {tramos.map((t, i) => t.tipo === 'salto'
        ? <div key={i} className="fis-diff-salto">… {t.cantidad} líneas sin cambios …</div>
        : <div key={i} className={'fis-diff-' + t.tipo}>
            {t.tipo === 'agregada' ? '+ ' : t.tipo === 'quitada' ? '- ' : '  '}{t.texto}
          </div>)}
    </pre>
  )
}
