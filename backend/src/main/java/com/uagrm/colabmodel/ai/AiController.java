package com.uagrm.colabmodel.ai;

import com.uagrm.colabmodel.ai.imagenes.ProcesadorDeImagenDelChat;
import com.uagrm.colabmodel.modelcore.command.Origin;
import com.uagrm.colabmodel.project.AccesoAProyecto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.util.Map;
import java.util.UUID;

/**
 * Pedirle a la IA un cambio en lenguaje natural: el primer paso, que solo
 * PROPONE (misma forma que ImportacionController, ADR-016).
 *
 * No hay un endpoint que aplique. Confirmar es mandar
 * {@link ServicioDeIA#comandoBatchDe} por el WebSocket como un BATCH mas, con
 * los mismos permisos, locks y secuencia que cualquier otro cambio: un
 * segundo camino de escritura para "lo que confirmo la IA" rompería ADR-002.
 */
@RestController
@RequestMapping("/api/v1/diagrams/{diagramId}/ai")
public class AiController {

    private final ServicioDeIA servicio;
    private final AccesoAProyecto acceso;
    private final String proveedorActivo;
    private final String modeloOllama;
    private final int contextoOllama;
    private final CatalogoModelosOllama modelosOllama;
    private final HistorialIA historial;
    private final ServicioDeRecomendaciones recomendaciones;
    private final AvisosDeIA avisos;
    private final ProcesadorDeImagenDelChat imagenes;

    /** Sin el procesador de imagenes (pruebas anteriores al modo IMAGEN): un turno IMAGEN responde 503. */
    public AiController(ServicioDeIA servicio, AccesoAProyecto acceso, String proveedorActivo, String modeloOllama,
                        int contextoOllama, CatalogoModelosOllama modelosOllama, HistorialIA historial,
                        AvisosDeIA avisos, ServicioDeRecomendaciones recomendaciones) {
        this(servicio, acceso, proveedorActivo, modeloOllama, contextoOllama, modelosOllama, historial, avisos,
                recomendaciones, null);
    }

    @Autowired
    public AiController(ServicioDeIA servicio, AccesoAProyecto acceso,
                        @Value("${colabmodel.ai.proveedor:ollama}") String proveedorActivo,
                        @Value("${colabmodel.ai.ollama.model:qwen3:8b}") String modeloOllama,
                        @Value("${colabmodel.ai.ollama.contexto:4096}") int contextoOllama,
                        CatalogoModelosOllama modelosOllama, HistorialIA historial, AvisosDeIA avisos,
                        ServicioDeRecomendaciones recomendaciones, ProcesadorDeImagenDelChat imagenes) {
        this.imagenes = imagenes;
        this.recomendaciones = recomendaciones;
        this.servicio = servicio;
        this.acceso = acceso;
        this.proveedorActivo = proveedorActivo;
        this.modeloOllama = modeloOllama;
        this.contextoOllama = contextoOllama;
        this.modelosOllama = modelosOllama;
        this.historial = historial;
        this.avisos = avisos;
    }

    /**
     * `imagenId`, `giroGrados` y `recorte` solo valen con `modo = IMAGEN` (docs/DISENO-CHAT-IMAGENES.md, 5.2).
     * El giro es en sentido horario; el recorte, en pixeles de la imagen GUARDADA y se aplica antes que el giro.
     */
    public record SolicitudDePropuesta(String prompt, Origin origin, ModoIA modo, String modelo,
                                       UUID imagenId, Integer giroGrados, Recorte recorte) {
        public record Recorte(int x, int y, int ancho, int alto) { }

        /** Un pedido de texto: las tres opciones de imagen, sin usar. */
        public SolicitudDePropuesta(String prompt, Origin origin, ModoIA modo, String modelo) {
            this(prompt, origin, modo, modelo, null, null, null);
        }

        /**
         * Las reglas de la tabla del 5.2 que no necesitan la imagen. Lo que depende de sus medidas (el recorte)
         * lo comprueba el procesador contra la imagen guardada.
         *
         * @throws IllegalArgumentException (400) con el mensaje de la tabla
         */
        void validar(ModoIA modo) {
            if (modo != ModoIA.IMAGEN) {
                if (imagenId != null) throw new IllegalArgumentException("La imagen solo se usa en el modo IMAGEN");
                if (giroGrados != null || recorte != null) {
                    throw new IllegalArgumentException("El giro y el recorte solo valen al leer una imagen");
                }
                return;
            }
            if (imagenId == null) throw new IllegalArgumentException("Falta la imagen que se quiere leer");
            if (giroGrados != null) {
                int g = giroGrados;
                if (g < -180 || g > 180 || !(Math.abs(g) <= 10 || g % 90 == 0)) {
                    throw new IllegalArgumentException("El giro debe ser de hasta 10° o un múltiplo de 90°");
                }
            }
        }
    }
    /** El opId del BATCH que aplico la propuesta: el servidor lo busca en el log antes de marcarla. */
    public record MarcaDeAplicada(UUID opId) { }
    public record EstadoIA(String proveedor, String modelo, boolean local, int contexto,
                           java.util.List<CatalogoModelosOllama.Opcion> modelos) { }

