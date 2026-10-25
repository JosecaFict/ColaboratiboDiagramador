# ColabModel

Herramienta colaborativa de diseño de datos con asistencia de IA y generación
de backend Spring Boot.

**Primer Parcial · UAGRM · Presentación 23/09/2026**

---

## Tres piezas de software, no una

Esta distinción es fundacional. Confundirlas es el error de integración más caro.

| | Pieza | Qué es | Carpeta |
|---|---|---|---|
| **P1** | **La Herramienta** | Web colaborativa: diagramación, IA, generador, XMI | `backend/` + `frontend/` |
| **P2** | **El Backend Generado** | *Artefacto de salida* de P1. Código que P1 escribe solo | (se genera) |
| **P3** | **La App Móvil** | Asistente de voz que consume **P2**, no P1 | `mobile/` |

> **P3 no consume P1.** P3 consume el backend que P1 acaba de generar.

---

## Requisitos

| Herramienta | Mínimo | Verificar |
|---|---|---|
| JDK | 21 | `java -version` |
| Maven | 3.9 | `mvn -version` |
| Node | 20 | `node -v` |
| Docker Desktop | 4.x | `docker compose version` |
| Flutter | 3.24 | `flutter doctor` |

---

## Cómo levantarlo

### 1 · Base de datos

```powershell
docker compose up -d
docker compose ps        # debe decir healthy
```

> Si el puerto 5432 está ocupado (tenés PostgreSQL nativo en Windows), cambiá
> `"5432:5432"` por `"5433:5432"` en `docker-compose.yml` y ajustá la URL en
> `backend/src/main/resources/application.yml`.

### 2 · Backend

```powershell
cd backend
mvn spring-boot:run
```

Arranca en `http://localhost:8080`. Flyway crea el esquema en el primer
arranque. Swagger en `http://localhost:8080/swagger-ui.html`.

**IA local con Ollama** (predeterminada): descargá una vez el modelo configurado
para una RTX 3070 de 8 GB y dejá Ollama abierto antes de iniciar el backend.

```powershell
ollama pull qwen3:8b
ollama serve
```

El texto y el diagrama se procesan localmente. La configuración está en
`colabmodel.ai.ollama`: modelo 8B, contexto de 4096 tokens,
temperatura 0.1 y espera máxima de 120 segundos. Claude y Gemini siguen
disponibles como proveedores opcionales, pero no hacen falta para la Vista IA.

> **Si no arranca por un error de validación de esquema, eso es el sistema
> funcionando** (ADR-009: `ddl-auto: validate`). Significa que el mapeo JPA y
> el DDL no coinciden. Pegá el error y se corrige.

### 3 · Frontend

```powershell
cd frontend
npm install
npm run dev
```

Abre `http://localhost:5173`.

Dentro del editor, la pestaña **IA** abre la vista tipo Copilot. **Preguntar** y
**Revisar** solo analizan; **Diseñar** y **Modificar** generan una propuesta
visible que el usuario puede seleccionar y aplicar como un único `BATCH`.

### 4 · Probar la colaboración

Abrí **3 pestañas** en `http://localhost:5173`. Cada una es un usuario: en la primera
**Crear cuenta** y creá un proyecto; registrá las otras dos con otros emails y agregalas
desde el panel **Miembros** como Modelador (sección 5). Después, en cada una abrí el
diagrama y:

- Tocá **Clase**, escribí un nombre y dale a **Crear** → la entidad aparece en
  las tres pestañas con su `seq`, **sin recargar**.
- Hacé clic en una clase → se abre el panel de propiedades. Agregale un
  atributo y marcalo **MULTI**: mirá cómo aparece el doble óvalo en **Chen** y
  cómo en **Lógico** ese atributo sale a su propia tabla por 1FN (Regla 12).
- Tocá **Relación**, elegí el tipo viendo su notación, y **arrastrá de una
  clase a otra** → la relación se dibuja en las tres pestañas.
- Hacé clic sobre la línea de una relación → panel para ponerle nombre,
  cardinalidades y roles. Poné una **composición** y mirá en **Lógico** cómo su
  FK queda `NOT NULL` con `ON DELETE CASCADE`; cambiala a **agregación** y pasa
  a `SET NULL`.
- Hacé clic en una entidad → se bloquea en rojo en las otras dos con el nombre
  de quien la tiene (exclusión mutua, nivel 2).
- Alterná entre **Clases** y **Chen** → misma verdad, dos notaciones.
- Recargá con F5 → el diagrama se reconstruye solo, re-aplicando el historial
  de operaciones.

### 5 · Probar los roles (EC-11)

**Desde la interfaz**, con dos pestañas: cada una es un usuario, porque la sesión
vive en `sessionStorage` (F5 la conserva).

1. En la pestaña **A**: **Crear cuenta** y creá un proyecto. Nace con su primer diagrama.
2. En la pestaña **B**: **Crear cuenta** con otro email.
3. En **A**, panel **Miembros**: agregá el email de B como **Observador**. A B le
   aparece el proyecto **sin recargar**.
4. **B** abre el diagrama: ve *Solo lectura*, la barra de herramientas y el panel de
   propiedades deshabilitados; sí puede cambiar de vista y descargar el código. Y aunque
   mande un comando a mano, el servidor lo rechaza con `FORBIDDEN`.
5. En **A**, cambiale el rol a **Modelador**: B edita **sin recargar**.
6. En **A**, **Quitar** a B: B vuelve a *Mis proyectos* con el aviso "Te quitaron del
   proyecto", y deja de recibir operaciones.

La lista de quién puede qué está en `docs/CASOS-DE-USO.md` (CU-06) y en el ADR-026.

**Por API**, con el atajo de desarrollo, que emite un JWT real sin contraseña. Solo
existe con el perfil `dev`, así que el backend se arranca así (la interfaz no lo usa):

```powershell
cd backend
mvn spring-boot:run "-Dspring-boot.run.profiles=dev"
```

```powershell
curl -X POST "http://localhost:8080/api/v1/dev/bootstrap?role=VIEWER"
```

Cualquier comando que mande ese usuario vuelve rechazado con `FORBIDDEN`. Con
`?role=EDITOR` sí puede escribir. Sin `?role`, sos el usuario demo, propietario del
"Proyecto Demo" mientras nadie se lo haya transferido. Esas cuentas demo no pueden
iniciar sesión por la pantalla de login: su contraseña guardada no es un hash real.

### 6 · Generar el backend

Con alguna clase en el diagrama, apretá **Generar Spring Boot** (el botón azul,
a la derecha de la barra). Antes de bajar nada te dice cuántas entidades salen
y qué quedó sin soportar; el ZIP se descomprime y compila:

```powershell
cd <lo-que-descargaste>
mvn package
```

Y para arrancarlo, con la base del `docker compose` levantada (el nombre de la
base es el del diagrama, está en `application.yml`):

```powershell
docker compose exec db psql -U colabmodel -d colabmodel -c "CREATE DATABASE diagrama_demo;"
java -jar target/diagrama-demo-0.1.0.jar
```

Queda en `http://localhost:8081/api/<tabla>`. Flyway crea el esquema al
arrancar y Hibernate valida que las entidades coincidan con él.

Funciona también desde las pestañas Lógico y Físico: generar es una lectura del
modelo, no una edición.

**Antes de generar, confirmá la base** (ADR-032). En la pestaña **Físico**:
revisá las tablas (se pueden editar ahí mismo: cada cambio va al modelo),
apretá **Verificar en PostgreSQL** —ejecuta el script en una transacción y la
deshace— y después **Confirmar esquema**. El panel de Generar dice si el zip
lleva la base confirmada; si alguien editó después, lo avisa y la Vista Física
muestra qué cambió.

### 7 · Tests

```powershell
cd backend  ; mvn test      # ArchUnit (sin ciclos) + EC-11 (VIEWER rechazado) + proyectos, miembros y roles
cd frontend ; npm test      # layout radial de Chen (ADR-006) + permisos por rol y manejo del 401
cd mobile   ; flutter test  # motor de intenciones contra 8 manifiestos generados (ADR-021)
```

### 8 · App móvil

```powershell
cd mobile
flutter pub get
flutter run          # con un telefono real por USB, no el emulador
```

Al abrirla pide **la dirección del backend generado**: la IP de la máquina en
la red, no `localhost` — para el teléfono, `localhost` es el teléfono. De ahí
baja `app-manifest.json` y **aprende el dominio en ese momento**: el mismo APK
sirve para biblioteca, barbería o veterinaria (EC-10).

La interfaz es el **diálogo**, no un formulario. Pide de a un campo, y cuando
el que falta es una clave foránea **no pide un UUID**: lista la otra entidad y
la numera.

```
> crea un niño
¿Qué Año Académico?
1. Descripcion 1
2. Descripcion 2
> 2
Listo, Niño creado.
```

