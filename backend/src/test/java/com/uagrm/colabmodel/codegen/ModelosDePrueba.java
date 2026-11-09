package com.uagrm.colabmodel.codegen;

import com.uagrm.colabmodel.modelcore.mapping.Enumeraciones;
import com.uagrm.colabmodel.modelcore.metamodel.RelationKind;
import com.uagrm.colabmodel.modelcore.metamodel.UmlAttribute;
import com.uagrm.colabmodel.modelcore.metamodel.UmlClass;
import com.uagrm.colabmodel.modelcore.metamodel.UmlRelationship;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Modelos armados en codigo para lo que ningun canonico cubre.
 *
 * La TIENDA tiene una composicion (Pedido ◆ LineaPedido) cuya parte apunta
 * ademas a otra clase (Producto): es el caso que la variante MongoDB guarda
 * incrustado (ADR-035), y ninguno de los 7 canonicos tiene composiciones.
 */
final class ModelosDePrueba {

    record Modelo(List<UmlClass> clases, List<UmlAttribute> atributos, List<UmlRelationship> relaciones) {

        ProyectoGenerado generar(GeneradorSpringBoot.Opciones opciones) {
            return GeneradorSpringBoot.generar(opciones, clases, atributos, List.of(), relaciones);
        }
    }

    private final UUID diagrama = UUID.randomUUID();
    private final List<UmlClass> clases = new ArrayList<>();
    private final List<UmlAttribute> atributos = new ArrayList<>();
    private final List<UmlRelationship> relaciones = new ArrayList<>();

    private ModelosDePrueba() { }

    static Modelo tienda() {
        ModelosDePrueba b = new ModelosDePrueba();
        UmlClass estado = b.clase("EstadoPedido");
        estado.setStereotype(Enumeraciones.ESTEREOTIPO);
        b.atributo(estado, "Abierto", "string");
        b.atributo(estado, "Pagado", "string");

        UmlClass cliente = b.clase("Cliente");
        b.atributo(cliente, "nombre", "string").setRequired(true);
        UmlAttribute email = b.atributo(cliente, "email", "string");
        email.setUnique(true);

        UmlClass producto = b.clase("Producto");
        b.atributo(producto, "nombre", "string").setRequired(true);
        UmlAttribute precio = b.atributo(producto, "precio", "decimal");
        precio.setRequired(true);
        precio.setMinValue(BigDecimal.ZERO);

        UmlClass pedido = b.clase("Pedido");
        b.atributo(pedido, "fecha", "date").setRequired(true);
        b.atributo(pedido, "estado", "EstadoPedido").setRequired(true);

        UmlClass linea = b.clase("LineaPedido");
        UmlAttribute cantidad = b.atributo(linea, "cantidad", "int");
        cantidad.setRequired(true);
        cantidad.setMinValue(BigDecimal.ONE);

        b.relacion(cliente, pedido, "cliente", RelationKind.ASSOCIATION);
        b.relacion(pedido, linea, "pedido", RelationKind.COMPOSITION);
        b.relacion(producto, linea, "producto", RelationKind.ASSOCIATION);
        return new Modelo(b.clases, b.atributos, b.relaciones);
    }

    private UmlClass clase(String nombre) {
        UmlClass c = new UmlClass();
        c.setId(UUID.nameUUIDFromBytes(nombre.getBytes(StandardCharsets.UTF_8)));
        c.setDiagramId(diagrama);
        c.setName(nombre);
        clases.add(c);
        return c;
    }

    private UmlAttribute atributo(UmlClass c, String nombre, String tipo) {
        UmlAttribute a = new UmlAttribute();
        a.setId(UUID.randomUUID());
        a.setClassId(c.getId());
        a.setName(nombre);
        a.setDataType(tipo);
        a.setOrdinal(atributos.size());
        atributos.add(a);
        return a;
    }

    /** 1 -> 0..*, la FK del lado "muchos" sale del rol. En una composicion, `uno` es el todo. */
    private void relacion(UmlClass uno, UmlClass muchos, String rol, RelationKind tipo) {
        UmlRelationship r = new UmlRelationship();
        r.setId(UUID.randomUUID());
        r.setDiagramId(diagrama);
        r.setSourceClassId(uno.getId());
        r.setTargetClassId(muchos.getId());
        r.setKind(tipo);
        r.setSourceRole(rol);
        r.setSourceMultiplicity("1");
        r.setTargetMultiplicity("0..*");
        relaciones.add(r);
    }
}
