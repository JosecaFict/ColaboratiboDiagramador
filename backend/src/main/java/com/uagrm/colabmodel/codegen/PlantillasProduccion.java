package com.uagrm.colabmodel.codegen;

import com.uagrm.colabmodel.codegen.GeneradorSpringBoot.Almacen;
import com.uagrm.colabmodel.codegen.GeneradorSpringBoot.Asociacion;
import com.uagrm.colabmodel.codegen.GeneradorSpringBoot.Campo;
import com.uagrm.colabmodel.codegen.GeneradorSpringBoot.Meta;
import com.uagrm.colabmodel.codegen.GeneradorSpringBoot.Opciones;
import com.uagrm.colabmodel.codegen.GeneradorSpringBoot.Unidad;
import com.uagrm.colabmodel.modelcore.mapping.ModeloLogico;
import com.uagrm.colabmodel.modelcore.mapping.Nombres;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lo que el backend generado necesita para ir a produccion y que NO depende
 * de la base (ADR-034): la API REST, el formato unico de errores, la
 * paginacion, las pruebas, los perfiles y el despliegue.
 *
 * Vive aparte de GeneradorSpringBoot porque lo usan las dos variantes
 * (PostgreSQL y MongoDB) tal cual: si el controlador o el DTO cambiaran de
 * una a otra, la app movil y el frontend tendrian que saber que base hay
 * atras, que es justo lo que ADR-035 promete que no pasa.
 */
final class PlantillasProduccion {

    /** Ids fijos de las pruebas generadas: asi una falla se reproduce igual. */
    static final String ID_PRUEBA = "00000000-0000-4000-8000-0000000000a1";
    static final String ID_REFERENCIA = "00000000-0000-4000-8000-0000000000b2";

    private PlantillasProduccion() { }

    // ------------------------------------------------------------ MAVEN

    static String dependenciasComunes() {
        return """
                        <!-- /actuator/health e /actuator/info (ADR-034). -->
                        <dependency>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-starter-actuator</artifactId>
                        </dependency>
                        <dependency>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-starter-test</artifactId>
                            <scope>test</scope>
                        </dependency>
                """;
    }

    // ------------------------------------------------------------- CONFIG

    /** Lo que vale en todos los perfiles: puerto, apagado ordenado, Actuator, CORS y logs. */
    static String yamlComun(Opciones o) {
        return """
                server:
                  # PORT manda en produccion (Docker, Railway, Render...).
                  port: ${PORT:8081}
                  # Apagado ordenado: las peticiones en curso terminan antes de cerrar.
                  shutdown: graceful
                  error:
                    include-stacktrace: never
                spring.lifecycle.timeout-per-shutdown-phase: 20s

                management:
                  endpoints:
                    web:
                      exposure:
                        include: health,info
                  info:
                    env:
                      enabled: true

                info:
                  app:
                    nombre: {{proyecto}}
                    generado-por: ColabModel
                    base: {{base}}

                app:
                  cors:
                    # Separados por coma. "*" acepta cualquier origen (solo para desarrollar).
                    origenes: ${CORS_ORIGINS:*}

                logging:
                  level:
                    root: INFO

                """
                .replace("{{proyecto}}", o.nombreProyecto())
                .replace("{{base}}", o.almacen() == Almacen.MONGO ? "mongodb" : "postgresql");
    }

    /**
     * Lo que se agrega dentro del documento `prod`. No puede traer `spring:`:
     * ese documento ya lo abre la variante con su base.
     */
    static String yamlProd(Opciones o) {
        return """
                app:
                  cors:
                    # En produccion no se abre a nadie que no este en CORS_ORIGINS.
                    origenes: ${CORS_ORIGINS:}
                management:
                  endpoint:
                    health:
                      show-details: never
                      probes:
                        enabled: true
                springdoc:
                  api-docs:
                    enabled: false
                  swagger-ui:
                    enabled: false
                logging:
                  level:
                    root: WARN
                    "{{pkg}}": INFO
                """.replace("{{pkg}}", o.paquete());
    }

    static String cors(String pkg) {
        return """
                package {{pkg}}.config;

                import org.springframework.beans.factory.annotation.Value;
                import org.springframework.context.annotation.Configuration;
                import org.springframework.web.servlet.config.annotation.CorsRegistry;
                import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

                import java.util.Arrays;

                /**
                 * CORS desde configuracion (CORS_ORIGINS). La consola de `/` no lo
                 * necesita: la sirve este mismo backend. Lo necesita un frontend en
                 * otro origen, y por eso se exponen X-Total-Count y Location: sin
                 * eso el navegador se las esconde al JavaScript.
                 */
                @Configuration
                public class CorsConfig implements WebMvcConfigurer {

                    private final String[] origenes;

                    public CorsConfig(@Value("${app.cors.origenes:*}") String origenes) {
                        this.origenes = Arrays.stream(origenes.split(","))
                                .map(String::trim).filter(o -> !o.isEmpty()).toArray(String[]::new);
                    }

                    @Override
                    public void addCorsMappings(CorsRegistry registry) {
                        if (origenes.length == 0) return;
                        registry.addMapping("/**")
                                .allowedOriginPatterns(origenes)
                                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                                .allowedHeaders("*")
                                .exposedHeaders("X-Total-Count", "Location");
                    }
                }
                """.replace("{{pkg}}", pkg);
    }

    // ---------------------------------------------------- ARCHIVOS FIJOS

    /** Los que no dependen del modelo: errores, paginas, version y parches. */
    static Map<String, String> comunes(String pkg, String ruta) {
        Map<String, String> a = new LinkedHashMap<>();
        a.put(ruta + "/web/ManejadorDeErrores.java", manejadorDeErrores(pkg));
        a.put(ruta + "/web/Parches.java", parches(pkg));
        a.put(ruta + "/service/Pagina.java", pagina(pkg));
        a.put(ruta + "/service/Paginas.java", paginas(pkg));
        a.put(ruta + "/service/PeticionInvalidaException.java", peticionInvalida(pkg));
        a.put(ruta + "/service/ConflictoException.java", conflicto(pkg));
        a.put(ruta + "/service/Versiones.java", versiones(pkg));
        return a;
    }

