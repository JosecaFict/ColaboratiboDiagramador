# Las 12 reglas de mapeo

Contrato del módulo `codegen`. **Cada regla tiene un valor por defecto
declarado**, porque el generador no puede preguntar en medio de la generación.

> **Principio rector:** la base de datos guarda **ESTADO**; el objeto guarda
> **ESTADO + COMPORTAMIENTO**. No es traducción 1 a 1: es transformación guiada
> por reglas en la que **siempre se pierde algo**. El trabajo del ingeniero es
> elegir qué sacrificar.

| # | Regla | Decisión por defecto |
|---|---|---|
| **1** | **Identidad** | Si no hay identificador, **inyectar `id UUID` surrogado**. Si el usuario marcó un atributo como PK, degradarlo a `UNIQUE NOT NULL` (clave natural) y conservar el surrogado. **UUID, no `bigserial`** (ADR-007) |
| **2** | **Tabla ↔ Clase** | Tabla = clase en `snake_case` **singular**, editable por entidad. **No pluralizar** (`Pez→pezs`, `Análisis→analisiss`). Bajo `JOINED`/`SINGLE_TABLE` la clase abstracta **sí** genera tabla |
| **3** | **Relaciones** | 1:N → FK en el lado "muchos", que es propietario. N:M → tabla puente; propietario = origen dibujado. **Nombre de columna derivado del ROL** (`aeropuerto_origen_id`). Soporta reflexivas. `equals`/`hashCode` **solo por `id`** |
| **4** | **Herencia** ✅ *implementada* | **`JOINED` por defecto**; `SINGLE_TABLE` como opción **elegida en la clase raíz**, con columna discriminadora `tipo` y `CHECK` sobre las clases concretas. Las columnas de las subclases admiten NULL y su obligatorio queda en el DTO. Si una subclase tiene relaciones o multivaluados propios, la jerarquía se mantiene JOINED y se avisa. Herencia múltiple → rechazo con mensaje |
| **5** | **Embeddables** ✅ *implementada* | Estereotipo `«embeddable»` explícito, **o** sugerencia ante composición 1..1 sin identidad. **Sugerir y confirmar, nunca decidir sola**. Genera una clase `@Embeddable` con su DTO; el dueño la lleva como `@Embedded` con `@AttributeOverrides` hacia sus columnas prefijadas (`domicilio_calle`), así un mismo valor sirve a varios dueños. No se anidan valores ni se embeben colecciones: se avisa |
| **6** | **Métodos** ✅ *implementada* | **No se descartan.** No van a la Entity ni al DDL, pero **sí al Service** como stub con la firma del UML y `UnsupportedOperationException`. En 5 capas el comportamiento vive en el Service |
| **7** | **Fetch / Cascade** | `@ManyToOne` → **`LAZY` explícito** (JPA trae EAGER: bug clásico). Composición → `cascade=ALL, orphanRemoval=true`. Agregación → `{PERSIST, MERGE}`. Listados con `@EntityGraph` (anti N+1) |
| **8** | **Identificadores** | Transliteración (`Año→anio`), `snake_case`, palabras reservadas de PostgreSQL, truncado a **63 bytes** con hash anticolisión, `@Column(name=…)` explícito siempre |
| **9** | **Restricciones** | Validación en **tres capas**: DTO (`@NotNull/@Size`) · Entity (`nullable=false`) · BD (`NOT NULL`, `UNIQUE`, `CHECK`). ✅ El `CHECK` sale de tres fuentes: **rangos** de atributos numéricos (`precio >= 0.5 AND precio <= 10000`, con `@Min/@Max` o `@DecimalMin/@DecimalMax` en el DTO), **enumeraciones** y el **discriminador** de `SINGLE_TABLE`. Un rango imposible (mínimo > máximo, sobre un texto, o con decimales en un entero) se rechaza al entrar al bus |
| **10** | **No mapeable** | Herencia múltiple, n-arias, derivados, OCL, plantillas → **detectar, rechazar y listar en el informe.** Nunca ignorar en silencio |
| **11** | **Entidad débil** | PK compuesta = PK del propietario + clave parcial. Relación identificadora obligatoria |
| **12** | **Atributo multivaluado** | → **tabla propia** con FK. Es 1FN, no una opción |

## Mapeo de tipos

| UML | Java | PostgreSQL |
|---|---|---|
| string | `String` | `varchar(n)` |
| text | `String` | `text` |
| int / long | `Integer` / `Long` | `integer` / `bigint` |
| decimal | `BigDecimal` | `numeric(19,2)` |
| boolean | `Boolean` | `boolean` |
| date / datetime | `LocalDate` / `LocalDateTime` | `date` / `timestamp` |
| uuid | `UUID` | `uuid` |
| enum ✅ | `enum` de Java + `@Enumerated(STRING)` | `varchar` + `CHECK (col IN (...))` |

> **Enumeraciones.** Son clases con estereotipo `«enumeration»` cuyos atributos son
> los literales; otra clase las usa como **tipo** de un atributo. No generan tabla.
> Los literales se normalizan (`En préstamo` → `EN_PRESTAMO`) y ese mismo texto es
> la constante de Java y el valor en la base. `STRING` y no `ORDINAL`: con
> `ORDINAL` la base guarda la posición, y reordenar los literales cambia en silencio
> el significado de lo ya guardado.

