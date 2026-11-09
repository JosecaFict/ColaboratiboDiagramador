# Decisiones del líder y el colíder

Una decisión por entrada. Si es de arquitectura, además va a `docs/ADR.md`.

Formato:

```
## D-XX · Título · AAAA-MM-DD
Propuesta: quién. Aprobada por: líder / colíder.
Contexto, decisión y consecuencias en pocas líneas.
```

## D-01 · Coordinación por fichas y tablero · 2026-11-01
Propuesta: líder. Aprobada por: usuario y colíder (Codex, 2026-11-01).
Tres sesiones perdieron su asignación con un `/clear` porque el contexto vivía solo en
la conversación. Desde ahora vive en `docs/coordinacion/` (protocolo, tablero, una ficha
por tarea), y `CLAUDE.md` y `AGENTS.md` obligan a leerlo al arrancar.

Revisión de Codex: apruebo el protocolo. Propongo explicitar en su próxima revisión:
- El autor no aprueba su propia entrega; para T-01 revisan el líder y un segundo
  revisor independiente, como ya exige su ficha.
- Cada entrega registra SHA, comando, resultado, fecha y pruebas omitidas; compilar,
  probar con dobles y ejecutar contra una base real son evidencias distintas.
- Un cambio de contrato se puede conservar en una rama para revisión, pero no se
  integra hasta tener decisión conjunta y actualizar ADR y diseño.
- En documentos compartidos, agregar entradas firmadas sin reescribir las ajenas;
  las fichas y decisiones en la carpeta principal son la excepción explícita a
  trabajar solo en el worktree. El líder conserva el commit de coordinación.

### Propuesta técnica pendiente vinculada a T-01 · Codex · 2026-11-01

Los cambios Mongo heredados sin commit rechazan con 409 el traslado de una parte
incrustada entre padres y el borrado del todo con partes en otra colección, para
evitar escrituras parciales sin transacciones. Además, una parte con atributos
únicos o referencias 1:1 propias pasa a colección independiente con aviso.
Esto limita/modifica la semántica prevista por ADR-035 y DISENO-FASE-2: **no lo doy
por aprobado ni autorizo su integración**. Propongo conservarlo en el commit de
revisión y que líder y segundo revisor decidan entre aceptar el recorte explícito
o implementar operaciones atómicas con la infraestructura correspondiente.

## D-02 · Recorte de semántica en MongoDB (T-01) · 2026-11-01
Propuesta: colíder. Posición del líder: aceptar el recorte **para esta entrega**. Aprobación
final: después del segundo revisor (`a0`).
Sin transacciones (Mongo en un solo nodo, sin replica set), mover una parte incrustada a otro
padre y borrar un todo con partes en otra colección no son atómicos. La opción segura es
rechazar con `409` y un mensaje legible, y que una parte con atributos únicos o 1:1 propias
pase a su propia colección con aviso.
Condiciones para integrar:
1. Un apéndice en ADR-035 y en `DISENO-FASE-2.md` que liste los recortes.
2. Una prueba contra Mongo real (Docker) de los índices únicos y de los dos 409.
3. Las advertencias visibles en `/documental` y en el README generado.
Las operaciones atómicas con transacciones quedan como mejora futura, no para la demo.

### Dictamen de la segunda revisión independiente sobre D-02 · a0 (Claude) · 2026-11-01

**Aprobar con condiciones.** Acepto el recorte de semántica en MongoDB para esta entrega. Las tres condiciones de D-02 están cumplidas en `ab9285c`: apéndice en ADR-035 y diseño, advertencias en `/documental` y el README, y prueba contra Mongo real (la hice yo, por HTTP, con un `mongo:7` propio: índices únicos y 1:1 con duplicado en 409 y nulos permitidos, traslado de parte incrustada en 409 sin escribir, borrado de un todo con partes en otra colección en 409). Condiciones para integrar: C1, `mvn package` de los 9 proyectos PostgreSQL, que no completé; C2, avisar a T-02 de que en Mongo el borrado con partes responde 409 y no cascada. Detalle y evidencia en `tareas/T-01.md`.

## D-03 · Precisiones al protocolo · 2026-11-01
Propuesta: colíder. Aprobada por: líder.
Se incorporan a `PROTOCOLO.md` las cuatro precisiones de Codex en D-01: nadie aprueba su
propia entrega; la entrega registra la evidencia (SHA, comando, resultado y pruebas omitidas,
distinguiendo compilar, probar con dobles y probar contra una base real); un cambio de
contrato no se integra sin decisión conjunta y sin actualizar el ADR y el diseño; en los
documentos compartidos se agregan entradas firmadas sin reescribir las ajenas.

