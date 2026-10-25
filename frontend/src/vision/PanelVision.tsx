import { useMemo, useState } from 'react'
import type { RelationKind } from '../contracts/command'
import type { ClaseDetectada, DeteccionPropuesta, RelacionDetectada } from '../contracts/vision'
import { traducir, type ClaseConfirmada, type DeteccionConfirmada, type RelacionConfirmada } from './traductor'

const plural = (n: number, una: string, varias: string) => `${n} ${n === 1 ? una : varias}`

const TIPOS: { id: RelationKind; label: string }[] = [
  { id: 'ASSOCIATION', label: 'Asociacion' },
  { id: 'AGGREGATION', label: 'Agregacion' },
  { id: 'COMPOSITION', label: 'Composicion' },
  { id: 'GENERALIZATION', label: 'Generalizacion' },
  { id: 'DEPENDENCY', label: 'Dependencia' },
]

interface Props {
  propuesta: DeteccionPropuesta | null
  error: string | null
  aplicando: boolean
  onAplicar: (confirmada: DeteccionConfirmada) => void
  onCerrar: () => void
}

/** Lo que el usuario fue tocando. Arranca como la propuesta y se separa de ella. */
interface Borrador {
  clases: Record<string, { aceptada: boolean; nombre: string; atributos: Record<string, boolean> }>
  relaciones: Record<string, {
    aceptada: boolean
    kind: RelationKind
    /** Multiplicidad de cada extremo, como texto editable ("1", "0..*"). */
    origen: string
    destino: string
  }>
}

/**
 * EL PASO QUE CONVIERTE UNA LECTURA EN UNA EDICION.
 *
 * Hasta aca nada toco el modelo: `vision/` solo propone. Este panel es donde
 * una persona acepta, corrige o descarta, y recien lo que queda se traduce a
 * un `BATCH` y entra por el bus como cualquier otra escritura (ADR-002).
 *
 * Existe por una razon concreta, no por prolijidad. Un detector geometrico
 * confunde una diagonal con nada, y un modelo de vision se equivoca de la peor
 * manera posible: inventando texto que parece plausible. Un atributo alucinado
 * que entra al modelo sin que nadie lo mire es peor que uno faltante, porque
 * el faltante se nota. Este panel es lo que convierte esa falta de fiabilidad
 * en un paso de interfaz en vez de en un defecto de correccion --la misma
 * doctrina de la Regla 5 y de los 4 puntos de ambiguedad: sugerir y confirmar,
 * nunca decidir sola--.
 *
 * Por eso NADA viene tildado de arranque cuando no tiene nombre: aceptar por
 * omision seria decidir por el usuario.
 */
