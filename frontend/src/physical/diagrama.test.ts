import { describe, expect, it } from 'vitest'
import type { ElementLayout } from '../contracts/command'
import { acomodar, centroDeFila, filaDeLaClave, medidaDe, rutaDeFk, type Caja } from './diagrama'
import type { ColumnaFisica, TablaFisica } from './esquema'

/**
 * La geometria del diagrama fisico es derivada (ADR-006, ADR-032): sale de la
 * del diagrama de Clases. Estos tests fijan que nunca se encimen tablas, que
 * las derivadas queden junto a lo que referencian y que una FK salga de SU
 * fila y llegue a la clave que apunta.
 */
const col = (nombre: string, extra: Partial<ColumnaFisica> = {}): ColumnaFisica =>
  ({ nombre, tipo: 'uuid', pk: false, nullable: false, unique: false, ...extra })

const tabla = (nombre: string, claseId: string | null, columnas: ColumnaFisica[]): TablaFisica =>
  ({ nombre, origen: nombre, columnas, clavePrimaria: [], indices: [], referidaPor: [], claseId })

const lugar = (elementId: string, x: number, y: number): ElementLayout =>
  ({ elementId, x, y, width: 170, height: 60 })

const encimadas = (a: Caja, b: Caja) =>
  a.x < b.x + b.width && b.x < a.x + a.width && a.y < b.y + b.height && b.y < a.y + a.height

describe('acomodar', () => {
  const persona = tabla('persona', 'p', [col('id', { pk: true }), col('nombre', { tipo: 'varchar(120)' })])
  const libro = tabla('libro', 'l', [col('id', { pk: true }), col('titulo', { tipo: 'varchar(200)' })])
  const prestamo = tabla('prestamo', 'f', [col('id', { pk: true }),
    col('persona_id', { referencia: 'persona' }), col('libro_id', { referencia: 'libro' })])

  it('cada tabla va donde esta su clase en el diagrama de Clases, abierto en proporcion', () => {
    const cajas = acomodar([persona, libro], { p: lugar('p', 100, 100), l: lugar('l', 600, 100) })

    expect(cajas.persona.x).toBeLessThan(cajas.libro.x)
    expect(cajas.persona.y).toBe(cajas.libro.y)
  })

  it('nunca encima dos tablas, aunque sus clases esten una sobre otra', () => {
    const cajas = acomodar([persona, libro, prestamo],
      { p: lugar('p', 100, 100), l: lugar('l', 110, 105), f: lugar('f', 105, 102) })

    const lista = Object.values(cajas)
    for (let i = 0; i < lista.length; i++) {
      for (let j = i + 1; j < lista.length; j++) expect(encimadas(lista[i], lista[j])).toBe(false)
    }
  })

  it('una tabla puente va entre las dos tablas que une', () => {
    const puente = tabla('persona_libro', null, [col('persona_id', { pk: true, referencia: 'persona' }),
      col('libro_id', { pk: true, referencia: 'libro' })])

    const cajas = acomodar([persona, libro, puente], { p: lugar('p', 0, 0), l: lugar('l', 800, 0) })

    const centro = cajas.persona_libro.x + cajas.persona_libro.width / 2
    expect(centro).toBeGreaterThan(cajas.persona.x + cajas.persona.width)
    expect(centro).toBeLessThan(cajas.libro.x)
  })

  it('es determinista: el mismo esquema da el mismo dibujo', () => {
    const layout = { p: lugar('p', 100, 100), l: lugar('l', 110, 105), f: lugar('f', 105, 102) }
    expect(acomodar([persona, libro, prestamo], layout)).toEqual(acomodar([persona, libro, prestamo], layout))
  })

  it('la caja crece con las columnas y con el nombre mas largo', () => {
    const corta = medidaDe(tabla('a', 'a', [col('id')]))
    const larga = medidaDe(tabla('a', 'a', [col('id'), col('descripcion_extensa_del_material', { tipo: 'varchar(255)' })]))
    expect(larga.height).toBeGreaterThan(corta.height)
    expect(larga.width).toBeGreaterThan(corta.width)
  })
})

describe('rutaDeFk', () => {
  const a: Caja = { x: 0, y: 0, width: 200, height: 110 }
  const b: Caja = { x: 400, y: 50, width: 200, height: 90 }

  it('sale a la altura de SU columna y llega a la de la clave apuntada', () => {
    const r = rutaDeFk(a, 2, b, 0)

    expect(r.puntos[0]).toEqual({ x: 200, y: centroDeFila(a, 2) })
    expect(r.puntos[r.puntos.length - 1]).toEqual({ x: 400, y: centroDeFila(b, 0) })
    expect(r.ladoOrigen).toBe('derecha')
    expect(r.ladoDestino).toBe('izquierda')
  })

  it('si la tabla apuntada esta a la izquierda, sale por la izquierda', () => {
    const r = rutaDeFk(b, 1, a, 0)
    expect(r.ladoOrigen).toBe('izquierda')
    expect(r.ladoDestino).toBe('derecha')
  })

  it('una debajo de la otra: sale por arriba y entra por abajo, por el hueco del medio', () => {
    const abajo: Caja = { x: 20, y: 300, width: 200, height: 90 }
    const r = rutaDeFk(abajo, 1, a, 0)

    expect(r.ladoOrigen).toBe('arriba')
    expect(r.ladoDestino).toBe('abajo')
    expect(r.puntos[0].y).toBe(300)
    expect(r.puntos[r.puntos.length - 1].y).toBe(110)
  })

  it('herencia: dos hijas lado a lado bajo la madre no se cruzan con la vecina', () => {
    // docente y estudiante debajo de persona, como las acomoda el diagrama de Clases.
    const persona: Caja = { x: 160, y: 100, width: 220, height: 100 }
    const docente: Caja = { x: 50, y: 400, width: 240, height: 80 }
    const estudiante: Caja = { x: 330, y: 400, width: 240, height: 80 }
    const adentro = (p: { x: number; y: number }, c: Caja) =>
      p.x > c.x && p.x < c.x + c.width && p.y > c.y && p.y < c.y + c.height

    for (const [hija, vecina] of [[docente, estudiante], [estudiante, docente]]) {
      const r = rutaDeFk(hija, 0, persona, 0)
      // Muestreando cada tramo: ningun punto de la linea cae dentro de la vecina.
      for (let i = 1; i < r.puntos.length; i++) {
        for (let t = 0; t <= 1; t += 0.05) {
          const p = { x: r.puntos[i - 1].x + (r.puntos[i].x - r.puntos[i - 1].x) * t,
            y: r.puntos[i - 1].y + (r.puntos[i].y - r.puntos[i - 1].y) * t }
          expect(adentro(p, vecina)).toBe(false)
        }
      }
    }
  })

  it('una FK a su propia tabla es un bucle por la derecha', () => {
    const r = rutaDeFk(a, 3, a, 0, true)
    expect(r.ladoOrigen).toBe('derecha')
    expect(Math.max(...r.puntos.map((p) => p.x))).toBeGreaterThan(a.x + a.width)
  })

  it('llega a la primera columna de la clave primaria', () => {
    expect(filaDeLaClave(tabla('t', 't', [col('persona_id', { pk: true }), col('x')]))).toBe(0)
    expect(filaDeLaClave(tabla('t', 't', [col('x'), col('id', { pk: true })]))).toBe(1)
  })
})
