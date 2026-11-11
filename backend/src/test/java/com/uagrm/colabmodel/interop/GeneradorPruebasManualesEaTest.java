package com.uagrm.colabmodel.interop;

import com.uagrm.colabmodel.modelcore.metamodel.*;
import org.junit.jupiter.api.Test;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M-QA-05 · utilidad de pruebas: escribe los archivos que el usuario importa a mano en Enterprise Architect
 * (carpeta `docs/MesaDeTrabajo/pruebas-ea/`). NO toca produccion: arma el modelo «tienda» con lo que existe hoy
 * en main y lo pasa por `EscritorXmi` en los dos perfiles.
 *
 * Salida: `target/pruebas-ea/tanda-1/` o la carpeta de `-Dpruebas.ea.salida=...`. Los ids son fijos (derivados del
 * nombre), asi que regenerar da los mismos archivos. Ademas de escribir, comprueba que cada archivo es XML bien
 * formado y que nuestro propio lector lo lee con las cantidades que dicen los PASOS.md.
 *
 * Ejecutar: `cd backend; mvn -q "-Dtest=GeneradorPruebasManualesEaTest" test`
 */
class GeneradorPruebasManualesEaTest {

    private final UUID diagrama = UUID.nameUUIDFromBytes("tienda-diagrama".getBytes());
    private final Map<String, UmlClass> clases = new LinkedHashMap<>();
    private final List<UmlAttribute> atributos = new ArrayList<>();
    private final List<UmlMethod> metodos = new ArrayList<>();
    private final List<UmlRelationship> relaciones = new ArrayList<>();
    private final Map<UUID, Integer> orden = new HashMap<>();

    private static UUID id(String semilla) {
        return UUID.nameUUIDFromBytes(("tienda/" + semilla).getBytes());
    }

    private UmlClass clase(String nombre, boolean abstracta, String estereotipo) {
        UmlClass c = new UmlClass();
        c.setId(id("clase/" + nombre));
        c.setDiagramId(diagrama);
        c.setName(nombre);
        c.setAbstract(abstracta);
        c.setStereotype(estereotipo);
        clases.put(nombre, c);
        return c;
    }

    private void atributo(String clase, String nombre, String tipo, Visibility v, String inicial, boolean pk,
                          boolean derivado) {
        UmlAttribute a = new UmlAttribute();
        a.setId(id("atributo/" + clase + "." + nombre));
        a.setClassId(clases.get(clase).getId());
        a.setName(nombre);
        a.setDataType(tipo);
        a.setVisibility(v);
        a.setDefaultValue(inicial);
        a.setPk(pk);
        a.setRequired(pk);
        a.setDerived(derivado);
        a.setOrdinal(orden.merge(a.getClassId(), 1, Integer::sum));
        atributos.add(a);
    }

