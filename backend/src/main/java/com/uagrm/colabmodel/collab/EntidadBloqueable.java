package com.uagrm.colabmodel.collab;

import com.fasterxml.jackson.databind.JsonNode;
import com.uagrm.colabmodel.modelcore.command.CommandType;
import com.uagrm.colabmodel.modelcore.metamodel.UmlAttribute;
import com.uagrm.colabmodel.modelcore.metamodel.UmlAttributeRepository;
import com.uagrm.colabmodel.modelcore.metamodel.UmlMethod;
import com.uagrm.colabmodel.modelcore.metamodel.UmlMethodRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * EXCLUSION MUTUA NIVEL 2: sobre QUE entidad pide lease cada comando (ADR-028).
 *
 * Antes el bus buscaba `classId` o `elementId` en el payload y, si no estaba,
 * dejaba pasar. Pero la mitad de la semantica no los trae: UPDATE_ATTRIBUTE y
 * REMOVE_ATTRIBUTE traen `attributeId`, REMOVE_METHOD `methodId`, SET_ER_FLAG
 * `targetId` y UPDATE_RELATION `relationId`. Ninguno se revisaba, asi que otra
 * sesion podia editar los atributos de una clase que alguien tenia tomada.
 *
 * La entidad se resuelve en el SERVIDOR, leyendo el modelo, y no se le pide al
 * cliente que mande el `classId`: un cliente que se lo olvida --o que miente--
 * volveria a abrir el mismo agujero.
 *
 * Un `switch` sin `default` sobre CommandType: agregar un comando al catalogo
 * cerrado rompe la compilacion hasta decidir si pide lease (igual que
 * EsquemaDeComandos para la IA).
 */
@Component
public class EntidadBloqueable {

    private final UmlAttributeRepository attributes;
    private final UmlMethodRepository methods;

    public EntidadBloqueable(UmlAttributeRepository attributes, UmlMethodRepository methods) {
        this.attributes = attributes;
        this.methods = methods;
    }

    /**
     * @return la entidad cuyo lease hace falta para aplicar este comando, o
     *         vacio si el comando no pide lease (niveles 1 y 3) o si lo que
     *         referencia no existe: eso lo rechaza el applier con NOT_FOUND,
     *         que es un motivo mas util que un lock
     */
    public Optional<UUID> de(CommandType tipo, JsonNode p) {
        return switch (tipo) {
            case RENAME_CLASS, ADD_ATTRIBUTE, ADD_METHOD -> uuid(p, "classId");
            // Un atributo o un metodo es parte de su clase: el lease es de la
            // clase, como decide ADR-004 (granularidad por entidad, no por campo).
            case UPDATE_ATTRIBUTE, REMOVE_ATTRIBUTE -> claseDelAtributo(uuid(p, "attributeId"));
            case REMOVE_METHOD -> uuid(p, "methodId").flatMap(methods::findById).map(UmlMethod::getClassId);
            case SET_ER_FLAG -> "ATTRIBUTE".equals(p.path("targetType").asText())
                    ? claseDelAtributo(uuid(p, "targetId"))
                    : uuid(p, "targetId");
            // La relacion es una entidad con su propio panel: su lease es suyo,
            // no de sus extremos. Si no, editar una multiplicidad bloquearia
            // dos clases enteras.
            case UPDATE_RELATION -> uuid(p, "relationId");
            // Nivel 1 (estructura) y nivel 3 (geometria), sin lease. BATCH se
            // revisa cambio por cambio en el bus. Las decisiones de mapeo y la
            // confirmacion de la base son del diagrama entero, no de una
            // entidad que alguien tenga abierta.
            case CREATE_CLASS, DELETE_CLASS, CREATE_RELATION, DELETE_RELATION,
                 SET_MODELING_DECISION, CONFIRM_SCHEMA, MOVE_ELEMENT, RESIZE_ELEMENT,
                 BATCH -> Optional.empty();
        };
    }

    private Optional<UUID> claseDelAtributo(Optional<UUID> attributeId) {
        return attributeId.flatMap(attributes::findById).map(UmlAttribute::getClassId);
    }

    private static Optional<UUID> uuid(JsonNode p, String campo) {
        JsonNode v = p.path(campo);
        if (v.isMissingNode() || v.isNull() || v.asText().isBlank()) return Optional.empty();
        try {
            return Optional.of(UUID.fromString(v.asText()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();   // lo rechaza el applier con "no es un UUID valido"
        }
    }
}
