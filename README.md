# ColabModel

Herramienta colaborativa de diseño de datos con asistencia de IA y generación
de backend Spring Boot.

**Primer Parcial · UAGRM · Presentación 23/09/2026**
**Segundo Parcial · estado al 02/11/2026** → ver el [Informe del proyecto](#informe-del-proyecto-2do-parcial--02112026)

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

## Informe del proyecto (2do parcial · 02/11/2026)

Resumen de lo que hay y de lo que falta. **No es una lista cerrada:** lo que dice
"pendiente" o "abierto" es una propuesta, y quien siga el trabajo (por ejemplo, el
compañero a cargo de la IA local) puede cambiar el enfoque si lo ve mejor.

### Lo que está hecho

| Área | Qué hay | Cómo se probó |
|---|---|---|
| **Diagramación colaborativa** (P1) | Varios usuarios editan el mismo diagrama en vivo (STOMP), con bloqueo por elemento, roles Propietario / Modelador / Observador, versiones tipo Git y deshacer/rehacer | Pruebas automáticas del backend y del frontend; uso con varias pestañas |
| **Vistas del modelo** (P1) | Clases (UML), Lógico (12 reglas de mapeo), Físico (DDL que se verifica en PostgreSQL) y Documental (cómo queda en MongoDB) | Pruebas contra 7 modelos de referencia |
| **IA como un integrante más** (P1) | Pestaña **IA** tipo chat del equipo: Preguntar, Revisar, Diseñar y Modificar. El historial es compartido y en vivo. La IA **recuerda la conversación** del diagrama, **sabe quién le pregunta** (nombre y rol) y se **configura por proyecto** (Mentor, Senior o Revisor, nivel de detalle e instrucciones del equipo). Las propuestas se aplican de una vez y quedan marcadas. Dictado por voz en el navegador | Pruebas automáticas con un proveedor simulado; base PostgreSQL real para la configuración |
| **Generador de backend** (P1 → P2) | Proyecto Spring Boot listo para producción en **PostgreSQL** o en **MongoDB** (la parte NoSQL): CRUD completo, paginación, validación, bloqueo optimista, errores uniformes, perfiles `dev`/`prod` y Docker | 9 proyectos generados compilan y pasan sus pruebas; verificador CRUD contra un backend real |
| **Consola del backend generado** (P2) | Página en `http://localhost:8081/` que viene dentro del zip y sirve para **cualquier dominio**: listar, crear, editar, borrar, elegir relaciones de una lista, paginar y ver los errores | Recorrido en un navegador contra **PostgreSQL real y MongoDB real** |
| **App móvil** (P3) | APK de Android. **Asistente** por diálogo, escrito o **por voz**, contra el backend generado (aprende el dominio del manifiesto). **Visor en vivo** del diagrama de la herramienta | Teléfono real (Galaxy A71) por la red local |
| **Importar y leer diagramas** (P1) | Importación XMI y lectura de un diagrama desde una imagen | Pruebas automáticas |

### Lo que falta o queda por confirmar

| Qué | Situación | Sugerencia (abierta) |
|---|---|---|
| **Respuestas reales de la IA local** | El código no depende de un modelo, pero el backend acepta solo los del catálogo (`qwen3:8b`, `qwen3.5:9b`) y no se probó con uno instalado | Lo decide quien lleve la IA local: instalar uno de esos modelos, cambiar el catálogo o usar otro proveedor (Claude o Gemini ya están soportados) |
| **Contexto del modelo local** | Con Ollama el presupuesto es de 4096 tokens. En modo Modificar, las instrucciones y herramientas ya ocupan unos 2600, así que en diagramas grandes la IA recorta la memoria del chat o avisa que no entra | Subir `colabmodel.ai.ollama.contexto` si el modelo y la placa lo permiten, o ajustar `colabmodel.ai.memoria.*` |
| **Voz en el teléfono** | El dictado funciona; falta repetir en el teléfono el envío automático tras el silencio, responder una pregunta dictando y «cancelar» por voz | Probarlo en el ensayo de la demo |
| **Ensayo general de la demo** | Hay un guion en [`docs/DEMO-2DO-PARCIAL.md`](docs/DEMO-2DO-PARCIAL.md) (dominio Gimnasio, unos 22 minutos), todavía sin ensayar de punta a punta | Ensayarlo con la máquina despejada (más de 3 GB libres) |
| **XMI del Enterprise Architect del docente** | La importación existe, pero no se probó con un archivo real del docente | Conseguir el archivo y probarlo |
| **Detalles menores conocidos** | La consola ofrece hasta 200 opciones por relación. El panel de configuración de la IA no se actualiza en vivo. La interfaz web no tiene pruebas de componentes montados (no hay jsdom) | Mejoras opcionales |
| **Vista Chen** | Se retiró de la interfaz a propósito: se trabaja con UML, las anotaciones ER están en el panel de propiedades y la cardinalidad se puede mostrar al estilo Chen | Si el docente la pide como vista aparte, se puede volver a mostrar (el código de diagramación Chen sigue en `frontend/src/canvas/chenLayout.ts`) |

### Dónde seguir leyendo

- [`docs/SAD.md`](docs/SAD.md): arquitectura. [`docs/ADR.md`](docs/ADR.md): decisiones y por qué.
- [`docs/REGLAS-MAPEO.md`](docs/REGLAS-MAPEO.md), [`docs/CASOS-DE-USO.md`](docs/CASOS-DE-USO.md), [`docs/PLAN-DE-PRUEBAS.md`](docs/PLAN-DE-PRUEBAS.md).
- [`docs/DETALLE-TECNICO.md`](docs/DETALLE-TECNICO.md): historia del 1er parcial, lectura desde imagen y decisiones que hay que conocer antes de tocar código.

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

> **La IA local (el modelo y Ollama) la lleva otro compañero del equipo.**
> El backend solo acepta los modelos del catálogo (`qwen3:8b` y `qwen3.5:9b`):
> con otro modelo la Vista IA responde que no está permitido. Instalar el modelo
> o cambiar el catálogo es tarea de ese compañero, no del resto del equipo.

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

El historial de la IA es **compartido**: lo que pide un miembro aparece en las
pestañas de los demás sin recargar (`/topic/diagram/{id}/ai`). Una propuesta queda
marcada como **aplicada** solo cuando el servidor aceptó ese `BATCH`. El botón
**Dictar** usa la voz del navegador (Chrome o Edge; en Firefox se desactiva y avisa)
y el pedido viaja con origen `VOICE`.

La vista se lee como un **chat del equipo**: cada pedido con el nombre y la
inicial de su autor (decidido por usuario, no por nombre), los propios de un lado y los
de los demás del otro, y la respuesta de la IA indicando a quién le contesta. La IA
**recuerda la conversación** del diagrama y **sabe quién pregunta**: recibe los
últimos turnos con su autor, marca los de quien pregunta y, si el pedido y el diagrama no
entran en el contexto del modelo, responde 422 antes de llamarlo (lo escrito no se pierde).
El panel **Configuración de la IA** fija el estilo del proyecto (Mentor, Senior o Revisor),
el detalle y unas instrucciones del equipo de hasta 1000 caracteres: lo ven todos y **solo el
propietario lo cambia**. El estilo no cambia el modo, los permisos ni lo que la IA puede proponer.

### 4 · Probar la colaboración

Abrí **3 pestañas** en `http://localhost:5173`. Cada una es un usuario: en la primera
**Crear cuenta** y creá un proyecto; registrá las otras dos con otros emails y agregalas
desde el panel **Miembros** como Modelador (sección 5). Después, en cada una abrí el
diagrama y:

- Tocá **Clase**, escribí un nombre y dale a **Crear** → la entidad aparece en
  las tres pestañas con su `seq`, **sin recargar**.
- Hacé clic en una clase → se abre el panel de propiedades. Agregale un
  atributo y marcalo **MULTI**: en **Lógico** ese atributo sale a su propia
  tabla por 1FN (Regla 12). Las anotaciones ER (multivaluado, derivado, clave
  parcial, entidad débil) se editan desde este panel.
- Tocá **Relación**, elegí el tipo viendo su notación, y **arrastrá de una
  clase a otra** → la relación se dibuja en las tres pestañas.
- Hacé clic sobre la línea de una relación → panel para ponerle nombre,
  cardinalidades y roles. Poné una **composición** y mirá en **Lógico** cómo su
  FK queda `NOT NULL` con `ON DELETE CASCADE`; cambiala a **agregación** y pasa
  a `SET NULL`.
- Hacé clic en una entidad → se bloquea en rojo en las otras dos con el nombre
  de quien la tiene (exclusión mutua, nivel 2).
- Cambiá el selector **Cardinalidad** entre **UML (0..\*)** y **Chen (0,N)** → solo
  cambia cómo se muestran las multiplicidades; lo guardado no se toca.

> La **vista Chen** se sacó de la interfaz a propósito: se trabaja solo con UML
> (`frontend/src/App.tsx`). Lo que aportaba no se perdió: las anotaciones ER se
> editan en el panel de propiedades y siguen alimentando las reglas 11 y 12 de la
> Vista Lógica, y la notación Chen de cardinalidad queda en el selector.
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
a la derecha de la barra). En el panel se elige la base: **PostgreSQL** o
**MongoDB** (ADR-035; la variante MongoDB es la parte NoSQL del 2do parcial).
Antes de bajar nada te dice cuántas entidades salen y qué quedó sin soportar; el
ZIP se descomprime y se arranca:

