# Tarea A — revisión de Codex

Estado: consolidación de T-01; no integrar todavía. La entrega de esta
continuación es el commit de los cambios heredados y su evidencia, no el cierre
de los criterios pendientes contra bases reales.

## Coordinación

- Responsable de esta continuación: Codex, Herdr `t14`, por pedido del usuario.
- Coordinador con quien se acuerda integración: `04`, Herdr `t3`.
- Implementación original: Comparador, `feat/generador-produccion`, commit `7701576`.
- Verificador original: Sonnet-Diagramador, `t7`; su sesión recibió el commit y quedó detenida por cuota. Avisado de esta continuación.
- Rama aislada: `fix/generador-revision`, en `.worktrees/generador-revision`.
- Base integrada para evaluar: `7701576` más `main` en `9ba5efa`. La unión se realizó sin conflictos en esta rama de revisión.
- Android continúa pendiente; esta tarea atiende la prioridad nueva del usuario.

## Reparto histórico de la revisión inicial

- `revision_mongo`: `PlantillasMongo.java`, `MapeoDocumental.java`, pruebas Mongo nuevas e informe `INFORME-REVISION-MONGO.md`.
- `revision_produccion`: `PlantillasProduccion.java`, `ProduccionBordesTest.java` e informe `INFORME-REVISION-PRODUCCION.md`.
- Codex: validación conjunta, compilación de proyectos generados y este informe.

Los worktrees originales permanecen a cargo de sus agentes. No se hace push ni merge a main en esta revisión.

La continuación de 2026-11-01 la realiza Codex sin nuevos ayudantes. La fuente
vigente de asignación y próximos pasos es la ficha T-01 en la carpeta principal.

## Hallazgos a resolver

1. Un traslado de parte incrustada entre padres puede perder datos si falla el segundo guardado.
2. Las cascadas Mongo omiten controles de referencias de las partes.
3. Se pierde la unicidad de atributos en partes incrustadas.
4. Las referencias uno a uno carecen de índice único en Mongo.
5. El parámetro `sort` malformado puede provocar un error 500.

## Evidencia y pendientes

Un build o una prueba con Mockito no sustituyen el recorrido contra MongoDB real.
No se arranca Docker ni se usa la base compartida del producto para esta revisión.
La suite del backend dispone de pruebas PostgreSQL condicionales: sus omisiones
deben registrarse, no contarse como comprobación de una base real.

## Tratamiento de los hallazgos

1. Traslado entre padres: rechazo 409 antes de escribir; pendiente de aceptación
   del recorte funcional en la decisión conjunta.
2. Cascadas entre colecciones: rechazo 409 mientras existan partes, evitando
   borrados parciales; pendiente de la misma decisión. No cubre carreras entre
   validación y escritura de referencias en distintas colecciones.
3. Unicidad de partes: colección independiente con índice único y advertencia;
   falta verificar el índice y duplicados contra Mongo real.
4. FK 1:1: índice único disperso; falta verificar duplicados y nulos en Mongo real.
5. Orden malformado: validación completa del parámetro y respuesta 400 con
   `errores.sort`, probada ejecutando el código generado con MockMvc.

Los cambios de alcance Mongo están propuestos en DECISIONES.md de la carpeta
principal. No se registran como aceptación del líder. Los detalles están en
`INFORME-REVISION-MONGO.md` e `INFORME-REVISION-PRODUCCION.md`.

## Continuación y validación · 2026-11-01

El primer intento dentro del entorno restringido no pudo acceder al repositorio
local de Maven. El siguiente activó `ProveedorGeminiVivoTest` por una variable de
entorno presente y fue interrumpido mientras esperaba al servicio externo.
La ejecución de referencia desactiva `GEMINI_API_KEY` y `OLLAMA_VIVO` solo en el
proceso de prueba, sin cambiar la configuración persistente ni imprimir claves.

Resultado de la suite final (`revision-t01-mvn-local-final.log`): **BUILD SUCCESS,
1018 casos, 0 fallos, 0 errores y 3 omitidos**, finalizada a las 16:30 (UTC-4).
Incluye ProduccionBordesTest (13), SeguridadMongoTest (3), ManifestDeAppTest (13),
GeneradorProduccionTest (11) y ArranqueDelProyectoGeneradoTest (8, sobre H2).

Omitidos: PlanUmlOllamaVivoTest, ProveedorOllamaVivoTest y ProveedorGeminiVivoTest.
Además, ApiDeProyectosTest, AtajoDeDesarrolloTest y VersionesTest reportan cero
casos por sus condiciones de PostgreSQL de prueba. No son evidencia de pruebas
reales de base aprobadas. Los modelos conservaron manifestVersion 1 y el espejo
de manifiestos; no se modificaron los fixtures móviles.

Comando de referencia desde `backend/` (PowerShell):

```powershell
$env:GEMINI_API_KEY = ''; $env:OLLAMA_VIVO = ''
mvn -B test '-Dgenerados.salida=target/revision-generados' '-Dmongo.seguridad.salida=target/mongo-seguridad'
```

Commit del código validado: `3415a43`. La matriz de package se registra por
separado al terminar.

La primera suite local terminó con 1018 casos, 13 fallos de
`ProduccionBordesTest`, cero errores y tres omitidos: la sonda recibía una
representación distinta de JSON por negociación de contenido. Se corrigió
solicitando `Accept: application/json` y se repitió la suite completa con el
resultado en verde indicado arriba.

También se corrigió `tools/validar-generados.ps1`: en Windows PowerShell, stderr
con una advertencia de Mockito detenía el script por `ErrorActionPreference=Stop`.
Ahora se registra la salida y se usa el código de salida real de Maven. Se
repitió la matriz con ese arreglo; no se cuentan los intentos interrumpidos como
validación completa.
