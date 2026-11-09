# Diseño · Fase 2 — producción, NoSQL, visor móvil y versiones

Documento de coordinación. Fija los **contratos** entre los cuatro frentes para que
trabajen en paralelo sin adivinar. Las decisiones están en `ADR.md` (ADR-034 a ADR-037).
Si algo de acá no se puede cumplir, **se avisa al coordinador antes de cambiarlo**.

## Reparto y dueños de archivos

| Frente | Agente | Rama / worktree | Toca | NO toca |
|---|---|---|---|---|
| A · Generador listo para producción + MongoDB (ADR-034, ADR-035) | Comparador (Opus) | `feat/generador-produccion` | `backend/.../codegen/**`, `modelcore/mapping/**` (solo agregar), endpoints de codegen en `DiagramRestController`, endpoint nuevo `/documental`, `canonical-models/`, `mobile/test/manifests/` (solo con el procedimiento de `ManifestDeAppTest`) | `frontend/`, `mobile/lib/`, migraciones Flyway |
| B · Versiones tipo Git (ADR-037) | Trazo (Opus) | `feat/versiones` | paquete nuevo `backend/.../versiones/**`, `db/migration/V8__versiones.sql`, lectura de `CommandApplier`/`DiagramSnapshotService` | `codegen/`, `frontend/`, `mobile/` |
| C · Visor móvil en vivo (ADR-036) | Sonnet-Diagramador (Sonnet) | `feat/visor-movil` | `mobile/lib/**`, `mobile/test/**` (menos `test/manifests/`), `mobile/pubspec.yaml`; en el backend **solo** agregar un endpoint STOMP nativo en `collab/WebSocketConfig.java` si hace falta | el resto del backend, `frontend/` |
| D · Interfaz web de versiones y de la vista Documental | colaboratibodiagramador-05 (Sonnet) | `feat/web-versiones` | `frontend/src/**` | `backend/`, `mobile/` |

Reglas comunes:
- Cada frente trabaja **solo en su worktree y su rama**. Nadie hace `push` ni merge a `main`: eso lo hace el coordinador.
- Commits chicos con mensaje en español. No se toca `README.md` (lo integra el coordinador con lo que digan los informes).
- `mvn test` (backend), `npm test` (frontend) y `flutter test` (mobile) tienen que seguir en verde en lo que cada uno toca.
- Al terminar: **informe** al coordinador con archivos tocados, qué se probó y cómo, qué quedó afuera y riesgos.

---

## A · Generador listo para producción (ADR-034)

Aplica a **las dos** variantes (PostgreSQL y MongoDB). Por cada entidad, en `/api/{recurso}`:

| Método | Ruta | Respuesta |
|---|---|---|
| GET | `/api/{recurso}` | `200` arreglo JSON. Acepta `page`, `size`, `sort=campo,asc|desc` y el filtro por FK que ya existe. **Sin `page` devuelve todo** (compatibilidad con la app móvil). Siempre manda la cabecera `X-Total-Count` |
| GET | `/api/{recurso}/{id}` | `200` / `404` |
| POST | `/api/{recurso}` | `201` + cabecera `Location` |
| PUT | `/api/{recurso}/{id}` | `200` reemplazo completo / `404` |
| PATCH | `/api/{recurso}/{id}` | `200` cambio parcial (solo los campos enviados) / `404` — **nuevo** |
| DELETE | `/api/{recurso}/{id}` | `204` / `404` / `409` si otra fila lo referencia (con mensaje legible) |

Errores, **un solo formato** para todos: `{"error": "...", "errores": {"campo": "..."}}` (el que ya existe), con `400`, `404`, `409` y `500` (sin stack trace al cliente).