Sin el teléfono se puede verificar el contrato contra un backend real:

```powershell
cd mobile
dart run tool/prueba_contra_backend.dart http://localhost:8081
```

> **El dictado por voz (STT) es lo único que falta.** La costura está marcada
> en `main.dart`: lo que diga el micrófono entra por el mismo `_enviar` que el
> teclado, así que el motor de intenciones no se entera de por dónde vino el
> texto. Hoy se escribe.

---

## Estructura

```
colabmodel/
├── backend/          Spring Boot 3.5 · Java 21 · P1
│   └── src/main/java/com/uagrm/colabmodel/
│       ├── modelcore/      NÚCLEO. No depende de nadie (verificado con ArchUnit)
│       │   ├── command/    ← CONTRATO Command (ADR-002)
│       │   ├── metamodel/  ← metamodelo canónico UML + ER (ADR-003)
│       │   ├── mapping/    ← las 12 reglas: Vista Lógica y, después, codegen
│       │   ├── projection/ validation/
│       ├── codegen/        emite el proyecto Spring Boot de 5 capas (P2)
│       ├── collab/         bus, secuenciador, locks, WebSocket
│       ├── iam/ project/ config/
│       ├── interop/        importar: lectores → propuesta → BATCH (ADR-016)
│       └── ai/ vision/                       (Fases 2-3)
│       ├── vision/         lee un diagrama desde una imagen (ADR-016)
│       │   ├── Binarizacion            ← tinta vs fondo: Otsu global o Sauvola
│       │   ├── Enderezador             ← cuánto está torcida la foto
│       │   ├── DetectorDeDiagrama      ← interfaz; nivel 1 = DetectorGeometrico
│       │   ├── BuscadorDeRectas        ← Hough sobre la tinta sin explicar
│       │   ├── EnriquecedorGeometrico  ← nivel 2a: símbolos, sin modelo
│       │   ├── EnriquecedorPorPlantillas ← nivel 2a: TEXTO, sin modelo
│       │   ├── AtlasDeGlifos Rejilla   ← la fuente redibujada, y cómo se compara
│       │   ├── EnriquecedorPorModelo    ← nivel 2b: texto, por modelo local
│       │   ├── EnriquecedorPorGemini    ← nivel 2b: texto, 1 peticion a Gemini
│       │   ├── RespuestaDeModelo        ← filtra lo que contesta el modelo
│       │   └── DeteccionPropuesta      ← CONTRATO de salida, no escribe nada
│       └── ai/ interop/                      (Fases 2-3)
├── frontend/         React 18 + Vite + TS · P1
│   └── src/
│       ├── contracts/command.ts      ← ESPEJO del contrato Java
│       ├── contracts/vision.ts       ← ESPEJO de DeteccionPropuesta
│       ├── vision/traductor.ts      ← lo confirmado → un BATCH (funcion pura)
│       ├── canvas/applyOperation.ts  ← ESPEJO del efecto de CommandApplier
│       ├── canvas/chenLayout.ts      ← layout radial (ADR-006)
│       ├── logical/VistaLogica.tsx   ← solo dibuja; la derivación es del backend
│       ├── physical/edicion.ts      ← editar una tabla = un comando del modelo (ADR-032)
│       ├── physical/diagrama.ts     ← geometría derivada del diagrama físico
│       ├── toolbar/Toolbar.tsx
│       ├── importacion/              ← revisión de importación → plan → BATCH
│       ├── auth/                     ← sesión, permisos (ESPEJO de AccesoAProyecto), avisos, rutas por hash
│       ├── proyectos/                ← login, Mis proyectos, panel Miembros, confirmaciones
│       ├── Raiz.tsx                  ← sesión + navegación + avisos; App.tsx es el editor de UN diagrama
│       └── collab/wsClient.ts
├── mobile/           Flutter · P3
│   ├── lib/domain/app_manifest.dart  ← CONTRATO P2→P3 (ADR-011, ADR-021)
│   ├── lib/domain/intent.dart        ← motor de intenciones determinista (+ lexico, valores)
│   └── test/manifests/               ← los escribe el generador, no una persona
│   ├── lib/domain/app_manifest.dart  ← CONTRATO P2→P3 (ADR-011)
│   ├── lib/domain/intent.dart        ← motor de intenciones, nivel A (ADR-012)
│   ├── lib/domain/conversacion.dart  ← el diálogo: pide de a un campo
│   ├── lib/data/cliente_backend.dart ← habla con P2, no con P1
│   └── tool/prueba_contra_backend.dart ← prueba manual contra un backend real
├── canonical-models/ 7 modelos de regresión del generador
├── docs/             SAD.md · ADR.md · REGLAS-MAPEO.md
└── docker-compose.yml
```

---

## Los dos contratos

Son lo que permite que cuatro frentes trabajen en paralelo sin bloquearse.
**Si cambian, cambian en los tres lenguajes a la vez. No se interpretan: se copian.**

| Contrato | Java | TypeScript | Dart |
|---|---|---|---|
| **Command** | `modelcore/command/Command.java` | `src/contracts/command.ts` | — |
| **app-manifest** | `codegen/ManifestDeApp.java` | — | `lib/domain/app_manifest.dart` |

El espejo del manifiesto no se confía a la disciplina: `ManifestDeAppTest`
falla si lo que emite el generador difiere de `mobile/test/manifests/`, y las
pruebas del motor corren contra esos mismos archivos. Si el cambio es
intencional: `mvn test -Dtest=ManifestDeAppTest -Dmanifests.actualizar=true` y
después `flutter test` en `mobile/`.

Hay un tercer espejo, del **efecto** de cada comando: `collab/CommandApplier.java`
decide qué le pasa al modelo y `src/canvas/applyOperation.ts` repite esa misma
lógica en el cliente para redibujar sin esperar al servidor. Si se agrega o
cambia el efecto de un comando, se cambia en los dos. El servidor manda: el
cliente solo re-aplica operaciones que el bus ya aceptó.

Y un cuarto, del **rol**: `AccesoAProyecto.permite` decide qué puede hacer cada rol y
`src/auth/permisos.ts` lo repite para que la interfaz deshabilite lo que el servidor de
todos modos rechazaría (ADR-026). Los dos prueban **la misma tabla**, celda por celda
(`AccesoAProyectoTest` y `permisos.test.ts`). Igual para el aviso privado de cambio de
acceso: `collab/AvisoDeAcceso.java` ↔ `src/auth/avisos.ts`.

---

## Estado: Fase 0 (Inicio RUP) — completa

- [x] Monorepo, Docker Compose, CI
- [x] Contrato `Command` en Java + TypeScript
- [x] Contrato `app-manifest` en Dart
- [x] Esquema completo en Flyway (metamodelo UML + anotaciones ER)
- [x] Bus de comandos con secuenciador de un hilo por diagrama
- [x] LockManager (exclusión mutua nivel 2) con TTL
- [x] WebSocket STOMP: ops, presencia, locks, errores privados
- [x] Canvas SVG con vistas Clases y Chen
- [x] Layout radial de Chen **con pruebas unitarias**
- [x] 7 modelos canónicos de regresión
- [x] ArchUnit: `modelcore` sin dependencias, sin ciclos

**Elaboración (días 2-6), hito LCA — checklist completa:**

- [x] Aplicar el efecto de los comandos sobre `uml_class` / `uml_attribute` / `uml_relationship`
  (`CommandApplier`; los métodos llegaron con la Regla 6 y `BATCH` con la importación, ADR-016)
  (`CommandApplier`; `ADD_METHOD`/`REMOVE_METHOD` y `BATCH` ya no se rechazan)
- [x] Barra de herramientas Fase 0 (Clase, Relacion con tipo, indicador en vivo)
- [x] JWT + roles OWNER/EDITOR/VIEWER (escenario EC-11: verificado, un VIEWER
  autenticado es rechazado con FORBIDDEN al intentar escribir)
- [x] Snapshot real en `GET /diagrams/{id}` (el cliente arranca del snapshot,
  no re-aplicando todo el log; los tres roles leen, quien no es miembro recibe 403)
- [x] Vista Lógica derivada (reglas 1, 2, 3, 4, 8, 10, 11 y 12; verificada
  contra los 7 modelos canónicos, que hasta ahora no los ejercitaba nadie)
- [x] Esqueleto del generador: 1 entidad → 5 capas → `mvn package` verde
  (verificado con el modelo 06 de nombres hostiles: 3 entidades, jar construido)
- [x] Edición del modelo conceptual: panel de propiedades para renombrar la clase,
  agregar/editar/quitar atributos y marcar sus banderas ER. El lease se mantiene
  mientras la clase está seleccionada, con renovación por latido (ADR-004)