    /**
     * UN formato para todos los errores: `{"error": "...", "errores": {...}}`.
     * `errores` va siempre, vacio si no hay campos que marcar, para que el
     * cliente no tenga que preguntar si existe.
     */
    private static String manejadorDeErrores(String pkg) {
        return """
                package {{pkg}}.web;

                import {{pkg}}.service.ConflictoException;
                import {{pkg}}.service.PeticionInvalidaException;
                import com.fasterxml.jackson.core.JsonProcessingException;
                import com.fasterxml.jackson.databind.exc.MismatchedInputException;
                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;
                import org.springframework.dao.DataIntegrityViolationException;
                import org.springframework.dao.OptimisticLockingFailureException;
                import org.springframework.http.HttpStatus;
                import org.springframework.http.HttpStatusCode;
                import org.springframework.http.ResponseEntity;
                import org.springframework.http.converter.HttpMessageNotReadableException;
                import org.springframework.validation.FieldError;
                import org.springframework.web.ErrorResponse;
                import org.springframework.web.bind.MethodArgumentNotValidException;
                import org.springframework.web.bind.annotation.ExceptionHandler;
                import org.springframework.web.bind.annotation.RestControllerAdvice;
                import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

                import java.util.Arrays;
                import java.util.LinkedHashMap;
                import java.util.Map;
                import java.util.NoSuchElementException;
                import java.util.stream.Collectors;

                /**
                 * Codigos HTTP honestos y un solo formato de error (ADR-034):
                 *
                 *   {"error": "mensaje legible", "errores": {"campo": "que fallo"}}
                 *
                 * 400 lo que el cliente mando mal, 404 lo que no existe, 409 lo que
                 * choca con otro dato (unico repetido, algo que todavia se referencia,
                 * una version vieja) y 500 lo demas, SIN el stack trace: ese queda en
                 * el log del servidor, que es donde sirve.
                 */
                @RestControllerAdvice
                public class ManejadorDeErrores {

                    private static final Logger log = LoggerFactory.getLogger(ManejadorDeErrores.class);

                    /** Un id que no existe es un 404, no una falla del servidor. */
                    @ExceptionHandler(NoSuchElementException.class)
                    public ResponseEntity<Map<String, Object>> noEncontrado(NoSuchElementException e) {
                        return error(HttpStatus.NOT_FOUND, e.getMessage(), Map.of());
                    }

                    /**
                     * Cuerpo que no pasa las validaciones del DTO.
                     *
                     * Se devuelve `errores` campo por campo para que un formulario
                     * pueda marcar el que fallo. Si un campo junta dos violaciones
                     * se informa la primera: una por campo es lo que un formulario
                     * sabe mostrar.
                     */
                    @ExceptionHandler(MethodArgumentNotValidException.class)
                    public ResponseEntity<Map<String, Object>> invalido(MethodArgumentNotValidException e) {
                        Map<String, String> errores = new LinkedHashMap<>();
                        for (FieldError f : e.getBindingResult().getFieldErrors()) {
                            errores.putIfAbsent(f.getField(), f.getDefaultMessage());
                        }
                        return error(HttpStatus.BAD_REQUEST, "Hay campos invalidos", errores);
                    }

                    /** Parametros o cambios parciales invalidos que detecta la propia API. */
                    @ExceptionHandler(PeticionInvalidaException.class)
                    public ResponseEntity<Map<String, Object>> peticionInvalida(PeticionInvalidaException e) {
                        return error(HttpStatus.BAD_REQUEST, e.getMessage(), e.errores());
                    }

                    /** Un uuid mal escrito en la URL o en un filtro es culpa del cliente. */
                    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
                    public ResponseEntity<Map<String, Object>> tipoInvalido(MethodArgumentTypeMismatchException e) {
                        String mensaje = "El valor de '" + e.getName() + "' no tiene el formato esperado";
                        return error(HttpStatus.BAD_REQUEST, mensaje, Map.of(e.getName(), mensaje));
                    }

                    /**
                     * JSON roto, o un valor que no es del tipo del campo (texto donde
                     * va un numero, un literal que la enumeracion no tiene). Si se
                     * sabe que campo fue, se marca, como cualquier otro 400.
                     */
                    @ExceptionHandler({HttpMessageNotReadableException.class, JsonProcessingException.class})
                    public ResponseEntity<Map<String, Object>> ilegible(Exception e) {
                        Throwable causa = e instanceof JsonProcessingException ? e : e.getCause();
                        Map<String, String> errores = new LinkedHashMap<>();
                        if (causa instanceof MismatchedInputException m && !m.getPath().isEmpty()) {
                            String campo = m.getPath().stream()
                                    .map(r -> r.getFieldName() != null ? r.getFieldName() : String.valueOf(r.getIndex()))
                                    .collect(Collectors.joining("."));
                            Class<?> tipo = m.getTargetType();
                            String esperado = tipo == null ? "" : tipo.isEnum()
                                    ? ": valores posibles " + Arrays.toString(tipo.getEnumConstants())
                                    : ": se esperaba " + tipo.getSimpleName();
                            errores.put(campo, "Valor no valido" + esperado);
                        }
                        return error(HttpStatus.BAD_REQUEST, errores.isEmpty()
                                ? "El cuerpo no es JSON valido o algun valor no tiene el tipo esperado"
                                : "Hay campos invalidos", errores);
                    }

                    @ExceptionHandler(ConflictoException.class)
                    public ResponseEntity<Map<String, Object>> conflicto(ConflictoException e) {
                        return error(HttpStatus.CONFLICT, e.getMessage(), Map.of());
                    }

                    /** Dos escrituras sobre la misma version: la segunda pierde, y se le dice. */
                    @ExceptionHandler(OptimisticLockingFailureException.class)
                    public ResponseEntity<Map<String, Object>> versionVieja(OptimisticLockingFailureException e) {
                        return error(HttpStatus.CONFLICT, "Otro usuario modifico este registro mientras lo editabas."
                                + " Volve a leerlo y aplica el cambio de nuevo.", Map.of());
                    }

                    /** Clave unica repetida, o una fila que otra todavia referencia. */
                    @ExceptionHandler(DataIntegrityViolationException.class)
                    public ResponseEntity<Map<String, Object>> restriccion(DataIntegrityViolationException e) {
                        return error(HttpStatus.CONFLICT, "La operacion viola una restriccion de la base: un valor"
                                + " unico repetido, o un registro que otro todavia referencia.", Map.of());
                    }

                    /**
                     * Todo lo demas. Lo que Spring ya sabe clasificar (405, 415, una
                     * ruta que no existe...) conserva su codigo; el resto es un 500
                     * que no le muestra al cliente nada interno.
                     */
                    @ExceptionHandler(Exception.class)
                    public ResponseEntity<Map<String, Object>> otro(Exception e) {
                        if (e instanceof ErrorResponse respuesta) {
                            HttpStatusCode codigo = respuesta.getStatusCode();
                            String detalle = respuesta.getBody().getDetail();
                            return error(codigo, detalle == null ? "La peticion no se pudo atender" : detalle, Map.of());
                        }
                        log.error("Error no previsto", e);
                        return error(HttpStatus.INTERNAL_SERVER_ERROR, "Error interno del servidor", Map.of());
                    }

                    private static ResponseEntity<Map<String, Object>> error(HttpStatusCode codigo, String mensaje,
                                                                             Map<String, String> errores) {
                        Map<String, Object> cuerpo = new LinkedHashMap<>();
                        cuerpo.put("error", mensaje == null ? "Error" : mensaje);
                        cuerpo.put("errores", errores);
                        return ResponseEntity.status(codigo).body(cuerpo);
                    }
                }
                """.replace("{{pkg}}", pkg);
    }

