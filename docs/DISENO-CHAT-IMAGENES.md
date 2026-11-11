# Diseño · Chat de la IA con imágenes (Fase 0: contrato)

**Estado: APROBADO** (usuario, 2026-11-11, opción B para `PanelVision`; aceptadas O-1, O-4 y O-6) · Juan, 2026-11-11 · base `04212fa` (`main` local, mesa integrada, migraciones hasta V13) · rama `codex/juan-chat-imagenes`.
Plan: `docs/PLAN-CHAT-IMAGENES-IA.md` (D1 a D7 aprobadas, no se discuten aquí). Esta fase es solo documento: no hay código de
producción ni migraciones reales. Claude revisa y el usuario aprueba.

Convenciones del repo que este contrato respeta (todas comprobadas leyendo el código):
- Los errores de la API tienen la forma `{"mensaje": "..."}` en español (`config/ManejadorDeErrores.java:23-38`); la visión
  agrega `"error": "IMAGEN_INVALIDA"` (`vision/VisionRestController.java:141-145`).
- `jackson.default-property-inclusion: non_null` (`application.yml:31-32`): un campo nulo **no aparece** en el JSON. Por eso
  "puede ser nulo" se traduce a "puede faltar" en TS y Dart.
- El rol sale siempre de `AccesoAProyecto.rolSobreDiagrama` (`project/AccesoAProyecto.java:86-89`): sin membresía, o con un
  diagrama que no existe, da **403** "No sos miembro de este proyecto" (`AccesoAProyecto.java:177-179`). `puedeEditar` es
  OWNER o EDITOR (`AccesoAProyecto.java:136-138`).
- `ddl-auto: validate` (`application.yml:19`): cada columna nueva necesita su campo en la entidad y un tipo que Hibernate acepte.

---

## 0. Lo que el código actual hace y condiciona el diseño

| Hecho | Dónde |
| --- | --- |
| `HistorialIA.Entrada` es `(id, userId, userName, modo, modelo, prompt, respuesta, propuesta, aplicada, creadaEn)`. | `ai/HistorialIA.java:31-33` |
| `verificarLote` exige `Origin.TEXT` o `VOICE`, `BATCH`, el mismo usuario y la interacción en el payload. | `ai/HistorialIA.java:101-117` (la condición, en 107-110) |
| La tabla guarda la propuesta como `jsonb NOT NULL`; el CHECK de `mode` solo tiene 4 valores y se llama `chk_ai_interaction_mode`. | `V7__historial_colaborativo_ia.sql:11,15` |
| `SolicitudDePropuesta(prompt, origin, modo, modelo)`; `pideCambios` es DISENAR o MODIFICAR y exige `puedeEditar`. | `ai/AiController.java:55,79-83` |
| `ServicioDeIA.generar` rechaza todo origen que no sea TEXT o VOICE. | `ai/ServicioDeIA.java:111-113` |
| `AvisosDeIA.Tipo` es `NUEVA, APLICADA` y `Aviso(tipo, entrada)`; el tópico es `/topic/diagram/{id}/ai`. | `ai/AvisosDeIA.java:18-29` |
| El cliente web trata todo aviso como `{tipo, entrada}` y reemplaza la entrada por id. | `frontend/src/ai/historial.ts:3-6,19-26` |
| `ImagenDeEntrada.desde(bytes, contentType, limites)`: PNG/JPEG, 8 MB, 40 Mpx, lado de trabajo 1600, mínimo 32 px; todo error es `ImagenInvalida` (400). | `vision/ImagenDeEntrada.java:47,54-56,97-118` |
| Un JPEG que `ImageIO` no sabe leer cae en el `catch` y da 400 `"No se pudo leer la imagen: <Excepción>"`. | `vision/ImagenDeEntrada.java:165-170` |
| `ImagenDeEntrada` **sí** sabe girar (`rotada(grados)`, público) y recortar (`recortar(Caja)`, público, recorta contra los bordes), ambos sobre la imagen **de trabajo**. `Enderezador` solo **estima** la inclinación (±10°); `Rejilla` no tiene que ver (compara glifos). | `vision/ImagenDeEntrada.java:187,277`; `vision/Enderezador.java:30,69`; `vision/Rejilla.java:35` |
| El pipeline: `detector.detectar(entrada)` y luego cada `EnriquecedorDeDeteccion` en cadena; si uno lanza `RuntimeException` se registra y se sigue con lo anterior. | `vision/VisionRestController.java:96-98` (decodificar, detectar) y `110-126` (cadena y degradación en `enriquecer`) |
| `EnriquecedorDeDeteccion` expone `enriquecer`, `nombre`, `disponible`. | `vision/EnriquecedorDeDeteccion.java:47,50,57` |
| `DeteccionPropuesta(clases, relaciones, avisos, metricas)`; `RecorteDeTexto.texto` es `null` hasta el nivel 2 y su `confianza` vale 0 mientras tanto; `RecorteDeSimbolo.figura` es `null` hasta el nivel 2 y la relación queda en `ASSOCIATION`. | `vision/DeteccionPropuesta.java:49-54,88-89,108-109,151-161` |
| El nivel 2 de plantillas publica confianza `0.8 × calce`; el del modelo, 0.7. | `vision/EnriquecedorPorPlantillas.java:50,192` y su comentario |
| `Origin.IMAGE` existe, el bus le da tope de 500 cambios y la columna `origin` de `diagram_operations` ya lo admite. | `modelcore/command/Origin.java:13`; `modelcore/command/Lote.java:48-52`; `V1__init.sql:146` |
| `MemoriaDeConversacion` arma los últimos `max-turnos` (8) del diagrama y resume cada propuesta leyendo su JSON como `PropuestaDeIA`. | `ai/MemoriaDeConversacion.java:56-79,87-101`; `application.yml:71-74` |
| El multipart global permite 25 MB y su 413 dice "el máximo es de 25 MB… Si es un XMI…". | `application.yml:35-44`; `config/ManejadorDeErrores.java:83-87` |
| Toda ruta `/api/v1/**` que no es `auth` ni `ws` exige token (`Bearer`). | `config/SecurityConfig.java:46-64` |
| `PanelVision` vive aparte del chat y llama `POST /vision/deteccion`, que **no guarda nada**. | `frontend/src/vision/PanelVision.tsx` (446 líneas); `frontend/src/contracts/vision.ts:140-154`; `frontend/src/App.tsx:20,144-146` |
| La carpeta `mobile/lib/asistente/` es el asistente de la **app generada** (usa el manifiesto), no el chat del editor; en `mobile/lib` no hay ninguna llamada a `/ai/`. El ADR-036 deja "Modo IA" en la web. | `mobile/lib/asistente/controlador_asistente.dart:1-12`; `docs/ADR.md:360` |

---

## 1. Migración `V14__chat_con_imagenes.sql`

Número: `main` llega a V13 (`V13__atributos_estaticos_y_de_solo_lectura.sql`); la siguiente libre es **V14**. El plan decía V10/V14
"o la que corresponda": corresponde V14. SQL completo (solo aquí, no existe como archivo):

