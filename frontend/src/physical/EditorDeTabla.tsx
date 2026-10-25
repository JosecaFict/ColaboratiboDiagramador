import { useState } from 'react'
import { v4 as uuid } from 'uuid'
import type { UmlClass, UmlRelationship } from '../contracts/command'
import type { TablaFisica } from './esquema'
import {
  NUMERICOS, TIPOS_FISICOS, agregarColumna, cambiarLargo, cambiarObligatorio, cambiarObligatorioFk,
  cambiarOnDelete, cambiarRango, cambiarTipo, cambiarUnico, clasificarFilas, esError, quitarColumna,
  renombrarColumna, renombrarFk, renombrarTabla, tipoDelSelector,
  type Cambio, type Fila, type Resultado,
} from './edicion'

interface Props {
  tabla: TablaFisica
  clases: UmlClass[]
  relaciones: UmlRelationship[]
  /** Otra persona tiene tomada la clase de esta tabla: se ve, no se edita. */
  bloqueadaPor: string | null
  /** Sin conexion no se edita: el cambio no llegaria al bus. */
  puedeEditar: boolean
  enumeraciones: string[]
  /** Se llama al empezar a editar: es cuando se toma el lock de la clase (ADR-004). */
  onEditar: () => void
  onCambio: (c: Cambio) => void
  onIr: (tabla: string) => void
  onCopiar: (texto: string) => void
  ddl: string
}

/**
 * El detalle de una tabla, EDITABLE (ADR-032).
 *
 * Cada control manda un comando sobre el elemento del modelo que produjo la
 * columna (ver edicion.ts). Lo que no sale de esta tabla --la identidad, la
 * herencia, un valor embebido-- queda fijo y dice donde se cambia: ofrecer ahi
 * un control seria prometer algo que el modelo no puede expresar.
 *
 * El lock se toma al ENTRAR a editar (foco en un control), no al mirar: pasar
 * por la Vista Fisica no puede dejarle una clase bloqueada a los demas.
 */