    private static String parches(String pkg) {
        return """
                package {{pkg}}.web;

                import {{pkg}}.service.PeticionInvalidaException;
                import com.fasterxml.jackson.core.JsonProcessingException;
                import com.fasterxml.jackson.databind.JsonNode;
                import com.fasterxml.jackson.databind.ObjectMapper;
                import com.fasterxml.jackson.databind.node.ObjectNode;
                import jakarta.validation.ConstraintViolation;
                import jakarta.validation.Validator;

                import java.util.LinkedHashMap;
                import java.util.Map;

                /**
                 * PATCH: un cambio PARCIAL (ADR-034). Se parte del registro actual,
                 * se pisan solo los campos que vienen en el cuerpo y se valida el
                 * resultado completo, con las mismas reglas que un PUT. Un campo que
                 * el recurso no tiene es un 400, no un cambio que se pierde callado.
                 */
                public final class Parches {

                    private Parches() { }

                    public static <T> T aplicar(ObjectMapper mapper, Validator validador, T actual, JsonNode cambios,
                                                Class<T> tipo) throws JsonProcessingException {
                        if (cambios == null || !cambios.isObject()) {
                            throw new PeticionInvalidaException("cuerpo",
                                    "Un PATCH lleva un objeto JSON con los campos a cambiar");
                        }
                        ObjectNode mezcla = mapper.valueToTree(actual);
                        Map<String, String> desconocidos = new LinkedHashMap<>();
                        for (Map.Entry<String, JsonNode> cambio : cambios.properties()) {
                            if (mezcla.has(cambio.getKey())) mezcla.set(cambio.getKey(), cambio.getValue());
                            else desconocidos.put(cambio.getKey(), "No es un campo de este recurso");
                        }
                        if (!desconocidos.isEmpty()) throw new PeticionInvalidaException(desconocidos);

                        T resultado = mapper.treeToValue(mezcla, tipo);
                        Map<String, String> errores = new LinkedHashMap<>();
                        for (ConstraintViolation<T> v : validador.validate(resultado)) {
                            errores.putIfAbsent(v.getPropertyPath().toString(), v.getMessage());
                        }
                        if (!errores.isEmpty()) throw new PeticionInvalidaException(errores);
                        return resultado;
                    }
                }
                """.replace("{{pkg}}", pkg);
    }

    private static String pagina(String pkg) {
        return """
                package {{pkg}}.service;

                import java.util.List;

                /**
                 * Una pagina del listado y el total de registros que cumplen el
                 * filtro. El total viaja en la cabecera X-Total-Count: el cuerpo
                 * sigue siendo un arreglo, que es lo que lee la app movil.
                 */
                public record Pagina<T>(List<T> contenido, long total) {

                    public static final String CABECERA_TOTAL = "X-Total-Count";
                }
                """.replace("{{pkg}}", pkg);
    }

