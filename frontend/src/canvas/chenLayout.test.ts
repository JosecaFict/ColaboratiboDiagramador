import { describe, expect, it } from 'vitest'
import { arcoLibre, posicionarAtributos, posicionarSubAtributos, radioHalo } from './chenLayout'

const GRADO = Math.PI / 180
const CENTRO = { x: 100, y: 100 }
const distancia = (a: { x: number; y: number }, b: { x: number; y: number }) =>
  Math.hypot(a.x - b.x, a.y - b.y)

describe('arcoLibre', () => {
  it('sin relaciones usa el semicirculo superior, como un Chen de libro', () => {
    expect(arcoLibre([])).toEqual({ inicio: 200 * GRADO, fin: 340 * GRADO })
  })

  it('esquiva el cono que ocupa una relacion', () => {
    const anguloRelacion = 0
    const { inicio, fin } = arcoLibre([anguloRelacion])
    // El arco elegido arranca despues del cono de la relacion y no lo pisa.
    expect(inicio).toBeGreaterThanOrEqual(anguloRelacion + 20 * GRADO)
    expect(fin - inicio).toBeGreaterThan(0)
  })

  it('elige el hueco MAS GRANDE entre varias relaciones', () => {
    // Relaciones a 0 y 90 grados: el hueco grande va de 90 a 360, no de 0 a 90.
    const { inicio, fin } = arcoLibre([0, 90 * GRADO])
    expect(fin - inicio).toBeGreaterThan(180 * GRADO)
    expect(inicio).toBeGreaterThanOrEqual(90 * GRADO)
  })
})

describe('posicionarAtributos', () => {
  it('devuelve una posicion por atributo', () => {
    expect(posicionarAtributos(CENTRO, 5)).toHaveLength(5)
  })

  it('sin atributos no devuelve nada', () => {
    expect(posicionarAtributos(CENTRO, 0)).toEqual([])
  })

  it('es DETERMINISTA: el mismo modelo da la misma imagen (ADR-006)', () => {
    // Es la garantia que permite no persistir la geometria de los atributos:
    // si esto fallara, dos usuarios verian el diagrama distinto.
    const angulos = [0.3, 1.9, 4.2]
    expect(posicionarAtributos(CENTRO, 6, angulos)).toEqual(posicionarAtributos(CENTRO, 6, angulos))
  })

  it('coloca hasta 8 atributos en un solo anillo, a radio constante', () => {
    for (const p of posicionarAtributos(CENTRO, 8)) {
      expect(distancia(p, CENTRO)).toBeCloseTo(90)
    }
  })

  it('a partir de 9 atributos usa un segundo anillo', () => {
    const radios = posicionarAtributos(CENTRO, 9).map((p) => Math.round(distancia(p, CENTRO)))
    expect(new Set(radios)).toEqual(new Set([90, 150]))
  })

  it('reparte los atributos dentro del arco libre, sin pisar la relacion', () => {
    const anguloRelacion = 270 * GRADO
    const puntos = posicionarAtributos(CENTRO, 4, [anguloRelacion])
    for (const p of puntos) {
      const ang = Math.atan2(p.y - CENTRO.y, p.x - CENTRO.x)
      const diferencia = Math.abs(Math.atan2(Math.sin(ang - anguloRelacion), Math.cos(ang - anguloRelacion)))
      expect(diferencia).toBeGreaterThan(20 * GRADO)
    }
  })
})

describe('posicionarSubAtributos', () => {
  it('orbita al ovalo padre, no a la entidad', () => {
    const padre = { x: 200, y: 100 }
    for (const hijo of posicionarSubAtributos(CENTRO, padre, 3)) {
      expect(distancia(hijo, padre)).toBeCloseTo(50)
    }
  })

  it('apunta hacia afuera para no cruzarse con la entidad', () => {
    const padre = { x: 200, y: 100 }   // el padre esta a la derecha del centro
    for (const hijo of posicionarSubAtributos(CENTRO, padre, 3)) {
      expect(hijo.x).toBeGreaterThan(padre.x - 50)
    }
  })
})

describe('radioHalo', () => {
  it('crece cuando la entidad necesita el segundo anillo', () => {
    // Sin esto, dos entidades vecinas superponen sus halos de atributos.
    expect(radioHalo(9)).toBeGreaterThan(radioHalo(8))
  })
})
