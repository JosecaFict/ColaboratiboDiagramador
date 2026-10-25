# Documento de Arquitectura de Software (SAD)

**ColabModel** · v1.0 · 07/09/2026 · Hito RUP: producido en *Inicio*, se
congela en **LCA**.

> Este archivo es el índice operativo. El detalle vive en los documentos
> hermanos y en el código, que es la fuente de verdad de los contratos.

## Estándares de referencia

| Estándar | Aplicación |
|---|---|
| **ISO/IEC/IEEE 42010:2022** | Estructura: stakeholders, concerns, vistas, decisiones |
| **RUP / PUDS** | Proceso, fases, hitos y artefactos. Prescribe este documento |
| **4+1 (Kruchten)** | Vistas Lógica, Procesos, Desarrollo, Física, Escenarios |
| **C4 (Brown)** | Niveles: Contexto, Contenedores, Componentes |
| **ISO/IEC 25010:2023** | Atributos de calidad y escenarios medibles |
| **ISO/IEC 25012** | Calidad de datos |
| **UML 2.5.1 (OMG formal/17-12-05)** | Notación |
| **ADR (Nygard)** | Registro de decisiones → `ADR.md` |

## Los tres pilares

| Pilar | Se materializa en | Se mide con |
|---|---|---|
| **CALIDAD** | Escenarios EC-01…EC-13 · ArchUnit · validación en 3 capas · verificación por compilación | Cobertura `modelcore` > 70 % · 7/7 modelos compilan · p95 < 300 ms · 0 ciclos |
| **PRODUCTIVIDAD** | Contratos primero → 4 frentes paralelos · un solo `CommandBus` · CI · 5 capas generadas | **10 h → ~25 min** para un dominio de 8 entidades |
| **INNOVACIÓN** | Camino único (ADR-002) · IA restringida (ADR-014) · Informe de Mapeo · app por metadatos (ADR-011) · degradación (ADR-012) | Cada una trazable a un ADR |

## Escenarios de calidad (extracto verificable)

| ID | Estímulo | Medida |
|---|---|---|
| EC-01 | Dos usuarios editan la misma entidad | Exactamente 1 escritor; aviso < 300 ms |
| EC-02 | Crear una clase con 20 conectados | **p95 < 300 ms** |
| EC-03 | Generar desde 10 entidades | **< 90 s** |
| EC-04 | Suite canónica en CI | **7/7 compilan** |
| EC-05 | 5 operaciones offline + reconexión | **0 pérdidas, 0 duplicados** |
| EC-08 | Desconexión de 30 s | Resync **< 5 s** |
| EC-10 | Backend de dominio nuevo | **0 líneas** modificadas en P3 |
| EC-11 | VIEWER intenta escribir | **100 %** rechazados |
| EC-12 | Análisis estático | **0 ciclos** |
| EC-13 | Editar en Clases | Chen refleja; divergencia **imposible** |

## Vista de Procesos — exclusión mutua por niveles

| Nivel | Qué | Estrategia | Dónde |
|---|---|---|---|
| 1 · Estructura | crear/eliminar | sin lock; UUID de cliente + tombstones | `DiagramCommandBus` |
| 2 · Semántica | nombre, atributos, cardinalidades | **lease por entidad**, TTL 30 s | `LockManager` |
| 3 · Geometría | x, y, tamaño | LWW, throttle 50 ms | cliente |
| 4 · Presencia | cursores | efímero, **nunca toca el modelo** | `PresenceMessage` |

**Serialización:** un ejecutor de un solo hilo por `diagramId`.
**Orden global:** `seq` monotónico del servidor; hueco detectado → snapshot.

## Plan RUP

| Fase | Días | Hito | Criterio de salida |
|---|---|---|---|
| **Inicio** | 1 | **LCO** | Contratos escritos; riesgos R-01/R-04/R-10 con evidencia |
| **Elaboración** | 2–6 | **LCA** | **CU-01, CU-04, CU-05 funcionando** extremo a extremo |
| **Construcción** | 7–14 | **IOC** | 20 requisitos implementados; métricas medidas |
| **Transición** | 15–16 | **PR** | Ensayo con 3 dominios; plan B grabado; APK en 2 teléfonos |

**Orden de recorte declarado por adelantado** (para no decidirlo a las 2 AM):
SLM on-device → reificación asistida → tests generados → stubs de métodos →
export Chen → vista Lógica reducida → export XMI → IA local del móvil.

Todo lo que esté por debajo del 5º requiere decisión del equipo completo y
debe comunicarse al docente. **Recortar en silencio y que se note en la demo
es peor que declarar el recorte.**

## Documentos hermanos

- `ADR.md` — las decisiones con su justificación (ADR-001 a ADR-021)
- `REGLAS-MAPEO.md` — las 12 reglas, tipos, ambigüedades, normalización
- `CASOS-DE-USO.md` — CU-01, CU-04 y CU-05: el criterio de salida del LCA, con evidencia
- `../canonical-models/README.md` — la suite de regresión del generador