Además, en el proyecto generado:
- Validación Bean Validation derivada del modelo (obligatorio, longitud, rangos de V3).
- Auditoría `createdAt` / `updatedAt` y bloqueo optimista `@Version` (un `PUT`/`PATCH` sobre una versión vieja da `409`).
- Perfiles `dev` y `prod`; en `prod` la base, el usuario, la contraseña, el puerto y los orígenes CORS salen de variables de entorno (`DB_URL`, `DB_USER`, `DB_PASSWORD`, `PORT`, `CORS_ORIGINS`).
- Actuator con `/actuator/health` e `/actuator/info`; apagado ordenado; logs con nivel por perfil.
- `Dockerfile` multi-etapa y `docker-compose.yml` (app + base) dentro del zip; `README.md` generado que explica cómo arrancarlo.
- Pruebas generadas: al menos una prueba MockMvc por controlador (crear, leer, 404, validación).

**El manifiesto (`app-manifest.json`) no puede romperse**: `manifestVersion` sigue en `1` y solo se le agregan campos opcionales. Si cambia, se actualiza con el procedimiento de `ManifestDeAppTest` y se avisa en el informe, porque el frente C lo lee.

Criterio de terminado: los 7 modelos canónicos + `biblioteca` generan, `mvn package` en verde, y un recorrido real (crear, listar paginado, PATCH, DELETE con 409) contra el backend generado.

## A · Variante MongoDB (ADR-035)

- `GET /api/v1/diagrams/{id}/codegen?store=postgres|mongo` y `GET /api/v1/diagrams/{id}/codegen/resumen?store=...`. Sin `store`, `postgres`.
- `GET /api/v1/diagrams/{id}/documental` (los tres roles leen):

```json
{
  "colecciones": [
    {
      "nombre": "pedidos",
      "entidad": "Pedido",
      "campos": [ { "nombre": "fecha", "tipo": "date", "requerido": true } ],
      "incrustados": [ { "campo": "lineas", "entidad": "LineaPedido", "multiple": true } ],
      "referencias": [ { "campo": "clienteId", "coleccion": "clientes", "multiple": false } ],
      "discriminador": null
    }
  ],
  "advertencias": [ "..." ]
}
```

Reglas de mapeo a documentos:

| Del modelo | A documentos |
|---|---|
| Composición | La parte va **incrustada** en el todo. Si la parte también es referenciada desde otra clase, va en su propia colección y se avisa |
| Asociación / agregación 1:N o N:1 | Referencia por id en el lado N |
| 1:1 | Referencia por id en el lado que tiene la FK en la Vista Lógica |
| N:M | Arreglo de ids en un solo lado (el primer extremo de la relación) |
| Herencia | Una sola colección para la jerarquía, campo discriminador `_tipo` |
| Clase asociativa | Colección propia con las dos referencias |

**Recortes acordados (D-02, apéndice de ADR-035).** Sin transacciones: trasladar una parte incrustada a otro todo da `409`; borrar un todo con partes en otra colección da `409` hasta que se borren las partes; una parte con atributos únicos o 1:1 propias va a su propia colección. Todo esto se lista en `advertencias` de `/documental` y en el README generado.

Mismas rutas `/api/{recurso}`, mismos DTO y **mismo manifiesto** que la variante PostgreSQL: los ids viajan como texto UUID y el filtro por FK conserva su nombre. La app móvil no tiene que enterarse de qué base hay atrás.

## B · Versiones tipo Git (ADR-037)

**Datos** (`V8__versiones.sql`, el único que crea una migración en esta fase):
- `diagram_versions(id, diagram_id, seq, name, message, author_id, parent_version_id, snapshot jsonb, created_at)`; `uq(diagram_id, name)`.
- En `diagrams`: `branch_name` (por defecto `main`), `forked_from_diagram_id`, `forked_from_version_id`, `id_salt`.
- `diagram_merges(target_diagram_id, from_diagram_id, from_snapshot, from_seq, target_seq, author_id, created_at)`: la base del próximo merge entre dos diagramas.

