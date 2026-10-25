# Plan de pruebas manual — ColabModel

Guion para **probar a mano** todo lo que está construido, en el orden en que se
puede probar: primero entrar, después editar, después derivar, y al final las
tres salidas (generador, exportación, móvil). Cada caso dice **qué hacer** y
**qué tiene que pasar**; si lo que pasa es otra cosa, eso es un defecto y hay que
anotarlo con el número del caso.

> **No se prueba sobre el diagrama de la biblioteca.** Creá un diagrama nuevo
> para esto y borralo al terminar. Las clases `Docente`, `Estudiante`,
> `FichaPrestamo`, `Libros`, `MaterialBibliiografico`, `Periodicos`, `Persona` y
> `Revistas` son el modelo de la defensa y no se tocan.

**Leyenda:** ✅ pasó · ❌ falló (anotar qué pasó) · ⏭️ no aplica todavía.

---

## 0 · Preparación

Los comandos van de a uno, en `cmd`. La rama con la lectura de imágenes está en
el árbol de trabajo, así que **la ruta de los comandos es esa**; si ya la
fusionaste a `master`, usá `C:\Users\DIEGO\PROYECTOS\colabmodel`.

| # | Qué hacer | Qué tiene que pasar |
|---|---|---|
| 0.1 | `docker compose up -d` en `C:\Users\DIEGO\PROYECTOS\colabmodel` | `docker compose ps` dice **healthy** |
| 0.2 | `cd C:\Users\DIEGO\PROYECTOS\colabmodel\.claude\worktrees\software-features-analysis-012a68\backend` y después `mvn spring-boot:run` | Arranca en `http://localhost:8080` sin error de validación de esquema. Flyway aplica las migraciones |
| 0.3 | En otra consola: `cd C:\Users\DIEGO\PROYECTOS\colabmodel\.claude\worktrees\software-features-analysis-012a68\frontend`, `npm install`, `npm run dev` | Abre `http://localhost:5173` |
| 0.4 | `mvn test` en `backend` | **499 tests, 0 fallos** |
| 0.5 | `npm test` en `frontend` | Verde (layout de Chen y traductor de visión) |
| 0.6 | `flutter test` en `mobile` | 43 pruebas del motor de intenciones, verde |

Si 0.4 falla, **no sigas**: lo que sea que falle acá va a falsear todo lo demás.

---

## 1 · Entrar (IAM, JWT, roles — EC-11)

> **Ojo con esto antes de empezar:** *no hay pantalla de acceso todavía*. El
> frontend llama solo a `POST /api/v1/dev/bootstrap` al cargar y entra siempre
> como **OWNER** de un diagrama nuevo. Es el atajo de desarrollo, no un login: no
> hay registro, ni contraseña, ni forma de elegir el rol desde la interfaz. Los
> roles **sí** están de verdad en el backend, así que se prueban por API. Si el
> caso de uso exige una pantalla de acceso, eso es trabajo pendiente y conviene
> anotarlo como tal en vez de darlo por probado acá.

| # | Qué hacer | Qué tiene que pasar |
|---|---|---|
| 1.1 | Abrir `http://localhost:5173` | Entra solo, como OWNER, con un diagrama listo. La barra muestra el `seq` en vivo |
| 1.2 | `curl -X POST "http://localhost:8080/api/v1/dev/bootstrap"` | Devuelve un JWT real y un `diagramId`, con rol **OWNER** |
| 1.3 | Crear una clase en la interfaz | Aparece y el `seq` avanza |
| 1.4 | `curl -X POST "http://localhost:8080/api/v1/dev/bootstrap?role=EDITOR"` y usar ese token contra la API | Puede escribir igual que el OWNER |
| 1.5 | `curl -X POST "http://localhost:8080/api/v1/dev/bootstrap?role=VIEWER"` y pedir `GET /api/v1/diagrams/{id}` con ese token | **Lee** el diagrama sin problema |
| 1.6 | Con ese mismo token de VIEWER, mandar un comando de escritura por la API | Rechazado con **FORBIDDEN**. Que la interfaz esconda el botón no cuenta: el backend tiene que decir no |
| 1.7 | Con un token válido, pedir un diagrama del que **no** sos miembro | **403**, diciendo que no sos miembro del proyecto |
| 1.8 | Pedir cualquier endpoint **sin** cabecera `Authorization` | **401**, nunca un 500 ni una respuesta vacía |
| 1.9 | Recargar con F5 | El diagrama se reconstruye desde el snapshot, no re-aplicando todo el log |

