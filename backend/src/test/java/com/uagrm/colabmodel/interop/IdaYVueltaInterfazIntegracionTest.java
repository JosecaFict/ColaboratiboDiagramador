package com.uagrm.colabmodel.interop;

import com.uagrm.colabmodel.interop.ModeloLeido.AtributoLeido;
import com.uagrm.colabmodel.interop.ModeloLeido.ClaseLeida;
import com.uagrm.colabmodel.interop.ModeloLeido.MetodoLeido;
import com.uagrm.colabmodel.interop.ModeloLeido.ParametroLeido;
import com.uagrm.colabmodel.interop.ModeloLeido.RelacionLeida;
import com.uagrm.colabmodel.modelcore.metamodel.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M-QA-05 · rama de integracion: ida y vuelta INTERNA (EscritorXmi -> LectorXmi) de un modelo con interfaz,
 * realizacion y dependencia, comparada con {@link ComparadorDeModelos}. El esperado se escribe a mano a partir del
 * modelo de origen (no sale del lector). Pasa contra NUESTRO lector: no es evidencia de EA real.
 *
 * Tambien lee los dos archivos de ejemplo de Steven (docs/MesaDeTrabajo/pruebas-ea/tanda-2-.../para-importar-en-EA)
 * y los compara con lo que esos archivos dicen; se omite si la carpeta no esta (vive en la carpeta principal, sin
 * versionar), con un mensaje: un test omitido no acredita nada.
 */
class IdaYVueltaInterfazIntegracionTest {

    static final UUID D = UUID.fromString("d0000000-0000-4000-8000-000000000009");

    static UUID id(String s) {
        return UUID.nameUUIDFromBytes(s.getBytes());
    }

    // ---------------------------------------------------------------- modelo de origen
    UmlClass persona, cliente, pedido, pagable;
    List<UmlClass> clases;
    List<UmlAttribute> atributos = new ArrayList<>();
    List<UmlMethod> metodos = new ArrayList<>();
    List<UmlRelationship> relaciones = new ArrayList<>();

    UmlClass clase(String n, boolean abs, String est) {
        UmlClass c = new UmlClass();
        c.setId(id("c" + n));
        c.setDiagramId(D);
        c.setName(n);
        c.setAbstract(abs);
        c.setStereotype(est);
        return c;
    }

    void atributo(UmlClass c, String n, String tipo, Visibility v, String ini, boolean pk, int ord) {
        UmlAttribute a = new UmlAttribute();
        a.setId(id("a" + c.getName() + n));
        a.setClassId(c.getId());
        a.setName(n);
        a.setDataType(tipo);
        a.setVisibility(v);
        a.setDefaultValue(ini);
        a.setPk(pk);
        a.setRequired(pk);
        a.setOrdinal(ord);
        atributos.add(a);
    }

    void metodo(UmlClass c, String n, String ret, Visibility v, boolean est, boolean abs, int ord, String... p) {
        UmlMethod m = new UmlMethod();
        m.setId(id("m" + c.getName() + n + String.join(",", p)));
        m.setClassId(c.getId());
        m.setName(n);
        m.setReturnType(ret);
        m.setVisibility(v);
        m.setStatic(est);
        m.setAbstract(abs);
        m.setOrdinal(ord);
        StringBuilder j = new StringBuilder("[");
        for (int i = 0; i + 1 < p.length; i += 2) {
            if (i > 0) j.append(',');
            j.append("{\"name\":\"").append(p[i]).append("\",\"type\":\"").append(p[i + 1]).append("\"}");
        }
        m.setParams(j.append(']').toString());
        metodos.add(m);
    }

    void relacion(RelationKind k, UmlClass o, UmlClass d, String nombre, String ro, String mo, String rd, String md) {
        UmlRelationship r = new UmlRelationship();
        r.setId(id("r" + k + o.getName() + d.getName()));
        r.setDiagramId(D);
        r.setKind(k);
        r.setSourceClassId(o.getId());
        r.setTargetClassId(d.getId());
        r.setName(nombre);
        r.setSourceRole(ro);
        r.setSourceMultiplicity(mo);
        r.setTargetRole(rd);
        r.setTargetMultiplicity(md);
        relaciones.add(r);
    }

    void armar() {
        persona = clase("Persona", true, null);
        cliente = clase("Cliente", false, null);
        pedido = clase("Pedido", false, null);
        pagable = clase("Pagable", false, "interface");
        clases = List.of(persona, cliente, pedido, pagable);
        atributo(persona, "nombre", "string", Visibility.PRIVATE, null, false, 1);
        atributo(cliente, "codigo", "int", Visibility.PRIVATE, null, true, 1);
        atributo(pedido, "total", "double", Visibility.PUBLIC, "0.0", false, 1);
        metodo(pedido, "calcular", "double", Visibility.PUBLIC, false, false, 2, "descuento", "double");
        metodo(pedido, "crear", "Pedido", Visibility.PUBLIC, true, false, 3);
        metodo(persona, "describir", "string", Visibility.PROTECTED, false, true, 2);
        metodo(pagable, "pagar", "boolean", Visibility.PUBLIC, false, false, 1, "monto", "double");
        relacion(RelationKind.GENERALIZATION, cliente, persona, null, null, null, null, null);
        relacion(RelationKind.REALIZATION, cliente, pagable, null, null, null, null, null);
        relacion(RelationKind.ASSOCIATION, pedido, cliente, "hace", "pedidos", "0..*", "cliente", "1");
        relacion(RelationKind.DEPENDENCY, pedido, persona, "usa", null, null, null, null);
    }

