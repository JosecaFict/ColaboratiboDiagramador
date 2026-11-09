import { esEmbebible, esEnumeracion, type UmlClass } from '../contracts/command'

interface Props {
  clases: UmlClass[]
  relaciones: number
  /** Nombre de lo seleccionado, o null. */
  seleccionado: string | null
  vista: string
  /** Otros que tienen algo tomado ahora (sin uno mismo). */
  editandoAhora: string[]
  /** "Ana creó la clase «Pedido» · hace 2 min", o null si no hay cambios. */
  ultimoCambio: string | null
}

const plural = (n: number, una: string, varias: string) => `${n} ${n === 1 ? una : varias}`

/**
 * La linea de abajo del editor: cuanto hay, que esta seleccionado, quien esta
 * editando y cual fue el ultimo cambio. Solo lee: no tiene acciones.
 */
export function BarraDeEstado({ clases, relaciones, seleccionado, vista, editandoAhora, ultimoCambio }: Props) {
  const enumeraciones = clases.filter(esEnumeracion).length
  const embebibles = clases.filter(esEmbebible).length
  const comunes = clases.length - enumeraciones - embebibles

  const conteo = [
    plural(comunes, 'clase', 'clases'),
    plural(relaciones, 'relación', 'relaciones'),
    enumeraciones ? plural(enumeraciones, 'enumeración', 'enumeraciones') : null,
    embebibles ? plural(embebibles, 'valor embebido', 'valores embebidos') : null,
  ].filter(Boolean).join(' · ')

  return (
    <footer className="barra-estado" aria-label="Estado del diagrama">
      <span>{conteo}</span>
      <span className="be-sep" aria-hidden />
      <span>{seleccionado ? <>Seleccionado: <strong>{seleccionado}</strong></> : 'Nada seleccionado'}</span>
      <span className="be-sep" aria-hidden />
      <span>Vista: {vista}</span>
      {editandoAhora.length > 0 && (
        <>
          <span className="be-sep" aria-hidden />
          <span className="be-editando" title="Tienen una clase o relación tomada">
            Editando ahora: {editandoAhora.join(', ')}
          </span>
        </>
      )}
      {ultimoCambio && <span className="be-ultimo" title={ultimoCambio}>Último cambio: {ultimoCambio}</span>}
    </footer>
  )
}
