package com.uagrm.colabmodel.collab;

import com.uagrm.colabmodel.modelcore.metamodel.UmlAttributeRepository;
import com.uagrm.colabmodel.modelcore.metamodel.UmlMethodRepository;

import static org.mockito.Mockito.mock;

/** Para las pruebas del bus que no editan atributos ni metodos. */
final class Bloqueables {

    private Bloqueables() { }

    /**
     * Resuelve lo que se lee del payload (classId, targetId de clase,
     * relationId); un atributo o metodo no existe, asi que no pide lease.
     */
    static EntidadBloqueable sinModelo() {
        return new EntidadBloqueable(mock(UmlAttributeRepository.class), mock(UmlMethodRepository.class));
    }
}