```sql
-- Chat de la IA con imagenes (D1 a D7). Aditiva: las interacciones existentes quedan sin imagen.

CREATE TABLE ai_images (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    diagram_id    UUID NOT NULL REFERENCES diagrams(id) ON DELETE CASCADE,
    uploaded_by   UUID REFERENCES users(id) ON DELETE SET NULL,
    content_type  VARCHAR(16)  NOT NULL,
    size_bytes    INTEGER      NOT NULL,          -- bytes de `data`: lo que cuenta para el tope
    width         INTEGER      NOT NULL,
    height        INTEGER      NOT NULL,
    sha256        VARCHAR(64)  NOT NULL,          -- de los bytes RECIBIDOS, en minuscula
    data          BYTEA,                           -- null solo si fue eliminada
    thumbnail     BYTEA,                           -- JPEG de 320 px de lado mayor; null solo si fue eliminada
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted_at    TIMESTAMPTZ,
    deleted_by    UUID REFERENCES users(id) ON DELETE SET NULL,
    CONSTRAINT chk_ai_image_type CHECK (content_type IN ('image/png', 'image/jpeg')),
    CONSTRAINT chk_ai_image_size CHECK (size_bytes > 0 AND width > 0 AND height > 0),
    CONSTRAINT chk_ai_image_sha  CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    -- Viva: tiene sus bytes. Eliminada: no tiene ninguno (la fila queda para que el turno diga "imagen eliminada").
    CONSTRAINT chk_ai_image_bytes CHECK (
        (deleted_at IS NULL     AND data IS NOT NULL AND thumbnail IS NOT NULL) OR
        (deleted_at IS NOT NULL AND data IS NULL     AND thumbnail IS NULL))
);

-- D7: una sola fila VIVA por (diagrama, sha256). Parcial a proposito: ver la observacion O-1.
CREATE UNIQUE INDEX uq_ai_images_diagram_sha ON ai_images(diagram_id, sha256) WHERE deleted_at IS NULL;
-- La galeria: las del diagrama, mas nuevas primero.
CREATE INDEX idx_ai_images_diagram_created ON ai_images(diagram_id, created_at DESC, id);

-- El turno que trabaja sobre una imagen. SET NULL como pide el plan: solo se dispara si la fila de
-- `ai_images` se borra de verdad (al borrar el diagrama, que arrastra tambien sus turnos).
ALTER TABLE ai_interactions ADD COLUMN image_id UUID REFERENCES ai_images(id) ON DELETE SET NULL;
CREATE INDEX idx_ai_interactions_image ON ai_interactions(image_id) WHERE image_id IS NOT NULL;

-- Modo nuevo. RECOMENDAR y VERIFICAR no se guardan como turno (V7 tampoco los listaba), asi que no se agregan.
ALTER TABLE ai_interactions DROP CONSTRAINT chk_ai_interaction_mode;
ALTER TABLE ai_interactions ADD CONSTRAINT chk_ai_interaction_mode
    CHECK (mode IN ('CONSULTAR', 'REVISAR', 'DISENAR', 'MODIFICAR', 'IMAGEN'));
```

Decisiones del SQL:
- **`ON DELETE`**: `ai_images.diagram_id` → `CASCADE` (D1: se va con el diagrama); `ai_interactions.image_id` → `SET NULL` (D6 del plan).
  `uploaded_by` y `deleted_by` → `SET NULL`, igual que `user_id` en V7:5.
- **Borrar = soft delete.** `DELETE` vacía `data` y `thumbnail` y pone `deleted_at`/`deleted_by`, pero **deja la fila**. Si la fila se
  borrara, `image_id` quedaría en `NULL` y el turno no podría distinguir "imagen eliminada" de "este turno nunca tuvo imagen" (D6).
- **No hay CHECK `mode = 'IMAGEN' ⇔ image_id IS NOT NULL`** a propósito: el `SET NULL` de la cascada del diagrama lo violaría. Lo exige
  el servicio (§3.5).
- **`VARCHAR(64)` y no `CHAR(64)`** para `sha256`: con `ddl-auto: validate`, Hibernate espera `varchar` para un `String` y `CHAR` es
  `bpchar`. `byte[]` mapea a `bytea` sin `@Lob` (que en PostgreSQL usaría `oid`).
- `size_bytes INTEGER` alcanza (tope por imagen 8 MB de entrada; reencodar puede crecer algo, no 2 GB).
- **Entidad `InteraccionIA`** (`ai/InteraccionIA.java`): suma `@Column(name = "image_id") UUID imageId`. El CHECK de `mode` ya valida
  con `length = 12` (`InteraccionIA.java:153-155`): `IMAGEN` cabe.
- **Listar nunca carga bytes**: la lectura de metadatos y del historial usa una proyección (interfaz o `new ...Meta(...)` en JPQL) que
  no incluye `data` ni `thumbnail`. Solo `GET /{id}` y `GET /{id}/miniatura` los leen.
- **Tope**: lo cuentan solo las filas vivas (`deleted_at IS NULL`); borrar libera cupo.

---

## 2. Endpoints bajo `/api/v1/diagrams/{diagramId}/ai/imagenes`

Controlador nuevo `ImagenesController` (paquete nuevo `ai/imagenes`), sin tocar `AiController`. Todos exigen token. `{imagenId}` y
`{diagramId}` que no sean UUID dan 400 `{"mensaje": "El identificador «imagenId» no es válido"}` (`ManejadorDeErrores.java:91-94`).

Errores comunes a todos:

| Código | Cuándo | `mensaje` |
| --- | --- | --- |
| 401 | sin token o token vencido | lo emite la seguridad, como hoy |
| 403 | no es miembro (o el diagrama no existe) | `No sos miembro de este proyecto` |
| 404 | `imagenId` no es de este diagrama, o fue eliminada (para los `GET` de bytes) | `Esa imagen no existe en este diagrama` |

Una imagen de **otro diagrama** da 404, nunca 403: así no se revela que existe en otro proyecto. Ese diagrama puede ser del mismo
usuario o no; la regla es la misma.

### 2.1 `POST /` · subir

- Rol: **OWNER o EDITOR**. Un VIEWER → 403 `Un VIEWER no puede subir imágenes` (se parece al de `vision`, `VisionRestController.java:129-136`).
- Cuerpo: `multipart/form-data`, una parte `imagen` (mismo nombre que `/vision/deteccion`, `VisionRestController.java:83-86`).
  El `Content-Type` de la parte se usa **solo** para validar que sea PNG o JPEG, igual que hoy; lo que se guarda y se devuelve lo
  decide el servidor tras decodificar (§7).
- Respuesta: `ImagenMeta` (§4).
  - **201 Created** si es nueva (más `Location: /api/v1/diagrams/{id}/ai/imagenes/{imagenId}`).
  - **200 OK** si ya había una **viva** con el mismo SHA-256 en ese diagrama (D7): devuelve la existente, sin tocar la base ni mandar aviso.
- Errores propios:
  - **400** `{"error":"IMAGEN_INVALIDA","mensaje":"..."}` con los mensajes que ya da `ImagenDeEntrada` (`ImagenDeEntrada.java:98-118`):
    vacía, `"La imagen pesa N KB; el maximo es 8192 KB"`, formato no aceptado, más de 40 Mpx, menor de 32 px, ilegible. Se suma:
    falta la parte `imagen` → `Falta el archivo en la parte «imagen»`.
  - **413** `{"mensaje": "..."}` por el tope del diagrama, **explicado** y con los dos números:
    - cantidad: `Este diagrama ya tiene 100 imágenes (el máximo). Eliminá alguna de la galería para subir otra`
    - bytes: `Este diagrama ya usa 196 MB de 200 MB en imágenes y esta pesa 6 MB. Eliminá alguna de la galería para subir otra`
    - archivo mayor que el multipart global (25 MB): lo corta Spring antes del controlador. Hoy responde con el texto del XMI
      (`ManejadorDeErrores.java:83-87`), que aquí confunde. **Fase 1** hace que ese manejador mire la ruta y, bajo `/ai/imagenes`,
      diga `La imagen es demasiado grande: el máximo es de 8 MB`.
