import { useEffect, useState } from 'react'
import type { ImagenMeta } from '../contracts/imagenes'

/**
 * Miniatura de una imagen del chat. Los bytes se piden con fetch + Authorization (la funcion
 * `bytes` la arma el hook con el token) y se muestran como `blob:`; la URL se libera al desmontar.
 * Ninguna URL lleva token. Una imagen eliminada muestra "imagen eliminada" y NO pide bytes (daria 404).
 */

export type PedirBytes = (id: string, miniatura: boolean) => Promise<Blob>

export function useUrlDeBlob(imagen: ImagenMeta, miniatura: boolean, bytes: PedirBytes) {
  const [url, setUrl] = useState<string | null>(null)
  const [fallo, setFallo] = useState(false)
  useEffect(() => {
    if (imagen.eliminada) { setUrl(null); setFallo(false); return }
    let vivo = true
    let creada: string | null = null
    setFallo(false)
    bytes(imagen.id, miniatura)
      .then((b) => {
        if (!vivo) return
        creada = URL.createObjectURL(b)
        setUrl(creada)
      })
      .catch(() => { if (vivo) setFallo(true) })
    return () => { vivo = false; if (creada) URL.revokeObjectURL(creada) }
    // `bytes` cambia de identidad en cada render del hook: el efecto depende solo de la imagen.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [imagen.id, imagen.eliminada, miniatura])
  return { url, fallo }
}

export function MiniaturaDeImagen({ imagen, bytes, onAmpliar }: {
  imagen: ImagenMeta
  bytes: PedirBytes
  onAmpliar?: (imagen: ImagenMeta) => void
}) {
  const { url, fallo } = useUrlDeBlob(imagen, true, bytes)
  if (imagen.eliminada) return <span className="img-eliminada">imagen eliminada</span>
  if (fallo) return <span className="img-eliminada">no se pudo cargar la imagen</span>
  if (!url) return <span className="img-cargando" aria-label="Cargando la imagen" />
  return <button type="button" className="img-miniatura" onClick={() => onAmpliar?.(imagen)}
    title="Ver más grande" aria-label="Ver la imagen más grande">
    <img src={url} alt="Imagen adjunta" />
  </button>
}

/** La imagen entera, sobre el chat. Esc o un clic afuera la cierran. */
export function VisorDeImagen({ imagen, bytes, onCerrar }: { imagen: ImagenMeta; bytes: PedirBytes; onCerrar: () => void }) {
  const { url, fallo } = useUrlDeBlob(imagen, false, bytes)
  useEffect(() => {
    const alTeclear = (e: KeyboardEvent) => { if (e.key === 'Escape') onCerrar() }
    window.addEventListener('keydown', alTeclear)
    return () => window.removeEventListener('keydown', alTeclear)
  }, [onCerrar])
  return <div className="img-visor" role="dialog" aria-label="Imagen ampliada" onClick={onCerrar}>
    {imagen.eliminada || fallo
      ? <span className="img-eliminada">{imagen.eliminada ? 'imagen eliminada' : 'no se pudo cargar la imagen'}</span>
      : url ? <img src={url} alt="Imagen ampliada" onClick={(e) => e.stopPropagation()} /> : <span className="img-cargando" />}
    <button type="button" className="img-visor-cerrar" onClick={onCerrar} aria-label="Cerrar">✕</button>
  </div>
}