---

## 2 · Colaboración en vivo (CU-01)

Abrí **tres pestañas** sobre el mismo diagrama, cada una con su token (2 OWNER/EDITOR y 1 VIEWER).

| # | Qué hacer | Qué tiene que pasar |
|---|---|---|
| 2.1 | Crear una clase en la pestaña A | Aparece en B y C **sin recargar**, con el mismo `seq` en las tres |
| 2.2 | Mirar el indicador de presencia | Las tres sesiones se ven listadas |
| 2.3 | Hacer clic en una clase en A | En B y C esa clase se marca **en rojo con el nombre de quien la tiene** (exclusión mutua) |
| 2.4 | Intentar editar en B la clase que A tiene tomada | No se puede; el rechazo es privado de B, no un error global |
| 2.5 | Soltar la selección en A y esperar | El lock se libera (TTL) y B ya puede tomarla |
| 2.6 | Editar la misma clase desde A y B casi al mismo tiempo | No hay dos verdades: el secuenciador ordena y las tres pestañas terminan igual |
| 2.7 | Mover una clase en A (arrastrar) | Se mueve en B y C. Mover **no** toma lock (nivel 3, último que escribe gana) |
| 2.8 | F5 en las tres | El diagrama vuelve idéntico, re-aplicando el historial |
| 2.9 | Cortar el backend, editar, volver a levantarlo | La pestaña avisa que perdió la conexión y **no** finge que el cambio entró |

---

## 3 · Editar el modelo conceptual (CU-04)

| # | Qué hacer | Qué tiene que pasar |
|---|---|---|
| 3.1 | **Clase** → nombre → Crear | Caja con tres compartimentos en el canvas |
| 3.2 | Clic en la clase → panel → renombrar | Cambia en las tres pestañas |
| 3.3 | Agregar un atributo escribiendo `fechaPrestamo` | El tipo se **preselecciona** `date` solo, con su motivo y confianza |
| 3.4 | Escribir `montoMulta` | Sugiere `decimal`; escribir `numeroTelefono` sugiere `string` (no número) |
| 3.5 | Escribir `idLibro` en una clase que no es `Libros` | Ofrece **crear la relación** con `Libros` en vez de dejar una FK a mano |
| 3.6 | Marcar un atributo como **MULTI** (multivaluado) | En **Chen** sale el doble óvalo; en **Lógico** ese atributo se va a **su propia tabla** (1FN, Regla 12) |
| 3.7 | Marcar identidad, clave natural, requerido | Las banderas se guardan y se reflejan en la derivación |
| 3.8 | Agregar un **método** con parámetros | Aparece en el **tercer compartimento** de la caja |
| 3.9 | Quitar un método | Se va. No existe "editar método": se quita y se agrega (catálogo cerrado) |
| 3.10 | Crear un atributo `int` con **rango** mínimo 1 y máximo 5 | Se acepta; con mínimo 5 y máximo 1 se **rechaza al entrar** |
| 3.11 | **«enumeration»** → crear `Estado` con literales `ACTIVO`, `BAJA` | Se puede usar como **tipo** de un atributo |
| 3.12 | **▣ Valor** → crear `Direccion` «embeddable» con calle y ciudad | Se puede usar como tipo de un atributo de dos clases distintas |
| 3.13 | Marcar una clase como raíz con herencia **SINGLE_TABLE** | Se guarda; marcarla en una clase que **no** es raíz avisa que la estrategia se elige en la raíz |

---

## 4 · Relaciones