export function PanelVision({ propuesta, error, aplicando, onAplicar, onCerrar }: Props) {
  const [borrador, setBorrador] = useState<Borrador | null>(null)

  // Se inicializa al llegar la propuesta, no en un efecto: asi no hay un
  // fotograma con el panel vacio.
  const estado = useMemo<Borrador>(() => {
    if (borrador) return borrador
    const inicial: Borrador = { clases: {}, relaciones: {} }
    for (const c of propuesta?.clases ?? []) {
      inicial.clases[c.ref] = {
        // Sin nivel 2 el nombre viene ausente: la caja no se puede aceptar
        // hasta que alguien lo escriba.
        aceptada: Boolean(c.nombre),
        nombre: c.nombre ?? '',
        atributos: Object.fromEntries(c.atributos.map((a) => [a.ref, Boolean(a.texto)])),
      }
    }
    for (const r of propuesta?.relaciones ?? []) {
      inicial.relaciones[r.ref] = {
        aceptada: true,
        kind: r.kind,
        // Las cardinalidades vienen leidas si alguna capa del nivel 2 pudo
        // leerlas, y vacias si no. No son cosmeticas: de ellas sale que la FK
        // vaya NOT NULL y que un N:M se derive como TABLA PUENTE.
        origen: r.cardinalidadOrigen?.texto ?? '',
        destino: r.cardinalidadDestino?.texto ?? '',
      }
    }
    return inicial
  }, [propuesta, borrador])

  const tocar = (f: (b: Borrador) => Borrador) => setBorrador(f(estado))

  const confirmada = useMemo<DeteccionConfirmada>(() => {
    const clases: ClaseConfirmada[] = (propuesta?.clases ?? [])
      .filter((c) => estado.clases[c.ref]?.aceptada && estado.clases[c.ref].nombre.trim())
      .map((c) => ({
        ref: c.ref,
        nombre: estado.clases[c.ref].nombre.trim(),
        lienzo: c.lienzo,
        atributos: c.atributos
          .filter((a) => estado.clases[c.ref].atributos[a.ref] && a.texto?.trim())
          .map((a) => ({ nombre: a.texto!.trim(), dataType: 'string' })),
      }))
    const relaciones: RelacionConfirmada[] = (propuesta?.relaciones ?? [])
      .filter((r) => estado.relaciones[r.ref]?.aceptada)
      .map((r) => ({
        ref: r.ref,
        origenRef: r.origenRef,
        destinoRef: r.destinoRef,
        kind: estado.relaciones[r.ref].kind,
        origenMultiplicidad: multiplicidad(estado.relaciones[r.ref].origen),
        destinoMultiplicidad: multiplicidad(estado.relaciones[r.ref].destino),
      }))
    return { clases, relaciones }
  }, [propuesta, estado])

  // Se traduce en vivo, solo para MOSTRAR que va a pasar. Lo que se manda se
  // vuelve a traducir en App: esto no es la fuente de verdad, es la vista previa.
  const previa = useMemo(
    () => traducir(confirmada, '0'.repeat(8) + '-0000-0000-0000-' + '0'.repeat(12), 0),
    [confirmada],
  )
  const cantidad = previa.comando
    ? (previa.comando.payload as { comandos: unknown[] }).comandos.length
    : 0

  return (
    <div className="modal-fondo" onClick={onCerrar}>
      <div className="modal modal-ancho" onClick={(e) => e.stopPropagation()}>
        <header>
          <strong>Lo que se leyo de la imagen</strong>
          <button className="modal-cerrar" onClick={onCerrar} title="Cerrar">×</button>
        </header>

        {error && <p className="modal-error">{error}</p>}
        {!error && !propuesta && <p className="tenue">Leyendo la imagen…</p>}

        {propuesta && (
          <>
            <p className="modal-resumen">
              <strong>{propuesta.clases.length}</strong> cajas
              {' · '}
              <strong>{propuesta.relaciones.length}</strong> lineas
              {' · '}
              {propuesta.metricas.milis} ms
              {propuesta.metricas.enderezado && (
                <> {' · '} enderezada {propuesta.metricas.enderezado.grados.toFixed(1)}°</>
              )}
              {propuesta.metricas.binarizacion === 'adaptativa' && (
                <> {' · '} umbral adaptativo</>
              )}
              {propuesta.metricas.rectasSueltas > 0 && (
                <> {' · '} {propuesta.metricas.rectasSueltas} diagonal(es) o punteada(s)</>
              )}
            </p>

            {propuesta.metricas.detector === 'geometrico' && !propuesta.clases.some((c) => c.nombre) && (
              <p className="tenue">
                No hay lectura de texto disponible, asi que las cajas llegan sin nombre.
                Escribilos para poder aceptarlas: una clase sin nombre no se crea.
              </p>
            )}

            {propuesta.avisos.length > 0 && (
              <section className="advertencias">
                <h4>Lo que no se pudo interpretar</h4>
                <ul>
                  {propuesta.avisos.map((a, i) => <li key={i}>{a}</li>)}
                </ul>
              </section>
            )}

            <section className="vision-lista">
              <h4>Clases</h4>
              {propuesta.clases.map((c) => (
                <Caja key={c.ref} clase={c} estado={estado} tocar={tocar} />
              ))}
              {!propuesta.clases.length && <p className="tenue">No se reconocio ninguna caja.</p>}
            </section>

            {propuesta.relaciones.length > 0 && (
              <section className="vision-lista">
                <h4>Relaciones</h4>
                {propuesta.relaciones.map((r) => (
                  <Linea key={r.ref} relacion={r} propuesta={propuesta} estado={estado} tocar={tocar} />
                ))}
              </section>
            )}

            {previa.descartes.length > 0 && (
              <section className="advertencias">
                <h4>No va a entrar</h4>
                <ul>
                  {previa.descartes.map((d, i) => <li key={i}>{d}</li>)}
                </ul>
              </section>
            )}

            <footer className="modal-pie">
              <span className="tenue">
                {cantidad
                  ? `${plural(confirmada.clases.length, 'clase', 'clases')} y `
                    + `${plural(confirmada.relaciones.length, 'relacion', 'relaciones')}, `
                    + `en un solo comando de ${cantidad} pasos`
                  : 'Todavia no hay nada que agregar'}
              </span>
              <button className="accion"
                      disabled={!cantidad || aplicando}
                      onClick={() => onAplicar(confirmada)}>
                {aplicando ? 'Agregando…' : 'Agregar al diagrama'}
              </button>
            </footer>
          </>
        )}
      </div>
    </div>
  )
}

