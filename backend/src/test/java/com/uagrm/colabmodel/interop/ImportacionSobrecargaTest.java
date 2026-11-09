package com.uagrm.colabmodel.interop;

import com.uagrm.colabmodel.modelcore.metamodel.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.anyCollection;

/** T-05, revision de Codex: un metodo ya existe solo si coincide la firma, no el nombre. */
class ImportacionSobrecargaTest {
    @Test
    void la_propuesta_no_marca_como_existente_una_firma_nueva() {
        UUID diagramId = UUID.randomUUID();
        UUID classId = UUID.randomUUID();
        UmlClass clase = new UmlClass();
        clase.setId(classId); clase.setDiagramId(diagramId); clase.setName("Pedido");
        UmlMethod metodo = new UmlMethod();
        metodo.setId(UUID.randomUUID()); metodo.setClassId(classId); metodo.setName("pagar");
        metodo.setParams("[{\"name\":\"valor\",\"type\":\"int\"}]");
        UmlClassRepository clases = mock(UmlClassRepository.class);
        when(clases.findByDiagramIdAndDeletedAtIsNullOrderByNameAsc(diagramId)).thenReturn(List.of(clase));
        UmlAttributeRepository atributos = mock(UmlAttributeRepository.class);
        when(atributos.findByClassIdInOrderByOrdinalAsc(anyCollection())).thenReturn(List.of());
        UmlMethodRepository metodos = mock(UmlMethodRepository.class);
        when(metodos.findByClassIdInOrderByOrdinalAsc(anyCollection())).thenReturn(List.of(metodo));
        var servicio = new ServicioDeImportacion(List.of(), clases, atributos, metodos);
        var intLeido = new ModeloLeido.MetodoLeido("pagar", "void", List.of(new ModeloLeido.ParametroLeido("valor", "int")));
        var stringLeido = new ModeloLeido.MetodoLeido("pagar", "void", List.of(new ModeloLeido.ParametroLeido("valor", "string")));
        var leido = new ModeloLeido("XMI", List.of(new ModeloLeido.ClaseLeida("c", "Pedido", null, false,
                List.of(), List.of(intLeido, stringLeido))), List.of(), List.of());
        var propuesta = servicio.cruzarConElDiagrama(diagramId, "sobrecarga.xmi", leido);
        assertThat(propuesta.clases().get(0).metodos())
                .extracting(PropuestaDeImportacion.MetodoPropuesto::yaExiste).containsExactly(true, false);
    }
}