# Análisis funcional de ColabModel y comparación con «Diagramas UML colaborativos»

Análisis hecho sobre el **código fuente** de ColabModel (rama `fix/mongo-decimales` = `main` + arreglo de decimales en MongoDB), no sobre capturas.
Algunas mejoras viven en ramas locales sin unir a `main` (por ejemplo `feat/pendientes-rapidos`); aquí no se cuentan.

Leyenda: **SÍ** = existe y funciona · **PARCIAL** = existe una parte · **NO** = no existe · **MÁS** = lo tenemos y el otro proyecto no lo muestra.

---

## 1. Descripción general

ColabModel es una herramienta CASE colaborativa para diseñar el modelo de datos de un sistema en UML y generar a partir de él un backend Spring Boot. Se organiza en **tres piezas**:

| Pieza | Qué es | Tecnología |
|---|---|---|
| **P1 · La herramienta** | Web colaborativa: diagramación, IA, importación/exportación, generador | Spring Boot 3.5 + Java 21 (servidor) · React 18 + Vite + TypeScript (cliente) |
| **P2 · El backend generado** | Proyecto que P1 escribe solo y entrega en un ZIP | Spring Boot 3.5 + Java 21 · PostgreSQL **o MongoDB** |
| **P3 · La app móvil** | APK de Android: asistente por voz contra P2 y visor en vivo del diagrama de P1 | Flutter |

Flujo: crear proyecto → modelar (a mano, IA, voz, foto, XMI/SQL/JSON) → revisar vistas Lógica y Física → verificar y confirmar el esquema en PostgreSQL → generar ZIP → ejecutar con `mvn spring-boot:run` → usarlo desde la consola web incluida o desde la app Flutter.

Diferencia clave con el otro proyecto: el otro describe un monorepo Node.js (npm workspaces). En ColabModel **el servidor de la herramienta también es Spring Boot**, y el modelo pasa por cuatro vistas (Clases, Lógico, Físico, Documental) antes de generar.

---

## 2. Comparación módulo por módulo

### 2.1 Autenticación

| Función del otro proyecto | ColabModel | Detalle |
|---|---|---|
| Registro (nombre, correo, contraseña) | **SÍ** | `AuthController` `/register`; validación en cliente y servidor |
| Inicio de sesión | **SÍ** | JWT; sesión por pestaña (`sessionStorage`) |
| Código de recuperación / recuperar contraseña | **NO** | Declarado fuera de alcance en `PerfilController` |
| — | **MÁS** | Perfil editable y cambio de contraseña (`/perfil/password`) |

### 2.2 Asistente de bienvenida y ayuda

| Función | ColabModel | Detalle |
|---|---|---|
| Recorrido de 6 láminas (onboarding) | **NO** | No hay tutorial ni primer ingreso guiado |
| Panel de ayuda consultable | **NO** | La guía está en `README.md` y `docs/`, no dentro de la app |
| Ayuda sin conexión con documentación local / Ollama | **NO** | La IA (Ollama) responde sobre el **diagrama**, no sobre el manual de la herramienta |

### 2.3 Panel de proyectos

| Función | ColabModel | Detalle |
|---|---|---|
| Crear, listar, abrir, eliminar | **SÍ** | `MisProyectos`; eliminar pide confirmación y avisa a los miembros al instante |
| Compartir / miembros | **SÍ** | Panel Miembros por correo, con rol |
| Métricas (clases, relaciones, cambios, errores) | **NO** | Solo muestra cuántos diagramas tiene cada proyecto |
| «Origen del modelo» (manual / foto / voz %) | **NO** | El dato existe (cada comando guarda su `Origin`: manual, IA, voz, importación…), pero no se resume en el panel |
| — | **MÁS** | Varios diagramas por proyecto; el proyecto aparece/desaparece en vivo al agregar o quitar a alguien |

### 2.4 Editor UML

| Función | ColabModel | Detalle |
|---|---|---|
| Lienzo con clases y relaciones | **SÍ** | Canvas SVG propio (no React Flow), zoom al cursor, quiebres en las líneas |
| Tipos de relación | **SÍ** | Asociación, agregación, composición, generalización (herencia) y dependencia, más **clase asociativa** |
| Atributos, métodos, visibilidad, rangos | **SÍ** | Panel de propiedades; anotaciones ER (multivaluado, derivado, clave parcial, entidad débil) |
| Indicador de conexión «En directo» | **SÍ** | Estado conectado / resincronizando |
| — | **MÁS** | **Cuatro vistas** del mismo modelo: Clases, Lógico (12 reglas de mapeo), Físico (DDL editable) y Documental (MongoDB). Cardinalidad mostrable en notación UML o Chen |

