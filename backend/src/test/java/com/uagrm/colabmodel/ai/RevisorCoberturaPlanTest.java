package com.uagrm.colabmodel.ai;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class RevisorCoberturaPlanTest {
    private static final JsonNodeFactory J = JsonNodeFactory.instance;

    @Test
    void nombre_no_reemplaza_fecha_de_un_movimiento_historico() {
        ObjectNode p = plan("MovimientoAnimal", "Animal", "Lote");
        relacion(p, "MovimientoAnimal", "Animal");
        relacion(p, "MovimientoAnimal", "Lote");
        assertThat(revisar(p, "Historial de movimientos entre lotes con origen y destino"))
                .anyMatch(e -> e.contains("no tiene fecha"))
                .anyMatch(e -> e.contains("no distingue origen y destino"));
    }

    @Test
    void movimiento_con_fecha_y_roles_cubre_trazabilidad() {
        ObjectNode p = plan("MovimientoAnimal", "Animal", "Lote");
        atributo(p, 0, "fechaMovimiento", "datetime");
        relacion(p, "MovimientoAnimal", "Animal");
        relacion(p, "MovimientoAnimal", "Lote").put("targetRole", "origen");
        relacion(p, "MovimientoAnimal", "Lote").put("targetRole", "destino");
        assertThat(revisar(p, "Historial de movimientos entre lotes con origen y destino")).isEmpty();
    }

    @Test
    void referencia_academica_sin_asociacion_se_detecta_y_no_se_borra() {
        ObjectNode p = plan("Prerrequisito", "Asignatura", "DetallePlan");
        atributo(p, 0, "detallePlanId", "uuid");
        relacion(p, "Prerrequisito", "Asignatura");
        assertThat(revisar(p, "Plan academico"))
                .anyMatch(e -> e.contains("detallePlanId") && e.contains("sin asociacion"));
        relacion(p, "Prerrequisito", "DetallePlan");
        assertThat(revisar(p, "Plan academico")).isEmpty();
    }

    @Test
    void banco_no_confunde_conexion_al_grafo_con_identificacion_del_prestratario() {
        ObjectNode p = plan("Cliente", "Cuenta", "Titular", "Prestamo", "Cuota", "Pago");
        relacion(p, "Cliente", "Cuenta"); relacion(p, "Cuenta", "Titular");
        relacion(p, "Prestamo", "Cuota"); relacion(p, "Cuota", "Pago");
        assertThat(revisar(p, "Sistema bancario"))
                .anyMatch(e -> e.contains("Titular no identifica"))
                .anyMatch(e -> e.contains("cliente prestatario"));
        relacion(p, "Cliente", "Titular"); relacion(p, "Cliente", "Prestamo");
        assertThat(revisar(p, "Sistema bancario")).isEmpty();
    }

    @Test
    void garantia_por_unidad_vendida_necesita_trazabilidad_de_compra() {
        ObjectNode p = plan("Garantia", "Producto", "DetalleVenta");
        relacion(p, "Garantia", "Producto");
        assertThat(revisar(p, "Gestionar garantia de cada unidad vendida"))
                .anyMatch(e -> e.contains("compra concreta"));
        assertThat(revisar(p, "Catalogo de garantias del fabricante")).isEmpty();
        relacion(p, "Garantia", "DetalleVenta");
        assertThat(revisar(p, "Gestionar garantia de cada unidad vendida")).isEmpty();
    }

    @Test
    void pagos_requieren_importe_y_historial_fecha() {
        ObjectNode p = plan("Pago");
        assertThat(revisar(p, "Historial de pagos"))
                .anyMatch(e -> e.contains("importe"))
                .anyMatch(e -> e.contains("fecha"));
        atributo(p, 0, "monto", "decimal"); atributo(p, 0, "fechaPago", "datetime");
        assertThat(revisar(p, "Historial de pagos")).isEmpty();
    }

    @Test
    void no_obliga_a_catalogos_o_relaciones_a_tener_fecha() {
        assertThat(revisar(plan("Tratamiento", "Producto", "Prerrequisito"),
                "Catalogo de tratamientos y productos sin historial")).isEmpty();
    }

    @Test
    void movimiento_bancario_no_necesita_los_roles_que_corresponden_a_transferencia() {
        ObjectNode p = plan("Movimiento", "Cuenta", "Transferencia");
        atributo(p, 0, "fechaMovimiento", "datetime");
        atributo(p, 2, "fechaTransferencia", "datetime"); atributo(p, 2, "monto", "decimal");
        relacion(p, "Movimiento", "Cuenta");
        relacion(p, "Transferencia", "Cuenta").put("targetRole", "origen");
        relacion(p, "Transferencia", "Cuenta").put("targetRole", "destino");
        assertThat(revisar(p, "Sistema bancario con historial de movimientos y transferencias entre origen y destino"))
                .isEmpty();
        ((ObjectNode) p.path("relations").get(2)).remove("targetRole");
        assertThat(revisar(p, "Sistema bancario con historial de transferencias con origen y destino"))
                .anyMatch(e -> e.contains("Transferencia no distingue origen y destino"))
                .noneMatch(e -> e.contains("Movimiento no distingue"));
    }

    private static ObjectNode plan(String... nombres) {
        ObjectNode p = J.objectNode(); var cs = p.putArray("classes");
        for (String n : nombres) cs.addObject().put("name", n).putArray("attributes")
                .addObject().put("name", "nombre").put("dataType", "string");
        p.putArray("relations"); p.putArray("audit"); return p;
    }

    @Test
    void no_confunde_dos_roles_en_una_sola_relacion_con_dos_cuentas_de_transferencia() {
        ObjectNode p = plan("Transferencia", "Cuenta");
        atributo(p, 0, "fecha", "datetime"); atributo(p, 0, "monto", "decimal");
        relacion(p, "Transferencia", "Cuenta").put("sourceRole", "destino").put("targetRole", "origen");
        assertThat(revisar(p, "Historial de transferencias con origen y destino"))
                .anyMatch(e -> e.contains("no distingue origen y destino"));
        relacion(p, "Transferencia", "Cuenta").put("targetRole", "destino");
        assertThat(revisar(p, "Historial de transferencias con origen y destino")).isEmpty();
    }

    @Test
    void exige_inventario_sin_obligar_a_crear_una_clase_extra() {
        ObjectNode p = plan("Producto");
        assertThat(revisar(p, "Catalogo e inventario actual")).anyMatch(e -> e.contains("inventario no esta cubierto"));
        atributo(p, 0, "stockDisponible", "int");
        assertThat(revisar(p, "Catalogo e inventario actual")).isEmpty();
    }

    @Test
    void detecta_parentesco_solicitado_omitido_por_completo() {
        ObjectNode p = plan("Animal");
        assertThat(revisar(p, "Registrar padre y madre de los animales"))
                .anyMatch(e -> e.contains("parentesco solicitado no esta cubierto"));
        relacion(p, "Animal", "Animal").put("sourceRole", "descendientes").put("targetRole", "padre");
        relacion(p, "Animal", "Animal").put("sourceRole", "descendientes").put("targetRole", "madre");
        assertThat(revisar(p, "Registrar padre y madre de los animales")).isEmpty();
    }

    @Test
    void un_itinerario_conectado_por_el_destino_todavia_necesita_su_paquete() {
        ObjectNode p = plan("Paquete", "Destino", "Itinerario", "DetalleItinerario");
        relacion(p, "Paquete", "Destino"); relacion(p, "Itinerario", "DetalleItinerario");
        relacion(p, "DetalleItinerario", "Destino");
        assertThat(revisar(p, "Paquetes con itinerarios ordenados"))
                .anyMatch(e -> e.contains("no identifica el paquete o la salida"));
        relacion(p, "Paquete", "Itinerario");
        assertThat(revisar(p, "Paquetes con itinerarios ordenados")).isEmpty();
    }
    @Test
    void asignacion_necesita_el_contexto_de_materia_y_plan_y_creditos_contextuales() {
        ObjectNode p = plan("Plan", "Asignatura", "DetallePlan", "AsignacionDocente", "Docente", "PeriodoAcademico");
        atributo(p, 1, "creditos", "int");
        relacion(p, "AsignacionDocente", "Docente"); relacion(p, "AsignacionDocente", "PeriodoAcademico");
        String req = "Asignaciones docentes con creditos y nivel en cada plan";
        assertThat(revisar(p, req)).anyMatch(e -> e.contains("contexto de plan"))
                .anyMatch(e -> e.contains("creditos requeridos"));
        relacion(p, "DetallePlan", "Plan"); relacion(p, "DetallePlan", "Asignatura");
        relacion(p, "AsignacionDocente", "DetallePlan"); atributo(p, 2, "creditos", "int");
        assertThat(revisar(p, req)).isEmpty();
    }

    @Test
    void oferta_con_otro_nombre_conserva_el_contexto_y_los_prerrequisitos_no_se_omiten() {
        ObjectNode p = plan("Plan", "Asignatura", "OfertaAsignatura", "AsignacionDocente", "Docente", "PeriodoAcademico");
        relacion(p, "OfertaAsignatura", "Plan"); relacion(p, "OfertaAsignatura", "Asignatura");
        relacion(p, "AsignacionDocente", "OfertaAsignatura"); relacion(p, "AsignacionDocente", "Docente");
        relacion(p, "AsignacionDocente", "PeriodoAcademico"); atributo(p, 2, "creditos", "int");
        String req = "Asignaciones docentes con creditos en cada plan y prerrequisitos";
        assertThat(revisar(p, req)).hasSize(1).anyMatch(e -> e.contains("prerrequisitos solicitados"));
        relacion(p, "Asignatura", "Asignatura").put("sourceRole", "actual").put("targetRole", "requerida");
        assertThat(revisar(p, req)).isEmpty();
    }

    private static void atributo(ObjectNode p, int indice, String n, String tipo) {
        ((com.fasterxml.jackson.databind.node.ArrayNode) p.path("classes").get(indice).path("attributes"))
                .addObject().put("name", n).put("dataType", tipo);
    }
    private static ObjectNode relacion(ObjectNode p, String s, String t) {
        return ((com.fasterxml.jackson.databind.node.ArrayNode) p.path("relations")).addObject()
                .put("source", s).put("target", t).put("kind", "ASSOCIATION");
    }
    private static List<String> revisar(ObjectNode p, String requisito) {
        return RevisorCoberturaPlan.revisar(new RespuestaIA(List.of(
                new ComandoPropuestoIA(EsquemaPlanUml.NOMBRE, p)), ""), requisito);
    }
}
