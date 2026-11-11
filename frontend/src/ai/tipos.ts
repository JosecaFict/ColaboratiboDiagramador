import type { CommandType } from '../contracts/command'
import type { ImagenMeta, LecturaDeImagen } from '../contracts/imagenes'

export type ModoIA = 'CONSULTAR' | 'REVISAR' | 'DISENAR' | 'MODIFICAR' | 'RECOMENDAR' | 'IMAGEN'

/** Respuesta de POST /ai/recomendaciones (RecomendacionesIA en el backend). */
export interface RecomendacionIA {
  id: number
  titulo: string
  gravedad: 'ALTA' | 'MEDIA' | 'BAJA'
  categoria: string
  regla: string
  elementos: string[]
  problema: string
  accion: string
  /** VERIFICADO: comprobado por codigo. MODELO: opinion del modelo, revisarla. */
  origen: 'VERIFICADO' | 'MODELO'
}

export interface VerificacionIA {
  numero: number
  regla: string
  seccion: string
  estado: 'CUMPLE' | 'NO_CUMPLE' | 'NO_VERIFICABLE'
  evidencia: string[]
  explicacion: string
}

export interface RecomendacionesIA {
  diagramId: string
  resumen: string
  recomendaciones: RecomendacionIA[]
  verificaciones: VerificacionIA[]
  hallazgosEstructurales: string[]
  modelo: string | null
  /** Solo en el frontend: arreglos ya armados por recomendacion (p. ej. la revision para generar). */
  arreglos?: Record<number, import('./arreglosDirectos').ArregloDirecto[]>
  /** Solo en el frontend: lo que no tiene arreglo directo no se manda al modelo. */
  sinIA?: boolean
  /** Solo en el frontend: revision "¿esta listo para generar?". */
  generacion?: { bloqueantes: number }
}

export interface ComandoAceptadoIA {
  tipo: CommandType
  payload: Record<string, unknown>
  resumen: string
}

export interface PropuestaIA {
  diagramId: string
  origin: 'TEXT' | 'VOICE' | 'IMAGE'
  aceptados: ComandoAceptadoIA[]
  rechazados: { posicion: number; tipoCrudo: string; motivo: string }[]
  descartadosPorTope: string[]
  advertencias: string[]
  propuestosPorElModelo: number
  notaDelModelo: string | null
  modelo: string
  interaccionId?: string
  /** Solo en un turno IMAGEN: lo que se leyo de la foto. */
  lecturaDeImagen?: LecturaDeImagen
}

export interface EntradaHistorialIA {
  id: string
  userId: string | null
  userName: string
  modo: ModoIA
  modelo: string
  prompt: string
  respuesta: string | null
  propuesta: PropuestaIA
  aplicada: boolean
  creadaEn: string
  /** Falta en un turno sin imagen. Con `eliminada: true` el turno muestra "imagen eliminada". */
  imagen?: ImagenMeta
}

export interface EstadoIA {
  proveedor: string
  modelo: string
  local: boolean
  contexto: number
  modelos: { id: string; nombre: string; descripcion: string; recomendado: boolean; think: boolean }[]
}
