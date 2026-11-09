import { describe, expect, it } from 'vitest'
import { guardarTema, leerTema, resolverTema, guardarSeccion, leerSeccion, menusPara, siguienteHabilitado } from './estado'
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
