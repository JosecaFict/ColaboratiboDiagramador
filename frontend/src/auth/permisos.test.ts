import { describe, expect, it } from 'vitest'
import { ETIQUETA_DE_ROL, ROLES_ASIGNABLES, esSoloLectura, permisosDe, type Permisos, type Rol } from './permisos'

/**
 * La tabla de permisos del ADR-026, celda por celda: la MISMA que fija
 * `AccesoAProyectoTest` en el backend. La interfaz solo refleja lo que el
 * servidor decide; si una celda cambia, cambia en los dos lados.
 */
const TABLA: Record<keyof Permisos, Record<Rol, boolean>> = {
  //                          OWNER  EDITOR VIEWER
  leer:                { OWNER: true,  EDITOR: true,  VIEWER: true },
  editarModelo:        { OWNER: true,  EDITOR: true,  VIEWER: false },
  crearDiagramas:      { OWNER: true,  EDITOR: true,  VIEWER: false },
  eliminarDiagramas:   { OWNER: true,  EDITOR: false, VIEWER: false },
  administrarProyecto: { OWNER: true,  EDITOR: false, VIEWER: false },
  administrarMiembros: { OWNER: true,  EDITOR: false, VIEWER: false },
  transferirPropiedad: { OWNER: true,  EDITOR: false, VIEWER: false },
  abandonar:           { OWNER: false, EDITOR: true,  VIEWER: true },
}

describe('permisosDe: que habilita cada rol', () => {
  for (const [permiso, porRol] of Object.entries(TABLA)) {
    for (const rol of ['OWNER', 'EDITOR', 'VIEWER'] as Rol[]) {
      it(`${rol} ${porRol[rol] ? 'puede' : 'no puede'} ${permiso}`, () => {
        expect(permisosDe(rol)[permiso as keyof Permisos]).toBe(porRol[rol])
      })
    }
  }

  it('la tabla cubre todos los permisos que existen', () => {
    expect(Object.keys(TABLA).sort()).toEqual(Object.keys(permisosDe('OWNER')).sort())
  })

  it('sin rol (todavia no se sabe, o ya no tiene acceso) no habilita nada', () => {
    for (const sinRol of [null, undefined]) {
      expect(Object.values(permisosDe(sinRol)).every((v) => v === false)).toBe(true)
    }
  })
})

describe('modo Observador', () => {
  it('solo el VIEWER (y quien no tiene rol) esta en solo lectura', () => {
    expect(esSoloLectura('VIEWER')).toBe(true)
    expect(esSoloLectura(null)).toBe(true)
    expect(esSoloLectura('EDITOR')).toBe(false)
    expect(esSoloLectura('OWNER')).toBe(false)
  })

  it('un Observador lee todo pero no puede editar, crear, eliminar ni administrar', () => {
    const p = permisosDe('VIEWER')
    expect(p.leer).toBe(true)
    expect(p.editarModelo || p.crearDiagramas || p.eliminarDiagramas || p.administrarProyecto
      || p.administrarMiembros || p.transferirPropiedad).toBe(false)
  })
})

describe('roles', () => {
  it('el propietario no se asigna: solo Modelador y Observador', () => {
    expect(ROLES_ASIGNABLES).toEqual(['EDITOR', 'VIEWER'])
  })

  it('las etiquetas estan en espanol', () => {
    expect(ETIQUETA_DE_ROL).toEqual({ OWNER: 'Propietario', EDITOR: 'Modelador', VIEWER: 'Observador' })
  })
})