```powershell
cd <lo-que-descargaste>
mvn spring-boot:run
```

**Perfiles del backend generado** (están en el `README.md` que trae el zip):

| Perfil | PostgreSQL (variante) | MongoDB (variante) |
|---|---|---|
| `dev` (por defecto) | H2 en memoria con el DDL de Flyway y filas de ejemplo: **no hay que instalar nada** | un MongoDB local en `mongodb://localhost:27017` |
| `postgres` | el PostgreSQL local de desarrollo, mismo DDL | — |
| `prod` | `DB_URL`, `DB_USER` y `DB_PASSWORD` por variables de entorno (sin ellas no arranca) | `DB_URL` con la URI de MongoDB |

```powershell
mvn package
$env:SPRING_PROFILES_ACTIVE="prod"; $env:DB_URL="jdbc:postgresql://127.0.0.1:55432/tienda"; $env:DB_USER="colabmodel"; $env:DB_PASSWORD="colabmodel"
java -jar target/tienda-0.1.0.jar
```

Queda en `http://localhost:8081/api/<recurso>`. Con PostgreSQL, Flyway crea el
esquema al arrancar y Hibernate valida que las entidades coincidan con él. El zip
trae también `Dockerfile` y `docker-compose.yml`.

**Consola de prueba en `http://localhost:8081/`**: viene dentro del zip y se
arma sola leyendo `app-manifest.json`, así que sirve para cualquier dominio. Crea,
edita (PATCH con la versión leída: si otro guardó en el medio avisa el 409, conserva
el borrador y ofrece recargar) y borra. Las relaciones se eligen de una lista, pagina
y ordena con el total de `X-Total-Count` y muestra los errores de la API junto a cada
campo. Se recorrió en un navegador contra **PostgreSQL 16 real y MongoDB 7**.
Límites: los objetos embebidos se editan como JSON y una relación ofrece hasta 200
opciones. En `prod` Swagger está apagado.

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

