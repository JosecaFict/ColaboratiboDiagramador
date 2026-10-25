import { useState } from 'react'
import { ESTEREOTIPO_EMBEBIBLE, ESTEREOTIPO_ENUM, type RelationKind } from '../contracts/command'
import { NotacionRelacion } from './NotacionRelacion'

const TIPOS_RELACION: { id: RelationKind; label: string }[] = [
  { id: 'ASSOCIATION', label: 'Asociacion' },
  { id: 'AGGREGATION', label: 'Agregacion' },
  { id: 'COMPOSITION', label: 'Composicion' },
  { id: 'GENERALIZATION', label: 'Generalizacion' },
  { id: 'DEPENDENCY', label: 'Dependencia' },
]

interface Props {
  disabled: boolean
  /**
   * Generar es una LECTURA del modelo: no toma lock ni escribe. Por eso tiene
   * su propio interruptor y sigue disponible desde las vistas de solo lectura,
   * donde `disabled` esta puesto.
   */
  puedeGenerar: boolean
  onGenerar: () => void
  /** Cuantos puntos de ambiguedad quedan sin contestar. */
  ambiguedadesPendientes: number
  onAmbiguedades: () => void
  /** Abre la revision de importacion. Leer no escribe; aplicar pide conexion. */
  onImportar: () => void
  /** XMI 2.5.1 (UML 2.5.1) del diagrama. Es una lectura: sigue disponible para un Observador. */
  onExportar: () => void
  tipoRelacion: RelationKind
  onCambiarTipoRelacion: (k: RelationKind) => void
  /** true = la herramienta Relacion esta armada y arrastrar conecta. */
  modoRelacion: boolean
  /** Con `estereotipo` crea una enumeracion en vez de una clase comun. */
  onCrearClase: (nombre: string, estereotipo?: string) => void
  onIniciarRelacion: () => void
  onCancelarRelacion: () => void
  /** Lee un diagrama de una imagen. Lo detectado se confirma antes de escribir. */
  onImagen: (archivo: File) => void
  /**
   * Que deshace y que rehace el proximo clic ("mover Libro"), o null si no hay
   * nada PROPIO: el undo es de esta pestana, no del diagrama (ADR-027).
   */
  proximoDeshacer: string | null
  proximoRehacer: string | null
  onDeshacer: () => void
  onRehacer: () => void
}

/**
 * Barra de herramientas. Solo lo que el catalogo cerrado de comandos
 * (ADR-014) ya sabe aplicar: crear clase, crear relacion. "Generar Spring
 * Boot" no es un comando --no escribe nada-- sino una lectura del modelo.
 *
 * "Importar" abre una revision: el archivo XMI se lee y se propone, y lo que
 * el usuario confirma entra como un BATCH por el bus. "Desde imagen" hace lo
 * mismo leyendo una foto o una captura (ADR-016). Ninguna de las dos agrega un
 * comando nuevo: las dos aterrizan comandos del catalogo cerrado.
 */