## Sugerencia de tipo y rol por el nombre

Base para leer un modelo de una foto o de un script: la IA lee nombres y
símbolos, y estas reglas proponen qué es cada atributo (`InferenciaDeAtributos`).
**Sugieren, no deciden**: cada propuesta trae confianza y motivo.

| Precedencia | Fuente |
|---|---|
| 1 | Tipo **escrito** en el modelo (`real`, `VARCHAR(50)`, `bit` → `decimal`, `string`, `boolean`) |
| 2 | Rol por **identificador**: `id` o `id<Clase propia>` es la identidad; `id<Otra clase>`, `<otra>_id` o `fk_<otra>` es una **relación**, no un atributo (Regla 3) |
| 3 | Nombre de una **enumeración** o **valor embebido** del modelo |
| 4 | Código de negocio (`isbn`, `ci`, `codigo`) → clave natural (Regla 1) |
| 5 | Pistas del nombre: fechas → `date`, dinero → `decimal` (nunca `float`), cantidades → `int`, `es…`/`tiene…` → `boolean`; un **número de teléfono o documento es texto** |
| 6 | Sin pistas → `string` con confianza baja |

## Los 4 puntos de ambigüedad OO ↔ Datos ✅ *implementados*

El *impedance mismatch* hecho explícito. **Ninguna lectura es universalmente
correcta**: la herramienta pregunta una vez y persiste la decisión en
`modeling_decisions`.

| # | Construcción | Lectura **OO** | Lectura **Datos** | Por defecto |
|---|---|---|---|---|
| A-1 | `Factura ◆── DetalleFactura` | Entidad con `id` surrogado + `cascade=ALL` | **Entidad débil**: PK compuesta | OO |
| A-2 | `Cliente ◆── Direccion` | `@Embeddable` aplanado en la tabla del dueño | Tabla propia con FK | Datos |
| A-3 | `Cliente.telefonos [0..*]` | `@ElementCollection` colgando del dueño | Tabla propia, sin Entity | Datos |
| A-4 | `Estudiante ── Curso` con `fecha` | Clase asociativa con `id` surrogado, **con sus 5 capas** | Tabla puente con **PK compuesta**, sin Entity | Datos |

> **El valor por defecto de cada punto es el comportamiento histórico**, a
> propósito: agregar la pantalla de ambigüedades no cambió por sí sola ningún
> esquema ya derivado. Nada se mueve hasta que alguien decide.
>
> La detección (`Ambiguedades.detectar`) es pura, igual que la derivación, y la
> decisión entra por el bus como `SET_MODELING_DECISION`: se secuencia, se
> difunde a los demás y queda en el log con su autor.
>
> **Lo que A-4 desbloquea:** leer la N:M como clase asociativa le da a la tabla
> puente una sola PK llamada `id`, y eso es exactamente lo que el generador
> necesitaba para emitirle Entity, Repository, Service, DTO y Controller. Era
> el hueco más visible del generador. A cambio se pierde la unicidad del par,
> y por eso se avisa.

## Normalización

| Forma | Rol de la herramienta |
|---|---|
| **1FN** | Detectar atributos de tipo colección → Regla 12 |
| **2FN** | Solo aplica con PK compuesta (entidades débiles, puentes con atributos). Detectar por prefijo compartido con una parte de la clave y **sugerir** extracción |
| **3FN** | Detectar prefijo compartido con una FK y **sugerir**, nunca aplicar sola |
| **BCNF** | Documentada; rara vez violada en modelos de curso |
| **Desnormalización** | Permitida marcando `«derivado»` y **declarándola** en el informe |

> **Nota de honestidad:** con claves surrogadas la 2FN se cumple por
> construcción. **Es el modelo Chen quien la vuelve verificable**, al
> introducir claves compuestas reales. Decilo así en la defensa.

## Ingeniería inversa (relacional → conceptual)

| Lo que encuentra | Lo que reconstruye |
|---|---|
| PK simple | Entidad fuerte |
| PK compuesta = solo FKs, sin atributos extra | **Relación N:M** (la tabla desaparece) |
| PK compuesta = solo FKs, con atributos extra | **Relación con atributos** |
| PK compuesta = FK + columna propia | **Entidad débil** + relación identificadora |
| FK `NOT NULL` / nullable | Participación **total** / **parcial** |
| FK con `UNIQUE` / sin | Relación **1:1** / **1:N** |
| PK de una columna que además es FK ✅ | **Generalización** (JOINED). Se avisa que también puede ser 1:1 con clave compartida |
| `ON DELETE CASCADE` / `SET NULL` / otro ✅ | **Composición** / **agregación** / asociación |
| Columna FK que no se llama como la clase ✅ | **Rol** (`jefe_id` → rol `jefe`) |
| `CHECK (col IN (...))`, prefijo de columnas repetido | **Pista**, no se aplica: enumeración, discriminador SINGLE_TABLE, `«embeddable»` (ADR-018) |

> ✅ Implementado en `interop/LectorSql` (ADR-018). Equivalencia: reimportar el
> DDL generado vuelve a derivar **el mismo DDL**, salvo las pérdidas que el lector
> declara (SINGLE_TABLE, `@Embeddable`, A-1..A-4, abstractas, métodos, largos,
> `CHECK`, nombres UML originales).
