# Casos de uso del hito LCA

**ColabModel** · v1.3 · 21/09/2026 · Fase RUP: **Elaboración** (CU-01, CU-04, CU-05) y **Construcción** (CU-06)

El plan RUP (`SAD.md`) define el criterio de salida de Elaboración así:
**CU-01, CU-04 y CU-05 funcionando de punta a punta.** Hasta esta versión esos
tres identificadores existían solo como criterio; no estaban escritos en
ningún lado. Este documento los fija.

> **Sobre la numeración.** El equipo fijó estos tres casos de uso el
> 13/09/2026, eligiendo los que ejercitan la arquitectura completa. Los números
> 02 y 03 no se definen acá porque no forman parte del hito.

Cada caso lleva, además de la especificación RUP, dos secciones que el hito
exige y que una especificación común no trae:

- **Evidencia**: qué lo prueba de forma automática y cómo mostrarlo en vivo.
  Sin esto, "funciona de punta a punta" es una afirmación, no un resultado.
- **Lo que todavía no cumple**: declarado acá y no descubierto en la demo
  (ver *Orden de recorte* en `SAD.md`).

---

## Actores

| Actor | Quién es | Qué puede hacer |
|---|---|---|
| **Propietario** | Rol OWNER. Exactamente uno por proyecto, y es implícito: sale de `projects.owner_id` y no tiene fila en `project_members` | Todo lo del Modelador, y además eliminar diagramas, renombrar o eliminar el proyecto, agregar, cambiar y quitar miembros, y transferir la propiedad. No puede abandonar: primero transfiere |
| **Modelador** | Miembro con rol EDITOR | Leer y escribir el modelo; crear y renombrar diagramas; abandonar el proyecto. En CU-01, CU-04 y CU-05 "Modelador" es cualquiera que pueda escribir, Propietario incluido |
| **Observador** | Miembro con rol VIEWER | Leer todo: proyecto, diagramas, miembros, Vista Lógica, Física, ambigüedades y código generado. No puede escribir; puede abandonar el proyecto |
| **No miembro** | Usuario autenticado que no está en el proyecto | Nada: recibe 403 en todo, y tampoco puede suscribirse a los tópicos de sus diagramas |
| **Sistema de destino** | Maven + JDK 21 en la máquina del usuario | Compilar el proyecto generado |

Los roles son **por proyecto**: no hay rol por diagrama ni administrador global (ADR-026).

La autenticación es un JWT HS256 que emiten `POST /api/v1/auth/login` y
`/auth/register`, y la interfaz tiene su pantalla de **Iniciar sesión** y **Crear
cuenta**. Ya no hay usuario de prueba: `POST /api/v1/dev/bootstrap` solo existe con
el perfil `dev` y sirve para la prueba de roles por `curl` del README (sección 5).

---

## CU-01 · Modelar el diagrama de clases en colaboración

**Resumen.** Varios modeladores editan el mismo diagrama de clases UML al mismo
tiempo y cada uno ve los cambios de los demás sin recargar la página. El
sistema garantiza que una misma entidad tenga un solo escritor a la vez.

**Qué valida de la arquitectura.** El camino único de escritura (ADR-002), la
exclusión mutua por niveles (ADR-004), el servidor como secuenciador (ADR-005)
y el canal push por WebSocket (requisito C-09).

**Actor principal:** Modelador. **Secundario:** Observador.

### Precondiciones

1. El usuario tiene un JWT válido y es miembro del proyecto.
2. El backend, la base de datos y el broker STOMP están arriba.

### Flujo básico

1. El modelador abre el diagrama. El cliente pide el **snapshot**
   (`GET /api/v1/diagrams/{id}`) en vez de reproducir todo el historial, y las
   últimas operaciones para el panel lateral.
2. El cliente se conecta por STOMP a `/ws` y se suscribe a
   `/topic/diagram/{id}/ops`, `/topic/diagram/{id}/locks` y a su cola privada
   `/user/queue/errors`.
3. El modelador crea una clase desde la barra de herramientas. El cliente
   genera el `classId` (UUID, ADR-007) y envía `CREATE_CLASS` a
   `/app/diagram/{id}/op`.
4. El `DiagramCommandBus` serializa la operación en el hilo de ese diagrama,
   verifica el permiso y la aplica sobre el metamodelo (`CommandApplier`). Si
   el efecto se rechaza, no consume número de secuencia.
5. El servidor persiste la operación en `diagram_operations` con su `seq`,
   autor y origen, y la difunde a todos los suscriptores.
6. Cada cliente aplica la operación sobre su estado local
   (`applyOperation.ts`). La clase aparece en todas las pestañas sin recargar.
7. El modelador selecciona la clase. El cliente pide un **lease** sobre ella y
   lo renueva cada 10 s mientras siga seleccionada. Los demás ven
   *"editando: …"* sobre la caja.
8. En el panel de propiedades, el modelador renombra la clase, agrega
   atributos con sus banderas (PK, obligatorio, multivaluado, derivado, clave
   parcial) y agrega métodos con su firma. Cada acción es un comando
   (`RENAME_CLASS`, `ADD_ATTRIBUTE`, `UPDATE_ATTRIBUTE`, `ADD_METHOD`, …) que
   requiere el lease.
9. Con la herramienta *Relación*, elige el tipo viendo su notación UML y
   arrastra de una clase a otra (`CREATE_RELATION`). Después ajusta nombre,
   cardinalidades y roles en el panel de la relación (`UPDATE_RELATION`).
10. Acomoda el diagrama arrastrando cajas y creando quiebres en las líneas
    (`MOVE_ELEMENT`). La geometría es nivel 3: sin lease, gana la última
    escritura.