- **Serialización del tope**: dentro de la transacción se toma `SELECT ... FROM diagrams WHERE id = ? FOR UPDATE` antes de contar, así dos
  subidas simultáneas no pasan el tope juntas. La carrera de duplicados la cubre el índice único: si la inserción falla por
  `uq_ai_images_diagram_sha`, se vuelve a leer y se contesta 200 con la existente.
- Efecto: fila en `ai_images`, aviso `IMAGEN_SUBIDA` a todos (§6). **No crea un turno del chat**: el turno nace al pedir el
  procesamiento (§5).

### 2.2 `GET /` · galería

- Rol: cualquier miembro (OWNER, EDITOR, VIEWER).
- Respuesta 200:

```json
{
  "imagenes": [ { "...ImagenMeta..." } ],
  "uso": { "cantidad": 3, "bytes": 1820044, "maxCantidad": 100, "maxBytes": 209715200 }
}
```

- Solo vivas, más nuevas primero. Solo metadatos.

### 2.3 `GET /{imagenId}` · los bytes

- Rol: cualquier miembro. Respuesta 200 con el cuerpo binario y estas cabeceras:

| Cabecera | Valor |
| --- | --- |
| `Content-Type` | el **guardado** (`image/png` o `image/jpeg`); nunca el del cliente ni uno deducido |
| `X-Content-Type-Options` | `nosniff` |
| `Cache-Control` | `private, no-cache` |
| `ETag` | `"<sha256>"` (comillas incluidas) |
| `Content-Disposition` | `inline` |
| `Content-Length` | tamaño de `data` |

- `no-cache` (no `max-age`) a propósito: el navegador **revalida** cada vez y, si la imagen se eliminó, recibe 404 en vez de seguir
  mostrando su copia (D6). Con `If-None-Match` igual al ETag responde **304** sin cuerpo.
- Eliminada → **404** (los bytes ya no existen).

### 2.4 `GET /{imagenId}/miniatura`

- Igual que 2.3, pero el cuerpo es `thumbnail`, `Content-Type: image/jpeg` fijo, `ETag: "<sha256>-t"`.

### 2.5 `DELETE /{imagenId}`

- Rol: **quien la subió** (si todavía puede editar, es decir OWNER o EDITOR) **o el OWNER**. Cualquier otro caso → 403
  `Solo quien subió la imagen o el propietario puede eliminarla`. (Un VIEWER nunca borra, ni siquiera lo que subió cuando era
  EDITOR: es coherente con la Fase 3, "el VIEWER no tiene el botón de borrar". Ver O-6.)
- Respuesta: **200** con el `ImagenMeta` ya con `eliminada: true` (idempotente: borrar una eliminada devuelve 200 con lo mismo).
- Efecto: vacía `data` y `thumbnail`, pone `deleted_at`/`deleted_by`; aviso `IMAGEN_ELIMINADA` (§6). Los turnos que la usaron quedan
  tal cual y su `imagen.eliminada` pasa a `true` (§4). El resultado de la lectura (`lecturaDeImagen`, §5.4) sigue guardado: borrar la
  foto no borra lo que ya se leyó.

---

## 3. Reglas del servicio `ImagenesDelChat` (Fase 1)

1. Decodifica y valida con `ImagenDeEntrada.desde(bytes, contentType, limites)` (misma validación y mismos límites que la visión:
   `colabmodel.vision.max-bytes/max-pixeles/max-lado`, `VisionRestController.java:67-69,73`). El límite de lado 1600 es del trabajo
   del detector, **no** del almacenamiento: se guarda a la resolución recibida.
2. Calcula el SHA-256 de los **bytes recibidos** (antes de reencodar). Elegido así porque no depende de que el codificador del JDK sea
   determinista. Consecuencia: la misma foto con otro EXIF no deduplica (aceptable).
3. Reencoda sin EXIF y genera la miniatura (§7).
4. Con el diagrama bloqueado (`FOR UPDATE`): busca viva por `(diagrama, sha256)` → si existe, devuelve 200; si no, comprueba tope
   (cantidad y suma de `size_bytes` vivos + la nueva) → 413; inserta.
5. Un turno `IMAGEN` exige `imagenId` viva del mismo diagrama (§5.1). Lo comprueba el servicio de la Fase 2, no la base.

---

## 4. DTO de metadatos de imagen (`ImagenMeta`)

Nunca lleva bytes. Campos: id, quién la subió (id y nombre), tipo, ancho, alto, bytes, fecha y si fue eliminada.

**JSON** (los nulos no aparecen):

```json
{
  "id": "7b0c6a52-3f0e-4a56-9d6a-2f6f0c1c9a11",
  "subidaPorId": "c2f1b6e0-8a7d-4f0e-9a3b-0d5e6f7a8b9c",
  "subidaPorNombre": "Ana Pérez",
  "tipo": "image/jpeg",
  "ancho": 1600,
  "alto": 1200,
  "bytes": 412345,
  "creadaEn": "2026-11-11T15:04:05.123-04:00",
  "eliminada": false
}
```

- `subidaPorId` falta y `subidaPorNombre` es `"Usuario eliminado"` si la cuenta se borró (misma regla que `HistorialIA.java:74,129`).
- Eliminada: `eliminada: true`, más `eliminadaEn` (ISO-8601). `tipo`, `ancho`, `alto`, `bytes` se conservan (son datos de la fila, no
  la imagen). Los bytes no existen.
- No lleva nombre de archivo original: puede delatar a una persona o un lugar y nadie lo necesita (ver O-8).
- No lleva URL: el cliente arma `/api/v1/diagrams/{diagramId}/ai/imagenes/{id}` y `.../miniatura`.

**Java** (record, `ai/imagenes/ImagenMeta.java`):

```java
public record ImagenMeta(UUID id, UUID subidaPorId, String subidaPorNombre, String tipo,
                         int ancho, int alto, int bytes, OffsetDateTime creadaEn,
                         boolean eliminada, OffsetDateTime eliminadaEn) { }
```

**TypeScript** (`frontend/src/contracts/imagenes.ts`):

```ts
export interface ImagenMeta {
  id: string
  subidaPorId?: string
  subidaPorNombre: string
  tipo: 'image/png' | 'image/jpeg'
  ancho: number
  alto: number
  bytes: number
  creadaEn: string // ISO-8601
  eliminada: boolean
  eliminadaEn?: string
}
export interface UsoDeImagenes { cantidad: number; bytes: number; maxCantidad: number; maxBytes: number }
export interface GaleriaDeImagenes { imagenes: ImagenMeta[]; uso: UsoDeImagenes }
```

**Dart** (espejo; el móvil hoy no tiene chat del editor, ver Fase 4 y O-4):

```dart
class ImagenMeta {
  final String id;
  final String? subidaPorId;
  final String subidaPorNombre;
  final String tipo;
  final int ancho, alto, bytes;
  final DateTime creadaEn;
  final bool eliminada;
  final DateTime? eliminadaEn;
  const ImagenMeta({required this.id, this.subidaPorId, required this.subidaPorNombre, required this.tipo,
      required this.ancho, required this.alto, required this.bytes, required this.creadaEn,
      required this.eliminada, this.eliminadaEn});
  factory ImagenMeta.desdeJson(Map<String, dynamic> j) => ImagenMeta(
      id: j['id'] as String, subidaPorId: j['subidaPorId'] as String?,
      subidaPorNombre: (j['subidaPorNombre'] ?? 'Usuario eliminado') as String, tipo: j['tipo'] as String,
      ancho: (j['ancho'] as num).toInt(), alto: (j['alto'] as num).toInt(), bytes: (j['bytes'] as num).toInt(),
      creadaEn: DateTime.parse(j['creadaEn'] as String), eliminada: j['eliminada'] == true,
      eliminadaEn: j['eliminadaEn'] == null ? null : DateTime.parse(j['eliminadaEn'] as String));
}
```

