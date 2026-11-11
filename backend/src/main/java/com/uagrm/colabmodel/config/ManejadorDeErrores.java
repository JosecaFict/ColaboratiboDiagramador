package com.uagrm.colabmodel.config;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.TransactionException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * Toda respuesta de error de la API tiene la forma `{"mensaje": ...}` y el
 * texto va en espanol.
 *
 * Importa mas de lo que parece: un `ResponseStatusException` que sale sin
 * atrapar hace que el contenedor reenvie el pedido a `/error`, que la seguridad
 * no abre, y el codigo real (404, 409, 400) llega convertido en un 403 o un 401
 * engañoso. Atrapandolo aca la respuesta se arma dentro del controlador y nunca
 * pasa por `/error`.
 *
 * Los controladores que ya tienen su propio `@ExceptionHandler` (IA,
 * importacion) siguen mandando en lo suyo: el local gana sobre este.
 */
@RestControllerAdvice
public class ManejadorDeErrores {

    private static final Logger log = LoggerFactory.getLogger(ManejadorDeErrores.class);

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> conEstado(ResponseStatusException e) {
        HttpStatusCode estado = e.getStatusCode();
        String texto = e.getReason();
        if (texto == null || texto.isBlank()) {
            texto = HttpStatus.resolve(estado.value()) != null
                    ? HttpStatus.resolve(estado.value()).getReasonPhrase() : "Error";
        }
        return ResponseEntity.status(estado).body(Map.of("mensaje", texto));
    }

    /** Lo que las anotaciones de los DTO rechazan; sus mensajes ya estan en espanol. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> invalido(MethodArgumentNotValidException e) {
        String texto = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .distinct()
                .collect(Collectors.joining(". "));
        if (texto.isBlank()) texto = "Los datos enviados no son válidos";
        return ResponseEntity.badRequest().body(Map.of("mensaje", texto));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> cuerpoIlegible(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest().body(Map.of("mensaje", "El cuerpo de la solicitud no es válido"));
    }

    /**
     * La base rechazo algo que la aplicacion no anticipo (una restriccion, una
     * transaccion que no pudo confirmar). Es un 500, con el motivo en el registro
     * y un texto que no filtra detalles internos al cliente.
     */
    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    public ResponseEntity<Map<String, String>> falloDeBase(Exception e) {
        log.error("Error de base de datos al atender un pedido", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("mensaje", "El servidor no pudo completar la operación. Probá de nuevo"));
    }

    /**
     * Un archivo mas grande que `spring.servlet.multipart.max-file-size`. Sin
     * esto, Spring lo corta antes del controlador y llega un error generico.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, String>> archivoGrande(MaxUploadSizeExceededException e,
                                                             HttpServletRequest pedido) {
        // Las imagenes del chat tienen su propio limite (8 MB): decirles "exporta solo el paquete" confunde.
        if (pedido != null && pedido.getRequestURI() != null && pedido.getRequestURI().contains("/ai/imagenes")) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Map.of("mensaje",
                    "La imagen es demasiado grande: el máximo es de 8 MB"));
        }
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Map.of("mensaje",
                "El archivo es demasiado grande: el máximo es de 25 MB. Si es un XMI, exportá solo el paquete que"
                        + " querés importar"));
    }

    /** Un id que no es un UUID en la ruta. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, String>> parametroMalo(MethodArgumentTypeMismatchException e) {
        return ResponseEntity.badRequest().body(Map.of("mensaje", "El identificador «" + e.getName() + "» no es válido"));
    }
}