    private void metodo(String clase, String nombre, String retorno, Visibility v, String... parametros) {
        UmlMethod m = new UmlMethod();
        m.setId(id("metodo/" + clase + "." + nombre + "/" + String.join(",", parametros)));
        m.setClassId(clases.get(clase).getId());
        m.setName(nombre);
        m.setReturnType(retorno);
        m.setVisibility(v);
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i + 1 < parametros.length; i += 2) {
            if (i > 0) json.append(',');
            json.append("{\"name\":\"").append(parametros[i]).append("\",\"type\":\"").append(parametros[i + 1]).append("\"}");
        }
        m.setParams(json.append(']').toString());
        m.setOrdinal(orden.merge(m.getClassId(), 1, Integer::sum));
        metodos.add(m);
    }

    private void relacion(RelationKind tipo, String origen, String destino, String nombre, String rolOrigen,
                          String multOrigen, String rolDestino, String multDestino) {
        UmlRelationship r = new UmlRelationship();
        r.setId(id("relacion/" + tipo + "/" + origen + "-" + destino));
        r.setDiagramId(diagrama);
        r.setSourceClassId(clases.get(origen).getId());
        r.setTargetClassId(clases.get(destino).getId());
        r.setKind(tipo);
        r.setName(nombre);
        r.setSourceRole(rolOrigen);
        r.setSourceMultiplicity(multOrigen);
        r.setTargetRole(rolDestino);
        r.setTargetMultiplicity(multDestino);
        relaciones.add(r);
    }

    /** El modelo «tienda» de la tanda 1: solo lo que existe hoy en main (SHA 489dbc5). */
    private void armarTienda() {
        clase("Persona", true, null);
        clase("Cliente", false, null);
        clase("Pedido", false, null);
        clase("EstadoPedido", false, "enumeration");
        clase("LineaPedido", false, null);
        clase("Producto", false, null);
        clase("Categoria", false, null);

        // Las cuatro visibilidades en Persona.
        atributo("Persona", "nombre", "string", Visibility.PUBLIC, null, false, false);
        atributo("Persona", "correo", "string", Visibility.PROTECTED, null, false, false);
        atributo("Persona", "clave", "string", Visibility.PRIVATE, null, false, false);
        atributo("Persona", "alias", "string", Visibility.PACKAGE, null, false, false);
        metodo("Persona", "describir", "string", Visibility.PUBLIC);

        atributo("Cliente", "codigo", "int", Visibility.PRIVATE, null, true, false);
        atributo("Cliente", "descuento", "double", Visibility.PUBLIC, "0.0", false, false);

        atributo("Pedido", "numero", "int", Visibility.PRIVATE, null, true, false);
        atributo("Pedido", "fecha", "date", Visibility.PUBLIC, null, false, false);
        atributo("Pedido", "estado", "EstadoPedido", Visibility.PROTECTED, "PENDIENTE", false, false);
        atributo("Pedido", "total", "double", Visibility.PRIVATE, null, false, true);
        metodo("Pedido", "calcularTotal", "double", Visibility.PUBLIC);
        metodo("Pedido", "agregarLinea", "void", Visibility.PUBLIC, "producto", "Producto", "cantidad", "int");
        metodo("Pedido", "agregarLinea", "void", Visibility.PUBLIC, "producto", "Producto");
        metodo("Pedido", "pagar", "boolean", Visibility.PUBLIC);

        atributo("EstadoPedido", "PENDIENTE", "string", Visibility.PUBLIC, null, false, false);
        atributo("EstadoPedido", "PAGADO", "string", Visibility.PUBLIC, null, false, false);
        atributo("EstadoPedido", "ENVIADO", "string", Visibility.PUBLIC, null, false, false);

        atributo("LineaPedido", "cantidad", "int", Visibility.PRIVATE, "1", false, false);
        atributo("LineaPedido", "precioUnitario", "double", Visibility.PUBLIC, null, false, false);

        atributo("Producto", "nombre", "string", Visibility.PUBLIC, null, false, false);
        atributo("Producto", "precio", "double", Visibility.PUBLIC, "0.0", false, false);

        atributo("Categoria", "nombre", "string", Visibility.PUBLIC, null, false, false);

        relacion(RelationKind.GENERALIZATION, "Cliente", "Persona", null, null, null, null, null);
        relacion(RelationKind.ASSOCIATION, "Pedido", "Cliente", "realiza", "pedidos", "0..*", "cliente", "1");
        relacion(RelationKind.COMPOSITION, "Pedido", "LineaPedido", null, "pedido", "1", "lineas", "1..*");
        relacion(RelationKind.AGGREGATION, "Categoria", "Producto", "clasifica", "categoria", "1", "productos", "0..*");
        relacion(RelationKind.ASSOCIATION, "LineaPedido", "Producto", null, "lineas", "0..*", "producto", "1");
        relacion(RelationKind.DEPENDENCY, "Pedido", "Producto", "usa", null, null, null, null);
    }

    /** Tres columnas, posiciones positivas y distintas: el usuario las compara con lo que dibuja EA. */
    private List<DiagramLayout> disposicion() {
        List<DiagramLayout> r = new ArrayList<>();
        int i = 0;
        for (UmlClass c : clases.values()) {
            DiagramLayout l = new DiagramLayout();
            l.setDiagramId(diagrama);
            l.setElementId(c.getId());
            l.setElementKind("CLASS");
            l.setX(40 + (i % 3) * 260);
            l.setY(40 + (i / 3) * 240);
            l.setWidth(200);
            l.setHeight(150);
            r.add(l);
            i++;
        }
        return r;
    }

    @Test
    void escribe_la_tanda_1_estandar_y_ea_con_xml_bien_formado() throws Exception {
        armarTienda();
        Path salida = Paths.get(System.getProperty("pruebas.ea.salida", "target/pruebas-ea/tanda-1"));
        Files.createDirectories(salida);

        EscritorXmi.Exportado estandar = EscritorXmi.exportar(new ArrayList<>(clases.values()), atributos, metodos,
                relaciones, disposicion(), EscritorXmi.Perfil.ESTANDAR);
        EscritorXmi.Exportado ea = EscritorXmi.exportar(new ArrayList<>(clases.values()), atributos, metodos,
                relaciones, disposicion(), EscritorXmi.Perfil.ENTERPRISE_ARCHITECT);
        Files.write(salida.resolve("tienda-colabmodel-estandar-xmi251.xmi"), estandar.xmi());
        Files.write(salida.resolve("tienda-colabmodel-perfil-ea-xmi21.xmi"), ea.xmi());
        Files.writeString(salida.resolve("perdidas-declaradas.txt"),
                "estandar:\n- " + String.join("\n- ", estandar.perdidas()) + "\n\nea:\n- " + String.join("\n- ", ea.perdidas()) + "\n");

        for (byte[] xmi : List.of(estandar.xmi(), ea.xmi())) {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            assertThat(f.newDocumentBuilder().parse(new ByteArrayInputStream(xmi)).getDocumentElement()).isNotNull();

            ModeloLeido m = new LectorXmi().leer(xmi);
            assertThat(m.clases()).hasSize(7);
            assertThat(m.clases().stream().mapToInt(c -> c.atributos().size()).sum()).isEqualTo(atributos.size());
            assertThat(m.relaciones()).hasSize(6);
            System.out.println("M-QA-05 tanda-1 " + m.formato() + ": " + m.clases().size() + " clases, "
                    + m.relaciones().size() + " relaciones, posiciones=" + m.posiciones().size());
        }
        assertThat(new LectorXmi().leer(ea.xmi()).posiciones()).hasSize(7);
        assertThat(new LectorXmi().leer(estandar.xmi()).posiciones()).isEmpty();
    }
}
