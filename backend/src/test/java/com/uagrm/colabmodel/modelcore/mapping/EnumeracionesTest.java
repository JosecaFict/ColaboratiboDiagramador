package com.uagrm.colabmodel.modelcore.mapping;

import com.uagrm.colabmodel.codegen.GeneradorSpringBoot;
import com.uagrm.colabmodel.codegen.ProyectoGenerado;
import com.uagrm.colabmodel.modelcore.metamodel.*;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Enumeraciones: una clase «enumeration» usada como tipo de un atributo.
 *
 * Hasta esta version `enum` figuraba en la tabla de tipos del documento pero
 * no existia en el codigo: un atributo con ese tipo caia a `varchar` sin
 * restriccion y con una advertencia de "tipo desconocido".
 */
class EnumeracionesTest {

    private static final String BASE = "src/main/java/com/uagrm/generado/";
    private final UUID diagrama = UUID.randomUUID();
    private final List<UmlClass> clases = new ArrayList<>();
    private final List<UmlAttribute> atributos = new ArrayList<>();
    private final List<UmlRelationship> relaciones = new ArrayList<>();

    private UmlClass clase(String nombre) {
        UmlClass c = new UmlClass();
        c.setId(UUID.randomUUID());
        c.setDiagramId(diagrama);
        c.setName(nombre);
        clases.add(c);
        return c;
    }

