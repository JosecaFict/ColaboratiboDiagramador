package com.uagrm.colabmodel.ai;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class ValidadorPlanFinalTest {
    private static final JsonNodeFactory J = JsonNodeFactory.instance;

    @Test
    void ventas_detecta_inventario_desconectado_y_duplicados_con_nombres_distintos() {
        ObjectNode p = plan("Producto", "DetalleVenta", "Venta", "InventarioProducto");
        relacion(p, "Producto", "DetalleVenta", "tieneDetalles", "", "");
        relacion(p, "DetalleVenta", "Producto", "referenciaProducto", "", "");
        relacion(p, "Venta", "DetalleVenta", "contiene", "", "");
        assertThat(revisar(p)).anyMatch(e -> e.contains("Clase desconectada: InventarioProducto"))
                .anyMatch(e -> e.contains("Relaciones repetidas"));
    }

    @Test
    void banco_detecta_titular_incompleto_y_prestamos_separados_del_cliente() {
        ObjectNode p = plan("Cliente", "Cuenta", "Titular", "Prestamo", "Cuota");
        relacion(p, "Cliente", "Cuenta", "posee", "", "");
        relacion(p, "Cuenta", "Titular", "tiene", "", "");
        relacion(p, "Prestamo", "Cuota", "contiene", "", "");
        assertThat(revisar(p)).anyMatch(e -> e.contains("asociativa Titular"))
                .anyMatch(e -> e.contains("grupos sin conexion"));
    }

    @Test
    void conserva_dos_roles_invertidos_y_prerrequisitos_de_la_misma_clase() {
        ObjectNode p = plan("Asignatura", "Prerrequisito");
        relacion(p, "Asignatura", "Prerrequisito", "exige", "asignaturaActual", "requisitos");
        relacion(p, "Prerrequisito", "Asignatura", "requiere", "requisitosDe", "asignaturaRequerida");
        assertThat(revisar(p)).isEmpty();
    }

    @Test
    void detecta_presupuesto_final_y_datos_perdidos_despues_del_recorte() {
        ObjectNode p = plan("Animal", "Lote");
        relacion(p, "Animal", "Lote", "pertenece", "", "");
        ((ObjectNode) p.path("classes").get(0)).putArray("attributes")
                .addObject().put("name", "idAnimal").put("pk", true);
        var attrs = (com.fasterxml.jackson.databind.node.ArrayNode) p.path("classes").get(1).path("attributes");
        for (int i = 0; i < 48; i++) attrs.addObject().put("name", "dato" + i);
        assertThat(revisar(p)).anyMatch(e -> e.contains("solo tiene identidad"))
                .anyMatch(e -> e.contains("plan final requiere"));
    }

    @Test
    void turismo_no_confunde_participante_persona_con_asociativa() {
        ObjectNode p = plan("Reserva", "Participante");
        relacion(p, "Reserva", "Participante", "incluye", "", "");
        assertThat(revisar(p)).isEmpty();
    }

    @Test
    void viajero_titular_es_una_persona_no_una_titularidad_asociativa() {
        ObjectNode p = plan("Reserva", "ViajeroTitular");
        relacion(p, "Reserva", "ViajeroTitular", "solicitadaPor", "reservas", "titular");
        assertThat(revisar(p)).isEmpty();
    }

    @Test
    void una_clase_nueva_puede_referenciar_una_existente() {
        ObjectNode p = plan("Documento");
        relacion(p, "Documento", "ProyectoExistente", "pertenece", "", "");
        assertThat(ValidadorPlanFinal.revisar(new RespuestaIA(List.of(
                new ComandoPropuestoIA(EsquemaPlanUml.NOMBRE, p)), ""),
                java.util.Set.of("ProyectoExistente"))).isEmpty();
    }

    @Test
    void los_roles_no_justifican_limitar_la_cuenta_a_una_sola_transferencia() {
        ObjectNode p = plan("Transferencia", "Cuenta");
        relacion(p, "Transferencia", "Cuenta", "debita", "salientes", "origen");
        relacion(p, "Transferencia", "Cuenta", "acredita", "entrantes", "destino");
        assertThat(revisar(p)).anyMatch(e -> e.contains("multiples movimientos historicos"));
        for (var r : p.path("relations")) ((ObjectNode) r)
                .put("sourceMultiplicity", "0..*").put("targetMultiplicity", "1");
        assertThat(revisar(p)).isEmpty();
    }

    private static ObjectNode plan(String... nombres) {
        ObjectNode p = J.objectNode();
        var clases = p.putArray("classes");
        for (String nombre : nombres) {
            var c = clases.addObject().put("name", nombre);
            var attrs = c.putArray("attributes");
            attrs.addObject().put("name", "id" + nombre).put("pk", true);
            attrs.addObject().put("name", "datoPropio");
        }
        p.putArray("relations"); p.putArray("audit");
        return p;
    }

    @Test
    void prerrequisito_sin_datos_es_valido_si_tiene_dos_roles_completos() {
        ObjectNode p = plan("Asignatura", "Prerrequisito");
        var atributos = ((ObjectNode) p.path("classes").get(1)).putArray("attributes");
        atributos.addObject().put("name", "idPrerrequisito").put("pk", true);
        relacion(p, "Asignatura", "Prerrequisito", "exige", "actual", "requisitos");
        relacion(p, "Prerrequisito", "Asignatura", "requiere", "requisitosDe", "requerida");
        assertThat(revisar(p)).isEmpty();
        assertThat(ValidadorPlanFinal.asociativaSinDatosValida(p, "Prerrequisito")).isTrue();
        ((ObjectNode) p.path("relations").get(1)).put("sourceRole", "requisitos").put("targetRole", "actual");
        assertThat(revisar(p)).anyMatch(e -> e.contains("solo tiene identidad"));
    }

    @Test
    void detalle_itinerario_necesita_su_cabecera_aunque_tenga_dos_conexiones() {
        ObjectNode p = plan("Itinerario", "Actividad", "Destino", "DetalleItinerario");
        relacion(p, "Itinerario", "Actividad", "incluye", "", "");
        relacion(p, "DetalleItinerario", "Actividad", "referencia", "", "");
        relacion(p, "DetalleItinerario", "Destino", "ocurreEn", "", "");
        assertThat(revisar(p)).anyMatch(e -> e.contains("no identifica su cabecera Itinerario"));
        relacion(p, "Itinerario", "DetalleItinerario", "contiene", "", "");
        assertThat(revisar(p)).isEmpty();
    }

    @Test
    void aplicacion_debe_identificar_producto_y_no_solo_animal() {
        ObjectNode p = plan("AplicacionProducto", "Animal", "ProductoSanitario");
        relacion(p, "AplicacionProducto", "Animal", "trata", "", "");
        assertThat(revisar(p)).anyMatch(e -> e.contains("no identifica el producto aplicado"));
        relacion(p, "AplicacionProducto", "ProductoSanitario", "utiliza", "", "");
        assertThat(revisar(p)).isEmpty();
    }

    @Test
    void parentesco_se_expresa_con_roles_de_animal_y_no_con_entidades_duplicadas() {
        ObjectNode p = plan("Animal", "PadreAnimal");
        relacion(p, "Animal", "PadreAnimal", "tiene", "", "");
        assertThat(revisar(p)).anyMatch(e -> e.contains("rol familiar"));
        ObjectNode recursivo = plan("Animal");
        relacion(recursivo, "Animal", "Animal", "tienePadre", "descendientes", "padre");
        relacion(recursivo, "Animal", "Animal", "tieneMadre", "descendientes", "madre");
        assertThat(revisar(recursivo)).isEmpty();
    }

    private static void relacion(ObjectNode p, String s, String t, String nombre, String rolS, String rolT) {
        ((com.fasterxml.jackson.databind.node.ArrayNode) p.path("relations")).addObject()
                .put("source", s).put("target", t).put("name", nombre).put("kind", "ASSOCIATION")
                .put("sourceRole", rolS).put("targetRole", rolT)
                .put("sourceMultiplicity", "1").put("targetMultiplicity", "0..*");
    }

    private static List<String> revisar(ObjectNode p) {
        return ValidadorPlanFinal.revisar(new RespuestaIA(List.of(
                new ComandoPropuestoIA(EsquemaPlanUml.NOMBRE, p)), ""));
    }
}