    // ---------------------------------------------------------------- esperado, escrito a mano
    ModeloLeido esperado(boolean ea) {
        // Los tipos: el perfil EA los conserva tal cual; el estandar traduce int/string a los de la OMG.
        String s = ea ? "string" : "String", i = ea ? "int" : "Integer";
        List<ClaseLeida> cs = List.of(
                new ClaseLeida("persona", "Persona", null, true,
                        List.of(new AtributoLeido("nombre", s, false, false, false, false, false, false, "PRIVATE", null)),
                        List.of(new MetodoLeido("describir", s, List.of(), "PROTECTED", false, true))),
                new ClaseLeida("cliente", "Cliente", null, false,
                        List.of(new AtributoLeido("codigo", i, true, true, false, false, false, false, "PRIVATE", null)),
                        List.of()),
                new ClaseLeida("pedido", "Pedido", null, false,
                        List.of(new AtributoLeido("total", "double", false, false, false, false, false, false, "PUBLIC", "0.0")),
                        List.of(new MetodoLeido("calcular", "double", List.of(new ParametroLeido("descuento", "double")),
                                        "PUBLIC", false, false),
                                new MetodoLeido("crear", "Pedido", List.of(), "PUBLIC", true, false))),
                new ClaseLeida("pagable", "Pagable", "interface", false, List.of(),
                        List.of(new MetodoLeido("pagar", ea ? "boolean" : "Boolean",
                                List.of(new ParametroLeido("monto", "double")), "PUBLIC", false, false))));
        List<RelacionLeida> rs = List.of(
                new RelacionLeida("cliente", "persona", RelationKind.GENERALIZATION, null, null, null, null, null, false),
                new RelacionLeida("cliente", "pagable", RelationKind.REALIZATION, null, null, null, null, null, false),
                new RelacionLeida("pedido", "cliente", RelationKind.ASSOCIATION, "hace", "0..*", "1", "pedidos", "cliente", false),
                new RelacionLeida("pedido", "persona", RelationKind.DEPENDENCY, "usa", null, null, null, null, false));
        return new ModeloLeido("esperado", cs, rs, List.of());
    }

    @ParameterizedTest
    @EnumSource(EscritorXmi.Perfil.class)
    void interfaz_realizacion_y_dependencia_hacen_ida_y_vuelta_por_EscritorXmi_y_LectorXmi(EscritorXmi.Perfil perfil) {
        armar();
        EscritorXmi.Exportado ex = EscritorXmi.exportar(clases, atributos, metodos, relaciones, List.of(), perfil);
        ModeloLeido leido = new LectorXmi().leer(ex.xmi());
        ModeloLeido esperado = esperado(perfil == EscritorXmi.Perfil.ENTERPRISE_ARCHITECT);
        ComparadorDeModelos.Resultado r = ComparadorDeModelos.comparar(esperado, leido);
        System.out.println("M-QA-05 integracion · ida y vuelta " + perfil + ": " + r.comoTexto()
                + "\n  avisos del lector: " + leido.advertencias());
        assertThat(r.diferencias().stream().map(ComparadorDeModelos.Diferencia::toString).toList())
                .as(r.comoTexto()).containsExactlyInAnyOrderElementsOf(perfil == EscritorXmi.Perfil.ENTERPRISE_ARCHITECT
                        ? concat(DIFERENCIAS_CONOCIDAS_METODO, DIFERENCIAS_CONOCIDAS_EA) : DIFERENCIAS_CONOCIDAS_METODO);
    }

    static List<String> concat(List<String> a, List<String> b) {
        List<String> r = new ArrayList<>(a);
        r.addAll(b);
        return r;
    }

    /**
     * HALLAZGO M-QA-05 (sin corregir; pendiente de las rebanadas de importacion y exportacion de metodos): el metodo
     * pierde visibilidad, `static` y `abstract` en la ida y vuelta, en los DOS perfiles.
     *  - LectorXmi:646 crea `MetodoLeido` con el constructor de 3 argumentos: nunca lee visibilidad, ownerScope/isStatic
     *    ni isAbstract de una operacion (dueno: Rodolfo).
     *  - EscritorXmi:288 escribe la visibilidad de la operacion pero no isStatic ni isAbstract (dueno: Steven).
     * Se fijan aqui para que el test falle (y se actualice) cuando se cierren.
     */
    static final List<String> DIFERENCIAS_CONOCIDAS_METODO = List.of(
            "DISTINTO  Persona.describir() · visibilidad: esperado=PROTECTED obtenido=null",
            "DISTINTO  Persona.describir() · abstracto: esperado=true obtenido=false",
            "DISTINTO  Pedido.calcular(double) · visibilidad: esperado=PUBLIC obtenido=null",
            "DISTINTO  Pedido.crear() · visibilidad: esperado=PUBLIC obtenido=null",
            "DISTINTO  Pedido.crear() · estatico: esperado=true obtenido=false",
            "DISTINTO  Pagable.pagar(double) · visibilidad: esperado=PUBLIC obtenido=null");

