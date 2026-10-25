import { useState } from 'react'
import type { RelationKind, UmlClass, UmlRelationship } from '../contracts/command'
import { NotacionRelacion } from '../toolbar/NotacionRelacion'
import { esNaM, nombreSugerido } from './reificar'

const TIPOS: { id: RelationKind; label: string }[] = [
  { id: 'ASSOCIATION', label: 'Asociacion' },
  { id: 'AGGREGATION', label: 'Agregacion' },
  { id: 'COMPOSITION', label: 'Composicion' },
  { id: 'GENERALIZATION', label: 'Generalizacion' },
  { id: 'DEPENDENCY', label: 'Dependencia' },
]

/** Las de siempre. El resto se escribe a mano con "otro…". */
const CARDINALIDADES = ['1', '0..1', '1..*', '0..*']

/**
 * La derivacion decide 1:N o N:M buscando un `*`, asi que un valor mal
 * tipeado no da error: cambia el esquema en silencio. Por eso se valida.
 */
const FORMATO = /^(\*|\d+|\d+\.\.(\d+|\*))$/

function Cardinalidad({ valor, disabled, onCambiar }: {
  valor: string | null | undefined
  disabled: boolean
  onCambiar: (v: string) => void
}) {
  const actual = valor ?? ''
  const esComun = CARDINALIDADES.includes(actual)
  const [libre, setLibre] = useState(!esComun && actual !== '')
  const [texto, setTexto] = useState(actual)
  const valido = texto.trim() === '' || FORMATO.test(texto.trim())

  if (libre) {
    return (
      <div className="rel-card-libre">
        <input
          className={valido ? '' : 'invalido'}
          value={texto}
          disabled={disabled}
          placeholder="2..5"
          onChange={(e) => setTexto(e.target.value)}
          onBlur={() => { if (valido && texto.trim() !== actual) onCambiar(texto.trim()) }}
        />
        <button className="rel-volver" disabled={disabled}
                onClick={() => { setLibre(false); setTexto('') }}>listas</button>
        {!valido && <span className="rel-error">Usá 1, 0..1, 2..5 o 0..*</span>}
      </div>
    )
  }

  return (
    <select value={esComun ? actual : ''} disabled={disabled}
            onChange={(e) => {
              if (e.target.value === '__otro') { setLibre(true); setTexto(actual); return }
              onCambiar(e.target.value)
            }}>
      <option value="">(sin definir)</option>
      {CARDINALIDADES.map((c) => <option key={c} value={c}>{c}</option>)}
      <option value="__otro">otro…</option>
    </select>
  )
}

interface Props {
  relacion: UmlRelationship
  origen: UmlClass | undefined
  destino: UmlClass | undefined
  soloLectura: boolean
  /** Otra sesion tiene el lease de esta relacion (ADR-028): se ve, no se edita. */
  bloqueadaPorOtro: string | null
  onActualizar: (cambios: Record<string, unknown>) => void
  onBorrar: () => void
  /** Devuelve el motivo si no se pudo, o null si el lote salio. */
  onReificar: (nombre: string, pkCompuesta: boolean) => string | null
  onCerrar: () => void
}