Una **rama es un diagrama** del mismo proyecto que nace de una versión. Al copiar se conserva **la identidad lógica de los elementos (id XOR sal de la rama; la sal no toca los bits de versión/variante)**: sin eso no hay merge posible. Los ids son PK globales, así que en la rama cada elemento tiene otro id físico; la traducción vive solo en `versiones.IdentidadLogica`. La API expone siempre los ids físicos del diagrama que se mira, y en el merge `elementId` es el id del destino.

**API** (`/api/v1`; los tres roles leen, OWNER y EDITOR escriben, según `AccesoAProyecto`):

| Método | Ruta | Qué hace |
|---|---|---|
| POST | `/diagrams/{id}/versions` `{name, message}` | commit: fija el `seq` actual y guarda el snapshot |
| GET | `/diagrams/{id}/versions` | historial, del más nuevo al más viejo |
| GET | `/diagrams/{id}/versions/{vid}` | una versión con su snapshot |
| GET | `/diagrams/{id}/diff?from={vid}&to={vid|actual}` | diferencias |
| POST | `/diagrams/{id}/versions/{vid}/restore` | vuelve a esa versión |
| POST | `/diagrams/{id}/branches` `{name, fromVersionId}` | crea rama → `{diagramId}` |
| GET | `/diagrams/{id}/branches` | ramas del diagrama (y de qué versión nacieron) |
| POST | `/diagrams/{id}/merge/preview` `{fromDiagramId}` | merge a tres bandas: base común, cambios limpios y conflictos |
| POST | `/diagrams/{id}/merge` `{fromDiagramId, resoluciones:[{elementId, campo, eleccion: "nuestro"|"suyo"}]}` | aplica el merge |

Forma del diff (la usa el frente D):

```json
{
  "desde": { "versionId": "...", "seq": 120 },
  "hasta": { "versionId": null, "seq": 157 },
  "clases":     { "agregadas": [ {"id": "...", "nombre": "..."} ], "eliminadas": [ ... ], "modificadas": [ {"id": "...", "nombre": "...", "cambios": [ {"campo": "name", "antes": "A", "despues": "B"} ]} ] },
  "atributos":  { "agregadas": [ ... ], "eliminadas": [ ... ], "modificadas": [ ... ] },
  "metodos":    { "agregadas": [ ... ], "eliminadas": [ ... ], "modificadas": [ ... ] },
  "relaciones": { "agregadas": [ ... ], "eliminadas": [ ... ], "modificadas": [ ... ] }
}
```

En atributos y métodos cada elemento lleva además `claseId` y `clase`. Mover o redimensionar una caja aparece como cambios `x`/`y`/`width`/`height` de la clase, y los quiebres como `waypoints` de la relación. El merge preview usa la misma forma por lado (`nuestros`, `suyos`) más `conflictos: [{elementId, tipo, campo, base, nuestro, suyo, nombre, clase?}]`, `base: {versionId|null, seq, diagramId, tipo: "version"|"merge"}`, `aplicables` (lo que llegaría si todo se resuelve "nuestro") y `puedeMergear`. `tipo` ∈ `clase|atributo|metodo|relacion`; borrado contra modificación usa el campo `existencia` con valores `existe`/`eliminado`; un choque de decisión de modelado usa el tipo del elemento y el campo `decision.<TIPO>`; dos elementos distintos que quedarían con el mismo nombre (clases del diagrama, atributos de una clase) son un conflicto sobre el que trae "suyo", campo `name`, `motivo: "nombre repetido"` y `sugerido` (p. ej. `Prestamo_2`): "nuestro" no lo trae (o deja el nombre de "nuestro"), "suyo" lo trae con el nombre sugerido. Restaurar y mergear mandan en el `BATCH` el `seqEsperado` contra el que se calcularon; si el diagrama avanzó, el bus lo rechaza (`409`, no se aplica nada).