11. Al deseleccionar, el cliente libera el lease.

### Flujos alternativos

| # | Condición | Respuesta del sistema |
|---|---|---|
| A1 | Otra **sesión** tiene el lease de la entidad, sea otro usuario u otra pestaña del mismo | Rechazo `LOCKED_BY_OTHER` *"Ese elemento lo esta editando …"*, **solo a la sesión que lo intentó**. El panel queda en solo lectura |
| A2 | Un Observador intenta escribir | Rechazo `FORBIDDEN` *"Los VIEWER no pueden editar (EC-11)"* |
| A3 | Quien escribe no es miembro del proyecto | Rechazo `FORBIDDEN` *"No sos miembro de este proyecto"* |
| A4 | Nombre de clase repetido en el diagrama | Rechazo `INVALID`; no se consume `seq` |
| A5 | Firma de método repetida o parámetro duplicado | Rechazo `INVALID`. Sobrecargar cambiando los tipos sí se permite |
| A6 | Se borra una clase | Se van con ella sus atributos, métodos, relaciones y geometría: no quedan huérfanos |
| A7 | La relación va de una clase a sí misma | Se dibuja como bucle. En N:M los extremos se distinguen por el rol |
| A8 | Una sesión se desconecta | `SessionListener` libera **solo los leases de esa sesión** y avisa a las demás, que dejan de mostrar *"editando: …"* |
| A9 | El lease no se renueva durante 30 s | Expira solo y la entidad queda libre |
| A10 | Llega un `seq` salteado, o el cliente se reconecta | La operación salteada **no se aplica**: queda en espera, el cliente pide al log lo que falta (`GET /operations?sinceSeq=`) y aplica todo en orden. Mientras tanto el indicador dice *"Recuperando cambios..."* |
| A11 | Llega una operación repetida o ya aplicada | Se descarta: aplicarla dos veces duplicaría el elemento |
| A12 | Un pedido de lock no prospera, o alguien suelta un lock ajeno | Se difunde quién tiene **realmente** el lease, así ninguna pantalla borra el *"editando: …"* del que sí lo tiene |

### Postcondiciones

- El metamodelo refleja el cambio y la operación queda en el log con `seq`,
  autor y origen.
- Todos los clientes conectados convergen al mismo `seq` y al mismo modelo.

### Escenarios de calidad

| ID | Medida | Estado |
|---|---|---|
| EC-01 | Exactamente 1 escritor por entidad; aviso < 300 ms | ✅ **1 escritor**: test automático y prueba en vivo con dos sesiones del mismo usuario. ⚠️ Los 300 ms del aviso no se cronometraron |
| EC-08 | Desconexión de 30 s → resync < 5 s | ✅ Recuperación implementada y con tests. ⚠️ No se cronometró con un corte real de 30 s |
| EC-11 | VIEWER escribe → 100 % rechazado | ✅ Test automático |
| EC-12 | 0 ciclos entre módulos | ✅ Test automático |

### Evidencia

**Automática** (`mvn test`, corre en CI):

| Test | Casos | Qué prueba |
|---|---|---|
| `ExclusionMutuaTest` | 11 | EC-01: dos pestañas del mismo usuario no editan la misma clase, no se roban ni se sueltan el lease, cerrar una no libera la otra, y lo difundido es el dueño real |
| `PermisoDeEscrituraTest` | 5 | EC-11: VIEWER y no miembro rechazados; EDITOR y OWNER escriben; VIEWER lee |
| `BorradoDeClaseTest` | 4 | A6: borrar una clase no deja atributos, relaciones ni geometría huérfanos |
| `MetodosTest` | 9 | A5 y la Regla 6: firmas, sobrecarga, parámetros duplicados |
| `QuiebresDeLineaTest` | 5 | Paso 10: los quiebres viajan por `MOVE_ELEMENT` |
| `DiagramSnapshotServiceTest` | 5 de 8 | Paso 1: el snapshot arma el modelo sin N+1 |
| `ArquitecturaTest` | 2 | EC-12: `modelcore` no depende de nadie; sin ciclos |

**Automática** (`npm test`, corre en CI):

| Test | Casos | Qué prueba |
|---|---|---|
| `wsClient.test.ts` | 5 | EC-08: orden, descarte de repetidas, recuperación ante un hueco, lo que llega durante la recuperación, y que un fallo del servidor no deje agujeros |

**En vivo:**

1. Levantar base, backend y frontend (README, sección *Cómo levantarlo*).
2. Abrir `http://localhost:5173` en dos pestañas lado a lado. Cada pestaña es un
   usuario (la sesión vive en `sessionStorage`): registrarse con dos cuentas, crear un
   proyecto en una e invitar a la otra desde el panel **Miembros** (CU-06).
3. Crear una clase en la izquierda → aparece en la derecha sin recargar
   (pasos 3 a 6).
4. Agregarle un atributo y un método → aparecen en la otra ventana.
5. Arrastrar una relación entre dos clases y moverlas → la otra ventana sigue.
6. **EC-01:** seleccionar la misma clase en las dos ventanas. La primera queda
   editando y la segunda ve *"La está editando Usuario-…"* con el panel en
   solo lectura. Cerrar la primera → la segunda la puede tomar.
7. EC-11 desde la interfaz: invitar a la otra cuenta como **Observador** → su
   diagrama queda en *Solo lectura* y, si un cliente manda un comando a mano, el
   servidor responde `FORBIDDEN`. O por API con el atajo de desarrollo (perfil `dev`):
   `curl -X POST "http://localhost:8080/api/v1/dev/bootstrap?role=VIEWER"` y enviar
   un comando con ese token.

### Defectos corregidos al preparar este documento

