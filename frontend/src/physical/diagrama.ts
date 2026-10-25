import type { ElementLayout } from '../contracts/command'
import type { ColumnaFisica, TablaFisica } from './esquema'

/**
 * GEOMETRIA DEL DIAGRAMA FISICO (ADR-032, etapa 2).
 *
 * Es DERIVADA, como la de los atributos en Chen (ADR-006): no se guarda ni
 * viaja por el bus. Cada tabla que sale de una clase se dibuja donde esta esa
 * clase en el diagrama de Clases, asi el diagrama fisico se reconoce de un
 * vistazo; las derivadas (puente N:M, multivaluados) van entre las tablas a las
 * que apuntan. Para acomodar una tabla se mueve su clase en Clases, y la tabla
 * la sigue: no hay una segunda geometria que mantener sincronizada.
 *
 * Funciones puras: se prueban sin navegador.
 */
export interface Punto { x: number; y: number }
export interface Caja { x: number; y: number; width: number; height: number }

export const ALTO_CABECERA = 30
export const ALTO_FILA = 20
/** Monoespaciada de 12px: el ancho se calcula, no se mide. */
const ANCHO_LETRA = 7.2
const ANCHO_MINIMO = 190
const ANCHO_MAXIMO = 380
/** Aire minimo entre dos tablas. */
const SEPARACION = 36
/** Una tabla es mas alta y mas ancha que su clase: se abre el dibujo en esa medida. */
const ESCALA_X = 1.35
const ESCALA_Y = 1.3

/** Lo que va a la derecha del tipo en cada fila: NOT NULL y UNIQUE, abreviados. */
export function marcasDe(c: ColumnaFisica): string {
  const m = [!c.nullable && !c.pk ? 'NN' : '', c.unique && !c.pk ? 'UQ' : ''].filter(Boolean)
  return m.join(' ')
}

export function medidaDe(t: TablaFisica): { width: number; height: number } {
  const letras = Math.max(t.nombre.length + 2,
    ...t.columnas.map((c) => 4 + c.nombre.length + 2 + c.tipo.length + (marcasDe(c) ? marcasDe(c).length + 1 : 0)))
  const width = Math.min(ANCHO_MAXIMO, Math.max(ANCHO_MINIMO, Math.round(letras * ANCHO_LETRA + 28)))
  return { width, height: ALTO_CABECERA + t.columnas.length * ALTO_FILA + 8 }
}

/** El centro vertical de la fila `i` de una tabla. */
export const centroDeFila = (c: Caja, i: number) => c.y + ALTO_CABECERA + i * ALTO_FILA + ALTO_FILA / 2

const nombreDestino = (c: ColumnaFisica) => c.referencia ?? null

const seSolapan = (a: Caja, b: Caja) =>
  a.x < b.x + b.width + SEPARACION && b.x < a.x + a.width + SEPARACION
  && a.y < b.y + b.height + SEPARACION && b.y < a.y + a.height + SEPARACION

/**
 * Donde va cada tabla.
 *
 * @param layout la geometria del diagrama de Clases (por id de clase)
 */
