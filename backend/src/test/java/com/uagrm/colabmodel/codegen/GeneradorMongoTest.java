package com.uagrm.colabmodel.codegen;

import com.uagrm.colabmodel.codegen.GeneradorSpringBoot.Almacen;
import com.uagrm.colabmodel.modelcore.mapping.Decisiones;
import com.uagrm.colabmodel.modelcore.mapping.ModeloCanonico;
import com.uagrm.colabmodel.modelcore.mapping.ModeloDocumental;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-035: la variante MongoDB y la Vista Documental. Las reglas de mapeo
 * son las de DISENO-FASE-2.md §A, una por prueba.
 */
class GeneradorMongoTest {

    private static final String JAVA = "src/main/java/com/uagrm/generado/";

    private static ProyectoGenerado tienda() {
        return ModelosDePrueba.tienda().generar(GeneradorSpringBoot.Opciones.por("Tienda", Almacen.MONGO));
    }

    private static ModeloDocumental vistaDe(String canonico) {
        ModeloCanonico.Cargado m = ModeloCanonico.cargar(canonico);
        return GeneradorSpringBoot.documental("Demo", m.clases(), m.atributos(), List.of(), m.relaciones(),
                Decisiones.ninguna());
    }

    private static ModeloDocumental vistaDeLaTienda() {
        ModelosDePrueba.Modelo t = ModelosDePrueba.tienda();
        return GeneradorSpringBoot.documental("Tienda", t.clases(), t.atributos(), List.of(), t.relaciones(),
                Decisiones.ninguna());
    }

    @Test
    void la_variante_mongo_no_trae_ddl_ni_jpa() {
        ProyectoGenerado p = tienda();

        assertThat(p.archivos()).doesNotContainKeys(GeneradorSpringBoot.RUTA_DDL, GeneradorSpringBoot.RUTA_AUDITORIA)
                .containsKeys(MapeoDocumental.RUTA, JAVA + "config/MongoConfig.java");
        assertThat(p.archivos().get("pom.xml")).contains("spring-boot-starter-data-mongodb")
                .doesNotContain("data-jpa").doesNotContain("flyway");
        assertThat(p.archivos().get("docker-compose.yml")).contains("image: mongo:7");
        assertThat(p.archivos().values()).noneMatch(c -> c.contains("jakarta.persistence"));
    }

    @Test
    void la_composicion_guarda_la_parte_dentro_del_todo() {
        ProyectoGenerado p = tienda();

        assertThat(p.archivos().get(JAVA + "domain/Pedido.java"))
                .contains("@Document(collection = \"pedidos\")")
                .contains("private List<LineaPedido> lineaPedidos = new ArrayList<>();");
        assertThat(p.archivos().get(JAVA + "domain/LineaPedido.java"))
                .doesNotContain("@Document")
                .contains("@Transient\n    private UUID pedidoId;")
                .contains("private UUID productoId;");
        assertThat(p.archivos().get(JAVA + "repository/LineaPedidoRepository.java"))
                .contains("extends IncrustadoMongo<LineaPedido, Pedido>")
                .contains("super(padres, \"lineaPedidos\", \"pedidoId\", true, \"LineaPedido\",");
    }

    @Test
    void sin_claves_foraneas_la_integridad_la_cuida_el_service() {
        ProyectoGenerado p = tienda();

        // Lo que apunta a Producto vive incrustado en Pedido: se busca ahi adentro.
        assertThat(p.archivos().get(JAVA + "service/ProductoService.java"))
                .contains("pedidoRepository.existeConCampo(\"lineaPedidos.productoId\", id)")
                .contains("LineaPedido.producto todavia lo referencia");
        assertThat(p.archivos().get(JAVA + "service/ClienteService.java"))
                .contains("pedidoRepository.existeConCampo(\"clienteId\", id)");
        assertThat(p.archivos().get(JAVA + "service/PedidoService.java"))
                .contains("!clienteRepository.existe(entidad.getClienteId())");
    }

    @Test
    void la_herencia_va_en_una_coleccion_con_tipo() {
        ModeloCanonico.Cargado m = ModeloCanonico.cargar("04-herencia.json");
        ProyectoGenerado p = GeneradorSpringBoot.generar(GeneradorSpringBoot.Opciones.por("Demo", Almacen.MONGO),
                m.clases(), m.atributos(), List.of(), m.relaciones());

        assertThat(p.archivos().get(JAVA + "domain/Empleado.java"))
                .contains("@Document(collection = \"personas\")")
                .contains("@TypeAlias(\"Empleado\")")
                .contains("public class Empleado extends Persona {");
        assertThat(p.archivos().get(JAVA + "repository/EmpleadoRepository.java"))
                .contains("super(plantilla, Empleado.class, List.of(\"Empleado\"));");
        assertThat(p.archivos().get(JAVA + "config/MongoConfig.java"))
                .contains("new DefaultMongoTypeMapper(\"_tipo\", contexto)");
    }