**D-1 · Exclusión mutua entre pestañas del mismo usuario.** El frontend pide el
token sin rol, así que en la demo todas las pestañas son el mismo OWNER, y el
lease se identificaba por **usuario**. Dos pestañas eran un solo escritor para
el servidor: las dos editaban la misma clase, el cartel *"editando: …"*
alternaba entre ellas cada 10 s y cerrar una soltaba los locks de la otra.
**Corrección:** el lease ahora es de la **sesión** (`Autor(userId, sesion)` en el
contrato del bus). Además se corrigieron tres fallas vecinas que aparecieron
en la prueba en vivo: un pedido de lock fallido difundía *"libre"* y borraba el
cartel del dueño real, soltar un lock ajeno hacía lo mismo, y el rechazo le
llegaba a **todas** las pestañas del usuario en vez de a la que lo provocó.

**D-2 · Resincronización.** El cliente detectaba el hueco, mostraba
*"Resincronizando..."* y no hacía nada: la operación salteada se aplicaba igual
y el modelo quedaba con un agujero. Tampoco preguntaba qué se había perdido al
reconectarse, y arrancaba desde el `seq` del bootstrap y no del snapshot, lo
que podía duplicar operaciones. **Corrección:** ver A10 y A11.

### Lo que todavía no cumple

- Los tiempos de EC-01 (aviso < 300 ms) y EC-08 (resync < 5 s) no se
  cronometraron.
- **Modo sin conexión (EC-05)**: si se corta la conexión, lo que el usuario
  intenta editar no se guarda para enviarlo después. Recuperar lo que hicieron
  **los demás** sí funciona (A10).

---

## CU-04 · Derivar el modelo lógico

**Resumen.** Cualquier miembro ve el modelo conceptual proyectado a tablas,
claves primarias y foráneas, con las 12 reglas de mapeo aplicadas. Donde el
modelo de objetos y el de datos admiten dos lecturas correctas, el equipo
elige una y esa elección cambia el esquema derivado.

**Qué valida de la arquitectura.** Proyección y no conversión (ADR-003): la
Vista Lógica se deriva, no se guarda, así que no puede divergir del modelo. Y
la derivación como función pura en el backend, compartida con el generador.

**Actor principal:** Observador o Modelador. Contestar una ambigüedad requiere
permiso de escritura.

### Precondiciones

1. El usuario es miembro del proyecto.
2. El diagrama tiene al menos una clase. Si no, ver A1.

### Flujo básico

1. El usuario abre la pestaña **Lógico**. El cliente pide
   `GET /api/v1/diagrams/{id}/logical`.
2. El backend lee el modelo vivo **una sola vez**, junta las decisiones de
   modelado guardadas y ejecuta `DerivadorLogico.derivar`, una función pura:
   entra el modelo, sale el esquema.
3. La derivación aplica las reglas: identidad surrogada UUID (1), tabla por
   clase en `snake_case` singular (2), FK del lado "muchos" y tabla puente en
   N:M (3), herencia JOINED (4), nombres normalizados (8), lo no mapeable
   listado (10), entidad débil (11) y multivaluado a tabla propia (12). La regla
   de borrado sale del tipo de relación: composición `CASCADE`, agregación
   `SET NULL` y asociación `RESTRICT`.
4. El cliente muestra las tablas con PK, FK, `NOT NULL` y `UNIQUE`, y arriba
   el recuadro **"Decisiones tomadas y construcciones rechazadas"**.
5. La vista se vuelve a derivar sola cuando otro modelador cambia el diagrama
   o cuando alguien contesta una ambigüedad.

**Subflujo · Contestar un punto de ambigüedad**

6. El usuario abre **⚖ Ambigüedades** (el contador indica cuántas quedan sin
   decidir). El cliente pide `GET /api/v1/diagrams/{id}/ambiguities`.
7. El backend detecta las cuatro construcciones con dos lecturas: composición
   1:N (A-1), composición 1..1 (A-2), multivaluado (A-3) y N:M (A-4). De cada
   una devuelve la lectura OO, la de datos, la lectura por defecto y la elegida.
8. El usuario elige una lectura. El cliente envía `SET_MODELING_DECISION`
   **por el bus**, como cualquier otra escritura: se secuencia, se difunde y
   queda con su autor.
9. La decisión se guarda en `modeling_decisions` y la Vista Lógica se vuelve a
   derivar con ella.

### Flujos alternativos

| # | Condición | Respuesta del sistema |
|---|---|---|
| A1 | El diagrama no tiene clases | *"Todavía no hay nada que derivar"* |
| A2 | Relación n-aria, herencia múltiple o herencia de sí misma | Se rechaza y se lista en las advertencias (Regla 10). Nunca se ignora en silencio |
| A3 | Dos columnas generadas con el mismo nombre (dos relaciones sin rol entre las mismas clases) | Se desambigua con `_2` y se avisa que conviene poner roles |
| A4 | Agregación con FK obligatoria | Queda `RESTRICT` en vez de `SET NULL`, y se explica por qué |
| A5 | Se elige leer una composición como `@Embeddable`, pero la parte participa en otras relaciones | No se aplana (dejaría FK colgando); se avisa |
| A6 | Se elige leer una composición como entidad débil, pero la parte no tiene clave parcial | Se aplica y se avisa que no podrá distinguir dos filas del mismo dueño |
| A7 | Se lee una N:M como clase asociativa | La tabla puente recibe `id` y se avisa que **perdió la unicidad del par** |
| A8 | Un Observador intenta contestar una ambigüedad | El bus rechaza con `FORBIDDEN` |

### Postcondiciones

- Derivar **no escribe nada**: la Vista Lógica es una proyección de solo
  lectura.
