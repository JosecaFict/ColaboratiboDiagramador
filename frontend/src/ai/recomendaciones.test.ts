import { describe, expect, it } from 'vitest'
import { elegidas, ordenarRecomendaciones, seleccionInicial, textoDeRecomendaciones } from './recomendaciones'
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

  it('resume cuantas son verificadas y cuantas sugeridas', () => {
    const texto = textoDeRecomendaciones({ diagramId: 'd', resumen: 'Listo.', recomendaciones: lista,
      verificaciones: [], hallazgosEstructurales: [], modelo: null })
    expect(texto).toContain('4 recomendaciones: 3 verificadas por el backend y 1 sugerida por el modelo')
  })
})
