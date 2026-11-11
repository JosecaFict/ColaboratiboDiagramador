package com.uagrm.colabmodel.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uagrm.colabmodel.iam.AppUser;
import com.uagrm.colabmodel.iam.AppUserRepository;
import com.uagrm.colabmodel.project.DiagramRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Arma el {@link ContextoDeEquipo} de un pedido (T-09).
 *
 * Lee solo los ultimos turnos del diagrama, con el limite en la consulta (no
 * carga todo el historial para recortarlo despues), y los devuelve en orden
 * cronologico estable. Es una instantanea de lo ya guardado al empezar el
 * pedido: una respuesta que otro todavia esta esperando no aparece.
 */
@Service
public class MemoriaDeConversacion {

    static final String USUARIO_ELIMINADO = "Usuario eliminado";
    static final int MAX_PEDIDO = 500;
    static final int MAX_RESPUESTA = 600;
    static final int MAX_PROPUESTA = 300;
    /** El resumen de la lectura de la ultima imagen: nombres de clases y relaciones, y cuantas dudas. */
    static final int MAX_LECTURA_DE_IMAGEN = 800;

    private final InteraccionIARepository interacciones;
    private final AppUserRepository usuarios;
    private final DiagramRepository diagramas;
    private final ConfiguracionesDeIA configuraciones;
    private final ObjectMapper mapper;
    private final int maxTurnos;

    MemoriaDeConversacion(InteraccionIARepository interacciones, AppUserRepository usuarios,
                          DiagramRepository diagramas, ConfiguracionesDeIA configuraciones, ObjectMapper mapper,
                          @Value("${colabmodel.ai.memoria.max-turnos:8}") int maxTurnos) {
        this.interacciones = interacciones;
        this.usuarios = usuarios;
        this.diagramas = diagramas;
        this.configuraciones = configuraciones;
        this.mapper = mapper;
        this.maxTurnos = Math.max(0, maxTurnos);
    }

    @Transactional(readOnly = true)
    public ContextoDeEquipo para(UUID diagramId, ContextoDeEquipo.Interlocutor quien) {
        UUID projectId = diagramas.findById(diagramId)
                .orElseThrow(() -> new IllegalArgumentException("El diagrama no existe"))
                .getProjectId();
        ConfiguracionDeIA configuracion = configuraciones.de(projectId);
        if (maxTurnos == 0) return new ContextoDeEquipo(quien, configuracion, List.of(), 0);

        // Uno mas que el tope: alcanza para saber si hay turnos anteriores sin contarlos todos.
        List<InteraccionIA> recientes = new ArrayList<>(interacciones.findByDiagramIdOrderByCreatedAtDescIdDesc(
                diagramId, PageRequest.of(0, maxTurnos + 1)));
        boolean hayMas = recientes.size() > maxTurnos;
        if (hayMas) recientes = recientes.subList(0, maxTurnos);
        Collections.reverse(recientes);

        Map<UUID, String> nombres = usuarios.findAllById(recientes.stream().map(InteraccionIA::getUserId)
                        .filter(Objects::nonNull).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(AppUser::getId, AppUser::getFullName));
        // Solo la ULTIMA lectura de imagen de la ventana lleva su resumen: las anteriores ya fueron superadas.
        InteraccionIA ultimaImagen = recientes.stream().filter(f -> f.getMode() == ModoIA.IMAGEN)
                .reduce((a, b) -> b).orElse(null);
        List<ContextoDeEquipo.Turno> turnos = recientes.stream().map(f -> new ContextoDeEquipo.Turno(
                f.getUserId() == null ? USUARIO_ELIMINADO : nombres.getOrDefault(f.getUserId(), USUARIO_ELIMINADO),
                quien.userId().equals(f.getUserId()),
                f.getMode(),
                recortar(f.getPrompt(), MAX_PEDIDO),
                recortar(f.getResponse(), MAX_RESPUESTA),
                f == ultimaImagen ? recortar(resumenDeLaLectura(f), MAX_LECTURA_DE_IMAGEN)
                        : recortar(resumenDePropuesta(f), MAX_PROPUESTA))).toList();
        return new ContextoDeEquipo(quien, configuracion, turnos, hayMas ? 1 : 0);
    }

    /**
     * Lo que propuso un turno, sin el JSON de los comandos. La marca de
     * aplicada dice que en su momento se aplico, no que siga en el diagrama.
     */
    private String resumenDePropuesta(InteraccionIA f) {
        PropuestaDeIA p;
        try {
            p = mapper.readValue(f.getProposal(), PropuestaDeIA.class);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            return "";
        }
        if (p.aceptados() == null || p.aceptados().isEmpty()) return "";
        List<String> resumenes = p.aceptados().stream().map(PropuestaDeIA.ComandoAceptado::resumen)
                .filter(Objects::nonNull).limit(5).toList();
        int total = p.aceptados().size();
        String estado = f.getAppliedAt() != null ? "se aplico en su momento" : "pendiente, no se aplico";
        return total + (total == 1 ? " cambio (" : " cambios (") + estado + "): " + String.join("; ", resumenes)
                + (total > resumenes.size() ? "; ..." : "");
    }

    /**
     * Lo que leyo la IA de una imagen: nombres de las clases, relaciones con su tipo y cuantas dudas. Sale de
     * `lecturaDeImagen`; el llamador lo recorta a una linea sin comillas dobles.
     */
    private String resumenDeLaLectura(InteraccionIA f) {
        PropuestaDeIA p;
        try {
            p = mapper.readValue(f.getProposal(), PropuestaDeIA.class);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            return "";
        }
        PropuestaDeIA.LecturaDeImagen lectura = p.lecturaDeImagen();
        if (lectura == null || lectura.deteccion() == null) return "";
        var d = lectura.deteccion();
        Map<String, String> nombreDe = new java.util.HashMap<>();
        d.clases().forEach(c -> nombreDe.put(c.ref(), c.nombre() == null || c.nombre().isBlank()
                ? "(sin nombre, " + c.ref() + ")" : c.nombre()));
        String clases = d.clases().isEmpty() ? "ninguna"
                : d.clases().stream().map(c -> nombreDe.get(c.ref())).collect(Collectors.joining(", "));
        String relaciones = d.relaciones().isEmpty() ? "ninguna" : d.relaciones().stream()
                .map(r -> nombreDe.getOrDefault(r.origenRef(), r.origenRef()) + " -> "
                        + nombreDe.getOrDefault(r.destinoRef(), r.destinoRef()) + " (" + r.kind() + ")")
                .collect(Collectors.joining("; "));
        int dudas = (lectura.dudas() == null ? 0 : lectura.dudas().size()) + lectura.dudasOmitidas();
        String estado = f.getAppliedAt() != null ? "se aplico en su momento" : "pendiente, no se aplico";
        return "lectura de la imagen (" + estado + "). Clases: " + clases + ". Relaciones: " + relaciones
                + ". Dudas: " + dudas;
    }

    /**
     * Un turno anterior va en una sola linea y sin comillas dobles: asi no puede
     * imitar las marcas del mensaje ("QUIEN PREGUNTA AHORA:", "PEDIDO ACTUAL:")
     * ni cerrar la cita en la que va.
     */
    static String recortar(String texto, int max) {
        if (texto == null) return "";
        String limpio = texto.replaceAll("\\s+", " ").strip().replace('"', '\'');
        return limpio.length() <= max ? limpio : limpio.substring(0, max - 1) + "…";
    }
}
