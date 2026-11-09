export type NombreIcono = 'sol' | 'luna' | 'deshacer' | 'rehacer' | 'paleta' | 'decision'
export function Icono({ nombre }: { nombre: NombreIcono }) {
  const caminos: Record<NombreIcono, string> = {
    sol: 'M12 3v2m0 14v2M3 12h2m14 0h2M5.6 5.6 7 7m10 10 1.4 1.4M5.6 18.4 7 17M17 7l1.4-1.4 M16 12a4 4 0 1 1-8 0 4 4 0 0 1 8 0',
    luna: 'M20 13a8 8 0 0 1-9-9 8 8 0 1 0 9 9Z',
    deshacer: 'M9 6 4 11l5 5M4 11h10a6 6 0 0 1 6 6',
    rehacer: 'm15 6 5 5-5 5m5-5H10a6 6 0 0 0-6 6',
    paleta: 'M4 4h16v16H4ZM9 4v16M4 9h5',
    decision: 'M12 3v17m-5 1h10M5 7h14M5 7l-3 7h6L5 7Zm14 0-3 7h6l-3-7Z',
  }
  return <svg className="icono" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d={caminos[nombre]} /></svg>
}
