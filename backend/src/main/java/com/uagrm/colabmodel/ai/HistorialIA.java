package com.uagrm.colabmodel.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uagrm.colabmodel.iam.AppUserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class HistorialIA {

    public record Entrada(UUID id, UUID userId, String userName, ModoIA modo, String modelo,
                          String prompt, String respuesta, PropuestaDeIA propuesta,
                          boolean aplicada, OffsetDateTime creadaEn) { }

    private final InteraccionIARepository interactions;
    private final AppUserRepository users;
    private final ObjectMapper mapper;

    HistorialIA(InteraccionIARepository interactions, AppUserRepository users, ObjectMapper mapper) {
        this.interactions = interactions;
        this.users = users;
        this.mapper = mapper;
    }

    @Transactional
    PropuestaDeIA registrar(UUID diagramId, UUID userId, ModoIA modo, String prompt,
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
        interactions.save(fila);
        return identificada;
    }

    @Transactional(readOnly = true)
    List<Entrada> listar(UUID diagramId) {
        List<InteraccionIA> filas = interactions.findByDiagramIdOrderByCreatedAtAscIdAsc(diagramId);
        Map<UUID, String> nombres = users.findAllById(filas.stream().map(InteraccionIA::getUserId)
                        .filter(java.util.Objects::nonNull).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(u -> u.getId(), u -> u.getFullName()));
        return filas.stream().map(f -> new Entrada(f.getId(), f.getUserId(),
                nombres.getOrDefault(f.getUserId(), "Usuario eliminado"), f.getMode(), f.getModel(),
                f.getPrompt(), f.getResponse(), leer(f.getProposal()), f.getAppliedAt() != null, f.getCreatedAt()))
                .toList();
    }

    @Transactional
    void marcarAplicada(UUID diagramId, UUID interactionId, UUID userId) {
        InteraccionIA fila = interactions.findById(interactionId)
                .filter(i -> i.getDiagramId().equals(diagramId))
                .orElseThrow(() -> new IllegalArgumentException("La interaccion de IA no pertenece al diagrama"));
        if (fila.getAppliedAt() == null) {
            fila.setAppliedAt(OffsetDateTime.now());
            fila.setAppliedBy(userId);
        }
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

