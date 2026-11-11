import { useState } from 'react'
import type { ImagenMeta, RecorteDeImagen } from '../contracts/imagenes'
import { formatoDeBytes, imagenesVivas, puedeBorrarImagen, validarReproceso, type EstadoDeGaleria } from './imagenes'
import { MiniaturaDeImagen, type PedirBytes } from './MiniaturaDeImagen'

interface Props {
  galeria: EstadoDeGaleria
  bytes: PedirBytes
  usuarioId: string
  /** OWNER o EDITOR: puede usar y reprocesar. Un VIEWER solo mira. */
  puedeEditar: boolean
  esPropietario: boolean
  ocupado: boolean
  onUsar: (imagen: ImagenMeta) => void
  onReprocesar: (imagen: ImagenMeta, opciones: { giroGrados?: number; recorte?: RecorteDeImagen }) => Promise<boolean>
  onEliminar: (imagen: ImagenMeta) => void
  onAmpliar: (imagen: ImagenMeta) => void
}

const cuando = (iso: string) => {
  const t = new Date(iso)
  return Number.isNaN(t.getTime()) ? iso : t.toLocaleString('es', { dateStyle: 'short', timeStyle: 'short' })
}

/** "usada 3 de 100 · 1.8 MB de 200 MB": cuanto cupo queda antes del 413. */
export function textoDeUso(uso: EstadoDeGaleria['uso']): string {
  if (!uso) return ''
  return `${uso.cantidad} de ${uso.maxCantidad} imágenes · ${formatoDeBytes(uso.bytes)} de ${formatoDeBytes(uso.maxBytes)}`
}

/** Galeria del diagrama (contrato §2.2): quien subio cada imagen y cuando, uso frente al tope, y las acciones. */
export function GaleriaDeImagenes({ galeria, bytes, usuarioId, puedeEditar, esPropietario, ocupado, onUsar, onReprocesar, onEliminar, onAmpliar }: Props) {
  const vivas = imagenesVivas(galeria.imagenes)
  const [reprocesando, setReprocesando] = useState<string | null>(null)
  return <div className="img-galeria">
    <p className="img-uso" aria-label="Uso de imágenes">{textoDeUso(galeria.uso) || 'Imágenes del diagrama'}</p>
    {!vivas.length && <p className="img-vacia">Todavía no hay imágenes en este diagrama.</p>}
    <ul>
      {vivas.map((img) => <li key={img.id}>
        <MiniaturaDeImagen imagen={img} bytes={bytes} onAmpliar={onAmpliar} />
        <div className="img-datos">
          <strong>{img.subidaPorNombre}</strong>
          <small>{cuando(img.creadaEn)} · {img.ancho}×{img.alto} · {formatoDeBytes(img.bytes)}</small>
          <div className="img-acciones">
            {puedeEditar && <button type="button" onClick={() => onUsar(img)} disabled={ocupado}>Usar en el chat</button>}
            {puedeEditar && <button type="button" onClick={() => setReprocesando(reprocesando === img.id ? null : img.id)} disabled={ocupado}>Reprocesar</button>}
            {puedeBorrarImagen(img, { userId: usuarioId, puedeEditar, esPropietario }) &&
              <button type="button" className="peligro" onClick={() => onEliminar(img)} disabled={ocupado}>Borrar</button>}
          </div>
          {reprocesando === img.id && <FormularioDeReproceso imagen={img} ocupado={ocupado}
            onLeer={async (o) => { if (await onReprocesar(img, o)) setReprocesando(null) }} />}
        </div>
      </li>)}
    </ul>
  </div>
}

/** Giro y recorte (en pixeles de la imagen guardada). El servidor repite las mismas reglas. */
function FormularioDeReproceso({ imagen, ocupado, onLeer }: {
  imagen: ImagenMeta
  ocupado: boolean
  onLeer: (o: { giroGrados?: number; recorte?: RecorteDeImagen }) => Promise<void>
}) {
  const [giro, setGiro] = useState('0')
  const [recortar, setRecortar] = useState(false)
  const [caja, setCaja] = useState({ x: '0', y: '0', ancho: String(imagen.ancho), alto: String(imagen.alto) })
  const giroGrados = giro.trim() === '' ? undefined : Number(giro)
  const recorte = recortar ? { x: Number(caja.x), y: Number(caja.y), ancho: Number(caja.ancho), alto: Number(caja.alto) } : undefined
  const problema = validarReproceso(imagen, giroGrados === 0 ? undefined : giroGrados, recorte)
  const campo = (k: keyof typeof caja, etiqueta: string) => <label>{etiqueta}
    <input type="number" value={caja[k]} onChange={(e) => setCaja({ ...caja, [k]: e.target.value })} /></label>
  return <form className="img-reproceso" onSubmit={(e) => {
    e.preventDefault()
    if (!problema) void onLeer({ giroGrados: giroGrados || undefined, recorte })
  }}>
    <label>Giro
      <select value={['0', '90', '180', '-90'].includes(giro) ? giro : 'otro'}
        onChange={(e) => setGiro(e.target.value === 'otro' ? '5' : e.target.value)}>
        <option value="0">sin girar</option><option value="90">90° a la derecha</option>
        <option value="-90">90° a la izquierda</option><option value="180">180°</option><option value="otro">fino (hasta 10°)</option>
      </select></label>
    {!['0', '90', '180', '-90'].includes(giro) &&
      <label>Grados<input type="number" min={-10} max={10} value={giro} onChange={(e) => setGiro(e.target.value)} /></label>}
    <label className="img-check"><input type="checkbox" checked={recortar} onChange={(e) => setRecortar(e.target.checked)} /> Recortar</label>
    {recortar && <div className="img-caja">{campo('x', 'x')}{campo('y', 'y')}{campo('ancho', 'ancho')}{campo('alto', 'alto')}</div>}
    {problema && <small className="img-problema" role="alert">{problema}</small>}
    <button type="submit" disabled={ocupado || !!problema}>Leer de nuevo</button>
  </form>
}
