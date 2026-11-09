import type { ModeloDocumental } from './tipos'

interface Props {
  modelo: ModeloDocumental | null
  error: string | null
}

/**
 * Proyeccion de solo lectura del modelo a colecciones de MongoDB (ADR-035):
 * la hermana de la Vista Logica. Se deriva en el servidor; aca solo se dibuja.
 * Reusa las clases de la Vista Logica para verse igual.
 */
export function VistaDocumental({ modelo, error }: Props) {
  if (error) return <div className="pendiente"><p className="modal-error">{error}</p></div>
  if (!modelo) return <div className="pendiente"><p className="tenue">Derivando las colecciones…</p></div>
  if (modelo.colecciones.length === 0) {
    return (
      <div className="pendiente">
        <p><strong>Todavía no hay nada que derivar</strong></p>
        <p className="tenue">Creá alguna clase en la vista de Clases y volvé.</p>
      </div>
    )
  }

  return (
    <div className="logica documental">
      {modelo.advertencias.length > 0 && (
        <section className="advertencias">
          <h4>Decisiones tomadas y construcciones que no se pudieron mapear</h4>
          <ul>{modelo.advertencias.map((a, i) => <li key={i}>{a}</li>)}</ul>
        </section>
      )}

      <div className="tablas">
        {modelo.colecciones.map((c) => (
          <article className="tabla" key={c.nombre}>
            <header>
              <strong>{c.nombre}</strong>
              <span className="tenue">{c.entidad}</span>
            </header>
            {c.discriminador && (
              <p className="tabla-unica tenue">Guarda una jerarquía · discriminador <code>{c.discriminador}</code></p>
            )}
            <table>
              <tbody>
                <tr>
                  <td className="llave"><span className="tag pk">ID</span></td>
                  <td className="col-nombre">_id</td>
                  <td className="col-tipo">uuid</td>
                  <td className="col-marcas" />
                </tr>
                {c.campos.map((f) => (
                  <tr key={'f' + f.nombre}>
                    <td className="llave" />
                    <td className="col-nombre">{f.nombre}</td>
                    <td className="col-tipo">{f.tipo}</td>
                    <td className="col-marcas">{f.requerido && <span className="marca">REQUERIDO</span>}</td>
                  </tr>
                ))}
                {c.incrustados.map((i) => (
                  <tr key={'i' + i.campo}>
                    <td className="llave"><span className="tag emb">EMB</span></td>
                    <td className="col-nombre">{i.campo}</td>
                    <td className="col-tipo">{i.multiple ? `[${i.entidad}]` : i.entidad}</td>
                    <td className="col-marcas"><span className="marca">incrustado{i.multiple ? ' · varios' : ''}</span></td>
                  </tr>
                ))}
                {c.referencias.map((r) => (
                  <tr key={'r' + r.campo}>
                    <td className="llave"><span className="tag fk">REF</span></td>
                    <td className="col-nombre">{r.campo}</td>
                    <td className="col-tipo">{r.multiple ? '[uuid]' : 'uuid'}</td>
                    <td className="col-marcas"><span className="marca ref">→ {r.coleccion}</span></td>
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