    private UmlClass enumeracion(String nombre, String... literales) {
        UmlClass e = clase(nombre);
        e.setStereotype(Enumeraciones.ESTEREOTIPO);
        for (String l : literales) atributo(e, l, "string");
        return e;
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

    private ModeloLogico derivar() {
        return DerivadorLogico.derivar(clases, atributos, relaciones);
    }

    private ProyectoGenerado generar() {
        return GeneradorSpringBoot.generar(GeneradorSpringBoot.Opciones.por("Demo"),
                clases, atributos, List.of(), relaciones);
    }

    private static ModeloLogico.Columna columna(ModeloLogico m, String tabla, String col) {
        return m.tablas().stream().filter(t -> t.nombre().equals(tabla)).findFirst().orElseThrow()
                .columnas().stream().filter(c -> c.nombre().equals(col)).findFirst().orElseThrow();
    }

    // ------------------------------------------------------------- DERIVACION

    @Test
    void la_enumeracion_no_genera_tabla() {
        enumeracion("EstadoPrestamo", "Activo", "Devuelto");

        // Es un tipo: no tiene filas propias.
        assertThat(derivar().tablas()).extracting(ModeloLogico.Tabla::nombre)
                .doesNotContain("estado_prestamo");
    }

    @Test
    void un_atributo_de_tipo_enumeracion_lleva_sus_literales_normalizados() {
        enumeracion("EstadoPrestamo", "Activo", "En préstamo", "Devuelto");
        UmlClass prestamo = clase("Prestamo");
        atributo(prestamo, "estado", "EstadoPrestamo").setRequired(true);

        ModeloLogico m = derivar();
        ModeloLogico.Columna estado = columna(m, "prestamo", "estado");

        // El mismo texto es la constante de Java y el valor en la base: si no
        // coincidieran, leer una fila tiraria una excepcion.
        assertThat(estado.enumeracion().literales()).containsExactly("ACTIVO", "EN_PRESTAMO", "DEVUELTO");
        assertThat(estado.tipo()).isEqualTo("varchar(50)");
        assertThat(m.advertencias()).noneMatch(a -> a.contains("Tipo desconocido"));
    }

    @Test
    void los_literales_repetidos_despues_de_normalizar_quedan_una_vez_y_se_avisa() {
        enumeracion("Estado", "Activo", "ACTIVO", "activo");
        atributo(clase("Prestamo"), "estado", "Estado");

        ModeloLogico m = derivar();

        assertThat(columna(m, "prestamo", "estado").enumeracion().literales()).containsExactly("ACTIVO");
        assertThat(m.advertencias()).anyMatch(a -> a.contains("repite el literal ACTIVO"));
    }

    @Test
    void una_enumeracion_sin_literales_no_restringe_nada_y_lo_dice() {
        enumeracion("Estado");
        atributo(clase("Prestamo"), "estado", "Estado");

        ModeloLogico m = derivar();

        assertThat(columna(m, "prestamo", "estado").enumeracion()).isNull();
        assertThat(m.advertencias()).anyMatch(a -> a.contains("no tiene literales"));
    }

    @Test
    void relacionar_una_clase_con_una_enumeracion_se_ignora_y_se_avisa() {
        UmlClass estado = enumeracion("Estado", "Activo");
        UmlClass prestamo = clase("Prestamo");
        UmlRelationship r = new UmlRelationship();
        r.setId(UUID.randomUUID());
        r.setDiagramId(diagrama);
        r.setSourceClassId(estado.getId());
        r.setTargetClassId(prestamo.getId());
        r.setKind(RelationKind.ASSOCIATION);
        r.setSourceMultiplicity("1");
        r.setTargetMultiplicity("0..*");
        relaciones.add(r);

        ModeloLogico m = derivar();

        // Una FK a una enumeracion apuntaria a una tabla que no existe.
        assertThat(m.tablas().stream().filter(t -> t.nombre().equals("prestamo")).findFirst().orElseThrow()
                .columnas()).noneMatch(c -> c.referencia() != null);
        assertThat(m.advertencias()).anyMatch(a -> a.contains("toca una enumeracion"));
    }

    @Test
    void los_literales_de_una_enumeracion_no_se_preguntan_como_ambiguedad() {
        UmlClass e = enumeracion("Estado", "Activo");
        atributos.get(0).setMultivalued(true);

        assertThat(Ambiguedades.detectar(clases, atributos, relaciones)).isEmpty();
        assertThat(e.getStereotype()).isEqualTo("enumeration");
    }

    // ------------------------------------------------------------- GENERADOR

    @Test
    void se_genera_un_enum_de_java_con_los_mismos_literales() {
        enumeracion("EstadoPrestamo", "Activo", "Devuelto");
        atributo(clase("Prestamo"), "estado", "EstadoPrestamo");

        assertThat(generar().archivos().get(BASE + "domain/EstadoPrestamo.java"))
                .contains("public enum EstadoPrestamo {")
                .contains("ACTIVO,")
                .contains("DEVUELTO");
    }

    @Test
    void la_entidad_lo_guarda_por_nombre_y_no_por_posicion() {
        enumeracion("EstadoPrestamo", "Activo", "Devuelto");
        atributo(clase("Prestamo"), "estado", "EstadoPrestamo").setRequired(true);

        String entidad = generar().archivos().get(BASE + "domain/Prestamo.java");

        // ORDINAL guardaria la posicion: insertar un literal en el medio
        // cambiaria el significado de todas las filas ya guardadas.
        assertThat(entidad)
                .contains("@Enumerated(EnumType.STRING)")
                .contains("@Column(name = \"estado\", nullable = false, length = 50)")
                .contains("private EstadoPrestamo estado;");
    }

    @Test
    void la_base_rechaza_un_literal_que_no_existe_con_un_check() {
        enumeracion("EstadoPrestamo", "Activo", "Devuelto");
        atributo(clase("Prestamo"), "estado", "EstadoPrestamo").setRequired(true);

        // Regla 9, tercera capa: aunque alguien escriba sin pasar por la API.
        assertThat(generar().archivos().get("src/main/resources/db/migration/V1__init.sql"))
                .contains("estado varchar(50) NOT NULL CHECK (estado IN ('ACTIVO', 'DEVUELTO'))");
    }

    @Test
    void el_dto_y_los_metodos_usan_el_enum_como_tipo() {
        enumeracion("EstadoPrestamo", "Activo", "Devuelto");
        UmlClass prestamo = clase("Prestamo");
        atributo(prestamo, "estado", "EstadoPrestamo");
        UmlMethod cambiar = new UmlMethod();
        cambiar.setId(UUID.randomUUID());
        cambiar.setClassId(prestamo.getId());
        cambiar.setName("cambiarEstado");
        cambiar.setReturnType("EstadoPrestamo");
        cambiar.setParams("[{\"name\": \"nuevo\", \"type\": \"EstadoPrestamo\"}]");

        ProyectoGenerado p = GeneradorSpringBoot.generar(GeneradorSpringBoot.Opciones.por("Demo"),
                clases, atributos, List.of(cambiar), relaciones);

        assertThat(p.archivos().get(BASE + "web/dto/PrestamoDto.java"))
                .contains("import com.uagrm.generado.domain.EstadoPrestamo;")
                .contains("EstadoPrestamo estado");
        assertThat(p.archivos().get(BASE + "service/PrestamoService.java"))
                .contains("import com.uagrm.generado.domain.EstadoPrestamo;")
                .contains("public EstadoPrestamo cambiarEstado(EstadoPrestamo nuevo)");
    }

    @Test
    void emite_el_proyecto_con_enumeraciones_listo_para_compilar() throws java.io.IOException {
        enumeracion("EstadoPrestamo", "Activo", "En préstamo", "Devuelto");
        UmlClass prestamo = clase("Prestamo");
        atributo(prestamo, "estado", "EstadoPrestamo").setRequired(true);
        UmlAttribute historial = atributo(prestamo, "historial", "EstadoPrestamo");
        historial.setMultivalued(true);

        ProyectoGenerado p = GeneradorSpringBoot.generar(GeneradorSpringBoot.Opciones.por("Enumeraciones"),
                clases, atributos, List.of(), relaciones,
                Decisiones.constructor().con(Ambiguedad.TipoAmbiguedad.MULTIVALUED, historial.getId(),
                        Ambiguedad.Lectura.OO).construir());

        // Una coleccion de valores enumerados tambien se guarda por nombre.
        assertThat(p.archivos().get(BASE + "domain/Prestamo.java"))
                .contains("@Enumerated(EnumType.STRING)\n    @Column(name = \"historial\")")
                .contains("private List<EstadoPrestamo> historial = new ArrayList<>();");

        // Se deja en target/ para compilarlo y arrancarlo encima.
        p.escribirEn(java.nio.file.Path.of("target", "generado-enumeraciones"));
    }
}
