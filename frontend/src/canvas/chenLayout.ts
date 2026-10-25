// ============================================================================
// LAYOUT RADIAL DE ATRIBUTOS - notacion Chen (ADR-006)
//
// DECISION CLAVE: los atributos NO tienen geometria propia. No hay fila en
// diagram_layout para ellos; su posicion se CALCULA a partir de la de su
// entidad.
//
// Consecuencias:
//   - cero operaciones MOVE de ovalos viajando por el WebSocket
//   - cero locks sobre atributos
//   - renderizado DETERMINISTA: mismo modelo -> misma imagen en todos los
//     clientes, siempre. Imposible que dos usuarios vean el diagrama distinto.
//
// Costo aceptado: el usuario no puede recolocar un ovalo a mano.
// ============================================================================

export interface Punto { x: number; y: number }

const GRADO = Math.PI / 180
const BLOQUEO_POR_RELACION = 20 * GRADO   // semiancho del cono que ocupa una relacion
const RADIO_ANILLO_1 = 90
const RADIO_ANILLO_2 = 150
const MAX_ANILLO_1 = 8
const RADIO_COMPUESTO = 50

/** Normaliza un angulo a [0, 2PI). */
function norm(a: number): number {
  const dosPi = Math.PI * 2
  return ((a % dosPi) + dosPi) % dosPi
}

/**
 * Encuentra el mayor arco libre alrededor de la entidad, esquivando los conos
 * que ocupan las relaciones salientes.
 *
 * Si no hay relaciones, usa el semicirculo superior (200 a 340 grados), que es
 * como se ve un diagrama Chen de libro.
 */
export function arcoLibre(anguloRelaciones: number[]): { inicio: number; fin: number } {
  if (anguloRelaciones.length === 0) {
    return { inicio: 200 * GRADO, fin: 340 * GRADO }
  }

  // Intervalos bloqueados, normalizados y ordenados
  const bloqueados = anguloRelaciones
    .map((a) => norm(a))
    .sort((p, q) => p - q)

  let mejorInicio = 0
  let mejorTamano = -1

  for (let i = 0; i < bloqueados.length; i++) {
    const finActual = bloqueados[i] + BLOQUEO_POR_RELACION
    const siguiente = bloqueados[(i + 1) % bloqueados.length]
    const inicioSiguiente =
      (i + 1 === bloqueados.length ? siguiente + Math.PI * 2 : siguiente) - BLOQUEO_POR_RELACION

    const tamano = inicioSiguiente - finActual
    if (tamano > mejorTamano) {
      mejorTamano = tamano
      mejorInicio = finActual
    }
  }

  // Si todo esta bloqueado, caemos al semicirculo superior igual.
  if (mejorTamano <= 10 * GRADO) {
    return { inicio: 200 * GRADO, fin: 340 * GRADO }
  }
  return { inicio: mejorInicio, fin: mejorInicio + mejorTamano }
}

/**
 * Calcula la posicion de cada ovalo de atributo alrededor de su entidad.
 *
 * @param centro           centro de la entidad
 * @param cantidad         numero de atributos
 * @param anguloRelaciones angulos (rad) hacia los rombos de relacion salientes
 */
export function posicionarAtributos(
  centro: Punto,
  cantidad: number,
  anguloRelaciones: number[] = []
): Punto[] {
  if (cantidad <= 0) return []

  const { inicio, fin } = arcoLibre(anguloRelaciones)
  const usaDosAnillos = cantidad > MAX_ANILLO_1
  const enAnillo1 = usaDosAnillos ? Math.ceil(cantidad / 2) : cantidad
  const puntos: Punto[] = []

  const colocar = (indice: number, total: number, radio: number) => {
    const paso = (fin - inicio) / total
    const ang = inicio + (indice + 0.5) * paso
    puntos.push({
      x: centro.x + radio * Math.cos(ang),
      y: centro.y + radio * Math.sin(ang),
    })
  }

  for (let i = 0; i < enAnillo1; i++) colocar(i, enAnillo1, RADIO_ANILLO_1)
  const enAnillo2 = cantidad - enAnillo1
  for (let i = 0; i < enAnillo2; i++) colocar(i, enAnillo2, RADIO_ANILLO_2)

  return puntos
}

/**
 * Atributos compuestos: los hijos orbitan al ovalo padre, con radio menor y
 * apuntando en direccion OPUESTA a la entidad para no cruzarse con ella.
 */
export function posicionarSubAtributos(
  centroEntidad: Punto,
  padre: Punto,
  cantidad: number
): Punto[] {
  if (cantidad <= 0) return []
  const haciaAfuera = Math.atan2(padre.y - centroEntidad.y, padre.x - centroEntidad.x)
  const abanico = 100 * GRADO
  const inicio = haciaAfuera - abanico / 2
  const puntos: Punto[] = []
  for (let i = 0; i < cantidad; i++) {
    const ang = inicio + ((i + 0.5) * abanico) / cantidad
    puntos.push({
      x: padre.x + RADIO_COMPUESTO * Math.cos(ang),
      y: padre.y + RADIO_COMPUESTO * Math.sin(ang),
    })
  }
  return puntos
}

/**
 * Radio efectivo de repulsion para el auto-layout de entidades.
 *
 * IMPORTANTE: hay que usar ESTO y no el ancho del rectangulo. Si no, dos
 * entidades quedan cerca y sus halos de atributos se superponen.
 */
export function radioHalo(cantidadAtributos: number): number {
  return (cantidadAtributos > MAX_ANILLO_1 ? RADIO_ANILLO_2 : RADIO_ANILLO_1) + 70
}
