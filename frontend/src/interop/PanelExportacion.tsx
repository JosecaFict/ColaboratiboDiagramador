import type { PerfilXmi, XmiExportado } from './exportacionXmi'

interface Props {
  exportado: XmiExportado | null
  error: string | null
  perfil: PerfilXmi
  onPerfil: (perfil: PerfilXmi) => void
  onGuardar: () => void
  onCerrar: () => void
}

/**
 * Lo que el XMI se lleva y lo que no, ANTES de guardarlo (ADR-022).
 *
 * Mismo criterio que "Generar Spring Boot": un archivo ya guardado no puede
 * advertir nada a tiempo (Regla 10). Dos perfiles (ADR-031): para Enterprise
 * Architect, con el diagrama dibujado, o el estandar de la OMG. Y se dice sin
 * rodeos el riesgo que sigue abierto: ninguno se abrio todavia en el EA del
 * docente.
 */
export function PanelExportacion({ exportado, error, perfil, onPerfil, onGuardar, onCerrar }: Props) {
  return (
    <div className="modal-fondo" onClick={onCerrar}>
      <div className="modal" onClick={(e) => e.stopPropagation()}>
        <header>
          <strong>Exportar XMI</strong>
          <button className="modal-cerrar" onClick={onCerrar} title="Cerrar">×</button>
        </header>

        <div className="segmentado" role="radiogroup" aria-label="Formato">
          <button role="radio" aria-checked={perfil === 'ea'} className={perfil === 'ea' ? 'activo' : ''}
                  onClick={() => onPerfil('ea')}>Enterprise Architect</button>
          <button role="radio" aria-checked={perfil === 'estandar'} className={perfil === 'estandar' ? 'activo' : ''}
                  onClick={() => onPerfil('estandar')}>UML 2.5.1 estándar</button>
        </div>

        {error && <p className="modal-error">{error}</p>}

        {!error && !exportado && <p className="tenue">Escribiendo el XMI…</p>}

        {exportado && exportado.perfil === perfil && (
          <>
            {perfil === 'ea' ? (
              <>
                <p className="modal-resumen">
                  XMI 2.1 · Enterprise Architect · <code>{exportado.nombreArchivo}</code>
                </p>
                <p className="tenue">
                  Trae el diagrama de clases con la misma disposición que acá. En EA: clic derecho sobre
                  un paquete › <strong>Import/Export › Import Package from XMI…</strong> (o <strong>Ctrl+Alt+I</strong>).
                  Todavía no se probó en el EA del docente.
                </p>
              </>
            ) : (
              <>
                <p className="modal-resumen">
                  XMI 2.5.1 · UML 2.5.1 · <code>{exportado.nombreArchivo}</code>
                </p>
                <p className="tenue">
                  Sin extensiones propietarias y sin disposición: la herramienta que lo abra crea las
                  clases, pero no el diagrama. Para EA, usá el otro formato.
                </p>
              </>
            )}

            {exportado.perdidas.length > 0 && (
              <section className="advertencias">
                <h4>Lo que el archivo no puede llevarse</h4>
                <ul>
                  {exportado.perdidas.map((p, i) => <li key={i}>{p}</li>)}
                </ul>
              </section>
            )}

            <footer>
              <button className="tb-btn tb-primario" onClick={onGuardar}>Guardar {exportado.nombreArchivo}</button>
              <button className="tb-btn" onClick={onCerrar}>Cerrar</button>
            </footer>
          </>
        )}
      </div>
    </div>
  )
}
