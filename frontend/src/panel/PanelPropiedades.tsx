import { useEffect, useState } from 'react'
import { SIMBOLO_VISIBILIDAD, esEmbebible, esEnumeracion } from '../contracts/command'
import type {
  InheritanceStrategy, UmlAttribute, UmlClass, UmlMethod, Visibility,
} from '../contracts/command'

const TIPOS = ['string', 'text', 'int', 'long', 'decimal', 'boolean', 'date', 'datetime', 'uuid']

/** Los que admiten rango (Regla 9): en Java son numeros. */
const NUMERICOS = ['int', 'long', 'decimal']

/** "" -> null (sin limite); cualquier otra cosa viaja como texto y el backend la valida. */
const limite = (v: string) => (v.trim() === '' ? null : v.trim())

/** Las banderas ER: lo que hace que la vista Chen diga algo. */
const BANDERAS: { campo: keyof UmlAttribute; etiqueta: string; ayuda: string }[] = [
  { campo: 'pk', etiqueta: 'PK', ayuda: 'Clave. Un UUID técnico como idObra representa la PK física id; una clave de negocio queda UNIQUE NOT NULL.' },
  { campo: 'required', etiqueta: 'REQ', ayuda: 'Obligatorio: NOT NULL' },
  { campo: 'multivalued', etiqueta: 'MULTI', ayuda: 'Multivaluado: doble ovalo en Chen; sale a su propia tabla por 1FN (Regla 12)' },
  { campo: 'derived', etiqueta: 'DERIV', ayuda: 'Derivado: ovalo punteado en Chen; se calcula, no se persiste' },
  { campo: 'partialKey', etiqueta: 'PARCIAL', ayuda: 'Clave parcial de entidad debil: forma la PK compuesta junto con la del dueno (Regla 11)' },
]

export interface NuevoMetodo {
  name: string
  returnType: string
  visibility: Visibility
  params: { name: string; type: string }[]
}

/** "+ prestar(socio: uuid, dias: int): boolean" */
function firmaDe(m: UmlMethod): string {
  const params = m.params.map((p) => p.name + ': ' + p.type).join(', ')
  const ret = m.returnType && m.returnType !== 'void' ? ': ' + m.returnType : ''
  return SIMBOLO_VISIBILIDAD[m.visibility] + ' ' + m.name + '(' + params + ')' + ret
}

/**
 * "socio: uuid, dias: int" -> [{name, type}]. Devuelve el mensaje de error si
 * algo no cierra, en vez de inventar un tipo.
 */
function parsearParametros(texto: string, tipos: string[]): { name: string; type: string }[] | string {
  const limpio = texto.trim()
  if (!limpio) return []
  const params: { name: string; type: string }[] = []
  for (const trozo of limpio.split(',')) {
    const [nombre, tipo = 'string'] = trozo.split(':').map((x) => x.trim())
    if (!nombre) return 'Falta el nombre de un parametro.'
    if (!tipos.includes(tipo)) {
      return 'Tipo desconocido: "' + tipo + '". Usá uno de: ' + tipos.join(', ') + '.'
    }
    if (params.some((p) => p.name === nombre)) {
      return 'El parametro "' + nombre + '" esta repetido.'
    }
    params.push({ name: nombre, type: tipo })
  }
  return params
}

/** Espejo de InferenciaDeAtributos.Sugerencia. */
export interface SugerenciaAtributo {
  rol: 'ATRIBUTO' | 'IDENTIDAD' | 'CLAVE_NATURAL' | 'RELACION'
  dataType: string
  claseRelacionada: string | null
  claseRelacionadaId: string | null
  confianza: 'ALTA' | 'MEDIA' | 'BAJA'
  motivo: string
}

