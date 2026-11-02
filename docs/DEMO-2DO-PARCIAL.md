# Guion de la demo · 2do parcial

Para el ensayo general y la presentación. Dominio: **Gimnasio**, que no aparece en
ninguna prueba del repositorio: así se ve que nada está preparado para un dominio en particular.

**Duración total: unos 22 minutos**, más preguntas. Cada paso tiene un **plan B** por si algo
falla. Todo lo que se muestra está integrado en `main`. Lo que todavía depende de algo externo o de
la segunda prueba en el teléfono (el modelo local y el envío automático de la voz) está marcado.
**No hay que presentar como hecho algo que no lo está.**

| Quién | Dónde | Rol en el proyecto |
|---|---|---|
| **Ana** (presenta) | Computadora 1 · proyector · pestaña 1 | Propietaria (OWNER) |
| **Beto** | Computadora 2 (o pestaña 2) | Modelador (EDITOR) |
| **Carla** | Pestaña 3 en el proyector | Observadora (VIEWER) |
| **Teléfono** | Android con el APK, en la misma Wi-Fi | Lo maneja Beto o Carla |

> La sesión vive en `sessionStorage`: cada pestaña es un usuario distinto. Para que no se mezclen,
> usar una ventana por persona.

---

## 0 · Apertura · 1 min

**Decir:** «ColabModel son tres programas: la **herramienta** web donde diseñamos juntos, el
**backend** que la herramienta genera sola, y una **app móvil** que habla con ese backend
generado. Hoy diseñamos un gimnasio desde cero, sin nada preparado.»

**Tiene que verse:** el proyector con la pantalla de proyectos de Ana.

---

## 1 · Diseñar juntos en vivo · 4 min

| # | Quién | Acción | Tiene que verse |
|---|---|---|---|
| 1.1 | Ana | Crea el proyecto **Gimnasio**. En **Miembros** agrega a Beto como **Modelador** y a Carla como **Observadora** | A Beto y Carla les aparece el proyecto **sin recargar** |
| 1.2 | Ana | Con el botón de clase (título «Crear una clase nueva») crea **Socio** (`nombre` obligatorio, `email` único, `activo` booleano) | La clase aparece en las tres pantallas con su `seq` |
| 1.3 | Beto | Crea **Plan** (`nombre` obligatorio, `precioMensual` decimal ≥ 0, `duracionDias` entero) | Aparece en la pantalla de Ana al mismo tiempo |
| 1.4 | Ana | Con el botón «Crear una enumeracion» crea **EstadoMembresia** con `ACTIVA`, `VENCIDA` y `SUSPENDIDA` | Se ve con «enumeration» |
| 1.5 | Beto | Crea **Membresia** (`fechaInicio` fecha obligatoria, `estado` de tipo `EstadoMembresia`) | — |
| 1.6 | Ana | Con «Relación» arrastra **Socio → Membresia** y **Plan → Membresia** (asociación, uno a muchos) | Las líneas aparecen en las tres pantallas |
| 1.7 | Beto | Hace clic en **Socio** para editarla | En la pantalla de Ana, Socio se bloquea en rojo con el nombre de Beto (exclusión mutua) |
| 1.8 | Carla | Intenta editar | Ve *Solo lectura*; la barra está deshabilitada (EC-11) |

**Decir:** «Todo lo que hacemos, mouse, texto, voz o IA, entra por **un solo camino**, el bus de
comandos. Por eso el lock, los permisos y el historial se resuelven una sola vez.»

**Plan B:** si una pestaña no recibe los cambios, F5: el diagrama se reconstruye solo con el
historial de operaciones. Si falla la red de la sala, mostrar con dos pestañas en una sola
computadora.

> La vista Chen se sacó a propósito: se trabaja solo con UML. Si preguntan por ER: las anotaciones
> (multivaluado, derivado, clave parcial, entidad débil) se editan en el panel de propiedades, y el
> selector **Cardinalidad** muestra las multiplicidades en notación Chen `(0,N)` sin tocar lo guardado.

---

## 2 · Vista IA: memoria del chat y quién pregunta · 4 min

> La memoria, quién pregunta y la configuración y el chat grupal con su panel están
> integrados. **Depende de algo externo:** el modelo local, que maneja otro compañero. El
> backend solo acepta `qwen3:8b` o `qwen3.5:9b`. Lo que diga el modelo depende de él, no del
> código del equipo.

