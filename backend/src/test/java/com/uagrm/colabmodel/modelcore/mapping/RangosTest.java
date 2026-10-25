package com.uagrm.colabmodel.modelcore.mapping;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.uagrm.colabmodel.codegen.GeneradorSpringBoot;
import com.uagrm.colabmodel.codegen.ProyectoGenerado;
import com.uagrm.colabmodel.collab.CommandApplier;
import com.uagrm.colabmodel.modelcore.command.*;
import com.uagrm.colabmodel.modelcore.metamodel.*;
import com.uagrm.colabmodel.project.Diagram;
import com.uagrm.colabmodel.project.DiagramOperationRepository;
import com.uagrm.colabmodel.project.DiagramRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * REGLA 9: el rango de un atributo numerico, en el DTO y en la base.
 *
 * Hasta esta version el CHECK solo salia de enumeraciones: el metamodelo no
 * tenia donde guardar "precio >= 0".
 */
class RangosTest {

    private static final String BASE = "src/main/java/com/uagrm/generado/";

    private static UmlClass clase(String nombre) {
        UmlClass c = new UmlClass();
        c.setId(UUID.randomUUID());
        c.setDiagramId(UUID.randomUUID());
        c.setName(nombre);
        return c;
    }

    private static UmlAttribute atributo(UmlClass c, String nombre, String tipo, String min, String max) {
        UmlAttribute a = new UmlAttribute();
        a.setId(UUID.randomUUID());
        a.setClassId(c.getId());
        a.setName(nombre);
        a.setDataType(tipo);
        a.setMinValue(min == null ? null : new BigDecimal(min));
        a.setMaxValue(max == null ? null : new BigDecimal(max));
        return a;
    }

    private static ProyectoGenerado generar(String nombre, UmlClass c, UmlAttribute... atributos) {
        return GeneradorSpringBoot.generar(GeneradorSpringBoot.Opciones.por(nombre),
                List.of(c), List.of(atributos), List.of(), List.of());
    }

    // ------------------------------------------------------------- DERIVACION

    @Test
    void un_atributo_numerico_con_limites_lleva_su_rango_a_la_columna() {
        UmlClass producto = clase("Producto");
        ModeloLogico m = DerivadorLogico.derivar(List.of(producto),
                List.of(atributo(producto, "stock", "int", "0", null)), List.of());

        ModeloLogico.Columna stock = m.tablas().get(0).columnas().stream()
                .filter(x -> x.nombre().equals("stock")).findFirst().orElseThrow();
        assertThat(stock.rango().condicionSql("stock")).isEqualTo("stock >= 0");
        assertThat(stock.rango().entero()).isTrue();
    }

    // ------------------------------------------------------------- GENERADOR

    @Test
    void el_ddl_lleva_el_check_con_los_dos_limites() {
        UmlClass producto = clase("Producto");
        ProyectoGenerado p = generar("Demo", producto,
                atributo(producto, "precio", "decimal", "0.50", "10000.00"));

        // stripTrailingZeros: "0.50" sale 0.5, y "10000.00" sale 10000, no 1E+4.
        assertThat(p.archivos().get("src/main/resources/db/migration/V1__init.sql"))
                .contains("precio numeric(19,2) CHECK (precio >= 0.5 AND precio <= 10000)");
    }

    @Test
    void un_entero_usa_min_y_max_y_un_decimal_decimal_min_y_max() {
        UmlClass producto = clase("Producto");
        ProyectoGenerado p = generar("Demo", producto,
                atributo(producto, "stock", "int", "0", "500"),
                atributo(producto, "precio", "decimal", "0.5", null));

        // @Min/@Max solo aceptan long: con un decimal no compilarian.
        assertThat(p.archivos().get(BASE + "web/dto/ProductoDto.java"))
                .contains("@Min(0) @Max(500) Integer stock")
                .contains("@DecimalMin(\"0.5\") BigDecimal precio")
                .contains("import jakarta.validation.constraints.Min;");
    }

    @Test
    void emite_el_proyecto_con_rangos_listo_para_compilar_y_arrancar() throws java.io.IOException {
        UmlClass producto = clase("Producto");
        generar("Rangos", producto,
                atributo(producto, "stock", "int", "0", "500"),
                atributo(producto, "precio", "decimal", "0.5", "10000"))
                .escribirEn(java.nio.file.Path.of("target", "generado-rangos"));
        assertThat(java.nio.file.Path.of("target", "generado-rangos", "pom.xml")).exists();
    }

    // ------------------------------------------------------- VALIDACION AL ENTRAR

    private CommandRejectedException rechazo(String tipo, String min, String max) {
        UUID diagramId = UUID.randomUUID();
        DiagramRepository diagrams = mock(DiagramRepository.class);
        Diagram d = new Diagram();
        d.setId(diagramId);
        when(diagrams.findById(diagramId)).thenReturn(Optional.of(d));
        DiagramOperationRepository operations = mock(DiagramOperationRepository.class);
        when(operations.findByOpId(any())).thenReturn(Optional.empty());
        UmlClassRepository classes = mock(UmlClassRepository.class);
        UmlClass c = clase("Producto");
        when(classes.findById(c.getId())).thenReturn(Optional.of(c));

        CommandApplier applier = new CommandApplier(diagrams, operations, classes,
                mock(UmlAttributeRepository.class), mock(UmlMethodRepository.class),
                mock(UmlRelationshipRepository.class), mock(ModelingDecisionRepository.class),
                mock(DiagramLayoutRepository.class), new ObjectMapper());

        ObjectNode p = JsonNodeFactory.instance.objectNode();
        p.put("attributeId", UUID.randomUUID().toString());
        p.put("classId", c.getId().toString());
        p.put("name", "campo");
        p.put("dataType", tipo);
        if (min != null) p.put("minValue", min);
        if (max != null) p.put("maxValue", max);

        return catchThrowableOfType(() -> applier.apply(new Command(UUID.randomUUID(), diagramId, 0,
                CommandType.ADD_ATTRIBUTE, Origin.MOUSE, p), UUID.randomUUID()), CommandRejectedException.class);
    }

    @Test
    void un_minimo_mayor_que_el_maximo_se_rechaza() {
        // Su CHECK no dejaria insertar ninguna fila.
        assertThat(rechazo("int", "10", "5").getMessage()).contains("mayor que el maximo");
    }

    @Test
    void un_rango_sobre_un_texto_se_rechaza() {
        assertThat(rechazo("string", "0", null).getMessage()).contains("Solo un atributo numerico");
    }

    @Test
    void un_limite_con_decimales_sobre_un_entero_se_rechaza() {
        // Daria un @Min(0.5) que no compila.
        assertThat(rechazo("int", "0.5", null).getMessage()).contains("no puede tener decimales");
    }

    @Test
    void un_limite_que_no_es_un_numero_se_rechaza() {
        assertThat(rechazo("decimal", "cero", null).getMessage()).contains("no es un numero");
    }

    @Test
    void un_rango_valido_entra() {
        assertThat(rechazo("decimal", "0", "99.99")).isNull();
    }
}
