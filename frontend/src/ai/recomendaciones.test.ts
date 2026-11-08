import { describe, expect, it } from 'vitest'
import { elegidas, ordenarRecomendaciones, pendientesComoRecomendaciones, seleccionInicial, textoDeRecomendaciones } from './recomendaciones'
import type { RecomendacionIA } from './tipos'

const rec = (id: number, origen: RecomendacionIA['origen'], gravedad: RecomendacionIA['gravedad']): RecomendacionIA => ({
  id, origen, gravedad, titulo: `R${id}`, categoria: 'RELACION', regla: '', elementos: [], problema: '', accion: '',
})

describe('recomendaciones', () => {
  const lista = [rec(1, 'MODELO', 'ALTA'), rec(2, 'VERIFICADO', 'BAJA'), rec(3, 'VERIFICADO', 'ALTA'), rec(4, 'VERIFICADO', 'MEDIA')]

  it('ordena lo verificado primero y por gravedad', () => {
    expect(ordenarRecomendaciones(lista).map((r) => r.id)).toEqual([3, 4, 2, 1])
  })

  it('preselecciona solo lo verificado de gravedad alta o media', () => {
    expect([...seleccionInicial(lista)].sort()).toEqual([3, 4])
  })

  it('envia solo las elegidas', () => {
    expect(elegidas(lista, new Set([1, 4])).map((r) => r.id)).toEqual([1, 4])
  })

  it('convierte los pendientes de Diseñar en tarjetas verificadas', () => {
    const r = pendientesComoRecomendaciones([
      'La clase AsignacionServicio queda desconectada del resto del modelo.',
      'Sin resolver por la IA, revisalo antes de aplicar: Clase desconectada: AsignacionServicio',
      'Sin resolver por la IA, revisalo antes de aplicar: Itinerario no identifica el paquete o la salida al que pertenece',
    ])
    expect(r.recomendaciones.map((x) => x.titulo)).toEqual(['Clase desconectada: AsignacionServicio',
      'Itinerario no identifica el paquete o la salida al que pertenece'])
    expect(r.recomendaciones.every((x) => x.origen === 'VERIFICADO' && x.gravedad === 'ALTA')).toBe(true)
    expect(r.recomendaciones[0].accion).toContain('Corrige en el diagrama')
  })

  it('sin pendientes "sin resolver" usa los avisos de la auditoria', () => {
    const r = pendientesComoRecomendaciones(['La relacion A [1] - [1] B es uno a uno'])
    expect(r.recomendaciones).toHaveLength(1)
    expect(r.recomendaciones[0].gravedad).toBe('MEDIA')
  })

  it('resume cuantas son verificadas y cuantas sugeridas', () => {
    const texto = textoDeRecomendaciones({ diagramId: 'd', resumen: 'Listo.', recomendaciones: lista,
      verificaciones: [], hallazgosEstructurales: [], modelo: null })
    expect(texto).toContain('4 recomendaciones: 3 verificadas por el backend y 1 sugerida por el modelo')
  })
})