- [x] Arrastrar las clases en el canvas (`MOVE_ELEMENT`; nivel 3, sin lease,
  last-writer-wins). Un clic sin desplazamiento selecciona; arrastrar mueve
- [x] Relaciones dinámicas: se crean **arrastrando** de una clase a otra, se
  eligen viendo su notación UML dibujada, y se editan en un panel (nombre,
  cardinalidades, roles, identificadora). El canvas dibuja rombo hueco/relleno,
  triángulo hueco y punteados, con la línea cortada en el borde de cada caja
- [x] **Regla 7 (fetch/cascade)** en el backend generado: `@ManyToOne` con `LAZY`
  explícito, lado inverso con `mappedBy`, cascade según el tipo de relación
  (composición `ALL`+`orphanRemoval`, agregación `{PERSIST, MERGE}`) y
  `@EntityGraph` en los listados contra el N+1. El DTO lleva el id, no la entidad
- [x] **La API generada se puede consumir desde un frontend**: el listado acepta
  un filtro por cada FK (`GET /api/pedido?clienteId=…`) con el mismo nombre con
  que el id viaja en el DTO y en `app-manifest.json` —hasta acá el `inverseOf`
  del manifiesto prometía una ruta que la API no tenía, y el cliente se bajaba
  la tabla entera para filtrar en memoria—; el filtro baja hasta la consulta
  con el mismo `@EntityGraph`, así que no reabre el N+1. Y un cuerpo inválido
  devuelve **qué campo** falló (`{"error": …, "errores": {"titulo": "…"}}`) en
  vez del JSON por defecto de Spring, que un formulario no puede usar para
  marcar nada. Verificado arrancando el proyecto generado contra H2 y llamándolo
  > **Recorte declarado:** se aplica **un** filtro, el primero que venga.
  > Cruzar dos necesita Specifications. Tampoco hay paginación todavía: el
  > listado devuelve todas las filas.
- [x] **Recursión**: una clase puede relacionarse consigo misma. Se dibuja como
  un bucle (una línea de borde a borde mediría cero) y se acomoda como cualquier
  otra. En 1:N da una FK a su propia tabla; en N:M los extremos se distinguen
  por el **rol**, porque si no salen dos columnas con el mismo nombre y el
  esquema no compila. Heredar de sí misma se rechaza
- [x] Líneas acomodables: arrastrar sobre una relación seleccionada le crea un
  **quiebre**, se arrastra para moverlo y doble clic lo saca. Se guardan en
  `diagram_layout.waypoints` (columna que ya existía) y viajan por
  `MOVE_ELEMENT`: son geometría, nivel 3, sin comando nuevo (ADR-014)
- [x] La Vista Lógica respeta las **propiedades de cada relación**: composición
  → FK `NOT NULL` + `ON DELETE CASCADE`; agregación → `SET NULL`; asociación →
  `RESTRICT`; generalización → PK compartida. Las cardinalidades ya decidían
  1:1, 1:N y N:M; ahora se pueden cargar desde la interfaz

- [x] **Generar Spring Boot desde la aplicación**: el botón azul de la barra
  pide `GET /diagrams/{id}/codegen/resumen`, muestra cuántas entidades salen,
  qué archivos trae y **qué dejó afuera** (Regla 10: se avisa antes, porque un
  zip ya descargado no puede advertir nada a tiempo), y recién entonces baja el
  proyecto de `GET /diagrams/{id}/codegen`. El zip sale de la **misma lectura
  del modelo** que el snapshot y la Vista Lógica, así que lo que se vio en
  pantalla es lo que se entrega. Generar es una **lectura**: no toma lock, no
  escribe y no entra al log de operaciones, así que no agrega un comando al
  catálogo cerrado (ADR-014) y funciona también desde las vistas de solo lectura

- [x] **Regla 6 (métodos)**: el UML deja de perder la mitad del modelo al
  guardar. `ADD_METHOD`/`REMOVE_METHOD` se rechazaban con un "todavía no está
  implementado"; ahora persisten en `uml_method` (tabla que existía desde
  Fase 0, sin usar), se dibujan en el **tercer compartimento** de la caja y
  aterrizan en el `Service` generado como stubs con la firma del UML y
  `UnsupportedOperationException` —el generador escribe la firma, no el cuerpo:
  inventar comportamiento sería pasar de traducir un modelo a adivinarlo. No
  van a la Entity ni al DDL: la base guarda **estado**, el objeto guarda
  estado **y comportamiento**. Un método que choca con el CRUD del Service se
  avisa y no se emite, porque el criterio del hito es que lo generado compile.
  No hay `UPDATE_METHOD`: el catálogo está cerrado (ADR-014), así que cambiar
  una firma es quitarla y volver a agregarla

- [x] **Los 4 puntos de ambigüedad OO ↔ Datos**, con su tabla
  `modeling_decisions` (que también existía desde Fase 0 sin usar). La
  herramienta **detecta** las construcciones donde el modelo de objetos y el de
  datos no dicen lo mismo, muestra **las dos lecturas** y deja que el equipo
  elija; la elección se guarda y **cambia de verdad el esquema derivado y el
  código generado**. El valor por defecto de cada punto es el comportamiento
  histórico, así que agregar esto no movió ningún esquema por su cuenta.
  Contestar entra por el bus (`SET_MODELING_DECISION`): es una edición
  atómica, que es justo lo que ADR-014 permite —sigue sin existir ningún
  comando de generación masiva

- [x] **Enumeraciones** (`«enumeration»`): se crean desde la barra, sus
  literales se cargan en el panel y se usan como tipo de un atributo. Generan un
  `enum` de Java guardado por nombre y un `CHECK` en la base con los mismos
  literales. Verificado arrancando lo generado: la API rechaza un literal
  inexistente y la base también, aunque se escriba por SQL sin pasar por la API
- [x] **Herencia (Regla 4)**: JOINED por defecto y **SINGLE_TABLE** como opción en
  la clase raíz (migración V2). Al implementarla apareció que en JOINED **no se
  podía crear ninguna subclase** (la Entity hija salía suelta, sin `extends`);
  corregido. Las dos estrategias se verificaron arrancando lo generado y
  creando, listando polimórficamente y borrando en una jerarquía de tres niveles

- [x] **Rangos (Regla 9)**: un atributo `int`, `long` o `decimal` puede declarar mínimo y
  máximo desde el panel (migración V3). Sale como `CHECK` en la base y como
  `@Min/@Max` (o `@DecimalMin/@DecimalMax`) en el DTO. Un rango imposible se
  rechaza al entrar. Verificado arrancando lo generado: fuera de rango la API
  responde 400 y la base lo rechaza aunque se escriba por SQL

- [x] **Valores embebidos (Regla 5)**: botón **▣ Valor** para crear una clase
  `«embeddable»`, que se usa como tipo de un atributo o se compone 1..1 (la
  composición 1..1 sobre una clase común sigue preguntándose en A-2). Se genera
  una clase `@Embeddable` de verdad con su DTO, y el dueño la lleva `@Embedded`
  con `@AttributeOverrides`; antes A-2 dejaba campos sueltos. Verificado
  arrancando lo generado: el valor viaja anidado en el JSON, se valida por
  dentro y el mismo `Direccion` sirve a dos dueños sin tabla propia

- [x] **Sugerencia de tipo y rol por el nombre del atributo** (`InferenciaDeAtributos`,
  función pura en `modelcore`). Mientras se escribe el nombre en el panel, el tipo
  se preselecciona (`fechaPrestamo` → `date`, `montoMulta` → `decimal`,
  `numeroTelefono` → `string`) con su motivo y confianza. Reconoce el **rol**:
  `idFichaPrestamo` en su propia clase es la identidad (no hace falta), `idLibro`
  en otra clase es una **relación** con `Libros` (botón para crearla), `isbn` o
  `codigo` son clave natural. Lo escrito en el modelo manda (`real` → `decimal`).
  Es la base para leer una foto o importar un SQL: la IA solo tendrá que leer
  nombres y símbolos. Sugiere, no decide

- [x] **`BATCH` + pantalla de revisión de importación** (ADR-016). Un lote de
  cambios entra como **una operación**: una transacción, un `seq`, todo o nada.
  Verificado en vivo: con un nombre repetido en el tercer cambio no queda
  ninguno y el `seq` no se mueve; con un lock de otra sesión se rechaza
  diciendo cuál cambio chocó. Botón **↥ Importar**: el backend (`interop/`)
  lee el archivo y **propone** sin escribir; la pantalla muestra cada clase y
  atributo con su sugerencia, marca lo que **ya existe** en el diagrama,
  destilda la identidad, ofrece convertir `idCliente` en **relación** y avisa
  lo que no se va a aplicar. Lo confirmado vuelve por el bus. Hoy lee el JSON
  de `canonical-models/` (los 7 se leen en las pruebas); **XMI y SQL son el
  próximo lector**, y solo tienen que producir un `ModeloLeido`: la revisión y
  la atomicidad ya están

