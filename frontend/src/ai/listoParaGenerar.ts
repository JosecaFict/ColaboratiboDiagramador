import { v4 as uuid } from 'uuid'
import type { EstadoModelo } from '../canvas/applyOperation'
import type { ArregloDirecto } from './arreglosDirectos'
import type { RecomendacionIA, RecomendacionesIA } from './tipos'

/** Lo que devuelve GET /codegen/resumen (ResumenGeneracion en el backend), solo lo que se revisa aqui. */
export interface ResumenCodegen {
  advertencias: string[]
  correcciones: { relationId: string; type: 'UPDATE_RELATION'; name: string; descripcion: string }[]
}

export interface AmbiguedadPendiente { elementId: string; titulo: string; decidida: boolean }

/** Tipos que el generador sabe traducir a Java (TipoJava.java). */
const TIPOS = new Set(['string', 'text', 'int', 'integer', 'long', 'decimal', 'boolean', 'bool', 'date', 'datetime', 'uuid'])

const RESERVADAS = new Set(['abstract', 'assert', 'boolean', 'break', 'byte', 'case', 'catch', 'char', 'class', 'const',
  'continue', 'default', 'do', 'double', 'else', 'enum', 'extends', 'final', 'finally', 'float', 'for', 'goto', 'if',
  'implements', 'import', 'instanceof', 'int', 'interface', 'long', 'native', 'new', 'package', 'private', 'protected',
  'public', 'return', 'short', 'static', 'strictfp', 'super', 'switch', 'synchronized', 'this', 'throw', 'throws',
  'transient', 'try', 'void', 'volatile', 'while', 'var', 'record', 'true', 'false', 'null'])
// Una entidad con estos nombres choca con java.lang o con lo que el generador importa.
const CHOCAN = new Set(['Object', 'String', 'Integer', 'Long', 'Boolean', 'Class', 'Record', 'List', 'Map', 'Set', 'Optional', 'UUID'])

/** Mismo criterio que el backend (identificador): sin tildes ni ñ, en PascalCase o camelCase. */
export function identificadorJava(valor: string, pascal: boolean): string {
  const partes = valor.normalize('NFD').replace(/\p{M}/gu, '').replace(/[^A-Za-z0-9]+/g, ' ').trim().split(/\s+/).filter(Boolean)
  let r = partes.map((p, i) => i === 0 && !pascal ? p.charAt(0).toLowerCase() + p.slice(1) : p.charAt(0).toUpperCase() + p.slice(1)).join('')
  if (!r) r = pascal ? 'Elemento' : 'valor'
  if (/^\d/.test(r)) r = (pascal ? 'Elemento' : 'valor') + r
  if (RESERVADAS.has(r.toLowerCase())) r += pascal ? 'Entidad' : 'Valor'
  if (pascal && CHOCAN.has(r)) r += 'Entidad'
  return r
}

const valido = (n: string, pascal: boolean) => (pascal ? /^[A-Z][A-Za-z0-9]*$/ : /^[a-z][A-Za-z0-9]*$/).test(n)
  && !RESERVADAS.has(n.toLowerCase()) && !(pascal && CHOCAN.has(n))

/**
 * Revisa si el diagrama puede pasar al generador de base de datos y Spring Boot. Cada punto
 * mecanico trae su arreglo directo (sin IA); lo demas es informativo. Nunca se toca el codigo
 * generado: se corrige el diagrama, y una nueva generacion ya sale bien.
 */
