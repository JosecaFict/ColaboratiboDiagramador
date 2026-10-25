import { describe, expect, it } from 'vitest'
import { aplicarOperacion, estadoVacio } from '../canvas/applyOperation'
import type { AppliedOperation } from '../contracts/command'
import { MAX_POR_LOTE, traducir, type DeteccionConfirmada } from './traductor'

const DIAGRAMA = '00000000-0000-0000-0000-0000000000aa'

/** Ids predecibles: un test que compara UUID al azar no compara nada. */
function contador() {
  let n = 0
  return () => `id-${++n}`
}

const caja = (x: number, y: number) => ({ x, y, ancho: 170, alto: 90 })

const foto = (): DeteccionConfirmada => ({
  clases: [
    { ref: 'c1', nombre: 'Libro', lienzo: caja(80, 80),
      atributos: [{ nombre: 'titulo', dataType: 'string', required: true }] },
    { ref: 'c2', nombre: 'Socio', lienzo: caja(420, 80), atributos: [] },
  ],
  relaciones: [
    { ref: 'r1', origenRef: 'c1', destinoRef: 'c2', kind: 'ASSOCIATION' },
  ],
})

const comandosDe = (t: ReturnType<typeof traducir>) =>
  (t.comando!.payload as { comandos: { type: string; payload: any }[] }).comandos

describe('traductor de BATCH', () => {
  it('produce UN solo comando, no una tanda suelta', () => {
    const t = traducir(foto(), DIAGRAMA, 41, contador())

    // Un lote a medio aplicar es peor que ninguno: por eso viaja como una
    // sola operacion y el servidor le da un solo seq.
    expect(t.comando!.type).toBe('BATCH')
    expect(t.comando!.origin).toBe('IMAGE')
    expect(t.comando!.baseSeq).toBe(41)
    expect(comandosDe(t)).toHaveLength(4)
  })

  it('ordena clases, despues atributos, despues relaciones', () => {
    // CREATE_RELATION valida que las dos clases existan: una relacion emitida
    // antes que su clase se rechaza y se cae el lote entero.
    const tipos = comandosDe(traducir(foto(), DIAGRAMA, 0, contador())).map((c) => c.type)

    expect(tipos).toEqual(['CREATE_CLASS', 'ADD_ATTRIBUTE', 'CREATE_CLASS', 'CREATE_RELATION'])
    expect(tipos.indexOf('CREATE_RELATION')).toBe(tipos.length - 1)
  })

  it('resuelve los ref a los UUID que acaba de acunar', () => {
    const t = traducir(foto(), DIAGRAMA, 0, contador())
    const comandos = comandosDe(t)

    const relacion = comandos.find((c) => c.type === 'CREATE_RELATION')!
    expect(relacion.payload.sourceClassId).toBe(t.idsPorRef.c1)
    expect(relacion.payload.targetClassId).toBe(t.idsPorRef.c2)
    // Ningun ref de la propuesta puede sobrevivir al otro lado.
    expect(JSON.stringify(comandos)).not.toContain('"c1"')
  })

  it('lleva la disposicion que tenia en la imagen', () => {
    const comandos = comandosDe(traducir(foto(), DIAGRAMA, 0, contador()))

    const [primera, , segunda] = comandos
    expect(primera.payload).toMatchObject({ x: 80, y: 80, width: 170, height: 90 })
    expect(segunda.payload.x).toBe(420)
  })

  it('descarta una clase sin nombre en vez de inventarle uno', () => {
    const sinNombre: DeteccionConfirmada = {
      clases: [{ ref: 'c1', nombre: '   ', lienzo: caja(0, 0), atributos: [] }],
      relaciones: [],
    }

    const t = traducir(sinNombre, DIAGRAMA, 0, contador())

    expect(t.comando).toBeNull()
    expect(t.descartes.join(' ')).toContain('no tiene nombre')
  })

  it('descarta la relacion cuyo extremo el usuario no confirmo', () => {
    const conHuerfana: DeteccionConfirmada = {
      ...foto(),
      relaciones: [
        { ref: 'r1', origenRef: 'c1', destinoRef: 'c9', kind: 'ASSOCIATION' },
      ],
    }

    const t = traducir(conHuerfana, DIAGRAMA, 0, contador())

    // No se inventa la clase faltante para "salvar" la relacion: el usuario
    // decidio que esa caja no iba.
    expect(comandosDe(t).some((c) => c.type === 'CREATE_RELATION')).toBe(false)
    expect(t.descartes.join(' ')).toContain('c9 no entro al lote')
  })

  it('atrapa el nombre repetido antes de que tumbe el lote entero', () => {
    const repetida: DeteccionConfirmada = {
      clases: [
        { ref: 'c1', nombre: 'Libro', lienzo: caja(0, 0), atributos: [] },
        { ref: 'c2', nombre: 'libro', lienzo: caja(200, 0), atributos: [] },
      ],
      relaciones: [],
    }

    const t = traducir(repetida, DIAGRAMA, 0, contador())

    // El backend rechaza el duplicado y con el se cae TODO el lote. Atajarlo
    // aca convierte "no se aplico nada" en "esta clase no entro".
    expect(comandosDe(t)).toHaveLength(1)
    expect(t.descartes.join(' ')).toContain('ya hay otra clase llamada')
  })

  it('no manda un lote mas grande que el tope del backend', () => {
    const enorme: DeteccionConfirmada = {
      clases: Array.from({ length: MAX_POR_LOTE + 1 }, (_, i) => ({
        ref: `c${i}`, nombre: `Clase${i}`, lienzo: caja(0, 0), atributos: [],
      })),
      relaciones: [],
    }

    const t = traducir(enorme, DIAGRAMA, 0, contador())

    expect(t.comando).toBeNull()
    expect(t.descartes.join(' ')).toContain('por partes')
  })

  it('lo traducido, aplicado en el cliente, reconstruye el diagrama', () => {
    // Cierra el circulo: lo que produce el traductor tiene que poder plegarlo
    // el espejo de CommandApplier. Si los dos no coinciden, el canvas queda
    // mostrando algo distinto de lo que el servidor guardo.
    const t = traducir(foto(), DIAGRAMA, 41, contador())
    const op = { ...t.comando!, opId: 'op', seq: 42, userId: null, ts: '' } as unknown as AppliedOperation

    const estado = aplicarOperacion(estadoVacio(DIAGRAMA), op)

    expect(estado.classes.map((c) => c.name)).toEqual(['Libro', 'Socio'])
    expect(estado.classes[0].attributes.map((a) => a.name)).toEqual(['titulo'])
    expect(estado.relationships).toHaveLength(1)
    expect(estado.layout[estado.classes[1].id].x).toBe(420)
  })
})