export function EditorDeTabla({
  tabla, clases, relaciones, bloqueadaPor, puedeEditar, enumeraciones,
  onEditar, onCambio, onIr, onCopiar, ddl,
}: Props) {
  const [error, setError] = useState<string | null>(null)
  const [ultimo, setUltimo] = useState<Cambio | null>(null)
  const clase = clases.find((c) => c.id === tabla.claseId) ?? null
  const habilitado = puedeEditar && !bloqueadaPor && clase !== null
  const filas = clasificarFilas(tabla, clases, relaciones)

  /** Todo gesto pasa por aca: un error se muestra, un cambio se manda. */
  const aplicar = (r: Resultado) => {
    if (r === null) return
    if (esError(r)) {
      setError(r.error)
      return
    }
    setError(null)
    setUltimo(r)
    onCambio(r)
  }

  const tipos = [...TIPOS_FISICOS, ...enumeraciones.map((e) => ({ uml: e, etiqueta: `enum ${e}` }))]

  return (
    <section className="fis-detalle" onFocus={(e) => {
      // Solo un control de edicion toma el lock: seguir una FK o copiar el DDL
      // es mirar, y no puede bloquearle la clase a nadie.
      const t = e.target
      if (habilitado && (t instanceof HTMLInputElement || t instanceof HTMLSelectElement)) onEditar()
    }}>
      <header>
        {habilitado && clase ? (
          <input className="fis-in fis-nombre-tabla" defaultValue={tabla.nombre} key={tabla.nombre}
                 title="Renombrar la tabla renombra la clase"
                 onBlur={(e) => aplicar(renombrarTabla(clase, e.target.value))}
                 onKeyDown={(e) => { if (e.key === 'Enter') e.currentTarget.blur() }} />
        ) : (
          <strong>{tabla.nombre}</strong>
        )}
        <span className="tenue">viene de {tabla.origen}</span>
        {bloqueadaPor && <span className="fis-bloqueo">La está editando {bloqueadaPor}</span>}
      </header>

      {!clase && (
        <p className="fis-aviso">
          Tabla derivada: se genera sola a partir de {tabla.origen}. Para cambiarla, editá esa
          relación o ese atributo en Clases.
        </p>
      )}

      <table className="fis-editor">
        <thead>
          <tr>
            <th>columna</th>
            <th>tipo</th>
            <th title="NOT NULL">NN</th>
            <th title="UNIQUE">UQ</th>
            <th>clave y restricciones</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {filas.map((f) => (
            <FilaDeColumna key={f.columna.nombre} fila={f} clase={clase} habilitado={habilitado}
                           porQueNo={bloqueadaPor ? `la está editando ${bloqueadaPor}`
                             : 'no hay conexión con el servidor'}
                           tipos={tipos} aplicar={aplicar} onIr={onIr} />
          ))}
          {habilitado && clase && <FilaNueva clase={clase} tipos={tipos} aplicar={aplicar} />}
        </tbody>
      </table>

      {error && <p className="fis-error">{error}</p>}
      {ultimo && !error && (
        <p className="fis-efecto">
          <span>{ultimo.efecto}</span> <code>{ultimo.type}</code>
        </p>
      )}

      {tabla.indices.length > 0 && (
        <p className="fis-nota">
          <strong>Índices:</strong>{' '}
          {tabla.indices.map((i) => i.nombre).join(', ')}
          <span className="tenue"> — PostgreSQL no indexa las FK solo.</span>
        </p>
      )}

      <details className="fis-ddl">
        <summary>
          DDL de esta tabla
          <button className="marca" onClick={(e) => { e.preventDefault(); onCopiar(ddl) }}>
            copiar
          </button>
        </summary>
        {/* Recortado del mismo script que se descarga: no es una version
            reconstruida para mostrar. */}
        <pre>{ddl}</pre>
      </details>

      {tabla.referidaPor.length > 0 && (
        <p className="fis-nota">
          <strong>La referencian:</strong>{' '}
          {tabla.referidaPor.map((n) => (
            <button key={n} className="marca fk" onClick={() => onIr(n)}>{n}</button>
          ))}
        </p>
      )}
    </section>
  )
}

interface PropsFila {
  fila: Fila
  clase: UmlClass | null
  habilitado: boolean
  /** Por que una columna editable no se puede editar AHORA. */
  porQueNo: string
  tipos: { uml: string; etiqueta: string }[]
  aplicar: (r: Resultado) => void
  onIr: (tabla: string) => void
}

/** Confirmar al salir del campo o con Enter, como en el panel de Clases. */
const alSoltar = (f: (valor: string) => void) => ({
  onBlur: (e: React.FocusEvent<HTMLInputElement>) => f(e.target.value),
  onKeyDown: (e: React.KeyboardEvent<HTMLInputElement>) => { if (e.key === 'Enter') e.currentTarget.blur() },
})

