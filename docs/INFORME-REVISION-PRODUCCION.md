# Revisión del generador: parámetros de producción

Fecha: 2026-11-01. Rama: `fix/generador-revision`, sobre el trabajo de Comparador (`7701576`) y el `main` integrado por 04.

Autor inicial: subagente `revision_produccion` de Codex (`t14`). Continuación y
validación: Codex. Revisión independiente pendiente según la ficha T-01.

## Hallazgo corregido

El código emitido de `Paginas.orden` usaba `String.split(",")`, que descarta componentes vacíos al final. Un listado con `sort=,` llegaba a `partes[0]` sobre un arreglo vacío y devolvía 500. `sort=nombre,` se aceptaba como ascendente y `sort=nombre,asc,desc` ignoraba parte de lo enviado.

Ahora conserva los componentes vacíos y valida que haya un campo y, si se incluye, una única dirección no vacía. Las formas mal construidas devuelven 400 con `errores.sort`. Se conservan el orden opcional, el ascendente por defecto, la lista de campos permitidos y las direcciones `asc`/`desc` sin distinción de mayúsculas.

La utilidad es compartida por las variantes PostgreSQL y MongoDB.

## Archivos propios

- `backend/src/main/java/com/uagrm/colabmodel/codegen/PlantillasProduccion.java`.
- `backend/src/test/java/com/uagrm/colabmodel/codegen/ProduccionBordesTest.java`.
- Este informe.

## Verificación

`ProduccionBordesTest` compila los fuentes emitidos de las utilidades y del manejador HTTP, y los ejecuta con MockMvc mediante un controlador de prueba. No se limita a buscar fragmentos del texto generado.

Cubre once formas inválidas de `sort`, ausencia de orden, orden ascendente implícito, dirección con espacios y mayúsculas, página negativa y tamaños fuera del intervalo 1–500. Verifica las respuestas HTTP y la presencia del error asociado al campo. No necesita base de datos ni servidor.

Comando: `mvn -q test -Dtest=ProduccionBordesTest`.

En la continuación se detectó que MockMvc podía negociar XML porque el classpath
de P1 incluye el soporte usado por XMI. La sonda solicita ahora explícitamente
`Accept: application/json`: se mantienen todas las aserciones de estado HTTP y
contenido, sin relajar el contrato para hacer pasar las pruebas.

Estado de ejecución: resultado final en `INFORME-TAREA-A-CODEX.md`.

## Alcance y entrega

No se modificaron archivos de MongoDB ni los worktrees de otros agentes. Las compilaciones completas de los proyectos generados y la revisión de Mongo corresponden a los otros responsables del mismo frente y deben constar en el informe consolidado de Codex. Este arreglo no sustituye el recorrido CRUD contra una base real.
