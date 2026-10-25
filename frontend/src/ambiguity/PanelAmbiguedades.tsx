export type TipoAmbiguedad = 'COMPOSITION' | 'EMBEDDABLE' | 'MULTIVALUED' | 'ASSOC_CLASS'
export type Lectura = 'OO' | 'DATA'

/** Espejo de DiagramSnapshotService.AmbiguedadResuelta. */
export interface Ambiguedad {
  elementId: string
  tipo: TipoAmbiguedad
  titulo: string
  lecturaOO: string
  lecturaDatos: string
  porDefecto: Lectura
  elegida: Lectura
  /** false = nadie contesto todavia y manda `porDefecto`. */
  decidida: boolean
}

const ETIQUETA: Record<TipoAmbiguedad, string> = {
  COMPOSITION: 'A-1 · Composición 1:N',
  EMBEDDABLE: 'A-2 · Composición 1..1',
  MULTIVALUED: 'A-3 · Atributo multivaluado',
  ASSOC_CLASS: 'A-4 · Relación N:M',
}

interface Props {
  ambiguedades: Ambiguedad[] | null
  error: string | null
  soloLectura: boolean
  onElegir: (a: Ambiguedad, lectura: Lectura) => void
  onCerrar: () => void
}

/**
 * Los 4 puntos donde el modelo OO y el modelo de datos NO dicen lo mismo.
 *
 * Las dos lecturas de cada punto son correctas; lo que no existe es una
 * respuesta universal. Por eso esto pregunta en vez de decidir: la alternativa
 * es que el generador elija en silencio y el usuario se entere leyendo el
 * codigo que se acaba de bajar.
 */
export function PanelAmbiguedades({ ambiguedades, error, soloLectura, onElegir, onCerrar }: Props) {
  const pendientes = ambiguedades?.filter((a) => !a.decidida).length ?? 0

  return (
    <div className="modal-fondo" onClick={onCerrar}>
      <div className="modal" onClick={(e) => e.stopPropagation()}>
        <header>
          <strong>Puntos de ambigüedad OO ↔ Datos</strong>
          <button className="modal-cerrar" onClick={onCerrar} title="Cerrar">×</button>
        </header>

        {error && <p className="modal-error">{error}</p>}
        {!error && !ambiguedades && <p className="tenue">Revisando el modelo…</p>}

        {ambiguedades && ambiguedades.length === 0 && (
          <p className="tenue">
            Este modelo no tiene construcciones ambiguas. Aparecen con las composiciones,
            los atributos multivaluados y las relaciones N:M.
          </p>
        )}

        {ambiguedades && ambiguedades.length > 0 && (
          <>
            <p className="modal-resumen">
              Las dos lecturas son correctas. Elegir es una decisión de diseño del equipo,
              y queda guardada: el generador no vuelve a preguntar.
              {pendientes > 0 && <> <strong>{pendientes} sin decidir</strong>.</>}
            </p>

            {soloLectura && (
              <p className="props-bloqueada">
                Estás en una vista de solo lectura o no tenés permiso de escritura:
                podés ver las preguntas, no contestarlas.
              </p>
            )}

            <ul className="ambiguedades">
              {ambiguedades.map((a) => (
                <li key={a.tipo + a.elementId}>
                  <div className="amb-cabecera">
                    <span className="amb-tipo">{ETIQUETA[a.tipo]}</span>
                    <strong>{a.titulo}</strong>
                    {!a.decidida && <span className="amb-pendiente">sin decidir</span>}
                  </div>
                  <div className="amb-opciones">
                    {(['OO', 'DATA'] as Lectura[]).map((lectura) => {
                      const activa = a.elegida === lectura
                      const clases = ['amb-opcion']
                      if (activa) clases.push(a.decidida ? 'elegida' : 'por-defecto')
                      return (
                        <button key={lectura} className={clases.join(' ')} disabled={soloLectura}
                                onClick={() => onElegir(a, lectura)}>
                          <span className="amb-lectura">
                            {lectura === 'OO' ? 'Lectura orientada a objetos' : 'Lectura de datos'}
                            {activa && !a.decidida && ' · por defecto'}
                          </span>
                          <span className="amb-detalle">
                            {lectura === 'OO' ? a.lecturaOO : a.lecturaDatos}
                          </span>
                        </button>
                      )
                    })}
                  </div>
                </li>
              ))}
            </ul>
          </>
        )}

        <footer>
          <button className="tb-btn" onClick={onCerrar}>Cerrar</button>
        </footer>
      </div>
    </div>
  )
}
