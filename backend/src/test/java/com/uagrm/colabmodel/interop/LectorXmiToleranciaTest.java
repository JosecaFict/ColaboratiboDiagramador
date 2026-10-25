package com.uagrm.colabmodel.interop;

import com.uagrm.colabmodel.interop.HerramientasXmi.Herramienta;
import com.uagrm.colabmodel.modelcore.mapping.ModeloCanonico;
import com.uagrm.colabmodel.modelcore.metamodel.RelationKind;
import com.uagrm.colabmodel.modelcore.metamodel.UmlAttributeRepository;
import com.uagrm.colabmodel.modelcore.metamodel.UmlClassRepository;
import com.uagrm.colabmodel.modelcore.metamodel.UmlMethodRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Un dialecto que nunca vimos no puede tumbar la importacion (ADR-019).
 *
 * El 23/09 el docente puede traer un XMI de su Enterprise Architect que nadie
 * probo. Estas pruebas fijan el contrato para ese caso:
 *   - lo que se entiende se importa, lo que no se lista en las advertencias,
 *     que son lo que muestra la pantalla de revision;
 *   - la unica excepcion posible es ArchivoInvalidoException, con un mensaje
 *     para el usuario, y solo cuando no sale NINGUNA clase;
 *   - el perfil se decide por el contenido, no por lo que dice el encabezado.
 */
class LectorXmiToleranciaTest {

    private final LectorXmi lector = new LectorXmi();

    private ModeloLeido leer(String xmi) {
        return lector.leer(xmi.getBytes(StandardCharsets.UTF_8));
    }

    private static List<String> nombres(ModeloLeido m) {
        return m.clases().stream().map(ModeloLeido.ClaseLeida::nombre).toList();
    }

    /** Lo que llega a la pantalla de revision: la propuesta, no el ModeloLeido. */
    private static PropuestaDeImportacion proponer(String nombreArchivo, byte[] contenido) {
        UUID diagrama = UUID.randomUUID();
        UmlClassRepository classes = mock(UmlClassRepository.class);
        when(classes.findByDiagramIdAndDeletedAtIsNullOrderByNameAsc(diagrama)).thenReturn(List.of());
        return new ServicioDeImportacion(List.of(new LectorModeloJson(), new LectorSql(), new LectorXmi()), classes,
                mock(UmlAttributeRepository.class), mock(UmlMethodRepository.class)).proponer(diagrama, nombreArchivo, contenido);
    }

    // =========================================== ROTO: NUNCA OTRA EXCEPCION

    /** Leer y, si falla, que sea solo por no encontrar ninguna clase. */
    private Optional<ModeloLeido> leerSinCaerse(byte[] contenido) {
        try {
            return Optional.of(lector.leer(contenido));
        } catch (ArchivoInvalidoException e) {
            assertThat(e.getMessage()).isNotBlank().doesNotContain("Exception");
            return Optional.empty();
        }
    }

    @ParameterizedTest
    @EnumSource(Herramienta.class)
    void cortado_en_cualquier_punto_nunca_tumba_la_importacion_y_conserva_lo_leido(Herramienta h) {
        byte[] completo = HerramientasXmi.exportar(h, ModeloCanonico.cargar("07-reflexiva-doble.json"));

        int conClases = 0;
        for (int corte = completo.length / 20; corte < completo.length - 20; corte += completo.length / 40) {
            Optional<ModeloLeido> m = leerSinCaerse(Arrays.copyOf(completo, corte));
            if (m.isEmpty()) continue;
            conClases++;
            assertThat(m.get().advertencias()).as("corte en el byte %d", corte).anyMatch(a -> a.contains("El XML esta roto"));
        }
        assertThat(conClases).as("pasada la mitad del archivo, algo se tiene que importar").isGreaterThan(10);
    }

    @Test
    void cortado_despues_de_las_clases_importa_las_clases_aunque_falten_los_conectores_de_ea() {
        String completo = HerramientasXmi.enterpriseArchitect(ModeloCanonico.cargar("07-reflexiva-doble.json"));
        String cortado = completo.substring(0, completo.indexOf("<connectors>") + 40);

        ModeloLeido m = leer(cortado);

        assertThat(nombres(m)).containsExactly("Empleado", "Vuelo", "Aeropuerto");
        assertThat(m.relaciones()).as("las asociaciones del modelo UML ya estaban antes del corte").hasSize(3);
        assertThat(m.advertencias()).anyMatch(a -> a.contains("El XML esta roto en la linea"));
    }

