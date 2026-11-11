import { renderToStaticMarkup } from 'react-dom/server'
import { describe, expect, it, vi } from 'vitest'
import type { DeteccionPropuesta } from '../contracts/vision'
import type { GaleriaDeImagenes, ImagenMeta, LecturaDeImagen } from '../contracts/imagenes'
import { ChatFlotante } from './ChatFlotante'
import { GaleriaDeImagenes as Galeria } from './GaleriaDeImagenes'
import { clasificarAviso, fusionarEntrada, marcarImagenEliminada, origenDelLote, tieneCambiosParaAplicar, ultimaConCambios } from './historial'
import {
  agregarImagen, archivosDeImagen, borrarImagen, marcarEliminadaEnGaleria, pedirBytesDeImagen, puedeBorrarImagen,
  rutaDeImagen, subirImagen, textoDeOpciones, validarArchivoDeImagen, validarReproceso,
} from './imagenes'
import { comandosDeLectura, confirmarLectura, resumenDeLectura } from './lecturaDeImagen'
import { LecturaDeImagenTarjeta } from './LecturaDeImagenTarjeta'
import { MiniaturaDeImagen, VisorDeImagen } from './MiniaturaDeImagen'
import type { AsistenteIA } from './useAsistenteIA'
import { textoDeRespuesta } from './useAsistenteIA'
import type { EntradaHistorialIA, PropuestaIA } from './tipos'

const ANA = 'c2f1b6e0-8a7d-4f0e-9a3b-0d5e6f7a8b9c'
const BETO = 'b1b1b1b1-8a7d-4f0e-9a3b-0d5e6f7a8b9c'

const meta = (parcial: Partial<ImagenMeta> = {}): ImagenMeta => ({
  id: '7b0c6a52-3f0e-4a56-9d6a-2f6f0c1c9a11', subidaPorId: ANA, subidaPorNombre: 'Ana Pérez', tipo: 'image/jpeg',
  ancho: 1600, alto: 1200, bytes: 412345, creadaEn: '2026-11-11T15:04:05.123-04:00', eliminada: false, ...parcial,
})

const caja = (x: number, y: number) => ({ x, y, ancho: 170, alto: 90 })
const texto = (ref: string, rol: 'ATRIBUTO' | 'CARDINALIDAD', t?: string) =>
  ({ ref, rol, caja: caja(0, 0), confianza: t ? 0.8 : 0, ...(t ? { texto: t } : {}) })
const simbolo = (ref: string) => ({ ref, extremo: 'ORIGEN' as const, caja: caja(0, 0), confianza: 0.8 })

const deteccion = (): DeteccionPropuesta => ({
  clases: [
    { ref: 'c1', caja: caja(0, 0), lienzo: caja(80, 80), nombre: 'Libro', atributos: [texto('t1', 'ATRIBUTO', 'titulo')], metodos: [], compartimentos: 3, confianza: 0.9 },
    { ref: 'c2', caja: caja(0, 0), lienzo: caja(420, 80), atributos: [], metodos: [], compartimentos: 1, confianza: 0.9 },
  ],
  relaciones: [{
    ref: 'r1', origenRef: 'c1', destinoRef: 'c2', kind: 'ASSOCIATION', simboloOrigen: simbolo('s1'), simboloDestino: simbolo('s2'),
    cardinalidadOrigen: texto('k1', 'CARDINALIDAD', '1'), cardinalidadDestino: texto('k2', 'CARDINALIDAD'), confianza: 0.9,
  }],
  avisos: [],
  metricas: { anchoOriginal: 1600, altoOriginal: 1200, anchoTrabajo: 1600, altoTrabajo: 1200, escala: 1, umbral: 128,
    binarizacion: 'global', segmentos: 2, rectasSueltas: 0, milis: 10, pareceFoto: false, detector: 'geometrico' },
})

const lectura = (parcial: Partial<LecturaDeImagen> = {}): LecturaDeImagen => ({
  deteccion: deteccion(), dudas: [
    { tipo: 'CLASE_SIN_NOMBRE', elementoRef: 'c2', confianza: 0.9, motivo: 'sin nombre' },
    { tipo: 'TEXTO_INCIERTO', elementoRef: 'c1', leido: 'titulo', confianza: 0.55, motivo: 'incierto' },
  ], dudasOmitidas: 0, enriquecedoresAplicados: ['plantillas'], enriquecedoresFallidos: [], opciones: {}, ...parcial,
})

