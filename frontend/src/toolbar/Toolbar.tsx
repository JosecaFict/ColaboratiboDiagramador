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
  /** Lee un diagrama de una imagen. Lo detectado se confirma antes de escribir. */
  onImagen: (archivo: File) => void
  /** El panel izquierdo (arbol + paleta) esta abierto; null = esta vista no lo tiene. */
  panelIzquierdo: boolean | null
  onPanelIzquierdo: () => void
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
 * Barra de herramientas. Crear clases y relaciones se mudo a la PALETA del
 * panel izquierdo (como en StarUML); aca queda lo que actua sobre el diagrama
 * entero. "Generar Spring Boot" no es un comando --no escribe nada-- sino una
 * lectura del modelo.
 *
 * "Importar" abre una revision: el archivo XMI se lee y se propone, y lo que
 * el usuario confirma entra como un BATCH por el bus. "Desde imagen" hace lo
 * mismo leyendo una foto o una captura (ADR-016). Ninguna de las dos agrega un
 * comando nuevo: las dos aterrizan comandos del catalogo cerrado.
 */
export function Toolbar({
  disabled, puedeGenerar, onGenerar, ambiguedadesPendientes, onAmbiguedades, onImportar, onExportar,
  onImagen, panelIzquierdo, onPanelIzquierdo,
  proximoDeshacer, proximoRehacer, onDeshacer, onRehacer,
}: Props) {
  return (
    <div className="toolbar">
      {panelIzquierdo !== null && (
        <>
          <button className={panelIzquierdo ? 'tb-btn tb-icono tb-activo' : 'tb-btn tb-icono'}
                  onClick={onPanelIzquierdo} aria-pressed={panelIzquierdo}
                  title={panelIzquierdo ? 'Ocultar el proyecto y la paleta' : 'Mostrar el proyecto y la paleta'}>
            ◧
          </button>
          <div className="tb-separador" />
        </>
      )}

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