| # | Qué hacer | Qué tiene que pasar |
|---|---|---|
| 4.1 | **Relación** → elegir el tipo **viendo su notación dibujada** → arrastrar de una clase a otra | La línea aparece en las tres pestañas, con rombo/triángulo/punteado según el tipo |
| 4.2 | Clic sobre la línea → panel | Se puede poner nombre, **cardinalidades** y roles |
| 4.3 | Poner `1` y `0..*` | En **Lógico** sale una FK en el lado "muchos" |
| 4.4 | Poner `0..*` en **los dos** lados | En **Lógico** aparece una **tabla puente** (ver bloque 5.6) |
| 4.5 | Cambiar el tipo a **composición** | La FK pasa a `NOT NULL` + `ON DELETE CASCADE`, y se avisa si la cardinalidad decía lo contrario |
| 4.6 | Cambiar a **agregación** | `ON DELETE SET NULL` |
| 4.7 | Dejarla en **asociación** | `RESTRICT` |
| 4.8 | **Generalización** de B a A | En Lógico, B comparte la PK de A (JOINED) |
| 4.9 | Relacionar una clase **consigo misma** 1:N | Se dibuja como un **bucle** y da una FK a su propia tabla |
| 4.10 | Reflexiva **N:M** poniendo roles distintos a cada extremo | La tabla puente sale con **dos columnas con nombres distintos** (por el rol). Sin roles, avisa |
| 4.11 | Intentar que una clase **herede de sí misma** | Se rechaza |
| 4.12 | Arrastrar sobre una relación seleccionada | Se crea un **quiebre**; se arrastra para moverlo y **doble clic** lo saca |
| 4.13 | Recargar con F5 | Los quiebres siguen ahí (viajan en `MOVE_ELEMENT`) |
| 4.14 | Relacionar tres clases en cadena y borrar la del medio | Las relaciones que quedaban colgando no dejan basura en el modelo |
| 4.15 | En una relación **N:M**, abrir su panel | Aparece el bloque **Clase intermedia** con un nombre sugerido (el de la relación, o los dos nombres pegados) |
| 4.16 | En una relación **1:N**, abrir su panel | Ese bloque **no** aparece: en 1:N una clase en el medio sería una tabla de más |
| 4.17 | Poner nombre y apretar **Crear** | La caja aparece **en el canvas**, entre las dos clases y más abajo, ya conectada a las dos con `1` → `0..*`, y la N:M original desaparece. Queda **seleccionada** para cargarle atributos |
| 4.18 | Mirar el historial de operaciones | Es **una sola** operación (`BATCH` de 4), no cuatro |
| 4.19 | Arrastrar la clase intermedia y conectarle una tercera clase | Se comporta como cualquier clase: se mueve, se conecta, se le ponen atributos y métodos |
| 4.20 | Intentar crearla con el nombre de una clase que ya existe | Se avisa **antes de mandar nada**; la N:M sigue en su lugar |
| 4.21 | Intentar crearla mientras otra sesión tiene tomada una de las dos clases | El lote se rechaza entero: **no** queda la clase nueva sin sus relaciones ni el modelo sin la N:M |
| 4.22 | Reificar una N:M **reflexiva** | Las dos relaciones hacia la intermedia salen con **roles distintos** (los de la relación, o `origen`/`destino`) |
| 4.23 | Crearla con **PK compuesta** tildada (por defecto) y mirar Lógico | La tabla intermedia **no** tiene `id`: su PK son las **dos FK**, las dos con `ON DELETE CASCADE`. Es `VENDE(NroV, CodP, …)` |
| 4.24 | Crearla con la casilla **destildada** | La tabla lleva `id` propio y las dos FK como columnas comunes. El generador le emite las cinco capas |
| 4.25 | Con PK compuesta, agregarle atributos (`cantidad`, `precio`) | Entran como columnas comunes de esa misma tabla, junto a la PK compuesta |

---

## 5 · Vista Lógica (derivación, CU-05)

Solo lectura: se mira, no se edita.

