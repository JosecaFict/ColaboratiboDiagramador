import { useEffect, useId, useRef, useState, type KeyboardEvent } from 'react'
import { siguienteHabilitado, type GrupoMenu } from './estado'
export function MenuEditor({ grupos, alElegir }: { grupos: GrupoMenu[]; alElegir: (id: string) => void }) {
  const [abierto, setAbierto] = useState<number | null>(null)
  const [foco, setFoco] = useState(0)
  const nav = useRef<HTMLElement>(null)
  const inicio = useRef(false)
  const id = useId()
  const botones = () => Array.from(nav.current?.querySelectorAll<HTMLButtonElement>('[data-cabecera]') ?? [])
  const opciones = () => Array.from(nav.current?.querySelectorAll<HTMLButtonElement>('[role="menu"] [role="menuitem"]') ?? [])
  const cerrar = () => { setAbierto(null); botones()[foco]?.focus() }
  useEffect(() => {
    if (abierto === null) return
    const items = opciones()
    const n = siguienteHabilitado(items.map((b) => !b.disabled), inicio.current ? 0 : -1, inicio.current ? -1 : 1)
    items[n]?.focus()
    const fuera = (e: PointerEvent) => { if (!nav.current?.contains(e.target as Node)) setAbierto(null) }
    document.addEventListener('pointerdown', fuera)
    return () => document.removeEventListener('pointerdown', fuera)
  }, [abierto])
  const moverGrupo = (delta: number) => {
    const n = (foco + delta + grupos.length) % grupos.length
    setFoco(n); botones()[n]?.focus(); if (abierto !== null) setAbierto(n)
  }
  const tecla = (e: KeyboardEvent, cabecera: boolean) => {
    if (e.key === 'Escape') { e.preventDefault(); cerrar() }
    else if (e.key === 'ArrowRight' || e.key === 'ArrowLeft') { e.preventDefault(); moverGrupo(e.key === 'ArrowRight' ? 1 : -1) }
    else if (e.key === 'ArrowDown' || e.key === 'ArrowUp' || e.key === 'Home' || e.key === 'End') {
      e.preventDefault()
      if (cabecera) { inicio.current = e.key === 'ArrowUp'; setAbierto(foco) }
      else {
        const items = opciones(), actual = items.indexOf(document.activeElement as HTMLButtonElement)
        const delta = e.key === 'ArrowUp' || e.key === 'End' ? -1 : 1
        const desde = e.key === 'Home' ? -1 : e.key === 'End' ? 0 : actual
        items[siguienteHabilitado(items.map((b) => !b.disabled), desde, delta)]?.focus()
      }
    } else if (e.key === 'Tab') setAbierto(null)
  }
  return <nav className="menu-editor" aria-label="Menús del editor" role="menubar" ref={nav}>
    {grupos.map((grupo, i) => <div className="menu-grupo" key={grupo.titulo}>
      <button data-cabecera role="menuitem" aria-haspopup="menu" aria-expanded={abierto === i} aria-controls={`${id}-${i}`}
        tabIndex={foco === i ? 0 : -1} onFocus={() => setFoco(i)} onKeyDown={(e) => tecla(e, true)}
        onClick={() => { inicio.current = false; setAbierto(abierto === i ? null : i) }}>{grupo.titulo}</button>
      {abierto === i && <div className="menu-desplegable" role="menu" id={`${id}-${i}`} aria-label={grupo.titulo}>
        {grupo.acciones.map((a) => <button key={a.id} role="menuitem" tabIndex={-1} disabled={!a.habilitada}
          onKeyDown={(e) => tecla(e, false)} onClick={() => { cerrar(); alElegir(a.id) }}>
          <span>{a.texto}</span>{a.atajo && <kbd>{a.atajo}</kbd>}
        </button>)}
      </div>}
    </div>)}
  </nav>
}
