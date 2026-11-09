import { Icono } from '../apariencia/Icono'
interface Props {
  disabled: boolean
  puedeGenerar: boolean
  ambiguedadesPendientes: number
  onAmbiguedades: () => void
  panelIzquierdo: boolean | null
  onPanelIzquierdo: () => void
  proximoDeshacer: string | null
  proximoRehacer: string | null
  onDeshacer: () => void
  onRehacer: () => void
}
/** Acciones frecuentes. Importación, exportación y generación viven en los menús. */
export function Toolbar({ disabled, puedeGenerar, ambiguedadesPendientes, onAmbiguedades,
  panelIzquierdo, onPanelIzquierdo, proximoDeshacer, proximoRehacer, onDeshacer, onRehacer }: Props) {
  return <div className="toolbar" role="group" aria-label="Herramientas del diagrama">
    {panelIzquierdo !== null && <>
      <button className={panelIzquierdo ? 'tb-btn tb-icono tb-activo' : 'tb-btn tb-icono'}
        onClick={onPanelIzquierdo} aria-pressed={panelIzquierdo} aria-label="Proyecto y paleta"
        title={panelIzquierdo ? 'Ocultar el proyecto y la paleta' : 'Mostrar el proyecto y la paleta'}>
        <Icono nombre="paleta" />
      </button><div className="tb-separador" />
    </>}
    <button className="tb-btn tb-icono" disabled={disabled || !proximoDeshacer} onClick={onDeshacer}
      aria-label="Deshacer" title={proximoDeshacer ? `Deshacer ${proximoDeshacer} (Ctrl+Z)` : 'Nada tuyo para deshacer (Ctrl+Z)'}>
      <Icono nombre="deshacer" />
    </button>
    <button className="tb-btn tb-icono" disabled={disabled || !proximoRehacer} onClick={onRehacer}
      aria-label="Rehacer" title={proximoRehacer ? `Rehacer ${proximoRehacer} (Ctrl+Y)` : 'Nada para rehacer (Ctrl+Y)'}>
      <Icono nombre="rehacer" />
    </button><div className="tb-separador" />
    <button className="tb-btn" disabled={!puedeGenerar} onClick={onAmbiguedades}
      title="Los 4 puntos donde el modelo OO y el de datos no dicen lo mismo">
      <Icono nombre="decision" /> Ambigüedades
      {ambiguedadesPendientes > 0 && <span className="tb-badge">{ambiguedadesPendientes}</span>}
    </button>
    <span className="toolbar-ayuda">Importá, exportá y generá desde los menús</span>
  </div>
}