    private static String paginas(String pkg) {
        return """
                package {{pkg}}.service;

                import org.springframework.data.domain.PageRequest;
                import org.springframework.data.domain.Pageable;
                import org.springframework.data.domain.Sort;

                import java.util.Locale;
                import java.util.Set;
                import java.util.TreeSet;

                /** `page`, `size` y `sort=campo,asc|desc` del listado, validados (ADR-034). */
                public final class Paginas {

                    public static final int TAMANIO_POR_DEFECTO = 20;
                    public static final int TAMANIO_MAXIMO = 500;

                    private Paginas() { }

                    public static Sort orden(String sort, Set<String> permitidos) {
                        if (sort == null || sort.isBlank()) return Sort.unsorted();
                        // Conserva los campos vacios: una coma final no es un orden valido.
                        String[] partes = sort.split(",", -1);
                        if (partes.length > 2 || partes[0].isBlank()
                                || (partes.length == 2 && partes[1].isBlank())) {
                            throw new PeticionInvalidaException("sort",
                                    "Usa campo o campo,asc|desc para ordenar");
                        }
                        String campo = partes[0].trim();
                        if (!permitidos.contains(campo)) {
                            throw new PeticionInvalidaException("sort", "No se puede ordenar por '" + campo
                                    + "'. Campos validos: " + String.join(", ", new TreeSet<>(permitidos)));
                        }
                        if (partes.length == 1) return Sort.by(Sort.Direction.ASC, campo);
                        return switch (partes[1].trim().toLowerCase(Locale.ROOT)) {
                            case "asc" -> Sort.by(Sort.Direction.ASC, campo);
                            case "desc" -> Sort.by(Sort.Direction.DESC, campo);
                            default -> throw new PeticionInvalidaException("sort",
                                    "La direccion de orden es asc o desc, no '" + partes[1].trim() + "'");
                        };
                    }

                    public static Pageable pagina(int page, Integer size, Sort orden) {
                        if (page < 0) throw new PeticionInvalidaException("page", "page empieza en 0");
                        int tamanio = size == null ? TAMANIO_POR_DEFECTO : size;
                        if (tamanio < 1 || tamanio > TAMANIO_MAXIMO) {
                            throw new PeticionInvalidaException("size", "size va de 1 a " + TAMANIO_MAXIMO);
                        }
                        return PageRequest.of(page, tamanio, orden);
                    }
                }
                """.replace("{{pkg}}", pkg);
    }

    private static String peticionInvalida(String pkg) {
        return """
                package {{pkg}}.service;

                import java.util.Collections;
                import java.util.LinkedHashMap;
                import java.util.Map;

                /** Un 400 con los campos que fallaron, en el formato unico de errores. */
                public class PeticionInvalidaException extends RuntimeException {

                    private final Map<String, String> errores;

                    public PeticionInvalidaException(String campo, String mensaje) {
                        super(mensaje);
                        this.errores = Map.of(campo, mensaje);
                    }

                    public PeticionInvalidaException(Map<String, String> errores) {
                        super("Hay campos invalidos");
                        this.errores = Collections.unmodifiableMap(new LinkedHashMap<>(errores));
                    }

                    public Map<String, String> errores() {
                        return errores;
                    }
                }
                """.replace("{{pkg}}", pkg);
    }

    private static String conflicto(String pkg) {
        return """
                package {{pkg}}.service;

                /** Un 409 con un mensaje que dice que choca y que hacer. */
                public class ConflictoException extends RuntimeException {

                    public ConflictoException(String mensaje) {
                        super(mensaje);
                    }
                }
                """.replace("{{pkg}}", pkg);
    }

    private static String versiones(String pkg) {
        return """
                package {{pkg}}.service;

                /**
                 * Bloqueo optimista visto desde el cliente (ADR-034).
                 *
                 * La base ya impide que dos escrituras CONCURRENTES se pisen. Esto
                 * cubre el otro caso, el comun: alguien abre el formulario, otro
                 * guarda, y el primero guarda despues sobre lo que ya no existe. Si
                 * manda la version que leyo y no es la actual, es un 409.
                 */
                public final class Versiones {

                    private Versiones() { }

                    public static void comprobar(Long enviada, Long actual, String entidad, Object id) {
                        if (enviada != null && !enviada.equals(actual)) {
                            throw new ConflictoException(entidad + " " + id + " cambio desde que lo leiste (version "
                                    + enviada + ", ahora es " + actual + "). Volve a leerlo y aplica el cambio de nuevo.");
                        }
                    }
                }
                """.replace("{{pkg}}", pkg);
    }

    // -------------------------------------------------------- CONTROLADOR