| # | Quién | Acción | Tiene que verse |
|---|---|---|---|
| 2.1 | Ana | Pestaña **IA**, abre **Configuración de la IA**: elige estilo **Mentor** y escribe en instrucciones «somos 3, usamos nombres en español». **Guardar** | Solo Ana (OWNER) puede cambiarla; Beto la abre y la ve en solo lectura |
| 2.2 | Ana | Modo **Preguntar**: «¿Qué le falta a este modelo para manejar clases grupales con cupo?» | El pedido sale con la inicial de Ana, del lado propio; la respuesta dice «responde a vos». **En las pantallas de Beto y Carla aparece sin recargar**, del lado ajeno |
| 2.3 | Beto | Modo **Preguntar**: «¿Qué te preguntó Ana recién?» | La IA responde con lo que preguntó Ana: memoria del chat y quién pregunta |
| 2.4 | Beto | Modo **Modificar**: «Agregá Entrenador y Clase con cupo y horario, y relacioná Clase con Entrenador» | Propuesta visible con casillas |
| 2.5 | Beto | **Aplicar seleccionados** | Las clases aparecen en las tres pantallas como **un solo BATCH**; la propuesta queda marcada **aplicada** en todas |
| 2.6 | Ana | (Opcional) botón **Dictar** (Chrome o Edge) y dice un pedido | El texto aparece en el campo; el pedido viaja como `VOICE` |

**Decir:** «La IA no escribe en el diagrama por su cuenta: **propone**, el servidor valida cada
cambio y una persona decide qué aplicar. Y "aplicada" se marca solo si el servidor aceptó el lote.»

**Plan B (sin modelo):**
- Mostrar el **chat grupal** con el historial que ya haya (pedidos de cada uno de su lado, con su
  inicial) y que aparece en las tres pestañas, y una propuesta aplicada que figura como tal.
- Mostrar el **panel de configuración** (que solo el OWNER cambia) y explicar cómo arma la IA su
  contexto: los últimos 8 turnos con su autor, quién pregunta y el aviso 422 si el pedido no entra.
- Decir con claridad: «el modelo local lo maneja otro compañero; sin el modelo no hay respuesta,
  pero todo lo que lo rodea está probado con dobles».
- Para el diseño, agregar Entrenador y Clase **a mano** en 30 segundos y seguir.

---

## 3 · Del modelo a la base · 2 min

| # | Quién | Acción | Tiene que verse |
|---|---|---|---|
| 3.1 | Ana | Pestaña **Lógico** | Tablas con PK y FK derivadas de las 12 reglas; `membresia` tiene `socio_id` y `plan_id` |
| 3.2 | Ana | Pestaña **Documental** | La misma verdad en MongoDB: colecciones, incrustados y referencias |
| 3.3 | Ana | Pestaña **Físico (DDL)** → **Verificar en PostgreSQL** → **Confirmar esquema** | El script se ejecuta en una transacción que se deshace; queda confirmado |

**Decir:** «Es un solo modelo. La vista lógica, la documental y el DDL se derivan de él: no hay
nada que sincronizar a mano.»

**Plan B:** si **Verificar en PostgreSQL** falla (sin base), mostrar el DDL y seguir: el generador
igual funciona.

---

## 4 · Generar el backend: PostgreSQL y MongoDB · 3 min

| # | Quién | Acción | Tiene que verse |
|---|---|---|---|
| 4.1 | Ana | Botón **Generar** (título «Deriva el proyecto Spring Boot…»), base **PostgreSQL** | El panel dice cuántas entidades salen y si lleva la base confirmada; baja el zip |
| 4.2 | Ana | Descomprimir y `mvn -q package -DskipTests` (con la caché de Maven caliente, unos 20 s) | `target/gimnasio-0.1.0.jar` |
| 4.3 | Ana | Arrancar contra el PostgreSQL real (la base `gimnasio` ya creada, ver "Preparación") con perfil `prod` | Arranca en unos 10 s; Flyway aplica sus migraciones |
| 4.4 | Ana | Generar otra vez, ahora con **MongoDB**, compilar y arrancar en el puerto **8082** (`PORT=8082`) | El mismo dominio sobre MongoDB (punto NoSQL del parcial) |

Comando para 4.3 (PowerShell):

```powershell
$env:SPRING_PROFILES_ACTIVE="prod"; $env:DB_URL="jdbc:postgresql://127.0.0.1:55432/gimnasio"; $env:DB_USER="colabmodel"; $env:DB_PASSWORD="colabmodel"
java -Xmx256m -jar target/gimnasio-0.1.0.jar
```

Para 4.4 (MongoDB): en otra consola, `$env:SPRING_PROFILES_ACTIVE="prod"; $env:DB_URL="mongodb://localhost:27017/gimnasio"; $env:PORT="8082"` y el mismo `java -Xmx256m -jar`.