- Contestar una ambigüedad sí escribe: la decisión persiste y en adelante
  modifica el esquema y el código generado.
- Sin decisiones tomadas, cada ambigüedad usa su lectura por defecto, **que es
  el comportamiento anterior a que existiera la pantalla**.

### Escenarios de calidad

| ID | Medida | Estado |
|---|---|---|
| EC-04 (parcial) | Las reglas dan el esquema esperado en los 7 modelos canónicos | ✅ Test automático sobre los 7 |

### Evidencia

**Automática** (`mvn test`, corre en CI):

| Test | Casos | Qué prueba |
|---|---|---|
| `DerivadorLogicoTest` | 8 | Los 7 modelos canónicos de `canonical-models/` |
| `ReglasErTest` | 10 | Reglas 10, 11 y 12: entidad débil, multivaluado, derivado, n-aria rechazada, reglas de borrado y truncado de identificadores |
| `RecursionTest` | 5 | Reflexivas 1:N y N:M, desambiguación de columnas sin roles, herencia de sí misma rechazada |
| `AmbiguedadesTest` | 14 | Detección de A-1 a A-4, valores por defecto y el efecto de cada lectura |

**En vivo:**

1. Con un diagrama que tenga `Libro` con un atributo `autor` marcado como
   **MULTI**, abrir la pestaña **Lógico** → aparece la tabla `libro_autor` y
   la advertencia de la Regla 12.
2. Abrir **⚖ Ambigüedades** → aparece *A-3 · Libro.autor* con la lectura de
   datos marcada *por defecto*.
3. Elegir **Lectura orientada a objetos** → el contador baja, y la tabla pasa a
   decir `(@ElementCollection)` en la Vista Lógica.

### Lo que todavía no cumple

- ~~La pantalla de ambigüedades no conoce el rol del usuario.~~ Ya lo conoce (CU-06,
  ADR-026): en modo Observador la pantalla llega en solo lectura. El rechazo del
  servidor (A8) sigue siendo la garantía; la interfaz solo lo refleja.
- La **Vista Física** todavía es un marcador de lugar. El DDL ya existe (lo
  emite el generador en CU-05), pero la pestaña no lo muestra.

---

## CU-05 · Generar el backend Spring Boot

**Resumen.** Cualquier miembro genera, a partir del diagrama, un proyecto
Spring Boot de 5 capas listo para compilar. Antes de descargarlo ve cuántas
entidades salen y qué quedó sin soportar.

**Qué valida de la arquitectura.** La promesa central del producto: lo que el
usuario vio en la Vista Lógica es exactamente lo que se entrega, porque el
generador usa **la misma lectura del modelo y la misma derivación**.

**Actor principal:** Observador o Modelador. **Secundario:** Sistema de destino.

### Precondiciones

1. El usuario es miembro del proyecto.
2. Para compilar y arrancar lo generado: JDK 21, Maven y un PostgreSQL con
   la base que indica `application.yml`.

### Flujo básico

1. El usuario aprieta **Generar Spring Boot** (el botón azul funciona también
   desde las vistas de solo lectura: generar es una lectura).
2. El cliente pide `GET /api/v1/diagrams/{id}/codegen/resumen`.
3. El backend lee el modelo, las decisiones de modelado y ejecuta
   `GeneradorSpringBoot.generar` sobre la **misma derivación** que CU-04.
4. El cliente muestra *"N entidades · M archivos · nombre.zip"*, la lista de
   archivos y el recuadro **"Lo que este modelo deja afuera"**. La revisión va
   antes de la descarga porque un ZIP ya descargado no puede advertir nada.
5. El usuario aprieta **Descargar ZIP**. El cliente pide
   `GET /api/v1/diagrams/{id}/codegen` con el JWT y guarda el archivo.
6. El ZIP contiene, bajo una carpeta con el nombre normalizado del diagrama:
   `pom.xml`, `application.yml`, `V1__init.sql` (Flyway) y, por cada entidad,
   **Entity, Repository, Service, DTO y Controller**.
7. El usuario descomprime y corre `mvn package` → obtiene el JAR.
8. Crea la base y arranca el JAR. **Flyway** aplica `V1__init.sql`, Hibernate
   **valida** que las entidades coincidan con el esquema (ADR-009), y la API
   REST queda disponible en `/api/<tabla>` con listar, obtener, crear,
   actualizar y eliminar.

### Flujos alternativos

| # | Condición | Respuesta del sistema |
|---|---|---|
| A1 | El diagrama no tiene clases | Genera el andamiaje con 0 entidades y lo dice; no falla |
| A2 | Una tabla tiene clave primaria compuesta (entidad débil, tabla puente) | Entra al DDL pero no genera Entity; se avisa en el panel y en el README generado |
| A3 | Una N:M se leyó como clase asociativa (CU-04, A-4) | La tabla puente **sí** genera sus 5 capas, con `@ManyToOne` a cada extremo |
| A4 | Un multivaluado se leyó como `@ElementCollection` (CU-04, A-3) | La Entity del dueño lleva `@ElementCollection` + `@CollectionTable` |
| A5 | Un método del UML se llama como una operación del CRUD (`listar()`, …) | No se emite ese stub y se avisa: si no, el Service no compila |
| A6 | Quien pide no es miembro del proyecto | `403` |
| A7 | El backend no responde | *"No se pudo derivar el proyecto. Revisa que el backend siga arriba."* |

### Postcondiciones

- El diagrama no cambia: generar no toma lease, no escribe y no entra al log.
- El **empaquetado** es determinista: las entradas del ZIP llevan fecha fija,
  así que el mismo contenido da el mismo archivo byte a byte. La generación
  completa todavía no lo garantiza, porque las relaciones se leen de la base sin
  `ORDER BY` y el orden de tablas y advertencias podría variar entre dos
  lecturas.

