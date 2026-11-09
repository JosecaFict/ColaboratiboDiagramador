# Evaluación del proyecto (T-07)

Ronda 1: el líder y el colíder evalúan cada uno por su lado, sin leer la sección del otro.
Ronda 2: se comparan, se anotan las decisiones en `DECISIONES.md` y se escribe la evaluación acordada.

Para cada área: estado real (lo comprobado, no lo prometido), riesgos y qué se puede mejorar.
Áreas: colaboración en tiempo real · modelado y vistas · IA · visión · XMI · generador ·
versiones · web · móvil · pruebas y CI · documentación · preparación para la demo.

## Ronda 1 · Líder (Claude Code, 04)

Hecha el 2026-11-01 sobre `main` en `facb883`. Las pruebas se corrieron, no se copiaron de los informes.

| Área | Estado comprobado | Riesgo / mejora |
|---|---|---|
| Pruebas | Frontend: 251 pasan y 6 se saltan (`vitest`). Móvil: 101 pasan (`flutter test`). Backend: 976 pruebas, 0 fallos, 2 saltadas (`mvn test`) | Las 6 que se saltan son E2E contra un backend real: nadie las corre por defecto |
| CI | `.github/workflows` corre `mvn verify`, el frontend y `flutter test` (corregido en la ronda 2: el CI sí corre `flutter test`) | **`main` tiene 25 commits sin subir, así que el CI no vio nada de la Fase 2** |
| Colaboración, modelado, vistas, deshacer, roles | Integrado y con pruebas (Fase 0/1, CU-01/04/05 cumplen el criterio del LCA) | Bajo |
| IA | Cuatro modos; el hilo **ya se guarda** (`V7__historial_colaborativo_ia.sql`, `HistorialIA`, `VistaIA.tsx`) | **El README dice que falta y no es así**: hay que actualizarlo. Falta la voz |
| Visión e importar/exportar XMI | Implementados, con pruebas | El XMI no pasó por un Enterprise Architect real (T-05) |
| Generador | En `main` está solo la versión de la Fase 1. Lo de producción y Mongo está en T-01, sin integrar | **Es el riesgo principal**: la web ya ofrece el selector PostgreSQL/MongoDB y la pestaña Documental, pero el backend de `main` no los atiende. Hoy la web promete algo que `main` no hace |
| Versiones (backend y web) | Integradas, con E2E opcional contra un backend real | Medio: el merge a tres bandas es complejo y su E2E no corre en CI |
| Visor móvil en vivo | Integrado, con pruebas y probado por SockJS contra el backend real | Bajo |
| Asistente móvil (P3) | El cliente HTTP contra P2 existe (`cliente_backend.dart`), pero la pantalla del asistente es un esqueleto: no lo usa, no hay voz (`speech_to_text` está comentado) y **no existe `mobile/android/`** | **Alto para la demo**: la app no se puede instalar en un teléfono Android |
| Documentación | Completa, pero el README tiene secciones viejas (IA, "Pendiente esta semana") | Hay que actualizarla al integrar T-01 |

**Prioridades que propongo:**
1. Cerrar e integrar T-01, porque la web ya depende de esa parte.
2. Crear la plataforma Android y conectar el asistente móvil al motor de intenciones y al cliente HTTP (T-03).
3. Subir `main` para que el CI valide la Fase 2 (T-06, necesita la autorización del usuario).
4. Voz en la web y en el móvil (T-04 se reduce a esto).
5. Corregir el README.

## Ronda 1 · Codex (colíder, t14) · 2026-11-01

Evaluación independiente: escrita sin abrir ni leer la ronda del líder. Base de
inspección: `main` en `facb883` y T-01 en `fix/generador-revision` (`a7a9645` más
cambios sin commit). Evidencia: código, pruebas presentes, CI, diseño y estado Git.
No afirmo haber ejecutado en esta ronda las suites de frontend/móvil ni una demo
completa. Las pruebas nuevas de T-01 están en ejecución y se registran en su ficha.

### Estado real por área