| # | Qué hacer | Qué tiene que pasar |
|---|---|---|
| 5.1 | Cambiar a **Lógico** con el modelo del bloque 3 | Una tabla por clase, con `id` y las columnas de los atributos |
| 5.2 | Mirar el atributo MULTI | Está en **otra tabla**, con FK a la dueña (Regla 12) |
| 5.3 | Mirar el atributo de tipo `Direccion` («embeddable») | **No** tiene tabla propia: sus campos están **dentro** de la tabla dueña, con prefijo |
| 5.4 | Mirar el atributo de tipo enumeración | Columna con `CHECK` de los mismos literales |
| 5.5 | Mirar el atributo con rango | Columna con `CHECK` del mínimo y el máximo |
| 5.6 | Mirar la relación N:M del caso 4.4 | **Tabla puente** `tabla_a_tabla_b`, con **dos FK**, **PK compuesta por las dos** y `ON DELETE CASCADE` en ambas |
| 5.6b | Reificar esa N:M (caso 4.17) y volver a Lógico | La tabla de la clase intermedia tiene **las mismas dos FK** que tenía la tabla puente, ahora con su `id` propio y con los atributos que le hayas puesto |
| 5.7 | Mirar la jerarquía JOINED | La hija tiene su tabla con la PK que **también es FK** a la madre |
| 5.8 | Cambiar la raíz a SINGLE_TABLE y volver a Lógico | **Una sola tabla** con una columna `tipo` y las columnas de todas las hijas |
| 5.9 | Dejar una relación **sin cardinalidades** | Se deriva como 1:N y **lo avisa** en la lista de advertencias (Regla 10: no se calla) |
| 5.10 | Buscar la lista de advertencias | Todo lo que no se pudo mapear está **listado**, no escondido |
| 5.11 | Comparar con **Chen** y con **Clases** | Misma verdad en tres notaciones |

---

## 6 · Vista Física

Es **solo lectura**, igual que la Lógica: el esquema se deriva en el backend y acá
se mira. No hay edición de columnas desde esta vista.

| # | Qué hacer | Qué tiene que pasar |
|---|---|---|
| 6.1 | Abrir **Físico** | Tablas con los tipos concretos del motor, PK, FK con su `ON DELETE`, únicos, `CHECK` e índices |
| 6.2 | Mirar el resumen | Cuenta tablas, columnas, claves foráneas, índices y restricciones |
| 6.3 | Mirar el **script** | Es el DDL completo **verbatim**: el mismo archivo que sale en el zip del generador |
| 6.4 | Elegir una tabla | Se resaltan las líneas del script que hablan **de esa** tabla, y se ve quién le apunta (el inverso de las FK) |
| 6.5 | Navegar con el **teclado** entre tablas | Se recorre sin mouse |
| 6.6 | Abrir un modelo grande | Pinta sin colgarse |
| 6.7 | Cambiar algo en **Clases** y volver a Físico | El esquema refleja el cambio: es una derivación, no una copia |

---

## 7 · Los 4 puntos de ambigüedad (A-1 … A-4)

La herramienta **detecta**, muestra **las dos lecturas** y el equipo elige. Lo
elegido tiene que **cambiar el esquema**.

| # | Qué hacer | Qué tiene que pasar |
|---|---|---|
| 7.1 | Armar una composición 1..N con la parte sin identidad propia (A-1) | Se detecta y se ofrecen las dos lecturas: entidad débil (PK compuesta) o entidad propia |
| 7.2 | Elegir **entidad débil** y mirar Lógico | La PK de la parte **incluye** la FK al todo |
| 7.3 | Composición 1..1 sobre una clase común (A-2) | Se ofrece: valor embebido (sin tabla) o tabla aparte |
| 7.4 | Atributo multivaluado (A-3) | Se ofrece: tabla por 1FN (lectura de datos) o colección (lectura OO) |
| 7.5 | Relación N:M con atributos propios (A-4) | Se ofrece: tabla puente pura o **clase asociativa** |
| 7.6 | Elegir **clase asociativa** en A-4 y mirar Lógico | La tabla puente pasa a tener **`id` surrogado**, y se **avisa** que conviene un `UNIQUE(fk_a, fk_b)` si no se quiere el mismo par dos veces |
| 7.7 | Generar el backend con esa decisión | La tabla puente sale como **Entity con sus cinco capas** |
| 7.8 | Volver a la lectura de datos y regenerar | Vuelve a ser tabla puente pura, sin Entity |
| 7.9 | No contestar ninguna ambigüedad | El esquema sale con el comportamiento histórico: agregar esto **no movió** ningún esquema por su cuenta |