Lectura tolerante: ausente `eliminada` = `false` (una entrada de antes de la función no trae `imagen` en absoluto).

---

## 5. El turno `IMAGEN`

### 5.1 Cambio en `HistorialIA.Entrada`

Se agrega **un campo al final**, `ImagenMeta imagen`, que puede ser nulo (y entonces no aparece en el JSON):

```java
public record Entrada(UUID id, UUID userId, String userName, ModoIA modo, String modelo,
                      String prompt, String respuesta, PropuestaDeIA propuesta,
                      boolean aplicada, OffsetDateTime creadaEn, ImagenMeta imagen) { }
```

- `entradaDe(...)` (`HistorialIA.java:132-135`) y `listar` (`HistorialIA.java:68-76`) resuelven `imagen` con **una sola** consulta de
  metadatos por lote de `image_id` (sin N+1, sin bytes), igual que ya resuelven los nombres de usuario.
- Mantener el constructor de 10 argumentos para las pruebas anteriores (`imagen = null`).
- **Sin imagen**: `imagen` falta. **Con imagen viva**: `imagen` es el `ImagenMeta`. **Eliminada**: `imagen` está presente con
  `eliminada: true` y `eliminadaEn`; el cliente muestra "imagen eliminada" donde iría la miniatura, deja el texto del turno y la
  propuesta como estaban, y no pide bytes (daría 404).
- TS: `EntradaHistorialIA.imagen?: ImagenMeta`. Dart: `ImagenMeta? imagen`.
- Una entrada anterior a esta función no trae `imagen` y se ve exactamente como hoy.

### 5.2 `SolicitudDePropuesta`

```java
public record SolicitudDePropuesta(String prompt, Origin origin, ModoIA modo, String modelo,
                                   UUID imagenId, Integer giroGrados, Recorte recorte) {
    public record Recorte(int x, int y, int ancho, int alto) { }
}
```

Es `POST /api/v1/diagrams/{id}/ai/propuestas` (`AiController.java:71`), **el mismo endpoint**; el constructor de 4 argumentos se conserva
(`imagenId`, `giroGrados`, `recorte` = `null`). JSON:

```json
{ "modo": "IMAGEN", "imagenId": "7b0c6a52-...", "prompt": "Leé la pizarra", "giroGrados": 90,
  "recorte": { "x": 120, "y": 80, "ancho": 900, "alto": 700 } }
```

Reglas (cada incumplimiento es **400** `{"mensaje": ...}` por el `IllegalArgumentException` que ya maneja `AiController.java:169-172`):

| Regla | Mensaje |
| --- | --- |
| `modo = IMAGEN` exige `imagenId` | `Falta la imagen que se quiere leer` |
| `imagenId` solo vale con `modo = IMAGEN` | `La imagen solo se usa en el modo IMAGEN` |
| `giroGrados` / `recorte` solo valen con `modo = IMAGEN` | `El giro y el recorte solo valen al leer una imagen` |
| `giroGrados` entero en `[-180, 180]` que sea **un giro fino** (`\|g\| ≤ 10`) **o un giro recto** (múltiplo de 90) | `El giro debe ser de hasta 10° o un múltiplo de 90°` |
| `recorte` dentro de la imagen **guardada**: `x ≥ 0`, `y ≥ 0`, `ancho ≥ 32`, `alto ≥ 32`, `x + ancho ≤ ancho de la imagen`, `y + alto ≤ alto de la imagen` | `El recorte tiene que estar dentro de la imagen (ancho × alto) y medir al menos 32 px` |
| `prompt` es **opcional** en `IMAGEN`; si falta o está en blanco el servidor guarda `Leer la imagen subida por <nombre>` (la columna `prompt` es `NOT NULL`, `V7:8`) | — |
| `imagenId` de otro diagrama o eliminada | **404** `Esa imagen no existe en este diagrama` |
| rol | VIEWER → **403** `Los VIEWER no pueden pedirle cambios a la IA` (mismo texto que `AiController.java:82`; `IMAGEN` entra en `pideCambios`, `AiController.java:80`) |

- **Orden de las opciones**: primero el **recorte** (en píxeles de la imagen guardada, que es la que el usuario ve), después el **giro** sobre
  ese recorte. Así el usuario recorta lo que ve y no una imagen ya girada.
- Se aplican sobre la imagen guardada y el resultado **no se guarda**: no hay nueva fila en `ai_images`, el turno nuevo apunta a la
  **misma** `imagenId` y registra las opciones usadas (§5.4). Reprocesar tampoco cuenta contra el tope.
- Las transformaciones se hacen sobre el `BufferedImage` decodificado y la imagen resultante pasa por `ImagenDeEntrada.desde(...)`
  para el detector; **no** se agrega API nueva a `ImagenDeEntrada` ni a `vision/` (ver O-2). `rotada(grados)` y `recortar(Caja)` de
  `ImagenDeEntrada` no se usan para esto porque actúan sobre la imagen ya reducida a 1600 px.
- El `Enderezador` automático sigue corriendo dentro del detector (`DetectorGeometrico.java:69-75`): el giro del usuario se suma al
  fino que decida el detector (queda en `deteccion.metricas.enderezado`).
- `origin` del cuerpo: se ignora para `IMAGEN` y el turno sale con `origin = IMAGE`.

### 5.3 Quién ejecuta y qué se guarda

`AiController.proponer` deriva a un servicio nuevo (Fase 2) cuando `modo = IMAGEN`; **no** pasa por `ServicioDeIA.generar` (que solo
admite TEXT o VOICE, `ServicioDeIA.java:111-113`). El servicio corre `detector.detectar` y la cadena de `EnriquecedorDeDeteccion`
con la misma degradación que hoy (`VisionRestController.java:110-126`: el que falla se salta y el turno sale igual). Habla solo con
la interfaz `EnriquecedorDeDeteccion`; no nombra ningún proveedor ni modelo.

El turno es una fila de `ai_interactions` con `mode = IMAGEN`, `image_id`, `response` = resumen corto en lenguaje natural
("Leí 4 clases y 3 relaciones; 5 cosas con dudas.") y `proposal` = `PropuestaDeIA` con `origin = IMAGE`, `aceptados` **vacío** y el
campo nuevo `lecturaDeImagen` (§5.4). Los comandos no los produce el servidor: los arma el cliente con `traductor.ts` a partir de la
detección (que el usuario puede corregir antes), y los aplica como un BATCH `origin = IMAGE` con `interaccionIA` en el payload.

Se reutiliza `proposal jsonb` y **no se agrega una columna** para la detección: la regla de lectura del historial no cambia, el turno
ya es la unidad y `MemoriaDeConversacion` lo lee como `PropuestaDeIA` (`MemoriaDeConversacion.java:87-101`).

### 5.4 Resultado de un turno `IMAGEN`

`PropuestaDeIA` suma un campo al final, `LecturaDeImagen lecturaDeImagen` (nulo en los demás turnos; se conserva el constructor de 10):

