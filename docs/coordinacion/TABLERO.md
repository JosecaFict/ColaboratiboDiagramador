# Tablero

Lo mantiene el líder en `main`. Estados: `pendiente` · `en curso` · `en revisión` · `integrada` · `bloqueada`.

## Sesiones

| Sesión | Herramienta | Terminal | Tarea actual |
|---|---|---|---|
| colaboratibodiagramador-99 | Claude Code | ? | Líder |
| Codex | Codex | t1h | Colíder (volvió); repasa T-04 y T-12, después T-10 |
| colaboratibodiagramador-be | Claude Code | ? | Libre |
| colaboratibodiagramador-0d | Claude Code | ? | Sin tarea (no arrancó; D-10) |
| colaboratibodiagramador-00 | Claude Code | ? | Libre |
| a0, b9, bd | Claude Code | — | Sesiones cerradas (D-09). Sus fichas quedan como memoria |

**Turno de máquina (D-07):** libre.

## Tareas

| Id | Tarea | Complejidad | Modelo | Responsable | Rama | Estado |
|---|---|---|---|---|---|---|
| T-01 | Cerrar el generador para producción y MongoDB (cubre el punto NoSQL, D-08) | Alta | Codex / Opus | líder (D-06) · revisa a0 | `fix/generador-revision` | integrada (`d539c15`) |
| T-02 | Integrar el último commit del verificador CRUD | Baja | Sonnet | líder (D-09, retoma de a0) | `feat/verificador-crud` | integrada (`114e10d`) |
| T-03a | Asistente móvil conectado (crear y listar, sin voz) | Media | Sonnet | b9 | `feat/asistente-movil` | integrada (`cce3d27`) |
| T-03b | Proyecto Android y APK | Media | Sonnet | 00 (retoma de b9) | `feat/android-movil` | integrada (`24da966`) |
| T-03c | Voz en el asistente móvil | Media | Sonnet | 00 | `feat/voz-movil` | integrada (`0d750f6`); repetir en el teléfono el envío automático tras el silencio en T-13 |
| T-04 | Vista IA: sincronía, marca de aplicada y voz web | Media | Sonnet | líder 99 (D-10) | `feat/ia-voz` | integrada (`f1d846c`); Codex halló 2 P2, se corrigen en T-14 |
| T-05 | XMI con el Enterprise Architect del docente | Baja | Sonnet | sin asignar | por crear | bloqueada (falta el archivo) |
| T-06 | Subir `main` a GitHub | Baja | líder | líder | `main` | bloqueada (falta autorización del usuario) |
| T-07 | Evaluación conjunta del proyecto | Alta | líder + colíder | líder y colíder | `main` | terminada: D-04 y D-05 aprobadas; D-02 acordada con condiciones |
| T-08 | README y SAD al día con el estado real | Baja | líder (redacta 00) | 00 | `docs/estado-2do-parcial` | integrada (`fef6395`); se actualizan las filas al integrar T-03c, T-09, T-10 y T-11 |
| T-09 | IA con memoria del chat grupal, sabe quién pregunta y es configurable | Alta | Opus | líder 99 (D-10) | `feat/ia-memoria` | integrada (`81c5dd3`) |
| T-10 | Vista IA como chat grupal y panel de configuración | Media | Sonnet | be | `feat/ia-chat-grupal` | integrada (`8213dfa`) |
| T-11 | Asistente móvil: la respuesta a una pregunta pendiente se pierde o se malinterpreta | Media | Sonnet | be | `fix/asistente-pendiente` | integrada (`08a8fb3`); caso B dictado y «cancelar» por voz, en T-13 |
| T-12 | Consola del backend generado: CRUD completo para cualquier dominio | Media | Sonnet | 00 (después de T-03c) | `feat/consola-generada` | integrada (`8b63ab0`); aprobada también por Codex |
| T-13 | Ensayo general de la demo, de punta a punta | Media | Opus | líder + usuario | `main` | pendiente: listo para ensayar (todo integrado); necesita al usuario |
| T-14 | Vista IA: dos correcciones de T-04 (historial atrasado y doble aplicar) | Media | Sonnet | be (junto con T-10) | `feat/ia-chat-grupal` | integrada (`8213dfa`) |