    @Test
    void bytes_corrompidos_al_azar_nunca_producen_otra_excepcion() {
        byte[] original = HerramientasXmi.exportar(Herramienta.ENTERPRISE_ARCHITECT, ModeloCanonico.cargar("02-uno-a-muchos.json"));
        Random azar = new Random(23_09_2026L);
        for (int intento = 0; intento < 60; intento++) {
            byte[] roto = original.clone();
            for (int i = 0; i < 1 + intento / 4; i++) roto[azar.nextInt(roto.length)] = (byte) azar.nextInt(256);
            leerSinCaerse(roto);
        }
    }

    @Test
    void una_etiqueta_mal_cerrada_a_mitad_de_archivo_conserva_lo_anterior() {
        ModeloLeido m = leer("""
                <xmi:XMI xmi:version="2.1" xmlns:xmi="http://schema.omg.org/spec/XMI/2.1" xmlns:uml="http://schema.omg.org/spec/UML/2.1">
                  <uml:Model xmi:type="uml:Model" name="M">
                    <packagedElement xmi:type="uml:Class" xmi:id="A" name="Libro">
                      <ownedAttribute xmi:type="uml:Property" xmi:id="A1" name="titulo"/>
                    </packagedElement>
                    <packagedElement xmi:type="uml:Class" xmi:id="B" name="Autor">
                      <ownedAttribute xmi:type="uml:Property" xmi:id="B1" name="nombre">
                    </packagedElement>
                    <packagedElement xmi:type="uml:Class" xmi:id="C" name="Editorial"/>
                  </uml:Model>
                </xmi:XMI>
                """);

        assertThat(nombres(m)).startsWith("Libro", "Autor");
        assertThat(m.advertencias()).anyMatch(a -> a.contains("El XML esta roto en la linea")
                && a.contains("lo que seguia en el archivo quedo afuera"));
    }

    @Test
    void entidades_html_ampersands_sueltos_y_caracteres_de_control_se_sanean_y_se_avisa() {
        ModeloLeido m = leer("""
                <?xml version="1.0" encoding="UTF-8"?>
                <xmi:XMI xmi:version="2.1" xmlns:xmi="http://schema.omg.org/spec/XMI/2.1" xmlns:uml="http://schema.omg.org/spec/UML/2.1">
                  <uml:Model xmi:type="uml:Model" name="M">
                    <packagedElement xmi:type="uml:Class" xmi:id="A" name="I&D">
                      <ownedComment xmi:type="uml:Comment" xmi:id="N1" body="Nota&nbsp;copiada de Word\u0001"/>
                      <ownedAttribute xmi:type="uml:Property" xmi:id="A1" name="presupuesto">
                        <type xmi:idref="EAJava_double"/>
                      </ownedAttribute>
                    </packagedElement>
                  </uml:Model>
                </xmi:XMI>
                """);

        assertThat(nombres(m)).containsExactly("I&D");
        assertThat(m.clases().get(0).atributos().get(0).tipoLeido()).isEqualTo("double");
        assertThat(m.advertencias()).anyMatch(a -> a.contains("tenia errores de formato"));
    }

    @Test
    void una_entidad_externa_no_se_resuelve_xxe() {
        ModeloLeido m = leer("""
                <?xml version="1.0"?>
                <!DOCTYPE x [ <!ENTITY secreto SYSTEM "file:///C:/Windows/win.ini"> ]>
                <xmi:XMI xmi:version="2.1" xmlns:xmi="http://schema.omg.org/spec/XMI/2.1" xmlns:uml="http://schema.omg.org/spec/UML/2.1">
                  <uml:Model xmi:type="uml:Model" name="M">
                    <packagedElement xmi:type="uml:Class" xmi:id="A" name="Cliente">
                      <ownedAttribute xmi:type="uml:Property" xmi:id="A1" name="&secreto;"/>
                    </packagedElement>
                  </uml:Model>
                </xmi:XMI>
                """);

        assertThat(nombres(m)).containsExactly("Cliente");
        assertThat(m.clases().get(0).atributos()).allSatisfy(a -> assertThat(a.nombre())
                .doesNotContain("[fonts]").doesNotContain("for 16-bit"));
    }

