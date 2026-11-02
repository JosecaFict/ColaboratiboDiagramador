package com.uagrm.colabmodel.codegen;

import com.uagrm.colabmodel.modelcore.mapping.ModeloCanonico;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SeguridadMongoTest {
    private static final String JAVA = "src/main/java/com/uagrm/generado/";

    private static ProyectoGenerado tiendaConUnico() {
        var modelo = ModelosDePrueba.tienda();
        modelo.atributos().stream().filter(a -> a.getName().equals("cantidad"))
                .forEach(a -> a.setUnique(true));
        return modelo.generar(GeneradorSpringBoot.Opciones.por("Tienda segura", GeneradorSpringBoot.Almacen.MONGO));
    }

    @Test
    void una_parte_con_unico_se_mapea_a_una_coleccion_con_indice_y_aviso() {
        var proyecto = tiendaConUnico();
        assertThat(proyecto.archivos().get(JAVA + "domain/LineaPedido.java"))
                .contains("@Document(collection = \"linea_pedidos\")")
                .contains("@Indexed(unique = true, sparse = true)\n    private Integer cantidad;");
        assertThat(proyecto.archivos().get(MapeoDocumental.RUTA))
                .contains("tiene atributos unicos que requieren un indice propio");
        assertThat(proyecto.archivos().get(JAVA + "repository/LineaPedidoRepository.java"))
                .contains("extends ColeccionMongo<LineaPedido>");
    }

    @Test
    void una_fk_uno_a_uno_lleva_un_indice_unico() {
        var modelo = ModeloCanonico.cargar("01-uno-a-uno.json");
        var proyecto = GeneradorSpringBoot.generar(
                GeneradorSpringBoot.Opciones.por("Uno a uno", GeneradorSpringBoot.Almacen.MONGO),
                modelo.clases(), modelo.atributos(), List.of(), modelo.relaciones());
        assertThat(proyecto.archivos().get(JAVA + "domain/Perfil.java"))
                .contains("@Indexed(unique = true, sparse = true)\n    private UUID usuarioId;");
    }

    @Test
    @EnabledIfSystemProperty(named = "mongo.seguridad.salida", matches = ".+")
    void escribe_modelo_con_cascada_separada_para_ejecutar_sus_pruebas_reales() throws Exception {
        tiendaConUnico().escribirEn(Path.of(System.getProperty("mongo.seguridad.salida")));
    }
}