```java
public record LecturaDeImagen(DeteccionPropuesta deteccion, List<Duda> dudas, int dudasOmitidas,
                              List<String> enriquecedoresAplicados, List<String> enriquecedoresFallidos,
                              OpcionesDeReproceso opciones) {
    public record OpcionesDeReproceso(Integer giroGrados, SolicitudDePropuesta.Recorte recorte) { }
    public enum TipoDeDuda { SIN_LECTURA_DE_TEXTO, CLASE_SIN_NOMBRE, RELACION_SIN_TIPO, TEXTO_VACIO,
                             TEXTO_INCIERTO, CLASE_POCO_CLARA, RELACION_POCO_CLARA }
    public record Duda(TipoDeDuda tipo, String elementoRef, String recorteRef, DeteccionPropuesta.RolDeTexto rol,
                       String leido, double confianza, String motivo) { }
}
```

- `deteccion` es la `DeteccionPropuesta` completa y tal cual (`DeteccionPropuesta.java:49-54`); sus `Caja` están en la imagen de
  trabajo **ya enderezada** (`DeteccionPropuesta.java:162-176` documenta el marco de referencia). Es el mismo objeto que el panel
  actual recibe de `/vision/deteccion`, así `traductor.ts` no cambia.
- `enriquecedoresAplicados` / `enriquecedoresFallidos`: los `nombre()` de la cadena (`EnriquecedorDeDeteccion.java:50`) que
  corrieron y los que lanzaron. Dice por qué un turno salió solo con el nivel 1, sin exponer detalles del modelo.
- `opciones`: lo que se pidió (giro y recorte) o ambos `null`. Sirve para que el cliente muestre "girada 90°" y para repetirlo.
- `PropuestaDeIA.origin` pasa a admitir `IMAGE` (hoy el TS dice `'TEXT' | 'VOICE'`, `frontend/src/ai/tipos.ts:51`).

**Qué cuenta como duda** (lo calcula el servidor con una clase nueva `DudasDeLectura`, determinista y sin modelo). Primero, una
condición global: se dice que **hubo lectura de texto** si algún `RecorteDeTexto` de la detección final trae `texto` no vacío
(`texto == null` es el estado normal sin nivel 2, `DeteccionPropuesta.java:88-89`).

| Tipo | Condición | `leido` | Gravedad |
| --- | --- | --- | --- |
| `SIN_LECTURA_DE_TEXTO` | **no** hubo lectura de texto (sin nivel 2, o falló). Es **una sola** duda para toda la imagen y **reemplaza** a todas las `TEXTO_VACIO` | — | 1 |
| `CLASE_SIN_NOMBRE` | hubo lectura de texto y `ClaseDetectada.nombre` es `null` o en blanco | — | 2 |
| `RELACION_SIN_TIPO` | un `RecorteDeSimbolo` existe pero su `figura` es `null` (la relación quedó como `ASSOCIATION` por defecto, `DeteccionPropuesta.java:105-107`) | — | 3 |
| `TEXTO_VACIO` | hubo lectura de texto y un recorte de rol `NOMBRE_RELACION`, `ATRIBUTO` o `METODO` tiene `texto` `null` o en blanco. **No** se marca `CARDINALIDAD` ni `ROL` vacíos: una relación sin multiplicidad es válida | — | 4 |
| `TEXTO_INCIERTO` | `texto` presente con `confianza < 0.60` | el texto | 5 |
| `RELACION_POCO_CLARA` | `RelacionDetectada.confianza < 0.50` | — | 6 |
| `CLASE_POCO_CLARA` | `ClaseDetectada.confianza < 0.50` (qué tan cerrado quedó el rectángulo, `DeteccionPropuesta.java:127-128`) | — | 7 |

- **Umbrales**: `0.60` para texto y `0.50` para forma, como **constantes** de `DudasDeLectura` (no son configuración). El de texto está
  por debajo de lo que publican las capas reales (modelo 0.7; plantillas `0.8 × calce`, `EnriquecedorPorPlantillas.java:50`), así que
  solo marca lo que el propio lector duda. Son una **propuesta que se calibra en la Fase 2 con las imágenes de prueba** y los
  fija una prueba por cada borde (0.59 sí, 0.60 no).
- Orden: por gravedad ascendente y, dentro de cada una, por `confianza` ascendente. Máximo **50**; el resto se cuenta en
  `dudasOmitidas`. Una imagen sin dudas devuelve lista vacía (no falta).
- `elementoRef` es el `ref` de la clase o relación ("c1", "r2"); `recorteRef` el del recorte, si lo hay.
- Las dudas son **informativas**: no bloquean. Corregirlas es del cliente (el panel de revisión ya edita nombres y tipos antes de
  aplicar); aplicar sigue siendo mandar el BATCH por el bus.
- **Seguimiento** (Fase 2): `MemoriaDeConversacion` incluye, para el último turno `IMAGEN` de la ventana, un resumen de la lectura
  (nombres de clases, relaciones con su tipo y cuántas dudas) de hasta 800 caracteres, con el mismo tratamiento de recorte y de
  comillas que ya aplica a los demás turnos (`MemoriaDeConversacion.java:108-`).

---

## 6. Avisos en vivo por `/topic/diagram/{id}/ai`

`AvisosDeIA.Tipo` (`ai/AvisosDeIA.java:18`) pasa de `NUEVA, APLICADA` a `NUEVA, APLICADA, IMAGEN_SUBIDA, IMAGEN_ELIMINADA`, y `Aviso`
(`AvisosDeIA.java:20`) suma un campo al final:

```java
public record Aviso(Tipo tipo, HistorialIA.Entrada entrada, ImagenMeta imagen) { }
```

| `tipo` | `entrada` | `imagen` | Cuándo |
| --- | --- | --- | --- |
| `NUEVA` | sí | falta | turno nuevo (también el `IMAGEN`; su `entrada.imagen` ya trae la imagen) |
| `APLICADA` | sí | falta | un turno se aplicó |
| `IMAGEN_SUBIDA` | falta | sí | subida **nueva** (201). Un duplicado (200) no avisa |
| `IMAGEN_ELIMINADA` | falta | sí, con `eliminada: true` | se eliminó |

```json
{ "tipo": "IMAGEN_SUBIDA", "imagen": { "id": "7b0c6a52-...", "subidaPorNombre": "Ana Pérez", "tipo": "image/jpeg",
  "ancho": 1600, "alto": 1200, "bytes": 412345, "creadaEn": "2026-11-11T15:04:05.123-04:00", "eliminada": false } }
```

- Se mandan **después del commit**, como hoy (`AiController.java:91-93`).
- Cliente al recibir `IMAGEN_SUBIDA`: la agrega a la galería (por `id`, idempotente). Al recibir `IMAGEN_ELIMINADA`: la marca eliminada en
  la galería **y** marca `imagen.eliminada = true` en todas las entradas del historial con ese `imagen.id`, sin pedir nada.
- **Compatibilidad**: el cliente actual toma todo aviso como `{tipo, entrada}` (`frontend/src/ai/historial.ts:3-6`) y reemplaza por id.
  Un aviso de tipo nuevo, con `entrada` ausente, lo rompería. Por eso el cliente (Fase 3) **ignora los tipos que no conoce** y los
  que no traen `entrada`. Hasta que la Fase 3 llegue, el backend de la Fase 1 puede emitirlos sin riesgo solo si esa guarda ya
  está: se entrega en **el mismo paso de integración** (ver §12, orden).
- Sigue valiendo lo del topic: lo leen los tres roles, como `ops` (`AvisosDeIA.java:9-11`).

---

## 7. Reencodado sin EXIF y miniatura (`ImageIO`)

Librería: solo el JDK (`javax.imageio.ImageIO`, `ImageWriter`, `ImageWriteParam`). Ya lo usa la visión (`ImagenDeEntrada.java:5,145,159`;
`ImagenDeEntrada.java:288` escribe PNG). **No se agrega ninguna dependencia.**

