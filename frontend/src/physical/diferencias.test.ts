import { describe, expect, it } from 'vitest'
import { diferencias, resumir } from './diferencias'

describe('diferencias entre el DDL confirmado y el de hoy', () => {
  const antes = [
    'CREATE TABLE libro (',
    '    id uuid NOT NULL,',
    '    titulo varchar(255) NOT NULL,',
    '    PRIMARY KEY (id)',
    ');',
  ].join('\n')

  it('marca lo que cambio y deja igual lo demas', () => {
    const despues = antes.replace('titulo varchar(255)', 'titulo varchar(120)')

    const d = diferencias(antes, despues)

    expect(d.filter((l) => l.tipo === 'quitada').map((l) => l.texto)).toEqual(['    titulo varchar(255) NOT NULL,'])
    expect(d.filter((l) => l.tipo === 'agregada').map((l) => l.texto)).toEqual(['    titulo varchar(120) NOT NULL,'])
    expect(d.filter((l) => l.tipo === 'igual')).toHaveLength(4)
  })

  it('una columna nueva es una sola linea agregada', () => {
    const despues = antes.replace('    PRIMARY KEY', '    isbn varchar(13) UNIQUE,\n    PRIMARY KEY')

    const d = diferencias(antes, despues)

    expect(d.filter((l) => l.tipo !== 'igual')).toEqual([{ tipo: 'agregada', texto: '    isbn varchar(13) UNIQUE,' }])
  })

  it('sin cambios no hay nada que mostrar', () => {
    expect(resumir(diferencias(antes, antes))).toEqual([{ tipo: 'salto', cantidad: 5 }])
  })

  it('resume lo que no cambio lejos de un cambio', () => {
    const largo = Array.from({ length: 30 }, (_, i) => 'linea ' + i).join('\n')
    const cambiado = largo.replace('linea 15', 'linea quince')

    const tramos = resumir(diferencias(largo, cambiado), 2)

    expect(tramos[0]).toEqual({ tipo: 'salto', cantidad: 13 })
    expect(tramos.filter((t) => t.tipo !== 'salto' && t.tipo !== 'igual')).toHaveLength(2)
    expect(tramos[tramos.length - 1]).toEqual({ tipo: 'salto', cantidad: 12 })
  })
})
