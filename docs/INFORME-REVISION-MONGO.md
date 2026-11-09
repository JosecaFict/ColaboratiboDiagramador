# Revisión MongoDB de T-01

Fecha: 2026-11-01. Responsable de consolidación: Codex (t14).
Rama: `fix/generador-revision`; base `a7a9645`.

Se retoman cambios que ya estaban sin commit. Este informe registra su alcance;
no sustituye la aprobación del líder ni la revisión independiente que exige T-01.

## Cambios y límites

| Hallazgo | Tratamiento en la rama | Evidencia / pendiente |
|---|---|---|
| Traslado de una parte entre padres con dos guardados | Rechazo 409 antes de escribir. Editar dentro del mismo padre sigue permitido. | Prueba generada ejecuta `IncrustadoMongo` con repositorio simulado, verifica cero escrituras en el traslado y una en edición normal. Es un recorte funcional pendiente de aprobación. |
| Cascada entre colecciones sin comprobar todas las referencias | Rechazo 409 si quedan partes en otra colección; exige borrarlas primero. | Prueba generada ejecuta el servicio y verifica que no borre padre ni partes ante el rechazo. No implementa una transacción ni demuestra integridad concurrente. |
| Unicidad de atributos de partes incrustadas | La parte con atributo único pasa a colección propia, con aviso e índice único. | `SeguridadMongoTest` comprueba el mapeo y la emisión del índice. Falta verificar duplicados contra Mongo real. |
| Referencias 1:1 sin índice único | Emite `@Indexed(unique = true, sparse = true)`; una parte con referencia 1:1 propia también se separa. | Caso canónico 01 en `SeguridadMongoTest`. Falta comprobar creación efectiva del índice, duplicados y nulos contra Mongo real. |

Los recortes evitan las rutas de escritura parcial identificadas. No equivalen a
resolver transacciones distribuidas ni carreras entre la comprobación de una
referencia y una escritura concurrente. No se afirma que Mongo tenga las mismas
garantías referenciales que PostgreSQL.

Las propuestas están registradas en la copia principal de
`docs/coordinacion/DECISIONES.md`. Antes de integrar hay que aceptar explícitamente
el alcance y actualizar ADR-035 y el diseño, o implementar otra solución atómica.
El manifiesto conserva su contrato v1; la verificación corresponde a la suite.

## Reproducción

En `backend/`, ejecutar la suite con `-Dgenerados.salida=target/revision-generados`
y `-Dmongo.seguridad.salida=target/mongo-seguridad`. Las pruebas de IA externa son
opcionales y se desactivan para esta validación retirando sus variables únicamente
del proceso de prueba.

Desde la raíz del worktree, `tools/validar-generados.ps1 -Almacen mongo` compila y
prueba nueve proyectos: siete canónicos, biblioteca y tienda. Ejecutar también
`mvn -B -f backend/target/mongo-seguridad/pom.xml package` para la variante tienda
con unicidad y composición separada. Estas pruebas generadas usan dobles de
repositorio; no arrancan MongoDB.

Los resultados de esta continuación y las pruebas reales pendientes se consolidan
en `INFORME-TAREA-A-CODEX.md` y en la ficha principal de T-01.
