import type { LecturaDeImagen } from '../contracts/imagenes'
import { textoDeOpciones } from './imagenes'
import { textoDeDuda } from './lecturaDeImagen'
import type { CorreccionesDeLectura } from './lecturaDeImagen'

interface Props {
  lectura: LecturaDeImagen
  /** Solo la lectura vigente se corrige; las anteriores se muestran como estan. */
  editable: boolean
  correcciones: CorreccionesDeLectura
  onCorregir: (cambio: Partial<CorreccionesDeLectura>) => void
}

/**
 * Las dudas del turno IMAGEN, a la vista (contrato §5.4). Son informativas: corregir una (escribir
 * el nombre de una clase) cambia lo que se aplica, pero NUNCA bloquea Aplicar.
 */
export function LecturaDeImagenTarjeta({ lectura, editable, correcciones, onCorregir }: Props) {
  const opciones = textoDeOpciones(lectura.opciones)
  const sinNombre = lectura.deteccion.clases.filter((c) => !c.nombre?.trim())
  return <div className="lectura-imagen">
    {opciones && <small>Imagen {opciones}.</small>}
    {!!lectura.enriquecedoresFallidos.length &&
      <small className="lectura-aviso">Leída solo con la forma de las cajas: falló {lectura.enriquecedoresFallidos.join(', ')}.</small>}
    {!!lectura.dudas.length && <details open>
      <summary>{lectura.dudas.length + lectura.dudasOmitidas} duda{lectura.dudas.length + lectura.dudasOmitidas === 1 ? '' : 's'} de lectura</summary>
      <ul>{lectura.dudas.map((d, i) => <li key={`${d.tipo}-${d.elementoRef ?? ''}-${d.recorteRef ?? ''}-${i}`}>
        {textoDeDuda(d)} <em>{Math.round(d.confianza * 100)}%</em>
      </li>)}</ul>
      {lectura.dudasOmitidas > 0 && <small>…y {lectura.dudasOmitidas} más.</small>}
    </details>}
    {editable && !!sinNombre.length && <div className="lectura-nombres">
      <small>Escribí el nombre de las clases que quieras agregar (las demás no entran):</small>
      {sinNombre.map((c) => <label key={c.ref}>{c.ref}
        <input value={correcciones.nombres[c.ref] ?? ''} placeholder="Nombre de la clase"
          onChange={(e) => onCorregir({ nombres: { [c.ref]: e.target.value } })} /></label>)}
    </div>}
  </div>
}