function Caja({ clase, estado, tocar }: {
  clase: ClaseDetectada
  estado: Borrador
  tocar: (f: (b: Borrador) => Borrador) => void
}) {
  const mio = estado.clases[clase.ref]
  const cambiar = (cambios: Partial<Borrador['clases'][string]>) =>
    tocar((b) => ({ ...b, clases: { ...b.clases, [clase.ref]: { ...mio, ...cambios } } }))

  return (
    <div className={mio.aceptada ? 'vision-item' : 'vision-item descartado'}>
      <div className="vision-fila">
        <input type="checkbox" checked={mio.aceptada}
               disabled={!mio.nombre.trim()}
               title={mio.nombre.trim() ? 'Agregar esta clase' : 'Escribile un nombre primero'}
               onChange={(e) => cambiar({ aceptada: e.target.checked })} />
        <input className="vision-nombre" value={mio.nombre} placeholder="Nombre de la clase"
               onChange={(e) => {
                 const nombre = e.target.value
                 // Escribir el nombre la habilita; borrarlo la saca. Sin esto
                 // hay que tildarla en un segundo paso y se pierde.
                 cambiar({ nombre, aceptada: Boolean(nombre.trim()) })
               }} />
        <span className="vision-confianza" title="Que tan cerrado quedo el rectangulo">
          {Math.round(clase.confianza * 100)}%
        </span>
      </div>

      {clase.atributos.length > 0 && (
        <ul className="vision-atributos">
          {clase.atributos.map((a) => (
            <li key={a.ref}>
              <label>
                <input type="checkbox"
                       checked={Boolean(mio.atributos[a.ref])}
                       disabled={!a.texto?.trim()}
                       onChange={(e) => cambiar({
                         atributos: { ...mio.atributos, [a.ref]: e.target.checked },
                       })} />
                {a.texto?.trim()
                  ? a.texto
                  : <span className="tenue">(renglon sin leer, {a.caja.ancho}×{a.caja.alto} px)</span>}
              </label>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}

/**
 * La multiplicidad que se va a guardar, o null si no es una.
 *
 * Se valida en vez de mandar el texto tal cual porque nadie lo valida despues: el
 * comando la guarda como viene, y de ahi la lee el derivador logico. Un "l..*"
 * leido con una ele en vez de un uno no es un error visible --se ve casi igual--
 * pero cambia el esquema: "contiene un asterisco" es lo que decide si hace falta
 * una tabla puente, y "empieza con 1" lo que decide si la FK va NOT NULL.
 *
 * Vacio es una respuesta valida y significa "no se sabe": el derivador asume 1:N y
 * lo AVISA, que es mejor que inventar un numero.
 */
function multiplicidad(texto: string): string | null {
  const limpio = texto.replace(/\s+/g, '')
  if (!limpio) return null
  return /^(\d+|\*)(\.\.(\d+|\*))?$/.test(limpio) ? limpio : null
}

function Linea({ relacion, propuesta, estado, tocar }: {
  relacion: RelacionDetectada
  propuesta: DeteccionPropuesta
  estado: Borrador
  tocar: (f: (b: Borrador) => Borrador) => void
}) {
  const mia = estado.relaciones[relacion.ref]
  const nombreDe = (ref: string) =>
    estado.clases[ref]?.nombre.trim() || propuesta.clases.find((c) => c.ref === ref)?.ref || ref
  const cambiar = (cambios: Partial<Borrador['relaciones'][string]>) =>
    tocar((b) => ({ ...b, relaciones: { ...b.relaciones, [relacion.ref]: { ...mia, ...cambios } } }))

  return (
    <div className={mia.aceptada ? 'vision-item' : 'vision-item descartado'}>
      <div className="vision-fila">
        <input type="checkbox" checked={mia.aceptada}
               onChange={(e) => cambiar({ aceptada: e.target.checked })} />
        <span className="vision-nombre">
          {nombreDe(relacion.origenRef)} — {nombreDe(relacion.destinoRef)}
        </span>
        {/* El extremo no se clasifico: asociacion es la lectura conservadora,
            porque no impone ON DELETE CASCADE ni PK compartida. */}
        <select value={mia.kind} onChange={(e) => cambiar({ kind: e.target.value as RelationKind })}>
          {TIPOS.map((t) => <option key={t.id} value={t.id}>{t.label}</option>)}
        </select>
      </div>
      <div className="vision-fila">
        {/* Editables aunque vengan leidas: son dos caracteres para escribir y
            deciden el esquema derivado. Un 0..* en los dos lados es lo que
            convierte la relacion en una tabla puente. */}
        <label className="tenue">
          Mult. {nombreDe(relacion.origenRef)}
          <input className="vision-mult" value={mia.origen} placeholder="1"
                 onChange={(e) => cambiar({ origen: e.target.value })} />
        </label>
        <label className="tenue">
          Mult. {nombreDe(relacion.destinoRef)}
          <input className="vision-mult" value={mia.destino} placeholder="0..*"
                 onChange={(e) => cambiar({ destino: e.target.value })} />
        </label>
        {(mia.origen.trim() && !multiplicidad(mia.origen))
          || (mia.destino.trim() && !multiplicidad(mia.destino)) ? (
          <span className="tenue">
            Una multiplicidad se escribe 1, 0..1, 1..* o 0..*; lo que no tenga esa
            forma se manda vacio y el derivador asume 1:N avisando.
          </span>
        ) : null}
      </div>
    </div>
  )
}