- [x] **Módulo de IA local con pantalla de revisión (ítem 4.4), sin voz**
  (ADR-017). `ProveedorDeIA` (`ai/`) es una interfaz con tres implementaciones
  intercambiables por configuración, Claude API, **Gemini (Google AI Studio)** y
  Ollama local (`colabmodel.ai.proveedor`): el resto del modulo no sabe cual
  esta activa. Hoy arranca en `ollama` con `qwen3:8b`, contexto 4096
  y temperatura 0.1, para medir primero el modelo dentro de los 8 GB de la RTX
  3070. `ProveedorGeminiTest` verifica la alternativa de Google contra un Google
  simulado, sin clave y sin internet: la clave viaja en la cabecera y nunca en
  la URL, el catalogo cerrado viaja como `functionDeclarations` sin traducir el
  esquema, el razonamiento interno de los modelos 3.x no se muestra, y el 429
  de la cuota gratuita llega con el motivo de Google en vez de como "fallo la
  IA".
  El *function-calling schema* (`EsquemaDeComandos`) sale del catalogo cerrado
  con un `switch` sin `default` sobre `CommandType`: agregar un comando rompe
  la compilacion hasta decidir si la IA lo puede llamar. La respuesta del
  modelo se valida del lado del servidor **antes** de tocar el bus: un tipo
  fuera del catalogo se rechaza, una referencia a una clase inexistente se
  rechaza con motivo, y de lo valido se aceptan como maximo 32 cambios
  (`Lote.TOPE_TEXTO_O_VOZ`) — suficiente para un modelo pequeno completo;
  lo que sobra se trunca y se avisa
  explicitamente, en vez de rechazar el pedido entero. Verificado con un
  proveedor simulado que responde 30 comandos (mezclados con tipos inventados
  y referencias a clases que no existen): nunca se aceptan mas de 8, y ese
  mismo `BATCH` aplicado de verdad contra `CommandApplier` tampoco crea mas de
  8 clases. `POST /diagrams/{id}/ai/propuestas` devuelve la propuesta para
  revisar (mismo patron que importar, ADR-016); no hay endpoint que aplique,
  confirmar es mandar el `BATCH` por el bus con origen `TEXT` o `VOICE`.
  La pestaña **IA** implementa cuatro modos: consultar, revisar, diseñar y
  modificar. Los dos primeros no reciben herramientas y por tanto no pueden
  alterar el modelo; los otros devuelven cambios seleccionables que se aplican
  como un unico `BATCH`. Falta el reconocimiento de voz y persistir el hilo de
  conversacion para compartirlo entre todos los integrantes.

- [x] **Lector SQL (ítem 4.1)** (ADR-018). `interop/LectorSql`, propio y sin
  dependencias: lee `CREATE TABLE` y `ALTER TABLE ... ADD` (FK, PK, UNIQUE) y
  cuenta en una advertencia todo lo demás; una sentencia rota se saltea sin
  tumbar el archivo. Produce un `ModeloLeido`, así que la revisión y el `BATCH`
  no cambiaron. Re-detecta solo lo estructural: PK que es FK → herencia JOINED,
  PK de dos FK sin columnas → N:M, FK UNIQUE → 1:1, `ON DELETE` → composición o
  agregación, nombre de la FK → rol. SINGLE_TABLE, `@Embeddable`, A-1..A-4,
  abstractas, largos y `CHECK` **no se adivinan**: se declaran, con una pista
  cuando hay señal (`tipo` con `CHECK IN`, prefijo repetido). Criterio de
  equivalencia: el esquema derivado del modelo reimportado es **el mismo DDL**
  que el del original, salvo esas pérdidas declaradas. Verificado con una
  prueba por canónico que recorre generador → lector → propuesta → aplicar sin
  cambios → generador: **los 7 vuelven byte a byte**. Una prueba más fija que un
  SINGLE_TABLE con embebido generado por nosotros **no** vuelve igual y queda
  declarado, y otras cubren DDL ajeno (`REFERENCES` en línea, `SERIAL`,
  esquemas, comentarios, un `;` dentro de una cadena, entidad débil)

- [x] **Lector XMI (ítem 4.2)** (ADR-019). `interop/LectorXmi` sobre StAX, sin
  dependencias ni namespaces ni DTD (XXE). Lee XMI 1.x y 2.x con tres perfiles
  que se deciden **por el contenido de cada elemento, no por el encabezado**:
  diagrama de clases UML, ERD («entity», «attribute», «relationship», con
  cardinalidad Chen) y modelo de datos de Enterprise Architect («table»,
  «column», «PK», «FK»; este último pasa por la misma ingeniería inversa que el
  SQL). Reconoce el estereotipo escrito de siete maneras distintas según la
  versión de EA. **Nunca tumba la importación por algo que no entiende**: el XML
  roto se sanea o se lee hasta donde llega, cada clase se lee aislada, y lo que
  queda afuera se lista en la pantalla de revisión; el único error es un
  archivo sin ninguna clase, entidad ni tabla. La pantalla de importación ya
  acepta `.xmi`, `.xml`, `.uml` y `.sql`. Verificado: los **7 canónicos**
  exportados como lo harían **Enterprise Architect, Papyrus y ArgoUML**, y como
  **modelo de datos de EA** (estereotipos en la Extension o como perfil),
  vuelven con **el mismo DDL**; y hay pruebas que cortan el archivo en ~38
  puntos, lo corrompen al azar, le mezclan casos de uso, máquinas de estado e
  interfaces, y le meten un `&nbsp;`, sin una sola caída. Aparecieron y se
  corrigieron tres defectos: la relación reflexiva se leía como N:M, la
  multiplicidad que Papyrus omite dejaba todo opcional, y los TaggedValue de
  UML 1.4 no se resolvían. Los **métodos** (Regla 6) ya viajan enteros —nombre,
  tipo de retorno, parámetros— desde el archivo hasta la pantalla de revisión,
  con su propio tilde, y entran como `ADD_METHOD` en el mismo `BATCH` que las
  clases y los atributos

- [x] **Exportación XMI (ítem 4.2, ADR-013)** — *recorte de ADR-020 revertido
  por ADR-022*. Botón **↧ Exportar XMI** en la barra, al lado de Importar: muestra
  lo que UML 2.5.1 no puede llevarse **antes** de guardar el archivo y funciona también
  para un Observador. Por debajo es `GET /diagrams/{id}/xmi`, de solo lectura (mismo
  permiso que el zip del generador): `interop/EscritorXmi` escribe el diagrama vivo como
  **XMI 2.5.1 / UML 2.5.1 estándar y nada más** (ADR-024) —sin `xmi:Extension` de EA, sin `pathmap://`
  de Eclipse, sin inventar un perfil propio—. Lo que UML 2.5.1 puro no
  representa (las decisiones A-1..A-4, la estrategia de herencia, el
  estereotipo, y de un atributo `unique`/clave parcial/rango cuando no son
  también la PK) se **declara** en vez de forzarse con un tagged value
  propietario, con el mismo mecanismo de "equivalencia salvo pérdidas
  declaradas" que ya usa el lector SQL (ADR-018): la lista viaja en la
  respuesta (`X-Colabmodel-Perdidas`) y dentro del propio archivo
  (`xmi:Documentation`). Verificado con los **7 canónicos**, por el camino
  real —exportar, reimportar con **nuestro propio** `LectorXmi`, pasar por la
  misma propuesta que revisa el usuario, aplicar sin tocar nada, volver a
  derivar el DDL—: 6 vuelven con el **mismo DDL byte a byte**; el 7º
  (`04-herencia`) pierde declaradamente un `unique` que no es también PK, y la
  prueba afirma esa pérdida en vez de ignorarla. **Lo que esto NO prueba:**
  nada se abrió todavía en un Enterprise Architect real — ver "Pendiente esta
  semana" más abajo

- [x] **Diagramas de clases con Enterprise Architect, en las dos direcciones**
  (ADR-031). **↧ Exportar XMI** ofrece dos formatos: **Enterprise Architect**
  (el de entrada) escribe XMI 2.1 en el dialecto de EA —paquete, ids `EAID_`,
  tipos `EAnone_*`— con la `xmi:Extension` que trae el estereotipo y el
  **diagrama dibujado** con la misma disposición que el lienzo, y sale como
  `.xml`; **UML 2.5.1 estándar** es el de antes, sin cambios. Al importar, el
  lector toma la posición de cada clase del diagrama de EA (2.1 y 1.1) o de UML
  DI, y la revisión la respeta debajo de lo que ya está dibujado. Verificado
  con los mismos canónicos por el perfil de EA (`EnterpriseArchitectXmiTest`).
  **Lo que esto NO prueba:** que el EA del docente dibuje el diagrama al
  importarlo — ver "Pendiente esta semana"

