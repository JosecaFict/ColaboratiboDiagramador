import { describe, expect, it } from 'vitest'
import { interpretarAviso, type AvisoDeAcceso } from './avisos'
import type { Ruta } from './ruta'

const P = '11111111-2222-3333-4444-555555555555'
const OTRO = '99999999-2222-3333-4444-555555555555'
const D = 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee'

const enDiagrama: Ruta = { tipo: 'diagrama', proyectoId: P, diagramaId: D }
const enProyecto: Ruta = { tipo: 'proyecto', proyectoId: P }
const enLista: Ruta = { tipo: 'proyectos' }

const aviso = (a: Partial<AvisoDeAcceso>): AvisoDeAcceso =>
  ({ projectId: P, projectName: 'Biblioteca', reason: 'ROL_CAMBIADO', ...a })

/**
 * "Con el aviso de cambio de rol, la UI se adapta en caliente. Si lo quitaron,
 * vuelve a Mis proyectos con un mensaje" (ADR-026): la decision, sin navegador.
 */
describe('interpretarAviso', () => {
  it('lo quitan del proyecto que esta mirando: vuelve a Mis proyectos con un mensaje', () => {
    for (const ruta of [enDiagrama, enProyecto]) {
      const e = interpretarAviso(aviso({ reason: 'QUITADO' }), ruta)

      expect(e.irA).toEqual({ tipo: 'proyectos' })
      expect(e.mensaje).toBe('Te quitaron del proyecto «Biblioteca».')
      expect(e.refrescar).toBe(true)
    }
  })

  it('lo quitan de un proyecto que NO esta mirando: no lo mueve, solo refresca la lista', () => {
    const e = interpretarAviso(aviso({ reason: 'QUITADO', projectId: OTRO }), enDiagrama)

    expect(e.irA).toBeUndefined()
    expect(e.refrescar).toBe(true)
  })

  it('lo pasan a Observador: cambia el rol y avisa que el diagrama quedo en solo lectura', () => {
    const e = interpretarAviso(aviso({ reason: 'ROL_CAMBIADO', role: 'VIEWER' }), enDiagrama)

    expect(e.rol).toBe('VIEWER')
    expect(e.irA).toBeUndefined()      // se queda donde esta: la UI se adapta sin recargar
    expect(e.mensaje).toContain('solo lectura')
  })

  it('lo promueven a Modelador: cambia el rol y avisa que ya puede editar', () => {
    const e = interpretarAviso(aviso({ reason: 'ROL_CAMBIADO', role: 'EDITOR' }), enDiagrama)

    expect(e.rol).toBe('EDITOR')
    expect(e.mensaje).toContain('Modelador')
    expect(e.mensaje).toContain('ya podés editar')
  })

  it('un cambio de rol en otro proyecto no toca el rol de este', () => {
    const e = interpretarAviso(aviso({ reason: 'ROL_CAMBIADO', role: 'VIEWER', projectId: OTRO }), enDiagrama)

    expect(e.rol).toBeUndefined()
    expect(e.mensaje).toBeUndefined()
    expect(e.refrescar).toBe(true)
  })

  it('recibir o ceder la propiedad cambia el rol en el proyecto abierto', () => {
    expect(interpretarAviso(aviso({ reason: 'PROPIEDAD_RECIBIDA', role: 'OWNER' }), enProyecto).rol).toBe('OWNER')
    const cedida = interpretarAviso(aviso({ reason: 'PROPIEDAD_CEDIDA', role: 'EDITOR' }), enProyecto)
    expect(cedida.rol).toBe('EDITOR')
    expect(cedida.mensaje).toContain('Modelador')
  })

  it('eliminan el proyecto que esta mirando: vuelve a la lista y se le dice por que', () => {
    const e = interpretarAviso(aviso({ reason: 'PROYECTO_ELIMINADO' }), enDiagrama)

    expect(e.irA).toEqual({ tipo: 'proyectos' })
    expect(e.mensaje).toContain('eliminado')
  })

  it('eliminan el diagrama que esta mirando: lo lleva al proyecto, no a la lista', () => {
    const e = interpretarAviso(aviso({ reason: 'DIAGRAMA_ELIMINADO', diagramId: D }), enDiagrama)

    expect(e.irA).toEqual({ tipo: 'proyecto', proyectoId: P })
    expect(e.mensaje).toContain('diagrama')
  })

  it('eliminan OTRO diagrama del proyecto: no lo mueve, solo refresca', () => {
    const e = interpretarAviso(aviso({ reason: 'DIAGRAMA_ELIMINADO', diagramId: 'otro' }), enDiagrama)

    expect(e.irA).toBeUndefined()
    expect(e.mensaje).toBeUndefined()
    expect(e.refrescar).toBe(true)
  })

  it('lo agregan a un proyecto: avisa con su rol, aunque este en cualquier pantalla', () => {
    const e = interpretarAviso(aviso({ reason: 'AGREGADO', role: 'VIEWER' }), enLista)

    expect(e.mensaje).toBe('Te agregaron al proyecto «Biblioteca» como Observador.')
    expect(e.refrescar).toBe(true)
  })

  it('abandonar desde otra pestana lo saca de esta si estaba mirando ese proyecto', () => {
    const e = interpretarAviso(aviso({ reason: 'ABANDONO' }), enProyecto)

    expect(e.irA).toEqual({ tipo: 'proyectos' })
    expect(interpretarAviso(aviso({ reason: 'ABANDONO' }), enLista).mensaje).toBeUndefined()
  })
})
