import { useState, type ReactNode } from 'react'

interface Props {
  titulo: string
  mensaje: ReactNode
  /** El texto del boton que confirma. */
  etiqueta: string
  /**
   * Si se da, hay que escribirlo tal cual para habilitar el boton: lo que no se
   * puede deshacer (eliminar un proyecto) no se confirma con un clic distraido.
   */
  escribir?: string
  peligro?: boolean
  /** Si lanza, el mensaje se muestra en el dialogo y este no se cierra. */
  onConfirmar: () => Promise<void> | void
  onCancelar: () => void
}

/**
 * Confirmacion de lo destructivo o irreversible: quitar a alguien, transferir la
 * propiedad, abandonar, eliminar. Reusa el aspecto de los demas dialogos.
 */
export function Confirmar({ titulo, mensaje, etiqueta, escribir, peligro, onConfirmar, onCancelar }: Props) {
  const [texto, setTexto] = useState('')
  const [enviando, setEnviando] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const habilitado = !enviando && (escribir === undefined || texto === escribir)

  const confirmar = async () => {
    setEnviando(true)
    setError(null)
    try {
      await onConfirmar()
    } catch (e) {
      setError(e instanceof Error ? e.message : 'No se pudo completar la operación.')
      setEnviando(false)
    }
  }

  return (
    <div className="modal-fondo" onClick={onCancelar}>
      <form className="modal modal-confirmar" role="dialog" aria-label={titulo}
            onClick={(e) => e.stopPropagation()}
            onSubmit={(e) => { e.preventDefault(); if (habilitado) void confirmar() }}>
        <header>
          <strong>{titulo}</strong>
          <button type="button" className="modal-cerrar" onClick={onCancelar} aria-label="Cerrar">×</button>
        </header>
        <div className="modal-resumen">{mensaje}</div>
        {escribir !== undefined && (
          <label className="confirmar-escribir">
            <span>Para confirmar, escribí <code>{escribir}</code></span>
            <input autoFocus value={texto} onChange={(e) => setTexto(e.target.value)}
                   autoComplete="off" spellCheck={false} />
          </label>
        )}
        {error && <div className="modal-error">{error}</div>}
        <footer>
          <button type="submit" disabled={!habilitado}
                  className={peligro ? 'tb-btn tb-peligro' : 'tb-btn tb-primario confirmar-si'}>
            {enviando ? 'Un momento...' : etiqueta}
          </button>
          <button type="button" className="tb-btn" onClick={onCancelar} disabled={enviando}>Cancelar</button>
        </footer>
      </form>
    </div>
  )
}