const propuestaDeImagen = (parcial: Partial<PropuestaIA> = {}): PropuestaIA => ({
  diagramId: 'd1', origin: 'IMAGE', aceptados: [], rechazados: [], descartadosPorTope: [], advertencias: [],
  propuestosPorElModelo: 0, notaDelModelo: null, modelo: 'geometrico', interaccionId: 't1', lecturaDeImagen: lectura(), ...parcial,
})

const entrada = (id: string, parcial: Partial<EntradaHistorialIA> = {}): EntradaHistorialIA => ({
  id, userId: ANA, userName: 'Ana Pérez', modo: 'IMAGEN', modelo: 'geometrico', prompt: 'Leer la imagen', respuesta: null,
  propuesta: propuestaDeImagen({ interaccionId: id }), aplicada: false, creadaEn: '2026-11-11T15:05:00Z', imagen: meta(), ...parcial,
})

const respuesta = (estado: number, cuerpo?: unknown) =>
  Promise.resolve(new Response(cuerpo === undefined ? null : JSON.stringify(cuerpo), { status: estado }))

// --- 33 · adjuntar -----------------------------------------------------------------------------
describe('adjuntar una imagen (prueba 33)', () => {
  it('acepta PNG y JPEG de hasta 8 MB y avisa ANTES de subir si el tipo o el peso no sirven', () => {
    expect(validarArchivoDeImagen({ type: 'image/png', size: 1000 })).toBeNull()
    expect(validarArchivoDeImagen({ type: 'image/jpeg', size: 8 * 1024 * 1024 })).toBeNull()
    expect(validarArchivoDeImagen({ type: 'image/gif', size: 1000 })).toMatch(/PNG o JPEG/)
    expect(validarArchivoDeImagen({ type: 'image/webp', size: 1000 })).toMatch(/PNG o JPEG/)
    expect(validarArchivoDeImagen({ type: 'image/png', size: 8 * 1024 * 1024 + 1 })).toMatch(/máximo es de 8 MB/)
    expect(validarArchivoDeImagen({ type: 'image/png', size: 0 })).toMatch(/vacío/)
  })

  it('toma los archivos de un arrastre y de un pegado (Ctrl+V)', () => {
    const png = new File(['x'], 'captura.png', { type: 'image/png' })
    expect(archivosDeImagen({ files: [png] })).toEqual([png])
    const pegado = { files: [], items: [{ kind: 'string', getAsFile: () => null }, { kind: 'file', getAsFile: () => png }] }
    expect(archivosDeImagen(pegado)).toEqual([png])
    expect(archivosDeImagen({ files: null, items: null })).toEqual([])
  })

  it('el chat de un editor ofrece el boton de adjuntar con su selector PNG/JPEG', () => {
    const html = renderToStaticMarkup(<ChatFlotante asistente={asistenteFalso()} conectado puedeEditar inicialmenteAbierto
      onVerDetalle={() => undefined} onGenerar={() => undefined} />)
    expect(html).toContain('aria-label="Adjuntar una imagen"')
    expect(html).toContain('accept="image/png,image/jpeg"')
  })

  it('sube a POST /ai/imagenes con la parte «imagen» y el token en la cabecera', async () => {
    const f = vi.fn(() => respuesta(201, meta()))
    vi.stubGlobal('fetch', f)
    try {
      const r = await subirImagen('d1', 'tok', new File(['x'], 'a.png', { type: 'image/png' }))
      expect(r.id).toBe(meta().id)
      const [url, init] = f.mock.calls[0] as unknown as [string, RequestInit]
      expect(url).toBe('/api/v1/diagrams/d1/ai/imagenes')
      expect(init.method).toBe('POST')
      expect(init.headers).toMatchObject({ Authorization: 'Bearer tok' })
      expect((init.body as FormData).get('imagen')).toBeInstanceOf(File)
    } finally { vi.unstubAllGlobals() }
  })

  it('un 413 por tope o un 400 llegan con el mensaje del servidor', async () => {
    vi.stubGlobal('fetch', vi.fn(() => respuesta(413, { mensaje: 'Este diagrama ya tiene 100 imágenes (el máximo).' })))
    try {
      await expect(subirImagen('d1', 't', new File(['x'], 'a.png', { type: 'image/png' }))).rejects.toThrow(/100 imágenes/)
    } finally { vi.unstubAllGlobals() }
  })
})