**Por qué reencodar saca el EXIF.** Se decodifica a `BufferedImage` (solo píxeles) y se vuelve a escribir con `ImageIO.write(...)`
sin pasar metadatos: no viaja ni el GPS, ni el modelo del teléfono, ni la fecha, ni las miniaturas incrustadas. Se comprueba con
una imagen de prueba que **sí** trae EXIF con GPS (§11).

| Caso | Qué se hace |
| --- | --- |
| **JPEG normal** | se guarda como JPEG, calidad **0.92** (`ImageWriteParam.setCompressionQuality`; el valor por defecto del JDK es 0.75 y degradaría el texto que lee el OCR). `content_type = image/jpeg` |
| **PNG sin transparencia** | se guarda como PNG. `content_type = image/png` |
| **PNG con transparencia (canal alfa)** | se **aplana sobre blanco** y se guarda como PNG RGB, sin alfa. Hoy, un PNG con alfa dibujado sobre un `TYPE_INT_RGB` deja lo transparente en **negro** y la línea negra de un diagrama se vuelve invisible (`ImagenDeEntrada.java:127-131,194`); aplanar sobre blanco evita eso y además hace que la miniatura JPEG, que no admite alfa, no falle |
| **JPEG con rotación EXIF** (una foto de pie sacada con el celular) | `ImageIO` ignora el tag de orientación; sin cuidado la foto queda **acostada** tras sacar el EXIF. Antes de descartarlo se lee la orientación (tag `0x0112`, segmento APP1 del JPEG) y se aplica a los píxeles. Si el tag falta o es inválido, se deja como está |
| **JPEG CMYK o YCCK** | el lector del JDK no lo soporta (`ImageIO` lanza "Unsupported Image Type"): cae en `ImagenDeEntrada.java:165-170` como 400. El mensaje genérico `No se pudo leer la imagen: IIOException` no ayuda; se mejora a `El JPEG usa el espacio de color CMYK, que no se puede leer. Exportalo como RGB o como PNG`. **Es comportamiento conocido del JDK y se fija con una prueba que usa un JPEG CMYK de fixture** (§11); no lo comprobé en esta fase |
| **Perfil ICC** | se pierde al reencodar (el color puede variar en un monitor de gama ancha). Aceptado: es un diagrama, no una fotografía |
| **PNG animado / interlazado / 16 bits** | se decodifica el primer cuadro y se escribe 8 bits; sin cambios de contrato |

**Miniatura**: lado mayor 320 px, proporción conservada, **sin ampliar** (si el lado mayor ya es ≤ 320, queda igual); interpolación
bilineal (`RenderingHints.VALUE_INTERPOLATION_BILINEAR`, como `ImagenDeEntrada.java:129-130`); siempre **JPEG calidad 0.80**
sobre fondo blanco; `Content-Type: image/jpeg`.

**Memoria**: decodificar 40 Mpx ocupa ~160 MB de heap como `INT_ARGB`. Ya es así en la visión (`ImagenDeEntrada.java:154-159`);
no se agrega concurrencia nueva, pero la subida y el reproceso comparten ese costo (ver O-9).

---

## 8. `HistorialIA.verificarLote` acepta `Origin.IMAGE`

Cambio mínimo en `HistorialIA.java:107`:

```java
boolean deLaIA = op.getOrigin() == Origin.TEXT || op.getOrigin() == Origin.VOICE || op.getOrigin() == Origin.IMAGE;
```

Las demás comprobaciones **no cambian** (`HistorialIA.java:102-116`): hay `opId`; la operación está en el log de **este** diagrama;
es un `BATCH`; la mandó **el mismo usuario** que marca; y el payload dice `interaccionIA = <este turno>`.
Se agregan dos cosas, que son parte de "las mismas comprobaciones" aplicadas al origen nuevo:
- Un lote con `origin = IMAGE` solo marca un turno cuyo `mode` es `IMAGEN`, y un turno `IMAGEN` solo se marca con `origin = IMAGE`
  (`La operacion indicada no corresponde a esta propuesta`). Evita marcar un turno de texto con un lote de imagen, que son
  topes distintos (`Lote.java:48-52`).
- El mensaje existente `La operacion indicada no es un lote de la IA` se conserva para `MOUSE` y `XMI_IMPORT`.

---

## 9. Configuración

En `application.yml`, bajo `colabmodel.ai`:

```yaml
colabmodel:
  ai:
    imagenes:
      # D5: tope por diagrama. Cuenta solo las imagenes vivas; borrar libera cupo.
      max-por-diagrama: 100
      # 200 MB. En bytes, como colabmodel.vision.max-bytes.
      max-bytes-por-diagrama: 209715200
```

- Se leen con `@Value("${colabmodel.ai.imagenes.max-por-diagrama:100}")` y `@Value("${colabmodel.ai.imagenes.max-bytes-por-diagrama:209715200}")`.
- Se **reutilizan** `colabmodel.vision.max-bytes` (8 MB), `max-pixeles` (40 Mpx) y `max-lado` (1600) (`VisionRestController.java:67-69`): una
  imagen guardada y una leída valen lo mismo y no hay dos límites que se desalineen.
- El multipart global (25 MB, `application.yml:43-44`) **no se toca**: lo necesita el XMI. El límite real de la imagen es el de
  `ImagenDeEntrada` (8 MB).
- Un valor `<= 0` en cualquiera de las dos claves falla al arrancar (como `Limites`, `ImagenDeEntrada.java:58-62`).

---

## 10. `PanelVision`: mantenerlo o convertirlo en un atajo (lo decide el usuario)

**Cómo está hoy.** `PanelVision.tsx` (446 líneas) es una pantalla de revisión: sube una imagen a `/vision/deteccion`, muestra clases y
relaciones con su confianza, deja corregirlas y las aplica (`frontend/src/App.tsx:144-146,705` guardan su estado). La imagen se descarta
al cerrarla y nadie más la ve.

| | **A · Mantenerlo tal cual** | **B · Atajo que abre el chat con la imagen** (recomendada) |
| --- | --- | --- |
| Qué es | Dos caminos: el panel (sin historial) y el chat (con historial) | Un solo camino: el botón "Leer imagen" sube a `/ai/imagenes` y abre el chat con la imagen adjunta |
| Qué cuesta | Casi nada ahora (la Fase 3 no toca el panel) | Más trabajo de Pedro en la Fase 3: el botón nuevo, mover la entrada, y que **el chat reutilice el componente de revisión** del panel (la lista de clases/relaciones editable) al hacer clic en "Revisar y aplicar" en un turno `IMAGEN` |
| Riesgo | Dos flujos que se desalinean; el usuario que usa el panel no guarda nada, no reprocesa y no comparte; hay que mantener dos pruebas y dos textos | Se toca un componente de 446 líneas y `App.tsx` (hoy con su estado de visión) |
| Qué se pierde | nada | `/vision/deteccion` queda sin quien lo llame desde la interfaz (el endpoint se **conserva**: es el que prueban las pruebas de visión y lo usan otros clientes) |
| Pruebas | sin cambios | las del panel se reescriben contra el chat; la lógica (`traductor.ts`) no cambia |

**Recomendación: B**, hecha en dos pasos en la Fase 3: (1) primero el chat con la imagen y el panel intacto (se entrega y se revisa);
(2) después el atajo, reutilizando la revisión del panel. Razón: es justo lo que el usuario pidió (que la imagen quede en el chat y todos
la vean); mantener A deja el camino sin memoria como el más visible. El costo de B es acotado porque la lógica de traducción y la de
revisión ya existen. **Lo decide el usuario**; si elige A, la Fase 3 omite el paso (2).

---

## 11. Lista de pruebas (Fases 1 a 3)

