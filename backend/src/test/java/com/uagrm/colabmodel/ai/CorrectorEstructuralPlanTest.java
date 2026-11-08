package com.uagrm.colabmodel.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

/** Planes reales de la prueba de los 5 dominios desde la interfaz (2026-11-08). */
class CorrectorEstructuralPlanTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String VENTAS = "Venta de computadoras y productos tecnologicos: catalogo, inventario actual, clientes, "
            + "ventas con cantidades y precio unitario historico, pagos y garantias de lo vendido. Cada garantia identifica "
            + "el detalle o unidad vendida que cubre. Conserva historial de ventas y pagos.";
    private static final String TURISMO = "Agencia de turismo: destinos y servicios turisticos reutilizables, paquetes, salidas con "
            + "fecha y cupos, itinerarios con pasos ordenados vinculados al paquete o salida, reservas de salidas concretas, "
            + "viajeros participantes, pagos y cancelaciones opcionales que conserven historial. Registra el precio acordado "
            + "de cada reserva. Una cancelacion no elimina la reserva ni sus pagos.";
    private static final String BANCO = "Sistema bancario: clientes, cuentas con titulares multiples y porcentaje de titularidad, "
            + "historial de movimientos monetarios, transferencias con cuentas origen y destino, prestamos a clientes, "
            + "cuotas y pagos parciales de cuotas. Cada prestamo identifica al cliente prestatario. Movimiento es un cargo "
            + "o abono de una cuenta; Transferencia enlaza dos cuentas. Conserva fechas e importes.";
    private static final String ACADEMICO = "Plan academico universitario: carreras, planes versionados con vigencia, catalogo de "
            + "asignaturas, creditos y nivel en cada plan, prerrequisitos, periodos academicos, docentes y asignaciones "
            + "docentes. Conserva el contexto de plan y periodo de cada asignacion. Evita prerrequisitos circulares.";

    @Test
    void ventas_computadora_y_componente_sueltas_pasan_a_subclases_de_producto() throws Exception {
        ObjectNode plan = plan("""
                Producto: codigo:string, nombre:string, descripcion:text, precioActual:decimal
                Computadora: modelo:string, procesador:string, memoriaRam:int, almacenamiento:int
                Componente: nombre:string, fabricante:string, categoria:string
                Cliente: identificador:string, nombre:string, direccion:text
                Inventario: cantidadDisponible:int, ubicacion:string
                Venta: numeroFactura:string, fecha:date, total:decimal
                DetalleVenta: cantidad:int, precioUnitario:decimal
                Pago: monto:decimal, fechaPago:date
                Garantia: fechaInicio:date, fechaFin:date
                ---
                Producto 1 -- 0..* Inventario
                Venta 1 -- 1..* DetalleVenta
                DetalleVenta 0..* -- 1 Producto
                Venta 1 -- 0..* Pago
                Producto 1 -- 0..* Garantia
                Garantia 0..* -- 1 DetalleVenta
                Cliente 1 -- 0..* Venta
                """, "Se conecto Computadora y Componente al catalogo de Producto mediante asociacion simple.",
                "Se anadio la relacion Cliente-Venta para el historial de compras.");
        RespuestaIA r = normalizar(plan, VENTAS);
        JsonNode p = r.comandos().getFirst().parametros();

        assertThat(relaciones(p)).contains("Computadora GENERALIZATION Producto", "Componente GENERALIZATION Producto")
                .noneMatch(x -> x.contains("Garantia") && x.contains("Producto"));
        assertThat(atributos(p, "Componente")).doesNotContain("nombre").contains("fabricante");
        assertThat(ValidadorPlanFinal.revisar(r)).noneMatch(h -> h.contains("desconectada") || h.contains("sin conexion"));
        assertThat(p.path("audit").toString()).doesNotContain("mediante asociacion simple").contains("subclase");
    }

    @Test
    void turismo_completa_cabecera_cupos_y_participantes() throws Exception {
        ObjectNode plan = plan("""
                Destino: nombre:string, pais:string
                ServicioTuristico: nombre:string, categoria:string
                Paquete: nombre:string
                Salida: fechaSalida:date, precioBase:decimal
                Reserva: fechaCreacion:datetime, estado:string, precioAcordado:decimal
                TitularReserva: nombre:string, email:string
                Participante: nombre:string, email:string
                Pago: fecha:datetime, monto:decimal
                DetalleReserva: cantidad:int, precioUnitarioAcordado:decimal
                Cupo: cantidadTotal:int
                ---
                Paquete 1 -- 0..* Destino
                Paquete 1 -- 0..* ServicioTuristico
                Paquete 1 -- 0..* Salida
                Reserva 1 -- 1 Salida
                Reserva 1 -- 0..* TitularReserva
                Reserva 0..* -- 1 Participante
                Reserva 1 -- 0..* Pago
                TitularReserva 1 -- 0..* DetalleReserva
                Participante 1 -- 0..* DetalleReserva
                DetalleReserva 0..* -- 1 Paquete
                Cupo 1 -- 0..* Salida
                """, "Cupo se conecta a Salida porque controla la capacidad de una salida concreta.",
                "DetalleReserva actua como clase asociativa.");
        RespuestaIA r = normalizar(plan, TURISMO);
        JsonNode p = r.comandos().getFirst().parametros();

        assertThat(relaciones(p)).contains("Reserva 1 -- 1..* DetalleReserva", "Reserva 0..* -- 1 Salida",
                "Reserva 1 -- 1..* Participante", "Reserva 0..* -- 1 TitularReserva");
        assertThat(clases(p)).doesNotContain("Cupo");
        assertThat(atributos(p, "Salida")).contains("cupoMaximo");
        List<String> hallazgos = new ArrayList<>(ValidadorPlanFinal.revisar(r));
        hallazgos.addAll(RevisorCoberturaPlan.revisar(r, TURISMO));
        assertThat(hallazgos).noneMatch(h -> h.contains("cabecera") || h.contains("cupos")
                || h.contains("multiples viajeros"));
        assertThat(p.path("audit").toString()).doesNotContain("Cupo se conecta");
    }

    @Test
    void banco_retira_cliente_cuenta_directa_cuando_titular_ya_las_une() throws Exception {
        ObjectNode plan = plan("""
                Cliente: nombre:string, fechaNacimiento:date
                Cuenta: saldo:decimal, moneda:string
                Titular: porcentajePropiedad:decimal
                Prestamo: montoTotal:decimal, tasaInteres:decimal, fechaInicio:date
                ---
                Cliente 0..* -- 0..* Cuenta
                Cliente 1 -- 0..* Titular
                Cuenta 1 -- 0..* Titular
                Prestamo 0..* -- 1 Cliente
                """, "Titular conecta Cliente y Cuenta.", "Prestamo conecta con Cliente.");
        JsonNode p = normalizar(plan, BANCO).comandos().getFirst().parametros();
        assertThat(relaciones(p)).noneMatch(x -> x.startsWith("Cliente") && x.endsWith("Cuenta"))
                .anyMatch(x -> x.contains("Titular") && x.contains("Cliente"));
    }

    @Test
    void academico_composicion_que_la_auditoria_quiere_sin_borrado_pasa_a_asociacion() throws Exception {
        ObjectNode plan = plan("""
                Carrera: codigo:string, nombre:string
                PlanAcademico: nombreVersion:string, fechaVigenciaInicio:date
                ---
                Carrera 1 -- 0..* PlanAcademico COMPOSITION
                """, "El versionado del plan se modela mediante COMPOSITION Carrera-PlanAcademico para garantizar "
                + "que las versiones antiguas persistan sin borrado en cascada.", "Otro punto.");
        JsonNode p = normalizar(plan, ACADEMICO).comandos().getFirst().parametros();
        assertThat(relaciones(p)).contains("Carrera 1 -- 0..* PlanAcademico");
        assertThat(p.path("audit").toString()).contains("como ASSOCIATION");
    }

    @Test
    void una_clase_suelta_sin_concepto_base_no_se_conecta_inventando_enlaces() throws Exception {
        ObjectNode plan = plan("""
                Venta: fecha:date
                Cliente: nombre:string
                Proveedor: nombre:string
                ---
                Cliente 1 -- 0..* Venta
                """, "Proveedor se vincula con Venta.", "Otro punto.");
        RespuestaIA r = normalizar(plan, "Ventas a clientes y proveedores");
        JsonNode p = r.comandos().getFirst().parametros();
        assertThat(relaciones(p)).noneMatch(x -> x.contains("Proveedor"));
        assertThat(ValidadorPlanFinal.revisar(r)).anyMatch(h -> h.contains("Clase desconectada: Proveedor"));
        assertThat(p.path("audit").toString()).doesNotContain("Proveedor se vincula");
    }

    // --- planes compactos: "Clase: attr:tipo, ..." y "A m -- m B [KIND]" ---

    private static ObjectNode plan(String texto, String... auditoria) {
        ObjectNode plan = MAPPER.createObjectNode();
        ArrayNode clases = plan.putArray("classes"), relaciones = plan.putArray("relations");
        boolean enRelaciones = false;
        for (String linea : texto.strip().split("\n")) {
            linea = linea.strip();
            if (linea.equals("---")) { enRelaciones = true; continue; }
            if (!enRelaciones) {
                String nombre = linea.substring(0, linea.indexOf(':'));
                ArrayNode attrs = clases.addObject().put("name", nombre).putArray("attributes");
                attrs.addObject().put("name", "id" + nombre).put("dataType", "uuid").put("pk", true)
                        .put("required", true).put("unique", true);
                for (String a : linea.substring(linea.indexOf(':') + 1).split(",")) {
                    String[] partes = a.strip().split(":");
                    attrs.addObject().put("name", partes[0]).put("dataType", partes[1]).put("required", true);
                }
            } else {
                String[] t = linea.split("\\s+");
                relaciones.addObject().put("source", t[0]).put("sourceMultiplicity", t[1])
                        .put("targetMultiplicity", t[3]).put("target", t[4])
                        .put("kind", t.length > 5 ? t[5] : "ASSOCIATION");
            }
        }
        ArrayNode audit = plan.putArray("audit");
        for (String a : auditoria) audit.add(a);
        return plan;
    }

    private static RespuestaIA normalizar(ObjectNode plan, String requisito) {
        return NormalizadorPlanUml.normalizar(new RespuestaIA(List.of(
                new ComandoPropuestoIA(EsquemaPlanUml.NOMBRE, plan)), ""), requisito);
    }

    private static List<String> relaciones(JsonNode p) {
        List<String> out = new ArrayList<>();
        for (JsonNode r : p.path("relations")) {
            String kind = r.path("kind").asText();
            if (kind.equals("ASSOCIATION")) out.add(r.path("source").asText() + " " + r.path("sourceMultiplicity").asText()
                    + " -- " + r.path("targetMultiplicity").asText() + " " + r.path("target").asText());
            else out.add(r.path("source").asText() + " " + kind + " " + r.path("target").asText());
        }
        return out;
    }

    private static List<String> clases(JsonNode p) {
        List<String> out = new ArrayList<>();
        for (JsonNode c : p.path("classes")) out.add(c.path("name").asText());
        return out;
    }

    private static List<String> atributos(JsonNode p, String clase) {
        List<String> out = new ArrayList<>();
        for (JsonNode c : p.path("classes")) if (c.path("name").asText().equals(clase))
            for (JsonNode a : c.path("attributes")) out.add(a.path("name").asText());
        return out;
    }
}