// --- 34 · miniatura ----------------------------------------------------------------------------
describe('miniatura dentro del turno (prueba 34)', () => {
  it('pide los bytes con fetch + Authorization y ninguna URL lleva token', async () => {
    const f = vi.fn(() => Promise.resolve(new Response(new Blob(['png']), { status: 200 })))
    vi.stubGlobal('fetch', f)
    try {
      await pedirBytesDeImagen('d1', 'secreto-123', 'i1', true)
      await pedirBytesDeImagen('d1', 'secreto-123', 'i1', false)
      const llamadas = f.mock.calls as unknown as [string, RequestInit][]
      expect(llamadas[0][0]).toBe('/api/v1/diagrams/d1/ai/imagenes/i1/miniatura')
      expect(llamadas[1][0]).toBe('/api/v1/diagrams/d1/ai/imagenes/i1')
      for (const [url, init] of llamadas) {
        expect(url).not.toContain('secreto-123')
        expect(url).not.toMatch(/token|access/i)
        expect(init.headers).toMatchObject({ Authorization: 'Bearer secreto-123' })
      }
    } finally { vi.unstubAllGlobals() }
    expect(rutaDeImagen('d', 'i')).not.toContain('?')
  })

  it('una imagen que ya no existe (404) se reporta sin lanzar un error de red generico', async () => {
    vi.stubGlobal('fetch', vi.fn(() => respuesta(404, { mensaje: 'Esa imagen no existe en este diagrama' })))
    try { await expect(pedirBytesDeImagen('d1', 't', 'i1', true)).rejects.toThrow(/ya no existe/) } finally { vi.unstubAllGlobals() }
  })

  it('la miniatura es un boton que agranda; el visor es un dialogo', () => {
    const bytes = vi.fn(() => Promise.resolve(new Blob()))
    expect(renderToStaticMarkup(<MiniaturaDeImagen imagen={meta()} bytes={bytes} onAmpliar={() => undefined} />)).toContain('img-cargando')
    expect(renderToStaticMarkup(<VisorDeImagen imagen={meta()} bytes={bytes} onCerrar={() => undefined} />)).toContain('role="dialog"')
  })

  it('el turno con imagen muestra la miniatura junto a la pregunta', () => {
    const html = renderToStaticMarkup(<ChatFlotante asistente={asistenteFalso({ mensajes: [mensajeConImagen(meta())] })}
      conectado puedeEditar inicialmenteAbierto onVerDetalle={() => undefined} onGenerar={() => undefined} />)
    expect(html).toContain('img-cargando')
  })
})

// --- 35 · imagen eliminada ---------------------------------------------------------------------
describe('imagen eliminada (prueba 35)', () => {
  it('muestra «imagen eliminada» y NO pide bytes', () => {
    const bytes = vi.fn(() => Promise.resolve(new Blob()))
    const html = renderToStaticMarkup(<MiniaturaDeImagen imagen={meta({ eliminada: true, eliminadaEn: '2026-11-12T10:00:00Z' })} bytes={bytes} />)
    expect(html).toContain('imagen eliminada')
    expect(html).not.toContain('<img')
    expect(bytes).not.toHaveBeenCalled()
  })

  it('el turno eliminado conserva su texto y su lectura', () => {
    const e = entrada('t1', { imagen: meta({ eliminada: true }) })
    expect(e.propuesta.lecturaDeImagen?.deteccion.clases).toHaveLength(2)
    const html = renderToStaticMarkup(<ChatFlotante asistente={asistenteFalso({ mensajes: [mensajeConImagen(e.imagen!)] })}
      conectado puedeEditar inicialmenteAbierto onVerDetalle={() => undefined} onGenerar={() => undefined} />)
    expect(html).toContain('imagen eliminada')
    expect(html).toContain('Leer la imagen')
  })

  it('borrar manda DELETE con el token y devuelve la imagen marcada', async () => {
    const f = vi.fn(() => respuesta(200, meta({ eliminada: true })))
    vi.stubGlobal('fetch', f)
    try {
      expect((await borrarImagen('d1', 'tok', 'i1')).eliminada).toBe(true)
      expect((f.mock.calls[0] as unknown as [string, RequestInit])[1].method).toBe('DELETE')
    } finally { vi.unstubAllGlobals() }
  })
})

