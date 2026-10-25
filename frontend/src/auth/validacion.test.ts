import { describe, expect, it } from 'vitest'
import {
  LARGO_MINIMO_DE_CONTRASENA, cumpleLargoMinimo, emailValido, primerCampoConError, validarAcceso,
} from './validacion'

const bien = { nombre: 'Ana Pérez', email: 'ana@uagrm.edu.bo', password: 'contrasena-larga' }

describe('validarAcceso', () => {
  it('un registro correcto no tiene errores', () => {
    expect(validarAcceso('registrar', bien)).toEqual({})
  })

  it('un login correcto tampoco, y no pide nombre', () => {
    expect(validarAcceso('entrar', { ...bien, nombre: '' })).toEqual({})
  })

  it('al registrarse, cada campo vacio se senala con su mensaje en espanol', () => {
    expect(validarAcceso('registrar', { nombre: '  ', email: '', password: '' })).toEqual({
      nombre: 'El nombre es obligatorio',
      email: 'El email es obligatorio',
      password: 'La contraseña es obligatoria',
    })
  })

  it('el largo minimo de la contrasena solo se exige al crear la cuenta', () => {
    const corta = { ...bien, password: 'corta' }

    expect(validarAcceso('registrar', corta).password)
      .toBe('La contraseña debe tener al menos 8 caracteres')
    // Una cuenta anterior a la regla tiene que poder entrar.
    expect(validarAcceso('entrar', corta)).toEqual({})
  })

  it('el minimo es el mismo del servidor', () => {
    expect(LARGO_MINIMO_DE_CONTRASENA).toBe(8)
    expect(cumpleLargoMinimo('1234567')).toBe(false)
    expect(cumpleLargoMinimo('12345678')).toBe(true)
  })

  it('un email mal escrito se senala, con o sin espacios en los bordes', () => {
    for (const malo of ['ana', 'ana@', '@uagrm.edu.bo', 'ana@gmail', 'a na@x.com', 'ana@@x.com']) {
      expect(validarAcceso('entrar', { ...bien, email: malo }).email, malo).toBe('El email no es válido')
    }
    expect(validarAcceso('entrar', { ...bien, email: '  ana@uagrm.edu.bo  ' })).toEqual({})
  })

  it('la contrasena no se recorta: los espacios cuentan', () => {
    expect(validarAcceso('registrar', { ...bien, password: '        ' })).toEqual({})
  })
})

describe('emailValido', () => {
  it('acepta las formas habituales', () => {
    for (const ok of ['ana@uagrm.edu.bo', 'a.b+c@sub.dominio.org', 'demo-editor@uagrm.edu.bo']) {
      expect(emailValido(ok), ok).toBe(true)
    }
  })
})

describe('primerCampoConError', () => {
  it('lleva el foco al primero que se ve, en el orden del formulario', () => {
    const todos = { nombre: 'x', email: 'x', password: 'x' }

    expect(primerCampoConError('registrar', todos)).toBe('nombre')
    expect(primerCampoConError('registrar', { email: 'x', password: 'x' })).toBe('email')
    expect(primerCampoConError('registrar', { password: 'x' })).toBe('password')
  })

  it('al iniciar sesion no hay campo de nombre: ni aunque tuviera error', () => {
    expect(primerCampoConError('entrar', { nombre: 'x', password: 'x' })).toBe('password')
  })

  it('sin errores no hay a donde ir', () => {
    expect(primerCampoConError('entrar', {})).toBeNull()
  })
})
