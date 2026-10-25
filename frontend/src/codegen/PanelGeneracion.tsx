import type { ConfirmacionEsquema } from '../physical/esquema'

/** Espejo de DiagramRestController.ResumenGeneracion. */
export interface ResumenGeneracion {
  nombre: string
  /** Nombre del zip que va a bajar, ya normalizado por la Regla 8. */
  archivo: string
  entidades: number
  archivos: string[]
  advertencias: string[]
  /** La huella del DDL que va en el zip. */
  hashEsquema: string
  /** Lo que el equipo confirmo (ADR-032); `vigente` = es exactamente este DDL. */
  confirmacion?: ConfirmacionEsquema | null
  correcciones: { relationId: string; type: 'UPDATE_RELATION'; name: string; descripcion: string }[]
}

interface Props {
  resumen: ResumenGeneracion | null
  error: string | null
  descargando: boolean
  onDescargar: () => void
  /** Revisar y confirmar la base se hace en la Vista Fisica. */
  onIrAFisica: () => void
  onCerrar: () => void
  onAplicarCorrecciones: () => void
}

/**
 * Lo que el generador va a emitir, ANTES de emitirlo.
 *
 * La Regla 10 pide que lo no soportado se liste en lugar de callarse, y un
 * zip no tiene forma de avisar: para cuando el usuario abre el README ya se
 * lo llevo. Por eso la revision va antes de la descarga, igual que el
 * "revisar antes de importar" del XML.
 *
 * Por la misma razon dice si la base que va en el zip es la CONFIRMADA
 * (ADR-032). No lo impide: generar sigue siendo una lectura, y un prototipo
 * sin confirmar se puede bajar igual. Pero lo dice antes, y la accion
 * principal pasa a ser ir a revisarla.
 */
export function PanelGeneracion({ resumen, error, descargando, onDescargar, onIrAFisica, onCerrar, onAplicarCorrecciones }: Props) {
  const confirmada = resumen?.confirmacion?.vigente === true
  return (
    <div className="modal-fondo" onClick={onCerrar}>
      <div className="modal" onClick={(e) => e.stopPropagation()}>
        <header>
          <strong>Generar backend Spring Boot</strong>
          <button className="modal-cerrar" onClick={onCerrar} title="Cerrar">×</button>
        </header>

        {error && <p className="modal-error">{error}</p>}

        {!error && !resumen && <p className="tenue">Derivando el proyecto…</p>}

        {resumen && (
          <>
            <p className="modal-resumen">
              <strong>{resumen.entidades}</strong> {resumen.entidades === 1 ? 'entidad' : 'entidades'}
              {' · '}
              <strong>{resumen.archivos.length}</strong> archivos
              {' · '}
              <code>{resumen.archivo}</code>
            </p>

            {resumen.entidades === 0 && (
              <p className="tenue">
                El proyecto arranca igual, pero sin clases en el diagrama no hay
                entidades que generar.
              </p>
            )}

            {resumen.entidades > 0 && (confirmada ? (
              <p className="gen-base ok">
                <strong>Base confirmada</strong> por {resumen.confirmacion!.por ?? 'alguien del equipo'}{' '}
                (operación #{resumen.confirmacion!.seq}). El zip lleva exactamente ese DDL, el mismo que
                PostgreSQL aceptó al confirmarla.
              </p>
            ) : (
              <p className="gen-base aviso">
                <strong>
                  {resumen.confirmacion ? 'La base cambió desde que se confirmó.' : 'La base todavía no se confirmó.'}
                </strong>{' '}
                Revisala en la Vista Física, verificala en PostgreSQL y confirmala: así el backend sale
                con la base que el equipo aprobó.
              </p>
            ))}

            {resumen.advertencias.length > 0 && (
              <section className="advertencias">
                <h4>Lo que este modelo deja afuera</h4>
                <ul>
                  {resumen.advertencias.map((a, i) => <li key={i}>{a}</li>)}
                </ul>
              </section>
            )}

            {resumen.correcciones?.length > 0 && (
              <section className="advertencias">
                <h4>Correcciones seguras antes de generar</h4>
                <p className="tenue">No se aplicará ninguna sin tu confirmación.</p>
                <ul>{resumen.correcciones.map((c) => <li key={c.relationId}>{c.descripcion}</li>)}</ul>
                <button className="tb-btn tb-primario" onClick={onAplicarCorrecciones}>
                  Aplicar {resumen.correcciones.length} {resumen.correcciones.length === 1 ? 'corrección' : 'correcciones'}
                </button>
              </section>
            )}

            <details className="modal-archivos">
              <summary>Ver los {resumen.archivos.length} archivos</summary>
              <ul>
                {resumen.archivos.map((r) => <li key={r}><code>{r}</code></li>)}
              </ul>
            </details>

            <footer>
              {confirmada || resumen.entidades === 0 ? (
                <button className="tb-btn tb-primario" disabled={descargando} onClick={onDescargar}>
                  {descargando ? 'Empaquetando…' : 'Descargar ZIP'}
                </button>
              ) : (
                <>
                  <button className="tb-btn tb-primario" onClick={onIrAFisica}>
                    Revisar la base en la Vista Física
                  </button>
                  <button className="tb-btn" disabled={descargando} onClick={onDescargar}>
                    {descargando ? 'Empaquetando…' : 'Descargar sin confirmar'}
                  </button>
                </>
              )}
              <button className="tb-btn" onClick={onCerrar}>Cerrar</button>
            </footer>
          </>
        )}
      </div>
    </div>
  )
}