// --- 36 · galeria ------------------------------------------------------------------------------
describe('galería del diagrama (prueba 36)', () => {
  const galeria: GaleriaDeImagenes = { imagenes: [meta()], uso: { cantidad: 3, bytes: 1820044, maxCantidad: 100, maxBytes: 209715200 } }
  const html = (puedeEditar: boolean, esPropietario = false, usuarioId = ANA) => renderToStaticMarkup(
    <Galeria galeria={galeria} bytes={() => Promise.resolve(new Blob())} usuarioId={usuarioId} puedeEditar={puedeEditar}
      esPropietario={esPropietario} ocupado={false} onUsar={() => undefined} onReprocesar={async () => true}
      onEliminar={() => undefined} onAmpliar={() => undefined} />)

  it('dice quién subió cada imagen, cuándo, y el uso frente al tope', () => {
    const h = html(true)
    expect(h).toContain('Ana Pérez')
    expect(h).toContain('1600×1200')
    expect(h).toContain('3 de 100 imágenes')
    expect(h).toContain('200.0 MB')
  })

  it('un editor puede usarla en el chat y reprocesarla', () => {
    const h = html(true)
    expect(h).toContain('Usar en el chat')
    expect(h).toContain('Reprocesar')
  })

  it('el reproceso valida giro y recorte con los mensajes del servidor', () => {
    const img = { ancho: 1600, alto: 1200 }
    expect(validarReproceso(img, 90, undefined)).toBeNull()
    expect(validarReproceso(img, 7, undefined)).toBeNull()
    expect(validarReproceso(img, -180, undefined)).toBeNull()
    expect(validarReproceso(img, 45, undefined)).toBe('El giro debe ser de hasta 10° o un múltiplo de 90°')
    expect(validarReproceso(img, 270, undefined)).toBe('El giro debe ser de hasta 10° o un múltiplo de 90°')
    expect(validarReproceso(img, undefined, { x: 0, y: 0, ancho: 900, alto: 700 })).toBeNull()
    expect(validarReproceso(img, undefined, { x: 800, y: 0, ancho: 900, alto: 700 })).toMatch(/dentro de la imagen \(1600 × 1200\)/)
    expect(validarReproceso(img, undefined, { x: 0, y: 0, ancho: 31, alto: 700 })).toMatch(/al menos 32 px/)
    expect(validarReproceso(img, undefined, { x: -1, y: 0, ancho: 100, alto: 100 })).not.toBeNull()
  })

  it('dice lo que se pidió al reprocesar: «girada 90°, recortada»', () => {
    expect(textoDeOpciones({ giroGrados: 90, recorte: { x: 0, y: 0, ancho: 40, alto: 40 } })).toBe('girada 90°, recortada')
    expect(textoDeOpciones({})).toBe('')
  })
})

// --- 37 · VIEWER -------------------------------------------------------------------------------
describe('VIEWER (prueba 37)', () => {
  it('ve la galería y las miniaturas, pero no tiene usar, reprocesar ni borrar', () => {
    const h = renderToStaticMarkup(<Galeria galeria={{ imagenes: [meta()], uso: null }} bytes={() => Promise.resolve(new Blob())}
      usuarioId={ANA} puedeEditar={false} esPropietario={false} ocupado={false} onUsar={() => undefined}
      onReprocesar={async () => true} onEliminar={() => undefined} onAmpliar={() => undefined} />)
    expect(h).toContain('Ana Pérez')
    expect(h).not.toContain('Usar en el chat')
    expect(h).not.toContain('Reprocesar')
    expect(h).not.toContain('Borrar')
  })

  it('el chat de un VIEWER no tiene botón de adjuntar ni selector de archivo', () => {
    const h = renderToStaticMarkup(<ChatFlotante asistente={asistenteFalso({ mensajes: [mensajeConImagen(meta())] })}
      conectado puedeEditar={false} inicialmenteAbierto onVerDetalle={() => undefined} onGenerar={() => undefined} />)
    expect(h).not.toContain('Adjuntar una imagen')
    expect(h).not.toContain('type="file"')
  })

  it('borra solo quien la subió (si edita) o el OWNER; un VIEWER nunca, ni lo que subió', () => {
    const img = meta()
    expect(puedeBorrarImagen(img, { userId: ANA, puedeEditar: true, esPropietario: false })).toBe(true)
    expect(puedeBorrarImagen(img, { userId: BETO, puedeEditar: true, esPropietario: false })).toBe(false)
    expect(puedeBorrarImagen(img, { userId: BETO, puedeEditar: true, esPropietario: true })).toBe(true)
    expect(puedeBorrarImagen(img, { userId: ANA, puedeEditar: false, esPropietario: false })).toBe(false)
    expect(puedeBorrarImagen(meta({ subidaPorId: undefined }), { userId: ANA, puedeEditar: true, esPropietario: false })).toBe(false)
    expect(puedeBorrarImagen(meta({ eliminada: true }), { userId: ANA, puedeEditar: true, esPropietario: true })).toBe(false)
  })
})