    @Test
    void lo_que_no_es_xml_o_no_trae_ninguna_clase_es_el_unico_error_y_dice_por_que() {
        assertThat(catchThrowableOfType(() -> leer("esto no es un xmi"), ArchivoInvalidoException.class))
                .hasMessageContaining("no es un XML");
        assertThat(catchThrowableOfType(() -> lector.leer(new byte[0]), ArchivoInvalidoException.class))
                .hasMessageContaining("vacio");
        assertThat(catchThrowableOfType(() -> leer("""
                <xmi:XMI xmi:version="2.1" xmlns:xmi="http://schema.omg.org/spec/XMI/2.1" xmlns:uml="http://schema.omg.org/spec/UML/2.1">
                  <uml:Model xmi:type="uml:Model" name="Casos de uso">
                    <packagedElement xmi:type="uml:Actor" xmi:id="X1" name="Bibliotecario"/>
                    <packagedElement xmi:type="uml:UseCase" xmi:id="X2" name="Prestar libro"/>
                  </uml:Model>
                </xmi:XMI>
                """), ArchivoInvalidoException.class))
                .hasMessageContaining("No se encontro ninguna clase")
                .hasMessageContaining("1 Actor").hasMessageContaining("1 UseCase");
    }

    // ================================= IMPORTACION PARCIAL: EL 80 % QUE SE ENTIENDE

    private static final String MEZCLADO = """
            <?xml version="1.0" encoding="UTF-8"?>
            <xmi:XMI xmi:version="2.5.1" xmlns:xmi="http://www.omg.org/spec/XMI/20131001"
                     xmlns:uml="http://www.omg.org/spec/UML/20161101" xmlns:vp="http://www.visual-paradigm.com/xmi">
              <xmi:Documentation exporter="Herramienta Desconocida" exporterVersion="9.9"/>
              <uml:Model xmi:type="uml:Model" xmi:id="M" name="Biblioteca">
                <packagedElement xmi:type="uml:Class" xmi:id="L" name="Libro">
                  <ownedAttribute xmi:type="uml:Property" xmi:id="L1" name="isbn" isID="true">
                    <type href="http://www.omg.org/spec/UML/20161101/PrimitiveTypes.xmi#String"/>
                  </ownedAttribute>
                  <ownedAttribute xmi:type="uml:Property" xmi:id="L2" name="paginas">
                    <type href="http://www.omg.org/spec/UML/20161101/PrimitiveTypes.xmi#Integer"/>
                    <lowerValue xmi:type="uml:LiteralInteger" xmi:id="L2lo"/>
                  </ownedAttribute>
                  <ownedOperation xmi:type="uml:Operation" xmi:id="L3" name="prestar"/>
                  <vp:Estilo color="#ff0000"/>
                </packagedElement>
                <packagedElement xmi:type="uml:Class" xmi:id="A" name="Autor">
                  <ownedAttribute xmi:type="uml:Property" xmi:id="A1" name="nombre">
                    <type href="pathmap://OTRA_BIBLIOTECA/tipos.uml#_a7Hk2PQzEe2x"/>
                  </ownedAttribute>
                </packagedElement>
                <packagedElement xmi:type="uml:Class" xmi:id="S" name="Socio"/>
                <packagedElement xmi:type="uml:Class" xmi:id="P" name="Prestamo"/>
                <packagedElement xmi:type="uml:Class" xmi:id="SIN"/>

                <packagedElement xmi:type="uml:Association" xmi:id="R1" memberEnd="R1a R1b">
                  <ownedEnd xmi:type="uml:Property" xmi:id="R1a" name="libros" type="L" association="R1">
                    <upperValue xmi:type="uml:LiteralUnlimitedNatural" xmi:id="R1au" value="*"/>
                  </ownedEnd>
                  <ownedEnd xmi:type="uml:Property" xmi:id="R1b" name="autor" type="A" association="R1"/>
                </packagedElement>
                <packagedElement xmi:type="uml:Association" xmi:id="R2" memberEnd="R2a R2b">
                  <ownedEnd xmi:type="uml:Property" xmi:id="R2a" name="socio" type="S" association="R2"/>
                  <ownedEnd xmi:type="uml:Property" xmi:id="R2b" name="fantasma" type="NO_EXISTE" association="R2"/>
                </packagedElement>
                <packagedElement xmi:type="uml:Association" xmi:id="R3" memberEnd="R3a R3b R3c">
                  <ownedEnd xmi:type="uml:Property" xmi:id="R3a" type="S" association="R3"/>
                  <ownedEnd xmi:type="uml:Property" xmi:id="R3b" type="L" association="R3"/>
                  <ownedEnd xmi:type="uml:Property" xmi:id="R3c" type="P" association="R3"/>
                </packagedElement>

                <packagedElement xmi:type="uml:Interface" xmi:id="I" name="Prestable"/>
                <packagedElement xmi:type="uml:InterfaceRealization" xmi:id="IR" client="L" supplier="I"/>
                <packagedElement xmi:type="uml:StateMachine" xmi:id="SM" name="CicloDelPrestamo">
                  <region xmi:type="uml:Region" xmi:id="SMr"/>
                </packagedElement>
                <packagedElement xmi:type="uml:Component" xmi:id="CO" name="ServicioDePrestamos"/>
                <packagedElement xmi:type="vp:ReglaDeNegocio" xmi:id="RN" name="Un socio no puede tener mas de 3 prestamos"/>
              </uml:Model>
              <vp:Diagramas><vp:Diagrama xmi:id="D1" nombre="Clases"/></vp:Diagramas>
            </xmi:XMI>
            """;

