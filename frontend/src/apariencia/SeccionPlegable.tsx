import { useId, useState, type ReactNode } from 'react'
import { guardarSeccion, leerSeccion, type Seccion } from './estado'
export function SeccionPlegable({ id, titulo, children }: { id: Seccion; titulo: string; children: ReactNode }) {
  const [abierta, setAbierta] = useState(() => leerSeccion(() => window.localStorage, id))
  const cuerpo = useId()
  return <details className="seccion-plegable" open={abierta} onToggle={(e) => {
    const valor = e.currentTarget.open
    if (valor !== abierta) { setAbierta(valor); guardarSeccion(() => window.localStorage, id, valor) }
  }}>
    <summary aria-controls={cuerpo}>{titulo}<span className="seccion-chevron" aria-hidden="true"><svg width="14" height="14" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5"><path d="m4 6 4 4 4-4" /></svg></span></summary>
    <div className="seccion-contenido" id={cuerpo}>{children}</div>
  </details>
}
