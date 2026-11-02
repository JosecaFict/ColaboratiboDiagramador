import { renderToStaticMarkup } from 'react-dom/server'
import { describe, expect, it, vi } from 'vitest'
import { VistaPanelConfiguracion, resumenDeConfiguracion, type VistaDelPanel } from './PanelConfiguracionIA'
import {
  CONFIGURACION_POR_DEFECTO, MAX_INSTRUCCIONES, errorDeBorrador, guardarConfiguracion, hayCambios,
  leerConfiguracion, puedeEditarConfiguracion, resolverGuardado, type ConfiguracionIA,
} from './configuracion'

const cfg = (parcial: Partial<ConfiguracionIA> = {}): ConfiguracionIA => ({ ...CONFIGURACION_POR_DEFECTO, ...parcial })

const respuesta = (estado: number, cuerpo: unknown) =>
  Promise.resolve(new Response(cuerpo === undefined ? null : JSON.stringify(cuerpo), { status: estado }))

const fetchFalso = (estado: number, cuerpo?: unknown) =>
  vi.fn((_u: RequestInfo | URL, _i?: RequestInit) => respuesta(estado, cuerpo)) as unknown as typeof fetch & ReturnType<typeof vi.fn>

describe('cliente de la configuración de la IA', () => {
  it('GET lee la configuración del proyecto con el token', async () => {
    const f = fetchFalso(200, cfg({ rol: 'SENIOR', detalle: 'BREVE', instrucciones: 'sé breve' }))
    const r = await leerConfiguracion('p1', 'tok', f)
    expect(r).toEqual({ estado: 'ok', configuracion: { rol: 'SENIOR', detalle: 'BREVE', instrucciones: 'sé breve' } })
    const [url, init] = (f as unknown as ReturnType<typeof vi.fn>).mock.calls[0]
    expect(url).toBe('/api/v1/projects/p1/ia/configuracion')
    expect((init as RequestInit).headers).toMatchObject({ Authorization: 'Bearer tok' })
  })

  it('PUT manda los tres campos, con las instrucciones sin espacios en los bordes', async () => {
    const f = fetchFalso(200, cfg({ rol: 'REVISOR', instrucciones: 'usá UUID' }))
    const r = await guardarConfiguracion('p1', 'tok', cfg({ rol: 'REVISOR', instrucciones: '  usá UUID  ' }), f)
    expect(r.estado).toBe('ok')
    const [, init] = (f as unknown as ReturnType<typeof vi.fn>).mock.calls[0]
    expect((init as RequestInit).method).toBe('PUT')
    expect(JSON.parse((init as RequestInit).body as string))
      .toEqual({ rol: 'REVISOR', detalle: 'NORMAL', instrucciones: 'usá UUID' })
  })

  it('un 403 al guardar es "prohibido", no un éxito', async () => {
    const r = await guardarConfiguracion('p1', 'tok', cfg(), fetchFalso(403, { mensaje: 'Solo el propietario puede cambiarla' }))
    expect(r).toEqual({ estado: 'prohibido', mensaje: 'Solo el propietario puede cambiarla' })
  })

  it('un 400 trae el mensaje del servidor', async () => {
    const r = await guardarConfiguracion('p1', 'tok', cfg(), fetchFalso(400, { mensaje: 'Rol inválido' }))
    expect(r).toEqual({ estado: 'invalido', mensaje: 'Rol inválido' })
  })

  it('una respuesta 200 con un cuerpo que no es la configuración no se toma por éxito', async () => {
    const r = await guardarConfiguracion('p1', 'tok', cfg(), fetchFalso(200, { otra: 'cosa' }))
    expect(r.estado).toBe('error')
  })

  it('sin conexión, leer y guardar fallan con un aviso y sin lanzar', async () => {
    const caido = (() => Promise.reject(new TypeError('red'))) as unknown as typeof fetch
    expect((await leerConfiguracion('p1', 'tok', caido)).estado).toBe('error')
    expect((await guardarConfiguracion('p1', 'tok', cfg(), caido)).estado).toBe('error')
  })
})

