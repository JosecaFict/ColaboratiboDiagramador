# Plan: el chat de la IA guarda las imágenes y ayuda a procesarlas

**Estado: DECISIONES APROBADAS** (D1 a D7, por el usuario el 2026-11-11). Plan de Claude Code. Arranca cuando la
mesa de trabajo (UML 2.5 y Enterprise Architect) esté integrada en `main`. Hasta entonces nadie toca código de esto.

## Qué se quiere

1. Se puede adjuntar una imagen en el chat de la IA: una foto de pizarra, un boceto o una captura.
2. La imagen queda guardada en el chat, y la ven todos los miembros del proyecto, ahora y después de recargar.
3. La IA ayuda a procesarla: propone el diagrama, señala lo que leyó con dudas y permite volver a procesarla sin
   subirla de nuevo.

## Lo que ya existe (revisado en el código de `main`, 489dbc5)

| Pieza | Dónde | Estado |
| --- | --- | --- |
| Chat compartido y persistente por diagrama | `ai_interactions` (V7), `HistorialIA`, `GET /api/v1/diagrams/{id}/ai/historial` | Ya funciona. Lo ve cualquier miembro del proyecto: OWNER, EDITOR o VIEWER. |
| Aviso en vivo a los demás | `AvisosDeIA` → `/topic/diagram/{id}/ai` (NUEVA, APLICADA) | Ya funciona. |
| Lectura de imágenes | `vision/`: `POST /api/v1/diagrams/{id}/vision/deteccion`, nivel 1 geométrico y nivel 2 con modelo (ADR-012) | Funciona, pero **no guarda nada**: la imagen se procesa y se descarta. Va por un panel aparte (`frontend/src/vision/PanelVision.tsx`), fuera del chat. |
| Límites y validación de imagen | `ImagenDeEntrada`: PNG/JPEG, 8 MB, 40 Mpx, lado 1600 | Se reutiliza tal cual. |
| Origen de un lote que viene de una imagen | `Origin.IMAGE` | Existe, pero `HistorialIA.verificarLote` solo acepta TEXT y VOICE. |
| Traducción de detección a comandos | `frontend/src/vision/traductor.ts` | Se reutiliza. |
| Chat en el móvil | `mobile/lib/asistente/` | Sin imágenes. |

En resumen, **lo compartido ya está hecho**. Faltan tres cosas: guardar la imagen, unirla a un turno del chat y
que el chat pueda trabajar sobre una imagen ya guardada.

## Lo que no es nuestro

La IA local es del compañero. No entra en este plan: qué modelo se usa (texto o visión), Ollama, Colab,
`ProveedorOllama`, `CatalogoModelosOllama`, `colabmodel.ai.ollama` y la configuración del nivel 2 local.
Nosotros llamamos al nivel 2 por la interfaz que ya existe (`EnriquecedorDeDeteccion`). Las pruebas usan
**dobles**, y lo que exija un modelo real queda anotado como pendiente del compañero.

## Decisiones (aprobadas por el usuario el 2026-11-11)

| # | Decisión | Propuesta | Por qué |
| --- | --- | --- | --- |
| D1 | Dónde se guardan los bytes | Tabla nueva en PostgreSQL (`bytea`) | No suma servicios a `docker-compose`, borra en cascada con el diagrama y entra en la misma transacción que el turno. Con un tope por diagrama el tamaño queda acotado. |
| D2 | Alcance del chat | Igual que hoy: un chat por diagrama, visible para todos los miembros del proyecto | Es lo que ya hace V7. Un chat por proyecto (todos los diagramas juntos) sería otra fase. |
| D3 | Quién sube y quién ve | Suben OWNER y EDITOR; ven todos los miembros, incluido VIEWER | Es la misma regla que ya tiene `vision/deteccion` (exige escritura) y el historial (exige membresía). |
| D4 | Metadatos de la foto | Se vuelve a codificar al guardarla, sin EXIF | Una foto del celular puede llevar la ubicación GPS y el modelo del teléfono, y la verían todos los miembros. |
| D5 | Tope | 8 MB por imagen (ya existe) y 200 MB o 100 imágenes por diagrama, configurable | Evita que la base crezca sin control. |
| D6 | Quién borra | El que la subió o el OWNER. El turno del chat queda con "imagen eliminada". | La conversación no pierde su historia. |
| D7 | Duplicados | La misma imagen (mismo SHA-256) en el mismo diagrama se guarda una vez | Al subir dos veces la misma foto no se duplica el almacenamiento. |

## Fases

Cada fase termina con pruebas en verde y un revisor que no es quien la hizo, como en la mesa.

### Fase 0 · Contrato (solo documento)

- Tabla, endpoints, forma del aviso en vivo y cómo cambia `HistorialIA.Entrada` (lleva los metadatos de la imagen,
  nunca los bytes).
- Número de migración: el siguiente libre **después** de integrar la mesa (hoy `main` llega a V10 y la rama de
  Juan a V13, así que sería V14 o el que corresponda).
- D1 a D7 ya están aprobadas. Queda por decidir en esta fase qué pasa con `PanelVision` (ver fase 3).
- **Terminado cuando:** el usuario aprueba el contrato.

### Fase 1 · Guardar imágenes (backend)

- Migración `ai_images`: `id`, `diagram_id` (FK, ON DELETE CASCADE), `uploaded_by`, `content_type`, `size_bytes`,
  `width`, `height`, `sha256`, `data bytea`, `thumbnail bytea`, `created_at`. Índice único `(diagram_id, sha256)`.
