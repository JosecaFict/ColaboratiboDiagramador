import type { RelationKind } from '../contracts/command'

/**
 * Dibujo en chico de cada notacion UML, para que el usuario elija VIENDO la
 * relacion y no leyendo una palabra. Se dibuja con figuras sueltas en vez de
 * con los marcadores del canvas: a este tamano conviene controlar cada punto.
 */
export function NotacionRelacion({ kind }: { kind: RelationKind }) {
  const trazo = '#475569'
  return (
    <svg width="54" height="16" viewBox="0 0 54 16" aria-hidden="true">
      {kind === 'AGGREGATION' && (
        <polygon points="2,8 10,3 18,8 10,13" fill="#fff" stroke={trazo} strokeWidth={1.2} />
      )}
      {kind === 'COMPOSITION' && (
        <polygon points="2,8 10,3 18,8 10,13" fill={trazo} stroke={trazo} strokeWidth={1.2} />
      )}

      <line
        x1={kind === 'AGGREGATION' || kind === 'COMPOSITION' ? 18 : 2}
        y1={8}
        x2={kind === 'GENERALIZATION' ? 40 : kind === 'DEPENDENCY' ? 40 : 52}
        y2={8}
        stroke={trazo}
        strokeWidth={1.4}
        strokeDasharray={kind === 'DEPENDENCY' ? '4 3' : undefined}
      />

      {kind === 'GENERALIZATION' && (
        <polygon points="40,3 52,8 40,13" fill="#fff" stroke={trazo} strokeWidth={1.2} />
      )}
      {kind === 'DEPENDENCY' && (
        <path d="M40,3 L52,8 L40,13" fill="none" stroke={trazo} strokeWidth={1.2} />
      )}
    </svg>
  )
}
