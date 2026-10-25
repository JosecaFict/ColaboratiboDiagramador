/**
 * La identidad visual de la pantalla de acceso: el logo y una ilustracion de lo que
 * hace la herramienta --un diagrama UML que dos personas editan a la vez--.
 *
 * Son decoracion: van con `aria-hidden` y no llevan informacion que el texto de al
 * lado no diga. Los colores son los del canvas (`--acento`) y los de los cursores de
 * presencia, para que el login ya se parezca a lo que hay adentro.
 */
export function Logo({ tamano = 30 }: { tamano?: number }) {
  return (
    <svg width={tamano} height={tamano} viewBox="0 0 32 32" aria-hidden="true" focusable="false">
      <rect width="32" height="32" rx="8" fill="#1d4ed8" />
      {/* Dos clases y la relacion que las une. */}
      <rect x="5" y="6" width="12" height="9" rx="1.5" fill="#fff" />
      <rect x="5" y="6" width="12" height="3" rx="1.5" fill="#bfdbfe" />
      <rect x="15" y="18" width="12" height="8" rx="1.5" fill="#fff" />
      <rect x="15" y="18" width="12" height="3" rx="1.5" fill="#bfdbfe" />
      <path d="M11 15v5.5a1.5 1.5 0 0 0 1.5 1.5H15" fill="none" stroke="#bfdbfe" strokeWidth="1.6" />
    </svg>
  )
}

/** Un cursor de presencia con el nombre de quien lo mueve, como los del canvas. */
function Cursor({ x, y, color, nombre }: { x: number; y: number; color: string; nombre: string }) {
  return (
    <g transform={`translate(${x} ${y})`}>
      <path d="M0 0v15.5l4.2-3.6 3 6.7 2.6-1.2-3-6.5h5.6z" fill={color} stroke="#0f172a" strokeWidth="0.8" />
      <rect x="11" y="13" width={nombre.length * 6.4 + 14} height="17" rx="8.5" fill={color} />
      <text x="18" y="25" fontSize="10.5" fontWeight="600" fill="#fff">{nombre}</text>
    </g>
  )
}

function Clase({ x, y, ancho, nombre, atributos }: {
  x: number; y: number; ancho: number; nombre: string; atributos: string[]
}) {
  const alto = 30 + atributos.length * 17 + 8
  return (
    <g transform={`translate(${x} ${y})`}>
      <rect width={ancho} height={alto} rx="6" fill="#f8fafc" stroke="#94a3b8" />
      <path d={`M0 6a6 6 0 0 1 6-6h${ancho - 12}a6 6 0 0 1 6 6v18H0z`} fill="#dbeafe" />
      <line x1="0" y1="24" x2={ancho} y2="24" stroke="#94a3b8" />
      <text x={ancho / 2} y="17" fontSize="12" fontWeight="700" fill="#1e3a8a" textAnchor="middle">{nombre}</text>
      {atributos.map((a, i) => (
        <text key={a} x="10" y={41 + i * 17} fontSize="10.5" fill="#334155" fontFamily="ui-monospace, monospace">{a}</text>
      ))}
    </g>
  )
}

export function Ilustracion() {
  return (
    <svg className="acceso-ilustracion" viewBox="0 0 400 250" aria-hidden="true" focusable="false">
      <Clase x={22} y={44} ancho={158} nombre="Libro" atributos={['- titulo: string', '- isbn: string {id}']} />
      <Clase x={226} y={140} ancho={152} nombre="Ejemplar" atributos={['- codigo: string', '- estado: Estado']} />

      {/* Composicion: rombo relleno del lado del todo, 1 a 0..* */}
      <path d="M200 112H210V168H226" fill="none" stroke="#cbd5e1" strokeWidth="1.6" />
      <path d="M180 112l10-6 10 6-10 6z" fill="#cbd5e1" />
      <text x="205" y="104" fontSize="10" fill="#93c5fd">1</text>
      <text x="196" y="184" fontSize="10" fill="#93c5fd">0..*</text>

      <Cursor x={150} y={26} color="#16a34a" nombre="Ana" />
      <Cursor x={312} y={112} color="#d97706" nombre="Beto" />
    </svg>
  )
}
