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
 * REGLA 4, opcion SINGLE_TABLE: toda la jerarquia en la tabla de la raiz.
 *
 * Jerarquia de prueba, de tres niveles a proposito:
 *   Persona (abstracta) <- Empleado <- Gerente
 *   Persona             <- Cliente
 */
class TablaUnicaTest {

    private static final String BASE = "src/main/java/com/uagrm/generado/";
    private final UUID diagrama = UUID.randomUUID();
    private final List<UmlClass> clases = new ArrayList<>();
    private final List<UmlAttribute> atributos = new ArrayList<>();
    private final List<UmlRelationship> relaciones = new ArrayList<>();
    private UmlClass persona;
    private UmlClass empleado;

    private UmlClass clase(String nombre) {
        UmlClass c = new UmlClass();
        c.setId(UUID.randomUUID());
        c.setDiagramId(diagrama);
        c.setName(nombre);
        clases.add(c);
        return c;
    }

    private UmlAttribute atributo(UmlClass c, String nombre) {
        UmlAttribute a = new UmlAttribute();
        a.setId(UUID.randomUUID());
        a.setClassId(c.getId());
        a.setName(nombre);
        a.setDataType("string");
        a.setOrdinal(atributos.size());
        atributos.add(a);
        return a;
    }

    private UmlRelationship rel(UmlClass origen, UmlClass destino, RelationKind kind) {
        UmlRelationship r = new UmlRelationship();
        r.setId(UUID.randomUUID());
        r.setDiagramId(diagrama);
        r.setSourceClassId(origen.getId());
        r.setTargetClassId(destino.getId());
        r.setKind(kind);
        relaciones.add(r);
        return r;
    }

    @BeforeEach
    void jerarquia() {
        persona = clase("Persona");
        persona.setAbstract(true);
        persona.setInheritanceStrategy(InheritanceStrategy.SINGLE_TABLE);
        atributo(persona, "nombre").setRequired(true);
        atributo(persona, "ci").setUnique(true);

        empleado = clase("Empleado");
        atributo(empleado, "cargo").setRequired(true);
        UmlClass gerente = clase("Gerente");
        atributo(gerente, "bono");
        UmlClass cliente = clase("Cliente");
        atributo(cliente, "nit");

        rel(empleado, persona, RelationKind.GENERALIZATION);
        rel(gerente, empleado, RelationKind.GENERALIZATION);
        rel(cliente, persona, RelationKind.GENERALIZATION);
    }

    private ModeloLogico derivar() {
        return DerivadorLogico.derivar(clases, atributos, relaciones);
    }

    private ProyectoGenerado generar(String nombre) {
        return GeneradorSpringBoot.generar(GeneradorSpringBoot.Opciones.por(nombre),
                clases, atributos, List.of(), relaciones);
    }

    private static ModeloLogico.Columna col(ModeloLogico.Tabla t, String nombre) {
        return t.columnas().stream().filter(c -> c.nombre().equals(nombre)).findFirst().orElseThrow();
    }

    // ------------------------------------------------------------- DERIVACION

    @Test
    void toda_la_jerarquia_queda_en_la_tabla_de_la_raiz() {
        ModeloLogico m = derivar();

        assertThat(m.tablas()).extracting(ModeloLogico.Tabla::nombre).containsExactly("persona");
        assertThat(m.tablas().get(0).columnas()).extracting(ModeloLogico.Columna::nombre)
                .containsExactly("id", "nombre", "ci", "cargo", "nit", "bono", "tipo");
    }

    @Test
    void las_columnas_de_las_subclases_admiten_null_y_el_obligatorio_se_avisa() {
        ModeloLogico m = derivar();
        ModeloLogico.Tabla persona = m.tablas().get(0);

        // Las filas de Cliente no tienen cargo: la columna tiene que admitir NULL.
        assertThat(col(persona, "cargo").nullable()).isTrue();
        // La de la raiz conserva su NOT NULL: todas las filas son personas.
        assertThat(col(persona, "nombre").nullable()).isFalse();
        assertThat(m.advertencias()).anyMatch(a -> a.contains("Empleado.cargo es obligatorio"));
    }

