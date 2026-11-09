# Interfaz de ColabModel: comparación con la referencia y plan de mejoras

Comparación entre las 13 capturas de la web de referencia (la que tiene React Flow, voz de ElevenLabs y menús Archivo/Edición/Ver/Modelo) y el código actual de ColabModel (`frontend/src`, `mobile/lib`, rama `fix/mongo-decimales`).

---

## Parte 1 · Web

### 1.1 Cómo está organizada cada pantalla del editor

| Zona | Referencia | ColabModel hoy |
|---|---|---|
| Arriba | Barra de **menús** (Archivo, Edición, Ver, Modelo) + nombre del proyecto + deshacer/rehacer + mostrar/ocultar paneles | **Tres filas**: barra (marca, migas, «En directo», rol, usuario, Volver, Cerrar sesión), **barra de herramientas** con unos 15 botones y fila de **pestañas** de vista + Versiones + selector de cardinalidad |
| Izquierda | **Árbol del proyecto** (clases con contador, relaciones) + **paleta** (clase, interfaz, abstracta, enumeración y los 7 tipos de relación con su flecha) y una ayuda corta | No hay panel izquierdo. Los tipos de relación están en la barra de herramientas |
| Centro | Lienzo oscuro con puntos, **minimapa**, zoom vertical | Lienzo claro, zoom −/%/+ y «ver todo» (⤢). **Sin minimapa** |
| Abajo | **Barra de estado**: zoom, Ajustar, «10 clases · 8 relaciones», «Seleccionado: Clase2», En directo | No hay barra de estado |
| Derecha | Secciones **plegables**: Propiedades, Historial, Comunicación, Modo IA, Versiones. Cada clase muestra **quién la creó y cuándo** | Panel con Propiedades/Relación y debajo una lista técnica **«Operaciones»** (`#12 CREATE_CLASS MANUAL`) |
| Flotantes | Botón de **chat** del equipo y botón **? Ayuda** | No hay |
| Tema | Oscuro | Solo claro (los colores ya están en variables en `:root`) |

**Conclusión:** la referencia parece un IDE: las acciones están en menús y las cosas del modelo en paneles. ColabModel tiene **más funciones**, pero las muestra todas a la vez en botones, y eso hace la pantalla más cargada y más difícil de leer en la demo.

### 1.2 Pantalla por pantalla

| Pantalla | Qué hace mejor la referencia | Qué tenemos nosotros | Mejora propuesta |
|---|---|---|---|
| **Importar desde imagen** | Muestra la **foto al lado** de lo leído, con zoom. Indica la confianza. Avisa en rojo los extremos **«sin leer»** y ofrece «Sugerir lo que falta». Las multiplicidades se editan con desplegables. El botón dice «He comparado con la imagen: importar 8 clases» | Lectura propia que funciona sin API y confianza por rectángulo, pero **no se ve la imagen** mientras se revisa | Panel en dos columnas con la foto y zoom a la izquierda; marcar en rojo lo que no se leyó; botón que diga cuántas clases entran |
| **Antes de generar** | Ventana «Todavía no se puede generar»: errores numerados, explicación, **código de regla** (`MISSING_IDENTIFIER`) y «Aplicar los 2 cambios» | Advertencias + «Correcciones seguras», que hoy solo **renombran relaciones** | Validador con gravedad (bloquea / avisa), código de regla y arreglo automático para clave primaria faltante, nombres duplicados, etc. |
| **Diagrama de comunicación** | Por clase y por operación (Listar, Obtener, Crear, Actualizar, Borrar): cajas Controller → Service → Mapper → Entity con mensajes numerados y la **línea de Java** de cada uno | **No existe** (CU-19 del docente) | Se puede armar en el frontend con lo que ya sabe el generador (nombres de clases y rutas) |
| **Modo IA** | Casilla «la IA aplica sola los cambios» (lo que borra sigue pidiendo confirmación) y deshacer de una vez; botón **«Revisar mi diagrama»** con observaciones en tarjetas; **respuesta hablada**; mensaje claro si falta permiso del micrófono | Chat **compartido** con el equipo (la referencia es privado), 4 modos, propuestas seleccionables, configuración por proyecto | Agregar «aplicar automáticamente», un botón de revisión de un clic y lectura en voz alta con `speechSynthesis` del navegador (gratis, sin clave) |
| **Historial** | Por elemento: «Creada por Daniel hace unos segundos» | Lista técnica con `seq`, tipo de comando y origen | Mostrarlo en lenguaje de persona: «Daniel creó la clase Pedido · hace 2 min» |
| **Comunicación** | Tablón de mensajes del equipo | Solo el chat de la IA | Chat del equipo en un canal STOMP aparte |
| **Menú Modelo** | Nueva clase, interfaz, **clase abstracta**, enumeración, Revisar, **Módulos** | Clase, enumeración, valor embebido. «Abstracta» existe en el modelo del backend, pero la web no lo muestra; no hay interfaz ni realización | Exponer «abstracta» en el panel (es barato); interfaz y realización solo si el docente las pide |