    /**
     * Probado contra MongoDB 7 real: con dos convertidores propios
     * BigDecimal <-> Decimal128 el precio se guardaba como TEXTO, y
     * `sort=precio,desc` daba [2.5, 10]. Spring Data MongoDB 4.5 lo decide con
     * la opcion `bigDecimal(DECIMAL128)`, no con convertidores.
     */
    @Test
    void los_decimales_se_guardan_como_numero_y_no_como_texto() {
        String config = tienda().archivos().get(JAVA + "config/MongoConfig.java");

        assertThat(config)
                .contains("MongoCustomConversions.create(c -> c.bigDecimal(BigDecimalRepresentation.DECIMAL128))")
                .doesNotContain("implements Converter<BigDecimal, Decimal128>")
                .doesNotContain("@WritingConverter");
    }

    // ------------------------------------------------------ VISTA DOCUMENTAL

    @Test
    void la_vista_muestra_la_parte_incrustada_y_las_referencias() {
        ModeloDocumental v = vistaDeLaTienda();

        assertThat(v.colecciones()).extracting(ModeloDocumental.Coleccion::nombre)
                .containsExactlyInAnyOrder("clientes", "productos", "pedidos");
        ModeloDocumental.Coleccion pedidos = v.colecciones().stream()
                .filter(c -> c.nombre().equals("pedidos")).findFirst().orElseThrow();
        assertThat(pedidos.entidad()).isEqualTo("Pedido");
        assertThat(pedidos.incrustados())
                .containsExactly(new ModeloDocumental.Incrustado("lineaPedidos", "LineaPedido", true));
        assertThat(pedidos.referencias())
                .containsExactly(new ModeloDocumental.Referencia("clienteId", "clientes", false));
        assertThat(pedidos.campos()).contains(new ModeloDocumental.Campo("fecha", "date", true));
        assertThat(pedidos.discriminador()).isNull();
    }

    @Test
    void el_recorte_de_la_parte_incrustada_se_avisa_en_la_vista_y_en_el_readme() {
        // D-02: sin transacciones, trasladar una parte entre todos responde 409.
        // Lo que no se hace se dice (Regla 10): en /documental y en el README del zip.
        assertThat(vistaDeLaTienda().advertencias())
                .anyMatch(a -> a.contains("LineaPedido") && a.contains("no se puede trasladar") && a.contains("409"));
        assertThat(tienda().archivos().get("README.md")).contains("no se puede trasladar").contains("409");
    }

    @Test
    void la_vista_es_la_misma_que_viaja_en_el_zip() {
        String enElZip = tienda().archivos().get(MapeoDocumental.RUTA);

        assertThat(enElZip).contains("\"nombre\" : \"pedidos\"").contains("\"incrustados\"")
                .contains("\"discriminador\" : null");
    }

    @Test
    void la_herencia_es_una_coleccion_con_discriminador() {
        ModeloDocumental v = vistaDe("04-herencia.json");

        assertThat(v.colecciones()).singleElement().satisfies(c -> {
            assertThat(c.nombre()).isEqualTo("personas");
            assertThat(c.discriminador()).isEqualTo("_tipo");
            // Los campos de las hijas tambien viven en esa coleccion.
            assertThat(c.campos()).extracting(ModeloDocumental.Campo::nombre).contains("nombre", "cargo");
        });
    }

    @Test
    void la_n_a_m_es_un_arreglo_de_ids_en_el_primer_extremo() {
        ModeloDocumental v = vistaDe("03-muchos-a-muchos.json");

        assertThat(v.colecciones()).flatExtracting(ModeloDocumental.Coleccion::referencias)
                .anyMatch(ModeloDocumental.Referencia::multiple);
        assertThat(v.advertencias()).anyMatch(a -> a.contains("arreglo de ids"));
    }

    @Test
    void la_clase_asociativa_es_una_coleccion_con_sus_dos_referencias() {
        ModeloDocumental v = vistaDe("05-clase-asociativa.json");

        assertThat(v.colecciones()).anyMatch(c -> c.referencias().size() == 2
                && c.referencias().stream().noneMatch(ModeloDocumental.Referencia::multiple));
    }
}