**Decir:** «Esto lo escribió la herramienta: entidades, repositorios, servicios, DTO y controladores,
el DDL, la auditoría, el bloqueo optimista, el manejo de errores y el Docker. Sirve para producción:
en `prod` nada sensible queda escrito en el jar.»

**Plan B:**
- Si compilar tarda o falla por red, usar el perfil `dev` (H2 en memoria, **no hace falta
  instalar nada**): `mvn spring-boot:run`.
- Si no alcanza la memoria para los dos backends a la vez, mostrar MongoDB después de apagar el de
  PostgreSQL (los dos usan el mismo puerto por defecto).
- Último recurso: un zip generado y compilado en el ensayo **del mismo diagrama**, avisando que se
  generó antes.

---

## 5 · Consola del backend generado · 3 min

Abrir `http://localhost:8081/` (y después `:8082`, el de MongoDB).

| # | Quién | Acción | Tiene que verse |
|---|---|---|---|
| 5.1 | Ana | Crear un **Plan** («Mensual», 150) y un **Socio** | La consola se armó sola desde el manifiesto: nunca vio un gimnasio |
| 5.2 | Ana | Crear una **Membresia** eligiendo el socio y el plan **de una lista** | La lista muestra nombres, no UUID |
| 5.3 | Ana | Crear un Plan con precio **-5** | Error **400** junto al campo; el formulario no se borra |
| 5.4 | Ana y Beto | Los dos abren **Editar** sobre el mismo socio; guarda Beto y después Ana | A Ana le aparece **«Versión vieja (409)»**, conserva su borrador y ofrece recargar |
| 5.5 | Ana | Borrar un socio que tiene membresías | 409 de integridad, distinto del de versión |
| 5.6 | Ana | Cambiar a 5 por página y pasar de página | El total sale de `X-Total-Count` |

**Decir:** «La consola viene dentro del zip y sirve para cualquier dominio: lee el mismo manifiesto
que usa la app móvil.»

**Plan B:** si la consola no carga, mostrar la API con `curl` o con `/actuator/health`. En `prod`
Swagger está apagado a propósito.

---

## 6 · El teléfono: visor en vivo y asistente por voz · 3 min

| # | Quién | Acción | Tiene que verse |
|---|---|---|---|
| 6.1 | Beto | En la app, **Ver diagrama en vivo**: entra con su cuenta en `<IP>:8080` y abre el diagrama del Gimnasio | El diagrama en el teléfono |
| 6.2 | Ana | En la web mueve una clase o agrega un atributo | Llega solo al teléfono (el visor es solo lectura) |
| 6.3 | Beto | **Asistente** → dirección `<IP>:8081` (el backend generado) | «Conectado a Gimnasio… puedo crear y listar socio, plan, membresia…» |
| 6.4 | Beto | Toca el **micrófono** y dice: «crear socio con nombre Carla Rojas» | La frase aparece en el campo y se envía sola; «Listo, creé socio» |
| 6.5 | Ana | Recarga la consola en la computadora | Carla Rojas está en la lista: **el teléfono habló con el backend generado, no con la herramienta** |
| 6.6 | Beto | «listar socios» | La lista en el chat |

> La voz en el móvil y las preguntas pendientes están integradas. **El envío automático
> tras el silencio se vuelve a comprobar en el ensayo.** Si en el teléfono no se envía sola,
> tocar ■ (terminar y enviar) o, como último recurso, **escribir** el mismo pedido.

**Decir:** «La app no sabe nada de gimnasios: aprende el dominio del manifiesto al conectarse. El
mismo APK sirve para cualquier backend que genere la herramienta.»

**Plan B:**
- Si el teléfono no se conecta, revisar que esté en la misma Wi-Fi y que el firewall deje pasar
  8080 y 8081 (en una red **Privada**: regla «ColabModel 8080-8081», que ya existe en esta máquina).
  Desde el navegador del teléfono, `http://<IP>:8080/swagger-ui.html` tiene que abrir.
- Si sigue sin conectar, mostrar el asistente por escrito, o la verificación del contrato desde la
  computadora: `dart run tool/verificar_contrato_crud.dart http://localhost:8081`.
- Si el dictado corta la frase o no envía, tocar ■ para terminar, o escribir.

---

## 7 · Cierre · 1 min

**Decir:** «Diseñamos juntos un dominio nuevo, la IA participó con memoria del equipo, la
herramienta generó un backend real en PostgreSQL y en MongoDB, lo probamos con su consola y una app
móvil que nunca vio un gimnasio habló con él por voz.» Mostrar el **Informe del proyecto** del
README si preguntan qué está integrado.

---

## Preparación previa (una hora antes)