// --- 38 · avisos en vivo -----------------------------------------------------------------------
describe('avisos en vivo (prueba 38)', () => {
  it('un tipo desconocido, o sin lo que su tipo promete, se ignora sin romper', () => {
    expect(clasificarAviso({ tipo: 'ALGO_NUEVO', entrada: entrada('a') })).toBeNull()
    expect(clasificarAviso({ tipo: 'NUEVA' })).toBeNull()
    expect(clasificarAviso({ tipo: 'APLICADA', entrada: null })).toBeNull()
    expect(clasificarAviso({ tipo: 'IMAGEN_SUBIDA' })).toBeNull()
    expect(clasificarAviso({ tipo: 'IMAGEN_ELIMINADA', entrada: entrada('a') })).toBeNull()
    expect(clasificarAviso(null)).toBeNull()
    expect(clasificarAviso('texto')).toBeNull()
    expect(clasificarAviso({})).toBeNull()
  })

  it('reconoce los cuatro tipos del contrato', () => {
    expect(clasificarAviso({ tipo: 'NUEVA', entrada: entrada('a') })?.clase).toBe('turno')
    expect(clasificarAviso({ tipo: 'APLICADA', entrada: entrada('a') })?.clase).toBe('turno')
    expect(clasificarAviso({ tipo: 'IMAGEN_SUBIDA', imagen: meta() })?.clase).toBe('imagen-subida')
    expect(clasificarAviso({ tipo: 'IMAGEN_ELIMINADA', imagen: meta({ eliminada: true }) })?.clase).toBe('imagen-eliminada')
  })

  it('IMAGEN_SUBIDA agrega a la galería una sola vez y cuenta el uso', () => {
    const g = { imagenes: [], uso: { cantidad: 0, bytes: 0, maxCantidad: 100, maxBytes: 1000000 } }
    const una = agregarImagen(g, meta())
    const dos = agregarImagen(una, meta())
    expect(dos.imagenes).toHaveLength(1)
    expect(dos.uso).toMatchObject({ cantidad: 1, bytes: 412345 })
  })

  it('IMAGEN_ELIMINADA marca la galería y libera el cupo una sola vez', () => {
    const g = agregarImagen({ imagenes: [], uso: { cantidad: 0, bytes: 0, maxCantidad: 100, maxBytes: 1000000 } }, meta())
    const eliminada = meta({ eliminada: true, eliminadaEn: '2026-11-12T10:00:00Z' })
    const a = marcarEliminadaEnGaleria(g, eliminada)
    const b = marcarEliminadaEnGaleria(a, eliminada)
    expect(b.imagenes[0].eliminada).toBe(true)
    expect(b.uso).toMatchObject({ cantidad: 0, bytes: 0 })
  })

  it('IMAGEN_ELIMINADA marca TAMBIÉN los turnos con esa imagen y deja intactos los demás', () => {
    const otra = meta({ id: 'otra' })
    const entradas = [entrada('a'), entrada('b', { imagen: otra }), entrada('c', { imagen: undefined, modo: 'CONSULTAR' })]
    const r = marcarImagenEliminada(entradas, meta({ eliminada: true, eliminadaEn: '2026-11-12T10:00:00Z' }))
    expect(r[0].imagen).toMatchObject({ eliminada: true, eliminadaEn: '2026-11-12T10:00:00Z' })
    expect(r[1].imagen?.eliminada).toBe(false)
    expect(r[2].imagen).toBeUndefined()
    expect(r[0].propuesta.lecturaDeImagen).toBeDefined()
  })

  it('el aviso NUEVA de un turno IMAGEN trae su imagen y reemplaza al turno local por id', () => {
    const local = entrada('t1', { imagen: undefined })
    const r = fusionarEntrada([local], entrada('t1'))
    expect(r).toHaveLength(1)
    expect(r[0].imagen?.id).toBe(meta().id)
  })
})

