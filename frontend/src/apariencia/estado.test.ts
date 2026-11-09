import { describe, expect, it } from 'vitest'
import { guardarTema, leerTema, resolverTema, guardarSeccion, leerSeccion, menusPara, siguienteHabilitado, siguienteTema, preferenciaValida } from './estado'
describe('tema y preferencias', () => {
  it('sigue al sistema salvo elección explícita', () => {
    expect(resolverTema('sistema', true)).toBe('oscuro'); expect(resolverTema('sistema', false)).toBe('claro')
    expect(resolverTema('claro', true)).toBe('claro'); expect(resolverTema('oscuro', false)).toBe('oscuro')
  })
  it('tolera tanto el acceso prohibido a localStorage como valores corruptos', () => {
    const falla = () => { throw new Error('prohibido') }
    expect(leerTema(falla)).toBe('sistema'); expect(() => guardarTema(falla, 'oscuro')).not.toThrow()
    expect(leerSeccion(falla, 'historial')).toBe(true); expect(() => guardarSeccion(falla, 'panel', false)).not.toThrow()
    expect(leerTema(() => ({ getItem: () => 'otro', setItem: () => {} }))).toBe('sistema')
  })
  it('recuerda tema y cada sección por separado', () => {
    const datos = new Map<string, string>(); const acceso = () => ({getItem: (k: string) => datos.get(k) ?? null, setItem: (k: string,v: string) => { datos.set(k,v) }})
    guardarTema(acceso, 'oscuro'); expect(leerTema(acceso)).toBe('oscuro')
    guardarTema(acceso, 'sistema'); expect(leerTema(acceso)).toBe('sistema')
    guardarSeccion(acceso, 'propiedades', false); expect(leerSeccion(acceso, 'propiedades')).toBe(false)
    expect(leerSeccion(acceso, 'historial')).toBe(true); expect(leerSeccion(acceso, 'versiones')).toBe(false)
  })
})
describe('menús y teclado', () => {
  const c = { conectado: true, diagrama: true, editable: true, deshacer: true, rehacer: true }
  const habilitadas = (rol: 'OWNER'|'EDITOR'|'VIEWER'|null, contexto = c) => menusPara(rol, contexto).flatMap((g) => g.acciones).filter((a) => a.habilitada).map((a) => a.id)
  it('un Observador lee y descarga pero no importa, dicta imagen ni deshace', () => {
    const ids = habilitadas('VIEWER'); expect(ids).toEqual(expect.arrayContaining(['exportar','generar','versiones','ambiguedades']))
    for (const id of ['importar','imagen','deshacer','rehacer']) expect(ids).not.toContain(id)
  })
  it('Editor y Propietario conservan todas las acciones; sin rol o sin conexión no se escribe', () => {
    for (const rol of ['OWNER','EDITOR'] as const) expect(habilitadas(rol)).toEqual(expect.arrayContaining(['importar','imagen','deshacer','rehacer']))
    for (const ids of [habilitadas(null),habilitadas('OWNER',{...c,conectado:false}),habilitadas('OWNER',{...c,editable:false})]) expect(ids).not.toContain('importar')
  })
  it('flechas circulan saltando deshabilitados y no inventan foco si no hay opciones', () => {
    expect(siguienteHabilitado([false,true,false,true],-1,1)).toBe(1)
    expect(siguienteHabilitado([false,true,false,true],1,-1)).toBe(3)
    expect(siguienteHabilitado([false,false],0,1)).toBe(-1)
    expect(siguienteHabilitado([],0,1)).toBe(-1)
  })
})


describe('tercer tema Grafito', () => {
  it('rota Claro → Azul noche → Grafito → Claro', () => {
    expect(siguienteTema('claro')).toBe('oscuro')
    expect(siguienteTema('oscuro')).toBe('grafito')
    expect(siguienteTema('grafito')).toBe('claro')
  })
  it('recuerda Grafito, respeta las preferencias anteriores y sigue al sistema al pedirlo', () => {
    const datos = new Map<string, string>()
    const acceso = () => ({ getItem: (k: string) => datos.get(k) ?? null, setItem: (k: string, v: string) => { datos.set(k, v) } })
    for (const valor of ['claro', 'oscuro', 'grafito', 'sistema'] as const) {
      guardarTema(acceso, valor)
      expect(leerTema(acceso)).toBe(valor)
      expect(preferenciaValida(valor)).toBe(valor)
    }
    expect(resolverTema('grafito', false)).toBe('grafito')
    expect(resolverTema('grafito', true)).toBe('grafito')
    guardarTema(acceso, 'sistema')
    expect(resolverTema(leerTema(acceso), true)).toBe('oscuro')
    expect(resolverTema(leerTema(acceso), false)).toBe('claro')
  })
  it('Ver ofrece tres opciones radio y marca solo el tema resuelto, incluso sin conexión ni rol', () => {
    const c = { conectado: false, diagrama: false, editable: false, deshacer: false, rehacer: false }
    for (const tema of ['claro', 'oscuro', 'grafito'] as const) {
      const acciones = menusPara(null, c, tema).find((g) => g.titulo === 'Ver')!.acciones
      const radios = acciones.filter((a) => a.tipo === 'radio')
      expect(radios.map((a) => a.texto)).toEqual(['Tema claro', 'Tema azul noche', 'Tema grafito'])
      expect(radios.every((a) => a.habilitada)).toBe(true)
      expect(radios.filter((a) => a.marcada).map((a) => a.id)).toEqual([`tema-${tema}`])
      expect(acciones.find((a) => a.id === 'sistema')?.habilitada).toBe(true)
      expect(acciones.some((a) => a.id === 'tema')).toBe(false)
    }
  })
})