interface Props {
  clase: UmlClass
  /** Enumeraciones del modelo: se ofrecen como tipo de atributos y parametros. */
  enumeraciones: string[]
  /** Clases de valor (Regla 5): se ofrecen como tipo de atributo, no de parametro. */
  embebibles: string[]
  /** Tiene subclases y no hereda de nadie: aca se elige como se mapea la jerarquia. */
  esRaizDeJerarquia: boolean
  onCambiarEstrategia: (s: InheritanceStrategy) => void
  bloqueadaPorOtro: string | null
  /**
   * El rol del usuario no permite editar (Observador, ADR-026): el panel se ve
   * completo pero deshabilitado. Solo lo refleja; el servidor rechaza igual.
   */
  soloLecturaPorRol?: boolean
  onRenombrar: (nombre: string) => void
  onAgregarAtributo: (nombre: string, dataType: string, extra?: Record<string, unknown>) => void
  /** Tipo y rol sugeridos por el nombre (backend). null si no se pudo consultar. */
  sugerirAtributo: (nombre: string) => Promise<SugerenciaAtributo | null>
  /** Convierte una FK escrita como atributo en la relacion que realmente es. */
  onRelacionarCon: (claseDestinoId: string) => void
  onActualizarAtributo: (attributeId: string, cambios: Record<string, unknown>) => void
  onQuitarAtributo: (attributeId: string) => void
  /**
   * No hay UPDATE_METHOD: el catalogo de comandos es cerrado (ADR-014), asi
   * que cambiarle la firma a un metodo es quitarlo y volver a agregarlo.
   */
  onAgregarMetodo: (m: NuevoMetodo) => void
  onQuitarMetodo: (methodId: string) => void
  onBorrarClase: () => void
  onCerrar: () => void
}