### 2.5 Interfaz en el teléfono (web)

| Función | ColabModel | Detalle |
|---|---|---|
| Editor web adaptado al móvil | **PARCIAL** | Hay reglas `@media` en algunos paneles (IA, versiones, acceso), pero el editor está pensado para escritorio |
| Menú de acciones agrupado para pantallas chicas | **NO** | La barra de herramientas es horizontal de escritorio |
| — | **MÁS** | En el teléfono existe la **app Flutter** con un **visor en vivo** del diagrama (solo lectura) que recibe los cambios por STOMP |

### 2.6 Colaboración en tiempo real

| Función | ColabModel | Detalle |
|---|---|---|
| Edición simultánea sincronizada | **SÍ** | STOMP; el servidor ordena las operaciones con un `seq` contiguo y todos re-aplican el mismo historial |
| Evitar que se pisen cambios | **SÍ** | Bloqueo por elemento: la clase que otro edita se ve en rojo con su nombre |
| Cursores de colores por participante | **PARCIAL** | El canal de presencia existe en servidor y cliente, pero la web **no dibuja** los cursores de los demás |
| Invitar / ver miembros | **SÍ** | Con roles Propietario, Modelador, Observador; el servidor rechaza lo que el rol no permite (`FORBIDDEN`) |
| Historial de cambios | **SÍ** | Además: deshacer/rehacer por pestaña y **versiones tipo Git** (ramas, diferencias y fusión) |
| Tablón / comunicación interna | **PARCIAL** | No hay chat entre personas; el **chat de la IA es compartido** y en vivo, con el nombre de quién pregunta |

### 2.7 Importación y exportación

| Función | ColabModel | Detalle |
|---|---|---|
| Importar XMI | **SÍ** | Lee el dialecto de Enterprise Architect (XMI 2.1, incluida la posición de las cajas) y UML DI (Papyrus, MagicDraw). Muestra una propuesta para revisar antes de aplicar |
| Exportar XMI | **SÍ** | XMI 2.5.1 estándar **y** el dialecto XMI 2.1 de EA |
| Compatibilidad comprobada con EA real | **PARCIAL** | Probado con pruebas automáticas; falta el archivo de Enterprise Architect del docente |
| — | **MÁS** | También importa **SQL (DDL)** y JSON |

### 2.8 Modelado desde fotografía

| Función | ColabModel | Detalle |
|---|---|---|
| Foto → clases y relaciones | **SÍ** | Visión por computadora propia en Java: binarización (Otsu/Sauvola), enderezado, rectas por Hough, lectura de texto por plantillas |
| Revisar y aceptar | **SÍ** | La detección es una propuesta; lo confirmado se aplica como un solo lote |
| Necesita clave de API | **MÁS** | El nivel básico funciona **sin API**; Gemini, Claude o un modelo local solo mejoran la lectura del texto |

### 2.9 Edición por voz

| Función | ColabModel | Detalle |
|---|---|---|
| Dictar un cambio al diagrama | **SÍ** | Botón **Dictar** en la pestaña IA (Chrome/Edge); la IA arma una propuesta que se aplica de una vez |
| Confirmación hablada antes de acciones críticas | **NO** | No hay salida de voz (TTS); `flutter_tts` está comentado en `pubspec.yaml` |
| Voz en el teléfono | **SÍ** | El asistente Flutter dicta con `speech_to_text` en español; envío automático tras ~2 s de silencio y «cancelar» por voz (pendiente de re-probar en el teléfono) |

### 2.10 Revisor del modelo

| Función | ColabModel | Detalle |
|---|---|---|
| Errores bloqueantes vs advertencias | **PARCIAL** | El panel Generar lista advertencias («qué quedó sin soportar»); no hay un validador que **impida** generar con una lista de errores por gravedad |
| Corrección automática | **PARCIAL** | La IA en modo **Revisar** señala problemas y en **Modificar** propone correcciones aplicables; no hay un botón «arreglar todo» determinista |
| — | **MÁS** | Panel de **ambigüedades** (decisiones de mapeo con valor por defecto), **Verificar en PostgreSQL** (ejecuta el DDL y lo deshace) y **Confirmar esquema** |