    @Test
    void un_archivo_entendido_a_medias_importa_lo_que_entiende_y_lista_lo_que_quedo_afuera() {
        PropuestaDeImportacion p = proponer("docente.xmi", MEZCLADO.getBytes(StandardCharsets.UTF_8));

        // Se importa: 4 de las 5 clases y la unica asociacion sana.
        assertThat(p.clases()).extracting(PropuestaDeImportacion.ClasePropuesta::nombre)
                .containsExactly("Libro", "Autor", "Socio", "Prestamo");
        assertThat(p.relaciones()).singleElement().satisfies(r -> {
            assertThat(r.rolOrigen()).isEqualTo("libros");
            assertThat(r.multiplicidadOrigen()).isEqualTo("0..*");
        });
        assertThat(p.clases().get(0).atributos()).extracting(PropuestaDeImportacion.AtributoPropuesto::nombre)
                .containsExactly("isbn", "paginas");

        // Y la pantalla de revision recibe, uno por uno, los motivos de lo que no entro.
        assertThat(p.advertencias())
                .anyMatch(a -> a.contains("Class") && a.contains("se omitio") && a.contains("no tiene nombre"))
                .anyMatch(a -> a.contains("extremo que no apunta a ninguna clase"))
                .anyMatch(a -> a.contains("une 3 clases") && a.contains("n-aria"))
                .anyMatch(a -> a.contains("Quedaron afuera") && a.contains("1 Interface") && a.contains("1 StateMachine")
                        && a.contains("1 Component") && a.contains("1 ReglaDeNegocio"))
                .anyMatch(a -> a.contains("dependencia o realizacion"))
                .anyMatch(a -> a.contains("tipo que no esta en el archivo") && a.contains("_a7Hk2PQzEe2x"))
                .anyMatch(a -> a.contains("no escriben su multiplicidad"));
        assertThat(p.advertencias().get(0)).contains("4 clases UML").contains("Herramienta Desconocida");
    }

    // ========================================= PERFIL POR CONTENIDO, NO POR ENCABEZADO