function FilaDeColumna({ fila, clase, habilitado, porQueNo, tipos, aplicar, onIr }: PropsFila) {
  const c = fila.columna
  const marcas = (
    <>
      {c.pk && <span className="marca pk">PK</span>}
      {c.referencia && (
        // Navegar por la FK es lo que hace usable un esquema de 20 tablas:
        // se sigue la relacion en vez de buscarla.
        <button className="marca fk" onClick={() => onIr(c.referencia!)} title={c.restriccionFk ?? undefined}>
          → {c.referencia}
        </button>
      )}
      {c.check && <span className="marca check" title={c.check}>CHECK</span>}
    </>
  )

  if (fila.tipo === 'atributo' && habilitado && clase) {
    const a = fila.atributo
    // Clave natural (Regla 1): UNIQUE NOT NULL siempre. Clave parcial de una
    // debil: es parte de la PK. En los dos casos la casilla no decide nada.
    const fijaPorClave = a.pk || (a.partialKey && c.pk)
    const numerico = NUMERICOS.includes(tipoDelSelector(a.dataType))
    const opciones = tipos.some((t) => t.uml === tipoDelSelector(a.dataType))
      ? tipos : [...tipos, { uml: a.dataType, etiqueta: a.dataType }]
    return (
      <tr>
        <td>
          <input className="fis-in fis-col" defaultValue={c.nombre} key={a.id + c.nombre}
                 title={`Atributo ${clase.name}.${a.name}`}
                 {...alSoltar((v) => aplicar(renombrarColumna(clase, a, v)))} />
        </td>
        <td className="fis-celda-tipo">
          <select className="fis-in" value={tipoDelSelector(a.dataType)}
                  onChange={(e) => aplicar(cambiarTipo(clase, a, e.target.value))}>
            {opciones.map((t) => <option key={t.uml} value={t.uml}>{t.etiqueta}</option>)}
          </select>
          {tipoDelSelector(a.dataType) === 'string' && (
            <input className="fis-in fis-largo" placeholder="255" defaultValue={a.length ?? ''}
                   key={a.id + 'largo' + (a.length ?? '')} title="Largo del varchar (vacío = 255)"
                   {...alSoltar((v) => aplicar(cambiarLargo(clase, a, v)))} />
          )}
        </td>
        <td>
          <input type="checkbox" checked={!c.nullable} disabled={fijaPorClave}
                 title={fijaPorClave ? 'Es clave: NOT NULL siempre (Regla 1 / 11)' : 'NOT NULL'}
                 onChange={(e) => aplicar(cambiarObligatorio(clase, a, e.target.checked))} />
        </td>
        <td>
          <input type="checkbox" checked={c.unique} disabled={a.pk}
                 title={a.pk ? 'Clave: UNIQUE y NOT NULL; los UUID técnicos representan la identidad física' : 'UNIQUE'}
                 onChange={(e) => aplicar(cambiarUnico(clase, a, e.target.checked))} />
        </td>
        <td>
          {marcas}
          {numerico && <Rango key={a.id + (a.minValue ?? '') + '|' + (a.maxValue ?? '')}
                              minimo={a.minValue} maximo={a.maxValue}
                              onCambiar={(min, max) => aplicar(cambiarRango(clase, a, min, max))} />}
        </td>
        <td>
          <button className="props-quitar" title={`Quitar ${clase.name}.${a.name} del modelo`}
                  onClick={() => aplicar(quitarColumna(clase, a))}>✕</button>
        </td>
      </tr>
    )
  }

  if (fila.tipo === 'fk' && habilitado) {
    const { relacion: r, extremo, apuntada } = fila
    const composicion = r.kind === 'COMPOSITION'
    return (
      <tr>
        <td>
          <input className="fis-in fis-col" defaultValue={c.nombre} key={r.id + c.nombre}
                 title="El nombre de la FK es el rol del extremo apuntado (Regla 3)"
                 {...alSoltar((v) => aplicar(renombrarFk(r, extremo, apuntada, v)))} />
        </td>
        <td className="fis-tipo">{c.tipo}</td>
        <td>
          <input type="checkbox" checked={!c.nullable} disabled={composicion}
                 title={composicion ? 'Composición: la parte no existe sin el todo, la FK es NOT NULL siempre'
                   : `NOT NULL = el extremo ${apuntada.name} pasa a 1; sin tildar, 0..1`}
                 onChange={(e) => aplicar(cambiarObligatorioFk(r, extremo, apuntada, e.target.checked))} />
        </td>
        <td title="1:1 o 1:N se decide con las multiplicidades en Clases">
          {c.unique && <span className="marca">UQ</span>}
        </td>
        <td>
          {marcas}
          <select className="fis-in fis-ondelete" value={c.onDelete ?? 'RESTRICT'}
                  title="ON DELETE decide el tipo de relación: CASCADE = composición, SET NULL = agregación"
                  onChange={(e) => aplicar(cambiarOnDelete(r, e.target.value))}>
            <option value="RESTRICT">ON DELETE RESTRICT</option>
            <option value="SET NULL" disabled={!c.nullable}>ON DELETE SET NULL</option>
            <option value="CASCADE">ON DELETE CASCADE</option>
          </select>
        </td>
        <td />
      </tr>
    )
  }

  // Fija: se ve igual que antes, y dice por que no se edita aca.
  const motivo = fila.tipo === 'fija' ? fila.motivo : `No se puede editar ahora: ${porQueNo}.`
  return (
    <tr className="fis-fija" title={motivo}>
      <td className="fis-col">{c.nombre}</td>
      <td className="fis-tipo">{c.tipo}</td>
      <td>{!c.nullable && '✓'}</td>
      <td>{c.unique && '✓'}</td>
      <td>
        {marcas}
        {c.onDelete && <span className="marca sutil">ON DELETE {c.onDelete}</span>}
      </td>
      <td><span className="fis-porque" aria-label={motivo}>?</span></td>
    </tr>
  )
}

