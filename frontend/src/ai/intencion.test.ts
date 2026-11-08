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
    ['¿Cómo modela el diagrama las reservas?', 'CONSULTAR'],
    ['Crea la clase Proveedor', 'MODIFICAR'],
  ])('"%s" -> %s', (texto, esperado) => {
    expect(detectarIntencion(texto)).toBe(esperado)
  })

  it('un requisito completo que pide diseñar no se confunde con revisar ni corregir', () => {
    // Prueba desde el chat flotante, 2026-11-08.
    const turismo = 'Actúa como Arquitecto de Software Senior y diseña desde cero el modelo conceptual UML para una agencia '
      + 'de turismo. El diagrama está vacío. Deduce autónomamente las clases, atributos, asociaciones y cardinalidades. '
      + 'AUTOVERIFICACIÓN Revisa silenciosamente: que una reserva identifique una salida concreta; que no existan clases '
      + 'vacías o desconectadas. Prepara una auditoria y corrige errores de cardinalidad.'
    expect(detectarIntencion(turismo)).toBe('DISENAR')
    expect(detectarIntencion('El sistema permite registrar ventas y corregir errores de facturación en las notas de crédito '
      + 'emitidas a los clientes del negocio')).not.toBe('CORREGIR')
  })
})