### Lo que el código generado garantiza

| Regla | En el proyecto generado |
|---|---|
| 3 | `equals`/`hashCode` solo por `id` |
| 6 | Cada método del UML es un stub en el Service con su firma y `UnsupportedOperationException` |
| 7 | `@ManyToOne(fetch = LAZY)` explícito; lado inverso con `mappedBy`; cascade según composición o agregación; `@EntityGraph` en los listados |
| 8 | Nombres Java y columnas válidos aunque el modelo tenga acentos, eñes o palabras reservadas |
| 9 | `@NotNull`/`@Size` en el DTO, `nullable` en la Entity y `NOT NULL` en el DDL |
| — | El DDL crea primero todas las tablas y después las FK con nombre (`fk_<tabla>_<columna>`) e índices: **arranca con cualquier orden de tablas** |
| — | El controlador **solo ve DTOs**; el mapeo se hace en el Service, dentro de la transacción |
| — | Códigos HTTP honestos: validación **400**, id inexistente **404**, clave repetida o FK que impide borrar **409** |
| 5 | Valores embebidos: clase `@Embeddable` con su DTO, `@Embedded` con `@AttributeOverrides` en el dueño |
| 4 | Herencia **JOINED** (`@Inheritance`, `extends`, `@PrimaryKeyJoinColumn`) o **SINGLE_TABLE** (`@DiscriminatorColumn`/`@DiscriminatorValue`), según lo elegido en la raíz |
| 9 | Enumeraciones: `enum` de Java con `@Enumerated(STRING)` y `CHECK` en la base con los mismos literales. Rangos numéricos: `CHECK` en la base y `@Min/@Max` en el DTO |

### Escenarios de calidad

| ID | Medida | Estado |
|---|---|---|
| EC-03 | Generar desde 10 entidades en < 90 s | ⚠️ **Sin medir.** Generar es en memoria: los 22 tests del generador corren en poco más de un segundo. Pero nunca se cronometró un modelo de 10 entidades |
| EC-04 | Suite canónica en CI: 7/7 compilan | ⚠️ **Parcial.** CI verifica el contenido generado, pero **no compila ni arranca** los 7 proyectos. A mano: el diagrama de demo se **compiló, arrancó contra PostgreSQL y se recorrió su API** (12 pedidos, todos con el código esperado); el modelo 06 y una clase asociativa se compilaron |

### Evidencia

**Automática** (`mvn test`, corre en CI):

| Test | Casos | Qué prueba |
|---|---|---|
| `GeneradorSpringBootTest` | 28 | 5 capas, Regla 7, Regla 6, nombres hostiles, imports sin duplicar, DDL ejecutable en cualquier orden, índices en FK, controlador sin entidades, 404/409 |
| `ProyectoGeneradoTest` | 5 | ZIP con carpeta raíz, nombre normalizado, determinismo byte a byte, acentos |
| `DiagramSnapshotServiceTest` | 3 de 8 | El código sale del mismo modelo que el snapshot |
| `AmbiguedadesTest` | 4 de 14 | A3 y A4: `@ElementCollection` y la clase asociativa `EstudianteCurso` con sus `@ManyToOne` |

**En vivo:**

1. Con el diagrama de demo abierto, apretar **Generar Spring Boot** → el panel
   dice cuántas entidades salen y lista lo que deja afuera.
2. **Descargar ZIP**, descomprimir y, dentro de la carpeta:
   `mvn package` → `target/<nombre>-0.1.0.jar`.
3. Crear la base (`CREATE DATABASE diagrama_demo;`) y arrancar:
   `java -jar target/<nombre>-0.1.0.jar` → *"Successfully applied 1 migration"*
   y *"Started GeneratedApplication"*.
4. Recorrer la API en `http://localhost:8081/api/libro`: crear un libro con
   autores, listarlo, crearle un ejemplar, intentar borrar el libro (**409**:
   tiene ejemplares) y pedir un id que no existe (**404**).
5. Mostrar `service/LibroService.java`: el mapeo a DTO y el stub de `prestar`.

### Defectos corregidos al preparar este documento

Los tres se encontraron **arrancando** el backend generado. Hasta entonces el
criterio era que `mvn package` diera verde, y ninguno impide compilar.

**D-3 · El backend generado no arrancaba.** Cada FK iba en línea dentro de su
`CREATE TABLE`, en el orden en que salían las tablas. En el diagrama de demo,
`ejemplar` apuntaba a `libro` antes de que `libro` existiera, así que Flyway
cortaba con *"relation libro does not exist"* y la aplicación no levantaba.
**Corrección:** primero todas las tablas, después las FK con `ALTER TABLE` y al
final los índices. Así lo hace `pg_dump`, y también funciona con referencias
cruzadas.

**D-4 · La API respondía 500 casi siempre.** Los controladores devolvían las
entidades JPA. Con `open-in-view: false` la sesión ya estaba cerrada cuando
Jackson las serializaba, así que listar, obtener y crear algo con relaciones
fallaban. Crear un ejemplar respondía 500 **aunque el insert se había
guardado**. Además, un id inexistente o una FK que impedía borrar también daban
500. **Corrección:** el Service devuelve DTOs, mapeados dentro de la
transacción; el controlador no importa ninguna entidad; y un
`ManejadorDeErrores` responde 404 y 409.

