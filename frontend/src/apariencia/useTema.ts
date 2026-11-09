import { useEffect, useLayoutEffect, useState } from 'react'
import { guardarTema, leerTema, resolverTema, type PreferenciaTema } from './estado'
const acceso = () => window.localStorage
export function iniciarTema() {
  const tema = resolverTema(leerTema(acceso), window.matchMedia('(prefers-color-scheme: dark)').matches)
  document.documentElement.dataset.tema = tema
}
export function useTema() {
  const [preferencia, setPreferencia] = useState<PreferenciaTema>(() => leerTema(acceso))
  const [oscuro, setOscuro] = useState(() => window.matchMedia('(prefers-color-scheme: dark)').matches)
  useEffect(() => {
    const media = window.matchMedia('(prefers-color-scheme: dark)')
    const cambiar = () => setOscuro(media.matches)
    media.addEventListener('change', cambiar)
    return () => media.removeEventListener('change', cambiar)
  }, [])
  const tema = resolverTema(preferencia, oscuro)
  useLayoutEffect(() => { document.documentElement.dataset.tema = tema }, [tema])
  const elegir = (valor: PreferenciaTema) => { setPreferencia(valor); guardarTema(acceso, valor) }
  return { tema, alternar: () => elegir(tema === 'oscuro' ? 'claro' : 'oscuro'), sistema: () => elegir('sistema') }
}
