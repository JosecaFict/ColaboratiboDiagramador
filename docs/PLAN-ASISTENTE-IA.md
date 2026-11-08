# Plan: asistente IA con Ollama

Fecha: 2026-11-07. Rama de trabajo: `feature/asistente-ia`.

## Punto de referencia

| Que | Valor |
| --- | --- |
| Tag git | `base-antes-asistente` (commit `837a71b`) |
| Modelo | `qwen3.5:9b-q4_K_M`, thinking desactivado, temperatura 0.1, contexto 8192 |
| Flujo actual | Dominio -> Proyecto -> vista IA (reemplaza al lienzo) -> Disenar -> confirmar -> volver al diagrama |
| Calidad conocida | 5 dominios aceptados con Qwen 9B (ver `EVALUACION-IA-2026-11-05.md`), sin tasa de exito medida |

Volver al estado de referencia en cualquier momento:

```powershell
git diff base-antes-asistente          # ver que cambio desde la referencia
git switch main                        # main queda en la referencia
```

## Metricas que se comparan en cada fase

Se anotan en la tabla del final, siempre con la GPU libre (sin juegos ni overlays).

- **Tiempo total de Disenar** por dominio (segundos).
- **Llamadas a Ollama** por pedido de Disenar.
- **Tokens por segundo** y **tokens de prompt** por llamada (detecta truncamiento cerca de 8192).
- **`ollama ps` -> PROCESSOR**: debe decir `100% GPU`.
- **Tasa de exito**: 3 corridas por dominio; exito = propuesta aceptada, sin rechazados ni truncada.
- **Tiempo de respuesta del asistente** (consultar y recomendar).

---

## Fase 0 - Linea base (sin cambios de codigo)

1. Cerrar juegos y overlays. Verificar en el Administrador de tareas que la VRAM libre sea mayor a 7 GB.
2. Configurar Ollama (variables de entorno de Windows, reiniciar Ollama):
   `OLLAMA_FLASH_ATTENTION=1`, `OLLAMA_KV_CACHE_TYPE=q8_0`, `OLLAMA_MAX_LOADED_MODELS=1`, `OLLAMA_NUM_PARALLEL=1`.
3. Correr la suite completa desde NetBeans (JDK 21). Anotar: pruebas, fallos, omitidas.
4. Correr `CincoDominiosOllamaVivoTest` 3 veces por dominio (comando en `EVALUACION-IA-2026-11-05.md`).
   Durante la corrida, ejecutar `ollama ps` y anotar PROCESSOR.
5. Llenar la fila "Fase 0" de la tabla.

**Prueba de aceptacion:** existe una fila de numeros contra la cual comparar todo lo demas.

## Fase 1 - Ollama: memoria y telemetria (backend)

- `keep_alive` configurable en `application.yml` (`colabmodel.ai.ollama.keep-alive: 10m`). Mientras se usa el asistente el modelo no se descarga; tras ese tiempo sin uso se libera la GPU solo.
- Registrar por llamada: etapa (disenar, revisar, reparar, recomendar), `prompt_eval_count`, `eval_count`, tokens/s, `load_duration`.
- Advertencia en el log cuando `prompt_eval_count` supere el 90 % de `num_ctx`.
- `connectTimeout` corto en el `HttpClient` para fallar rapido si Ollama no esta levantado.

**Pruebas:**
- Unitarias: el cuerpo enviado incluye `keep_alive`; la telemetria se lee bien de una respuesta de ejemplo; la advertencia de contexto se emite en el umbral.
- Manual: Disenar, luego `ollama ps` -> `UNTIL` refleja el tiempo configurado.
- Vivo: repetir la Fase 0 y comparar tiempos (el segundo pedido no debe pagar `load_duration`).

## Fase 2 - Modo RECOMENDAR (backend)

- `ModoIA.RECOMENDAR`: sin herramientas, salida con `format` (JSON Schema) que devuelve una lista de recomendaciones: `id`, `titulo`, `gravedad` (alta/media/baja), `elementos` (clases o relaciones afectadas), `accion` (texto aplicable).
- Trabaja sobre el diagrama real (contexto desde la base, igual que REVISAR).
- Aplicar una o varias recomendaciones = enviar su `accion` como pedido de MODIFICAR. Se reutiliza la validacion y la vista previa existentes; nada se escribe sin confirmacion.

**Pruebas:**
- Unitarias con proveedor simulado: el esquema se valida; una recomendacion invalida se descarta sin romper el resto; aplicar produce una propuesta MODIFICAR.
- Vivo: con el diagrama de ventas generado por Disenar, pedir recomendaciones, aplicar dos y verificar que la propuesta resultante no tenga rechazados.

## Fase 3 - Panel lateral del asistente (frontend)

- `VistaIA` deja de reemplazar al lienzo: panel lateral plegable junto al diagrama, disponible en todo el proyecto.
- La conversacion se conserva al ocultar el panel; aviso de respuestas nuevas mientras esta oculto.
- Ancho ajustable y atajo `Ctrl+I`.
- Acciones dentro del panel: Consultar (chat), Disenar, Recomendar, Modificar. Selector de modelo (por defecto el mismo 9B para todo, para no intercambiar modelos en la VRAM).
- Recomendaciones como tarjetas: gravedad, "Ver en diagrama" (resalta elementos), "Aplicar", "Aplicar seleccionadas".

**Pruebas:**
- Unitarias (vitest): estado de la conversacion, seleccion de recomendaciones.
- Manual, lista de verificacion:
  - [ ] Abrir y ocultar el panel sin perder la charla.
  - [ ] Disenar y ver aparecer el diagrama sin cambiar de vista.
  - [ ] Recomendar -> aplicar 1 -> el cambio aparece en vivo.
  - [ ] Un segundo usuario conectado ve el cambio aplicado, pero no la charla.
  - [ ] Deshacer revierte el lote aplicado por el asistente.

## Fase 4 - Liberar la GPU al salir (opcional)

- Al salir del proyecto, el backend pide a Ollama descargar el modelo solo si no hay otros pedidos de IA en curso.

**Prueba:** salir del proyecto -> `ollama ps` vacio en pocos segundos; con otra sesion usando la IA, no se descarga.

---

## Etapa siguiente (despues de medir las fases 1-3)

1. **Asesor del requisito**: preguntas de aclaracion y ficha estructurada con reglas numeradas (R1, R2...).
2. **Verificacion por reglas** en RECOMENDAR: cada regla de la ficha queda trazada a un elemento del diagrama (cumple / no cumple).
3. **Control "Listo para generar"** antes de generar base de datos y backend: claves primarias, tipos mapeables, sin muchos a muchos sin resolver, identificadores Java validos.
4. **Simplificar Disenar**: `format` desde la primera llamada y una sola pasada; la mejora pasa al ciclo Recomendar -> Aplicar.
5. **Retirar reglas de dominio** de `NormalizadorPlanUml` y `RevisorCoberturaPlan`, conservando las genericas.
6. **Dominios de reserva**: 3-4 requisitos nuevos que nunca se usen para ajustar reglas; se evaluan solo para medir.

## Tabla de resultados

| Fase | Fecha | PROCESSOR | Disenar (s, promedio) | Llamadas por Disenar | Tokens/s | Exito (x/15) | Asistente (s) | Notas |
| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | --- |
| 0 - linea base | | | | | | | - | |
| 1 - Ollama | | | | | | | - | |
| 2-3 - asistente | | | | | | | | |
