package com.uagrm.colabmodel.modelcore.mapping;

import com.uagrm.colabmodel.codegen.GeneradorSpringBoot;
import com.uagrm.colabmodel.codegen.ProyectoGenerado;
import com.uagrm.colabmodel.modelcore.metamodel.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REGLA 5: objetos de valor (@Embeddable).
 *
 * Antes la composicion 1..1 leida como OO (A-2) aplanaba columnas, pero en Java
 * salian como campos sueltos en el duenio: no habia ninguna clase @Embeddable.
 * Y no habia forma de marcar una clase como valor explicitamente.
 */
class EmbebidosTest {

    private static final String BASE = "src/main/java/com/uagrm/generado/";
    private final UUID diagrama = UUID.randomUUID();
    private final List<UmlClass> clases = new ArrayList<>();
    private final List<UmlAttribute> atributos = new ArrayList<>();
    private final List<UmlRelationship> relaciones = new ArrayList<>();
    private UmlClass direccion;
    private UmlClass cliente;

    private UmlClass clase(String nombre) {
        UmlClass c = new UmlClass();
        c.setId(UUID.randomUUID());
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

    private UmlRelationship rel(UmlClass origen, UmlClass destino, RelationKind kind, String mo, String md) {
        UmlRelationship r = new UmlRelationship();
        r.setId(UUID.randomUUID());
        r.setDiagramId(diagrama);
        r.setSourceClassId(origen.getId());
        r.setTargetClassId(destino.getId());
        r.setKind(kind);
        r.setSourceMultiplicity(mo);
        r.setTargetMultiplicity(md);
        relaciones.add(r);
        return r;
    }

    @BeforeEach
    void valor() {
        direccion = clase("Direccion");
        direccion.setStereotype(Incrustables.ESTEREOTIPO);
        atributo(direccion, "calle", "string").setRequired(true);
        atributo(direccion, "ciudad", "string");
        cliente = clase("Cliente");
        atributo(cliente, "nombre", "string");
    }

    private ModeloLogico derivar() {
        return DerivadorLogico.derivar(clases, atributos, relaciones);
    }

    private ProyectoGenerado generar(String nombre) {
        return GeneradorSpringBoot.generar(GeneradorSpringBoot.Opciones.por(nombre),
                clases, atributos, List.of(), relaciones);
    }

    private static ModeloLogico.Tabla tabla(ModeloLogico m, String nombre) {
        return m.tablas().stream().filter(t -> t.nombre().equals(nombre)).findFirst().orElse(null);
    }

    // ------------------------------------------------------------- DERIVACION

    @Test
    void un_atributo_de_tipo_embeddable_se_guarda_en_columnas_prefijadas() {
        atributo(cliente, "domicilio", "Direccion").setRequired(true);

        ModeloLogico m = derivar();

        assertThat(tabla(m, "direccion")).isNull();
        ModeloLogico.Tabla t = tabla(m, "cliente");
        assertThat(t.columnas()).extracting(ModeloLogico.Columna::nombre)
                .containsExactly("id", "nombre", "domicilio_calle", "domicilio_ciudad");
        assertThat(t.incrustados()).singleElement().satisfies(i -> {
            assertThat(i.campo()).isEqualTo("domicilio");
            assertThat(i.clase()).isEqualTo("Direccion");
            assertThat(i.columnas()).extracting(ModeloLogico.ColumnaIncrustada::atributo)
                    .containsExactly("calle", "ciudad");
        });
    }

    @Test
    void un_campo_es_not_null_solo_si_el_valor_y_el_campo_son_obligatorios() {
        atributo(cliente, "domicilio", "Direccion");   // el valor es opcional

        ModeloLogico.Tabla t = tabla(derivar(), "cliente");

        // calle es obligatoria dentro de Direccion, pero un cliente sin domicilio
        // tiene la columna vacia: no puede ser NOT NULL.
        assertThat(t.columnas().stream().filter(c -> c.nombre().equals("domicilio_calle"))
                .findFirst().orElseThrow().nullable()).isTrue();
    }

    @Test
    void el_mismo_valor_sirve_a_varios_duenios_por_atributo_y_por_composicion() {
        atributo(cliente, "domicilio", "Direccion");
        UmlClass proveedor = clase("Proveedor");
        rel(proveedor, direccion, RelationKind.COMPOSITION, "1", "1");

        ModeloLogico m = derivar();

        assertThat(tabla(m, "cliente").incrustados()).hasSize(1);
        assertThat(tabla(m, "proveedor").columnas()).extracting(ModeloLogico.Columna::nombre)
                .contains("direccion_calle", "direccion_ciudad");
        assertThat(m.advertencias()).noneMatch(a -> a.contains("otras relaciones"));
    }

    @Test
    void componer_una_clase_embeddable_no_se_pregunta_como_ambiguedad() {
        UmlClass proveedor = clase("Proveedor");
        rel(proveedor, direccion, RelationKind.COMPOSITION, "1", "1");

        // El estereotipo ya lo decidio: preguntar A-2 seria preguntar dos veces.
        assertThat(Ambiguedades.detectar(clases, atributos, relaciones)).isEmpty();
        assertThat(tabla(derivar(), "direccion")).isNull();
    }

    @Test
    void asociar_una_clase_embeddable_se_ignora_y_se_avisa() {
        rel(cliente, direccion, RelationKind.ASSOCIATION, "0..*", "1");

        ModeloLogico m = derivar();

        assertThat(tabla(m, "cliente").columnas()).noneMatch(c -> c.referencia() != null);
        assertThat(m.advertencias()).anyMatch(a -> a.contains("toca una clase «embeddable»"));
    }

    // ------------------------------------------------------------- GENERADOR

    @Test
    void se_genera_una_clase_embeddable_de_verdad() {
        atributo(cliente, "domicilio", "Direccion");

        String d = generar("Demo").archivos().get(BASE + "domain/Direccion.java");

        assertThat(d).contains("@Embeddable")
                .contains("public class Direccion {")
                .contains("@Column(name = \"calle\", length = 255)")
                .doesNotContain("@Id");
    }

    @Test
    void el_duenio_lo_lleva_embebido_con_sus_columnas_redirigidas() {
        atributo(cliente, "domicilio", "Direccion").setRequired(true);

        ProyectoGenerado p = generar("Demo");

        assertThat(p.archivos().get(BASE + "domain/Cliente.java"))
                .contains("@Embedded")
                .contains("@AttributeOverride(name = \"calle\", column = @Column(name = \"domicilio_calle\"))")
                .contains("private Direccion domicilio;")
                .doesNotContain("private String domicilioCalle");
        assertThat(p.archivos().get(BASE + "web/dto/ClienteDto.java"))
                .contains("@Valid @NotNull DireccionDto domicilio");
        assertThat(p.archivos().get(BASE + "web/dto/DireccionDto.java"))
                .contains("public static DireccionDto de(Direccion v)")
                .contains("public Direccion aEntidad()");
        assertThat(p.archivos().get(BASE + "service/ClienteService.java"))
                .contains("dto.domicilio().aEntidad()")
                .contains("DireccionDto.de(e.getDomicilio())");
    }

    @Test
    void la_composicion_leida_como_oo_tambien_genera_el_embeddable() {
        UmlClass envio = clase("Envio");
        UmlClass datos = clase("DatosEnvio");   // clase comun, sin estereotipo
        atributo(datos, "courier", "string");
        UmlRelationship r = rel(envio, datos, RelationKind.COMPOSITION, "1", "1");

        ProyectoGenerado p = GeneradorSpringBoot.generar(GeneradorSpringBoot.Opciones.por("Demo"),
                clases, atributos, List.of(), relaciones, Decisiones.constructor()
                        .con(Ambiguedad.TipoAmbiguedad.EMBEDDABLE, r.getId(), Ambiguedad.Lectura.OO).construir());

        // Antes esto daba un campo suelto `datosEnvioCourier` en Envio.
        assertThat(p.archivos()).containsKey(BASE + "domain/DatosEnvio.java");
        assertThat(p.archivos().get(BASE + "domain/Envio.java"))
                .contains("private DatosEnvio datosEnvio;")
                .doesNotContain("datosEnvioCourier");
    }

    @Test
    void emite_el_proyecto_con_valores_embebidos_listo_para_compilar_y_arrancar() throws java.io.IOException {
        atributo(cliente, "domicilio", "Direccion").setRequired(true);
        UmlClass proveedor = clase("Proveedor");
        atributo(proveedor, "razonSocial", "string");
        rel(proveedor, direccion, RelationKind.COMPOSITION, "1", "1");

        generar("Embebidos").escribirEn(java.nio.file.Path.of("target", "generado-embebidos"));
        assertThat(java.nio.file.Path.of("target", "generado-embebidos", "pom.xml")).exists();
    }
}