    /**
     * HALLAZGO M-QA-05 (sin corregir): en el perfil EA la interfaz se escribe con isAbstract="true" (a proposito, como
     * EA; EscritorXmi:218) y LectorXmi:534 la lee como `abstracta` sin descontar las interfaces. Al reimportar, una
     * interfaz creada con abstracta=false vuelve con abstracta=true. Este caso se fija aqui para que falle (y se
     * actualice) cuando alguien lo corrija.
     */
    static final List<String> DIFERENCIAS_CONOCIDAS_EA = List.of(
            "DISTINTO  Pagable · abstracta: esperado=false obtenido=true");

    // ---------------------------------------------------------------- los ejemplos de Steven
    static final Path CARPETA = Path.of("C:/Users/DIEGO/PROYECTOS/ColaboratiboDiagramador/docs/MesaDeTrabajo/pruebas-ea/"
            + "tanda-2-interfaz-realizacion-estatico-abstracto/para-importar-en-EA");

    /** Lo que dicen los ejemplos de Steven (LEEME-STEVEN.md y el XML), escrito a mano. */
    ModeloLeido esperadoSteven(boolean ea) {
        String s = ea ? "string" : "String", i = ea ? "int" : "Integer", b = ea ? "boolean" : "Boolean";
        return new ModeloLeido("esperado", List.of(
                new ClaseLeida("persona", "Persona", null, true,
                        List.of(new AtributoLeido("nombre", s, false, false, false, false, false, false, "PRIVATE", null)), List.of()),
                new ClaseLeida("cliente", "Cliente", null, false,
                        List.of(new AtributoLeido("codigo", i, true, true, false, false, false, false, "PRIVATE", null)), List.of()),
                new ClaseLeida("pedido", "Pedido", null, false,
                        List.of(new AtributoLeido("total", "decimal", false, false, false, false, false, false, "PRIVATE", null)),
                        List.of(new MetodoLeido("calcular", "decimal", List.of(new ParametroLeido("descuento", "decimal")),
                                "PUBLIC", false, false))),
                new ClaseLeida("pagable", "Pagable", "interface", false, List.of(),
                        List.of(new MetodoLeido("pagar", b, List.of(new ParametroLeido("monto", "decimal")),
                                "PUBLIC", false, false)))),
                List.of(new RelacionLeida("cliente", "persona", RelationKind.GENERALIZATION, null, null, null, null, null, false),
                        new RelacionLeida("cliente", "pagable", RelationKind.REALIZATION, null, null, null, null, null, false),
                        new RelacionLeida("pedido", "cliente", RelationKind.ASSOCIATION, "hace", "0..*", "1", null, null, false),
                        // UML 2.5.1: `aggregation="composite"` en el extremo tipado Cliente => Cliente es la PARTE, Pedido el todo.
                        new RelacionLeida("pedido", "cliente", RelationKind.COMPOSITION, null, "0..*", "1", null, null, false),
                        new RelacionLeida("pedido", "persona", RelationKind.DEPENDENCY, "usa", null, null, null, null, false)),
                List.of());
    }

    @Test
    void los_dos_ejemplos_de_steven_se_leen_como_dicen_ser() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isDirectory(CARPETA),
                "OMITIDA (no acredita nada): falta " + CARPETA);
        StringBuilder informe = new StringBuilder();
        for (String archivo : List.of("interfaz-realizacion-estandar.xmi", "interfaz-realizacion-ea.xml")) {
            ModeloLeido leido = new LectorXmi().leer(Files.readAllBytes(CARPETA.resolve(archivo)));
            ComparadorDeModelos.Resultado r = ComparadorDeModelos.comparar(
                    esperadoSteven(archivo.endsWith(".xml")), leido);
            informe.append(archivo).append(": ").append(r.comoTexto()).append("\n  avisos: ").append(leido.advertencias()).append('\n');
        }
        System.out.println("M-QA-05 integracion · ejemplos de Steven:\n" + informe);
        // Los ejemplos de Steven no traen static ni abstract en metodos y su visibilidad es siempre `public`, que el lector
        // todavia no lee (hallazgo de metodos): se conocen esas diferencias de visibilidad y la de la interfaz en EA.
        assertThat(informe.toString()).contains("interfaz-realizacion-estandar.xmi: 2 diferencia(s):\n"
                        + "DISTINTO  Pedido.calcular(decimal) · visibilidad: esperado=PUBLIC obtenido=null\n"
                        + "DISTINTO  Pagable.pagar(decimal) · visibilidad: esperado=PUBLIC obtenido=null")
                .contains("interfaz-realizacion-ea.xml: 3 diferencia(s):")
                .contains(DIFERENCIAS_CONOCIDAS_EA.get(0));
    }
}