export function PanelPropiedades({
  clase, enumeraciones, embebibles, esRaizDeJerarquia, sugerirAtributo, onRelacionarCon, onCambiarEstrategia, bloqueadaPorOtro, soloLecturaPorRol = false, onRenombrar, onAgregarAtributo,
  onActualizarAtributo, onQuitarAtributo, onAgregarMetodo, onQuitarMetodo,
  onBorrarClase, onCerrar,
}: Props) {
  const [nuevoNombre, setNuevoNombre] = useState('')
  const [nuevoTipo, setNuevoTipo] = useState('string')
  const [nuevoMetodo, setNuevoMetodo] = useState('')
  const [retorno, setRetorno] = useState('void')
  const [visibilidad, setVisibilidad] = useState<Visibility>('PUBLIC')
  const [parametros, setParametros] = useState('')
  const [errorMetodo, setErrorMetodo] = useState<string | null>(null)
  const soloLectura = bloqueadaPorOtro !== null || soloLecturaPorRol
  const esEnum = esEnumeracion(clase)
  const esValor = esEmbebible(clase)
  const tipos = [...TIPOS, ...enumeraciones]
  // Un valor no puede contener otro valor: anidar @Embeddable no esta soportado.
  const tiposDeAtributo = esValor ? tipos : [...tipos, ...embebibles]

  const [sugerencia, setSugerencia] = useState<SugerenciaAtributo | null>(null)
  // Si el usuario ya eligio el tipo a mano, la sugerencia no se lo pisa.
  const [tipoTocado, setTipoTocado] = useState(false)

  // Mientras escribe el nombre, se pregunta al backend que parece ser. Con
  // espera: una consulta por tecla no aporta nada y satura la red.
  useEffect(() => {
    const n = nuevoNombre.trim()
    if (!n || esEnum) { setSugerencia(null); return }
    let vigente = true
    const espera = setTimeout(() => {
      sugerirAtributo(n).then((s) => {
        if (!vigente || !s) return
        setSugerencia(s)
        // Tambien con RELACION (uuid): si igual lo agregan como atributo, que no
        // quede con el tipo que habia sugerido el nombre anterior.
        if (!tipoTocado && tiposDeAtributo.includes(s.dataType)) {
          setNuevoTipo(s.dataType)
        }
      }).catch(() => { if (vigente) setSugerencia(null) })
    }, 250)
    return () => { vigente = false; clearTimeout(espera) }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [nuevoNombre, clase.id])

  const limpiarAlta = () => {
    setNuevoNombre('')
    setSugerencia(null)
    setTipoTocado(false)
  }

  const agregar = (extra?: Record<string, unknown>) => {
    const n = nuevoNombre.trim()
    if (!n) return
    // Un literal no tiene tipo propio: el backend lo ignora, pero la columna
    // del metamodelo es obligatoria.
    onAgregarAtributo(n, esEnum ? 'string' : nuevoTipo, extra)
    limpiarAlta()
  }

  const agregarMetodo = () => {
    const n = nuevoMetodo.trim()
    if (!n) return
    const params = parsearParametros(parametros, tipos)
    if (typeof params === 'string') {
      // Un tipo desconocido se avisa en vez de degradarse a string sin decirlo:
      // el usuario descubriria el cambio recien leyendo el codigo generado.
      setErrorMetodo(params)
      return
    }
    setErrorMetodo(null)
    onAgregarMetodo({ name: n, returnType: retorno, visibility: visibilidad, params })
    setNuevoMetodo('')
    setParametros('')
  }

  return (
    <section className="props">
      <div className="props-cabecera">
        <h3>Propiedades</h3>
        <button className="tb-btn tb-icono" onClick={onCerrar} title="Cerrar">✕</button>
      </div>

      {soloLecturaPorRol ? (
        <p className="props-bloqueada">Solo lectura: tu rol no permite editar.</p>
      ) : bloqueadaPorOtro !== null && (
        <p className="props-bloqueada">La esta editando {bloqueadaPorOtro}.</p>
      )}

      <label className="props-campo">
        <span>Nombre de la clase</span>
        <input
          defaultValue={clase.name}
          key={clase.id + clase.name}
          disabled={soloLectura}
          onBlur={(e) => {
            const v = e.target.value.trim()
            if (v && v !== clase.name) onRenombrar(v)
          }}
        />
      </label>

      {esRaizDeJerarquia && (
        <div className="props-herencia">
          <h4>Herencia (Regla 4)</h4>
          <div className="props-estrategias">
            {(['JOINED', 'SINGLE_TABLE'] as InheritanceStrategy[]).map((s) => (
              <label key={s} className={clase.inheritanceStrategy === s ? 'bandera activa' : 'bandera'}>
                <input type="radio" name={'estrategia-' + clase.id} disabled={soloLectura}
                       checked={clase.inheritanceStrategy === s}
                       onChange={() => onCambiarEstrategia(s)} />
                {s}
              </label>
            ))}
          </div>
          <p className="tenue">
            {clase.inheritanceStrategy === 'SINGLE_TABLE'
              ? 'Toda la jerarquía en una tabla con columna "tipo". Sin JOINs, pero las columnas '
                + 'de cada subclase admiten NULL: su obligatorio se valida en el DTO.'
              : 'Una tabla por clase; la hija comparte la clave primaria con la madre. '
                + 'Normalizado, cuesta un JOIN por nivel.'}
          </p>
        </div>
      )}

      <h4>{esEnum ? 'Literales' : 'Atributos'}</h4>
      {esValor && (
        <p className="tenue">
          Valor embebido: no tiene tabla ni identidad. Sus atributos se guardan como columnas de
          quien lo use (<code>domicilio_calle</code>) y en Java es un <code>@Embeddable</code>.
          Usalo como tipo de un atributo o componelo 1..1.
        </p>
      )}
      {esEnum && (
        <p className="tenue">
          Cada literal es un valor posible. En la base queda como <code>CHECK</code> y en
          Java como <code>enum</code> guardado por nombre. Para usarla, poné esta enumeración
          como tipo de un atributo de otra clase.
        </p>
      )}
      {clase.attributes.length === 0 && (
        <p className="tenue">{esEnum ? 'Sin literales todavía.' : 'Sin atributos todavía.'}</p>
      )}

      <ul className="props-attrs">
        {clase.attributes.map((a) => (
          <li key={a.id}>
            <div className="props-attr-fila">
              <input
                className="props-attr-nombre"
                defaultValue={a.name}
                key={a.id + a.name}
                disabled={soloLectura}
                onBlur={(e) => {
                  const v = e.target.value.trim()
                  if (v && v !== a.name) onActualizarAtributo(a.id, { name: v })
                }}
              />
              {/* UML 2.5.1: una Property lleva visibilidad, igual que una
                  Operation. Un literal de enumeracion no. */}
              {!esEnum && (
                <select
                  value={a.visibility}
                  disabled={soloLectura}
                  className="props-visibilidad"
                  title="Visibilidad UML"
                  onChange={(e) => onActualizarAtributo(a.id, { visibility: e.target.value })}
                >
                  {(['PRIVATE', 'PUBLIC', 'PROTECTED', 'PACKAGE'] as Visibility[]).map((v) => (
                    <option key={v} value={v}>{SIMBOLO_VISIBILIDAD[v]}</option>
                  ))}
                </select>
              )}
              {!esEnum && (
                <select
                  value={a.dataType}
                  disabled={soloLectura}
                  onChange={(e) => onActualizarAtributo(a.id, { dataType: e.target.value })}
                >
                  {/* Si el tipo era una enumeracion que ya no existe, se sigue mostrando. */}
                  {(tiposDeAtributo.includes(a.dataType) ? tiposDeAtributo : [...tiposDeAtributo, a.dataType])
                    .map((t) => <option key={t} value={t}>{t}</option>)}
                </select>
              )}
              <button className="props-quitar" disabled={soloLectura}
                      onClick={() => onQuitarAtributo(a.id)}
                      title={esEnum ? 'Quitar literal' : 'Quitar atributo'}>✕</button>
            </div>
            {!esEnum && <div className="props-banderas">
              {BANDERAS.map((b) => (
                <label key={b.campo} title={b.ayuda}
                       className={a[b.campo] ? 'bandera activa' : 'bandera'}>
                  <input type="checkbox" checked={Boolean(a[b.campo])} disabled={soloLectura}
                         onChange={(e) => onActualizarAtributo(a.id, { [b.campo]: e.target.checked })} />
                  {b.etiqueta}
                </label>
              ))}
            </div>}
            {!esEnum && NUMERICOS.includes(a.dataType) && (
              <div className="props-rango" title="Regla 9: sale como CHECK en la base y @Min/@Max en el DTO">
                <span>rango</span>
                <input placeholder="mín" defaultValue={a.minValue ?? ''} disabled={soloLectura}
                       key={a.id + 'min' + (a.minValue ?? '')}
                       onBlur={(e) => {
                         const v = limite(e.target.value)
                         if (v !== (a.minValue == null ? null : String(a.minValue))) {
                           onActualizarAtributo(a.id, { minValue: v })
                         }
                       }} />
                <span>a</span>
                <input placeholder="máx" defaultValue={a.maxValue ?? ''} disabled={soloLectura}
                       key={a.id + 'max' + (a.maxValue ?? '')}
                       onBlur={(e) => {
                         const v = limite(e.target.value)
                         if (v !== (a.maxValue == null ? null : String(a.maxValue))) {
                           onActualizarAtributo(a.id, { maxValue: v })
                         }
                       }} />
              </div>
            )}
          </li>
        ))}
      </ul>

      <div className="props-agregar">
        <input placeholder={esEnum ? 'Nuevo literal' : 'Nuevo atributo'} value={nuevoNombre}
               disabled={soloLectura}
               onChange={(e) => setNuevoNombre(e.target.value)}
               onKeyDown={(e) => { if (e.key === 'Enter') agregar() }} />
        {!esEnum && (
          <select value={nuevoTipo} disabled={soloLectura}
                  onChange={(e) => { setNuevoTipo(e.target.value); setTipoTocado(true) }}>
            {tiposDeAtributo.map((t) => <option key={t} value={t}>{t}</option>)}
          </select>
        )}
        <button className="tb-btn" disabled={soloLectura || !nuevoNombre.trim()} onClick={() => agregar()}>
          Agregar
        </button>
      </div>
      {sugerencia && nuevoNombre.trim() && (
        <div className={'props-sugerencia confianza-' + sugerencia.confianza.toLowerCase()}>
          {sugerencia.rol === 'ATRIBUTO' && (
            <span><strong>{sugerencia.dataType}</strong> · {sugerencia.motivo}</span>
          )}
          {sugerencia.rol === 'IDENTIDAD' && (
            <span>No hace falta: {sugerencia.motivo}</span>
          )}
          {sugerencia.rol === 'CLAVE_NATURAL' && (
            <>
              <span>{sugerencia.motivo}</span>
              <button className="tb-btn" disabled={soloLectura} onClick={() => agregar({ pk: true })}>
                Agregar como clave
              </button>
            </>
          )}
          {sugerencia.rol === 'RELACION' && sugerencia.claseRelacionadaId && (
            <>
              <span>Es una relación: {sugerencia.motivo}</span>
              <button className="tb-btn tb-primario" disabled={soloLectura}
                      onClick={() => { onRelacionarCon(sugerencia.claseRelacionadaId!); limpiarAlta() }}>
                Crear relación con {sugerencia.claseRelacionada}
              </button>
            </>
          )}
        </div>
      )}

      {!esEnum && !esValor && <>
      <h4>Metodos</h4>
      <p className="tenue">
        Regla 6: no van al DDL —una tabla guarda estado, no comportamiento—
        pero sí al <code>Service</code> generado, como stub con esta firma.
      </p>

      <ul className="props-metodos">
        {clase.methods.map((m) => (
          <li key={m.id}>
            <code>{firmaDe(m)}</code>
            <button className="props-quitar" disabled={soloLectura}
                    onClick={() => onQuitarMetodo(m.id)}
                    title="Quitar metodo (para cambiarle la firma, quitalo y agregalo de nuevo)">✕</button>
          </li>
        ))}
      </ul>

      <div className="props-metodo-nuevo">
        <div className="props-attr-fila">
          <input className="props-attr-nombre" placeholder="Nuevo metodo" value={nuevoMetodo}
                 disabled={soloLectura} onChange={(e) => setNuevoMetodo(e.target.value)} />
          <select value={visibilidad} disabled={soloLectura}
                  onChange={(e) => setVisibilidad(e.target.value as Visibility)}
                  title="Visibilidad UML">
            {(['PUBLIC', 'PRIVATE', 'PROTECTED', 'PACKAGE'] as Visibility[]).map((v) => (
              <option key={v} value={v}>{SIMBOLO_VISIBILIDAD[v]} {v.toLowerCase()}</option>
            ))}
          </select>
        </div>
        <div className="props-attr-fila">
          <input className="props-attr-nombre" placeholder="socio: uuid, dias: int"
                 value={parametros} disabled={soloLectura}
                 title="Parametros separados por coma, con su tipo despues de los dos puntos"
                 onChange={(e) => setParametros(e.target.value)} />
          <select value={retorno} disabled={soloLectura}
                  onChange={(e) => setRetorno(e.target.value)} title="Tipo de retorno">
            {['void', ...tipos].map((t) => <option key={t} value={t}>{t}</option>)}
          </select>
          <button className="tb-btn" disabled={soloLectura || !nuevoMetodo.trim()}
                  onClick={agregarMetodo}>Agregar</button>
        </div>
        {errorMetodo && <p className="props-error">{errorMetodo}</p>}
      </div>
      </>}

      <button className="props-borrar" disabled={soloLectura} onClick={onBorrarClase}>
        Borrar la clase
      </button>
    </section>
  )
}