---

## 8 · Importar (ítems 4.1 y 4.2)

| # | Qué hacer | Qué tiene que pasar |
|---|---|---|
| 8.1 | **↥ Importar** un JSON de `canonical-models/` | Pantalla de revisión con cada clase y atributo, su sugerencia y **lo que ya existe** en el diagrama |
| 8.2 | Destildar un atributo y confirmar | Entra todo lo tildado y **nada** de lo destildado |
| 8.3 | Importar un `.sql` con `CREATE TABLE` y `ALTER TABLE ADD FOREIGN KEY` | Se leen tablas, PK, FK y UNIQUE; **todo lo demás se cuenta en una advertencia** |
| 8.4 | En ese SQL, una PK formada por **dos FK** y nada más | Se re-detecta como **N:M** y la tabla puente desaparece como tabla: vuelve a ser una relación |
| 8.5 | Una FK **UNIQUE** | Se re-detecta como 1:1 |
| 8.6 | Una PK que además es FK | Se re-detecta como **herencia JOINED** |
| 8.7 | `ON DELETE CASCADE` / `SET NULL` en el SQL | Vuelven como composición / agregación |
| 8.8 | Un archivo SQL con una sentencia **rota** en el medio | Esa se saltea y el resto entra; el archivo no tumba nada |
| 8.9 | Importar un `.xmi` de clases UML | Se leen clases, atributos, **métodos con parámetros** y relaciones |
| 8.10 | Importar un XMI **cortado a la mitad** o con basura | Se lee hasta donde llega y se lista lo que quedó afuera. El único error aceptable es "el archivo no tiene ninguna clase" |
| 8.11 | Importar un XMI de **modelo de datos de EA** («table», «column», «PK», «FK») | Pasa por la misma ingeniería inversa que el SQL |
| 8.12 | Confirmar una importación con un **nombre repetido** en el medio | **No entra nada** y el `seq` **no se mueve** (BATCH todo o nada) |
| 8.13 | Confirmar una importación mientras otra sesión tiene una clase tomada | Se rechaza **diciendo cuál** cambio chocó con el lock |

---

## 9 · Exportar XMI

| # | Qué hacer | Qué tiene que pasar |
|---|---|---|
| 9.1 | `GET /diagrams/{id}/xmi` como OWNER | Baja un XMI **2.1 estándar**, sin extensiones propietarias |
| 9.2 | Mirar la cabecera `X-Colabmodel-Perdidas` y el `xmi:Documentation` | Lo que UML 2.1 puro no representa está **declarado** ahí (decisiones A-1..A-4, estrategia de herencia, estereotipos, `unique` que no es PK) |
| 9.3 | Reimportar ese mismo archivo en un diagrama nuevo y derivar el DDL | **El mismo DDL**, salvo las pérdidas declaradas |
| 9.4 | Pedir el XMI con un token de **VIEWER** | Se permite: exportar es una lectura, igual que el zip |
| 9.5 | Abrir el archivo en un Enterprise Architect de verdad | **Esto no está verificado todavía** — es el pendiente con el docente |

---

## 10 · Generar el backend Spring Boot (CU-05)