**D-5 · En una jerarquía no se podía crear ninguna subclase.** La tabla hija
compartía la PK con la madre (JOINED), pero la Entity hija salía suelta: con su
propio `@Id` y `@GeneratedValue`, sin `extends` y sin los atributos heredados.
Insertar un `Empleado` generaba un id que no existía en `persona` y la FK lo
rechazaba; y su API ni siquiera tenía dónde recibir el `nombre`. **Corrección:**
la raíz lleva `@Inheritance(JOINED)` (y es `abstract` si el modelo lo dice), la
hija `extends` a su madre con `@PrimaryKeyJoinColumn` y sin redeclarar el id, el
DTO de la hija trae los campos de toda su línea, y una clase abstracta no ofrece
crear ni actualizar. Verificado arrancando el modelo canónico 04: se crean
empleados y clientes, se listan como personas y borrar un empleado elimina sus
dos filas.

### Lo que todavía no cumple

- **EC-04 no está automatizado**: nada en CI compila ni **arranca** los modelos
  canónicos. D-3 y D-4 son justo el tipo de defecto que eso atraparía; hoy solo
  los atrapan los tests de forma, que miran el texto generado.
- Los listados no traen en la misma consulta las colecciones de valores
  (`@ElementCollection`): un listado de N libros hace N consultas extra para sus
  autores. El `@EntityGraph` cubre solo las asociaciones.
- **Entidades débiles y tablas puente con PK compuesta** no generan Entity
  (haría falta `@IdClass`). Se puede esquivar leyendo la N:M como clase
  asociativa.
- **ADR-010 no está implementado.** El ADR pide compilar lo generado
  (`mvn package`) **antes** de entregar el ZIP y devolver `FAILED` con el log si
  falla. Hoy el ZIP se entrega sin compilar; la compilación se verificó a mano.

---

## CU-06 · Gestionar proyectos, miembros y roles

> **Fase RUP: Construcción.** No forma parte del hito LCA: cierra el "no hay login" que
> CU-01 declaraba pendiente. Se numera 06 y no 02 porque el documento reserva el 02 y el
> 03 para casos que no son del hito.

**Resumen.** Una persona se registra, crea sus propios proyectos, agrega a otros usuarios
ya registrados con un rol, y los cambia, quita o les transfiere la propiedad. El rol se
aplica **en caliente**: sin recargar, la interfaz del afectado pasa a solo lectura,
recupera la edición o vuelve a "Mis proyectos" con un mensaje.

**Qué valida de la arquitectura.** La regla de permisos en **un único lugar**
(`AccesoAProyecto`, ADR-026), consultada por REST, por el bus y por el WebSocket; EC-11
desde la interfaz y no solo por `curl`; el canal push (C-09) para el aviso privado; y que
`project` y `collab` sigan sin ciclos (EC-12) gracias a los eventos.

**Actor principal:** Propietario. **Secundarios:** Modelador, Observador.

### Precondiciones

1. El backend, la base de datos y el broker STOMP están arriba.
2. Para agregar a alguien: ya está registrado (no hay invitaciones por correo).

### Flujo básico

1. La persona abre la aplicación. Sin sesión ve **Iniciar sesión** y **Crear cuenta**. Se
   registra (`POST /auth/register`: email único sin distinguir mayúsculas, contraseña de 8
   o más) y entra a **Mis proyectos** (`GET /projects`). El token queda en `sessionStorage`:
   cada pestaña es un usuario.
2. Crea un proyecto (`POST /projects`). Nace con su primer diagrama, *Diagrama principal*,
   y quien lo crea es el **Propietario**.
3. En el panel **Miembros** agrega por email a otra persona como Modelador u Observador
   (`POST /projects/{id}/members`).
4. La persona agregada, sin hacer nada, ve el proyecto aparecer en su lista con su rol: recibe
   un **aviso privado** por `/user/queue/membresia`.
5. El Propietario le cambia el rol (`PATCH /projects/{id}/members/{userId}`). La interfaz
   del afectado se adapta en el acto. Como **Observador** ve un aviso *Solo lectura*; la
   barra de herramientas, el panel de propiedades, el arrastre y la conexión quedan
   deshabilitados, pero sí puede cambiar de vista y descargar el código. Como **Modelador**
   recupera la edición sin recargar. Su próximo comando se evalúa con el rol nuevo; si pasó a
   Observador se le liberan los locks y se difunde.
6. El Propietario quita a un miembro (`DELETE /projects/{id}/members/{userId}`, con
   confirmación). El quitado recibe el aviso y vuelve a "Mis proyectos" con un mensaje, y
   sus suscripciones a los diagramas se **cancelan**: no le llega ni una operación más.
7. El Propietario transfiere la propiedad a un miembro (`POST /projects/{id}/transfer`, con
   confirmación). Es **una transacción**: el anterior queda Modelador y el nuevo ve los
   controles de propietario sin recargar.
8. Un Modelador o un Observador abandona el proyecto (`DELETE /projects/{id}/members/{suId}`,
   con confirmación).
9. Propietario y Modelador crean y renombran diagramas (`POST /projects/{id}/diagrams`,
   `PATCH /diagrams/{id}`); solo el Propietario los elimina, escribiendo el nombre
   (`DELETE /diagrams/{id}`). Solo el Propietario renombra o elimina el proyecto
   (`PATCH` y `DELETE /projects/{id}`); eliminar pide escribir el nombre.

### Flujos alternativos