    @Test
    void el_encabezado_miente_y_el_perfil_se_decide_por_el_contenido() {
        String xmi = HerramientasXmi.modeloDeDatosEa(
                        com.uagrm.colabmodel.modelcore.mapping.DerivadorLogico.derivar(
                                ModeloCanonico.cargar("02-uno-a-muchos.json").clases(),
                                ModeloCanonico.cargar("02-uno-a-muchos.json").atributos(), List.of()).tablas(),
                        HerramientasXmi.EstereotiposEa.EXTENSION)
                .replace("exporter=\"Enterprise Architect\"", "exporter=\"Visual Paradigm\"")
                .replace("xmi:version=\"2.1\"", "xmi:version=\"1.1\"");

        // Un .xml sin la palabra XMI en el nombre: lo reconoce el contenido.
        PropuestaDeImportacion p = proponer("exportacion.xml", xmi.getBytes(StandardCharsets.UTF_8));

        assertThat(p.formato()).isEqualTo("XMI");
        assertThat(p.clases()).extracting(PropuestaDeImportacion.ClasePropuesta::nombre).containsExactly("cliente", "pedido");
        assertThat(p.advertencias().get(0)).contains("2 tablas de un modelo de datos");
    }

    @Test
    void un_mismo_archivo_puede_mezclar_los_tres_perfiles() {
        ModeloLeido m = leer("""
                <xmi:XMI xmi:version="2.1" xmlns:xmi="http://schema.omg.org/spec/XMI/2.1" xmlns:uml="http://schema.omg.org/spec/UML/2.1">
                  <uml:Model xmi:type="uml:Model" name="M">
                    <packagedElement xmi:type="uml:Class" xmi:id="C1" name="Clase">
                      <ownedAttribute xmi:type="uml:Property" xmi:id="C1a" name="nombre"/>
                    </packagedElement>
                    <packagedElement xmi:type="uml:Class" xmi:id="E1" name="Entidad"/>
                    <packagedElement xmi:type="uml:Class" xmi:id="T1" name="tabla">
                      <ownedAttribute xmi:type="uml:Property" xmi:id="T1a" name="codigo"/>
                    </packagedElement>
                  </uml:Model>
                  <xmi:Extension extender="Enterprise Architect">
                    <elements>
                      <element xmi:idref="E1"><properties stereotype="Entity"/></element>
                      <element xmi:idref="T1"><properties stereotype="table"/></element>
                    </elements>
                  </xmi:Extension>
                </xmi:XMI>
                """);

        assertThat(nombres(m)).containsExactlyInAnyOrder("Clase", "Entidad", "tabla");
        assertThat(m.advertencias().get(0)).contains("1 clase UML").contains("1 entidad").contains("1 tabla");
    }

    // ============================================ VARIANTES DE EA DE UN MISMO «table»

    /** Las formas en que distintas versiones de EA (y otros) dicen que una clase es una tabla. */
    enum FormaDelEstereotipo {
        PROPERTIES("<element xmi:idref=\"T\"><properties sType=\"Class\" stereotype=\"table\"/></element>", ""),
        NODO_STEREOTYPE("<element xmi:idref=\"T\"><stereotype stereotype=\"EAUML::table\"/></element>", ""),
        GUILLEMETS_Y_MAYUSCULAS("<element xmi:idref=\"T\"><properties stereotype=\"«Table»\"/></element>", ""),
        SOLO_XREFS("<element xmi:idref=\"T\"><xrefs value=\"$XREFPROP=$XID={AB}$XID;$NAM=Stereotypes$NAM;"
                   + "$DES=@STEREO;Name=table;GUID={CD};FQName=EAUML::table;@ENDSTEREO;$DES;$ENDXREF;\"/></element>", ""),
        PERFIL_EAUML("", "<EAUML:table xmi:id=\"S\" base_Class=\"T\"/>"),
        PERFIL_SIN_DECLARAR_Y_OTRO_PREFIJO("", "<thecustomprofile:Table base_Class=\"T\"/>"),
        TAGGED_VALUE_EA_STYPE("", "<UML:TaggedValue xmi.id=\"TV\" tag=\"ea_stype\" value=\"table\" modelElement=\"T\"/>");

        final String enExtension;
        final String afuera;

        FormaDelEstereotipo(String enExtension, String afuera) {
            this.enExtension = enExtension;
            this.afuera = afuera;
        }
    }