export function revisarParaGenerar(modelo: EstadoModelo, resumen: ResumenCodegen | null,
                                   ambiguedades: AmbiguedadPendiente[] | null): RecomendacionesIA {
  const recomendaciones: RecomendacionIA[] = []
  const arreglos: Record<number, ArregloDirecto[]> = {}
  const agregar = (gravedad: RecomendacionIA['gravedad'], titulo: string, elementos: string[], opciones: ArregloDirecto[] = []) => {
    const id = recomendaciones.length + 1
    recomendaciones.push({ id, titulo, gravedad, categoria: 'GENERACION', regla: '', elementos, problema: '',
      accion: opciones[0]?.etiqueta ?? '', origen: 'VERIFICADO' })
    if (opciones.length) arreglos[id] = opciones
  }
  const nombre = (id: string) => modelo.classes.find((c) => c.id === id)?.name ?? '?'
  const nombresDeClase = new Set(modelo.classes.map((c) => c.name))

  if (!modelo.classes.length) agregar('ALTA', 'El diagrama esta vacio: no hay nada que generar.', [])

  for (const c of modelo.classes) {
    if (!valido(c.name, true)) {
      const nuevo = identificadorJava(c.name, true)
      agregar('ALTA', `El nombre de clase "${c.name}" no es valido en Java.`, [c.name], [{ etiqueta: `Renombrar a ${nuevo}`,
        cambios: [{ tipo: 'RENAME_CLASS', payload: { classId: c.id, name: nuevo }, resumen: `Renombrar ${c.name} a ${nuevo}` }] }])
    }
    if (!c.attributes.some((a) => a.pk)) {
      const id = `id${identificadorJava(c.name, true)}`
      agregar('ALTA', `La clase ${c.name} no tiene clave primaria.`, [c.name], [{ etiqueta: `Agregar ${id} (uuid) como clave`,
        cambios: [{ tipo: 'ADD_ATTRIBUTE', payload: { attributeId: uuid(), classId: c.id, name: id, dataType: 'uuid',
          required: true, unique: true, pk: true }, resumen: `Agregar atributo ${id} (uuid) a ${c.name}` }] }])
    }
    const vistos = new Set<string>()
    for (const a of c.attributes) {
      if (!valido(a.name, false)) {
        const nuevo = identificadorJava(a.name, false)
        agregar('ALTA', `El atributo ${c.name}.${a.name} no es un nombre valido en Java.`, [`${c.name}.${a.name}`],
          [{ etiqueta: `Renombrar a ${nuevo}`, cambios: [{ tipo: 'UPDATE_ATTRIBUTE', payload: { attributeId: a.id, name: nuevo },
            resumen: `Renombrar ${c.name}.${a.name} a ${nuevo}` }] }])
      }
      const tipo = (a.dataType ?? 'string').toLowerCase()
      // Un tipo con el nombre de una clase es una enumeracion o un valor embebido: el generador lo resuelve.
      if (!TIPOS.has(tipo) && !nombresDeClase.has(a.dataType ?? '')) {
        agregar('ALTA', `El atributo ${c.name}.${a.name} usa el tipo "${a.dataType}", que el generador no conoce.`,
          [`${c.name}.${a.name}`], ['string', 'int', 'decimal', 'date'].map((t) => ({ etiqueta: `Cambiar a ${t}`,
            cambios: [{ tipo: 'UPDATE_ATTRIBUTE' as const, payload: { attributeId: a.id, dataType: t },
              resumen: `Cambiar el tipo de ${c.name}.${a.name} a ${t}` }] })))
      }
      if (vistos.has(a.name.toLowerCase())) agregar('MEDIA', `La clase ${c.name} repite el atributo ${a.name}.`, [c.name])
      vistos.add(a.name.toLowerCase())
    }
    if (modelo.classes.length > 1 && !modelo.relationships.some((r) => r.sourceClassId === c.id || r.targetClassId === c.id))
      agregar('MEDIA', `La clase ${c.name} esta desconectada: se generara sin relaciones.`, [c.name])
    if (c.attributes.length && c.attributes.every((a) => a.pk))
      agregar('BAJA', `La clase ${c.name} solo tiene su identidad: no guarda ningun dato de negocio.`, [c.name])
  }

  for (const r of modelo.relationships) {
    if (r.kind === 'GENERALIZATION' || r.kind === 'DEPENDENCY') continue
    if (r.sourceMultiplicity && r.targetMultiplicity) continue
    const s = nombre(r.sourceClassId), t = nombre(r.targetClassId)
    const completar = (ms: string, mt: string) => ({ etiqueta: `Completar ${s} [${r.sourceMultiplicity || ms}] — [${r.targetMultiplicity || mt}] ${t}`,
      cambios: [{ tipo: 'UPDATE_RELATION' as const, payload: { relationId: r.id,
        sourceMultiplicity: r.sourceMultiplicity || ms, targetMultiplicity: r.targetMultiplicity || mt },
        resumen: `Completar las multiplicidades de ${s} - ${t}` }] })
    agregar('ALTA', `La relacion ${s} - ${t} no define multiplicidad en ambos extremos.`, [s, t],
      [completar('0..*', '1'), completar('1', '0..*')])
  }

  // Academico 2026-11-08: tres errores de modelo que el generador acepta, pero que producen una base equivocada.
  const vecinas = (id: string) => new Set(modelo.relationships.flatMap((r) =>
    r.sourceClassId === id ? [r.targetClassId] : r.targetClassId === id ? [r.sourceClassId] : []))
  for (const c of modelo.classes) {
    for (const a of c.attributes) {
      // 1. periodoAcademico: string -> una clave foranea guardada como texto libre.
      const destino = !a.pk && modelo.classes.find((d) => d.id !== c.id && d.name.toLowerCase() === a.name.toLowerCase())
      if (!destino) continue
      const yaUnidas = vecinas(c.id).has(destino.id)
      agregar('MEDIA', `${c.name}.${a.name} guarda como texto una referencia a ${destino.name}: no habra clave foranea `
        + `y se podra escribir un ${destino.name} que no existe.`, [c.name, destino.name], [{
        etiqueta: yaUnidas ? `Quitar ${a.name} (la relacion con ${destino.name} ya existe)` : `Convertir en relacion con ${destino.name}`,
        cambios: [
          ...(yaUnidas ? [] : [{ tipo: 'CREATE_RELATION' as const, payload: { relationId: uuid(), sourceClassId: c.id,
            targetClassId: destino.id, kind: 'ASSOCIATION', sourceMultiplicity: '0..*', targetMultiplicity: a.required ? '1' : '0..1' },
            resumen: `Crear relacion ASSOCIATION entre ${c.name} y ${destino.name} [0..*] — [${a.required ? '1' : '0..1'}]` }]),
          { tipo: 'REMOVE_ATTRIBUTE' as const, payload: { attributeId: a.id }, resumen: `Quitar el atributo ${c.name}.${a.name}` },
        ] }])
    }
  }
  // 2. Creditos y carga horaria en el catalogo, cuando hay una clase que une la asignatura con el plan.
  for (const asig of modelo.classes.filter((c) => /^(asignatura|materia)/i.test(c.name))) {
    const datos = asig.attributes.filter((a) => !a.pk && /^(credito|cargahoraria|horas|nivel|caracter|obligatori|electiv)/i.test(a.name))
    const detalle = modelo.classes.find((d) => d.id !== asig.id && !/^plan/i.test(d.name) && vecinas(asig.id).has(d.id)
      && [...vecinas(d.id)].some((v) => /^plan/i.test(nombre(v))))
    if (!datos.length || !detalle) continue
    const lista = datos.map((a) => a.name).join(', ')
    agregar('MEDIA', `${lista} esta${datos.length === 1 ? '' : 'n'} en ${asig.name}: valen igual para todos los planes, `
      + `y un cambio en una version nueva reescribe las anteriores.`, [asig.name, detalle.name], [{
      etiqueta: `Pasar ${lista} a ${detalle.name}`,
      cambios: datos.flatMap((a) => [
        ...(detalle.attributes.some((x) => x.name.toLowerCase() === a.name.toLowerCase()) ? [] : [{ tipo: 'ADD_ATTRIBUTE' as const,
          payload: { attributeId: uuid(), classId: detalle.id, name: a.name, dataType: a.dataType, required: a.required,
            unique: false, pk: false }, resumen: `Agregar atributo ${a.name} (${a.dataType}) a ${detalle.name}` }]),
        { tipo: 'REMOVE_ATTRIBUTE' as const, payload: { attributeId: a.id }, resumen: `Quitar el atributo ${asig.name}.${a.name}` },
      ]) }])
  }
  // 3. Detalle [0..*] - [1] Prerrequisito: cada asignatura con exactamente un prerrequisito.
  for (const r of modelo.relationships) {
    const s = nombre(r.sourceClassId), t = nombre(r.targetClassId)
    const requisitoEsOrigen = /^prerrequisito/i.test(s)
    if (requisitoEsOrigen === /^prerrequisito/i.test(t)) continue
    const mRequisito = requisitoEsOrigen ? r.sourceMultiplicity : r.targetMultiplicity
    const mOtro = (requisitoEsOrigen ? r.targetMultiplicity : r.sourceMultiplicity) ?? ''
    if (!(mRequisito === '1' || mRequisito === '0..1') || !mOtro.includes('*')) continue
    const otro = requisitoEsOrigen ? t : s
    agregar('MEDIA', `${s} - ${t} obliga a que cada ${otro} tenga exactamente un prerrequisito: no admite materias sin `
      + `prerrequisitos ni con varios.`, [s, t], [{ etiqueta: `Un ${otro} con 0..* prerrequisitos`, cambios: [{
        tipo: 'UPDATE_RELATION', payload: { relationId: r.id,
          sourceMultiplicity: requisitoEsOrigen ? '0..*' : '1', targetMultiplicity: requisitoEsOrigen ? '1' : '0..*' },
        resumen: `Cambiar ${s} - ${t}: un ${otro} tiene 0..* prerrequisitos` }] }])
  }

  for (const c of resumen?.correcciones ?? []) {
    agregar('MEDIA', `Nombre de relacion: ${c.descripcion}`, [], [{ etiqueta: `Renombrar a ${c.name}`,
      cambios: [{ tipo: 'UPDATE_RELATION', payload: { relationId: c.relationId, name: c.name }, resumen: c.descripcion }] }])
  }
  for (const a of resumen?.advertencias ?? []) agregar('MEDIA', a, [])
  for (const a of (ambiguedades ?? []).filter((x) => !x.decidida))
    agregar('BAJA', `${a.titulo}: se usara la lectura por defecto. Si preferis la otra, decidila en "Puntos de ambiguedad".`, [])

  const bloqueantes = recomendaciones.filter((r) => r.gravedad === 'ALTA').length
  const resumenTexto = !recomendaciones.length
    ? `✔ El diagrama esta listo para generar: ${modelo.classes.length} clases con clave primaria, nombres validos en Java y relaciones completas.`
    : bloqueantes
      ? `Hay ${bloqueantes} punto${bloqueantes === 1 ? '' : 's'} que impide${bloqueantes === 1 ? '' : 'n'} generar. Los que tienen arreglo directo se corrigen con un clic.`
      : `✔ Se puede generar. Quedan ${recomendaciones.length} punto${recomendaciones.length === 1 ? '' : 's'} para revisar, ninguno bloquea.`
  return { diagramId: modelo.diagramId, resumen: resumenTexto, recomendaciones, verificaciones: [],
    hallazgosEstructurales: [], modelo: null, arreglos, sinIA: true, generacion: { bloqueantes } }
}