export function PanelRelacion({
  relacion, origen, destino, soloLectura: soloLecturaPorRol, bloqueadaPorOtro,
  onActualizar, onBorrar, onReificar, onCerrar,
}: Props) {
  const soloLectura = soloLecturaPorRol || bloqueadaPorOtro !== null
  return (
    <section className="props">
      <div className="props-cabecera">
        <h3>Relacion</h3>
        <button className="tb-btn tb-icono" onClick={onCerrar} title="Cerrar">✕</button>
      </div>

      <p className="tenue rel-extremos">
        {origen?.name ?? '?'} → {destino?.name ?? '?'}
      </p>

      {!soloLecturaPorRol && bloqueadaPorOtro !== null && (
        <p className="props-bloqueada">La esta editando {bloqueadaPorOtro}.</p>
      )}

      <h4>Tipo</h4>
      <div className="rel-tipos">
        {TIPOS.map((t) => (
          <button key={t.id} disabled={soloLectura}
                  className={relacion.kind === t.id ? 'rel-tipo activa' : 'rel-tipo'}
                  onClick={() => onActualizar({ kind: t.id })}>
            <NotacionRelacion kind={t.id} />
            <span>{t.label}</span>
          </button>
        ))}
      </div>

      <label className="props-campo">
        <span>Nombre (se dibuja en el medio)</span>
        <input
          defaultValue={relacion.name ?? ''}
          key={relacion.id + (relacion.name ?? '')}
          placeholder="tiene"
          disabled={soloLectura}
          onBlur={(e) => {
            const v = e.target.value.trim()
            if (v !== (relacion.name ?? '')) onActualizar({ name: v === '' ? null : v })
          }}
        />
      </label>

      <h4>Cardinalidades</h4>
      <div className="rel-cards">
        <label>
          <span>{origen?.name ?? 'origen'}</span>
          <Cardinalidad valor={relacion.sourceMultiplicity} disabled={soloLectura}
                        onCambiar={(v) => onActualizar({ sourceMultiplicity: v === '' ? null : v })} />
        </label>
        <label>
          <span>{destino?.name ?? 'destino'}</span>
          <Cardinalidad valor={relacion.targetMultiplicity} disabled={soloLectura}
                        onCambiar={(v) => onActualizar({ targetMultiplicity: v === '' ? null : v })} />
        </label>
      </div>

      <h4>Roles</h4>
      <p className="tenue">De acá sale el nombre de la columna FK (Regla 3).</p>
      <div className="rel-cards">
        <label>
          <span>{origen?.name ?? 'origen'}</span>
          <input defaultValue={relacion.sourceRole ?? ''} disabled={soloLectura}
                 key={relacion.id + 'sr' + (relacion.sourceRole ?? '')}
                 onBlur={(e) => onActualizar({ sourceRole: e.target.value.trim() || null })} />
        </label>
        <label>
          <span>{destino?.name ?? 'destino'}</span>
          <input defaultValue={relacion.targetRole ?? ''} disabled={soloLectura}
                 key={relacion.id + 'tr' + (relacion.targetRole ?? '')}
                 onBlur={(e) => onActualizar({ targetRole: e.target.value.trim() || null })} />
        </label>
      </div>

      <label className={relacion.identifying ? 'bandera activa rel-ident' : 'bandera rel-ident'}
             title="Relacion identificadora: la entidad debil hereda la PK del dueno (Regla 11)">
        <input type="checkbox" checked={relacion.identifying} disabled={soloLectura}
               onChange={(e) => onActualizar({ identifying: e.target.checked })} />
        IDENTIFICADORA
      </label>

      {esNaM(relacion) && (
        <ClaseIntermedia relacion={relacion} origen={origen} destino={destino}
                         soloLectura={soloLectura} onReificar={onReificar} />
      )}

      <button className="props-borrar" disabled={soloLectura} onClick={onBorrar}>
        Borrar la relacion
      </button>
    </section>
  )
}

/**
 * Convertir la N:M en una clase intermedia dibujada en el canvas.
 *
 * Solo aparece cuando la relación ES N:M, porque es la única que necesita una
 * tabla aparte: en 1:N la FK vive en el lado "muchos" y una clase en el medio
 * sería una tabla de más.
 *
 * Está acá, en el panel de la relación, y no en la barra de herramientas, porque
 * no es un elemento que se dibuje desde cero: es una transformación DE esta
 * relación. Al aplicarla, la N:M se va y quedan dos asociaciones 1:N hacia la
 * clase nueva — que es la reificación de siempre, y lo que permite al fin ponerle
 * atributos (un atributo pertenece a una clase, nunca a una línea).
 */
function ClaseIntermedia({ relacion, origen, destino, soloLectura, onReificar }: {
  relacion: UmlRelationship
  origen: UmlClass | undefined
  destino: UmlClass | undefined
  soloLectura: boolean
  onReificar: (nombre: string, pkCompuesta: boolean) => string | null
}) {
  const [nombre, setNombre] = useState(() => nombreSugerido(relacion, origen, destino))
  const [pkCompuesta, setPkCompuesta] = useState(true)
  const [motivo, setMotivo] = useState<string | null>(null)

  return (
    <section className="rel-intermedia">
      <h4>Clase intermedia</h4>
      <p className="tenue">
        Es una N:M. Convertila en una clase del diagrama para poder arrastrarla,
        conectarle otras clases y <strong>ponerle atributos</strong> (la fecha, la
        cantidad). Las dos FK quedan en ella, igual que en la tabla puente.
      </p>
      <div className="rel-intermedia-fila">
        <input value={nombre} disabled={soloLectura} placeholder="FichaPrestamo"
               onChange={(e) => { setNombre(e.target.value); setMotivo(null) }} />
        <button className="tb-btn" disabled={soloLectura || !nombre.trim()}
                onClick={() => setMotivo(onReificar(nombre, pkCompuesta))}>
          Crear
        </button>
      </div>
      {/* La forma clásica de la tabla puente: VENDE(NroV, CodP, cantidad, precio).
          Sin esto la clase lleva su propio id y nada impide repetir el par. */}
      <label className={pkCompuesta ? 'bandera activa' : 'bandera'}
             title="La clase se identifica por sus dos FK (entidad débil, Regla 11)">
        <input type="checkbox" checked={pkCompuesta} disabled={soloLectura}
               onChange={(e) => { setPkCompuesta(e.target.checked); setMotivo(null) }} />
        PK COMPUESTA POR LAS DOS FK
      </label>
      <p className="tenue">
        {pkCompuesta
          ? 'Como la tabla puente: no se puede repetir el mismo par, y si se borra '
            + 'cualquiera de los dos lados la fila se va con él.'
          : 'La clase lleva su propio id. Hay que agregar un UNIQUE a mano si no '
            + 'querés el mismo par dos veces.'}
      </p>
      {motivo && <p className="rel-error">{motivo}</p>}
    </section>
  )
}