/** Minimo y maximo juntos: el rango se valida entero (minimo <= maximo). */
function Rango({ minimo, maximo, onCambiar }: {
  minimo?: number | null
  maximo?: number | null
  onCambiar: (min: string, max: string) => void
}) {
  const [min, setMin] = useState(minimo == null ? '' : String(minimo))
  const [max, setMax] = useState(maximo == null ? '' : String(maximo))
  const enter = (e: React.KeyboardEvent<HTMLInputElement>) => { if (e.key === 'Enter') e.currentTarget.blur() }
  return (
    // Se confirma al salir del PAR, no de cada campo: pasar del minimo al
    // maximo no puede mandar un rango a medio escribir.
    <span className="fis-rango" title="Regla 9: CHECK en la base y @Min/@Max en el DTO"
          onBlur={(e) => { if (!e.currentTarget.contains(e.relatedTarget as Node | null)) onCambiar(min, max) }}>
      ≥ <input className="fis-in" value={min} placeholder="mín" onKeyDown={enter}
               onChange={(e) => setMin(e.target.value)} />
      ≤ <input className="fis-in" value={max} placeholder="máx" onKeyDown={enter}
               onChange={(e) => setMax(e.target.value)} />
    </span>
  )
}

/** Una columna nueva es un atributo nuevo de la clase de la tabla. */
function FilaNueva({ clase, tipos, aplicar }: {
  clase: UmlClass
  tipos: { uml: string; etiqueta: string }[]
  aplicar: (r: Resultado) => void
}) {
  const [nombre, setNombre] = useState('')
  const [tipo, setTipo] = useState('string')
  const [obligatoria, setObligatoria] = useState(false)
  const [unica, setUnica] = useState(false)

  const agregar = () => {
    if (!nombre.trim()) return
    const r = agregarColumna(clase, uuid(), nombre, tipo, obligatoria, unica)
    aplicar(r)
    if (r && !esError(r)) {
      setNombre('')
      setObligatoria(false)
      setUnica(false)
    }
  }

  return (
    <tr className="fis-nueva">
      <td>
        <input className="fis-in fis-col" placeholder="nueva_columna" value={nombre}
               onChange={(e) => setNombre(e.target.value)}
               onKeyDown={(e) => { if (e.key === 'Enter') agregar() }} />
      </td>
      <td>
        <select className="fis-in" value={tipo} onChange={(e) => setTipo(e.target.value)}>
          {tipos.map((t) => <option key={t.uml} value={t.uml}>{t.etiqueta}</option>)}
        </select>
      </td>
      <td><input type="checkbox" checked={obligatoria} title="NOT NULL"
                 onChange={(e) => setObligatoria(e.target.checked)} /></td>
      <td><input type="checkbox" checked={unica} title="UNIQUE"
                 onChange={(e) => setUnica(e.target.checked)} /></td>
      <td colSpan={2}>
        <button className="tb-btn" disabled={!nombre.trim()} onClick={agregar}>Agregar columna</button>
      </td>
    </tr>
  )
}