    @ParameterizedTest
    @EnumSource(FormaDelEstereotipo.class)
    void el_estereotipo_de_tabla_se_reconoce_lo_escriba_como_lo_escriba_la_version_de_ea(FormaDelEstereotipo forma) {
        ModeloLeido m = leer("""
                <xmi:XMI xmi:version="2.1" xmlns:xmi="http://schema.omg.org/spec/XMI/2.1" xmlns:uml="http://schema.omg.org/spec/UML/2.1" xmlns:EAUML="http://www.sparxsystems.com/profiles/EAUML/1.0">
                  <uml:Model xmi:type="uml:Model" name="EA_Model">
                    <packagedElement xmi:type="uml:Class" xmi:id="T" name="socio">
                      <ownedAttribute xmi:type="uml:Property" xmi:id="C1" name="id"><type xmi:idref="EAnone_uuid"/></ownedAttribute>
                      <ownedAttribute xmi:type="uml:Property" xmi:id="C2" name="carnet"><type xmi:idref="EAnone_varchar"/></ownedAttribute>
                      <ownedOperation xmi:id="O1" name="PK_socio">
                        <ownedParameter xmi:id="O1p" name="id" direction="in"/>
                      </ownedOperation>
                    </packagedElement>
                  </uml:Model>
                  %s
                  <xmi:Extension extender="Enterprise Architect" extenderID="6.5">
                    <elements>%s</elements>
                    <operations/>
                  </xmi:Extension>
                </xmi:XMI>
                """.formatted(forma.afuera, forma.enExtension)
                .replace("<operations/>", "<elements><element xmi:idref=\"T\"><operations><operation xmi:idref=\"O1\">"
                        + "<stereotype stereotype=\"PK\"/></operation></operations></element></elements>"));

        assertThat(m.advertencias().get(0)).contains("1 tabla de un modelo de datos");
        // Como tabla, `id uuid` PK es la identidad que la herramienta ya pone: no queda como atributo.
        assertThat(m.clases()).singleElement().satisfies(c -> assertThat(c.atributos())
                .extracting(ModeloLeido.AtributoLeido::nombre).containsExactly("carnet"));
    }

    // ================================================================= ERD