| # | Qué hacer | Qué tiene que pasar |
|---|---|---|
| 10.1 | Con clases en el diagrama, apretar **Generar Spring Boot** | Antes de bajar nada dice **cuántas entidades** salen, qué archivos trae y **qué quedó afuera** |
| 10.2 | Descargar el zip y `mvn package` | **Compila** |
| 10.3 | Crear la base y `java -jar target\...jar` | Arranca en `http://localhost:8081`. Flyway crea el esquema y Hibernate **valida** que las entidades coincidan |
| 10.4 | `GET /api/<tabla>` | Lista |
| 10.5 | `POST` un objeto válido | Lo crea |
| 10.6 | `POST` un objeto **inválido** | **400** con `{"error": …, "errores": {"campo": "motivo"}}` — no el JSON por defecto de Spring |
| 10.7 | `GET /api/pedido?clienteId=…` (filtro por FK) | Filtra en la consulta, con el mismo nombre con que el id viaja en el DTO |
| 10.8 | Enviar un literal de enumeración inexistente | La API lo rechaza; y por SQL directo, **la base también** (CHECK) |
| 10.9 | Enviar un valor fuera de rango | 400 en la API y rechazo en la base |
| 10.10 | Mirar una entidad con relación en el código generado | `@ManyToOne(fetch = LAZY)`, lado inverso con `mappedBy`, cascade según el tipo, `@EntityGraph` en el listado |
| 10.11 | Mirar el `Service` de una clase con métodos | Los métodos del UML están como **stubs con la firma** y `UnsupportedOperationException` |
| 10.12 | Mirar una jerarquía de tres niveles | Crear, listar polimórficamente y borrar funciona en JOINED y en SINGLE_TABLE |
| 10.13 | Generar desde la pestaña **Lógico** o **Físico** | Funciona igual: generar es una lectura |
| 10.14 | Generar con un token de **VIEWER** | Se permite (lectura) |
| 10.15 | `GET /static/app-manifest.json` del proyecto generado | Está, con entidades, rutas REST, campos, tipos, obligatorios, únicos, largos, literales, rangos, referencias y lado inverso |

---

## 11 · IA por texto (ítem 4.4, sin UI ni voz)

| # | Qué hacer | Qué tiene que pasar |
|---|---|---|
| 11.1 | `POST /diagrams/{id}/ai/propuestas` con "creá una clase Autor con nombre y nacionalidad" | Devuelve una **propuesta** para revisar. **No** escribe nada |
| 11.2 | Pedir 30 cambios de una vez | Se aceptan **como máximo 8**, y se **avisa** que el resto se truncó (no se rechaza el pedido entero) |
| 11.3 | Forzar una respuesta con un tipo de comando inventado | Se rechaza: el catálogo es cerrado |
| 11.4 | Pedir algo sobre una clase que no existe | Se rechaza **con motivo** |
| 11.5 | Confirmar la propuesta mandando el `BATCH` por el bus | Entra como **una** operación, con origen `TEXT` |
| 11.6 | Cambiar `colabmodel.ai.proveedor` entre Claude y Ollama | El resto del módulo no cambia |

---

## 12 · Leer un diagrama desde una imagen (`vision/`)

Esto es lo último que se construyó y **no necesita ningún modelo ni internet**.
Preparate cuatro imágenes:

- **(a)** una captura de pantalla de un diagrama de clases (de ColabModel mismo,
  de draw.io o de EA) con texto legible a tamaño natural;
- **(b)** la misma captura con **una relación diagonal** y **una punteada**;
- **(c)** una **foto** de una pizarra o de un proyector, con sombra de un lado;
- **(d)** una captura en **tema oscuro**.

