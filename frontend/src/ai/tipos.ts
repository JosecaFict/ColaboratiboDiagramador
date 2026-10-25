import type { CommandType } from '../contracts/command'

export type ModoIA = 'CONSULTAR' | 'REVISAR' | 'DISENAR' | 'MODIFICAR'

export interface ComandoAceptadoIA {
  tipo: CommandType
  payload: Record<string, unknown>
  resumen: string
}

export interface PropuestaIA {
  diagramId: string
  origin: 'TEXT' | 'VOICE'
  aceptados: ComandoAceptadoIA[]
  rechazados: { posicion: number; tipoCrudo: string; motivo: string }[]
  descartadosPorTope: string[]
  advertencias: string[]
  propuestosPorElModelo: number
  notaDelModelo: string | null
  modelo: string
  interaccionId?: string
}

export interface MensajeIA {
  id: string
  autor: 'usuario' | 'asistente'
  texto: string
  modo: ModoIA
  propuesta?: PropuestaIA
  usuario?: string
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
}

export interface EstadoIA {
  proveedor: string
  modelo: string
  local: boolean
  contexto: number
  modelos: { id: string; nombre: string; descripcion: string; recomendado: boolean; think: boolean }[]
}
