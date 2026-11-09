# Protocolo de coordinación entre agentes

Si eres un agente (Claude Code, Codex u otro) y acabas de arrancar o de hacer `/clear`,
**lee esto primero**, después `TABLERO.md` y después la ficha de tu tarea en `tareas/`.
Si no tienes tarea, avisa al líder y no toques nada.

## Roles

| Rol | Quién | Terminal Herdr | Qué hace |
|---|---|---|---|
| Líder / coordinador | Claude Code, sesión `colaboratibodiagramador-99` (antes `-04`) | ver `TABLERO.md` | Integra a `main`, mantiene el tablero, asigna tareas junto al colíder |
| Colíder | Codex | `t1h` | Revisa las tareas terminadas, evalúa calidad y aprueba decisiones importantes junto al líder |
| Agentes | Las demás sesiones | ver `TABLERO.md` | Hacen su tarea y mantienen su ficha al día |

## Cómo se comunican

- Entre sesiones de Claude Code: `SendMessage` con el nombre de la sesión.
- Con Codex o desde Codex: `hc send <terminal> "<texto>"`
  (`C:\Users\DIEGO\AppData\Local\Programs\herdr-core\hc.exe`).
- Todo aviso importante (empecé, terminé, me trabé, necesito ayuda) **también** queda
  escrito en la bitácora de la ficha. El mensaje se pierde con un `/clear`; la ficha no.

## Reglas

1. **Asignar.** El líder y el colíder crean la ficha `tareas/T-XX.md` y la fila en
   `TABLERO.md`. La tarea dice modelo y complejidad (ver abajo).
2. **Trabajar.** Cada agente trabaja solo en su rama y su worktree. Nadie hace `push` ni
   merge a `main`: eso lo hace el líder. Commits chicos, mensajes en español. No se toca
   `README.md` (lo integra el líder).
3. **Mantener el contexto.** Al empezar, en cada avance que valga la pena y **antes de
   cortar**, el responsable agrega una línea a la bitácora de su ficha y actualiza
   "Siguiente paso". Otro agente tiene que poder retomar la tarea leyendo solo la ficha.
   **Hay una sola copia de las fichas**: la de la carpeta principal,
   `C:\Users\DIEGO\PROYECTOS\ColaboratiboDiagramador\docs\coordinacion\`. Se edita ahí,
   por ruta absoluta, aunque trabajes en otro worktree. No se copia a las ramas. El líder
   la commitea en `main` y es el único que cambia `TABLERO.md`.
4. **Terminar.** El responsable pone la ficha en `en revisión`, completa "Entrega"
   y avisa al colíder y al líder. La entrega registra la evidencia: SHA, comando, entorno,
   resultado y pruebas omitidas. Compilar, probar con dobles (mocks) y probar contra una
   base real son evidencias distintas y se anotan por separado.
5. **Evaluar.** El colíder revisa y escribe su evaluación en la ficha. El líder integra
   solo si los dos están de acuerdo. Si no, la ficha vuelve a `en curso` con lo que falta.
   **Nadie aprueba su propia entrega**: si el responsable es el colíder o el líder, la
   revisa el otro más un segundo revisor independiente.
6. **Ayudar.** Si una tarea es grande, el líder o un agente libre pueden sumarse. El
   responsable anota en "Ayudantes" quién, qué parte y con qué objetivo, para no pisarse.
7. **Quedar libre.** El agente avisa al líder. El líder y el colíder le asignan algo
   pendiente del tablero o una mejora.
8. **Decisiones.** Lo que cambia un contrato, la arquitectura o el alcance se acuerda entre
   líder y colíder y se anota en `DECISIONES.md` (y en `docs/ADR.md` si es de arquitectura).
   Ningún agente cambia un contrato por su cuenta. Un cambio de contrato puede quedar en una
   rama para revisarlo, pero no se integra sin decisión conjunta y sin actualizar el ADR y el diseño.
9. **Documentos compartidos.** En `DECISIONES.md`, `EVALUACION.md` y las bitácoras se agregan
   entradas firmadas; nunca se reescribe lo que escribió otro.

## Turnos de máquina

La máquina tiene 14 GB. Los trabajos pesados (`mvn test` completo, Gradle o compilar un APK,
levantar backends generados, la matriz de proyectos generados) van **de a uno**, y el turno lo da
el líder. Antes de lanzar uno, pide turno. Al terminar, apaga lo que levantaste (backends,
demonios de Gradle) y avisa. Las pruebas focalizadas (`-Dtest=...`), `npm test` y
`flutter test` no necesitan turno.

## Modelo según complejidad

| Complejidad | Ejemplos | Modelo |
|---|---|---|
| Alta | Algoritmos, generador de código, merge, seguridad, concurrencia | Opus, o Codex para revisión |
| Media | Interfaz web, app móvil, integración entre piezas | Sonnet |
| Baja | Documentación, ajustes mecánicos, pruebas simples, integrar ramas ya revisadas | Haiku o Sonnet |

## Pruebas que no pueden quedar en rojo

`mvn test` (backend), `npm test` (frontend) y `flutter test` (mobile), en lo que toque cada uno.