- `ai_interactions.image_id` (FK, ON DELETE SET NULL).
- Servicio nuevo `ImagenesDelChat`: valida con `ImagenDeEntrada.desde`, vuelve a codificar sin EXIF, genera una
  miniatura de 320 px, deduplica y aplica el tope.
- Endpoints bajo `/api/v1/diagrams/{id}/ai/imagenes`:
  - `POST` (multipart): solo OWNER o EDITOR.
  - `GET /{imagenId}` y `GET /{imagenId}/miniatura`: cualquier miembro. El `Content-Type` sale del valor guardado,
    nunca del cliente, y lleva `X-Content-Type-Options: nosniff` y `Cache-Control: private`.
  - `DELETE /{imagenId}`: quien la subió o el OWNER.
  - `GET` (lista): la galería del diagrama, solo metadatos.
- **Pruebas:** un VIEWER no puede subir (403); alguien que no es miembro no puede ver (403); una imagen de otro
  diagrama da 404; el archivo guardado ya no tiene EXIF; una imagen que excede los límites da 400; la misma imagen
  dos veces deja una sola fila; al borrar el diagrama se borran sus imágenes; con el tope lleno da 413 explicado.

### Fase 2 · La imagen entra al chat y la IA la procesa (backend)

- Modo nuevo `ModoIA.IMAGEN`. El CHECK de `ai_interactions.mode` se amplía en la misma migración.
- `SolicitudDePropuesta` acepta `imagenId`. El turno corre el pipeline que ya existe (detector de nivel 1 y
  enriquecedores de nivel 2, con su degradación) sobre la imagen **guardada**. El resultado queda en el turno, con
  su imagen, y sale como aviso NUEVA a todos.
- Ayuda en el procesamiento, sin modelo nuevo:
  - **Reprocesar** la misma imagen girada o recortada (`Enderezador` y `Rejilla` ya existen), sin volver a subirla.
  - **Dudas a la vista:** el turno lista lo que el detector leyó con baja confianza (textos vacíos, relaciones sin
    tipo) para que el usuario lo corrija en el chat antes de aplicar.
  - **Seguimiento:** en un pedido de texto posterior ("la flecha de Pedido es una composición"), la IA recibe el
    resultado de la última imagen del chat como contexto.
- `HistorialIA.verificarLote` acepta `Origin.IMAGE`, con las mismas comprobaciones que hoy: el mismo usuario, el
  mismo diagrama y la interacción en el payload.
- **Pruebas** con dobles del enriquecedor: el turno guarda imagen y propuesta; si el enriquecedor falla, el turno
  sale igual con el nivel 1; aplicar marca el turno como aplicado; reprocesar no crea otra imagen.

### Fase 3 · Interfaz web

- En `ChatFlotante`: botón para adjuntar, además de pegar (Ctrl+V) y arrastrar. Vista previa antes de enviar.
- En el historial, la miniatura dentro del turno; al hacer clic se agranda. Como las rutas exigen `Bearer`, la
  imagen se pide con `fetch` y se muestra como `blob:`; no se ponen tokens en la URL.
- Galería "Imágenes del diagrama", con quién subió cada una y cuándo, y un botón para usarla de nuevo en el chat.
- El VIEWER ve todo, pero no tiene el botón de adjuntar ni el de borrar.
- Se recibe en vivo por `/topic/diagram/{id}/ai`: cuando alguien sube una imagen, aparece a los demás sin recargar.
- `PanelVision` se mantiene o pasa a ser un atajo que abre el chat con la imagen. Se decide en la fase 0.
- **Pruebas:** vitest de los componentes y del cliente (adjuntar, miniatura, sin botones para el VIEWER).

### Fase 4 · Móvil (opcional, después de la web)

- En `pantalla_asistente`, ver las miniaturas del historial, sacar una foto con la cámara y subirla.

### Fase 5 · Verificación de punta a punta

- Tres usuarios en el mismo diagrama: A (EDITOR) sube una foto y B (EDITOR) la ve al instante, en otro navegador.
- C (VIEWER) la ve, pero no puede subir ni borrar.
- Al recargar todo sigue ahí. Al borrar la imagen, el turno queda con "imagen eliminada".
- Se aplica la propuesta de la imagen y el turno queda marcado como aplicado para todos.
- Con el modelo real del compañero se mide el tiempo del nivel 2; si no está listo, queda **pendiente**, no
  aprobado.

## Riesgos

- **Tamaño de la base:** lo acota D5. Si el proyecto crece, la tabla se puede llevar a almacenamiento de archivos sin
  cambiar los endpoints.
- **Privacidad:** fotos con personas o datos de terceros, que verá todo el proyecto. Lo cubren D4 y D6, y conviene
  un aviso al adjuntar.
- **Choque con la mesa:** número de migración y archivos del paquete `ai`. Se evita arrancando después de la
  integración.
- **Calidad de lectura:** depende del modelo de visión del compañero. Este plan no promete precisión, solo que la
  imagen se guarda, se comparte y se puede reprocesar.

## Reparto sugerido (se confirma al arrancar)

| Fase | Quién | Revisa |
| --- | --- | --- |
| 0 Contrato | Juan | Claude y el usuario |
| 1 Guardar imágenes | Juan | Pepe |
| 2 Chat con imagen | Rodolfo (conoce la importación y las propuestas) | Juan |
| 3 Interfaz web | Pedro | Pepe |
| 4 Móvil | Pedro o quien lleve el móvil | Pepe |
| 5 Punta a punta | Pepe | Claude |

Steven queda libre para cerrar lo que salga de las tandas 3 y 4 de Enterprise Architect.