- [x] **Esquema de `app-manifest.json` y motor de intenciones del móvil (ítem 4.3),
  sin UI ni voz** (ADR-021). El generador emite el manifiesto en
  `static/app-manifest.json` (lo sirve el backend generado) **con los mismos
  campos con que escribe el DTO**: entidades con ruta REST, abstractas y
  herencia; campos con tipo, obligatorio, único, largo, literales de la
  enumeración y rango; referencias y lado inverso; sinónimos derivados del
  nombre. El motor (`mobile/lib/domain/intent.dart`) es **determinista**
  (gramática + coincidencia difusa, sin LLM): resuelve "crear libro con título
  X" contra un dominio que nunca vio, doblando plural, género, tildes y errores
  de dictado sobre el vocabulario del manifiesto. **Un empate se pregunta**
  (dos entidades, origen/destino, "fecha" entre dos fechas, una abstracta, texto
  sin campo) y **un valor que no encaja se rechaza con motivo** antes de P2
  (tipo, largo, rango, literal, fecha inexistente). El espejo se verifica:
  `ManifestDeAppTest` compara lo que emite el generador para los 7 canónicos y
  una biblioteca (enumeración, rango, único) con `mobile/test/manifests/`, que
  es contra lo que corren las 43 pruebas del motor (`flutter test`).
  **Recorte declarado: solo crear y listar.** Actualizar y borrar no se
  ejecutan (se reconocen para decir que no están), no hay cola offline, ni
  filtros al listar, ni valores embebidos o listas dictados; la referencia
  dicha por nombre ("el socio Ana") queda pendiente para la capa que consulte
  P2. Falta: la pantalla conversacional, la voz y esa capa HTTP

- [x] **Área de trabajo recorrible**, como en draw.io: el panel dejó de ser un
  rectángulo fijo de 1200×700. Ahora hay una **hoja** que rodea al dibujo con
  700 unidades de aire a cada lado, se dibuja entera y **la desplaza el
  navegador**, así que hay **barras de desplazamiento de verdad** y se recorre
  en cualquier dirección: arrastrando el fondo, con la rueda, con Shift+rueda a
  lo ancho, con el botón del medio o con la barra espaciadora sobre lo que sea.
  El zoom va de 20 % a 300 % con Ctrl+rueda (anclado al puntero), los botones o
  el teclado, y **⤢ encuadra todo el modelo**; ese encuadre también se hace
  solo al entrar, porque un diagrama que quedó fuera del hueco inicial se veía
  como un lienzo vacío. La hoja **crece sola** cuando se arrastra una clase
  hacia afuera —incluso a coordenadas negativas— y el punto de vista se
  sostiene: si la hoja se corre bajo los pies, se compensa el scroll para
  seguir mirando lo mismo. Mirar no es editar: no se toca ni una coordenada
  del modelo, así que no se genera `MOVE_ELEMENT` ni se le contagia a los
  demás de la sesión, cada uno mira donde quiere. El arrastre de clases, los
  quiebres y el conector siguen cayendo donde está el mouse a cualquier escala,
  porque las coordenadas salen de la matriz del SVG y no de una conversión a mano

- [x] **La defensa se hace con Claude, en texto y en imagen** (ADR-025, cierra
  la decisión pendiente del ADR-017). `ai/ClienteClaude` es la única conexión,
  por el SDK oficial, y la usan el prompt de texto y el nivel 2b de visión:
  modelo `claude-opus-5` con esfuerzo `low`, caché del prompt sobre
  herramientas y sistema, y herramientas estrictas. Lo beta —modo rápido y
  fallback ante un rechazo de los clasificadores— se prueba en cada pedido y
  **se apaga solo** si la cuenta no lo tiene: lo peor que puede pasar es que
  ande a velocidad normal. `vision/EnriquecedorPorClaude` manda **todos** los
  recortes en un solo pedido, con respuesta JSON de esquema garantizado, y
  cada texto sigue pasando por el filtro de `RespuestaDeModelo`. Verificado
  contra la API real con `mvn test -Dtest=PruebaRealClaude` (no corre con
  `mvn test`: gasta crédito). Eso destapó dos cosas, ya corregidas: Opus 5
  crea las clases y espera antes de agregarles atributos, así que se le
  contesta "registrado" y sigue (hasta 4 vueltas), y el filtro de texto
  descartaba los atributos privados (`- titulo: String`)

- [x] **Gestión de usuarios, proyectos y roles (CU-06, ADR-026)** — *Construcción*:
  cierra el "no hay login" de CU-01. La interfaz ya no entra por `/dev/bootstrap`: hay
  **Iniciar sesión** y **Crear cuenta**, **Mis proyectos** con la insignia de rol y,
  dentro de cada proyecto, su lista de diagramas y el panel **Miembros**. Los roles son
  **por proyecto** —Propietario, Modelador, Observador— y los decide un solo lugar,
  `AccesoAProyecto`. El Propietario es implícito (`projects.owner_id`), único, y la
  migración **V6** lo hace cumplir en la base. Solo se agregan usuarios **ya
  registrados**, y transferir la propiedad es una sola transacción. Un cambio de rol se
  aplica **en caliente**: el próximo comando ya se evalúa con el rol nuevo, se sueltan
  sus locks, el afectado recibe un aviso privado y su interfaz pasa a *Solo lectura*
  —o vuelve a "Mis proyectos" si lo quitaron, con sus suscripciones cortadas desde el
  servidor—. Se cerraron dos agujeros que aparecieron al hacerlo: cualquier usuario
  autenticado podía **suscribirse por WebSocket a un proyecto ajeno**, y un error
  inesperado del servidor llegaba como 401. El token vive en `sessionStorage` (una
  pestaña es un usuario) y la navegación es por hash (F5 no saca a nadie del diagrama).
  `/dev/bootstrap` solo existe con el perfil `dev`. Verificado con pruebas contra
  PostgreSQL de verdad y con un recorrido de dos pestañas en un navegador. **Fuera de
  alcance:** invitaciones por correo, recuperación de contraseña, OAuth, refresh tokens
  y permisos por diagrama

- [x] **Deshacer y rehacer (ADR-027, ADR-029, ADR-030)** — *Construcción*. Botones
  **↶ ↷** y **Ctrl+Z / Ctrl+Y / Ctrl+Shift+Z** para **todo lo propio**: mover clases y
  acomodar líneas, renombrar, editar atributos y relaciones, marcar banderas ER,
  contestar ambigüedades, **crear y borrar** clases, atributos, métodos y relaciones, y
  **una importación entera** (un solo Ctrl+Z). El tooltip dice qué se va a deshacer.
  Es **local**: cada pestaña deshace lo suyo, no la última operación del diagrama, que
  puede ser de otro. Un undo **no reescribe la historia**: es un comando común del
  catálogo por el bus, con seq nuevo, marcado con `deshace` (se ve con ↶ en el panel de
  operaciones). Lleva además `esperado`: si alguien cambió **lo mismo** después, el
  servidor **rechaza en vez de pisar** y la interfaz dice qué no se pudo deshacer y por
  qué; si cambió **otro** campo, lo tuyo se revierte y lo suyo queda. Respeta los
  locks. **Borrar una clase se deshace entero** —atributos, métodos, relaciones y
  quiebres, con sus mismos ids— aunque el servidor los borre en duro: el cliente arma la
  restauración antes de borrar. No hay comando nuevo, migración ni cambio en `Origin`.
  Verificado con undos y ediciones ajenas **simultáneos** contra el bus real
  (`DeshacerConcurrenteTest`). **Recorte declarado:** un lote con decisiones de mapeo
  no se deshace, y recargar la página vacía las pilas

- [x] **Exclusión mutua de nivel 2 completa (ADR-028)** — *Construcción*. El bus
  solo revisaba el lease cuando el comando traía `classId`: editar o quitar un
  atributo, quitar un método, marcar una bandera ER o editar una relación **pasaban
  sin control** aunque otra sesión tuviera la clase tomada. Ahora el servidor resuelve
  la entidad leyendo el modelo (un atributo pide el lease de su clase) y una relación
  tiene **su propio lease**, que la interfaz toma al seleccionarla. Verificado caso por
  caso, también dentro de un `BATCH` (`LeaseDeTodaLaSemanticaTest`)