export function acomodar(tablas: TablaFisica[], layout: Record<string, ElementLayout>): Record<string, Caja> {
  const cajas: Record<string, Caja> = {}
  const sinLugar: TablaFisica[] = []

  // 1. Las que salen de una clase, donde esta la clase.
  for (const t of tablas) {
    const l = t.claseId ? layout[t.claseId] : undefined
    const { width, height } = medidaDe(t)
    if (!l) { sinLugar.push(t); continue }
    cajas[t.nombre] = { x: Math.round(l.x * ESCALA_X), y: Math.round(l.y * ESCALA_Y), width, height }
  }

  // 2. Las derivadas, entre las tablas a las que apuntan. Si no apuntan a
  //    ninguna ubicada (o no hay ninguna), al final, en fila.
  let siguienteLibre = Object.values(cajas).reduce((m, c) => Math.max(m, c.y + c.height), 0) + SEPARACION * 2
  let columnaLibre = 0
  for (const t of sinLugar) {
    const { width, height } = medidaDe(t)
    const destinos = t.columnas.map(nombreDestino).filter((n): n is string => !!n && !!cajas[n])
    if (destinos.length) {
      const cx = destinos.reduce((s, n) => s + cajas[n].x + cajas[n].width / 2, 0) / destinos.length
      const cy = destinos.reduce((s, n) => s + cajas[n].y + cajas[n].height / 2, 0) / destinos.length
      cajas[t.nombre] = { x: Math.round(cx - width / 2), y: Math.round(cy + 40), width, height }
    } else {
      cajas[t.nombre] = { x: columnaLibre, y: siguienteLibre, width, height }
      columnaLibre += width + SEPARACION
      if (columnaLibre > 1400) { columnaLibre = 0; siguienteLibre += height + SEPARACION }
    }
  }

  // 3. Nada encimado: de arriba hacia abajo, cada tabla que choca con una ya
  //    ubicada baja hasta quedar libre. Determinista, para que el diagrama no
  //    "salte" entre dos pedidos del mismo esquema.
  const orden = Object.keys(cajas).sort((a, b) => cajas[a].y - cajas[b].y || cajas[a].x - cajas[b].x || a.localeCompare(b))
  const ubicadas: Caja[] = []
  for (const nombre of orden) {
    const c = cajas[nombre]
    for (let vuelta = 0; vuelta < 200; vuelta++) {
      const choque = ubicadas.filter((u) => seSolapan(c, u))
      if (!choque.length) break
      c.y = Math.max(...choque.map((u) => u.y + u.height + SEPARACION))
    }
    ubicadas.push(c)
  }
  return cajas
}

export type Lado = 'izquierda' | 'derecha' | 'arriba' | 'abajo'

/** Recorrido de una FK: de la fila de la columna a la fila de la PK apuntada. */
export interface Ruta {
  puntos: Punto[]
  /** Por donde sale de la tabla que tiene la FK: ahi va la pata de gallo. */
  ladoOrigen: Lado
  ladoDestino: Lado
}

/** Cuanto sale la linea de la tabla antes de doblar: ahi se dibujan los simbolos. */
const SALIDA = 22

/**
 * Lineas rectas con codos, de costado a costado, como en las herramientas de
 * modelado de datos: la FK sale a la altura de SU columna y llega a la altura
 * de la clave que referencia, asi se lee que columna apunta a que.
 */
