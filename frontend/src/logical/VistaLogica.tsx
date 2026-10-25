/** Espejo de ModeloLogico.java. La derivación vive en el backend. */
export interface ColumnaLogica {
  nombre: string
  tipo: string
  pk: boolean
  nullable: boolean
  unique: boolean
  referencia?: string | null
  /** Literales si la columna es de una enumeracion: en el DDL, un CHECK. */
  enumeracion?: { nombre: string; literales: string[] } | null
  /** Limites de una columna numerica: en el DDL, un CHECK. */
  rango?: { minimo: number | null; maximo: number | null } | null
}

export interface TablaLogica {
  nombre: string
  origen: string
  columnas: ColumnaLogica[]
  /** Valores embebidos (Regla 5): que columnas son de que objeto de valor. */
  incrustados?: { campo: string; clase: string; columnas: { atributo: string; columna: string }[] }[]
  /** No nulo si la tabla guarda una jerarquia entera (SINGLE_TABLE). */
  tablaUnica?: {
    discriminador: string
    miembros: { clase: string; madre: string | null; valor: string | null; columnas: string[] }[]
  } | null
}

export interface ModeloLogico {
  tablas: TablaLogica[]
  advertencias: string[]
}

/**
 * Proyección de solo lectura (ADR-003): se deriva del mismo metamodelo que
 * dibuja la vista de Clases, aplicando las 12 reglas de mapeo. No se edita
 * acá; se edita el modelo conceptual y esto lo refleja.
 */
export function VistaLogica({ modelo }: { modelo: ModeloLogico | null }) {
  if (!modelo) {
    return <div className="pendiente"><p className="tenue">Derivando el esquema…</p></div>
  }

  if (modelo.tablas.length === 0) {
    return (
      <div className="pendiente">
        <p><strong>Todavía no hay nada que derivar</strong></p>
        <p className="tenue">Creá alguna clase en la vista de Clases y volvé.</p>
      </div>
    )
  }

  return (
    <div className="logica">
      {modelo.advertencias.length > 0 && (
        <section className="advertencias">
          <h4>Decisiones tomadas y construcciones rechazadas</h4>
          <p className="tenue">
            Regla 10: lo que no se puede mapear se lista, nunca se ignora en silencio.
          </p>
          <ul>
            {modelo.advertencias.map((a, i) => <li key={i}>{a}</li>)}
          </ul>
        </section>
      )}

      <div className="tablas">
        {modelo.tablas.map((t) => (
          <article className="tabla" key={t.nombre}>
            <header>
              <strong>{t.nombre}</strong>
              <span className="tenue">{t.origen}</span>
            </header>
            {(t.incrustados ?? []).map((i) => (
              <p className="tabla-unica tenue" key={i.campo}>
                <code>{i.campo}</code> es un {i.clase} embebido:{' '}
                {i.columnas.map((c) => c.columna).join(', ')}
              </p>
            ))}
            {t.tablaUnica && (
              <p className="tabla-unica tenue">
                Guarda {t.tablaUnica.miembros.map((m) => m.clase).join(', ')} ·
                discriminador <code>{t.tablaUnica.discriminador}</code>
              </p>
            )}
            <table>
              <tbody>
                {t.columnas.map((c) => (
                  <tr key={c.nombre}>
                    <td className="llave">
                      {c.pk && <span className="tag pk">PK</span>}
                      {c.referencia && <span className="tag fk">FK</span>}
                    </td>
                    <td className="col-nombre">{c.nombre}</td>
                    <td className="col-tipo">{c.tipo}</td>
                    <td className="col-marcas">
                      {!c.nullable && <span className="marca">NOT NULL</span>}
                      {c.unique && <span className="marca">UNIQUE</span>}
                      {c.referencia && <span className="marca ref">→ {c.referencia}</span>}
                      {c.rango && (
                        <span className="marca">
                          CHECK ({[c.rango.minimo != null ? '≥ ' + c.rango.minimo : null,
                                   c.rango.maximo != null ? '≤ ' + c.rango.maximo : null]
                            .filter(Boolean).join(', ')})
                        </span>
                      )}
                      {c.enumeracion && (
                        <span className="marca" title={'Enumeracion ' + c.enumeracion.nombre}>
                          CHECK ({c.enumeracion.literales.join(', ')})
                        </span>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </article>
        ))}
      </div>
    </div>
  )
}