describe('guardar no pierde el borrador', () => {
  const guardada = cfg()
  const borrador = cfg({ rol: 'SENIOR', instrucciones: 'sé breve y usá español' })
  const estado = { guardada, borrador, aviso: null }

  it('con éxito, lo guardado pasa a ser lo que devolvió el servidor', () => {
    const del = cfg({ rol: 'SENIOR', instrucciones: 'sé breve y usá español' })
    const s = resolverGuardado(estado, borrador, { estado: 'ok', configuracion: del })
    expect(s.guardada).toEqual(del)
    expect(s.borrador).toEqual(del)
    expect(s.aviso?.tipo).toBe('ok')
  })

  it('un 403 NO cambia lo guardado ni el borrador, y no muestra éxito', () => {
    const s = resolverGuardado(estado, borrador, { estado: 'prohibido', mensaje: 'Solo el propietario puede cambiarla.' })
    expect(s.guardada).toEqual(guardada)
    expect(s.borrador).toEqual(borrador)
    expect(s.aviso?.tipo).toBe('mal')
    expect(s.aviso?.texto).toContain('No se guardó')
    expect(s.aviso?.texto).toContain('Tu borrador sigue aquí')
  })

  it('un 400 o un corte tampoco tocan el borrador', () => {
    for (const r of [{ estado: 'invalido', mensaje: 'Rol inválido' }, { estado: 'error', mensaje: 'sin conexión' }] as const) {
      const s = resolverGuardado(estado, borrador, r)
      expect(s.guardada).toEqual(guardada)
      expect(s.borrador).toEqual(borrador)
      expect(s.aviso?.tipo).toBe('mal')
    }
  })

  it('si se siguió escribiendo mientras se guardaba, lo nuevo no se pisa', () => {
    const siguio = cfg({ rol: 'SENIOR', instrucciones: 'sé breve y usá español, y sin tildes' })
    const s = resolverGuardado({ ...estado, borrador: siguio }, borrador, { estado: 'ok', configuracion: borrador })
    expect(s.guardada).toEqual(borrador)
    expect(s.borrador).toEqual(siguio)
  })

  it('hay cambios solo si algo real cambió (los espacios en los bordes no cuentan)', () => {
    expect(hayCambios(cfg({ instrucciones: 'x' }), cfg({ instrucciones: '  x  ' }))).toBe(false)
    expect(hayCambios(cfg(), cfg({ detalle: 'BREVE' }))).toBe(true)
  })

  it('más de 1000 caracteres se avisa antes de mandar', () => {
    expect(errorDeBorrador(cfg({ instrucciones: 'a'.repeat(MAX_INSTRUCCIONES) }))).toBeNull()
    expect(errorDeBorrador(cfg({ instrucciones: 'a'.repeat(MAX_INSTRUCCIONES + 1) }))).toContain('1000')
  })
})