**Memoria.** La máquina tiene 14 GB. En las pruebas, Windows y Claude Code mataron procesos cuando
quedaban menos de 1 GB libres. Medido en esta máquina: Docker Desktop (WSL) unos **2 GB**; Chrome con
varias pestañas unos **0,9 GB**; cada backend generado con `-Xmx256m` unos **0,4 GB**; MongoDB con
`-m 400m` hasta **0,4 GB**; las sesiones de Claude Code abiertas, **1,5 GB o más**. El modelo local
(Ollama, en la GPU de 8 GB) lo mide el compañero de IA local.

**Cerrar antes de empezar:** todo programa que no se use (navegadores extra, asistentes, editores).
Revisar la memoria libre (`(Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory/1MB`, en GB):
apuntar a **más de 3 GB libres** con todo arriba.

**Orden para levantar:**
1. **Docker Desktop**, y `docker compose up -d` en la raíz (PostgreSQL de la herramienta en `55432`).
2. Crear las bases de la demo:
   `docker exec colabmodel-db psql -U colabmodel -d colabmodel -c "CREATE DATABASE gimnasio;"`
3. **MongoDB** para la variante NoSQL:
   `docker run -d --name demo-mongo -m 400m -p 27017:27017 mongo:7 --wiredTigerCacheSizeGB 0.25`.

   > **Empezar limpio (después del ensayo y antes de la demo).** El ensayo deja datos de prueba en
   > `gimnasio` y en MongoDB. Antes de presentar, con los backends generados apagados:
   >
   > ```powershell
   > docker exec colabmodel-db psql -U colabmodel -d colabmodel -c "DROP DATABASE IF EXISTS gimnasio;"
   > docker exec colabmodel-db psql -U colabmodel -d colabmodel -c "CREATE DATABASE gimnasio;"
   > docker rm -f demo-mongo
   > docker run -d --name demo-mongo -m 400m -p 27017:27017 mongo:7 --wiredTigerCacheSizeGB 0.25
   > ```
   >
   > El proyecto «Gimnasio» de la herramienta también queda del ensayo. Para dibujarlo desde cero,
   > borrarlo o usar otro nombre (por ejemplo, «Gimnasio Demo»).
4. **Modelo local:** lo deja listo el compañero de IA local (Ollama con `qwen3:8b` o `qwen3.5:9b`).
5. **Backend de la herramienta:** `cd backend; mvn spring-boot:run` (puerto 8080).
6. **Frontend:** `cd frontend; npm run dev` (puerto 5173). Abrir las tres ventanas y dejar
   creadas las cuentas de Ana, Beto y Carla.
7. **Caché de Maven caliente:** compilar una vez cualquier backend generado
   (`mvn -q package -DskipTests`), para que en la demo compile sin red y en unos 20 s.
8. **Teléfono:** APK compilado desde `main` (trae la voz) e instalado, en la misma Wi-Fi. Anotar la IP
   de la computadora (`ipconfig`). El teléfono solo necesita **8080** (visor, herramienta) y **8081**
   (asistente, backend generado): la regla de entrada «ColabModel 8080-8081» (TCP, perfil
   **Privado**) **ya existe** en esta máquina. El 8082 (MongoDB) lo usa solo el navegador de la
   computadora, así que no hace falta abrirlo. Aceptar antes el permiso del micrófono.
9. **Ensayo corto de red:** desde el navegador del teléfono abrir `http://<IP>:8080/swagger-ui.html`,
   que es pública en la herramienta. La raíz `/` da 401 porque pide sesión, aunque haya red. Cuando
   el backend generado esté arriba (paso 4), abrir `http://<IP>:8081/`: tiene que verse la consola.

**Durante la demo:** no abrir programas nuevos. Si la memoria baja de 1 GB, apagar primero el
backend de MongoDB (paso 4.4).

**Después:** apagar los backends generados, `docker rm -f demo-mongo`, `docker compose stop`.

---

## Qué falta para poder mostrar todo esto

Todo el código está en `main`. Lo que todavía depende de algo:

| Paso | Depende de | Estado |
|---|---|---|
| 2.2 a 2.5 (respuestas de la IA) | el modelo local del compañero | externo al equipo; plan B sin modelo |
| 6.4 (envío automático tras el silencio) | la segunda prueba de voz en el teléfono | se comprueba |
| 6.3 a 6.6 dictados (caso de la pregunta pendiente y «cancelar» por voz) | la segunda prueba de voz en el teléfono | se comprueba |

En el ensayo general hay que tomar capturas de cada paso, medir la memoria en el peor momento
(los pasos 4 y 6, con todo arriba) y anotar cada error encontrado.