Todas con dobles; lo que exija PostgreSQL real, un modelo real o un navegador queda marcado **pendiente** y no cuenta como aprobado.

**Fase 1 · backend (Juan; revisa Pepe)**
1. VIEWER no puede subir: 403. (plan)
2. No miembro no puede ver ni listar ni descargar: 403. (plan)
3. `imagenId` de otro diagrama: 404 en `GET`, `GET miniatura` y `DELETE`. (plan)
4. El archivo guardado **no tiene EXIF**: una imagen con EXIF y GPS sale sin esos segmentos (se lee de nuevo con `ImageIO` y se
   comprueban los metadatos; y se busca la cadena `Exif` en los bytes). (plan, ampliada)
5. Una imagen fuera de límites da 400: >8 MB, >40 Mpx, <32 px, formato no PNG/JPEG, vacía, no es una imagen. (plan)
6. La misma imagen dos veces deja **una** fila viva; la segunda respuesta es 200 y **no** manda aviso. (plan)
7. Borrar el diagrama borra sus imágenes. **Pendiente: PostgreSQL real** (con dobles solo se prueba el servicio). (plan)
8. Con el tope lleno: 413 con el mensaje explicado, tanto por cantidad como por bytes. (plan)
9. Orientación EXIF: un JPEG con orientación 6 queda con el ancho y el alto cambiados y los píxeles girados.
10. PNG con transparencia: queda aplanado sobre blanco (un píxel transparente es blanco, no negro); la miniatura se genera.
11. JPEG CMYK: da 400 con el mensaje de CMYK (comportamiento del JDK, fijado con un fixture).
12. Miniatura: lado mayor 320, proporción conservada, no amplía, es JPEG.
13. Cabeceras de `GET`: `Content-Type` es el **guardado** aunque el cliente subió otro tipo en la parte; `nosniff`; `Cache-Control: private, no-cache`;
    `ETag`; `If-None-Match` da 304.
14. `DELETE`: lo hace quien subió (si edita) y el OWNER; otro EDITOR → 403; VIEWER → 403; vuelve a borrar → 200 idempotente;
    después `GET` de bytes → 404; la galería no la lista; **el tope se libera**.
15. Volver a subir, después de borrarla, la misma imagen crea una fila nueva (índice parcial).
16. Concurrencia: dos subidas simultáneas con el tope a una de llenarse no pasan las dos. **Pendiente: PostgreSQL real** (el `FOR UPDATE` no existe en un doble).
17. Aviso `IMAGEN_SUBIDA` e `IMAGEN_ELIMINADA` se mandan **después del commit** y con la forma del §6.
18. `HistorialIA.listar` con 50 turnos con imagen hace **una** consulta de metadatos y no lee `data` ni `thumbnail`.
19. `Entrada.imagen`: ausente sin imagen; viva con su meta; eliminada con `eliminada: true`.
20. Entidad y migración: `InteraccionIA` y la nueva entidad pasan `ddl-auto: validate`. **Pendiente: PostgreSQL real** (V14 con datos previos, el CHECK de `mode`, el
    `ON DELETE SET NULL`).
21. Un 413 por multipart global (>25 MB) en esta ruta dice `máximo es de 8 MB`, y en `/import` conserva el texto del XMI.

**Fase 2 · el chat procesa la imagen (Rodolfo; revisa Juan)**
22. El turno guarda la imagen **y** la propuesta; `entrada.imagen` y `propuesta.lecturaDeImagen` presentes. (plan)
23. Si el enriquecedor falla, el turno sale igual con el nivel 1, `enriquecedoresFallidos` lo lista. (plan)
24. Aplicar marca el turno como aplicado; con `Origin.IMAGE`. (plan)
25. Reprocesar con giro y/o recorte **no crea otra fila** en `ai_images` y el turno apunta a la misma `imagenId`. (plan)
26. `verificarLote`: acepta IMAGE con las mismas comprobaciones (opId inexistente, otro diagrama, otro usuario, otra interacción, no-BATCH) y rechaza
    IMAGE sobre un turno que no es `IMAGEN`, y TEXT sobre uno que sí lo es.
27. Validación de `SolicitudDePropuesta`: cada fila de la tabla del §5.2 (modo sin imagen, imagen sin modo, giro 45° → 400, giro 90° ok, giro 7° ok,
    recorte fuera de la imagen, recorte < 32 px, prompt vacío en `IMAGEN` toma el valor por defecto, VIEWER → 403, imagen eliminada → 404).
28. `DudasDeLectura`: sin nivel 2 → una sola `SIN_LECTURA_DE_TEXTO` y ninguna `TEXTO_VACIO`; con nivel 2 → `CLASE_SIN_NOMBRE`,
    `RELACION_SIN_TIPO`, `TEXTO_VACIO` (atributo, no cardinalidad), bordes 0.59/0.60 y 0.49/0.50; orden por gravedad; tope 50 con `dudasOmitidas`.
29. Giro y recorte: el recorte se hace sobre la imagen guardada y el giro sobre el recorte; el `Enderezador` automático sigue corriendo.
30. Seguimiento: un pedido de texto posterior recibe, en la memoria, el resumen del último turno `IMAGEN` (probado con el doble del proveedor);
    el resumen queda en una línea y sin comillas dobles.
31. Un turno `IMAGEN` no pasa por `ServicioDeIA.generar` y no llama al proveedor de texto.
32. **Pendiente (no es nuestro): tiempo y calidad del nivel 2 con el modelo real del compañero.**

**Fase 3 · interfaz web (Pedro; revisa Pepe)**
33. Adjuntar por botón, por pegado (Ctrl+V) y por arrastre; vista previa antes de enviar; tipo y peso inválidos se avisan antes de subir. (plan)
34. Miniatura dentro del turno; clic la agranda; se pide con `fetch` + `Authorization` y se muestra como `blob:`; **ninguna URL lleva token**. (plan)
35. Imagen eliminada: el turno muestra "imagen eliminada", sin pedir bytes. (plan)
36. Galería con quién subió y cuándo, botón "usar en el chat" y botón de reprocesar (giro y recorte). (plan)
37. VIEWER: ve miniaturas y galería; **no** tiene botón de adjuntar ni de borrar. (plan)
38. Aviso en vivo: `IMAGEN_SUBIDA` agrega a la galería sin recargar; `IMAGEN_ELIMINADA` marca la galería **y** los turnos con esa imagen; un tipo
    desconocido o sin `entrada` se ignora sin romper el chat (compat. con §6).
39. Las dudas se muestran junto al turno; corregir una no bloquea aplicar; el cliente arma el BATCH `origin = IMAGE` con `interaccionIA`.
40. Tipos TS (`ImagenMeta`, `GaleriaDeImagenes`, `EntradaHistorialIA.imagen`, `PropuestaIA.lecturaDeImagen`, `AvisoIA`) iguales a los del §4-§6; `tsc` limpio.
41. Si el usuario elige B (§10): el atajo "Leer imagen" abre el chat con la imagen; las pruebas del panel pasan a probar la revisión dentro del chat.
42. **Pendiente (Fase 5, Pepe): tres navegadores y tres usuarios.**

---

## 12. Quién toca cada archivo (Fases 1 a 3)

Las fases van en cadena (1 → 2; la 3 puede avanzar en paralelo a la 2 **solo** en el frontend, contra este contrato). Dos agentes no
editan el mismo archivo porque cada archivo tiene **un** dueño y los compartidos se entregan en orden.

