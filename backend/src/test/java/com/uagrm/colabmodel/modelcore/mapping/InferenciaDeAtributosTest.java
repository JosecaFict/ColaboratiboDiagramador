package com.uagrm.colabmodel.modelcore.mapping;

import com.uagrm.colabmodel.modelcore.mapping.InferenciaDeAtributos.Confianza;
import com.uagrm.colabmodel.modelcore.mapping.InferenciaDeAtributos.Rol;
import com.uagrm.colabmodel.modelcore.mapping.InferenciaDeAtributos.Sugerencia;
import com.uagrm.colabmodel.modelcore.metamodel.UmlClass;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tipo y rol de un atributo deducidos del nombre.
 *
 * Es la base para leer una foto de un modelo: la IA lee "idMaterial" y
 * "fechaPrestamo", y estas reglas proponen que es cada cosa.
 */
class InferenciaDeAtributosTest {

    private static UmlClass clase(String nombre) {
        UmlClass c = new UmlClass();
        c.setId(UUID.randomUUID());
        c.setDiagramId(UUID.randomUUID());
        c.setName(nombre);
        return c;
    }

    private final UmlClass material = clase("Material");
    private final UmlClass prestamo = clase("Prestamo");
    private final UmlClass libros = clase("Libros");
    private final List<UmlClass> diagrama = List.of(material, prestamo, libros);