### 2.11 Organización en módulos

| Función | ColabModel | Detalle |
|---|---|---|
| Agrupar clases en módulos → subpaquetes Java | **NO** | Todas las clases van a un único paquete del proyecto generado |

### 2.12 Generación del backend

| Función | ColabModel | Detalle |
|---|---|---|
| Controller, Service, Repository, Entity, DTO | **SÍ** | 5 capas + DTO |
| Base de datos | **SÍ / MÁS** | PostgreSQL (con Flyway) **o MongoDB** |
| Previsualizar antes de descargar | **PARCIAL** | Muestra entidades, advertencias y la **lista** de archivos; no el contenido de cada archivo |
| ZIP + `mvn spring-boot:run` | **SÍ** | 9 proyectos generados compilan y pasan sus pruebas |
| Diagrama de comunicación del backend generado | **NO** | |
| — | **MÁS** | Paginación, validación, bloqueo optimista (409), errores uniformes, perfiles `dev` (H2, sin instalar nada) / `postgres` / `prod`, Dockerfile, docker-compose, datos de ejemplo, Swagger y una **consola web** que sirve para cualquier dominio |

### 2.13 Funcionamiento sin conexión

| Función | ColabModel | Detalle |
|---|---|---|
| Editor web como PWA / offline | **NO** | No hay service worker ni manifiesto; al reconectar, el editor se resincroniza, pero no se edita sin red |
| Asistente Flutter registra sin red y sincroniza | **NO** | Recorte declarado; las dependencias (`sqflite`, `connectivity_plus`, `workmanager`) están comentadas |
| Visor móvil sin red | **SÍ** | Guarda el último diagrama, avisa «Sin conexión» y al volver pide solo lo que faltó (`sinceSeq`) |

### 2.14 Asistente Flutter

| Función | ColabModel | Detalle |
|---|---|---|
| Interactuar por voz con el backend generado | **SÍ** | Diálogo escrito o por voz; pide de a un campo y, para una relación, lista las opciones numeradas en vez de pedir un UUID |
| Aprende el dominio | **MÁS** | Lee el `app-manifest.json` del backend generado: el mismo APK sirve para cualquier dominio |
| Probado en teléfono real | **SÍ** | Galaxy A71, Android 13, por la red local |

---

## 3. Arquitectura y tecnologías

| Componente | Otro proyecto | ColabModel |
|---|---|---|
| Lienzo | React Flow | SVG propio en React |
| Aplicación web | PWA | SPA con Vite (sin PWA) |
| Servidor de la herramienta | Monorepo Node.js 20 | Spring Boot 3.5 · Java 21 · ArchUnit para controlar dependencias |
| Colaboración | No especificado | STOMP sobre WebSocket, secuenciador por diagrama, bloqueos y presencia |
| Autenticación | No especificado | JWT propio |
| Base de la herramienta | No especificado | PostgreSQL + Flyway (9 migraciones) |
| Backend generado | Spring Boot 3.3.5 · Java 17 · PostgreSQL | Spring Boot 3.5 · Java 21 · PostgreSQL **o MongoDB** |
| IA | Ollama opcional para la ayuda | Ollama local por defecto (`qwen3:8b`), Claude y Gemini opcionales; 4 modos (Preguntar, Revisar, Diseñar, Modificar) |
| Intercambio UML | XMI 2.1 | XMI 2.5.1 + dialecto XMI 2.1 de EA, SQL y JSON |
| Móvil | Flutter | Flutter (asistente + visor) |
| Integración continua | No mencionada | GitHub Actions: backend con PostgreSQL, frontend, pruebas de Flutter y APK |

---

## 4. Pruebas: equivalencia con los CP del otro proyecto

| CP del otro proyecto | ColabModel |
|---|---|
| CP-01 Sincronizar dos ventanas | **SÍ** · pruebas automáticas y prueba manual con 3 pestañas |
| CP-06 Exportar e importar XMI sin perder el modelo | **PARCIAL** · pruebas de exportación e importación; falta archivo real de EA |
| CP-07 Cuatro capas, DTO y compilación Maven | **SÍ** · 9 proyectos generados compilan (`tools/validar-generados.ps1`, 7 modelos canónicos) |
| CP-08 / CP-09 Revisor corrige errores bloqueantes | **PARCIAL** · revisión con IA, sin validador bloqueante |
| CP-10 / CP-11 Voz y confirmación hablada | **PARCIAL** · dictado sí, confirmación hablada no |
| CP-12 Sincronizar sin duplicar tras recuperar red | **PARCIAL** · el visor sí (no aplica dos veces el mismo `seq`); el asistente no tiene cola sin conexión |
| CP-13 Ayuda sin conexión | **NO** |