| Archivo | Fase | Dueño | Cambio |
| --- | --- | --- | --- |
| `backend/src/main/resources/db/migration/V14__chat_con_imagenes.sql` | 1 | Juan | nuevo (§1) |
| `backend/src/main/java/.../ai/imagenes/*` (entidad, repositorio, servicio, controlador, `ImagenMeta`, reencodador de §7) | 1 | Juan | nuevos |
| `backend/src/main/java/.../ai/InteraccionIA.java` | 1 | Juan | `imageId` |
| `backend/src/main/java/.../ai/HistorialIA.java` | **1** (`Entrada.imagen`, `listar`, `entradaDe`) y **2** (`verificarLote`, `registrar`) | Juan, **luego** Rodolfo | **orden obligatorio**: la 2 parte de la 1 ya integrada |
| `backend/src/main/java/.../ai/AvisosDeIA.java` | 1 | Juan | nuevos tipos y campo `imagen` |
| `backend/src/main/java/.../config/ManejadorDeErrores.java` | 1 | Juan | el 413 de imagen mira la ruta |
| `backend/src/main/resources/application.yml` | 1 | Juan | las dos claves del §9 |
| `backend/src/test/java/.../ai/imagenes/*` | 1 | Juan | pruebas 1 a 21 |
| `backend/src/main/java/.../ai/AiController.java` | 2 | Rodolfo | `SolicitudDePropuesta`, `pideCambios`, derivar `IMAGEN` |
| `backend/src/main/java/.../ai/ModoIA.java` | 2 | Rodolfo | `IMAGEN` |
| `backend/src/main/java/.../ai/PropuestaDeIA.java` | 2 | Rodolfo | `lecturaDeImagen` y su constructor de 10 |
| `backend/src/main/java/.../ai/MemoriaDeConversacion.java` | 2 | Rodolfo | resumen de la última imagen |
| `backend/src/main/java/.../ai/imagenes/ProcesadorDeImagenDelChat.java`, `DudasDeLectura.java`, `TransformacionDeImagen.java` | 2 | Rodolfo | nuevos, en el paquete de la 1 |
| `backend/src/test/java/.../ai/**` de la fase 2 | 2 | Rodolfo | pruebas 22 a 32 |
| `frontend/src/contracts/imagenes.ts`, `frontend/src/ai/tipos.ts`, `frontend/src/ai/historial.ts` | 3 | Pedro | tipos y guarda de avisos |
| `frontend/src/ai/useAsistenteIA.ts`, `ChatFlotante` y su galería (componentes nuevos) | 3 | Pedro | |
| `frontend/src/vision/PanelVision.tsx`, `frontend/src/App.tsx` | 3 | Pedro | solo si el usuario elige B |
| `mobile/lib/**` (espejo de `ImagenMeta`) | 4 | Pedro o quien lleve el móvil | recién cuando exista un chat del editor en el móvil (O-4) |
| `ModeloLeido`/`LectorXmi`/`EscritorXmi`/`vision/**`/`Ollama*`/`CatalogoModelosOllama`/`colabmodel.ai.ollama` | — | **nadie** | no se tocan en este plan |
| `docs/DISENO-CHAT-IMAGENES.md`, `docs/ADR.md` | 0 | Juan | este documento y la entrada ADR |

**Orden de integración que evita romper el cliente**: la guarda de §6 (ignorar avisos desconocidos) y el tipo `EntradaHistorialIA.imagen?`
son de Pedro (Fase 3) pero deben llegar **antes o junto con** el primer `IMAGEN_SUBIDA` real. Como la Fase 1 no tiene botón de subir en la
interfaz, no hay quien lo dispare desde la web hasta que la 3 exista; el riesgo solo está en pruebas manuales con `curl`.

---

## 13. Observaciones sobre el plan (no cambian D1 a D7)

- **O-1 · Índice único parcial.** El plan pide un índice único `(diagram_id, sha256)`. Combinado con D6 (borrar deja "imagen eliminada" en el
  turno) obliga a no borrar la fila; con un índice único **total**, volver a subir una foto ya eliminada chocaría con la fila vieja. Se
  resolvió con un índice único **parcial** (`WHERE deleted_at IS NULL`) y borrado lógico. D7 se cumple para las vivas. Si se prefiere el
  índice total, la alternativa es "resucitar" la fila vieja, que deshace D6 para los turnos antiguos; no la recomiendo.
- **O-2 · "`Enderezador` y `Rejilla` ya existen".** El plan (Fase 2) los cita para girar y recortar. En el código, `Enderezador` solo
  **estima** el ángulo (`Enderezador.java:69`), `Rejilla` compara glifos y no recorta (`Rejilla.java:35`). Lo que sí existe es
  `ImagenDeEntrada.rotada` y `recortar`, pero actúan sobre la imagen ya reducida a 1600 px. Por eso el contrato aplica giro y recorte
  sobre la imagen **guardada** y la entrega al detector por `ImagenDeEntrada.desde`, sin tocar `vision/`. Es trabajo nuevo, chico, de la Fase 2.
- **O-3 · La detección vive en `proposal`.** No se agrega columna. Ventaja: cero cambios en el historial; costo: `PropuestaDeIA` gana un campo
  opcional. Un turno de imagen grande puede pesar cientos de KB en `jsonb`, y `listar` los trae todos: aceptable con el tope de 8 turnos de
  memoria, pero conviene que la Fase 2 mida `GET /historial` con un diagrama de 1000 clases.
- **O-4 · Móvil.** El plan dice "en `pantalla_asistente`". Esa pantalla es el asistente de la **app generada** (`controlador_asistente.dart:1-12`),
  no el chat del editor; el móvil no llama a `/ai/` (el ADR-036 deja "Modo IA" en la web, `docs/ADR.md:360`). La Fase 4 requiere antes un chat del editor
  móvil que hoy no existe. Se deja el espejo Dart definido, pero **no se crea archivo Dart en las fases 1 a 3**.
- **O-5 · Orientación EXIF.** Reencodar sin cuidado deja las fotos de pie acostadas. Hoy la visión ya recibe fotos de celular sin corregir su orientación;
  corregirla al guardar mejora también la detección. Es la única parte del reencodado que no es "solo `ImageIO.write`".
- **O-6 · Quién borra.** D6: "el que la subió o el OWNER". Si el que la subió ya es VIEWER (se le bajó el rol), este contrato **no** le deja borrar,
  para no contradecir "el VIEWER no tiene el botón de borrar" de la Fase 3. Lo puede borrar el OWNER.
- **O-7 · Cuenta de memoria.** El tope cuenta `size_bytes` (la imagen), no la miniatura (≤ ~25 KB × 100 = 2,5 MB). Si se quiere exacto, se suma `octet_length(thumbnail)`.
- **O-8 · Nombre de archivo.** No se guarda ni se devuelve. Si la galería lo necesita, es un campo opcional que se agrega sin romper nada, pero con el cuidado de privacidad de D4.
- **O-9 · Memoria y CPU.** La subida y el reproceso decodifican hasta 40 Mpx. No hay límite de concurrencia hoy en `vision/deteccion` y este
  contrato no lo agrega; si hace falta, es un semáforo en `ImagenesDelChat` (decisión de la Fase 1, no del contrato).
- **O-10 · `ModoIA.IMAGEN` y los clientes.** `ModoIA` en TS no lo conoce (`frontend/src/ai/tipos.ts:3`); un cliente viejo que reciba un turno `IMAGEN`
  por aviso lo mostrará con un modo desconocido. La Fase 3 lo agrega.
- **O-11 · Sin 404 para "diagrama inexistente".** Como hoy, un diagrama que no existe da 403 (`AccesoAProyecto.java:86-89`). Se deja así por
  consistencia con el resto de los endpoints de IA.
