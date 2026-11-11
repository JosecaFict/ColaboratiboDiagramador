package com.uagrm.colabmodel.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uagrm.colabmodel.ai.imagenes.ImagenMeta;
import com.uagrm.colabmodel.ai.imagenes.ImagenesDelChat;
import com.uagrm.colabmodel.iam.AppUser;
import com.uagrm.colabmodel.iam.AppUserRepository;
import com.uagrm.colabmodel.modelcore.command.CommandType;
import com.uagrm.colabmodel.modelcore.command.Origin;
import com.uagrm.colabmodel.project.DiagramOperation;
import com.uagrm.colabmodel.project.DiagramOperationRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class HistorialIA {

    /**
     * Clave del payload del BATCH que dice qué turno del historial aplica.
     * El bus no la lee (solo usa "descripcion" y "comandos"); queda en el log
     * y es lo que permite comprobar que la propuesta se aplicó de verdad.
     */
    public static final String CLAVE_INTERACCION = "interaccionIA";

    /**
     * @param imagen la imagen del chat sobre la que trabajo el turno, o null (entonces no sale en el JSON). Si
     *               fue eliminada llega con `eliminada = true` y sin bytes; nunca lleva bytes.
     */
    public record Entrada(UUID id, UUID userId, String userName, ModoIA modo, String modelo,
                          String prompt, String respuesta, PropuestaDeIA propuesta,
                          boolean aplicada, OffsetDateTime creadaEn, ImagenMeta imagen) {
        /** Un turno sin imagen: todos los de antes de esta funcion. */
        public Entrada(UUID id, UUID userId, String userName, ModoIA modo, String modelo,
                       String prompt, String respuesta, PropuestaDeIA propuesta,
                       boolean aplicada, OffsetDateTime creadaEn) {
            this(id, userId, userName, modo, modelo, prompt, respuesta, propuesta, aplicada, creadaEn, null);
        }
    }

    private final InteraccionIARepository interactions;
    private final AppUserRepository users;
    private final DiagramOperationRepository operations;
    private final ObjectMapper mapper;
    private final ImagenesDelChat imagenes;

    @Autowired
    HistorialIA(InteraccionIARepository interactions, AppUserRepository users,
                DiagramOperationRepository operations, ObjectMapper mapper, ImagenesDelChat imagenes) {
        this.interactions = interactions;
        this.users = users;
        this.operations = operations;
        this.mapper = mapper;
        this.imagenes = imagenes;
    }

    /** Sin imagenes: los turnos salen sin `imagen`. Lo usan las pruebas anteriores a la funcion. */
    HistorialIA(InteraccionIARepository interactions, AppUserRepository users,
                DiagramOperationRepository operations, ObjectMapper mapper) {
        this(interactions, users, operations, mapper, null);
    }

    @Transactional
    Entrada registrar(UUID diagramId, UUID userId, ModoIA modo, String prompt,
                      PropuestaDeIA propuesta) {
        InteraccionIA fila = new InteraccionIA();
        fila.setDiagramId(diagramId);
        fila.setUserId(userId);
        fila.setMode(modo);
        fila.setModel(propuesta.modelo());
        fila.setPrompt(prompt.trim());
        fila.setResponse(propuesta.notaDelModelo());
        // Primero se obtiene el id; despues se guarda dentro de la propuesta
        // para que el cliente pueda marcar exactamente ese turno como aplicado.
        fila.setProposal("{}");
        fila = interactions.save(fila);
        PropuestaDeIA identificada = propuesta.conInteraccionId(fila.getId());
        fila.setProposal(escribir(identificada));
        fila = interactions.save(fila);
        return entradaDe(fila, nombreDe(userId));
    }

    @Transactional(readOnly = true)
    List<Entrada> listar(UUID diagramId) {
        List<InteraccionIA> filas = interactions.findByDiagramIdOrderByCreatedAtAscIdAsc(diagramId);
        Map<UUID, String> nombres = users.findAllById(filas.stream().map(InteraccionIA::getUserId)
                        .filter(Objects::nonNull).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(AppUser::getId, AppUser::getFullName));
        // Las imagenes de TODOS los turnos en una sola consulta (sin N+1) y solo metadatos.
        Map<UUID, ImagenMeta> metas = imagenes == null ? Map.of() : imagenes.metas(filas.stream()
                .map(InteraccionIA::getImageId).filter(Objects::nonNull).collect(Collectors.toSet()));
        return filas.stream().map(f -> entradaDe(f, nombres.getOrDefault(f.getUserId(), "Usuario eliminado"),
                        f.getImageId() == null ? null : metas.get(f.getImageId())))
                .toList();
    }

    /**
     * Marca el turno como aplicado SOLO si el bus ya aceptó el BATCH que lo
     * aplica. La prueba es el log: una operación con ese opId, en este
     * diagrama, que sea un BATCH de texto o voz del mismo usuario y que diga
     * qué turno aplica. Un lote rechazado no deja fila en el log, así que no
     * hay forma de marcarlo.
     *
     * @throws LoteNoConfirmadoException si el log no prueba que se aplicó
     */
    @Transactional
    Entrada marcarAplicada(UUID diagramId, UUID interactionId, UUID userId, UUID opId) {
        InteraccionIA fila = interactions.findById(interactionId)
                .filter(i -> i.getDiagramId().equals(diagramId))
                .orElseThrow(() -> new IllegalArgumentException("La interaccion de IA no pertenece al diagrama"));
        if (fila.getAppliedAt() == null) {
            verificarLote(diagramId, interactionId, userId, opId);
            fila.setAppliedAt(OffsetDateTime.now());
            fila.setAppliedBy(userId);
            fila = interactions.save(fila);
        }
        return entradaDe(fila, nombreDe(fila.getUserId()));
    }

    private void verificarLote(UUID diagramId, UUID interactionId, UUID userId, UUID opId) {
        if (opId == null) throw new IllegalArgumentException("Falta el opId del lote que aplica la propuesta");
        DiagramOperation op = operations.findByOpId(opId)
                .filter(o -> diagramId.equals(o.getDiagramId()))
                .orElseThrow(() -> new LoteNoConfirmadoException(
                        "El lote no figura como aceptado en este diagrama: la propuesta no se marca como aplicada"));
        boolean deTextoOVoz = op.getOrigin() == Origin.TEXT || op.getOrigin() == Origin.VOICE;
        if (op.getOpType() != CommandType.BATCH || !deTextoOVoz) {
            throw new LoteNoConfirmadoException("La operacion indicada no es un lote de la IA");
        }
        if (!userId.equals(op.getUserId())) {
            throw new LoteNoConfirmadoException("El lote lo envio otro usuario");
        }
        if (!interactionId.toString().equals(interaccionDelPayload(op.getPayload()))) {
            throw new LoteNoConfirmadoException("El lote no aplica esta propuesta");
        }
    }

    private String interaccionDelPayload(String payload) {
        try {
            return mapper.readTree(payload).path(CLAVE_INTERACCION).asText(null);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    String nombreDe(UUID userId) {
        if (userId == null) return "Usuario eliminado";
        return users.findById(userId).map(AppUser::getFullName).orElse("Usuario eliminado");
    }

    /** Un turno suelto (registrar, marcarAplicada): su imagen, si la tiene, con una consulta. */
    private Entrada entradaDe(InteraccionIA f, String nombre) {
        ImagenMeta imagen = imagenes == null || f.getImageId() == null ? null : imagenes.metaDe(f.getImageId());
        return entradaDe(f, nombre, imagen);
    }

    private Entrada entradaDe(InteraccionIA f, String nombre, ImagenMeta imagen) {
        return new Entrada(f.getId(), f.getUserId(), nombre, f.getMode(), f.getModel(), f.getPrompt(),
                f.getResponse(), leer(f.getProposal()), f.getAppliedAt() != null, f.getCreatedAt(), imagen);
    }

    private String escribir(PropuestaDeIA propuesta) {
        try {
            return mapper.writeValueAsString(propuesta);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("No se pudo guardar la propuesta de IA", e);
        }
    }

    private PropuestaDeIA leer(String json) {
        try {
            return mapper.readValue(json, PropuestaDeIA.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("El historial de IA contiene una propuesta invalida", e);
        }
    }
}