export function Toolbar({
  disabled, puedeGenerar, onGenerar, ambiguedadesPendientes, onAmbiguedades, onImportar, onExportar,
  tipoRelacion, onCambiarTipoRelacion, modoRelacion,
  onCrearClase, onIniciarRelacion, onCancelarRelacion, onImagen,
  proximoDeshacer, proximoRehacer, onDeshacer, onRehacer,
}: Props) {
  /** Que se esta creando: una clase comun o una enumeracion. */
  const [creandoClase, setCreandoClase] = useState<false | 'CLASE' | 'ENUM' | 'VALOR'>(false)
  const [nombreClase, setNombreClase] = useState('')

  const confirmarClase = () => {
    const n = nombreClase.trim()
    const estereotipo = creandoClase === 'ENUM' ? ESTEREOTIPO_ENUM
      : creandoClase === 'VALOR' ? ESTEREOTIPO_EMBEBIBLE : undefined
    if (n) onCrearClase(n, estereotipo)
    setNombreClase('')
    setCreandoClase(false)
  }

  return (
    <div className="toolbar">
      {creandoClase ? (
        <form className="tb-form" onSubmit={(e) => { e.preventDefault(); confirmarClase() }}>
          <input
            autoFocus
            className="tb-input"
            placeholder={creandoClase === 'ENUM' ? 'Nombre de la enumeracion'
              : creandoClase === 'VALOR' ? 'Nombre del valor (ej. Direccion)' : 'Nombre de la clase'}
            value={nombreClase}
            onChange={(e) => setNombreClase(e.target.value)}
            onKeyDown={(e) => { if (e.key === 'Escape') { setCreandoClase(false); setNombreClase('') } }}
          />
          <button type="submit" className="tb-btn tb-split-main" disabled={!nombreClase.trim()}>Crear</button>
          <button type="button" className="tb-btn tb-cancelar"
                  onClick={() => { setCreandoClase(false); setNombreClase('') }}>Cancelar</button>
        </form>
      ) : (
        <>
          <button className="tb-btn" disabled={disabled} onClick={() => setCreandoClase('CLASE')}
                  title="Crear una clase nueva">
            <span className="tb-icon">▭</span> Clase
          </button>
          <button className="tb-btn" disabled={disabled} onClick={() => setCreandoClase('ENUM')}
                  title="Crear una enumeracion: sus literales se usan como tipo de un atributo">
            <span className="tb-icon">«»</span> Enumeración
          </button>
          <button className="tb-btn" disabled={disabled} onClick={() => setCreandoClase('VALOR')}
                  title="Crear un valor embebido (@Embeddable): sin tabla propia, vive dentro de quien lo usa">
            <span className="tb-icon">▣</span> Valor
          </button>
        </>
      )}

      <div className="tb-separador" />

      <button className={modoRelacion ? 'tb-btn tb-activo' : 'tb-btn'} disabled={disabled}
              onClick={modoRelacion ? onCancelarRelacion : onIniciarRelacion}
              title="Con esto activo, arrastrá de una clase a otra para relacionarlas">
        <span className="tb-icon">↔</span> Relacion
      </button>

      <div className="tb-notaciones">
        {TIPOS_RELACION.map((t) => (
          <button key={t.id} title={t.label} disabled={disabled}
                  className={tipoRelacion === t.id ? 'tb-notacion activa' : 'tb-notacion'}
                  onClick={() => onCambiarTipoRelacion(t.id)}>
            <NotacionRelacion kind={t.id} />
          </button>
        ))}
      </div>

      {modoRelacion && (
        <span className="tb-hint">Arrastrá de una clase a otra (Esc cancela)</span>
      )}

      <div className="tb-separador" />

      {/* Un input de archivo disfrazado de boton: no hay que subir nada a
          ningun lado, la imagen se procesa y no se guarda. */}
      <label className={disabled ? 'tb-btn tb-deshabilitado' : 'tb-btn'}
             title="Leer un diagrama de una imagen: captura de proyector, screenshot o foto">
        <span className="tb-icon">▦</span> Desde imagen
        <input type="file" accept="image/png,image/jpeg" hidden disabled={disabled}
               onChange={(e) => {
                 const archivo = e.target.files?.[0]
                 // Se limpia el input para poder elegir DOS VECES la misma
                 // imagen: sin esto, el segundo intento no dispara nada.
                 e.target.value = ''
                 if (archivo) onImagen(archivo)
               }} />
      </label>

      <div className="tb-separador" />

      {/* Todo lo propio (ADR-027 a ADR-030): mover, editar, crear, borrar y
          lotes importados. El tooltip dice que se va a deshacer antes del clic. */}
      <button className="tb-btn tb-icono" disabled={disabled || !proximoDeshacer} onClick={onDeshacer}
              title={proximoDeshacer ? `Deshacer ${proximoDeshacer} (Ctrl+Z)`
                : 'Nada tuyo para deshacer (Ctrl+Z)'}>
        ↶
      </button>
      <button className="tb-btn tb-icono" disabled={disabled || !proximoRehacer} onClick={onRehacer}
              title={proximoRehacer ? `Rehacer ${proximoRehacer} (Ctrl+Y)` : 'Nada para rehacer (Ctrl+Y)'}>
        ↷
      </button>

      <div className="tb-separador" />

      <button className="tb-btn" disabled={disabled} onClick={onImportar}
              title="Leer un modelo de un archivo, revisarlo y agregarlo al diagrama">
        <span className="tb-icon">↥</span> Importar
      </button>

      {/* Como Generar: leer el modelo no es editarlo (ADR-022). */}
      <button className="tb-btn" disabled={!puedeGenerar} onClick={onExportar}
              title="Bajar el diagrama como XMI 2.5.1 (UML 2.5.1 estándar) para abrirlo en otra herramienta">
        <span className="tb-icon">↧</span> Exportar XMI
      </button>

      <button className="tb-btn" disabled={!puedeGenerar} onClick={onAmbiguedades}
              title="Los 4 puntos donde el modelo OO y el de datos no dicen lo mismo">
        <span className="tb-icon">⚖</span> Ambigüedades
        {ambiguedadesPendientes > 0 && (
          <span className="tb-badge">{ambiguedadesPendientes}</span>
        )}
      </button>

      <button className="tb-btn tb-primario" disabled={!puedeGenerar} onClick={onGenerar}
              title="Deriva el proyecto Spring Boot de este modelo y lo descarga">
        <span className="tb-icon">⚙</span> Generar Spring Boot
      </button>
    </div>
  )
}