- [x] **Vista Física editable y base confirmada antes de generar** (ADR-032),
  etapa 1. La tabla elegida se edita en su lugar —nombre, tipo, largo de
  `varchar`, NOT NULL, UNIQUE, rango, agregar y quitar columnas, renombrar la
  tabla, y en cada FK su nombre (el rol), si admite NULL (la multiplicidad) y su
  `ON DELETE` (el tipo de relación)—, y **cada cambio es un comando del catálogo
  que ya existía** sobre el elemento que produjo la columna: no hay segundo
  esquema, y al volver a Clases se ve lo mismo. Lo que sale de otra parte (la
  identidad, la herencia, un valor embebido, las tablas puente) queda fijo y dice
  dónde se cambia. **Verificar en PostgreSQL** ejecuta el script de verdad en una
  transacción que se deshace y dice qué creó o en qué línea falló; **confirmar**
  (`CONFIRM_SCHEMA`, por el bus) guarda la huella del DDL si es el que se revisó y
  PostgreSQL lo acepta. Si alguien edita después, la vista lo avisa y muestra la
  diferencia línea por línea, y Generar lo dice antes de bajar el zip (409 si se
  pide el zip de una base que ya cambió). Verificado en el navegador contra un
  modelo de biblioteca: 5 tablas, 4 FK, 2 índices y 6 restricciones creadas y
  deshechas, sin esquemas temporales colgados
- [x] **Diagrama físico** (ADR-032, etapa 2): la Vista Física arranca en
  **Diagrama** —además de **Tablas** y **Script**—, con todas las tablas y sus FK
  en notación pata de gallo (muchos, uno, puede faltar), el `ON DELETE` a la vista
  cuando no es RESTRICT, y las derivadas (puente, multivaluado) punteadas. Se
  recorre como el lienzo de Clases: barras de verdad, Ctrl+rueda anclado al
  puntero, arrastre del fondo o barra espaciadora, `+` `-` `0` y `F`, y se
  encuadra solo hasta que uno toca la vista. Clic en una tabla abre el mismo
  editor abajo, como en MySQL Workbench; el buscador atenúa lo que no coincide.
  La geometría es **derivada** (como la de Chen, ADR-006): cada tabla va donde
  está su clase en Clases, nunca encimada, así que para acomodarla se mueve la
  clase y la tabla la sigue en vivo. Una FK sale a la altura de SU columna y
  llega a la de la clave; entre tablas apiladas va de borde a borde, para no
  cruzar a la vecina

- [x] **Clase intermedia de una N:M, dibujada en el canvas.** Una N:M ya derivaba
  su tabla puente, y contestar A-4 como lectura OO le ponía `id` surrogado y le
  hacía emitir las cinco capas — pero esa clase asociativa vivía **solo en lo
  derivado**: en el diagrama seguía habiendo una línea entre dos cajas, así que no
  se podía arrastrar, ni conectarle una tercera clase, ni **ponerle atributos**. Y
  los atributos son el motivo por el que una N:M se reifica: un atributo pertenece
  siempre a una clase (`classId`), nunca a una relación, así que `fechaPrestamo` no
  tenía dónde ir mientras el préstamo fuera una línea. Ahora el panel de una
  relación N:M ofrece convertirla: sale **un** `BATCH` con cuatro pasos —crear la
  clase, dos asociaciones `1` → `0..*` hacia ella y borrar la N:M—, todos comandos
  que ya estaban en el catálogo cerrado (ADR-014), así que no hay ningún comando de
  transformación nuevo. La dirección no es arbitraria: la intermedia queda del lado
  **"muchos"** en las dos, porque la Regla 3 pone la FK ahí y las dos claves
  foráneas tienen que caer en la tabla intermedia — exactamente las mismas que
  tenía la tabla puente. El borrado va **último**: si el nombre choca o alguien
  tiene tomada una de las clases, el lote entero se rechaza y la N:M original sigue
  en su lugar, en vez de quedar un modelo sin la relación y sin la clase que la
  reemplaza. En una N:M **reflexiva** se arrastran los roles (o se inventan
  `origen`/`destino`), porque sin roles distintos las dos FK se llamarían igual y
  el esquema no compila

- [x] **La clase intermedia puede llevar PK compuesta**, que es la forma clásica
  de la tabla puente: `VENDE(NroV, CodP, cantidad, precio)`. Sale de marcarla
  **débil** con sus **dos relaciones identificadoras** (Regla 11), y el botón de
  clase intermedia lo ofrece con una casilla, encendida por defecto. Implementarlo
  destapó un defecto viejo del derivador: una entidad débil solo podía tener **una**
  relación identificadora —`findFirst()`— así que con dos, la segunda se daba por
  materializada y **su clave foránea no se emitía en ningún lado**. La tabla salía
  con una sola FK, sin la otra relación y **sin un aviso**: una relación dibujada
  que no llegaba al DDL ni se denunciaba, que es justo el modo de falla que la
  Regla 10 prohíbe. Ahora cada identificadora aporta su columna a la PK; sin la
  casilla, la intermedia lleva `id` propio y dos FK comunes, que es la lectura que
  permite al generador emitirle las cinco capas. Verificado con el ejemplo clásico
  NotaVenta / Vende / Producto en `ClaseAsociativaTest`

**El hito LCA exige que funcionen CU-01, CU-04 y CU-05.** Si el día 6 no
funcionan, la arquitectura no está validada y el plan se replantea, no se
continúa.

> **Estado del hito.** CU-01, CU-04 y CU-05 están escritos en
> [`docs/CASOS-DE-USO.md`](docs/CASOS-DE-USO.md), cada uno con su evidencia y
> con lo que todavía no cumple. **Los tres cumplen el criterio de salida.**
> Al verificarlos aparecieron cuatro defectos, ya corregidos. En CU-01, la
> exclusión mutua no se aplicaba entre pestañas del mismo usuario y la
> resincronización que anunciaba la pantalla no ocurría. En CU-05, el backend
> generado **compilaba pero no arrancaba** (las FK referenciaban tablas todavía
> no creadas) y, una vez arrancado, su API respondía 500 casi siempre.
>
> **Construcción, lo que falta**: persistir y sincronizar el hilo de la Vista IA,
> el reconocimiento de voz, probar el lector XMI **y el exportador** con un archivo real
> del docente (ver abajo) y, en la app móvil P3, la pantalla conversacional, la
> voz y la capa HTTP contra P2 (el esquema del manifiesto y el motor de
> intenciones ya están — ADR-021). Tampoco actualizar ni borrar desde el móvil:
> recorte declarado del ítem 4.3, el asistente solo crea y lista.
>
> `vision/` **sí está** (ADR-023): niveles 1 y 2, panel de confirmación y
> aterrizaje por `BATCH`. **Sin nada instalado y sin red** ya lee la geometría,
> los símbolos de los extremos y **el texto** de una captura (nivel 2a, por
> plantillas). El nivel 2b lee con Claude (ADR-025) en cuanto está la clave;
> Gemini (ADR-033) y un modelo local quedan como alternativas, y se elige una con
> `colabmodel.vision.nivel2.proveedor`. Lo único que ninguna capa local puede es
> la letra manuscrita: eso siempre es del 2b.

### Pendiente esta semana: pedirle al docente un XMI de su Enterprise Architect

El lector XMI está probado contra dialectos que **imitamos nosotros**, y el
exportador contra **su propio lector**: ninguno de los dos pasó todavía por un
Enterprise Architect de verdad. Hay cuatro cosas que solo un archivo real de su
EA puede confirmar: dónde guarda el `ON DELETE` de una FK, cómo marca una
columna NOT NULL, en qué extremo pone el rombo de una composición en XMI 2.1
(ADR-019), y si el XMI que exportamos **se abre** en su EA sin errores
(ADR-022) — esto último es la parte del riesgo que no se puede probar sin su
archivo, y hay que poder decirlo en la demo si no se llega a probar antes.

**Y al revés, con el EA del docente** (ADR-031): exportar un diagrama con
**↧ Exportar XMI › Enterprise Architect**, y en su EA hacer clic derecho sobre un
paquete › **Import/Export › Import Package from XMI…** (Ctrl+Alt+I) con ese
`.xml`. Lo que hay que mirar: que se cree el paquete *ColabModel* **con su
diagrama "Diagrama de clases" dibujado**, no solo las clases sueltas en el
*Project Browser*. Si solo aparecen las clases, el modelo entró y lo que falla
es la Extension: anotar la versión de EA y el mensaje del log de importación.

**Qué pedirle** (un paquete chico alcanza, 4 a 6 clases):

1. **Un diagrama de clases** con al menos una herencia, una composición, una
   agregación, multiplicidades distintas de 1 y, si lo usa, un atributo marcado
   como identificador.
2. **Un modelo de datos** («table») si trabaja con ellos: PK, al menos una FK con
   *On Delete = Cascade*, una columna *Not Null* y una *Unique*.
3. **Un ERD** solo si lo usa en la materia.
4. La **versión de EA** (*Help › About EA*) y una **captura del diagrama**, para
   comparar lo importado contra lo que él ve.