// --- 39 · dudas y BATCH ------------------------------------------------------------------------
describe('dudas y aplicar (prueba 39)', () => {
  it('las dudas se muestran junto al turno', () => {
    const h = renderToStaticMarkup(<LecturaDeImagenTarjeta lectura={lectura()} editable correcciones={{ nombres: {}, tipos: {}, descartados: {} }} onCorregir={() => undefined} />)
    expect(h).toContain('2 dudas de lectura')
    expect(h).toContain('Una clase quedó sin nombre (c2)')
    expect(h).toContain('Lectura incierta (c1)')
    expect(h).toContain('Nombre de la clase')
  })

  it('«sin lectura de texto» se explica y las dudas omitidas se cuentan', () => {
    const l = lectura({ dudas: [{ tipo: 'SIN_LECTURA_DE_TEXTO', confianza: 0, motivo: '' }], dudasOmitidas: 7 })
    const h = renderToStaticMarkup(<LecturaDeImagenTarjeta lectura={l} editable={false} correcciones={{ nombres: {}, tipos: {}, descartados: {} }} onCorregir={() => undefined} />)
    expect(h).toContain('No se pudo leer ningún texto')
    expect(h).toContain('8 dudas')
    expect(h).toContain('y 7 más')
  })

  it('una lectura anterior no se corrige (sin campos de nombre) pero sus dudas se ven', () => {
    const h = renderToStaticMarkup(<LecturaDeImagenTarjeta lectura={lectura()} editable={false} correcciones={{ nombres: {}, tipos: {}, descartados: {} }} onCorregir={() => undefined} />)
    expect(h).toContain('dudas de lectura')
    expect(h).not.toContain('Nombre de la clase')
  })

  it('una duda sin corregir NO bloquea aplicar: lo que sí tiene nombre entra al lote', () => {
    const { comandos, descartes } = comandosDeLectura(lectura())
    expect(comandos.map((c) => c.type)).toEqual(['CREATE_CLASS', 'ADD_ATTRIBUTE'])
    expect(comandos[0].payload).toMatchObject({ name: 'Libro', x: 80, y: 80 })
    expect(descartes.some((d) => d.includes('c2'))).toBe(true)
  })

  it('corregir la duda (escribir el nombre) suma la clase y la relación al lote, sin cambiar traductor.ts', () => {
    const { comandos } = comandosDeLectura(lectura(), { nombres: { c2: 'Socio' }, tipos: { r1: 'AGGREGATION' }, descartados: {} })
    expect(comandos.map((c) => c.type)).toEqual(['CREATE_CLASS', 'ADD_ATTRIBUTE', 'CREATE_CLASS', 'CREATE_RELATION'])
    const relacion = comandos[3].payload
    expect(relacion).toMatchObject({ kind: 'AGGREGATION', sourceMultiplicity: '1', targetMultiplicity: null })
  })

  it('descartar a mano una clase la saca, y con ella sus relaciones', () => {
    const { comandos } = comandosDeLectura(lectura(), { nombres: { c2: 'Socio' }, tipos: {}, descartados: { c2: true } })
    expect(comandos.map((c) => c.type)).toEqual(['CREATE_CLASS', 'ADD_ATTRIBUTE'])
    expect(confirmarLectura(lectura()).relaciones).toHaveLength(1)
  })

  it('sin ningún nombre no queda nada para aplicar', () => {
    const l = lectura({ deteccion: { ...deteccion(), clases: deteccion().clases.map((c) => ({ ...c, nombre: undefined })) } })
    expect(comandosDeLectura(l).comandos).toEqual([])
  })

  it('el BATCH sale con origin IMAGE; un pedido de texto o dictado conserva el suyo', () => {
    expect(origenDelLote(propuestaDeImagen())).toBe('IMAGE')
    expect(origenDelLote(propuestaDeImagen({ origin: 'VOICE' }))).toBe('VOICE')
    expect(origenDelLote(propuestaDeImagen({ origin: 'TEXT' }))).toBe('TEXT')
  })

  it('un turno IMAGEN sin comandos igual tiene algo para aplicar, y es el vigente', () => {
    const e = entrada('a')
    expect(e.propuesta.aceptados).toHaveLength(0)
    expect(tieneCambiosParaAplicar(e.propuesta)).toBe(true)
    expect(tieneCambiosParaAplicar(propuestaDeImagen({ lecturaDeImagen: undefined }))).toBe(false)
    expect(ultimaConCambios([e])?.id).toBe('a')
  })

  it('el mensaje de la IA usa lo que dijo el servidor, o un resumen de la lectura', () => {
    expect(textoDeRespuesta(propuestaDeImagen({ notaDelModelo: 'Leí 2 clases y 1 relación; 2 cosas con dudas.' }), 'IMAGEN'))
      .toBe('Leí 2 clases y 1 relación; 2 cosas con dudas.')
    expect(textoDeRespuesta(propuestaDeImagen(), 'IMAGEN')).toBe('Leí 2 clases y 1 relación; 2 cosas con dudas.')
    expect(resumenDeLectura(lectura({ dudas: [], dudasOmitidas: 0 }))).toBe('Leí 2 clases y 1 relación.')
  })
})