    @Test
    void erd_entidades_atributos_como_elementos_y_rombos_con_cardinalidad_chen() {
        ModeloLeido m = leer("""
                <xmi:XMI xmi:version="2.1" xmlns:xmi="http://schema.omg.org/spec/XMI/2.1" xmlns:uml="http://schema.omg.org/spec/UML/2.1">
                  <xmi:Documentation exporter="Enterprise Architect" exporterVersion="6.5"/>
                  <uml:Model xmi:type="uml:Model" name="EA_Model">
                    <packagedElement xmi:type="uml:Package" xmi:id="PK1" name="ERD">
                      <packagedElement xmi:type="uml:Class" xmi:id="E_CLI" name="Cliente"/>
                      <packagedElement xmi:type="uml:Class" xmi:id="E_PED" name="Pedido"/>
                      <packagedElement xmi:type="uml:Class" xmi:id="E_PRO" name="Producto"/>
                      <packagedElement xmi:type="uml:Class" xmi:id="A_CI" name="ci"/>
                      <packagedElement xmi:type="uml:Class" xmi:id="A_TEL" name="telefono"/>
                      <packagedElement xmi:type="uml:Class" xmi:id="A_FEC" name="fecha"/>
                      <packagedElement xmi:type="uml:Class" xmi:id="R_HACE" name="hace"/>
                      <packagedElement xmi:type="uml:Class" xmi:id="R_CONT" name="contiene">
                        <ownedAttribute xmi:type="uml:Property" xmi:id="R_CONT_a" name="cantidad"/>
                      </packagedElement>
                      <packagedElement xmi:type="uml:Class" xmi:id="R_TER" name="entrega"/>
                    </packagedElement>
                  </uml:Model>
                  <xmi:Extension extender="Enterprise Architect">
                    <elements>
                      <element xmi:idref="E_CLI"><properties stereotype="Entity"/></element>
                      <element xmi:idref="E_PED"><properties stereotype="Entity"/></element>
                      <element xmi:idref="E_PRO"><properties stereotype="entity"/></element>
                      <element xmi:idref="A_CI"><properties stereotype="PrimaryKey"/></element>
                      <element xmi:idref="A_TEL"><properties stereotype="MultivaluedAttribute"/></element>
                      <element xmi:idref="A_FEC"><properties stereotype="Attribute"/></element>
                      <element xmi:idref="R_HACE"><properties stereotype="Relationship"/></element>
                      <element xmi:idref="R_CONT"><properties stereotype="Relationship"/></element>
                      <element xmi:idref="R_TER"><properties stereotype="Relationship"/></element>
                    </elements>
                    <connectors>
                      <connector xmi:idref="K1"><source xmi:idref="E_CLI"/><target xmi:idref="A_CI"/><properties ea_type="Association"/></connector>
                      <connector xmi:idref="K2"><source xmi:idref="E_CLI"/><target xmi:idref="A_TEL"/><properties ea_type="Association"/></connector>
                      <connector xmi:idref="K3"><source xmi:idref="A_FEC"/><target xmi:idref="E_PED"/><properties ea_type="Association"/></connector>
                      <connector xmi:idref="K4"><source xmi:idref="E_CLI"><type multiplicity="(1,1)"/></source><target xmi:idref="R_HACE"/><properties ea_type="Association"/></connector>
                      <connector xmi:idref="K5"><source xmi:idref="E_PED"><type multiplicity="(0,N)"/></source><target xmi:idref="R_HACE"/><properties ea_type="Association"/></connector>
                      <connector xmi:idref="K6"><source xmi:idref="E_PED"/><target xmi:idref="R_CONT"/><properties ea_type="Association"/></connector>
                      <connector xmi:idref="K7"><source xmi:idref="E_PRO"/><target xmi:idref="R_CONT"/><properties ea_type="Association"/></connector>
                      <connector xmi:idref="K8"><source xmi:idref="E_CLI"/><target xmi:idref="R_TER"/><properties ea_type="Association"/></connector>
                      <connector xmi:idref="K9"><source xmi:idref="E_PED"/><target xmi:idref="R_TER"/><properties ea_type="Association"/></connector>
                      <connector xmi:idref="K10"><source xmi:idref="E_PRO"/><target xmi:idref="R_TER"/><properties ea_type="Association"/></connector>
                    </connectors>
                  </xmi:Extension>
                </xmi:XMI>
                """);

        assertThat(nombres(m)).containsExactly("Cliente", "Pedido", "Producto", "contiene", "entrega");
        assertThat(m.clases().get(0).atributos()).satisfiesExactly(
                ci -> assertThat(ci.pk()).isTrue(),
                tel -> assertThat(tel.multivaluado()).isTrue());
        assertThat(m.clases().get(1).atributos()).extracting(ModeloLeido.AtributoLeido::nombre).containsExactly("fecha");
        // `hace` es binaria y sin atributos: una relacion con la cardinalidad Chen de cada lado.
        assertThat(m.relaciones()).filteredOn(r -> "hace".equals(r.nombre())).singleElement().satisfies(r -> {
            assertThat(r.multiplicidadOrigen()).isEqualTo("1");
            assertThat(r.multiplicidadDestino()).isEqualTo("0..*");
        });
        assertThat(m.advertencias())
                .anyMatch(a -> a.contains("\"contiene\" tiene atributos propios") && a.contains("clase asociativa"))
                .anyMatch(a -> a.contains("\"entrega\" une 3 entidades"))
                .anyMatch(a -> a.contains("notacion Chen"));
    }

    // ============================================================ XMI 1.1 DE EA

