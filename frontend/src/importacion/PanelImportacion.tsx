import { useMemo, useRef, useState } from 'react'
import { v4 as uuid } from 'uuid'
import type { EstadoModelo } from '../canvas/applyOperation'
import { esEmbebible, esEnumeracion } from '../contracts/command'
import {
  planDeImportacion, revisionDe, TOPE_IMPORTACION,
  type AtributoEnRevision, type ClaseEnRevision, type MetodoEnRevision, type Plan, type PropuestaDeImportacion,
  type Revision,
} from './propuesta'

const TIPOS = ['string', 'text', 'int', 'long', 'decimal', 'boolean', 'date', 'datetime', 'uuid']

/** Lo que paso con el lote despues de mandarlo: llega por el WebSocket. */
export type EstadoEnvio =
  | { tipo: 'enviando' }
  | { tipo: 'aplicado'; seq: number }
  | { tipo: 'rechazado'; mensaje: string }

interface Props {
  modelo: EstadoModelo
  /** Sube el archivo y trae la propuesta. Lanza Error con el mensaje del backend. */
  pedirPropuesta: (archivo: File) => Promise<PropuestaDeImportacion>
  onAplicar: (plan: Plan, descripcion: string) => void
  envio: EstadoEnvio | null
  /** Sin conexion o en una vista de solo lectura se puede revisar, no aplicar. */
  puedeAplicar: boolean
  onCerrar: () => void
}

/**
 * Importar = leer, revisar, aplicar.
 *
 * El archivo nunca escribe por su cuenta: el backend solo propone, y lo que
 * el usuario deja tildado entra como UN BATCH por el bus (ADR-002, ADR-016).
 * Todo o nada: si un cambio choca con un lock o una validacion, no queda
 * medio modelo importado.
 */
