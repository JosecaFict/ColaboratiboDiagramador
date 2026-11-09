import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
const css = readFileSync(new URL('./paleta.css', import.meta.url), 'utf8')
const bloques = [...css.matchAll(/(?:^|\n)(:root[^\{]*)\{([^}]+)\}/g)].map((m) =>
  Object.fromEntries([...m[2].matchAll(/--([\w-]+):\s*(#[\da-f]{6});/gi)].map((c) => [c[1],c[2]])))
const luminancia = (hex) => {
  const c = [1,3,5].map((i) => parseInt(hex.slice(i,i+2),16)/255).map((n) => n <= .04045 ? n/12.92 : ((n+.055)/1.055)**2.4)
  return c[0]*.2126+c[1]*.7152+c[2]*.0722
}
const contraste = (a,b) => {
  const x = luminancia(a), y = luminancia(b)
  return (Math.max(x,y)+.05)/(Math.min(x,y)+.05)
}
describe('contraste de los tokens de texto y acciones', () => {
  for (const [i, tema] of ['claro', 'oscuro', 'grafito'].entries()) it(`tema ${tema} cumple AA en superficies, estados y botones`, () => {
    expect(bloques).toHaveLength(3)
    const p = bloques[i]
    for (const fondo of ['panel','fondo','suave','elevado']) for (const texto of ['texto','secundario','tenue','acento','lila']) {
      expect(contraste(p[texto],p[fondo]),`${texto} sobre ${fondo}`).toBeGreaterThanOrEqual(4.5)
    }
    for (const estado of ['verde','rojo','ambar','lila','acento']) {
      expect(contraste(p[estado],p[`${estado}-fondo`]),estado).toBeGreaterThanOrEqual(4.5)
    }
    for (const accion of ['accion','accion-hover','peligro','lila-accion','inverso']) {
      expect(contraste(p['sobre-acento'],p[accion]),accion).toBeGreaterThanOrEqual(4.5)
    }
  })
})


it('todos los temas declaran los mismos tokens y Grafito usa acciones en píldora', () => {
  const declaraciones = [...css.matchAll(/(?:^|\n)(:root[^\{]*)\{([^}]+)\}/g)].map((m) =>
    Object.fromEntries([...m[2].matchAll(/--([\w-]+):\s*([^;]+);/g)].map((c) => [c[1], c[2]])))
  expect(declaraciones).toHaveLength(3)
  for (const p of declaraciones) expect(Object.keys(p).sort()).toEqual(Object.keys(declaraciones[0]).sort())
  expect(declaraciones.map((p) => p['radio-accion'])).toEqual(['6px', '6px', '999px'])
  for (const fondo of ['marca-inicio', 'marca-fin']) {
    expect(contraste(declaraciones[2]['marca-texto'], declaraciones[2][fondo])).toBeGreaterThanOrEqual(4.5)
  }
})