### 1.3 Lo que hacemos mejor y hay que conservar

- **Cuatro vistas** (Clases, Lógico, Físico, Documental) y el selector de cardinalidad UML/Chen.
- **Bloqueo visible**: la clase que otro edita se pinta en rojo con su nombre.
- **Rol visible** y aviso de solo lectura.
- **Versiones con ramas, diferencias y fusión** (en la referencia solo hay «punto de control»).
- **IA compartida** con todo el equipo.
- Migas de navegación (Mis proyectos › Proyecto › Diagrama).

### 1.4 Plan de mejoras de la web (ordenado por impacto y esfuerzo)

**Rápidas (menos de un día cada una)**

| # | Mejora | Dónde |
|---|---|---|
| W1 | **Barra de estado** abajo: «N clases · M relaciones», zoom, seleccionado, conexión | `App.tsx`, `styles.css` |
| W2 | Cambiar «Operaciones» por un **historial legible** (quién, qué, cuándo) y mostrar el de la clase seleccionada en Propiedades | `App.tsx` (lista `ops`), `PanelPropiedades.tsx` |
| W3 | **Mostrar la foto** en el panel de importar desde imagen | `vision/PanelVision.tsx` |
| W4 | **Tema oscuro** con un botón (las variables ya existen en `:root`) | `styles.css` |
| W5 | **Cursores de los demás**: el canal de presencia ya existe, falta enviarlo y dibujarlo | `App.tsx`, `canvas/Canvas.tsx`, `collab/wsClient.ts` |
| W6 | La IA **lee la respuesta en voz alta** con `speechSynthesis` | `ai/VistaIA.tsx`, `ai/voz.ts` |
| W7 | Casilla **«Clase abstracta»** en Propiedades | `panel/PanelPropiedades.tsx` |

**Medianas (1 a 3 días)**

| # | Mejora | Dónde |
|---|---|---|
| W8 | **Menús Archivo / Edición / Ver / Modelo**: Importar, Exportar, Imagen, Generar y Versiones salen de la barra de herramientas; la barra queda con lo de dibujar | `toolbar/Toolbar.tsx` → menú nuevo |
| W9 | **Panel izquierdo**: árbol de clases (clic = seleccionar y centrar) + paleta de relaciones con su notación (ya existe `NotacionRelacion.tsx`) | componente nuevo |
| W10 | **Panel derecho en secciones plegables**: Propiedades, Historial, IA, Versiones | `App.tsx` |
| W11 | **Minimapa** del lienzo | `canvas/Canvas.tsx` |
| W12 | **Validador con gravedad y códigos** + «Aplicar N cambios» (backend y ventana) | `codegen/`, `PanelGeneracion.tsx` |
| W13 | **Chat del equipo** (tablón) | backend `collab/` + panel nuevo |

**Grandes (más de 3 días)**

| # | Mejora |
|---|---|
| W14 | **Diagrama de comunicación** por operación CRUD (CU-19 del docente) |
| W15 | **Módulos** del proyecto que salgan como subpaquetes Java |
| W16 | **Editor usable en el teléfono**: menú de hamburguesa, paneles como hojas que suben desde abajo y lienzo a pantalla completa |
| W17 | **Ayuda** con un recorrido de bienvenida y respuestas que citan la documentación (CU-12 del docente) |

Para la demo rinden más **W1, W2, W3, W8 y W10**: cambian la primera impresión y no tocan el backend.

---

## Parte 2 · Móvil

Hay dos «móviles» y conviene separarlos:

