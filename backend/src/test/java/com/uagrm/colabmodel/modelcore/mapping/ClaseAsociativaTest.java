package com.uagrm.colabmodel.modelcore.mapping;

import com.uagrm.colabmodel.modelcore.metamodel.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La clase asociativa de una N:M, con el ejemplo clasico de la materia.
 *
 *   NotaVenta 0..* ---- Vende ---- 1..* Producto
 *                     cantidad
 *                     precio
 *
 *   NOTAVENTA(Nro, Fecha, Monto)
 *   VENDE(NroV, CodP, Cantidad, Precio)      <- PK compuesta por las DOS FK
 *   PRODUCTO(Cod, Descripcion, Precio)
 *
 * Es el caso que obliga a que una entidad debil pueda tener MAS DE UNA relacion
 * identificadora. Mientras el derivador se quedaba con la primera, la segunda se
 * daba por materializada y su clave foranea no se emitia en ningun lado: la tabla
 * salia con una sola FK, sin la otra relacion y SIN UN AVISO. Ese es el modo de
 * falla que la Regla 10 prohibe, y por eso estos tests existen.
 */
class ClaseAsociativaTest {

    private final UUID diagrama = UUID.randomUUID();

    @Test
    @DisplayName("Las dos identificadoras arman la PK compuesta, como la tabla puente")
    void pk_compuesta_por_las_dos_fk() {
        Caso c = new Caso(true, true);

        ModeloLogico.Tabla vende = tabla(c.derivar(), "vende");

        assertThat(vende.columnas()).filteredOn(ModeloLogico.Columna::pk)
                .extracting(ModeloLogico.Columna::nombre)
                .containsExactly("nota_venta_id", "producto_id");
        // Sin id surrogado: la identidad son sus dos duenios (Regla 11).
        assertThat(vende.columnas()).extracting(ModeloLogico.Columna::nombre)
                .doesNotContain("id")
                .contains("cantidad", "precio");
        // Y si se va cualquiera de los dos lados, la fila no significa nada.
        assertThat(vende.columnas()).filteredOn(ModeloLogico.Columna::pk)
                .extracting(ModeloLogico.Columna::onDelete)
                .containsOnly("CASCADE");
    }

    @Test
    @DisplayName("Ninguna de las dos claves foraneas se pierde por el camino")
    void no_se_pierde_ninguna_fk() {
        // La regresion concreta: con dos identificadoras, `producto_id` desaparecia
        // del esquema entero. Una relacion dibujada que no llega al DDL y que
        // ademas no se denuncia es el peor resultado posible.
        ModeloLogico.Tabla vende = tabla(new Caso(true, true).derivar(), "vende");

        assertThat(vende.columnas()).extracting(ModeloLogico.Columna::referencia)
                .contains("nota_venta(id)", "producto(id)");
    }

    @Test
    @DisplayName("Sin marcarla debil, la intermedia lleva su propio id y dos FK comunes")
    void sin_debil_lleva_id_propio() {
        // Es la otra lectura valida, y la que deja al generador emitirle las cinco
        // capas: una entidad normal. Lo que NO da es la restriccion de unicidad del
        // par, que ahi hay que agregar a mano.
        ModeloLogico.Tabla vende = tabla(new Caso(false, false).derivar(), "vende");

        assertThat(vende.columnas()).filteredOn(ModeloLogico.Columna::pk)
                .extracting(ModeloLogico.Columna::nombre)
                .containsExactly("id");
        assertThat(vende.columnas()).extracting(ModeloLogico.Columna::referencia)
                .contains("nota_venta(id)", "producto(id)");
    }

    @Test
    @DisplayName("Con una sola identificadora, la otra FK sigue estando como columna comun")
    void una_sola_identificadora() {
        // Modelo raro --la PK deja repetir pares-- pero no se pierde nada: la
        // segunda relacion entra por el camino normal.
        ModeloLogico.Tabla vende = tabla(new Caso(true, false).derivar(), "vende");

        assertThat(vende.columnas()).filteredOn(ModeloLogico.Columna::pk)
                .extracting(ModeloLogico.Columna::nombre)
                .containsExactly("nota_venta_id");
        assertThat(vende.columnas()).extracting(ModeloLogico.Columna::referencia)
                .contains("producto(id)");
    }

    // ------------------------------------------------------------- EL CASO

    /** NotaVenta y Producto unidas por la clase asociativa Vende. */
    private final class Caso {

        private final UmlClass notaVenta = clase("NotaVenta", false);
        private final UmlClass producto = clase("Producto", false);
        private final UmlClass vende;
        private final UmlRelationship desdeNota;
        private final UmlRelationship desdeProducto;

        Caso(boolean debil, boolean dosIdentificadoras) {
            vende = clase("Vende", debil);
            desdeNota = relacion(notaVenta, vende, debil);
            desdeProducto = relacion(producto, vende, debil && dosIdentificadoras);
        }

        ModeloLogico derivar() {
            return DerivadorLogico.derivar(
                    List.of(notaVenta, producto, vende),
                    List.of(atributo(notaVenta, "fecha", "date"),
                            atributo(notaVenta, "monto", "decimal"),
                            atributo(producto, "descripcion", "string"),
                            atributo(vende, "cantidad", "int"),
                            atributo(vende, "precio", "decimal")),
                    List.of(desdeNota, desdeProducto));
        }
    }

    private static ModeloLogico.Tabla tabla(ModeloLogico m, String nombre) {
        return m.tablas().stream().filter(t -> t.nombre().equals(nombre)).findFirst()
                .orElseThrow(() -> new AssertionError("No se derivo la tabla " + nombre));
    }

    private UmlClass clase(String nombre, boolean debil) {
        UmlClass c = new UmlClass();
        c.setId(UUID.randomUUID());
        c.setDiagramId(diagrama);
        c.setName(nombre);
        c.setWeak(debil);
        c.setErKind(debil ? ErKind.WEAK : ErKind.STRONG);
        return c;
    }

    private UmlAttribute atributo(UmlClass clase, String nombre, String tipo) {
        UmlAttribute a = new UmlAttribute();
        a.setId(UUID.randomUUID());
        a.setClassId(clase.getId());
        a.setName(nombre);
        a.setDataType(tipo);
        return a;
    }

    /** La clase original es el lado "1"; la asociativa, el lado "muchos". */
    private UmlRelationship relacion(UmlClass origen, UmlClass asociativa, boolean identificadora) {
        UmlRelationship r = new UmlRelationship();
        r.setId(UUID.randomUUID());
        r.setDiagramId(diagrama);
        r.setSourceClassId(origen.getId());
        r.setTargetClassId(asociativa.getId());
        r.setKind(RelationKind.ASSOCIATION);
        r.setSourceMultiplicity("1");
        r.setTargetMultiplicity("0..*");
        r.setIdentifying(identificadora);
        return r;
    }
}