    @Test
    void el_discriminador_solo_acepta_clases_que_pueden_tener_filas() {
        ModeloLogico.Tabla persona = derivar().tablas().get(0);

        // Persona es abstracta: ninguna fila puede ser "PERSONA".
        assertThat(col(persona, "tipo").nullable()).isFalse();
        assertThat(col(persona, "tipo").enumeracion().literales())
                .containsExactlyInAnyOrder("EMPLEADO", "GERENTE", "CLIENTE");
    }

    @Test
    void cada_miembro_sabe_que_columnas_declaro() {
        ModeloLogico.TablaUnica tu = derivar().tablas().get(0).tablaUnica();

        assertThat(tu.discriminador()).isEqualTo("tipo");
        assertThat(tu.miembros()).extracting(ModeloLogico.Miembro::clase)
                .containsExactly("Persona", "Empleado", "Cliente", "Gerente");
        ModeloLogico.Miembro gerente = tu.miembros().stream()
                .filter(x -> x.clase().equals("Gerente")).findFirst().orElseThrow();
        assertThat(gerente.madre()).isEqualTo("Empleado");
        assertThat(gerente.columnas()).containsExactly("bono");
    }

    @Test
    void por_defecto_la_jerarquia_sigue_siendo_joined() {
        persona.setInheritanceStrategy(InheritanceStrategy.JOINED);

        assertThat(derivar().tablas()).extracting(ModeloLogico.Tabla::nombre)
                .contains("persona", "empleado", "gerente", "cliente");
    }

    @Test
    void una_subclase_con_relaciones_propias_mantiene_joined_y_se_avisa() {
        UmlClass sucursal = clase("Sucursal");
        UmlRelationship r = rel(empleado, sucursal, RelationKind.ASSOCIATION);
        r.setSourceMultiplicity("0..*");
        r.setTargetMultiplicity("1");

        ModeloLogico m = derivar();

        // Su FK a sucursal no tendria donde vivir sin volverse nula para todos.
        assertThat(m.tablas()).extracting(ModeloLogico.Tabla::nombre).contains("empleado");
        assertThat(m.advertencias()).anyMatch(a -> a.contains("se mantiene JOINED"));
    }

    // ------------------------------------------------------------- GENERADOR

    @Test
    void la_raiz_declara_single_table_y_su_discriminador() {
        String p = generar("Demo").archivos().get(BASE + "domain/Persona.java");

        assertThat(p)
                .contains("@Table(name = \"persona\")")
                .contains("@Inheritance(strategy = InheritanceType.SINGLE_TABLE)")
                .contains("@DiscriminatorColumn(name = \"tipo\", length = 31)")
                .contains("public abstract class Persona {")
                .doesNotContain("@DiscriminatorValue")
                .doesNotContain("private String cargo");
    }

    @Test
    void las_subclases_no_declaran_tabla_y_llevan_su_valor() {
        ProyectoGenerado p = generar("Demo");

        assertThat(p.archivos().get(BASE + "domain/Empleado.java"))
                .doesNotContain("@Table")
                .contains("@DiscriminatorValue(\"EMPLEADO\")")
                .contains("public class Empleado extends Persona {")
                .contains("private String cargo;");
        assertThat(p.archivos().get(BASE + "domain/Gerente.java"))
                .contains("@DiscriminatorValue(\"GERENTE\")")
                .contains("public class Gerente extends Empleado {");
    }

    @Test
    void el_obligatorio_que_perdio_la_base_vuelve_en_el_dto() {
        ProyectoGenerado p = generar("Demo");

        assertThat(p.archivos().get(BASE + "web/dto/EmpleadoDto.java"))
                .contains("@NotNull @Size(max = 255) String cargo");
        // El gerente arrastra los campos de toda su linea: Persona y Empleado.
        assertThat(p.archivos().get(BASE + "web/dto/GerenteDto.java"))
                .contains("String nombre").contains("String ci")
                .contains("@NotNull @Size(max = 255) String cargo").contains("String bono");
    }

    @Test
    void emite_el_proyecto_single_table_listo_para_compilar_y_arrancar() throws java.io.IOException {
        ProyectoGenerado p = generar("Tabla Unica");

        assertThat(p.archivos().get("src/main/resources/db/migration/V1__init.sql"))
                .contains("CREATE TABLE persona")
                .doesNotContain("CREATE TABLE empleado");
        p.escribirEn(java.nio.file.Path.of("target", "generado-tabla-unica"));
    }
}