Volumen de pruebas: unas 100 clases de prueba en el backend, unos 30 archivos de prueba en el frontend y pruebas del motor de intenciones de Flutter contra 8 manifiestos generados.

---

## 5. Lo que ColabModel tiene y el otro proyecto no muestra

1. **Cuatro vistas del modelo** (Clases, Lógico, Físico, Documental) con 12 reglas de mapeo explícitas.
2. **MongoDB** como alternativa a PostgreSQL en el backend generado.
3. **Verificar el DDL en PostgreSQL real** y confirmar el esquema antes de generar.
4. **Roles** con permisos verificados en el servidor y cambios de acceso en vivo.
5. **Versiones tipo Git**: ramas, diferencias y fusión de diagramas; deshacer/rehacer por pestaña.
6. **IA como integrante del equipo**: chat compartido, memoria de la conversación, sabe quién pregunta, estilo configurable por proyecto, funciona con un modelo local.
7. **Lectura de fotos sin API externa** (visión por computadora propia).
8. **Importar SQL y JSON**, además de XMI.
9. **Backend generado listo para producción**: perfiles, Docker, paginación, bloqueo optimista y una consola web genérica.
10. **Visor móvil en vivo** del diagrama, con recuperación tras perder la red.
11. **Integración continua** que compila el APK.

## 6. Lo que le falta a ColabModel (ordenado por impacto)

| # | Falta | Por qué importa | Esfuerzo |
|---|---|---|---|
| 1 | Validador con **errores bloqueantes y advertencias** + «arreglar» | Es un caso de uso del docente (CU-17, CU-18) y evita generar algo que no compila | Medio |
| 2 | **Recuperar contraseña** (código de recuperación) | Casos del docente CU-03 y CU-04 | Bajo–medio |
| 3 | **Sin conexión**: cola en el asistente Flutter (CU-16) y, si se pide, PWA | Caso del docente; hoy está como recorte | Alto |
| 4 | **Ayuda / guía dentro de la app** con respuestas citando la documentación (CU-12) y un recorrido inicial | Caso del docente; mejora la demo | Medio |
| 5 | **Cursores de colores** de los demás en la web | El canal ya existe; falta enviarlos y dibujarlos | Bajo |
| 6 | **Módulos / paquetes** que salgan como subpaquetes Java | Modelos grandes | Medio |
| 7 | **Métricas en el panel de proyectos** (clases, relaciones, errores, origen) | Los datos ya existen | Bajo |
| 8 | **Vista previa del contenido** de los archivos generados | Hoy solo la lista | Bajo |
| 9 | **Diagrama de comunicación** del backend generado (CU-19 del docente) | Caso del docente | Medio |
| 10 | **Editor usable en el teléfono** (menú de acciones compacto) | Hoy el móvil es solo visor | Medio |
| 11 | **Confirmación hablada** (TTS) en el asistente | Pedido en CP-10/11 del otro proyecto | Bajo |
| 12 | Probar con el **XMI real de Enterprise Architect** del docente | Riesgo de compatibilidad | Bajo (conseguir el archivo) |

## 7. Conclusión

En el **núcleo técnico** (modelado, colaboración, mapeo a base de datos y generación) ColabModel está por encima de lo que el otro proyecto muestra: más vistas, dos motores de base de datos, roles, versiones, verificación del esquema y una IA integrada al equipo, todo respaldado por pruebas automáticas y CI.

Donde el otro proyecto está mejor es en **la experiencia de uso alrededor del núcleo**: recuperación de cuenta, recorrido de bienvenida y ayuda, revisor con errores bloqueantes, módulos, uso del editor desde el teléfono y trabajo sin conexión. Varias de esas piezas (cursores, métricas, vista previa) son baratas porque los datos ya existen; las más caras son el modo sin conexión y la ayuda con documentación.

Nota: el otro análisis se basa en capturas y no confirma que sus funciones anunciadas funcionen; este se basa en el código, así que donde dice **SÍ** hay implementación y, en general, pruebas.