    /**
     * El REST de una entidad, el mismo para PostgreSQL y para MongoDB.
     *
     * El listado acepta un filtro por cada FK, con el mismo nombre con que el
     * id viaja en el DTO y en `app-manifest.json` (`clienteId`), mas `page`,
     * `size` y `sort`. Sin `page` devuelve todo: es lo que hace la app movil.
     *
     * RECORTE DECLARADO: se aplica UN filtro por FK, el primero que venga.
     */
    static String controlador(String pkg, Unidad u) {
        String clase = u.clase();
        StringBuilder filtros = new StringBuilder();
        StringBuilder despacho = new StringBuilder();
        for (Asociacion a : u.asociaciones()) {
            String param = a.campo() + "Id";
            filtros.append(",\n            @RequestParam(required = false) UUID ").append(param);
            despacho.append(param).append(" != null ? servicio.listarPor").append(mayuscula(a.campo()))
                    .append("(").append(param).append(", page, size, sort)\n                : ");
        }

        String escritura = !u.instanciable() ? "" : """

                    @PostMapping
                    public ResponseEntity<{{clase}}Dto> crear(@Valid @RequestBody {{clase}}Dto dto) {
                        {{clase}}Dto creado = servicio.crear(dto);
                        URI ubicacion = ServletUriComponentsBuilder.fromCurrentRequest()
                                .path("/{id}").buildAndExpand(creado.id()).toUri();
                        return ResponseEntity.created(ubicacion).body(creado);
                    }

                    /** Reemplazo completo: lo que no viene queda vacio. */
                    @PutMapping("/{id}")
                    public {{clase}}Dto actualizar(@PathVariable UUID id, @Valid @RequestBody {{clase}}Dto dto) {
                        return servicio.actualizar(id, dto);
                    }

                    /** Cambio parcial: solo los campos que vienen en el cuerpo. */
                    @PatchMapping("/{id}")
                    public {{clase}}Dto parchear(@PathVariable UUID id, @RequestBody JsonNode cambios)
                            throws JsonProcessingException {
                        {{clase}}Dto cambiado = Parches.aplicar(mapper, validador, servicio.obtener(id), cambios,
                                {{clase}}Dto.class);
                        return servicio.actualizar(id, cambiado);
                    }
                """;

        return """
                package {{pkg}}.web;

                import {{pkg}}.service.{{clase}}Service;
                import {{pkg}}.service.Pagina;
                import {{pkg}}.web.dto.{{clase}}Dto;
                {{importsEscritura}}import org.springframework.http.ResponseEntity;
                import org.springframework.web.bind.annotation.*;

                import java.util.List;
                import java.util.UUID;

                /** Solo DTOs: la entidad nunca cruza esta frontera. */
                @RestController
                @RequestMapping("{{recurso}}")
                public class {{clase}}Controller {

                    private final {{clase}}Service servicio;
                {{campos}}
                    public {{clase}}Controller({{clase}}Service servicio{{parametros}}) {
                        this.servicio = servicio;
                {{asignaciones}}    }

                    @GetMapping
                    public ResponseEntity<List<{{clase}}Dto>> listar(
                            @RequestParam(required = false) Integer page,
                            @RequestParam(required = false) Integer size,
                            @RequestParam(required = false) String sort{{filtros}}) {
                        Pagina<{{clase}}Dto> resultado = {{despacho}}servicio.listar(page, size, sort);
                        return ResponseEntity.ok()
                                .header(Pagina.CABECERA_TOTAL, String.valueOf(resultado.total()))
                                .body(resultado.contenido());
                    }

                    @GetMapping("/{id}")
                    public {{clase}}Dto obtener(@PathVariable UUID id) {
                        return servicio.obtener(id);
                    }
                {{escritura}}
                    @DeleteMapping("/{id}")
                    public ResponseEntity<Void> eliminar(@PathVariable UUID id) {
                        servicio.eliminar(id);
                        return ResponseEntity.noContent().build();
                    }
                }
                """
                .replace("{{escritura}}", escritura)
                .replace("{{importsEscritura}}", !u.instanciable() ? "" : """
                        import com.fasterxml.jackson.core.JsonProcessingException;
                        import com.fasterxml.jackson.databind.JsonNode;
                        import com.fasterxml.jackson.databind.ObjectMapper;
                        import jakarta.validation.Valid;
                        import jakarta.validation.Validator;
                        import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

                        import java.net.URI;
                        """)
                .replace("{{campos}}", !u.instanciable() ? "" : """
                            private final ObjectMapper mapper;
                            private final Validator validador;
                        """)
                .replace("{{parametros}}", !u.instanciable() ? "" : ", ObjectMapper mapper, Validator validador")
                .replace("{{asignaciones}}", !u.instanciable() ? "" : """
                                this.mapper = mapper;
                                this.validador = validador;
                        """)
                .replace("{{filtros}}", filtros.toString())
                .replace("{{despacho}}", despacho.toString())
                .replace("{{recurso}}", u.recurso())
                .replace("{{pkg}}", pkg)
                .replace("{{clase}}", clase);
    }

    // ------------------------------------------------------------ PRUEBAS

    /**
     * Una prueba MockMvc por controlador (ADR-034): leer, 404, id mal escrito,
     * listado con total y, si se puede crear, crear con 201 + Location,
     * validacion campo por campo y PATCH con un campo desconocido.
     *
     * Es @WebMvcTest con el Service simulado, asi que no necesita base: la
     * misma prueba corre igual en la variante PostgreSQL y en la MongoDB, y
     * `mvn package` la ejecuta sin levantar nada.
     */
    static String prueba(String pkg, Unidad u, Map<String, List<Campo>> camposDeValores,
                         Map<String, List<String>> literales) {
        String cuerpo = ejemplo(u, camposDeValores, literales);
        String conId = "{\"id\":\"" + ID_PRUEBA + "\"" + (cuerpo.equals("{}") ? "}" : "," + cuerpo.substring(1));
        String requerido = primerRequerido(u);

        StringBuilder pruebas = new StringBuilder("""

                    @Test
                    void leer_devuelve_el_registro() throws Exception {
                        given(servicio.obtener(ID)).willReturn(guardado());
                        mvc.perform(get(RECURSO + "/" + ID))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.id").value(ID.toString()));
                    }

                    @Test
                    void un_id_que_no_existe_responde_404_con_el_formato_de_error() throws Exception {
                        given(servicio.obtener(ID)).willThrow(new NoSuchElementException("No existe {{clase}} " + ID));
                        mvc.perform(get(RECURSO + "/" + ID))
                                .andExpect(status().isNotFound())
                                .andExpect(jsonPath("$.error").exists())
                                .andExpect(jsonPath("$.errores").isMap());
                    }

                    @Test
                    void un_id_mal_escrito_responde_400() throws Exception {
                        mvc.perform(get(RECURSO + "/no-es-un-uuid"))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.errores.id").exists());
                    }

                    @Test
                    void el_listado_paginado_informa_el_total() throws Exception {
                        given(servicio.listar(any(), any(), any())).willReturn(new Pagina<>(List.of(guardado()), 1));
                        mvc.perform(get(RECURSO).param("page", "0").param("size", "10"))
                                .andExpect(status().isOk())
                                .andExpect(header().string("X-Total-Count", "1"))
                                .andExpect(jsonPath("$[0].id").value(ID.toString()));
                    }
                """);
        if (u.instanciable()) {
            pruebas.append("""

                        @Test
                        void crear_responde_201_con_location() throws Exception {
                            given(servicio.crear(any())).willReturn(guardado());
                            mvc.perform(post(RECURSO).contentType(MediaType.APPLICATION_JSON).content(CUERPO))
                                    .andExpect(status().isCreated())
                                    .andExpect(header().string("Location", endsWith(RECURSO + "/" + ID)))
                                    .andExpect(jsonPath("$.id").value(ID.toString()));
                        }

                        @Test
                        void un_json_roto_responde_400() throws Exception {
                            mvc.perform(post(RECURSO).contentType(MediaType.APPLICATION_JSON).content("{"))
                                    .andExpect(status().isBadRequest())
                                    .andExpect(jsonPath("$.error").exists());
                        }

                        @Test
                        void un_patch_con_un_campo_desconocido_responde_400() throws Exception {
                            given(servicio.obtener(ID)).willReturn(guardado());
                            mvc.perform(patch(RECURSO + "/" + ID).contentType(MediaType.APPLICATION_JSON)
                                            .content("{\\"campoQueNoExiste\\":1}"))
                                    .andExpect(status().isBadRequest())
                                    .andExpect(jsonPath("$.errores.campoQueNoExiste").exists());
                        }
                    """);
            if (requerido != null) {
                pruebas.append("""

                            @Test
                            void un_cuerpo_sin_lo_obligatorio_dice_que_campo_falta() throws Exception {
                                mvc.perform(post(RECURSO).contentType(MediaType.APPLICATION_JSON).content("{}"))
                                        .andExpect(status().isBadRequest())
                                        .andExpect(jsonPath("$.errores.{{requerido}}").exists());
                            }
                        """.replace("{{requerido}}", requerido));
            }
        }

        return """
                package {{pkg}}.web;

                import {{pkg}}.service.{{clase}}Service;
                import {{pkg}}.service.Pagina;
                import {{pkg}}.web.dto.{{clase}}Dto;
                import com.fasterxml.jackson.databind.ObjectMapper;
                import org.junit.jupiter.api.Test;
                import org.springframework.beans.factory.annotation.Autowired;
                import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
                import org.springframework.http.MediaType;
                import org.springframework.test.context.bean.override.mockito.MockitoBean;
                import org.springframework.test.web.servlet.MockMvc;

                import java.util.List;
                import java.util.NoSuchElementException;
                import java.util.UUID;

                import static org.hamcrest.Matchers.endsWith;
                import static org.mockito.ArgumentMatchers.any;
                import static org.mockito.BDDMockito.given;
                import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
                import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

                /** Generada desde el modelo: el contrato HTTP de {{clase}}, sin base de datos. */
                @WebMvcTest({{clase}}Controller.class)
                class {{clase}}ControllerTest {

                    private static final String RECURSO = "{{recurso}}";
                    private static final UUID ID = UUID.fromString("{{id}}");
                    /** Un cuerpo valido: lo obligatorio, con valores dentro de sus rangos. */
                    private static final String CUERPO = "{{cuerpo}}";
                    private static final String GUARDADO = "{{conId}}";

                    @Autowired
                    private MockMvc mvc;

                    @Autowired
                    private ObjectMapper mapper;

                    @MockitoBean
                    private {{clase}}Service servicio;

                    private {{clase}}Dto guardado() throws Exception {
                        return mapper.readValue(GUARDADO, {{clase}}Dto.class);
                    }
                {{pruebas}}}
                """
                .replace("{{pruebas}}", pruebas.toString())
                .replace("{{cuerpo}}", javaTexto(cuerpo))
                .replace("{{conId}}", javaTexto(conId))
                .replace("{{recurso}}", u.recurso())
                .replace("{{id}}", ID_PRUEBA)
                .replace("{{pkg}}", pkg)
                .replace("{{clase}}", u.clase());
    }

