import { describe, expect, it } from 'vitest'
import { detectarIntencion } from './intencion'

describe('detectarIntencion', () => {
  it.each([
    ['Diseña un sistema de ventas de computadoras', 'DISENAR'],
    ['diseñame el modelo de una agencia de turismo', 'DISENAR'],
    ['Corrige los errores', 'CORREGIR'],
    ['arreglá los pendientes del diagrama', 'CORREGIR'],
    ['Revisa el diagrama', 'RECOMENDAR'],
    ['¿qué le falta a este modelo?', 'RECOMENDAR'],
    ['¿está bien el diagrama?', 'RECOMENDAR'],
    ['Agrega una clase Proveedor relacionada con Producto', 'MODIFICAR'],
    ['Conecta Itinerario con Paquete', 'MODIFICAR'],
    ['¿Qué representa la clase Titular?', 'CONSULTAR'],
    ['explicame las relaciones', 'CONSULTAR'],
    ['', 'CONSULTAR'],
  ])('"%s" -> %s', (texto, esperado) => {
    expect(detectarIntencion(texto)).toBe(esperado)
  })
})