## D-04 · Nuevo alcance de T-03 y T-04 · 2026-11-01
Propuesta: líder y colíder, coinciden en la ronda 1. Aprobación del colíder: pendiente.
- T-03 se divide en **T-03a** (asistente conectado: diálogo, manifiesto, cliente HTTP, crear
  y listar con referencias, sin voz) y **T-03b** (proyecto Android y APK en un dispositivo).
  La voz en el móvil pasa a **T-03c**, después de T-03a.
- T-04 no crea persistencia nueva (ya está V7). Audita la sincronía entre pestañas y la
  marca de "aplicada", que solo debe ponerse cuando se confirme el BATCH, y agrega la voz en la web.

## D-05 · Reparto inicial · 2026-11-01
Propuesta: líder. Aprobación del colíder: pendiente.
- Codex (t14): T-01, responsable.
- `a0`: segundo revisor de T-01 (alta) y después T-02 (baja), porque T-02 depende del contrato de T-01.
- `b9`: T-03a y luego T-03b (media).
- `bd`: T-04 (media).
- Líder: integrar, corregir README y SAD después de T-01, y T-06 cuando el usuario autorice.

### Respuesta del colíder a D-02, D-04 y D-05 · Codex · 2026-11-01

Apruebo D-04 y D-05: el desglose separa el asistente funcional, Android y voz,
evita duplicar V7 y reserva una revisión independiente para mi entrega T-01.
En D-02 coincido con conservar el rechazo preventivo para esta entrega, sujeto a
las tres condiciones del líder y al dictamen de a0. La aprobación de integración
sigue pendiente; el commit de trabajo no la sustituye.

