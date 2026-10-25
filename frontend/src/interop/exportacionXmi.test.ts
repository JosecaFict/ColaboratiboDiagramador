import { describe, expect, it } from 'vitest'
import { nombreDeArchivo, perdidasDelXmi } from './exportacionXmi'

/**
 * Lo que la pantalla de exportar muestra ANTES de guardar sale del propio
 * archivo. Estas pruebas usan la forma exacta en que EscritorXmi lo escribe:
 * con sangria, en su propia linea y con el texto escapado.
 */
const xmi = (perdidas: string) => `<?xml version="1.0" encoding="UTF-8"?>
<xmi:XMI xmlns:xmi="http://www.omg.org/spec/XMI/20131001" xmlns:uml="http://www.omg.org/spec/UML/20161101">
  <xmi:Documentation exporter="ColabModel" exporterVersion="1.0">
    ${perdidas}
  </xmi:Documentation>
  <uml:Model xmi:type="uml:Model" name="Biblioteca"/>
</xmi:XMI>
`

describe('perdidasDelXmi', () => {
  it('lee cada perdida declarada, separada por " | "', () => {
    const p = perdidasDelXmi(xmi('Perdidas declaradas (ADR-018): Persona.ci: unique no viaja en UML 2.5.1'
      + ' | Las decisiones de mapeo OO-Datos (A-1..A-4) no viajan'))
    expect(p).toEqual([
      'Persona.ci: unique no viaja en UML 2.5.1',
      'Las decisiones de mapeo OO-Datos (A-1..A-4) no viajan',
    ])
  })

  it('desescapa lo que el escritor escapo', () => {
    const p = perdidasDelXmi(xmi('Perdidas declaradas (ADR-018): Direccion es «embeddable» &amp; &lt;valor&gt;'))
    expect(p).toEqual(['Direccion es «embeddable» & <valor>'])
  })

  it('un XMI ajeno, sin la documentacion de ColabModel, no inventa perdidas', () => {
    expect(perdidasDelXmi('<xmi:XMI><uml:Model/></xmi:XMI>')).toEqual([])
  })
})

describe('nombreDeArchivo', () => {
  it('usa el nombre del diagrama', () => {
    expect(nombreDeArchivo('Biblioteca')).toBe('Biblioteca.xmi')
  })

  it('saca lo que Windows no acepta en un nombre de archivo', () => {
    expect(nombreDeArchivo('Biblioteca: v2 / final?')).toBe('Biblioteca v2 final.xmi')
  })

  it('para Enterprise Architect, .xml: es lo que su dialogo de importar filtra de entrada', () => {
    expect(nombreDeArchivo('Biblioteca', 'ea')).toBe('Biblioteca.xml')
  })

  it('sin nombre usable, uno generico', () => {
    expect(nombreDeArchivo('  :*? ')).toBe('diagrama.xmi')
  })
})