**De qué menú se exporta**, con el paquete seleccionado en el *Project Browser*:

- EA 15 / 16: cinta **Publish › Model Exchange › Export-XML › Export XML for
  Current Package**.
- Cualquier versión: clic derecho sobre el paquete › **Import/Export › Export
  Package to XMI…**, o **Ctrl+Alt+E**.
- En el diálogo, **XML Type = XMI 2.1**. Si no le cuesta, que exporte el mismo
  paquete una segunda vez con **XMI 1.1**. Que **no** tilde la opción de excluir
  los tagged values de EA: ahí viaja la mitad de la información.

> Los nombres exactos del menú cambian entre versiones de EA: si no coinciden,
> lo que se busca es "Export Package to XMI" / "Export XML for Current Package".

**Cuando llegue**: guardarlo en `backend/src/test/resources/xmi/docente/`,
importarlo desde **↥ Importar** y revisar las advertencias. Lo que diga ahí que
quedó afuera es lo que hay que ajustar, y el archivo se vuelve una prueba más de
`LectorXmiToleranciaTest`.


---

## Leer un diagrama desde una imagen (`vision/`, ADR-023)

Se llama **imagen** y no *pizarra* porque el contrato `Origin` dice `IMAGE`
desde la Fase 0: una captura de proyector, un screenshot o un PDF exportado
entran por el mismo camino — y son además los casos **fáciles**, porque no
tienen perspectiva, ni brillo, ni letra manuscrita.

El trabajo está partido en dos niveles, con degradación elegante (ADR-012):

| | Qué resuelve | Estado |
|---|---|---|
| `Binarizacion` | Separar tinta de fondo. Otsu **global** en una captura limpia; **Sauvola por ventanas** cuando la imagen trae mucho gris intermedio, que es la firma de una foto con sombra. Funciona igual en tema claro y oscuro | ✅ |
| `Enderezador` | Estimar y corregir la inclinación de una foto | ✅ |
| **Nivel 1** `DetectorGeometrico` | Geometría y topología: qué cajas hay, sus compartimentos, dónde hay texto dentro de cada una, y qué líneas unen qué cajas — incluso quebradas en L y reflexivas | ✅ **implementado**, Java puro, sin dependencias nuevas |
| `BuscadorDeRectas` | Las líneas que el análisis de corridas no ve: **diagonales y punteadas**, con Hough sobre la tinta que quedó sin explicar | ✅ |
| **Nivel 2a** `EnriquecedorGeometrico` | El rombo o el triángulo de cada extremo, midiendo la figura **aislada** del resto del recorte. **Sin modelo** | ✅ siempre disponible |
| **Nivel 2a** `EnriquecedorPorPlantillas` | **El texto**, redibujando la fuente en memoria y comparando. **Sin modelo, sin red y sin nada instalado** | ✅ encendido por defecto |
| **Nivel 2b** `EnriquecedorPorClaude` | Lo que las plantillas no pudieron —sobre todo letra manuscrita— con la API de Claude, **todos los recortes en un pedido** (ADR-025) | ✅ implementado, activo en cuanto hay `ANTHROPIC_API_KEY` |
| **Nivel 2b** `EnriquecedorPorGemini` | Los **mismos** recortes, pero todos en **una sola** petición a Gemini | ✅ implementado, se enciende solo con `GEMINI_API_KEY` |
| **Nivel 2b** `EnriquecedorPorModelo` | Lo mismo con un modelo de visión local (Ollama / llama.cpp), de a un recorte | ✅ implementado, alternativa sin conexión (`proveedor: local`) |

**Por qué hay un enderezador.** El detector lee corridas de tinta por fila y
por columna, así que una línea inclinada no se ve más débil: se astilla en una
escalera de trocitos más cortos que el mínimo y desaparece entera. Medido
sobre el mismo diagrama, **antes** de tener enderezado:

| Inclinación | 0° | 1° | 2° | 4° | 6° |
|---|---|---|---|---|---|
| Clases detectadas | 2 | **0** | **0** | **0** | **0** |

No es una degradación suave, es un acantilado — y nadie saca una foto a 0.0°.
Con el enderezado, los cinco casos dan 2 clases y 1 relación, con los mismos 9
segmentos que el control alineado.

**La foto con sombra ahora se intenta leer.** `pareceFoto` ya detectaba la
iluminación despareja — mucho gris intermedio — pero sólo para **avisar** que la
lectura iba a ser peor. El umbral seguía siendo uno solo para toda la imagen, que
es lo peor posible en ese caso: con la ventana a un costado, el lado iluminado del
fondo queda todo fondo y el lado en sombra queda todo tinta, así que se pierde
medio diagrama. Cuando la imagen tiene esa firma se binariza con **Sauvola**, un
umbral por ventana calculado con la media y la desviación local, sobre dos
integrales acumuladas para que el costo no dependa del tamaño de la ventana.

> Lo intuitivo sería "si la vecindad es lisa, que mande el umbral global", y es
> exactamente el error: el umbral global es lo que falla en esa foto. La fórmula de
> Sauvola ya se porta bien en una zona lisa —pide ser un 18 % más oscuro que la
> media— y lo único que le falta es un piso **absoluto**, porque en una zona muy
> oscura ese 18 % son tres niveles de gris y el ruido del sensor los pasa.

**La diagonal y la punteada ahora se leen.** El análisis de corridas es de ejes,
así que una diagonal se le astilla en escalones y una punteada no llega al largo
mínimo ni siendo horizontal: las dos daban *todas* las clases y **cero**
relaciones. Se avisaba — la Regla 10 lo exige — pero un aviso no es una lectura, y
había que volver a dibujar a mano una relación que estaba ahí. Hoy, sobre la tinta
que quedó **sin explicar** (fuera de toda caja y de todo trazo recto), se ajustan
rectas con Hough; una recta se propone como relación **sólo si sus dos puntas
llegan a dos cajas distintas**, y el estilo del trazo decide el tipo:

| Cobertura de la recta | Se propone como |
|---|---|
| ~1.0, trazo continuo | `ASSOCIATION` |
| < 0.8, huecos periódicos | `DEPENDENCY` |

Eso abrió un camino que no existía: **`DEPENDENCY` era un tipo de relación al que
la lectura de imágenes no podía llegar**, porque la única forma de dibujarla es
con línea punteada. Tres filtros la sostienen, y cada uno está por un falso
positivo concreto: **grosor** (un renglón de texto entre dos clases es una recta
horizontal perfecta de 12 px de alto; una línea de diagrama tiene 1 a 3),
**cobertura** y **largo**. Y lo que no engancha a dos cajas **se descarta y se
sigue avisando**: encontrar una recta no es haberla leído, y dar por interpretada
una que después se descarta sería lo peor de los dos mundos.

**El nivel 1 sigue sin leer una sola letra, y ese es el punto.** Deja publicado el
recorte exacto donde habría que leerla. Sin eso, al modelo hay que preguntarle
*"leeme esta pizarra y dame un modelo UML"* sobre una imagen entera: miles de
tokens, minutos de CPU sin GPU y una respuesta con atributos inventados. Con eso,
las preguntas son triviales — *"transcribí este texto"*, *"¿rombo relleno, rombo
hueco, triángulo o nada?"* — y para la primera ya no hace falta ningún modelo.

### Leer el texto sin modelo (`EnriquecedorPorPlantillas`)

El texto de un diagrama **exportado** no es escritura: es una fuente tipográfica
renderizada. Entonces no hay que reconocerlo, hay que **volver a dibujarlo y
comparar** — y `java.awt.Font` ya viene en el JDK, así que no hay dependencia
nueva, ni pesos que descargar, ni servidor que levantar. Tres pasos:

1. **segmentar** — la tinta se proyecta sobre el eje x: cada grupo de columnas
   contiguas es un glifo y un hueco ancho es un espacio. Funciona porque el
   recorte del nivel 1 ya es **un renglón**: la geometría le ahorró al
   reconocimiento el trabajo de encontrar líneas, bloques y columnas;
2. **acertar el tamaño** — se barren tamaños de fuente y gana el que mejor explica
   el renglón. No es un detalle: los glifos se comparan **a su tamaño real en
   píxeles**, así que el alto de la figura (9 px una minúscula, 12 una mayúscula)
   ya separa la `C` de la `c` sin ningún rasgo adicional;
3. **clasificar** cada glifo con esa fuente y promediar las confianzas.

Medido sobre texto renderizado en SansSerif, leyendo renglones completos:

| Tamaño del texto | Resultado |
|---|---|
| 16–20 px (diagrama a tamaño natural) | exacto: `Estudiante`, `nombre: String`, `getNombre()`, `fecha: Date` |
| 12–14 px (captura reducida) | casi todo; confunde `l` con `I`, que en una fuente sin serifas son **el mismo dibujo** |
| ≤ 11 px | `null` en vez de arriesgar |
| manuscrito | no lee, y no hay forma de que lea: eso es del nivel 2b |