Formas de respuesta: versión `{id, diagramId, seq, name, message, authorId, authorName, parentVersionId, createdAt}` (más `snapshot` en `GET /versions/{vid}`); rama `{diagramId, name, diagramName, forkedFromDiagramId, forkedFromVersionId, forkedFromVersionName, forkedFromSeq, currentSeq, createdAt}` (`POST /branches`); `GET /branches` devuelve `{actual: Rama, origen: {diagramId, name, diagramName, forkedFromVersionId, forkedFromVersionName} | null, ramas: [Rama]}` (`origen` null en un diagrama raíz); restaurar y mergear `{diagramId, seq, cambios}`. Errores `{mensaje, …}`: `409` por lock ajeno agrega `elementId` y `usuario`; `409` por merge sin resolver agrega `conflictos`; `422` si los diagramas no comparten historia o el lote supera 500 cambios.

Restaurar y mergear **no agregan comandos** al catálogo cerrado (ADR-014): se calculan los comandos que llevan del estado actual al pedido y se mandan como **un `BATCH` por el bus**. Así se secuencian, todos los ven en vivo, quedan en el log con su autor y respetan los locks (si un elemento está bloqueado por otro, `409` con quién lo tiene).

Cada commit nuevo se anuncia en `/topic/diagram/{id}/versions` para que el historial se actualice sin recargar.

## C · Visor móvil en vivo (ADR-036)

Misma app Flutter; la pantalla inicial ofrece **Asistente** (lo de hoy, contra el backend generado) o **Ver diagrama en vivo** (contra la herramienta). Solo lectura: **nunca** manda nada a `/app/...`.

1. Pide la dirección de la herramienta (IP de la máquina, no `localhost`) y hace `POST /api/v1/auth/login` → JWT.
2. `GET /api/v1/projects` → `GET /api/v1/projects/{id}` (trae `diagrams` con `currentSeq`) → elegir diagrama.
3. `GET /api/v1/diagrams/{id}` → snapshot con su `seq`.
4. STOMP con `Authorization: Bearer <jwt>` en el CONNECT; suscribe `/topic/diagram/{id}/ops`, `/locks` y `/presence`. El servidor publica en `/ws` con SockJS; si el cliente Dart no lo soporta bien, se permite agregar en `WebSocketConfig` un endpoint sin SockJS `/ws-nativo` con la misma seguridad.
5. Cada operación se aplica con un **espejo en Dart** de `frontend/src/canvas/applyOperation.ts` (`lib/visor/aplicar_operacion.dart`) y con pruebas que comparan contra los mismos casos.
6. Si hay un hueco en `seq` o se cortó la red: `GET /api/v1/diagrams/{id}/operations?sinceSeq=N` y sigue.
7. Sin red: muestra el último snapshot guardado en el teléfono con un aviso "sin conexión, última actualización hh:mm".

Dibujo: vista de Clases (cajas con nombre, atributos y métodos; relaciones con su notación básica), con zoom y desplazamiento (`InteractiveViewer` + `CustomPainter`). Muestra quién tiene bloqueado cada elemento y quién está conectado.

Este es el **quinto espejo** del proyecto (`CommandApplier.java` ↔ `applyOperation.ts` ↔ `aplicar_operacion.dart`): si cambia el efecto de un comando, cambia en los tres.

## D · Interfaz web

En orden, contra los contratos de arriba (se puede empezar con datos simulados y conectar cuando el backend esté):
1. Panel **Versiones** en el editor: hacer commit (nombre + mensaje), historial en vivo, botón restaurar con confirmación.
2. Vista **Diff** entre dos versiones (o versión ↔ actual), agrupada por clases, atributos, métodos y relaciones, con colores agregado / eliminado / modificado; y resaltado en el canvas.
3. **Ramas**: crear desde una versión, listar, abrir; **Merge** con preview y elección por conflicto.
4. Pestaña **Documental** junto a Lógico y Físico (lee `/documental`) y selector **PostgreSQL / MongoDB** en el panel de Generar.

Los permisos se respetan con `src/auth/permisos.ts` (VIEWER ve, no escribe).