describe('el panel según el rol', () => {
  const vista = (parcial: Partial<VistaDelPanel> = {}): VistaDelPanel => ({
    guardada: cfg(), borrador: cfg(), cargando: false, errorDeCarga: null, guardando: false, aviso: null,
    puedeEditar: true, ...parcial,
  })
  const acciones = { onCambiar: () => undefined, onGuardar: () => undefined, onDescartar: () => undefined, onReintentar: () => undefined }
  const html = (v: VistaDelPanel) => renderToStaticMarkup(<VistaPanelConfiguracion v={v} a={acciones} />)
  const deshabilitados = (h: string) => (h.match(/disabled=""/g) ?? []).length

  it('solo el OWNER edita', () => {
    expect(puedeEditarConfiguracion('OWNER')).toBe(true)
    expect(puedeEditarConfiguracion('EDITOR')).toBe(false)
    expect(puedeEditarConfiguracion('VIEWER')).toBe(false)
  })

  it('el OWNER ve los campos editables y el botón Guardar', () => {
    const h = html(vista({ borrador: cfg({ detalle: 'BREVE' }) }))
    expect(h).toContain('Guardar')
    expect(h).toContain('Descartar')
    // Los tres campos están habilitados; solo Guardar y Descartar no (no hay cambios guardados distintos... sí los hay).
    expect(h).not.toMatch(/<select[^>]*disabled/)
    expect(h).not.toMatch(/<textarea[^>]*disabled/)
  })

  it('un EDITOR o un VIEWER ven la configuración en solo lectura, sin Guardar', () => {
    const h = html(vista({ puedeEditar: false, guardada: cfg({ rol: 'REVISOR' }), borrador: cfg({ rol: 'REVISOR' }) }))
    expect(h).toContain('Solo el propietario puede cambiarla')
    expect(h).not.toContain('Guardar')
    expect(h).not.toContain('Descartar')
    expect(h).toMatch(/<select[^>]*disabled/)
    expect(h).toMatch(/<textarea[^>]*disabled/)
    expect(h).toContain('Revisor')
  })

  it('Guardar está apagado si no hay cambios y encendido si los hay', () => {
    const sin = html(vista())
    const con = html(vista({ borrador: cfg({ detalle: 'DETALLADO' }) }))
    expect(sin).toMatch(/<button[^>]*disabled=""[^>]*>Guardar/)
    expect(con).not.toMatch(/<button[^>]*disabled=""[^>]*>Guardar/)
  })

  it('con más de 1000 caracteres no se puede guardar y el contador lo marca', () => {
    const h = html(vista({ borrador: cfg({ instrucciones: 'a'.repeat(MAX_INSTRUCCIONES + 1) }) }))
    expect(h).toMatch(/<button[^>]*disabled=""[^>]*>Guardar/)
    expect(h).toContain('excedido')
    expect(h).toContain(`${MAX_INSTRUCCIONES + 1} / ${MAX_INSTRUCCIONES}`)
  })

  it('un 403 al guardar se ve como error y el borrador queda en pantalla', () => {
    const s = resolverGuardado(
      { guardada: cfg(), borrador: cfg({ instrucciones: 'mi borrador importante' }), aviso: null },
      cfg({ instrucciones: 'mi borrador importante' }), { estado: 'prohibido', mensaje: 'Solo el propietario puede cambiarla.' })
    const h = html(vista({ guardada: s.guardada, borrador: s.borrador, aviso: s.aviso }))
    expect(h).toContain('role="alert"')
    expect(h).toContain('No se guardó')
    expect(h).not.toContain('Configuración guardada')
    expect(h).toContain('mi borrador importante')
  })

  it('el éxito se anuncia como estado, no como alerta', () => {
    const h = html(vista({ aviso: { tipo: 'ok', texto: 'Configuración guardada.' } }))
    expect(h).toContain('role="status"')
  })

  it('si la lectura falla se dice y no se deja editar; se puede reintentar', () => {
    const h = html(vista({ guardada: null, errorDeCarga: 'sin conexión', puedeEditar: true }))
    expect(h).toContain('sin conexión')
    expect(h).toContain('Reintentar')
    expect(deshabilitados(h)).toBeGreaterThanOrEqual(3)
  })

  it('no hay contador de memoria: no existe ese dato en el contrato', () => {
    expect(html(vista())).not.toMatch(/recuerda|memoria|últimos \d+ mensajes/i)
  })

  it('el resumen del encabezado dice rol y detalle', () => {
    expect(resumenDeConfiguracion(cfg({ rol: 'SENIOR', detalle: 'DETALLADO' }))).toBe('Senior · Detallado')
    expect(resumenDeConfiguracion(null)).toBe('Sin leer')
  })
})
