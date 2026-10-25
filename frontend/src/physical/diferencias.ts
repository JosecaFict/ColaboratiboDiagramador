/**
 * Que cambio en el DDL desde que se confirmo (ADR-032).
 *
 * Confirmar sirve de algo solo si, cuando el esquema cambia despues, se puede
 * ver QUE cambio antes de volver a confirmar. Sin esto, "cambio desde la
 * confirmacion" obliga a releer el script entero.
 */
export type LineaDiff = { tipo: 'igual' | 'agregada' | 'quitada'; texto: string }
export type Tramo = LineaDiff | { tipo: 'salto'; cantidad: number }

/**
 * Diferencias por linea, con la subsecuencia comun mas larga. Un script de 20
 * tablas son unos cientos de lineas: la tabla O(n·m) entra de sobra.
 */
export function diferencias(antes: string, despues: string): LineaDiff[] {
  const a = antes.split('\n')
  const b = despues.split('\n')
  const n = a.length
  const m = b.length
  const lcs: number[][] = Array.from({ length: n + 1 }, () => new Array<number>(m + 1).fill(0))
  for (let i = n - 1; i >= 0; i--) {
    for (let j = m - 1; j >= 0; j--) {
      lcs[i][j] = a[i] === b[j] ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1])
    }
  }
  const salida: LineaDiff[] = []
  let i = 0
  let j = 0
  while (i < n && j < m) {
    if (a[i] === b[j]) {
      salida.push({ tipo: 'igual', texto: a[i] })
      i++
      j++
    } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
      salida.push({ tipo: 'quitada', texto: a[i++] })
    } else {
      salida.push({ tipo: 'agregada', texto: b[j++] })
    }
  }
  while (i < n) salida.push({ tipo: 'quitada', texto: a[i++] })
  while (j < m) salida.push({ tipo: 'agregada', texto: b[j++] })
  return salida
}

/** Solo los cambios, con `contexto` lineas alrededor; lo igual de en medio se resume. */
export function resumir(lineas: LineaDiff[], contexto = 2): Tramo[] {
  const cerca = lineas.map((l, i) => l.tipo !== 'igual'
    || lineas.slice(Math.max(0, i - contexto), i + contexto + 1).some((x) => x.tipo !== 'igual'))
  const salida: Tramo[] = []
  let saltadas = 0
  lineas.forEach((l, i) => {
    if (cerca[i]) {
      if (saltadas) salida.push({ tipo: 'salto', cantidad: saltadas })
      saltadas = 0
      salida.push(l)
    } else {
      saltadas++
    }
  })
  if (saltadas) salida.push({ tipo: 'salto', cantidad: saltadas })
  return salida
}