| Área | Estado observado | Riesgo y siguiente comprobación |
|---|---|---|
| Núcleo y colaboración P1 | Metamodelo, bus, leases, roles, undo y vistas están implementados; existen pruebas específicas de concurrencia, permisos y semántica. | No confundir presencia de pruebas con métricas medidas. Repetir recorrido de tres sesiones con reconexión, revocación de rol y cambios simultáneos; registrar p95 y recuperación. |
| Generador PostgreSQL P2 | La ampliación de producción está en la rama de T-01, todavía sin integrar en main. Se inspeccionó el arreglo del ordenamiento malformado y su prueba ejecutable con MockMvc. | La web ya ofrece opciones de fase 2, pero la entrega del generador sigue pendiente. Exigir compilación de cada dominio, arranque y recorrido HTTP, además de tests del emisor. |
| Generador MongoDB P2 | Implementado en T-01, con correcciones heredadas sobre integridad, índices únicos y composición. | Bloqueo de integración: los rechazos preventivos 409 para traslado y cascadas entre documentos cambian alcance. Requieren decisión conjunta, pruebas sobre Mongo real y documentación de las limitaciones. Los mocks no demuestran índices efectivos ni integridad concurrente. |
| Versiones y web | Paquete `versiones`, V8, historial/diff/ramas/merge y UI existen en main; hay pruebas de API, vistas y E2E. | Validar juntos backend y web, incluyendo locks, seq obsoleto, permisos y conflictos. La existencia de tests `backendReal` no acredita que se hayan ejecutado en esta ronda. |
| Vista Documental | UI integrada; depende del frente de generación pendiente. | Probar selector PostgreSQL/Mongo, resumen, advertencias y ZIP con la misma revisión del backend antes de declarar completa la fase. |
| Visor móvil P1 | Cliente de herramienta, sincronizador, aplicación de operaciones, almacenamiento local, dibujo y pruebas están en main. | Es un quinto espejo del contrato: requiere fixtures comunes Java/TS/Dart y prueba de red interrumpida. No equivale al asistente que consume P2. |
| Asistente móvil P3 / Android | Motor de intenciones, manifiestos y cliente HTTP existen, pero `PantallaAsistente._enviar` solo añade el texto fijo de Fase 0. No hay archivos Android versionados en main. | T-03 debe conectar realmente diálogo, manifiesto y cliente; validar crear/listar con referencias antes de voz y APK. El README presenta una conversación funcional que este main no implementa en esa pantalla. |
| Vista IA | Ya existen `HistorialIA`, repositorio, V7, endpoints de historial y carga inicial desde `VistaIA.tsx`. | T-04 no debe recrear persistencia ni otra migración equivalente. Falta verificar sincronización entre pestañas y confirmación de aplicación tras aceptación del BATCH: la UI envía la marca aplicada por una llamada separada. Voz no está conectada. |
| Visión e interoperabilidad | Hay lectores SQL/XMI, exportador EA/estándar, propuestas revisables y pruebas canónicas/tolerancia. | T-05 sigue requiriendo evidencia del EA real del docente. Reimportar con nuestro lector no prueba compatibilidad externa. Documentar versión, archivo y resultado de apertura. |
| CI y documentación | CI configura PostgreSQL de prueba, backend verify, frontend test/build y Flutter test. Existen SAD, ADR, diseño y fichas. | El pipeline no construye APK ni demuestra toda la matriz de proyectos generados Mongo/PostgreSQL con bases reales. README y SAD mezclan hitos históricos y estado vigente; corregir desde evidencia. |

### Riesgos prioritarios y mejoras propuestas

1. **Integridad de datos de P2:** cerrar los cinco hallazgos de T-01 y acordar su
   semántica antes del merge. Mantener separados resultados de compilación, mocks,
   ejecución real y concurrencia. No llamar listo para producción solo por compilar.
2. **Asistente demostrable:** dividir T-03 en conexión conversacional (crear/listar,
   validación y referencias), empaquetado Android y voz. Una prueba en dispositivo
   con un dominio nuevo debe demostrar consumo del manifiesto sin cambios de código.
3. **Alcance real de T-04:** auditar la persistencia ya existente, agregar sincronía
   y pruebas de autorización, y marcar propuestas aplicadas solo al confirmarse su
   BATCH. Definir después la entrada de voz y sus errores. Evitar duplicar V7.
4. **Evidencia reproducible:** registrar SHA, comando, entorno, resultado y omitidos
   por tarea. Añadir a CI una matriz explícita de generación y package, pruebas de
   contrato P2/P3 con bases aisladas y build Android cuando exista el proyecto.
5. **Contrato entre lenguajes:** usar casos compartidos para comandos, roles y
   manifiestos. Cubrir actualización, eliminación, BATCH, huecos de seq y rechazos;
   el nuevo espejo Dart aumenta el costo de cambios sin fixtures comunes.
6. **Documentación verificable:** el líder debe actualizar README y estado de SAD
   después de integrar, separar implementado/probado/pendiente y evitar porcentajes
   sin denominador. Conservar los recortes declarados y la dependencia externa EA.
7. **Integración:** T-02 tiene un commit pendiente (`1e9cbfa`) sobre PATCH/versiones
   y cascadas; revisarlo contra el contrato decidido en T-01. La política de aceptar
   cascada correcta no sustituye la prueba de 409 cuando hay referencia restrictiva.

Propongo priorizar cierre verificable de T-01 + T-02, asistente funcional T-03 y
ajuste de T-04; T-05 depende del archivo externo. T-06 conserva el control del líder.
No propongo expandir alcance hasta cerrar estos recorridos. La aprobación de D-01
está registrada; los recortes Mongo están propuestos, todavía sin aprobación final.

## Ronda 2 · Evaluación acordada · 2026-11-01
Redacta: líder. Aprobación del colíder: pendiente (ver D-02 a D-05).

**Coincidimos en:**
- La Fase 0/1, las versiones, la web y el visor móvil están integrados y tienen pruebas.
  En `facb883` pasan 976 pruebas del backend, 251 del frontend y 101 del móvil, sin fallos.
- **Riesgo 1, la integridad de P2 (T-01):** la web ya ofrece PostgreSQL/MongoDB y Documental,
  pero `main` no lo atiende. Hay que cerrarlo con evidencia real, no solo con mocks.
- **Riesgo 2, el asistente móvil P3:** la pantalla todavía es el esqueleto de la Fase 0 y no
  existe el proyecto Android. Es lo que más compromete la demo.
- **Vista IA:** el historial ya persiste (V7). T-04 no lo rehace: audita la sincronía y la
  marca de "aplicada", y agrega la voz.
- **README y SAD** mezclan hitos viejos con el estado actual. Los corrige el líder después de
  integrar, separando lo implementado, lo probado y lo pendiente.
- **Evidencia por entrega:** SHA, comando, entorno, resultado y pruebas omitidas, sin mezclar
  compilar, probar con dobles y probar contra una base real (D-03).

**Corrección del líder:** el CI sí corre `flutter test`. Lo que no hace es compilar un APK ni
probar la matriz de proyectos generados.

**Orden de trabajo acordado:** T-01 y su segunda revisión → T-02 → T-03a/T-03b → T-04.
T-05 espera el archivo del docente y T-06 la autorización del usuario.