    private Sugerencia en(UmlClass duenio, String nombre) {
        return InferenciaDeAtributos.sugerir(nombre, null, duenio, diagrama);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "fechaNacimiento,   date",
            "fecha_nac,         date",
            "fechaHoraRegistro, datetime",
            "created_at,        datetime",
            "precio,            decimal",
            "montoTotal,        decimal",
            "cantidad,          int",
            "nroPaginas,        int",
            "esActivo,          boolean",
            "activo,            boolean",
            "tienePrestamos,    boolean",
            "descripcion,       text",
            "nombre,            string",
            "email,             string",
    })
    void el_tipo_sale_del_nombre(String nombre, String tipo) {
        assertThat(en(prestamo, nombre).dataType()).isEqualTo(tipo);
    }

    @Test
    void un_numero_de_telefono_es_texto_y_no_un_entero() {
        // Lleva ceros adelante y nunca se suma: como int, 0712 se guardaria 712.
        Sugerencia s = en(prestamo, "numeroTelefono");
        assertThat(s.dataType()).isEqualTo("string");
        assertThat(s.motivo()).contains("ceros adelante");
    }

    @Test
    void el_dinero_es_decimal_con_confianza_alta_y_explica_por_que_no_float() {
        Sugerencia s = en(prestamo, "multa");
        assertThat(s.dataType()).isEqualTo("decimal");
        assertThat(s.confianza()).isEqualTo(Confianza.ALTA);
        assertThat(s.motivo()).contains("float");
    }

    @Test
    void sin_pistas_sugiere_string_pero_con_confianza_baja() {
        Sugerencia s = en(prestamo, "xyz");
        assertThat(s.dataType()).isEqualTo("string");
        assertThat(s.confianza()).isEqualTo(Confianza.BAJA);
    }

    // ------------------------------------------------------------ id y FK

    @Test
    void id_solo_es_la_identidad_que_ya_crea_la_herramienta() {
        assertThat(en(prestamo, "id").rol()).isEqualTo(Rol.IDENTIDAD);
    }

    @Test
    void id_material_dentro_de_material_es_su_identidad_no_un_atributo() {
        Sugerencia s = en(material, "idMaterial");

        assertThat(s.rol()).isEqualTo(Rol.IDENTIDAD);
        assertThat(s.motivo()).contains("duplicaria");
    }

    @Test
    void id_material_dentro_de_prestamo_es_una_relacion_con_material() {
        Sugerencia s = en(prestamo, "idMaterial");

        // Como atributo quedaria una columna id_material suelta, sin integridad,
        // ademas de la material_id que deriva la relacion.
        assertThat(s.rol()).isEqualTo(Rol.RELACION);
        assertThat(s.claseRelacionada()).isEqualTo("Material");
        assertThat(s.claseRelacionadaId()).isEqualTo(material.getId());
    }

    @ParameterizedTest
    @CsvSource({"material_id", "IDMaterial", "fk_material", "idMateriales"})
    void la_fk_se_reconoce_escrita_de_varias_formas(String nombre) {
        assertThat(en(prestamo, nombre).claseRelacionada()).isEqualTo("Material");
    }

    @Test
    void el_plural_de_la_clase_tambien_cuenta() {
        // La clase se llama "Libros" y el atributo "idLibro".
        assertThat(en(prestamo, "idLibro").claseRelacionada()).isEqualTo("Libros");
    }

    @Test
    void una_fk_hacia_una_clase_que_no_existe_queda_como_atributo_y_se_avisa() {
        Sugerencia s = en(prestamo, "idProveedor");

        assertThat(s.rol()).isEqualTo(Rol.ATRIBUTO);
        assertThat(s.dataType()).isEqualTo("uuid");
        assertThat(s.motivo()).contains("no hay ninguna clase");
    }

    @Test
    void un_codigo_de_negocio_se_sugiere_como_clave_natural() {
        Sugerencia s = en(libros, "isbn");

        assertThat(s.rol()).isEqualTo(Rol.CLAVE_NATURAL);
        assertThat(s.dataType()).isEqualTo("string");
        assertThat(s.motivo()).contains("UNIQUE NOT NULL");
    }

    // ------------------------------------------------------------ tipo escrito

    @Test
    void lo_escrito_en_el_modelo_manda_sobre_el_nombre() {
        // Se llama "cantidad", pero la foto dice "real".
        Sugerencia s = InferenciaDeAtributos.sugerir("cantidad", "real", prestamo, diagrama);

        assertThat(s.dataType()).isEqualTo("decimal");
        assertThat(s.confianza()).isEqualTo(Confianza.ALTA);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({"VARCHAR(50), string", "integer, int", "bigint, long", "bit, boolean",
                "timestamp, datetime", "float, decimal", "entero, int"})
    void los_tipos_escritos_se_traducen(String escrito, String tipo) {
        assertThat(InferenciaDeAtributos.sugerir("x", escrito, prestamo, diagrama).dataType()).isEqualTo(tipo);
    }

    @Test
    void un_tipo_escrito_desconocido_cae_a_la_pista_del_nombre() {
        assertThat(InferenciaDeAtributos.sugerir("fechaAlta", "blob", prestamo, diagrama).dataType())
                .isEqualTo("date");
    }

    @Test
    void reconoce_las_enumeraciones_y_los_valores_del_modelo() {
        UmlClass estado = clase("EstadoPrestamo");
        estado.setStereotype(Enumeraciones.ESTEREOTIPO);
        UmlClass direccion = clase("Direccion");
        direccion.setStereotype(Incrustables.ESTEREOTIPO);
        List<UmlClass> con = List.of(prestamo, estado, direccion);

        assertThat(InferenciaDeAtributos.sugerir("x", "EstadoPrestamo", prestamo, con).dataType())
                .isEqualTo("EstadoPrestamo");
        assertThat(InferenciaDeAtributos.sugerir("direccion", null, prestamo, con).dataType())
                .isEqualTo("Direccion");
    }

    @Test
    void la_fk_se_reconoce_aunque_el_tipo_venga_escrito() {
        // Un SQL o un XMI siempre traen el tipo: "material_id uuid" sigue
        // siendo una relacion, no un atributo uuid suelto.
        Sugerencia s = InferenciaDeAtributos.sugerir("material_id", "uuid", prestamo, diagrama);

        assertThat(s.rol()).isEqualTo(Rol.RELACION);
        assertThat(s.claseRelacionada()).isEqualTo("Material");
    }

    @Test
    void la_clave_natural_con_tipo_escrito_respeta_el_tipo() {
        Sugerencia s = InferenciaDeAtributos.sugerir("codigo", "int", material, diagrama);

        assertThat(s.rol()).isEqualTo(Rol.CLAVE_NATURAL);
        assertThat(s.dataType()).isEqualTo("int");
    }

    @Test
    void una_fk_hacia_una_clase_ausente_con_tipo_escrito_respeta_el_tipo() {
        assertThat(InferenciaDeAtributos.sugerir("idProveedor", "bigint", prestamo, diagrama).dataType())
                .isEqualTo("long");
    }
}
