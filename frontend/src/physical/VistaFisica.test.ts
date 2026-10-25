import { describe, expect, it } from 'vitest'
import { ddlDeTabla } from './VistaFisica'

/**
 * El recorte del DDL por tabla.
 *
 * Vive en el cliente porque el script entero YA viaja: mandar ademas un
 * fragmento por tabla duplicaba el payload (medido: 26 KB para 17 tablas, y la
 * respuesta pasaba de ~60 ms a ~120 ms) para transmitir algo que ya estaba.
 *
 * Lo que estos tests sostienen es que el fragmento sea un RECORTE y no una
 * version aparte: si se generara por su cuenta podria diferir del archivo que
 * se descarga, y nadie lo notaria hasta ejecutarlo.
 */
const SCRIPT = [
  '-- Generado desde el modelo conceptual. No editar a mano.',
  '',
  'CREATE TABLE cliente (',
  '    id uuid NOT NULL,',
  '    nombre varchar(255) NOT NULL,',
  '    PRIMARY KEY (id)',
  ');',
  '',
  'CREATE TABLE pedido (',
  '    id uuid NOT NULL,',
  '    cliente_id uuid NOT NULL,',
  '    PRIMARY KEY (id)',
  ');',
  '',
  'ALTER TABLE pedido ADD CONSTRAINT fk_pedido_cliente_id FOREIGN KEY (cliente_id)' + ' REFERENCES cliente(id) ON DELETE RESTRICT;',
  '',
  'CREATE INDEX idx_pedido_cliente_id ON pedido (cliente_id);',
].join('\n')

describe('ddlDeTabla', () => {
  it('trae el CREATE TABLE entero', () => {
    const ddl = ddlDeTabla(SCRIPT, 'cliente')

    expect(ddl).toContain('CREATE TABLE cliente (')
    expect(ddl).toContain('nombre varchar(255) NOT NULL')
    expect(ddl.trimEnd().endsWith(');')).toBe(true)
  })

  it('no se pasa a la tabla siguiente', () => {
    // El corte es el ');' del final: sin eso, el fragmento se comia el resto
    // del script y copiar una tabla copiaba el esquema entero.
    expect(ddlDeTabla(SCRIPT, 'cliente')).not.toContain('CREATE TABLE pedido')
  })

  it('arrastra las FK y los indices, que viven fuera del CREATE', () => {
    const ddl = ddlDeTabla(SCRIPT, 'pedido')

    expect(ddl).toContain('fk_pedido_cliente_id')
    expect(ddl).toContain('ON DELETE RESTRICT')
    expect(ddl).toContain('CREATE INDEX idx_pedido_cliente_id')
  })

  it('cada linea del fragmento existe tal cual en el script', () => {
    for (const tabla of ['cliente', 'pedido']) {
      for (const linea of ddlDeTabla(SCRIPT, tabla).split('\n')) {
        if (!linea.trim()) continue
        expect(SCRIPT).toContain(linea)
      }
    }
  })

  it('una tabla que no esta da vacio, no basura', () => {
    expect(ddlDeTabla(SCRIPT, 'no_existe')).toBe('')
  })
})