export function PanelImportacion({ modelo, pedirPropuesta, onAplicar, envio, puedeAplicar, onCerrar }: Props) {
  const [leyendo, setLeyendo] = useState(false)
  const [errorLectura, setErrorLectura] = useState<string | null>(null)
  const [revision, setRevision] = useState<Revision | null>(null)
  /** El plan que se mando: congela lo que se muestra mientras el modelo cambia. */
  const [enviado, setEnviado] = useState<Plan | null>(null)
  const input = useRef<HTMLInputElement>(null)

  const leer = async (archivo: File | undefined) => {
    if (!archivo) return
    setLeyendo(true)
    setErrorLectura(null)
    try {
      setRevision(revisionDe(await pedirPropuesta(archivo)))
    } catch (e) {
      setErrorLectura(e instanceof Error ? e.message : 'No se pudo leer el archivo.')
    } finally {
      setLeyendo(false)
      if (input.current) input.current.value = ''
    }
  }

  // Contra el modelo ACTUAL: si otro crea una clase mientras se revisa, el
  // plan ya no la duplica.
  const plan = useMemo(() => revision && planDeImportacion(revision, modelo, uuid), [revision, modelo])

  const cambiarClase = (clave: string, cambios: Partial<ClaseEnRevision>) =>
    setRevision((r) => r && { ...r, clases: r.clases.map((c) => (c.clave === clave ? { ...c, ...cambios } : c)) })

  const cambiarAtributo = (clave: string, i: number, cambios: Partial<AtributoEnRevision>) =>
    setRevision((r) => r && {
      ...r,
      clases: r.clases.map((c) => c.clave !== clave ? c
        : { ...c, atributos: c.atributos.map((a, j) => (j === i ? { ...a, ...cambios } : a)) }),
    })

  const cambiarMetodo = (clave: string, i: number, cambios: Partial<MetodoEnRevision>) =>
    setRevision((r) => r && {
      ...r,
      clases: r.clases.map((c) => c.clave !== clave ? c
        : { ...c, metodos: c.metodos.map((m, j) => (j === i ? { ...m, ...cambios } : m)) }),
    })

  const cambiarRelacion = (i: number, incluir: boolean) =>
    setRevision((r) => r && { ...r, relaciones: r.relaciones.map((x, j) => (j === i ? { ...x, incluir } : x)) })

  const aplicar = () => {
    if (!plan || !revision) return
    setEnviado(plan)
    onAplicar(plan, revision.fuente)
  }

  const volverARevisar = () => setEnviado(null)

  const tiposDelModelo = [
    ...modelo.classes.filter(esEnumeracion).map((c) => c.name),
    ...modelo.classes.filter(esEmbebible).map((c) => c.name),
  ]
  const nombreDeClave = (clave: string) => revision?.clases.find((c) => c.clave === clave)?.nombre ?? clave

  return (
    <div className="modal-fondo" onClick={onCerrar}>
      <div className="modal modal-ancho" onClick={(e) => e.stopPropagation()}>
        <header>
          <strong>Importar modelo</strong>
          {revision && <code className="imp-fuente">{revision.fuente}</code>}
          <button className="modal-cerrar" onClick={onCerrar} title="Cerrar">×</button>
        </header>

        {/* ------------------------------------------------ 1. ELEGIR ARCHIVO */}
        {!revision && (
          <section className="imp-elegir">
            <p>
              Se lee el archivo y se muestra lo que se agregaría. <strong>Nada se escribe</strong> hasta
              que confirmes.
            </p>
            <label className="tb-btn tb-primario imp-archivo">
              {leyendo ? 'Leyendo…' : 'Elegir archivo'}
              <input ref={input} type="file" accept=".json,.xmi,.xml,.uml,.sql,.ddl" disabled={leyendo}
                     onChange={(e) => leer(e.target.files?.[0])} />
            </label>
            <ul className="imp-formatos">
              <li><strong>JSON</strong> de ColabModel (el formato de <code>canonical-models/</code>)</li>
              <li><strong>XMI</strong> 1.x o 2.x de Enterprise Architect, Papyrus, ArgoUML u otra herramienta:
                diagrama de clases, ERD o modelo de datos. Lo que no se entienda se lista abajo, no se pierde en silencio</li>
              <li><strong>SQL</strong> con <code>CREATE TABLE</code></li>
            </ul>
            {errorLectura && <p className="modal-error">{errorLectura}</p>}
          </section>
        )}

        {/* ------------------------------------------------- 3. RESULTADO */}
        {revision && enviado && (
          <section className="imp-resultado">
            <p className="modal-resumen">{resumenDe(enviado)}</p>
            {envio?.tipo === 'enviando' && <p className="tenue">Aplicando como un solo cambio…</p>}
            {envio?.tipo === 'aplicado' && (
              <p className="imp-ok">Importado en la operación <strong>#{envio.seq}</strong>. Ya lo ven todos los conectados.</p>
            )}
            {envio?.tipo === 'rechazado' && (
              <>
                <p className="modal-error">{envio.mensaje}</p>
                <footer>
                  <button className="tb-btn" onClick={volverARevisar}>Volver a la revisión</button>
                </footer>
              </>
            )}
            {envio?.tipo === 'aplicado' && (
              <footer><button className="tb-btn tb-primario imp-sin-margen" onClick={onCerrar}>Listo</button></footer>
            )}
          </section>
        )}

        {/* ------------------------------------------------- 2. REVISAR */}
        {revision && plan && !enviado && (
          <>
            {revision.advertencias.length > 0 && (
              <section className="advertencias">
                <h4>Al leer el archivo</h4>
                <ul>{revision.advertencias.map((a, i) => <li key={i}>{a}</li>)}</ul>
              </section>
            )}

            <div className="imp-clases">
              {revision.clases.map((c) => (
                <article key={c.clave} className={c.incluir ? 'imp-clase' : 'imp-clase excluida'}>
                  <header>
                    <input type="checkbox" checked={c.incluir} title="Incluir esta clase"
                           onChange={(e) => cambiarClase(c.clave, { incluir: e.target.checked })} />
                    <input className="imp-nombre" value={c.nombre} disabled={!c.incluir}
                           onChange={(e) => cambiarClase(c.clave, { nombre: e.target.value })} />
                    {c.estereotipo && <span className="amb-tipo">«{c.estereotipo}»</span>}
                    {c.abstracta && <span className="amb-tipo">abstracta</span>}
                    {modelo.classes.some((x) => x.name.trim().toLowerCase() === c.nombre.trim().toLowerCase()) && (
                      <span className="imp-existe" title="No se crea de nuevo: solo se le agregan los atributos que falten">
                        ya existe
                      </span>
                    )}
                  </header>

                  {c.atributos.length === 0 && <p className="tenue imp-vacia">Sin atributos.</p>}
                  <ul className="imp-atributos">
                    {c.atributos.map((a, i) => (
                      <FilaAtributo key={i} a={a} habilitada={c.incluir}
                                    tipos={[...TIPOS, ...tiposDelModelo]}
                                    onCambiar={(cambios) => cambiarAtributo(c.clave, i, cambios)} />
                    ))}
                  </ul>

                  {c.metodos.length > 0 && (
                    <ul className="imp-atributos imp-metodos">
                      {c.metodos.map((m, i) => (
                        <FilaMetodo key={i} m={m} habilitada={c.incluir}
                                    onCambiar={(cambios) => cambiarMetodo(c.clave, i, cambios)} />
                      ))}
                    </ul>
                  )}
                </article>
              ))}
            </div>

            {revision.relaciones.length > 0 && (
              <section className="imp-relaciones">
                <h4>Relaciones del archivo</h4>
                <ul>
                  {revision.relaciones.map((r, i) => (
                    <li key={i}>
                      <label>
                        <input type="checkbox" checked={r.incluir} onChange={(e) => cambiarRelacion(i, e.target.checked)} />
                        <code>{nombreDeClave(r.claveOrigen)}</code>
                        <span className="tenue"> {r.multiplicidadOrigen ?? ''} —{r.tipo.toLowerCase()}— {r.multiplicidadDestino ?? ''} </span>
                        <code>{nombreDeClave(r.claveDestino)}</code>
                        {r.nombre && <span className="tenue"> «{r.nombre}»</span>}
                      </label>
                    </li>
                  ))}
                </ul>
              </section>
            )}

            {plan.avisos.length > 0 && (
              <section className="advertencias">
                <h4>No se va a aplicar</h4>
                <ul>{plan.avisos.map((a, i) => <li key={i}>{a}</li>)}</ul>
              </section>
            )}

            <footer className="imp-pie">
              <span className="modal-resumen">{resumenDe(plan)}</span>
              {plan.comandos.length > TOPE_IMPORTACION && (
                <span className="props-error">Supera el tope de {TOPE_IMPORTACION} cambios por importación.</span>
              )}
              {!puedeAplicar && <span className="props-error">Sin conexión o en una vista de solo lectura.</span>}
              <button className="tb-btn" onClick={() => setRevision(null)}>Otro archivo</button>
              <button className="tb-btn tb-primario imp-sin-margen" onClick={aplicar}
                      disabled={!puedeAplicar || plan.comandos.length === 0 || plan.comandos.length > TOPE_IMPORTACION}>
                Aplicar {plan.comandos.length} {plan.comandos.length === 1 ? 'cambio' : 'cambios'}
              </button>
            </footer>
          </>
        )}
      </div>
    </div>
  )
}