| # | Qué hacer | Qué tiene que pasar |
|---|---|---|
| 12.1 | Subir (a) | Sale una propuesta con **una caja por clase**, con sus compartimentos, y **las líneas** entre ellas |
| 12.2 | Mirar los nombres | **Vienen leídos** (nombre de clase, atributos, métodos): eso es el OCR local, sin modelo |
| 12.3 | Mirar un renglón que no se pudo leer | Está **vacío** para completar a mano, **nunca inventado** |
| 12.4 | Mirar los avisos | Dice **cuántos de cuántos** textos se leyeron y que conviene revisarlos |
| 12.5 | Mirar el resumen de métricas | Dice los ms, y si hubo enderezado, umbral adaptativo o diagonales |
| 12.6 | Subir (b) | La **diagonal** y la **punteada** aparecen como relaciones; la punteada como **DEPENDENCY** |
| 12.7 | Contar las relaciones de (b) | No hay relaciones de más: una recta que no toca dos clases **no** se propone, y se sigue avisando |
| 12.8 | Subir (c) | El aviso dice que se binarizó con **umbral adaptativo**; se detectan las cajas igual (peor que en una captura, pero no vacío) |
| 12.9 | Subir (d) | Se lee igual que la clara |
| 12.10 | Subir una imagen **sin diagrama** (una foto cualquiera) | No falla: propuesta vacía **con su aviso** |
| 12.11 | Subir un archivo que no es PNG ni JPEG | **400** con "formato no aceptado", nunca un 500 |
| 12.12 | Subir un PNG enorme (declarando 40000×40000) | Se rechaza **sin** decodificarlo |
| 12.13 | Subir una imagen con un token de **VIEWER** | **403**: un VIEWER no puede empezar una edición |
| 12.14 | En el panel, mirar los campos **Mult.** de una relación | Vienen con la cardinalidad leída del dibujo (`1`, `0..*`), y se pueden corregir |
| 12.15 | Dejar `0..*` en los dos extremos y **Aceptar** | Entra **un** BATCH; en **Lógico** esa relación sale como **tabla puente** |
| 12.16 | Escribir una multiplicidad inválida (`x..y`) | Se avisa de la forma correcta y se manda **vacía** (el derivador asume 1:N avisando), en vez de guardar basura |
| 12.17 | Destildar una clase y aceptar | No entra esa clase **ni** las relaciones que dependían de ella, y se dice por qué |
| 12.18 | Aceptar con una clase **sin nombre** | No se puede: una clase sin nombre no se crea |
| 12.19 | Mirar el diagrama después de aceptar | Las clases quedan **con la disposición que tenían en la imagen**, no apiladas en el origen |
| 12.20 | Subir la misma imagen dos veces | Da **exactamente** la misma propuesta (es determinista) |
| 12.21 | Medir el tiempo de la primera lectura y de la segunda | La primera tarda unos segundos (calentamiento de fuentes), las siguientes **décimas** |
| 12.22 | Apagar `colabmodel.vision.nivel2.plantillas` y reiniciar | La propuesta sale **sin textos** pero con toda la geometría: la degradación funciona |

---

## 13 · App móvil (ítem 4.3, sin UI ni voz)

| # | Qué hacer | Qué tiene que pasar |
|---|---|---|
| 13.1 | `flutter test` en `mobile` | 43 pruebas verdes |
| 13.2 | Probar el motor con "crear libro con título X" sobre un manifiesto generado | Resuelve la entidad y el campo sin haber visto antes ese dominio |
| 13.3 | Decir algo ambiguo ("fecha" cuando hay dos fechas) | **Pregunta**, no elige por su cuenta |
| 13.4 | Dar un valor que no encaja (fuera de rango, literal inexistente, fecha imposible) | Lo **rechaza con motivo** antes de llegar al backend |
| 13.5 | Pedir **actualizar** o **borrar** | Se reconoce y **se dice que no está** (recorte declarado) |

---

## 14 · Casos negativos que no hay que olvidar

| # | Qué hacer | Qué tiene que pasar |
|---|---|---|
| 14.1 | Dos clases con el **mismo nombre** | Se rechaza con motivo |
| 14.2 | Un atributo sin nombre | Se rechaza |
| 14.3 | Relación entre una clase y otra **inexistente** | Se rechaza |
| 14.4 | Relación **n-aria** (una línea que toca tres clases, desde imagen) | Se avisa que hay que reificarla como clase asociativa; no se inventa |
| 14.5 | Caja dibujada **dentro** de otra (desde imagen) | Se proponen las dos por separado, avisando que no hay clases anidadas |
| 14.6 | Cerrar el navegador con una clase tomada | El lock se libera solo por TTL: nadie queda bloqueado para siempre |
| 14.7 | Un `BATCH` con un cambio sobre una clase que otro tiene tomada | Se rechaza entero, diciendo cuál |
| 14.8 | Borrar el diagrama de prueba al terminar | Solo quedan las ocho clases de la biblioteca |

---

## Qué mirar si algo falla

- **El backend no arranca por validación de esquema**: eso es el sistema
  funcionando (`ddl-auto: validate`). El mapeo JPA y el DDL no coinciden.
- **Una relación no aparece en Lógico**: mirá primero las **advertencias** de esa
  vista; casi siempre lo dice ahí.
- **Un texto de la imagen no se leyó**: es el comportamiento esperado cuando la
  letra es manuscrita, muy chica (≤ 11 px) o de una fuente rara. Que quede vacío
  es correcto; que aparezca **mal** no lo es — eso sí hay que anotarlo.