// --- 40 · tipos del contrato -------------------------------------------------------------------
describe('tipos del contrato (prueba 40)', () => {
  it('las formas del §4 a §6 compilan tal cual y un turno sin imagen sigue valiendo', () => {
    const subida = { tipo: 'IMAGEN_SUBIDA', imagen: meta() } satisfies import('./historial').AvisoIA
    const nueva = { tipo: 'NUEVA', entrada: entrada('x') } satisfies import('./historial').AvisoIA
    const sinImagen = { ...entrada('y'), imagen: undefined } satisfies EntradaHistorialIA
    const modo = 'IMAGEN' satisfies import('./tipos').ModoIA
    const origen = 'IMAGE' satisfies PropuestaIA['origin']
    expect([subida.tipo, nueva.tipo, sinImagen.imagen, modo, origen]).toEqual(['IMAGEN_SUBIDA', 'NUEVA', undefined, 'IMAGEN', 'IMAGE'])
  })

  it('un turno anterior a la función (sin imagen ni lectura) no cambia', () => {
    const viejo = { ...entrada('v'), imagen: undefined, modo: 'MODIFICAR' as const, propuesta: propuestaDeImagen({ origin: 'TEXT', lecturaDeImagen: undefined,
      aceptados: [{ tipo: 'CREATE_CLASS', payload: { name: 'A' }, resumen: 'Crear A' }] }) }
    expect(tieneCambiosParaAplicar(viejo.propuesta)).toBe(true)
    expect(textoDeRespuesta(viejo.propuesta, 'MODIFICAR')).toBe('Preparé 1 cambio para revisar.')
    expect(origenDelLote(viejo.propuesta)).toBe('TEXT')
  })
})

// --- ayudas ------------------------------------------------------------------------------------
function mensajeConImagen(imagen: ImagenMeta) {
  return { id: 'm1-usuario', autor: 'usuario' as const, propio: true, nombre: 'Ana', etiqueta: 'Ana · vos', inicial: 'A',
    texto: 'Leer la imagen', modo: 'IMAGEN' as const, imagen }
}

/** Lo mínimo del asistente que usa ChatFlotante: se renderiza sin red ni estado. */
function asistenteFalso(parcial: Record<string, unknown> = {}): AsistenteIA {
  return {
    mensajes: [], consultando: false, error: null, enviar: vi.fn(), ultimaPropuesta: undefined, ultimasRecomendaciones: undefined,
    recomendacionesOrdenadas: [], opcionesPorRecomendacion: new Map(), arreglosElegidos: {}, setArreglosElegidos: vi.fn(),
    seleccionRec: new Set(), setSeleccionRec: vi.fn(), convertirRecomendaciones: vi.fn(), corregirPendientes: vi.fn(),
    revisarGeneracion: vi.fn(), aplicar: vi.fn(), yaAplicada: false, estadoIA: null, modeloIA: '', texto: '', escribir: vi.fn(),
    dictar: vi.fn(), dictando: false, vozDisponible: false, origenPedido: 'TEXT', pendientesVigentes: [], cargandoHistorial: false,
    galeria: { imagenes: [], uso: null }, leerImagen: vi.fn(), eliminarImagen: vi.fn(), correcciones: {}, corregirLectura: vi.fn(),
    bytesDeImagen: () => Promise.resolve(new Blob()), usuarioId: ANA, ...parcial,
  } as unknown as AsistenteIA
}
