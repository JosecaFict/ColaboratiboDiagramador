import { renderToStaticMarkup } from 'react-dom/server'
import { describe, expect, it } from 'vitest'
import { VistaIA } from './VistaIA'
import {
  AVISO_SIN_VOZ, idiomaDeDictado, mensajeDeErrorDeVoz, reconocedorDisponible, textoReconocido,
  type EventoDeVoz,
} from './voz'

class Falso {}

describe('dictado por voz', () => {
  it('usa SpeechRecognition o el prefijo webkit de Chrome y Edge', () => {
    expect(reconocedorDisponible({ SpeechRecognition: Falso })).toBe(Falso)
    expect(reconocedorDisponible({ webkitSpeechRecognition: Falso })).toBe(Falso)
  })

  it('sin la API (Firefox, o fuera del navegador) no hay reconocedor', () => {
    expect(reconocedorDisponible({})).toBeNull()
    expect(reconocedorDisponible(undefined)).toBeNull()
  })

  it('arma el pedido con todo lo reconocido, provisional incluido', () => {
    const evento: EventoDeVoz = {
      resultIndex: 1,
      results: {
        length: 2,
        0: { isFinal: true, length: 1, 0: { transcript: 'agrega una clase ' } },
        1: { isFinal: false, length: 1, 0: { transcript: ' Pago' } },
      },
    }
    expect(textoReconocido(evento)).toBe('agrega una clase Pago')
  })

  it('dicta en el español del navegador o, si no, en es-ES', () => {
    expect(idiomaDeDictado('es-BO')).toBe('es-BO')
    expect(idiomaDeDictado('en-US')).toBe('es-ES')
    expect(idiomaDeDictado(undefined)).toBe('es-ES')
  })

  it('explica los errores del micrófono y calla la cancelación propia', () => {
    expect(mensajeDeErrorDeVoz('not-allowed')).toContain('permiso')
    expect(mensajeDeErrorDeVoz('no-speech')).toContain('No se escuchó')
    expect(mensajeDeErrorDeVoz('aborted')).toBeNull()
    expect(mensajeDeErrorDeVoz('otro')).toContain('escribir')
  })

  it('sin soporte, la vista desactiva el botón y dice qué hacer', () => {
    const html = renderToStaticMarkup(
      <VistaIA diagramId="d1" proyectoId="p1" token="t" conectado puedeEditar usuarioId="u1" usuario="Ana" rol="OWNER"
        modelo={{ classes: [], relationships: [] } as never}
        onAplicar={() => Promise.resolve({ estado: 'aceptado', opId: 'x' })} />)
    expect(html).toContain(AVISO_SIN_VOZ)
    expect(html).toMatch(/<button[^>]*disabled=""[^>]*aria-label="Dictar el pedido"|<button[^>]*aria-label="Dictar el pedido"[^>]*disabled=""/)
  })
})