    @Test
    void xmi_1_1_de_enterprise_architect_con_uml_1_3_y_tagged_values() {
        ModeloLeido m = leer("""
                <?xml version="1.0" encoding="windows-1252"?>
                <XMI xmi.version="1.1" xmlns:UML="omg.org/UML1.3" timestamp="2026-09-01 10:00:00">
                  <XMI.header><XMI.documentation><XMI.exporter>Enterprise Architect</XMI.exporter><XMI.exporterVersion>2.5</XMI.exporterVersion></XMI.documentation></XMI.header>
                  <XMI.content>
                    <UML:Model name="EA Model" xmi.id="MX_EAID_1">
                      <UML:Namespace.ownedElement>
                        <UML:Package name="Datos" xmi.id="EAPK_1" isRoot="false" isLeaf="false" isAbstract="false" visibility="public">
                          <UML:Namespace.ownedElement>
                            <UML:Class name="socio" xmi.id="EAID_S" visibility="public" namespace="EAPK_1">
                              <UML:ModelElement.taggedValue>
                                <UML:TaggedValue tag="ea_stype" value="table"/>
                                <UML:TaggedValue tag="gentype" value="PostgreSQL"/>
                              </UML:ModelElement.taggedValue>
                              <UML:Classifier.feature>
                                <UML:Attribute name="id" changeable="none" visibility="public" ownerScope="instance" targetScope="instance">
                                  <UML:Attribute.initialValue><UML:Expression/></UML:Attribute.initialValue>
                                  <UML:StructuralFeature.type><UML:Classifier xmi.idref="eaxmiid0"/></UML:StructuralFeature.type>
                                  <UML:ModelElement.taggedValue>
                                    <UML:TaggedValue tag="type" value="uuid"/>
                                    <UML:TaggedValue tag="stereotype" value="column"/>
                                    <UML:TaggedValue tag="allowduplicates" value="1"/>
                                  </UML:ModelElement.taggedValue>
                                </UML:Attribute>
                                <UML:Attribute name="nombre" changeable="none" visibility="public">
                                  <UML:ModelElement.taggedValue>
                                    <UML:TaggedValue tag="type" value="VARCHAR"/>
                                    <UML:TaggedValue tag="length" value="80"/>
                                    <UML:TaggedValue tag="stereotype" value="column"/>
                                  </UML:ModelElement.taggedValue>
                                </UML:Attribute>
                                <UML:Operation name="PK_socio" visibility="public" ownerScope="instance">
                                  <UML:ModelElement.stereotype><UML:Stereotype name="PK"/></UML:ModelElement.stereotype>
                                  <UML:BehavioralFeature.parameter>
                                    <UML:Parameter name="id" kind="in" visibility="public"/>
                                  </UML:BehavioralFeature.parameter>
                                </UML:Operation>
                              </UML:Classifier.feature>
                            </UML:Class>
                          </UML:Namespace.ownedElement>
                        </UML:Package>
                      </UML:Namespace.ownedElement>
                    </UML:Model>
                    <UML:DataType xmi.id="eaxmiid0" name="uuid" visibility="private"/>
                  </XMI.content>
                  <XMI.extensions xmi.extender="Enterprise Architect 2.5"/>
                </XMI>
                """);

        assertThat(nombres(m)).containsExactly("socio");
        assertThat(m.clases().get(0).atributos()).singleElement().satisfies(a -> {
            assertThat(a.nombre()).isEqualTo("nombre");
            assertThat(a.tipoLeido()).isEqualTo("VARCHAR(80)".toLowerCase(Locale.ROOT));
        });
        assertThat(m.advertencias().get(0)).contains("1 tabla").contains("XMI 1.1");
    }

    // ======================================================== ACEPTAR

    @ParameterizedTest
    @ValueSource(strings = {"modelo.xmi", "modelo.XMI", "modelo.uml", "sin-extension", "exportacion.xml"})
    void acepta_por_extension_o_por_contenido(String nombre) {
        assertThat(lector.acepta(nombre, MEZCLADO.getBytes(StandardCharsets.UTF_8))).isTrue();
    }

    @Test
    void no_se_hace_cargo_de_un_json_ni_de_un_sql() {
        assertThat(lector.acepta("modelo.json", "{\"xmlns:uml\": 1}".getBytes())).isFalse();
        assertThat(lector.acepta("modelo.sql", "-- <xmi".getBytes())).isFalse();
        assertThat(lector.acepta("notas.xml", "<notas><nota/></notas>".getBytes())).isFalse();
    }

    @Test
    void una_relacion_del_archivo_se_ve_igual_venga_de_la_asociacion_o_del_conector_de_ea() {
        ModeloLeido m = lector.leer(HerramientasXmi.exportar(Herramienta.ENTERPRISE_ARCHITECT,
                ModeloCanonico.cargar("02-uno-a-muchos.json")));
        assertThat(m.relaciones()).singleElement().satisfies(r -> {
            assertThat(r.tipo()).isEqualTo(RelationKind.ASSOCIATION);
            assertThat(r.rolOrigen()).isEqualTo("cliente");
            assertThat(r.multiplicidadDestino()).isEqualTo("0..*");
        });
    }
}