    /** El nombre JSON del primer campo que el DTO exige, o null si no exige ninguno. */
    private static String primerRequerido(Unidad u) {
        for (Campo c : u.campos()) {
            if (!c.esId() && (!c.nullable() || c.obligatorio())) return c.nombreJava();
        }
        for (Asociacion a : u.asociaciones()) {
            if (!a.nullable()) return a.campo() + "Id";
        }
        for (ModeloLogico.Incrustado i : u.incrustados()) {
            if (i.obligatorio()) return NombresJava.campo(i.campo());
        }
        return null;
    }

    /** Un cuerpo JSON valido para crear: todos los campos simples, y las referencias obligatorias. */
    static String ejemplo(Unidad u, Map<String, List<Campo>> camposDeValores, Map<String, List<String>> literales) {
        List<String> pares = new ArrayList<>();
        for (Campo c : u.campos()) {
            if (c.esId()) continue;
            pares.add("\"" + c.nombreJava() + "\":" + valor(c, literales));
        }
        for (Asociacion a : u.asociaciones()) {
            if (!a.nullable()) pares.add("\"" + a.campo() + "Id\":\"" + ID_REFERENCIA + "\"");
        }
        for (ModeloLogico.Incrustado i : u.incrustados()) {
            if (!i.obligatorio()) continue;
            List<String> dentro = new ArrayList<>();
            for (Campo c : camposDeValores.getOrDefault(NombresJava.clase(i.clase()), List.of())) {
                dentro.add("\"" + c.nombreJava() + "\":" + valor(c, literales));
            }
            pares.add("\"" + NombresJava.campo(i.campo()) + "\":{" + String.join(",", dentro) + "}");
        }
        return "{" + String.join(",", pares) + "}";
    }

    private static String valor(Campo c, Map<String, List<String>> literales) {
        if (c.enumerado()) {
            List<String> l = literales.getOrDefault(c.tipoJava(), List.of());
            return l.isEmpty() ? "null" : "\"" + l.get(0) + "\"";
        }
        return switch (c.tipoJava()) {
            case "Integer", "Long" -> numero(c, true);
            case "BigDecimal" -> numero(c, false);
            case "Boolean" -> "true";
            case "LocalDate" -> "\"2024-03-15\"";
            case "LocalDateTime" -> "\"2024-03-15T10:30:00\"";
            case "UUID" -> "\"" + ID_REFERENCIA + "\"";
            default -> "\"x\"";
        };
    }

    /** Un numero dentro del rango del modelo: el minimo si hay, si no 1 (o el maximo si 1 no entra). */
    private static String numero(Campo c, boolean entero) {
        BigDecimal n = BigDecimal.ONE;
        ModeloLogico.Rango r = c.rango();
        if (r != null && r.minimo() != null) n = r.minimo();
        else if (r != null && r.maximo() != null && r.maximo().compareTo(BigDecimal.ONE) < 0) n = r.maximo();
        if (entero) n = n.setScale(0, java.math.RoundingMode.CEILING);
        return ModeloLogico.Rango.texto(n);
    }