| # | Condición | Respuesta del sistema |
|---|---|---|
| A1 | Sin token, token inválido o vencido en **cualquier** llamada | 401 con `{"mensaje": …}`. La interfaz cierra la sesión y vuelve al login, conservando la ruta para retomar al volver a entrar |
| A2 | Login con credenciales equivocadas | 401 en el formulario, **sin** cerrar nada: esa llamada no llevaba token |
| A3 | Email ya registrado, contraseña de menos de 8 caracteres, email inválido | 409 o 400, con el mensaje en español |
| A4 | Un no miembro llama a cualquier endpoint del proyecto o de sus diagramas | 403. Un proyecto o diagrama que no existe se ve igual que uno ajeno, para no revelar qué ids existen |
| A5 | Un no miembro se suscribe a `/topic/diagram/{id}/…` de un proyecto ajeno | El SUBSCRIBE se rechaza y se corta la conexión (frame ERROR) |
| A6 | Un Observador o un no miembro envía `lock`; un no miembro envía `presence` | El mensaje se descarta en silencio |
| A7 | Un Modelador o un Observador intenta agregar, cambiar rol, quitar o transferir | 403, y no se cambia nada |
| A8 | El email no existe / ya es miembro (el Propietario cuenta) | 404 / 409 |
| A9 | Se pide el rol OWNER por el endpoint de roles o al agregar, o un rol inventado | 400 |
| A10 | Degradar o quitar al Propietario | 409: el propietario cambia con *transferir* |
| A11 | El Propietario intenta abandonar | 403: "primero transferí la propiedad" |
| A12 | Transferir a quien no es miembro / a uno mismo | 404 / 409 |
| A13 | Una transferencia falla a la mitad | Se revierte **todo**: `owner_id`, las filas de miembros y los avisos (que se envían recién después del commit) |
| A14 | Dos pedidos simultáneos sobre el mismo proyecto | Se serializan por el bloqueo de su fila (`SELECT … FOR UPDATE`) |
| A15 | Eliminan el proyecto o el diagrama que otro tiene abierto | Se sueltan sus locks y suscripciones; el otro recibe el aviso y vuelve a "Mis proyectos" (o al proyecto) con un mensaje |
| A16 | Se corta el WebSocket y se pierde un aviso | Al reconectar la interfaz vuelve a pedir el proyecto; si ya no es miembro, 403 → "Mis proyectos" |
| A17 | Se pega el enlace de un proyecto al que ya no se tiene acceso | 403 → vuelve a "Mis proyectos" con aviso |
| A18 | Cambiar la contraseña con la actual equivocada | 400 y **no** 401: no cierra la sesión |
| A19 | `/dev/bootstrap` sin el perfil `dev` | No existe: 401 |

### Postcondiciones

- Siempre hay **exactamente un** Propietario, y nunca una fila OWNER en `project_members`
  (lo garantiza el `CHECK` de V6, no solo el código).
- El modelo no se toca: gestionar proyectos no entra al log de operaciones.

### Escenarios de calidad

| ID | Medida | Estado |
|---|---|---|
| EC-11 | VIEWER escribe → 100 % rechazado, **también el VIEWER recién degradado** | ✅ Tests automáticos y recorrido en vivo con dos pestañas |
| EC-12 | 0 ciclos entre módulos | ✅ `ArquitecturaTest`: `project` avisa a `collab` por eventos, sin depender de él |
| — | Un cambio de rol se aplica antes del próximo comando, sin recargar | ✅ Tests automáticos y recorrido en vivo. ⚠️ La latencia del aviso no se cronometró |

### Evidencia

**Automática** (`mvn test`, corre en CI):

| Test | Casos | Qué prueba |
|---|---|---|
| `AccesoAProyectoTest` | 10 | La tabla de permisos del ADR-026, celda por celda, y `exigir` |
| `GestionDeMiembrosTest` | 23 | El Propietario agrega EDITOR y VIEWER; EDITOR y VIEWER no agregan, no cambian roles ni quitan (403); email inexistente 404 y duplicado 409; OWNER no se asigna ni se quita ni se degrada; transferencia con el anterior como EDITOR; EDITOR y VIEWER abandonan y el OWNER no; el no miembro recibe 403 en todo |
| `GestionDeProyectosTest` | 10 | Crear con su primer diagrama, listar con rol, quién renombra y elimina proyecto y diagramas |
| `CambioDeRolEnCalienteTest` | 14 | Un EDITOR degradado recibe FORBIDDEN en su próximo comando; se liberan sus locks; un quitado ve rechazado su próximo comando y se le cortan las suscripciones; los avisos van solo al afectado y después del commit |
| `SuscripcionAutorizadaTest` | 12 | Un no miembro no se suscribe al tópico de un diagrama ajeno; `lock` y `presence` autorizados |
| `CuentaDeUsuarioTest` | 12 | Cambiar la contraseña exige la actual; entra con la nueva y no con la vieja |
| `RespuestasHttpTest` | 14 | Los códigos 201/204/400/403/404/409 y el `{"mensaje"}` en español de todos los endpoints |
| `RespuestasDeSeguridadTest` | 3 | 401 (no 403) sin sesión; el atajo `dev` solo con su perfil |
| `ApiDeProyectosTest` | 12 | **Contra PostgreSQL de verdad**: V6, el `CHECK` que rechaza un segundo OWNER escribiendo a mano, la reversión de una transferencia que falla a la mitad, 401 con token vencido, y el WebSocket completo (un miembro quitado deja de recibir operaciones) |
| `AtajoDeDesarrolloTest` | 3 | `dev/bootstrap` con el perfil `dev`, y la recuperación de una cuenta demo hacia una real |

`ApiDeProyectosTest` y `AtajoDeDesarrolloTest` usan su propia base, `colabmodel_test`, y
se **omiten** —no fallan— si no hay un PostgreSQL alcanzable. El CI de GitHub lo levanta
como servicio para que no se omitan allí.

**Automática** (`npm test`, corre en CI):

