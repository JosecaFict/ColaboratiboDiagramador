import { ETIQUETA_DE_GRUPO, mostrarValor, totalDeCambios } from './diff'
import { GRUPOS, type DiffVersiones, type ElementoDelDiff, type GrupoDelDiff, type NombreDeGrupo } from './tipos'

type ConGrupos = Pick<DiffVersiones, NombreDeGrupo>

const ETIQUETA_CAMPO: Record<string, string> = {
  name: 'nombre', dataType: 'tipo', returnType: 'devuelve', params: 'parámetros', visibility: 'visibilidad',
  isAbstract: 'abstracta', weak: 'débil', erKind: 'clase ER', inheritanceStrategy: 'herencia',
  pk: 'clave primaria', required: 'obligatorio', unique: 'único', defaultValue: 'valor por defecto',
  ordinal: 'posición', multivalued: 'multivaluado', derived: 'derivado', partialKey: 'clave parcial',
  parentAttributeId: 'atributo padre', minValue: 'mínimo', maxValue: 'máximo', length: 'longitud',
  sourceClassId: 'origen', targetClassId: 'destino', kind: 'tipo de relación', sourceRole: 'rol origen',
  targetRole: 'rol destino', sourceMultiplicity: 'multiplicidad origen', targetMultiplicity: 'multiplicidad destino',
  identifying: 'identificante', sourceParticipation: 'participación origen',
  targetParticipation: 'participación destino', degree: 'grado', classId: 'clase', stereotype: 'estereotipo',
  existencia: 'existencia',
  // Geometria: el backend la reporta como cambios de la clase o la relacion.
  x: 'posición x', y: 'posición y', width: 'ancho', height: 'alto', waypoints: 'quiebres de la línea',
}

const DECISION = 'decision.'
const ETIQUETA_DECISION: Record<string, string> = { COMPOSITION: 'composición' }

/** `decision.COMPOSITION` -> "decisión: composición"; el resto, la etiqueta del campo o el campo tal cual. */
export const etiquetaDeCampo = (campo: string): string => {
  if (campo.startsWith(DECISION)) {
    const tipo = campo.slice(DECISION.length)
    return 'decisión: ' + (ETIQUETA_DECISION[tipo] ?? tipo.toLowerCase().replace(/_/g, ' '))
  }
  return ETIQUETA_CAMPO[campo] ?? campo
}

const donde = (e: ElementoDelDiff) => (e.clase ? <span className="diff-clase"> · en {e.clase}</span> : null)

function Grupo({ nombre, grupo }: { nombre: NombreDeGrupo; grupo: GrupoDelDiff }) {
  const n = grupo.agregadas.length + grupo.eliminadas.length + grupo.modificadas.length
  if (n === 0) return null
  return (
    <section className="diff-grupo">
      <h4>
        {ETIQUETA_DE_GRUPO[nombre]}
        <span className="diff-cuenta">{n}</span>
      </h4>
      <ul>
        {grupo.agregadas.map((e) => (
          <li key={'a' + e.id} className="diff-agregado">
            <span className="diff-marca" aria-label="agregado">＋</span>
            <strong>{e.nombre}</strong>{donde(e)}
          </li>
        ))}
        {grupo.eliminadas.map((e) => (
          <li key={'e' + e.id} className="diff-eliminado">
            <span className="diff-marca" aria-label="eliminado">－</span>
            <strong>{e.nombre}</strong>{donde(e)}
          </li>
        ))}
        {grupo.modificadas.map((e) => (
          <li key={'m' + e.id} className="diff-modificado">
            <span className="diff-marca" aria-label="modificado">≠</span>
            <strong>{e.nombre}</strong>{donde(e)}
            <ul className="diff-cambios">
              {e.cambios.map((c) => (
                <li key={c.campo}>
                  <span className="diff-campo">{etiquetaDeCampo(c.campo)}</span>
                  <del>{mostrarValor(c.antes, c.campo)}</del>
                  <span aria-hidden> → </span>
                  <ins>{mostrarValor(c.despues, c.campo)}</ins>
                </li>
              ))}
            </ul>
          </li>
        ))}
      </ul>
    </section>
  )
}

/** Lo que dice cada extremo: la version (con su seq) o el estado actual. */
export const nombreDeExtremo = (x: { versionId: string | null; seq: number }, nombres: Record<string, string>): string =>
  x.versionId === null ? `el estado actual (#${x.seq})` : `${nombres[x.versionId] ?? 'una versión'} (#${x.seq})`

interface Props {
  diff: DiffVersiones
  /** id de version -> nombre, para rotular los extremos. */
  nombres: Record<string, string>
  /** Resaltar lo que cambio en el lienzo; ausente = no se ofrece. */
  onResaltar?: () => void
}

/** El diff agrupado por clases, atributos, metodos y relaciones. Verde agregado, rojo eliminado, ambar modificado. */
export function VistaDiff({ diff, nombres, onResaltar }: Props) {
  const total = totalDeCambios(diff)
  return (
    <div className="diff">
      <p className="diff-titulo">
        De <strong>{nombreDeExtremo(diff.desde, nombres)}</strong> a <strong>{nombreDeExtremo(diff.hasta, nombres)}</strong>
      </p>
      {total === 0 ? (
        <p className="tenue">Sin diferencias en el modelo.</p>
      ) : (
        <>
          <p className="diff-leyenda">
            <span className="diff-agregado">＋ agregado</span>
            <span className="diff-eliminado">－ eliminado</span>
            <span className="diff-modificado">≠ modificado</span>
            {onResaltar && (
              <button className="tb-btn" onClick={onResaltar}>Resaltar en el lienzo</button>
            )}
          </p>
          {GRUPOS.map((g) => <Grupo key={g} nombre={g} grupo={diff[g]} />)}
        </>
      )}
    </div>
  )
}