export function rutaDeFk(origen: Caja, filaOrigen: number, destino: Caja, filaDestino: number,
                         mismaTabla = false): Ruta {
  const yo = centroDeFila(origen, filaOrigen)
  const yd = centroDeFila(destino, filaDestino)

  if (mismaTabla) {
    // Una FK a su propia tabla (reflexiva): un bucle por la derecha.
    const x = origen.x + origen.width
    return {
      puntos: [{ x, y: yo }, { x: x + SALIDA * 1.6, y: yo }, { x: x + SALIDA * 1.6, y: yd }, { x, y: yd }],
      ladoOrigen: 'derecha', ladoDestino: 'derecha',
    }
  }

  const derechaOrigen = origen.x + origen.width
  const derechaDestino = destino.x + destino.width
  const abajoOrigen = origen.y + origen.height
  const abajoDestino = destino.y + destino.height
  // Una ARRIBA de la otra y sin lugar al costado: de borde a borde por el
  // hueco del medio. Rodearlas por un costado cruzaba a las vecinas (la
  // herencia pone a las hijas debajo de la madre, una al lado de la otra).
  const hayLugarAlCostado = derechaOrigen + SALIDA * 2 <= destino.x || derechaDestino + SALIDA * 2 <= origen.x
  if (!hayLugarAlCostado && (abajoDestino + SALIDA * 2 <= origen.y || abajoOrigen + SALIDA * 2 <= destino.y)) {
    const subiendo = abajoDestino <= origen.y
    const ys = subiendo ? origen.y : abajoOrigen
    const yl = subiendo ? abajoDestino : destino.y
    // Si se superponen en horizontal, una recta en el medio de lo comun;
    // si no, cada punta desde su centro y un codo en el hueco.
    const desde = Math.max(origen.x, destino.x)
    const hasta = Math.min(derechaOrigen, derechaDestino)
    const xs = hasta - desde >= 16 ? Math.round((desde + hasta) / 2) : Math.round(origen.x + origen.width / 2)
    const xl = hasta - desde >= 16 ? xs : Math.round(destino.x + destino.width / 2)
    const medio = Math.round((ys + yl) / 2)
    return {
      puntos: xs === xl
        ? [{ x: xs, y: ys }, { x: xl, y: yl }]
        : [{ x: xs, y: ys }, { x: xs, y: medio }, { x: xl, y: medio }, { x: xl, y: yl }],
      ladoOrigen: subiendo ? 'arriba' : 'abajo',
      ladoDestino: subiendo ? 'abajo' : 'arriba',
    }
  }
  if (derechaOrigen + SALIDA * 2 <= destino.x) {
    // Destino a la derecha, con lugar entre las dos.
    const medio = Math.round((derechaOrigen + destino.x) / 2)
    return { puntos: [{ x: derechaOrigen, y: yo }, { x: medio, y: yo }, { x: medio, y: yd }, { x: destino.x, y: yd }],
      ladoOrigen: 'derecha', ladoDestino: 'izquierda' }
  }
  if (derechaDestino + SALIDA * 2 <= origen.x) {
    // Destino a la izquierda.
    const medio = Math.round((derechaDestino + origen.x) / 2)
    return { puntos: [{ x: origen.x, y: yo }, { x: medio, y: yo }, { x: medio, y: yd }, { x: derechaDestino, y: yd }],
      ladoOrigen: 'izquierda', ladoDestino: 'derecha' }
  }
  // Una encima de la otra: se rodea por el lado que quede mas cerca de las dos.
  const porDerecha = Math.max(derechaOrigen, derechaDestino) + SALIDA
  const porIzquierda = Math.min(origen.x, destino.x) - SALIDA
  const costoDerecha = (porDerecha - derechaOrigen) + (porDerecha - derechaDestino)
  const costoIzquierda = (origen.x - porIzquierda) + (destino.x - porIzquierda)
  if (costoDerecha <= costoIzquierda) {
    return { puntos: [{ x: derechaOrigen, y: yo }, { x: porDerecha, y: yo }, { x: porDerecha, y: yd }, { x: derechaDestino, y: yd }],
      ladoOrigen: 'derecha', ladoDestino: 'derecha' }
  }
  return { puntos: [{ x: origen.x, y: yo }, { x: porIzquierda, y: yo }, { x: porIzquierda, y: yd }, { x: destino.x, y: yd }],
    ladoOrigen: 'izquierda', ladoDestino: 'izquierda' }
}

/** La fila a la que llega una FK: la primera columna de la clave primaria. */
export function filaDeLaClave(t: TablaFisica): number {
  const i = t.columnas.findIndex((c) => c.pk)
  return i < 0 ? 0 : i
}

/** Caja que envuelve todas: para encuadrar y para el area recorrible. */
export function limites(cajas: Caja[]): Caja | null {
  if (!cajas.length) return null
  const minX = Math.min(...cajas.map((c) => c.x))
  const minY = Math.min(...cajas.map((c) => c.y))
  const maxX = Math.max(...cajas.map((c) => c.x + c.width))
  const maxY = Math.max(...cajas.map((c) => c.y + c.height))
  return { x: minX, y: minY, width: maxX - minX, height: maxY - minY }
}