| Test | Casos | Qué prueba |
|---|---|---|
| `permisos.test.ts` | 30 | Qué habilita cada rol: la misma tabla que el backend |
| `cliente.test.ts` | 14 | Un 401 en cualquier llamada con token cierra la sesión; uno sin token, no |
| `avisos.test.ts` | 11 | "Me quitan y vuelvo a Mis proyectos con un mensaje"; el cambio de rol en caliente |
| `sesion.test.ts` / `ruta.test.ts` | 7 / 5 | La sesión en `sessionStorage`; la navegación por hash |
| `validacion.test.ts` | 11 | El formulario de acceso: mensajes en español, el largo mínimo solo al crear la cuenta (una cuenta anterior a la regla tiene que poder entrar) y el foco en el primer campo con error |

**En vivo** (dos pestañas del mismo navegador; cada una es un usuario):

1. **A** se registra y crea un proyecto.
2. **A** invita a **B** como Observador: a B le aparece el proyecto sin recargar.
3. **B** ve *Solo lectura* y la barra deshabilitada; si manda un comando a mano el
   servidor responde `FORBIDDEN` (*"Los VIEWER no pueden editar (EC-11)"*).
4. **A** lo promueve a Modelador: B edita sin recargar, y **A** ve lo que B creó.
5. **A** lo quita: B vuelve a "Mis proyectos" con *"Te quitaron del proyecto …"*.

### Defectos corregidos al preparar este documento

**D-6 · Un error inesperado del servidor llegaba como 401.** Un `ResponseStatusException` o
una falla de la base hace que el contenedor reenvíe el pedido a `/error`, que la seguridad
no abría: el código real llegaba convertido en 401 o 403. Sin login era inofensivo; con
login, "401 = tu sesión murió", y un 500 habría sacado al usuario de su sesión. Lo mostró
la prueba de la transferencia que falla a la mitad. **Corrección:** `/error` se abre en la
seguridad y `ManejadorDeErrores` responde 4xx y 500 con la forma `{"mensaje": …}`.

**D-7 · Cualquier usuario autenticado podía leer en vivo un proyecto ajeno.**
`StompAuthChannelInterceptor` solo autenticaba el CONNECT: bastaba saber el id de un
diagrama para suscribirse a `/topic/diagram/{id}/…`, y también para tomar locks o difundir
cursores ahí. **Corrección:** SUBSCRIBE y SEND a `lock`, `unlock` y `presence` se autorizan
con `AccesoAProyecto`.

**D-8 · La base admitía dos propietarios.** El `CHECK` de `project_members.role` de V1
aceptaba `'OWNER'`, así que un proyecto podía tener uno en `owner_id` y otro en la tabla.
**Corrección:** V6 restringe el `CHECK` a `EDITOR` y `VIEWER` (y se detiene si encuentra
filas OWNER, en vez de decidir por su cuenta).

### Lo que todavía no cumple

- **Fuera de alcance, declarado:** emails e invitaciones por enlace, recuperación de
  contraseña, OAuth/SSO, refresh tokens, administrador global, equipos u organizaciones y
  permisos por diagrama.
- **Un token ya emitido sigue valiendo hasta vencer** (8 h) aunque el usuario cambie su
  contraseña o lo quiten de un proyecto. Lo que se corta al quitarlo es su **acceso**, no su
  identidad.
- **Los avisos son de mejor esfuerzo.** Durante un corte del WebSocket no se reenvían: la
  interfaz se reconcilia al reconectar. La lista de miembros de *otros* no se refresca sola
  cuando alguien entra o sale; se ve al recargar o al hacer una acción propia.
- **Sin pantalla para cambiar el nombre ni la contraseña**: `PATCH /me` y
  `POST /me/password` existen y están probados, pero solo por API.
- **El recorrido en vivo se corrió con un navegador automatizado** (Edge) contra una base
  descartable, no con personas. La latencia del aviso no se cronometró.
- **La cuenta `demo@uagrm.edu.bo` no puede iniciar sesión** (su hash es `{noop}demo`, que
  BCrypt nunca acepta): el "Proyecto Demo" solo se alcanza con `dev/bootstrap` hasta que
  se le transfiera la propiedad a una cuenta real.

---

## Estado del hito LCA

| Caso de uso | Funciona de punta a punta | Pendiente para darlo por cumplido |
|---|---|---|
| **CU-01** Modelar en colaboración | ✅ Incluso entre pestañas del mismo usuario | — |
| **CU-04** Derivar el modelo lógico | ✅ | — |
| **CU-05** Generar el backend | ✅ Verificado **arrancando** lo descargado y recorriendo su API | Automatizar EC-04 en CI |

**Veredicto.** **Los tres casos de uso cumplen el criterio de salida del LCA.**
CU-05 quedó cumplido al corregir D-3 y D-4: hasta entonces el backend
generado compilaba, pero no arrancaba y su API respondía 500.
CU-01 quedó cumplido al corregir D-1 y D-2: la exclusión mutua se probó en
vivo con dos sesiones del mismo usuario contra el backend real, y los dos
escenarios tienen tests automáticos. Lo pendiente —cronometrar EC-01, EC-03 y
EC-08, compilar en CI (EC-04, ADR-010) y el modo offline (EC-05)— son medidas
de Construcción, no requisitos para validar la arquitectura.

## Trazabilidad

| Caso de uso | ADR | Escenarios de calidad | Reglas de mapeo |
|---|---|---|---|
| CU-01 | 002, 004, 005, 007, 014 | EC-01, EC-08, EC-11, EC-12 | — |
| CU-04 | 003, 009 | EC-04 (parcial) | 1, 2, 3, 4, 8, 10, 11, 12 y los puntos A-1 a A-4 |
| CU-05 | 003, 007, 014 | EC-03, EC-04 | 3, 6, 7, 8, 9, 10 |
| CU-06 | 002, 004, 026 | EC-11, EC-12 | — |
