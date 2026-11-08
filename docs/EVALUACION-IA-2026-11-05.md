# Evaluacion de modelos UML con Ollama

Las mejoras se aplicaron en `ColaboratiboDiagramador`. No se modifico la copia original `colabmodel` ni se publicaron cambios remotos.

## Que cambio

- El proveedor distingue un JSON legible de un diseno semanticamente completo: un borrador reparable ya no se descarta prematuramente.
- Las referencias inequivocas a clases propuestas se convierten en asociaciones antes de retirar sus atributos FK. Se conservan la obligatoriedad y la unicidad expresadas por esas referencias.
- Se comprueba cobertura observable del requisito: inventario, genealogia, productos administrados, contexto curricular, prerrequisitos, roles de transferencias, itinerarios, viajeros, fechas e importes.
- La reparacion dirigida conserva el borrador y admite hasta 16 ajustes, incluyendo como maximo dos clases esenciales omitidas. No permite borrar clases existentes ni cambiar sus identidades. El resultado vuelve a pasar todas las validaciones y el limite de 48 cambios.
- Los roles origen/destino se interpretan en el extremo del objeto. Se corrigen inversiones inequivocas sin agregar cuentas o lotes duplicados.
- La herencia no se trata como una asociacion con cardinalidades. Un parentesco redundante solo se simplifica si no pierde datos propios.
- Las reglas especificas de participacion prevalecen sobre la regla generica de historial. Tener vigencia no convierte por si solo un catalogo en un evento historico.
- Se compactaron las solicitudes de revision y se acoto la salida: 4096 tokens para planes y 2048 para ajustes dirigidos. El contexto sigue siendo de 8192 tokens y el timeout de 180 segundos por peticion HTTP. Una consulta puede realizar varias peticiones internas.

## Como se comprueba

`CincoDominiosOllamaVivoTest` usa el proveedor real y el servicio de propuestas, con repositorios simulados vacios. No aplica cambios a diagramas ni escribe proyectos reales. Las inferencias se ejecutan secuencialmente.

Los cinco requisitos representativos estan dentro de esa prueba. Cubren ventas de computadoras, plan academico, banco, ganaderia y turismo. No son una copia literal de todos los prompts originales del usuario.

Modelo: `qwen3.5:9b-q4_K_M`, thinking desactivado y temperatura 0.1. Los resultados pueden variar entre ejecuciones.

Para repetir con Java y Maven ya configurados:

```powershell
cd "F:\Repositorios 2-2026\ColaboratiboDiagramador\backend"
$env:OLLAMA_CINCO_DOMINIOS = '1'
$env:OLLAMA_MODEL = 'qwen3.5:9b-q4_K_M'
$env:OLLAMA_DOMINIOS = 'ventas,academico,banco,ganaderia,turismo'
mvn test '-Dtest=CincoDominiosOllamaVivoTest'
Remove-Item Env:OLLAMA_CINCO_DOMINIOS -ErrorAction SilentlyContinue
Remove-Item Env:OLLAMA_DOMINIOS -ErrorAction SilentlyContinue
```

Si Maven no esta en PATH, usar la ruta habitual de `mvn.cmd` de NetBeans.

La ultima salida de cada dominio se guarda en `backend/target/evaluaciones-ia`: propuesta JSON o error de ejecucion y respuestas por turno en `trazas`. Son artefactos generados, no datos del usuario. Cada nueva ejecucion sustituye los resultados de los dominios seleccionados.

## Validacion y alcance

Suite completa: 988 pruebas registradas, cero fallos, cero errores y cuatro omitidas. Las pruebas vivas son optativas y se ejecutan por separado.

Ultima propuesta aceptada de cada caso representativo, generada con Qwen 9B:

| Dominio | Cambios | Advertencias | Comandos rechazados | Descartados por tope |
| --- | ---: | ---: | ---: | ---: |
| Ventas de computadoras | 35 | 0 | 0 | 0 |
| Plan academico | 41 | 0 | 0 | 0 |
| Banco | 41 | 0 | 0 | 0 |
| Ganaderia | 47 | 0 | 0 | 0 |
| Turismo | 48 | 0 | 0 | 0 |

Los cinco dominios consiguieron producir propuestas aceptadas. Hubo varias iteraciones con omisiones y timeouts, y se repitieron los casos afectados por nuevas correcciones. La tabla combina la ultima ejecucion de cada dominio; no representa una unica pasada de cinco casos, un porcentaje estadistico de acierto ni ausencia de reparaciones internas.

Aceptar una propuesta significa que paso los controles implementados, que no hubo comandos rechazados y que no fue truncada. No demuestra que todas las decisiones de negocio sean correctas. Por ejemplo, la imputacion de un pago a una o varias cuotas exige definir como se registra el importe aplicado a cada cuota. Tampoco una asociacion UML garantiza por si sola conservacion de datos al borrar, ausencia de solapamientos, cupos o integridad transaccional.

La revision manual debe comprobar tambien si dos asociaciones con nombres y roles distintos representan responsabilidades diferentes o duplican el mismo vinculo. El control de duplicados exactos no resuelve todas las equivalencias semanticas.

Observaciones que siguen requiriendo criterio de negocio: la propuesta bancaria permite vincular pagos y cuotas en una relacion muchos a muchos; debe definirse la imputacion de importes. En el academico conviene revisar las dos asociaciones entre periodo y asignacion docente. En turismo, el orden de un paso no describe por si solo su actividad o destino: ese contenido debe completarse antes de utilizar el modelo como itinerario operativo. Estos puntos delimitan el alcance de los controles automaticos; cero advertencias no equivale a un diseno perfecto.

Para usar el codigo actualizado hay que reiniciar el backend de ColabModel. Estos cambios no requieren reiniciar el frontend ni regenerar los proyectos guardados.