Sobre un diagrama de tres clases y nueve renglones: **6,3 s la primera lectura del
proceso y 0,24 s las siguientes**. La diferencia es AWT inicializando su motor de
fuentes y dibujando los atlas, que después quedan cacheados; por eso el
presupuesto de tiempo de esta capa es de 12 s y no de 4 — con 4, la primera
petición perdía los últimos renglones y la propuesta parecía incompleta sin
explicación.

**La virtud no es la precisión, es el modo de falla.** Cuando no reconoce, el costo
de la comparación se dispara y la lectura se descarta: *ante la duda, `null`*. Un
modelo chico, en cambio, contesta **siempre**, y su error es un nombre plausible
que nadie va a volver a mirar. Un recorte vacío es una celda que el usuario
completa en el panel; `Estudlante` con una ele es el nombre de una tabla. Y es
**determinista**: la misma imagen da siempre el mismo texto, así que un fallo se
reproduce y se arregla en vez de discutirse.

Va **antes** del modelo en la cadena (`@Order(15)` contra `20`) por dos razones: lo
que se lee acá sale gratis, y así el presupuesto del modelo queda entero para los
recortes que ninguna plantilla pudo leer. El modelo ya **no vuelve a preguntar**
por un texto que otra capa resolvió.

> En un contenedor pelado no hay ninguna fuente instalada y AWT no dibuja nada: ahí
> esta capa se declara **no disponible** y la detección sigue por el nivel 1,
> igual que cuando no hay modelo. Para habilitarla en ese caso alcanza con dejar un
> `.ttf` en `backend/src/main/resources/fuentes/ocr.ttf`.

El nivel 1 **siempre responde**. Si el nivel 2 no está, falla o tarda de más,
la propuesta sale igual con las cajas sin nombre: fea pero usable. Esa caída
está escrita en `VisionRestController`, no prometida en un documento.

El lector de texto usa Claude por defecto: alcanza con la clave en
`ANTHROPIC_API_KEY` (ver *Cómo levantarlo*). Se elige **uno** de los tres con
`colabmodel.vision.nivel2.proveedor`: no se suman, porque dos leyendo los
mismos recortes es pagar dos veces lo mismo.

```yaml
colabmodel.vision.nivel2.proveedor: gemini   # la clave va en GEMINI_API_KEY
```

Para leer sin conexión con un modelo local:

```yaml
colabmodel.vision.nivel2.proveedor: local
colabmodel.vision.nivel2.url: "http://localhost:11434"   # Ollama
colabmodel.vision.nivel2.modelo: "qwen2.5vl:3b"
colabmodel.vision.nivel2.plantillas: true   # el OCR local; en false para medir sin él
```

**Contra Claude y contra Gemini los recortes van todos juntos, en UNA sola
petición**, y esa es la diferencia de fondo con el modelo local. Contra un
modelo local, una pregunta por recorte es gratis. Contra una API en la nube son
veinte peticiones, y el plan gratuito limita las peticiones **por minuto**: una
foto de seis clases pasa las treinta y la mitad volvería con 429, por una razón
que no tiene nada que ver con la foto. La estrategia del nivel 1 no cambia — los
recortes numerados siguen siendo preguntas triviales y el modelo sigue sin ver
nunca la imagen entera, así que tampoco puede inventar una clase que la
geometría no vio.


Sin clave (Claude o Gemini) o sin URL (local) = sin nivel 2b. **El sistema no
necesita ningún modelo para funcionar**: el 2a clasifica los símbolos igual — y
esa es la diferencia entre proponer una composición (FK con `ON DELETE CASCADE`)
y una agregación (`SET NULL`) — y además lee el texto con plantillas.

El nivel 2b tiene un **presupuesto de tiempo total**: al agotarse devuelve lo
leído hasta ahí con un aviso. Media propuesta es útil; una petición que no
vuelve, no. Y **nada de lo que contesta el modelo entra sin filtrar**: una
disculpa, una frase o un párrafo se descartan, porque un nombre inventado que
nadie mira termina siendo el nombre de una tabla.

```powershell
curl -X POST "http://localhost:8080/api/v1/diagrams/<id>/vision/deteccion" `
     -H "Authorization: Bearer <token>" -F "imagen=@diagrama.png"
```

### El aterrizaje

Lo que el usuario confirma pasa por `vision/traductor.ts`: acuña un UUID por
elemento, ordena (clases → atributos → relaciones, porque `CREATE_RELATION`
valida que sus dos clases existan), resuelve los `ref` a esos UUID y descarta
lo que quedó colgando en vez de inventarlo. Sale **un** `BATCH`.

`CommandApplier` lo aplica **todo o nada**: misma transacción, **un solo
`seq`**, una sola entrada en el log. Una foto a medio aplicar deja el modelo en
un estado que nadie pidió, y un cliente que se reconecta vería veinte
operaciones a medio camino en vez de una.

El `BATCH` es un **contenedor, no una capacidad nueva**: adentro solo entran
comandos de edición atómica del catálogo cerrado, por el mismo `aplicarEfecto`
que usa el mouse. El tope de 8 del ADR-014 es por *enunciado de la IA* y sigue
intacto; el del lote (500) acota el trabajo de una transacción.

> Un lote se revisa **comando por comando** también en los locks. `BATCH` no
> requiere lease, así que sin eso bastaba meterle adentro un `ADD_ATTRIBUTE`
> sobre una clase ajena para escribirla mientras otra persona la tenía tomada.
> El lote no es una excepción a la exclusión mutua, es un sobre.

> **La detección no escribe en el modelo.** Devuelve candidatos: no toma lock, no
> entra al log de operaciones y no agrega ningún comando al catálogo cerrado
> (ADR-014). Lo que el usuario confirme se emitirá después como `Command`s con
> `origin: IMAGE` por el mismo bus que el mouse (ADR-002).
>
> Pide permiso de **escritura** aunque no escriba: un VIEWER no podría
> aterrizar la propuesta, y éste es el único endpoint que decodifica un binario
> arbitrario de afuera. Es el criterio opuesto al del zip de codegen, que sí va
> con permiso de lectura porque entrega algo que el VIEWER ya veía en la Vista
> Lógica.

---

## Decisiones que hay que conocer antes de tocar código

1. **Un solo camino de escritura** (ADR-002). Mouse, voz, texto, visión y XMI
   producen el mismo `Command`. Nunca agregues un segundo camino de escritura.
2. **Proyección, no conversión** (ADR-003). Cambiar de vista **nunca** borra
   información. Los métodos siguen ahí cuando estás en Chen.
3. **La geometría de los atributos es derivada** (ADR-006). No la persistas.
4. **El catálogo de comandos es cerrado** (ADR-014). No agregues un comando de
   generación masiva: su ausencia es lo que cumple el requisito del docente.
5. **UUID como PK** en el backend generado (ADR-007). Es lo que elimina la
   reconciliación de identificadores en la sincronización offline.
6. **La cardinalidad se guarda en UNA forma canónica (la de UML) y se
   *proyecta* a la notación que el usuario elija** (`0..*` ↔ `(0,N)`). Nunca se
   convierten los datos guardados: la conversión sería destructiva —en Chen,
   `0..*` y `1..*` se escriben igual— y además rompería la Vista Lógica, que
   decide 1:N o N:M buscando un `*`. Es el mismo principio del ADR-003.
7. **Clases es el lienzo; Físico se edita a través del modelo** (ADR-032).
   Clases es la vista interactiva y multiusuario: ahí se crea, se mueve y
   actúan los locks. Lógico es una proyección de **solo lectura**. Físico se
   edita tabla por tabla, pero **no es un segundo esquema**: cada cambio es un
   comando sobre la clase, el atributo o la relación que produjo esa columna,
   con el mismo lock por clase que en Clases. Lo que UML no puede expresar no
   se ofrece para editar ahí.
8. **La notación es UML 2.5.1, estrictamente** (ADR-017). En la vista de
   Clases un atributo se escribe `- /saldo: decimal [0..1] = 0 {id}`: la clave
   primaria es el modificador `{id}` y **no** un subrayado, porque en UML
   subrayar significa *estática*. El subrayado de clave es notación ER y vive
   en Chen. La multiplicidad se escribe cuando no es `1..1`, que es lo que UML
   asume si se la omite.
9. **La Vista Lógica se deriva en el backend**, no en el cliente. La misma
   derivación (`modelcore/mapping`) alimentará a `codegen`: si el cliente
   tuviera su propia copia de las 12 reglas, lo que se demuestra en pantalla
   dejaría de ser lo que se entrega.

Las decisiones completas (ADR-001 a ADR-033) están en `docs/ADR.md`.