    @GetMapping("/estado")
    public EstadoIA estado(@PathVariable UUID diagramId, Principal principal) {
        UUID userId = UUID.fromString(principal.getName());
        acceso.rolSobreDiagrama(diagramId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "No sos miembro de este proyecto"));
        boolean local = "ollama".equalsIgnoreCase(proveedorActivo);
        return new EstadoIA(proveedorActivo, local ? modeloOllama : proveedorActivo, local, contextoOllama,
                local ? modelosOllama.opciones() : java.util.List.of());
    }

    @PostMapping("/propuestas")
    public PropuestaDeIA proponer(@PathVariable UUID diagramId, @RequestBody SolicitudDePropuesta cuerpo,
                                  Principal principal) {
        // Igual que en la importacion: proponerle un cambio a un VIEWER que el
        // bus va a rechazar despues es hacerle perder el tiempo revisandolo.
        UUID userId = UUID.fromString(principal.getName());
        AccesoAProyecto.Rol rol = acceso.rolSobreDiagrama(diagramId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "No sos miembro de este proyecto"));
        ModoIA modo = cuerpo.modo() == null ? ModoIA.MODIFICAR : cuerpo.modo();
        boolean pideCambios = modo == ModoIA.DISENAR || modo == ModoIA.MODIFICAR || modo == ModoIA.IMAGEN;
        if (pideCambios && !acceso.puedeEditar(rol)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Los " + rol + " no pueden pedirle cambios a la IA");
        }
        cuerpo.validar(modo);
        if (modo == ModoIA.IMAGEN) return proponerDesdeImagen(diagramId, userId, cuerpo);
        Origin origin = cuerpo.origin() == null ? Origin.TEXT : cuerpo.origin();
        String modelo = "ollama".equalsIgnoreCase(proveedorActivo) ? modelosOllama.resolver(cuerpo.modelo()) : null;
        // T-09: quien pregunta sale de la sesion y de la pertenencia al proyecto, no del cuerpo.
        ContextoDeEquipo.Interlocutor quien = new ContextoDeEquipo.Interlocutor(userId,
                historial.nombreDe(userId), rol.etiqueta());
        PropuestaDeIA propuesta = servicio.generar(diagramId, cuerpo.prompt(), origin, modo, modelo, quien);
        HistorialIA.Entrada entrada = historial.registrar(diagramId, userId, modo, cuerpo.prompt(), propuesta);
        // Despues del commit de registrar: quien recibe el aviso y pide el
        // historial ya encuentra la fila.
        avisos.nueva(diagramId, entrada);
        return entrada.propuesta();
    }

    /**
     * El turno IMAGEN: NO pasa por `ServicioDeIA.generar` ni por el proveedor de texto. El `origin` del cuerpo se
     * ignora: el turno sale siempre con `origin = IMAGE`.
     */
    private PropuestaDeIA proponerDesdeImagen(UUID diagramId, UUID userId, SolicitudDePropuesta cuerpo) {
        if (imagenes == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "La lectura de imágenes todavía no está disponible");
        }
        ProcesadorDeImagenDelChat.Lectura lectura = imagenes.procesar(diagramId, cuerpo.imagenId(),
                cuerpo.giroGrados(), cuerpo.recorte());
        // La columna `prompt` es NOT NULL: sin texto del usuario, el turno dice quien subio la imagen.
        String prompt = cuerpo.prompt() == null || cuerpo.prompt().isBlank()
                ? "Leer la imagen subida por " + lectura.imagen().subidaPorNombre() : cuerpo.prompt();
        HistorialIA.Entrada entrada = historial.registrar(diagramId, userId, ModoIA.IMAGEN, prompt,
                lectura.propuesta(), cuerpo.imagenId());
        avisos.nueva(diagramId, entrada);
        return entrada.propuesta();
    }

    public record SolicitudDeRecomendaciones(String requisito, String modelo) { }
    public record SolicitudDeAplicacion(java.util.List<RecomendacionesIA.Recomendacion> recomendaciones,
                                        Origin origin, String modelo) { }

    /** Solo lectura, como REVISAR: cualquier miembro puede pedir consejo. */
    @PostMapping("/recomendaciones")
    public RecomendacionesIA recomendar(@PathVariable UUID diagramId,
                                        @RequestBody(required = false) SolicitudDeRecomendaciones cuerpo,
                                        Principal principal) {
        UUID userId = UUID.fromString(principal.getName());
        acceso.rolSobreDiagrama(diagramId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "No sos miembro de este proyecto"));
        String requisito = cuerpo == null ? null : cuerpo.requisito();
        return recomendaciones.recomendar(diagramId, requisito, modeloSolicitado(cuerpo == null ? null : cuerpo.modelo()));
    }

    /**
     * Convierte las recomendaciones elegidas en una propuesta MODIFICAR. Sigue
     * sin aplicar nada: el usuario la revisa y la confirma como cualquier otra.
     */
    @PostMapping("/recomendaciones/propuesta")
    public PropuestaDeIA proponerDesdeRecomendaciones(@PathVariable UUID diagramId,
                                                      @RequestBody SolicitudDeAplicacion cuerpo, Principal principal) {
        UUID userId = UUID.fromString(principal.getName());
        AccesoAProyecto.Rol rol = acceso.rolSobreDiagrama(diagramId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "No sos miembro de este proyecto"));
        if (!acceso.puedeEditar(rol)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Los " + rol + " no pueden pedirle cambios a la IA");
        }
        Origin origin = cuerpo.origin() == null ? Origin.TEXT : cuerpo.origin();
        PropuestaDeIA propuesta = recomendaciones.proponerAplicacion(diagramId, cuerpo.recomendaciones(), origin,
                modeloSolicitado(cuerpo.modelo()));
        String pedido = ServicioDeRecomendaciones.pedidoDeAplicacion(cuerpo.recomendaciones());
        HistorialIA.Entrada entrada = historial.registrar(diagramId, userId, ModoIA.MODIFICAR, pedido, propuesta);
        // Igual que una propuesta comun: los demas miembros ven el turno en vivo (T-09).
        avisos.nueva(diagramId, entrada);
        return entrada.propuesta();
    }

    private String modeloSolicitado(String modelo) {
        return "ollama".equalsIgnoreCase(proveedorActivo) ? modelosOllama.resolver(modelo) : null;
    }

    @GetMapping("/historial")
    public java.util.List<HistorialIA.Entrada> historial(@PathVariable UUID diagramId, Principal principal) {
        UUID userId = UUID.fromString(principal.getName());
        acceso.rolSobreDiagrama(diagramId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "No sos miembro de este proyecto"));
        return historial.listar(diagramId);
    }

    /**
     * "Aplicada" quiere decir que el bus acepto el BATCH (T-04). El cliente lo
     * pide al recibir la operacion confirmada, y el servidor no le cree: busca
     * ese opId en el log. Un lote rechazado no esta en el log y devuelve 409.
     */
    @PatchMapping("/historial/{interactionId}/aplicada")
    public HistorialIA.Entrada marcarAplicada(@PathVariable UUID diagramId, @PathVariable UUID interactionId,
                                              @RequestBody(required = false) MarcaDeAplicada cuerpo,
                                              Principal principal) {
        UUID userId = UUID.fromString(principal.getName());
        AccesoAProyecto.Rol rol = acceso.rolSobreDiagrama(diagramId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "No sos miembro de este proyecto"));
        if (!acceso.puedeEditar(rol)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Tu rol no puede aplicar propuestas de IA");
        }
        HistorialIA.Entrada entrada = historial.marcarAplicada(diagramId, interactionId, userId,
                cuerpo == null ? null : cuerpo.opId());
        avisos.aplicada(diagramId, entrada);
        return entrada;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> solicitudInvalida(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("mensaje", e.getMessage()));
    }

    /** T-09: el pedido y el diagrama no entran en el contexto del modelo. Se avisa antes de llamarlo. */
    @ExceptionHandler(ContextoExcedidoException.class)
    ResponseEntity<Map<String, String>> contextoExcedido(ContextoExcedidoException e) {
        return ResponseEntity.unprocessableEntity().body(Map.of("mensaje", e.getMessage()));
    }

    @ExceptionHandler(LoteNoConfirmadoException.class)
    ResponseEntity<Map<String, String>> loteNoConfirmado(LoteNoConfirmadoException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("mensaje", e.getMessage()));
    }

    @ExceptionHandler(ProveedorIAException.class)
    ResponseEntity<Map<String, String>> proveedorFallo(ProveedorIAException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("mensaje", e.getMessage()));
    }
}