    /** Un texto como literal de Java: comillas y barras escapadas. */
    private static String javaTexto(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // --------------------------------------------------------- DESPLIEGUE

    static Map<String, String> despliegue(Opciones o) {
        Map<String, String> a = new LinkedHashMap<>();
        a.put("Dockerfile", dockerfile());
        a.put(".dockerignore", "target/\n.git/\n*.log\n");
        a.put("docker-compose.yml", o.almacen() == Almacen.MONGO ? composeMongo(o) : composePostgres(o));
        return a;
    }

    /**
     * Multi-etapa: Maven compila en una imagen y el jar corre en otra que solo
     * trae el JRE. La imagen final no tiene Maven, ni el codigo fuente, ni
     * corre como root.
     */
    private static String dockerfile() {
        return """
                # --- Etapa 1: compilar ---
                FROM maven:3.9-eclipse-temurin-21 AS construccion
                WORKDIR /fuente
                # Primero el pom solo: mientras no cambie, las dependencias quedan en cache.
                COPY pom.xml .
                RUN mvn -q -B dependency:go-offline
                COPY src ./src
                RUN mvn -q -B package -DskipTests

                # --- Etapa 2: correr ---
                FROM eclipse-temurin:21-jre
                WORKDIR /app
                RUN useradd --system --create-home --shell /usr/sbin/nologin app
                COPY --from=construccion /fuente/target/*.jar app.jar
                USER app
                ENV SPRING_PROFILES_ACTIVE=prod \\
                    PORT=8080
                EXPOSE 8080
                ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
                """;
    }

    private static String composePostgres(Opciones o) {
        String base = Nombres.identificador(o.nombreProyecto());
        return """
                # La aplicacion y su base, listas con un solo comando:
                #   docker compose up --build
                # Las claves se toman del entorno o de un archivo .env al lado de este.
                services:
                  db:
                    image: postgres:16-alpine
                    environment:
                      POSTGRES_DB: {{base}}
                      POSTGRES_USER: ${DB_USER:-{{base}}}
                      POSTGRES_PASSWORD: ${DB_PASSWORD:-cambiar-esta-clave}
                    volumes:
                      - datos:/var/lib/postgresql/data
                    healthcheck:
                      test: ["CMD-SHELL", "pg_isready -U $${POSTGRES_USER} -d {{base}}"]
                      interval: 5s
                      timeout: 5s
                      retries: 10

                  app:
                    build: .
                    depends_on:
                      db:
                        condition: service_healthy
                    environment:
                      DB_URL: jdbc:postgresql://db:5432/{{base}}
                      DB_USER: ${DB_USER:-{{base}}}
                      DB_PASSWORD: ${DB_PASSWORD:-cambiar-esta-clave}
                      PORT: 8080
                      CORS_ORIGINS: ${CORS_ORIGINS:-}
                    ports:
                      - "8080:8080"

                volumes:
                  datos:
                """.replace("{{base}}", base);
    }

    private static String composeMongo(Opciones o) {
        String base = Nombres.identificador(o.nombreProyecto());
        return """
                # La aplicacion y su base, listas con un solo comando:
                #   docker compose up --build
                # Las claves se toman del entorno o de un archivo .env al lado de este.
                services:
                  db:
                    image: mongo:7
                    environment:
                      MONGO_INITDB_ROOT_USERNAME: ${DB_USER:-{{base}}}
                      MONGO_INITDB_ROOT_PASSWORD: ${DB_PASSWORD:-cambiar-esta-clave}
                    volumes:
                      - datos:/data/db
                    healthcheck:
                      test: ["CMD", "mongosh", "--quiet", "--eval", "db.adminCommand('ping')"]
                      interval: 5s
                      timeout: 5s
                      retries: 10

                  app:
                    build: .
                    depends_on:
                      db:
                        condition: service_healthy
                    environment:
                      DB_URL: mongodb://db:27017/{{base}}
                      DB_USER: ${DB_USER:-{{base}}}
                      DB_PASSWORD: ${DB_PASSWORD:-cambiar-esta-clave}
                      PORT: 8080
                      CORS_ORIGINS: ${CORS_ORIGINS:-}
                    ports:
                      - "8080:8080"

                volumes:
                  datos:
                """.replace("{{base}}", base);
    }

    // ------------------------------------------------------------- README

    static String readme(Opciones o, int entidades, List<String> advertencias, Meta meta) {
        boolean mongo = o.almacen() == Almacen.MONGO;
        String lista = advertencias.isEmpty()
                ? "Ninguna: el modelo se mapeo completo.\n"
                : advertencias.stream().map(a -> "- " + a + "\n").reduce("", String::concat);
        return """
                # {{proyecto}}

                Generado por ColabModel desde el modelo conceptual. **No edites a mano**:
                si volves a generar, se pisa.

                Base de datos: **{{base}}**. Entidades generadas: {{entidades}}. Cada una trae 5 capas
                (Entity, Repository, Service, DTO y Controller){{extra}}.

                ## Como levantarlo

                {{desarrollo}}

                ### En produccion

                Con el perfil `prod` todo lo sensible sale de variables de entorno:

                | Variable | Que es |
                |---|---|
                | `DB_URL` | {{dbUrl}} |
                | `DB_USER` | usuario de la base |
                | `DB_PASSWORD` | clave de la base |
                | `PORT` | puerto HTTP (por defecto 8081) |
                | `CORS_ORIGINS` | origenes permitidos, separados por coma (vacio = ninguno) |

                ```bash
                mvn package
                SPRING_PROFILES_ACTIVE=prod DB_URL=... DB_USER=... DB_PASSWORD=... java -jar target/*.jar
                ```

                ### Con Docker

                ```bash
                docker compose up --build
                ```

                Levanta la aplicacion y su base. El `Dockerfile` es multi-etapa (compila con
                Maven, corre con solo el JRE y sin root). Cambia la clave por defecto con un
                archivo `.env` al lado de `docker-compose.yml` (`DB_PASSWORD=...`).

                Salud: `GET /actuator/health`. Informacion: `GET /actuator/info`. El apagado es
                ordenado: las peticiones en curso terminan antes de cerrar.

                ## La API

                Por cada entidad, en `/api/{recurso}`:

                | Metodo | Ruta | Respuesta |
                |---|---|---|
                | GET | `/api/{recurso}` | `200` arreglo. Acepta `page`, `size`, `sort=campo,asc` y un filtro por cada FK (`?clienteId=...`). **Sin `page` devuelve todo.** Siempre trae la cabecera `X-Total-Count` |
                | GET | `/api/{recurso}/{id}` | `200` / `404` |
                | POST | `/api/{recurso}` | `201` + cabecera `Location` |
                | PUT | `/api/{recurso}/{id}` | `200` reemplazo completo / `404` |
                | PATCH | `/api/{recurso}/{id}` | `200` cambio parcial: solo los campos enviados / `404` |
                | DELETE | `/api/{recurso}/{id}` | `204` / `404` / `409` si otro registro lo referencia |

                Todos los errores tienen la misma forma:

                ```json
                {"error": "mensaje legible", "errores": {"campo": "que fallo"}}
                ```

                con `400` (datos invalidos), `404` (no existe), `409` (choca con otro dato) y `500`
                (sin detalles internos: esos quedan en el log).

                Cada registro trae `{{version}}`, `{{creado}}` y `{{actualizado}}`. Si un `PUT` o un `PATCH`
                manda la `{{version}}` que leyo y ya no es la actual, la respuesta es `409`: otro
                usuario lo cambio en el medio y no se le pisa el cambio.

                ## Como probarlo

                ```bash
                mvn test
                ```

                Corre una prueba MockMvc por controlador (leer, 404, validacion, crear con
                `201`, listado con total). No necesitan base: el Service esta simulado.

                Apenas arranca, sin escribir nada:

                | Donde | Que es |
                |---|---|
                | `/` | Consola de prueba: un ABM por entidad, servido por este mismo backend |
                | `/swagger-ui.html` | La API completa, para probarla a mano (apagada en `prod`) |
                | `/app-manifest.json` | El contrato que lee la app movil (ADR-011) |

                ### Desde el telefono

                La app movil lee `/app-manifest.json` y aprende el dominio sola, asi que
                el mismo APK sirve para cualquier modelo. Usa la **IP de esta maquina en la
                red**, no `localhost`, y Android 9 o posterior necesita `usesCleartextTraffic`
                para HTTP sin TLS. `baseUrl` viene vacio a proposito: significa "resolve las
                rutas contra el host del que me bajaste".

                ## Decisiones tomadas y limites de esta version

                {{advertencias}}
                {{limites}}
                """
                .replace("{{desarrollo}}", mongo ? """
                        ```bash
                        mvn spring-boot:run
                        ```

                        Por defecto (perfil `dev`) se conecta a un MongoDB en `mongodb://localhost:27017`.
                        Si no tenes uno, `docker compose up db` levanta solo la base. Las colecciones se
                        crean solas al primer uso, con sus indices.

                        Como queda cada coleccion (que va incrustado y que va por referencia) esta en
                        `docs/modelo-documental.json`, el mismo contenido que la Vista Documental.
                        """ : """
                        ```bash
                        mvn spring-boot:run
                        ```

                        **No hace falta instalar nada mas.** Por defecto (perfil `dev`) usa H2 en
                        memoria, con el esquema creado por Flyway y unas filas de ejemplo. La base se
                        borra al apagar, que es justo lo que se quiere para probar.

                        Para el PostgreSQL local de desarrollo (mismo DDL, sin datos de ejemplo), el
                        del `docker compose` de ColabModel en el puerto 55432. La base se crea una vez:

                        ```bash
                        docker compose exec db psql -U colabmodel -d colabmodel -c "CREATE DATABASE {{db}};"
                        mvn spring-boot:run -Dspring-boot.run.profiles=postgres
                        ```

                        El primer comando va en la carpeta de ColabModel, donde esta su `docker-compose.yml`.

                        El esquema lo crea Flyway: `V1__init.sql` es el que se vio y confirmo en la
                        Vista Fisica y `V2__auditoria.sql` agrega version y auditoria. Hibernate solo
                        VALIDA: si el mapeo y el DDL no coinciden, la aplicacion no arranca, y eso es el
                        sistema funcionando.
                        """)
                .replace("{{limites}}", mongo ? """
                        En MongoDB no hay claves foraneas: la integridad la cuida el Service. Crear o
                        cambiar un registro que apunta a un id inexistente da `409`, y borrar uno que
                        otro referencia tambien. La composicion guarda la parte **dentro** del todo
                        (borrar el todo se lleva sus partes); la herencia va en una sola coleccion con
                        el campo `_tipo`.
                        """ : """
                        Las asociaciones se emiten completas (Regla 7): `@ManyToOne` con `LAZY`
                        explicito, el lado inverso con `mappedBy`, el cascade que corresponde al tipo de
                        relacion y `@EntityGraph` en los listados para no caer en N+1.

                        Todavia NO se emite `@ManyToMany` para las N:M: su tabla puente no genera Entity.
                        """)
                .replace("{{extra}}", mongo ? " sobre Spring Data MongoDB" : " y su DDL de Flyway")
                .replace("{{dbUrl}}", mongo ? "URI de MongoDB, por ejemplo `mongodb://host:27017/base`"
                        : "URL JDBC, por ejemplo `jdbc:postgresql://host:5432/base`")
                .replace("{{base}}", mongo ? "MongoDB" : "PostgreSQL")
                .replace("{{version}}", meta.version())
                .replace("{{creado}}", meta.creado())
                .replace("{{actualizado}}", meta.actualizado())
                .replace("{{proyecto}}", o.nombreProyecto())
                .replace("{{db}}", Nombres.identificador(o.nombreProyecto()))
                .replace("{{entidades}}", String.valueOf(entidades))
                .replace("{{advertencias}}", lista);
    }

    static String mayuscula(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
