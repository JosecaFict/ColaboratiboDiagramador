import { describe, expect, it } from 'vitest'
import {
  inicialDe, mensajeDeFalloDePropuesta, mensajesDeChat, textoDeDestinatario, textoTrasFallo,
} from './chat'
import type { EntradaHistorialIA, PropuestaIA } from './tipos'

const propuesta = (): PropuestaIA => ({
  diagramId: 'd1', origin: 'TEXT', rechazados: [], descartadosPorTope: [], advertencias: [],
  propuestosPorElModelo: 0, notaDelModelo: 'Listo', modelo: 'qwen3:8b', aceptados: [],
})

const entrada = (id: string, userId: string | null, userName: string, parcial: Partial<EntradaHistorialIA> = {}): EntradaHistorialIA => ({
  id, userId, userName, modo: 'CONSULTAR', modelo: 'qwen3:8b', prompt: `pedido de ${userName}`,
  respuesta: 'Listo', propuesta: propuesta(), aplicada: false, creadaEn: '2026-11-02T10:00:00Z', ...parcial,
})

const respuesta = () => 'respuesta de la IA'

describe('el historial como chat grupal', () => {
  it('cada pedido lleva el nombre y la inicial de su autor, y la IA contesta debajo', () => {
    const m = mensajesDeChat([entrada('a', 'u-beto', 'beto')], null, 'u-ana', 'Ana', respuesta)
    expect(m.map((x) => x.autor)).toEqual(['usuario', 'asistente'])
    expect(m[0]).toMatchObject({ nombre: 'beto', inicial: 'B', texto: 'pedido de beto', propio: false })
    expect(m[1]).toMatchObject({ autor: 'asistente', texto: 'respuesta de la IA', propio: false })
  })

  it('lo propio se decide por userId y va del lado propio; lo del resto, del otro', () => {
    const m = mensajesDeChat([entrada('a', 'u-ana', 'Ana'), entrada('b', 'u-beto', 'Beto')], null, 'u-ana', 'Ana', respuesta)
    expect(m.filter((x) => x.autor === 'usuario').map((x) => x.propio)).toEqual([true, false])
    expect(m[0].etiqueta).toBe('Ana · vos')
    expect(m[2].etiqueta).toBe('Beto')
  })

  it('dos personas con el MISMO nombre no se confunden: manda el userId', () => {
    const m = mensajesDeChat(
      [entrada('a', 'u-1', 'Ana'), entrada('b', 'u-2', 'Ana'), entrada('c', 'u-1', 'Ana')],
      null, 'u-2', 'Ana', respuesta)
    const pedidos = m.filter((x) => x.autor === 'usuario')
    expect(pedidos.map((x) => x.propio)).toEqual([false, true, false])
    expect(pedidos.map((x) => x.etiqueta)).toEqual(['Ana (1)', 'Ana (2) · vos', 'Ana (1)'])
  })

  it('una sola persona con ese nombre no lleva número', () => {
    const m = mensajesDeChat([entrada('a', 'u-1', 'Ana')], null, 'u-9', 'Zoe', respuesta)
    expect(m[0].etiqueta).toBe('Ana')
  })

  it('la respuesta de la IA dice a quién le contesta: a vos o a otra persona', () => {
    const m = mensajesDeChat([entrada('a', 'u-ana', 'Ana'), entrada('b', 'u-beto', 'Beto')], null, 'u-ana', 'Ana', respuesta)
    const respuestas = m.filter((x) => x.autor === 'asistente')
    expect(respuestas[0].respondeA).toEqual({ nombre: 'Ana', tuyo: true })
    expect(respuestas[1].respondeA).toEqual({ nombre: 'Beto', tuyo: false })
    expect(textoDeDestinatario(respuestas[0].respondeA!)).toBe('responde a vos')
    expect(textoDeDestinatario(respuestas[1].respondeA!)).toBe('responde a Beto')
    // La respuesta va del mismo lado que el pedido al que contesta.
    expect(respuestas.map((x) => x.propio)).toEqual([true, false])
  })

  it('un usuario eliminado conserva su pedido con un nombre de respaldo y nunca es "propio"', () => {
    const m = mensajesDeChat([entrada('a', null, '')], null, 'u-ana', 'Ana', respuesta)
    expect(m[0]).toMatchObject({ nombre: 'Usuario eliminado', propio: false, inicial: 'U' })
    expect(m[1].respondeA).toEqual({ nombre: 'Usuario eliminado', tuyo: false })
  })

  it('un userId nulo nunca coincide con el mío aunque mi id también sea vacío', () => {
    const m = mensajesDeChat([entrada('a', null, 'Fantasma')], null, '', 'Yo', respuesta)
    expect(m[0].propio).toBe(false)
  })

  it('mantiene el orden del historial y suma al final el pedido propio que espera respuesta', () => {
    const m = mensajesDeChat(
      [entrada('a', 'u-beto', 'Beto'), entrada('b', 'u-ana', 'Ana')],
      { id: 'p1', texto: 'agrega Pago', modo: 'MODIFICAR' }, 'u-ana', 'Ana', respuesta)
    expect(m.map((x) => x.id)).toEqual(['a-usuario', 'a-asistente', 'b-usuario', 'b-asistente', 'p1'])
    expect(m[4]).toMatchObject({ propio: true, enviando: true, nombre: 'Ana', etiqueta: 'Ana · vos', modo: 'MODIFICAR' })
  })

  it('la inicial sale de la primera letra del nombre', () => {
    expect(inicialDe('  ñandú')).toBe('Ñ')
    expect(inicialDe('')).toBe('?')
  })
})

describe('un pedido que falla no pierde lo escrito', () => {
  it('lo intentado vuelve al cuadro si está vacío', () => {
    expect(textoTrasFallo('', 'agrega una clase Pago')).toBe('agrega una clase Pago')
    expect(textoTrasFallo('   ', 'agrega una clase Pago')).toBe('agrega una clase Pago')
  })

  it('si mientras tanto se escribió otra cosa, no se pisa', () => {
    expect(textoTrasFallo('otro pedido', 'agrega una clase Pago')).toBe('otro pedido')
  })

  it('el 422 muestra el mensaje del servidor tal cual', () => {
    expect(mensajeDeFalloDePropuesta(422, { mensaje: 'El pedido no entra en el contexto del modelo (4096 tokens).' }))
      .toBe('El pedido no entra en el contexto del modelo (4096 tokens).')
  })

  it('un 422 sin cuerpo igual se explica, y otros errores dicen su estado', () => {
    expect(mensajeDeFalloDePropuesta(422, null)).toContain('no entran en el contexto')
    expect(mensajeDeFalloDePropuesta(502, null)).toBe('La IA respondió con HTTP 502.')
    expect(mensajeDeFalloDePropuesta(400, { mensaje: 'Falta el prompt' })).toBe('Falta el prompt')
  })
})