1. **La web abierta en el teléfono.** Es lo que muestran las capturas de la referencia.
2. **La app Flutter** (`mobile/`), con el **Asistente** contra el backend generado y el **Visor** del diagrama.

### 2.1 La web en el teléfono · avance aproximado **15 %**

| Pantalla | Estado |
|---|---|
| Login / crear cuenta | Se adapta (a menos de 900 px la columna de la marca desaparece) |
| Mis proyectos / proyecto | Se adapta (a menos de 860 px pasa a una sola columna) |
| Vista IA | Se adapta (a menos de 820 px pasa a una sola columna) |
| **Editor** (barra, herramientas, lienzo, panel) | **No se adapta**: la barra de herramientas y el panel derecho no entran; no hay gestos táctiles para zoom ni para mover |

El porcentaje sale de que 3 de las pantallas secundarias se acomodan, pero la principal, que es el editor, no.

### 2.2 App Flutter · avance aproximado **62 %**

| Función | Estado | Peso |
|---|---|---|
| **Asistente (contra el backend generado)** | | **≈ 48 %** |
| Conectarse y aprender el dominio del manifiesto | Hecho | 1 |
| Crear un registro por diálogo, de a un campo | Hecho | 1 |
| Listar | Hecho | 1 |
| Relaciones como lista numerada (sin pedir UUID) | Hecho | 1 |
| Completar lo que falta y «cancelar» | Hecho | 1 |
| Dictado por voz (`speech_to_text`) | Casi (falta re-probar el envío automático en el teléfono) | 0,8 |
| **Buscar / ver un registro** | Falta (el motor solo tiene `crear` y `listar`) | 0 |
| **Actualizar** | Falta | 0 |
| **Borrar** (con confirmación) | Falta | 0 |
| **Respuesta hablada** (TTS) | Falta (`flutter_tts` está comentado) | 0 |
| **Confirmar por voz antes de algo crítico** | Falta | 0 |
| **Registrar sin conexión y sincronizar sin duplicar** (CU-16) | Falta (`sqflite` y `connectivity_plus` comentados) | 0 |
| **Visor del diagrama** | | **80 %** |
| Entrar con la cuenta, ver proyectos y diagramas | Hecho | 1 |
| Diagrama en vivo por STOMP, con zoom (`InteractiveViewer`) | Hecho | 1 |
| Sin conexión: último diagrama guardado y recuperación de lo que faltó | Hecho | 1 |
| Quién está conectado | Hecho | 1 |
| Tocar una clase para ver su detalle | Falta | 0 |
| **Plataforma** | | **100 %** |
| APK de Android compilado en CI | Hecho | 1 |
| Probado en un teléfono real (Galaxy A71) | Hecho | 1 |
| **Total** | **11,8 de 19** | **≈ 62 %** |

Contra los dos casos móviles del docente: **CU-15 Dictar una orden** está hecho (la versión completa vive en `pendientes-rapidos`) y **CU-16 Registrar sin conexión** falta. Eso da **50 %**.

### 2.3 Qué se puede hacer en la app, en orden

| # | Mejora | Esfuerzo | Sube el avance a |
|---|---|---|---|
| M1 | **Respuesta hablada** con `flutter_tts`: funciona sin red; leer «Listo, Niño creado» y las preguntas | Bajo | 67 % |
| M2 | **Ver, actualizar y borrar** en el motor de intenciones (`Accion.obtener / actualizar / borrar`) con la misma lógica de elegir de una lista numerada | Medio | 82 % |
| M3 | **Confirmación hablada antes de borrar** («¿Borro a Ana Pérez? Decí sí o no») | Bajo, después de M1 y M2 | 87 % |
| M4 | **Cola sin conexión**: guardar el pedido en `sqflite` con un `opId`, reenviar al volver la red y que el backend generado ignore un `opId` repetido (CU-16) | Alto (toca también el generador) | 95 % |
| M5 | Visor: **tocar una clase** y ver sus atributos en una hoja inferior | Bajo | 100 % |
| M6 | Pulido visual: tema oscuro igual al de la web, ícono propio y pantalla de inicio con las dos entradas como tarjetas | Bajo | — |

**Orden recomendado:** M1 → M2 → M3, que cierran voz y CRUD y son lo que más se ve en una demo. M4 conviene hacerlo solo si el docente lo va a evaluar, porque es el más caro.