**Pruebas contra PostgreSQL real.** Algunas pruebas del backend (por ejemplo,
`ConfiguracionIAPostgresTest`) solo corren si se les da una base;
sin la propiedad se omiten. Con el `docker compose` levantado (puerto 55432):

```powershell
cd backend ; mvn test "-Dcolabmodel.test.db=jdbc:postgresql://localhost:55432/"
```

`mvn test` completo pide memoria: en una máquina justa conviene
`$env:MAVEN_OPTS="-Xmx256m"; mvn test "-DargLine=-Xmx512m"`.

Las pruebas de la consola del backend generado (`PlantillaConsolaTest`) ejecutan su
JavaScript con **Node**; si la máquina no tiene Node se omiten y lo avisan en la salida.

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

**APK de Android**: `mobile/android/` ya está generado (permiso de Internet,
HTTP en claro hacia la IP de la red local y nombre «ColabModel»). Se compila con
`flutter build apk --debug` y la CI tiene un job `apk` que lo deja como artefacto.
Se probó en un teléfono real (Galaxy A71, Android 13) por la red local: el
asistente contra un backend generado y el visor contra la herramienta.

**Ver diagrama en vivo**: la otra entrada de la app. Entra con la cuenta de la
herramienta, lista proyectos y diagramas y muestra el diagrama **en solo lectura**.
Los cambios que otros hacen en la web llegan solos por STOMP, y si se corta la red
avisa «Sin conexión» y al volver recupera lo que se perdió.

**Voz en el asistente**: el botón del micrófono dicta con el reconocedor del
teléfono (`speech_to_text`, en español, prefiriendo reconocer en el dispositivo). Una frase
con pausas se junta en una sola y se envía sola tras unos 2 s de silencio, o al tocar ■;
mientras escucha se ve «Escuchando… Tocá ■ para terminar y enviar». Lo dictado entra por el
mismo camino que lo escrito, así que el asistente no se entera de por dónde vino, y una frase
se envía una sola vez aunque se toque Enviar a mitad del dictado. Sin permiso del micrófono,
el botón se desactiva con el motivo y se sigue escribiendo. En el teléfono se probó que una
frase dictada llega entera y crea el registro; el envío automático tras el silencio se vuelve
a comprobar en el ensayo general.

**Preguntas pendientes**: si el asistente pide un dato («falta nombre»), la respuesta
suelta («Ana Pérez») completa ese pedido en vez de perderse o confundirse. Decir «cancelar»
corta el diálogo sin crear nada.

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