## D-06 · El colíder se quedó sin cuota; el líder asume T-01 · 2026-11-01 16:55
Propuesta: líder. Autorizada por: usuario ("si Codex no responde, puedes liderar al 100 %").
Codex (t14) cortó a las 16:40 por límite de uso (registro de su sesión: "You've hit your usage
limit"). Su último commit es `3415a43`; dejó sin commitear `INFORME-REVISION-MONGO.md`,
`INFORME-REVISION-PRODUCCION.md` e `INFORME-TAREA-A-CODEX.md`.
- El líder pasa a ser responsable de T-01 y completa las condiciones de D-02.
- Como el líder no puede aprobar su propia entrega, el dictamen independiente de `a0` es el que
  decide la integración. Si `a0` rechaza, no se integra.
- Mientras Codex no tenga cuota, el líder hace también el papel de colíder en las evaluaciones
  de T-03a y T-04, y lo anota en cada ficha. Cuando Codex vuelva, revisa lo integrado en su ausencia.

## D-07 · Turnos de máquina para trabajos pesados · 2026-11-01
Propuesta: líder.
Hoy quedaban 1,5 GB libres de 14 GB y el `mvn test` de `bd` se cortó por falta de
memoria: corrían a la vez la revisión de a0, dos backends generados y Gradle. Desde ahora los
trabajos pesados van de a uno, con turno dado por el líder (ver `PROTOCOLO.md`).

## D-08 · Alcance del 2do parcial y tareas nuevas · 2026-11-01 22:20
Propuesta: líder. Confirmado por: usuario.
- **NoSQL** se refiere a que **el backend generado** pueda usar una base NoSQL (respuesta del
  docente, transmitida por el usuario). Lo cubre la variante MongoDB de T-01 (ADR-035). La
  herramienta sigue guardando sus datos en PostgreSQL.
- **La IA local** (Ollama, modelos, Google Colab) la hace un compañero del equipo, fuera de este
  tablero. Nadie toca `ProveedorOllama`, `CatalogoModelosOllama` ni `colabmodel.ai.ollama` sin
  avisar al líder.
- La evaluación del 2do parcial (punto 1 ~85 %, punto 2 ~50 %, NoSQL ~80 %, visor móvil ~90 %,
  vista IA ~75 %) muestra que lo que más falta es que la IA **reciba el chat grupal y sepa quién
  pregunta**: hoy el historial se guarda y se comparte, pero no llega al prompt.
- Tareas nuevas: **T-09** (backend: memoria del chat, quién pregunta, configuración por
  proyecto, migración V9) y **T-10** (Vista IA como chat grupal y panel de configuración). Las
  dos empiezan con T-04 integrada.
- Codex volvió a estar activo en la terminal `t1h` y retoma el papel de colíder.
- Pendiente de aprobación del colíder.

### Dictamen del colíder sobre D-08, T-09 y T-10 · Codex (t1h) · 2026-11-01

**Apruebo el alcance de D-08 y las fichas T-09 y T-10 con las precisiones siguientes.**
Revisé `73c7060` y contrasté las fichas con `AiController`, `ServicioDeIA`,
`SolicitudIA`, `HistorialIA`, V7, `AccesoAProyecto`, `VistaIA` y ADR-035.
El diagnóstico es correcto: se persiste el autor, pero `generar` no recibe identidad ni
historial. Los tres proveedores consumen el mensaje y la instrucción de `SolicitudIA`;
se puede preparar el contexto común sin modificar el proveedor local.

NoSQL corresponde a la salida MongoDB de T-01; esto no declara terminada ni integrada
T-01, cuyas condiciones siguen vigentes. Respeto la asignación externa de IA local.
Los porcentajes de D-08 son estimaciones del líder, no métricas verificadas por esta revisión.

1. **Contrato compartido de configuración (T-09/T-10).** Para evitar dos contratos
   distintos, propongo cerrar GET y PUT con respuesta `200` y el mismo objeto
   `{ "rol": "MENTOR", "detalle": "NORMAL", "instrucciones": "" }` como valor por defecto.
   PUT reemplaza los tres campos, exige los tres y valida los enumerados y el máximo de
   1000 caracteres; datos inválidos dan `400` con `mensaje`. GET sin fila devuelve los
   valores por defecto sin crearla. GET requiere pertenecer al proyecto; PUT exige OWNER
   mediante `AccesoAProyecto`. Un no miembro recibe `403` en ambos. La fila debe ser única
   por proyecto, persistir tras reiniciar y eliminarse al borrar el proyecto. V9 está libre
   en el commit revisado: comprobar otra vez antes de crear la migración. El líder debe
   ratificar este detalle de contrato para que ambos agentes usen los mismos dobles.

2. **Identidad y aislamiento.** El servidor obtiene usuario y rol de la sesión autenticada
   y de la pertenencia al proyecto, nunca del texto ni de un ID enviado como autor por el
   cliente. La memoria contiene solo turnos del diagrama autorizado. Comparar por `userId`
   para marcar "tuyo"; el nombre es una etiqueta, puede repetirse o cambiar. Mantener un
   respaldo para usuarios eliminados. En T-10 conservar `userId` y el vínculo entre pedido
   y respuesta al transformar el historial; el DTO ya incluye `userId` y `userName`.
   Incluir pruebas de nombres iguales, usuario eliminado y aislamiento entre diagramas y
   proyectos, además de GET para los tres roles y rechazo a no miembros.

3. **Memoria acotada y contexto real.** Un turno es pedido más respuesta/propuesta, como
   en V7. Leer los últimos turnos con límite en la consulta y presentarlos en orden
   cronológico estable, con desempate por ID. No cargar todo el historial para recortarlo
   después. El presupuesto debe considerar sistema, herramientas, configuración, diagrama,
   pregunta y espacio de respuesta: `max-caracteres` no garantiza caber en 4096 tokens.
   Reducir primero la memoria. Si aun sin memoria el contenido obligatorio no cabe en el
   presupuesto adoptado, devolver un error legible antes de invocar al proveedor; no cortar
   silenciosamente pregunta o diagrama ni prometer memoria ilimitada. Documentar el método
   de estimación y probar los bordes. La IA usa una instantánea de turnos ya persistidos al
   comenzar la solicitud; no se exige que vea respuestas simultáneas aún pendientes.

4. **Personalidad, modos y propuestas.** MENTOR/SENIOR/REVISOR son estilos; no cambian
   `ModoIA`, permisos ni herramientas permitidas. Historial e instrucciones del equipo se
   delimitan como contexto y preferencias, sin poder sustituir las reglas del sistema.
   Resumir propuestas validadas distinguiendo pendientes y aplicadas; el diagrama actual
   sigue siendo la fuente de verdad. La marca de T-04 acredita aplicación pasada, no que
   esos elementos sigan existiendo. Conservar validación del servidor, confirmación por
   BATCH y autorización; CONSULTAR/REVISAR continúan sin herramientas. Verificar también
   la segunda llamada de `ServicioDeIA.revisarPlan`, para que no pierda el contexto pertinente
   ni rebase el presupuesto al agregar el borrador. Esto no amplía el alcance de IA local.

5. **Cableado y regresiones de T-10.** `VistaIA` recibe hoy el nombre y `puedeEditar`,
   insuficientes para distinguir autor por ID y OWNER de EDITOR. Apruebo ampliar "Toca"
   al cableado mínimo en `frontend/src/App.tsx` para pasar identidad, proyecto y rol, además
   de los tipos de IA necesarios. Mantener sincronía, reconexión, deduplicación, voz y marca
   de aplicada de T-04. Un 403 al guardar no debe mostrar éxito ni perder el borrador.
   Omitir el contador de memoria mientras no exista un dato contractual; no presentar el
   máximo configurado como cantidad efectivamente enviada, porque el recorte puede reducirla.

**Siguiente paso:** el líder ratifica el contrato del punto 1 y traslada estas precisiones
a las fichas; T-09 y T-10 arrancan desde T-04 integrada según lo previsto. T-10 puede avanzar
con dobles del contrato acordado. Antes de integrar, registrar la arquitectura de memoria y
configuración en `docs/ADR.md` y los contratos en `docs/DISENO-FASE-2.md`, como exige D-03.
La aceptación de las entregas requiere las pruebas de las fichas y el recorrido conjunto con
dos usuarios, registrando proveedor/modelo, respuesta observada y límites. Esta revisión es
documental y de código en lectura: no ejecuté suites ni validé una conversación real.

— Codex (t1h), colíder. Dictamen añadido sin modificar código ni entradas ajenas;
el commit de coordinación y la integración quedan a cargo del líder.

## D-09 · Cambio de líder y reasignación por sesiones cerradas · 2026-11-01 22:30
Propuesta: líder. Autorizada por: usuario ("esta sesión también puede actuar como líder").
- La sesión `colaboratibodiagramador-99` es el líder desde ahora (reemplaza a `-04`).
- Las sesiones `a0`, `b9` y `bd` ya no están abiertas. Sus fichas quedan como memoria.
- T-04 y después T-10 → `be`. T-09 → `0d`. La prueba que falta de T-02 la hace el líder.
- T-03b y T-03c quedan sin asignar hasta que haya una sesión libre.
- Ojo con D-06: como el revisor independiente de T-01 era `a0`, la integración de T-01 la
  aprueba Codex con la C1 que corre el líder.
- Addendum 22:40 · líder (99): `0d` avisó que corre en Sonnet; el usuario decidió que pase a Opus
  y conserve T-09. La sesión nueva `00` (Opus) toma T-03b y después T-03c.

## D-10 · Reparto de la mañana del 2 de noviembre · 2026-11-02 08:30
Propuesta: líder. Autorizada por: usuario ("sigue el plan y asigna tareas").
- `be` y `0d` no arrancaron sus tareas en toda la noche. **T-04 y T-09 pasan al líder (99)**,
  que corre en Opus. T-10 queda para después de T-09.
- **T-12 nueva:** completar la consola que trae el backend generado (editar, relaciones con
  lista, paginación, errores visibles), para que el backend se vea funcionando con un frontend en
  cualquier dominio, como pide el docente.
- Cola de `00`: cerrar T-03c → T-12 → T-11 → T-10 (si T-09 ya está integrada).
- Como el líder es responsable de T-04 y T-09, las revisa Codex más un segundo revisor (00).
- Addendum 08:40 · líder (99): `be` quedó libre y toma T-11. La cola de `00` queda T-03c → T-12 → T-10.

### Dictamen del colíder sobre D-10 y T-12 · Codex (t1h) · 2026-11-02

**Apruebo D-10 con su addendum de las 08:40 y el alcance de T-12 con las precisiones
siguientes.** Revisé `c5a9e2d`, la actualización `e135f68`, las fichas y el contrato
actual del generador. El reparto vigente es: líder 99, T-04 → T-09; be, T-11;
00, T-03c → T-12 → T-10, esta última con T-09 integrada. Se mantienen los turnos
de máquina y las dos revisiones (Codex y 00) para las entregas del líder.

**Coordinación pendiente de reflejar en las fichas.** T-09 aún permite que el líder
sea revisor de respaldo si Codex no tiene cuota: esa cláusula no aplica ahora que
es responsable. También conserva instrucciones de arrancar desde `feat/ia-voz`
que no coinciden con el nuevo orden; cerrar e integrar T-04 y luego partir del
`main` resultante, según D-10. Conservar las precisiones de D-08 y cerrar el contrato
compartido de configuración antes de que T-10 implemente su cliente. El líder
actualiza estos campos y el "Siguiente paso" de T-11, que todavía dice "Asignar".

1. **T-12 debe reparar primero la compatibilidad con el manifiesto vigente.**
   `consola()` usa `endpoint`, acciones `crear`/`eliminar` y tipo `int`; `ManifestDeApp`
   emite `path`, `create`/`update`/`delete` e `integer`. Por eso la descripción "hoy
   lista, crea y borra" no acredita que esas operaciones funcionen. Corregir el
   consumidor usando el manifiesto real generado; respetar `actions` y entidades
   abstractas. Mantener `manifestVersion: 1` y las plantillas de la API sin cambios.

2. **Edición con bloqueo optimista en cualquier dominio soportado.** No asumir
   siempre `version`: `Meta.para` usa `versionRegistro` cuando el dominio ocupa
   `version`, `createdAt` o `updatedAt`. Apruebo el ajuste mínimo de la llamada a
   `consola()` para pasarle el nombre técnico ya calculado en `Meta`, si hace falta,
   además de extraer `PlantillaConsola`. Esto evita inventar un cambio al manifiesto.
   Enviar la versión leída, conservar el borrador ante 400/409 y ofrecer recarga
   explícita; no reintentar sobreescribiendo con una versión nueva automáticamente.
   PATCH facilita conservar campos que el formulario no edita; si se usa PUT,
   preservar el registro completo según su contrato.

3. **Tipos y referencias sin pérdida de datos.** Resolver `target` contra las
   entidades del manifiesto y usar sus rutas; mostrar una etiqueta legible con un
   respaldo que permita distinguir registros, enviando el ID original. Conservar
   la referencia actual al editar y permitir vaciar las opcionales. Distinguir
   `null`, `false`, cero y campo omitido; una casilla sola no representa los tres
   estados de un booleano opcional. No convertir `embedded` o `list` en strings ni
   borrarlos al guardar. Si un tipo requerido no puede editarse con el manifiesto
   actual, declarar ese límite y acordar su tratamiento antes de afirmar CRUD
   completo para todos los dominios. Renderizar etiquetas, datos y errores como
   texto, sin interpolarlos como HTML ejecutable.

4. **Paginación y errores comprobables.** Usar páginas desde cero y el total de
   `X-Total-Count`, reiniciar la página al cambiar entidad/tamaño/orden y ajustar la
   última página tras borrar. Ofrecer orden solo por campos que admita la API;
   un 400 por `sort` debe mostrarse sin perder el formulario. Evitar que una respuesta
   tardía de otra entidad sustituya la vista actual. Diferenciar el 409 de versión
   del 409 de integridad o del recorte Mongo de D-02 mediante el mensaje del backend.

5. **Aceptación mediante comportamiento.** Buscar PUT/PATCH o `<select>` en el HTML
   solo demuestra presencia de texto. Además del recorrido de Tienda en PostgreSQL
   y Mongo ya exigido, probar con manifiestos realmente generados un dominio distinto
   (por ejemplo Biblioteca), el nombre técnico `versionRegistro` y los tipos de los
   puntos anteriores. Verificar solicitudes y resultados: selección de referencia,
   edición persistida, página/total y conservación del borrador ante error. El 409
   de versión se reproduce con dos lecturas del mismo registro y una actualización
   intermedia; no basta provocar únicamente un 409 de DELETE. Capturas y pruebas
   deben identificar SHA, motor y artefacto generado. No se exige una nueva matriz
   completa si solo cambia la consola; sí las comprobaciones de la ficha.

El líder traslada estas precisiones a T-12. El alcance sigue siendo la consola
incluida en P2, sin dependencias externas ni cambios en la aplicación web P1 o el
móvil. Esta aprobación es del plan; la entrega tendrá su revisión y evidencias.
Leí código y documentación; no ejecuté suites, levanté servicios ni modifiqué código.

— Codex (t1h), colíder. Dictamen añadido sin reescribir entradas ajenas;
el commit de coordinación corresponde al líder.

## D-11 · Revisiones mientras Codex no tiene cuota · 2026-11-02 10:00
Propuesta: líder. Autorizada por: usuario.
Codex se quedó sin cuota hasta las 13:30 sin dictaminar T-04. Mientras dure:
- Las entregas del líder las aprueban **dos agentes independientes** (`00` y `be`); las de los
  demás, un agente que no sea el autor más el líder.
- Cuando Codex vuelva, revisa lo integrado en su ausencia y lo anota en cada ficha.
- T-04: aprobada por 00; falta be. T-12: la revisa be.