function resumenDe(p: Plan) {
  const { clases, atributos, relaciones, metodos } = p.conteo
  const n = (k: number, uno: string, varios: string) => `${k} ${k === 1 ? uno : varios}`
  const base = `${n(clases, 'clase nueva', 'clases nuevas')} · ${n(atributos, 'atributo', 'atributos')} · `
    + n(relaciones, 'relación', 'relaciones')
  return metodos === 0 ? base : `${base} · ${n(metodos, 'método', 'métodos')}`
}

interface FilaProps {
  a: AtributoEnRevision
  habilitada: boolean
  tipos: string[]
  onCambiar: (cambios: Partial<AtributoEnRevision>) => void
}

/** Un atributo: incluir, nombre, tipo, banderas y lo que sugiere su nombre. */
function FilaAtributo({ a, habilitada, tipos, onCambiar }: FilaProps) {
  const s = a.sugerencia
  const activa = habilitada && a.incluir
  const esRelacion = s.rol === 'RELACION' && !!s.claseRelacionadaId
  // El tipo leido puede ser una enumeracion que todavia no esta en el modelo.
  const opciones = tipos.includes(a.tipo) ? tipos : [...tipos, a.tipo]

  return (
    <li className={activa ? '' : 'excluida'}>
      <div className="props-attr-fila">
        <input type="checkbox" checked={a.incluir} disabled={!habilitada}
               onChange={(e) => onCambiar({ incluir: e.target.checked })} />
        <input className="props-attr-nombre" value={a.nombre} disabled={!activa}
               onChange={(e) => onCambiar({ nombre: e.target.value })} />
        {a.comoRelacion && esRelacion ? (
          <span className="imp-como-relacion">→ {s.claseRelacionada}</span>
        ) : (
          <select value={a.tipo} disabled={!activa} onChange={(e) => onCambiar({ tipo: e.target.value })}>
            {opciones.map((t) => <option key={t} value={t}>{t}</option>)}
          </select>
        )}
        {!(a.comoRelacion && esRelacion) && (
          <>
            <label className={a.requerido ? 'bandera activa' : 'bandera'} title="Obligatorio (NOT NULL)">
              <input type="checkbox" checked={a.requerido} disabled={!activa}
                     onChange={(e) => onCambiar({ requerido: e.target.checked })} />NN
            </label>
            <label className={a.unico ? 'bandera activa' : 'bandera'} title="Unico (UNIQUE)">
              <input type="checkbox" checked={a.unico} disabled={!activa}
                     onChange={(e) => onCambiar({ unico: e.target.checked })} />UQ
            </label>
          </>
        )}
      </div>

      {a.yaExiste && <p className="imp-nota">Ya está en la clase existente.</p>}
      {a.tipoLeido && a.tipoLeido.toLowerCase() !== a.tipo.toLowerCase() && !a.comoRelacion && (
        <p className="imp-nota">En el archivo: <code>{a.tipoLeido}</code></p>
      )}

      {s.rol !== 'ATRIBUTO' && (
        <div className={'props-sugerencia confianza-' + s.confianza.toLowerCase()}>
          <span>{s.motivo}</span>
          {esRelacion && (
            <label className="imp-toggle">
              <input type="checkbox" checked={a.comoRelacion} disabled={!activa}
                     onChange={(e) => onCambiar({ comoRelacion: e.target.checked })} />
              Crear como relación con {s.claseRelacionada} (en vez de un atributo {a.tipo})
            </label>
          )}
          {s.rol === 'CLAVE_NATURAL' && !(a.requerido && a.unico) && (
            <button className="tb-btn" disabled={!activa}
                    onClick={() => onCambiar({ requerido: true, unico: true })}>
              Marcar obligatorio y único
            </button>
          )}
        </div>
      )}
    </li>
  )
}

interface FilaMetodoProps {
  m: MetodoEnRevision
  habilitada: boolean
  onCambiar: (cambios: Partial<MetodoEnRevision>) => void
}

/**
 * Un metodo (REGLA 6): solo se tilda o se destilda. El generador emite su
 * firma como stub en el Service, no su cuerpo, asi que aca no hay nada que
 * editar salvo si entra.
 */
function FilaMetodo({ m, habilitada, onCambiar }: FilaMetodoProps) {
  const activa = habilitada && m.incluir
  const firma = `${m.nombre}(${m.parametros.map((p) => `${p.nombre}: ${p.tipo ?? 'string'}`).join(', ')})`
    + `: ${m.tipoRetorno ?? 'void'}`
  return (
    <li className={activa ? '' : 'excluida'}>
      <div className="props-attr-fila">
        <input type="checkbox" checked={m.incluir} disabled={!habilitada}
               onChange={(e) => onCambiar({ incluir: e.target.checked })} />
        <code className="imp-metodo-firma">{firma}</code>
      </div>
      {m.yaExiste && <p className="imp-nota">Ya está en la clase existente.</p>}
    </li>
  )
}
