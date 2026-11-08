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

    // --- segunda ronda de la interfaz (2026-11-08) ---

    @Test
    void ventas_un_pago_opcional_por_venta_pasa_a_varios_pagos() throws Exception {
        ObjectNode plan = plan("""
                Venta: fecha:date, total:decimal
                Pago: monto:decimal, fechaPago:date
                ---
                Pago 0..1 -- 1 Venta
                """, "Uno.", "Dos.");
        JsonNode p = normalizar(plan, VENTAS).comandos().getFirst().parametros();
        assertThat(relaciones(p)).contains("Pago 0..* -- 1 Venta");
    }

    @Test
    void banco_la_directa_cliente_cuenta_sobra_aunque_tenga_roles() throws Exception {
        ObjectNode plan = plan("""
                Cliente: nombre:string
                Cuenta: saldo:decimal
                TitularCuenta: porcentajePropiedad:decimal
                ---
                Cliente * -- 0..* Cuenta roles=titulares/cuentas
                Cuenta 1 -- 0..* TitularCuenta
                TitularCuenta 0..* -- 1 Cliente
                """, "Uno.", "Dos.");
        JsonNode p = normalizar(plan, BANCO).comandos().getFirst().parametros();
        assertThat(relaciones(p)).noneMatch(x -> x.startsWith("Cliente") && x.endsWith("Cuenta"));
    }

    @Test
    void academico_un_uuid_con_nombre_de_clase_se_convierte_en_asociacion() throws Exception {
        ObjectNode plan = plan("""
                PeriodoAcademico: nombre:string, anio:int
                Docente: nombre:string
                AsignacionDocente: periodoAcademico:uuid, horasSemanales:int
                DetallePlan: orden:int
                Prerrequisito: detallePlanOrigen:uuid, condicion:string
                ---
                Docente 1 -- 0..* AsignacionDocente
                DetallePlan 1 -- 0..* AsignacionDocente
                Prerrequisito 1 -- 0..* DetallePlan
                """, "Uno.", "Dos.");
        JsonNode p = normalizar(plan, ACADEMICO).comandos().getFirst().parametros();
        assertThat(relaciones(p)).anyMatch(x -> x.contains("AsignacionDocente") && x.contains("PeriodoAcademico"));
        assertThat(atributos(p, "AsignacionDocente")).doesNotContain("periodoAcademico");
        assertThat(atributos(p, "Prerrequisito")).doesNotContain("detallePlanOrigen");
    }

    @Test
    void ganaderia_cuatro_movimientos_a_lote_quedan_en_origen_y_destino() throws Exception {
        ObjectNode plan = plan("""
                Lote: codigo:string
                Animal: fechaNacimiento:date
                MovimientoAnimal: fechaMovimiento:date
                ---
                Animal 1 -- 0..* MovimientoAnimal
                MovimientoAnimal 0..* -- 1 Lote roles=loteOrigen/movimientos
                MovimientoAnimal 0..* -- 1 Lote roles=loteDestino/movimientos
                MovimientoAnimal 0..* -- 1 Lote
                MovimientoAnimal 0..* -- 1 Lote roles=/loteActual
                """, "Uno.", "Dos.");
        String ganaderia = "Ganaderia: lotes, animales, historial de movimientos entre lotes con origen y destino.";
        RespuestaIA r = normalizar(plan, ganaderia);
        JsonNode p = r.comandos().getFirst().parametros();
        List<String> roles = new ArrayList<>();
        for (JsonNode x : p.path("relations")) if (x.path("target").asText().equals("Lote")
                || x.path("source").asText().equals("Lote")) roles.add(x.path("targetRole").asText());
        assertThat(roles).containsExactlyInAnyOrder("loteOrigen", "loteDestino");
        assertThat(RevisorCoberturaPlan.revisar(r, ganaderia)).noneMatch(h -> h.contains("origen y destino"));
    }

    @Test
    void turismo_titular_persona_con_una_relacion_no_es_asociativa_incompleta() throws Exception {
        ObjectNode plan = plan("""
                Reserva: fechaCreacion:datetime
                TitularReserva: nombre:string, email:string
                ---
                Reserva 0..* -- 1 TitularReserva
                """, "Uno.", "Dos.");
        RespuestaIA r = normalizar(plan, TURISMO);
        assertThat(ValidadorPlanFinal.revisar(r)).noneMatch(h -> h.contains("TitularReserva"));
    }

    // --- tercera ronda (2026-11-08) ---

    @Test
    void ganaderia_padre_id_y_madre_id_repiten_las_recursivas_y_se_retiran() throws Exception {
        ObjectNode plan = plan("""
                Animal: fechaNacimiento:date, madreId:uuid, padreId:uuid, tipoId:string
                Lote: codigo:string
                ---
                Lote 1 -- 0..* Animal
                Animal 0..* -- 0..1 Animal roles=descendientes/padre
                Animal 0..* -- 0..1 Animal roles=descendientes/madre
                """, "Uno.", "Dos.");
        JsonNode p = normalizar(plan, "Ganaderia: animales y parentesco con padre y madre.").comandos().getFirst().parametros();
        assertThat(atributos(p, "Animal")).doesNotContain("madreId", "padreId").contains("fechaNacimiento", "tipoId");
    }

    @Test
    void turismo_una_reserva_tiene_a_lo_sumo_una_cancelacion() throws Exception {
        ObjectNode plan = plan("""
                Reserva: fechaCreacion:datetime
                Cancelacion: fechaCancelacion:datetime, motivo:text
                ---
                Reserva 1 -- 0..* Cancelacion
                """, "Uno.", "Dos.");
        JsonNode p = normalizar(plan, TURISMO).comandos().getFirst().parametros();
        assertThat(relaciones(p)).contains("Reserva 1 -- 0..1 Cancelacion");
    }

    // --- cuarta ronda (2026-11-08) ---

    @Test
    void la_auditoria_descarta_restos_del_json_y_conserva_los_puntos_con_negrita() throws Exception {
        ObjectNode plan = plan("""
                Reserva: fecha:date
                Pago: monto:decimal
                ---
                Reserva 1 -- 0..* Pago
                """, "]}**Nota:** El campo `classes_count` en el JSON anterior era un error de formato. {",
                "**Trazabilidad:** cada pago identifica su reserva.");
        JsonNode p = normalizar(plan, TURISMO).comandos().getFirst().parametros();
        assertThat(p.path("audit").toString()).doesNotContain("classes_count", "JSON", "**")
                .contains("Trazabilidad: cada pago identifica su reserva.");
    }

    @Test
    void una_subclase_no_repite_los_atributos_que_hereda() throws Exception {
        ObjectNode plan = plan("""
                Producto: nombre:string, precioActual:decimal
                Componente: nombre:string, especificaciones:text
                Venta: fecha:date
                ---
                Componente 1 -- 0..* Producto GENERALIZATION
                Venta 1 -- 0..* Producto
                """, "Uno.", "Dos.");
        JsonNode p = normalizar(plan, VENTAS).comandos().getFirst().parametros();
        assertThat(atributos(p, "Componente")).doesNotContain("nombre").contains("especificaciones");
        assertThat(atributos(p, "Producto")).contains("nombre");
    }

    @Test
    void turismo_sin_clase_de_participacion_una_reserva_lleva_varios_viajeros() throws Exception {
        ObjectNode plan = plan("""
                Reserva: fechaCreacion:datetime
                Viajero: nombre:string, email:string
                TitularReserva: nombre:string
                ---
                Reserva 0..* -- 1 Viajero
                Reserva 0..* -- 1 TitularReserva
                """, "Uno.", "Dos.");
        RespuestaIA r = normalizar(plan, TURISMO);
        assertThat(relaciones(r.comandos().getFirst().parametros())).contains("Reserva 0..* -- 1..* Viajero");
        assertThat(RevisorCoberturaPlan.revisar(r, TURISMO)).noneMatch(h -> h.contains("multiples viajeros"));
    }

    // --- quinta ronda (2026-11-08) ---

    @Test
    void banco_titular_unido_solo_a_cuenta_recupera_al_cliente_aunque_la_directa_tenga_roles() throws Exception {
        ObjectNode plan = plan("""
                Cliente: nombre:string
                Cuenta: saldo:decimal
                Titular: porcentajePropiedad:decimal
                ---
                Cliente 0..* -- 0..* Cuenta roles=titulares/cuentas
                Titular 0..* -- 1 Cuenta
                """, "Uno.", "Dos.");
        RespuestaIA r = normalizar(plan, BANCO);
        JsonNode p = r.comandos().getFirst().parametros();
        assertThat(relaciones(p)).noneMatch(x -> x.startsWith("Cliente") && x.endsWith("Cuenta")
                || x.startsWith("Cuenta") && x.endsWith("Cliente"))
                .anyMatch(x -> x.contains("Titular") && x.contains("Cliente"));
        assertThat(ValidadorPlanFinal.revisar(r)).noneMatch(h -> h.contains("Titular"));
        assertThat(RevisorCoberturaPlan.revisar(r, BANCO)).noneMatch(h -> h.contains("Titular"));
    }

    @Test
    void academico_la_recursiva_de_asignatura_sobra_junto_a_la_clase_prerrequisito() throws Exception {
        ObjectNode plan = plan("""
                Asignatura: nombre:string
                DetallePlan: orden:int
                Prerrequisito: condicion:string
                ---
                DetallePlan 0..* -- 1 Asignatura
                DetallePlan 1 -- 0..* Prerrequisito
                Asignatura 1 -- 0..* Asignatura roles=requisitos/requisitoDe
                Prerrequisito 0..* -- 1 Asignatura
                """, "Uno.", "Dos.");
        RespuestaIA r = normalizar(plan, ACADEMICO);
        assertThat(relaciones(r.comandos().getFirst().parametros())).noneMatch(x -> x.startsWith("Asignatura") && x.endsWith("Asignatura"));
        assertThat(RevisorCoberturaPlan.revisar(r, ACADEMICO)).noneMatch(h -> h.contains("prerrequisitos"));
    }

    @Test
    void una_prerrequisito_con_un_solo_extremo_se_completa_y_la_recursiva_sobra() throws Exception {
        ObjectNode plan = plan("""
                Asignatura: nombre:string
                Prerrequisito: condicion:string
                ---
                Asignatura 1 -- 0..* Asignatura roles=requisitos/requisitoDe
                Prerrequisito 0..* -- 1 Asignatura
                """, "Uno.", "Dos.");
        RespuestaIA r = normalizar(plan, "Asignaturas con prerrequisitos");
        List<String> rels = relaciones(r.comandos().getFirst().parametros());
        assertThat(rels).noneMatch(x -> x.startsWith("Asignatura") && x.endsWith("Asignatura"));
        assertThat(rels.stream().filter(x -> x.contains("Prerrequisito"))).hasSize(2); // actual y requerida
        assertThat(RevisorCoberturaPlan.revisar(r, "Asignaturas con prerrequisitos")).noneMatch(h -> h.contains("prerrequisitos"));
    }

    @Test
    void sin_clase_prerrequisito_la_recursiva_se_conserva() throws Exception {
        ObjectNode plan = plan("""
                Asignatura: nombre:string
                Carrera: nombre:string
                ---
                Asignatura 1 -- 0..* Asignatura roles=requisitos/requisitoDe
                Carrera 1 -- 0..* Asignatura
                """, "Uno.", "Dos.");
        JsonNode p = normalizar(plan, "Asignaturas con prerrequisitos").comandos().getFirst().parametros();
        assertThat(relaciones(p)).anyMatch(x -> x.startsWith("Asignatura") && x.endsWith("Asignatura"));
    }

    // --- sexta ronda (2026-11-08) ---

    @Test
    void banco_transferencia_sin_importe_y_prestamo_sin_cliente_se_completan() throws Exception {
        ObjectNode plan = plan("""
                Cliente: nombre:string
                Cuenta: saldo:decimal
                Titular: porcentajePropiedad:decimal
                Transferencia: fechaEjecucion:datetime, estado:string
                Prestamo: montoTotal:decimal
                Cuota: monto:decimal
                ---
                Cliente 1 -- 0..* Titular
                Titular 0..* -- 1 Cuenta
                Transferencia 0..* -- 1 Cuenta roles=/cuentaOrigen
                Transferencia 0..* -- 1 Cuenta roles=/cuentaDestino
                Prestamo 1 -- 1..* Cuota
                """, "Uno.", "Dos.");
        RespuestaIA r = normalizar(plan, BANCO);
        JsonNode p = r.comandos().getFirst().parametros();
        assertThat(atributos(p, "Transferencia")).contains("importe");
        assertThat(relaciones(p)).containsAnyOf("Prestamo 0..* -- 1 Cliente", "Cliente 1 -- 0..* Prestamo");
        List<String> hallazgos = new ArrayList<>(ValidadorPlanFinal.revisar(r));
        hallazgos.addAll(RevisorCoberturaPlan.revisar(r, BANCO));
        assertThat(hallazgos).noneMatch(h -> h.contains("importe") || h.contains("prestatario") || h.contains("sin conexion"));
    }

    @Test
    void academico_asignacion_docente_sin_docente_toma_la_directa_docente_periodo() throws Exception {
        ObjectNode plan = plan("""
                Docente: nombre:string
                PeriodoAcademico: nombre:string
                DetallePlan: orden:int
                AsignacionDocente: horasSemanales:int
                ---
                Docente 0..* -- 0..* PeriodoAcademico
                AsignacionDocente 0..* -- 1 DetallePlan
                AsignacionDocente 0..* -- 1 PeriodoAcademico
                """, "Uno.", "Dos.");
        JsonNode p = normalizar(plan, ACADEMICO).comandos().getFirst().parametros();
        assertThat(relaciones(p)).contains("Docente 1 -- 0..* AsignacionDocente")
                .noneMatch(x -> x.contains("Docente") && x.contains("PeriodoAcademico") && !x.contains("Asignacion"));
    }

    // --- septima ronda (2026-11-08) ---

    @Test
    void ganaderia_un_animal_acumula_controles_y_tratamientos() throws Exception {
        ObjectNode plan = plan("""
                Animal: fechaNacimiento:date
                ControlSanitario: fechaHora:datetime
                Tratamiento: fechaHoraInicio:datetime
                ---
                ControlSanitario 1 -- 1 Animal
                Tratamiento 1 -- 1 Animal
                """, "Uno.", "Dos.");
        RespuestaIA r = normalizar(plan, "Ganaderia: animales, controles sanitarios y tratamientos.");
        assertThat(relaciones(r.comandos().getFirst().parametros()))
                .contains("ControlSanitario 0..* -- 1 Animal", "Tratamiento 0..* -- 1 Animal");
    }

    @Test
    void una_identidad_tecnica_de_texto_pasa_a_uuid() throws Exception {
        ObjectNode plan = MAPPER.createObjectNode();
        plan.putArray("classes").addObject().put("name", "Producto").putArray("attributes")
                .addObject().put("name", "idProducto").put("dataType", "string").put("pk", true);
        ((ArrayNode) plan.path("classes").get(0).path("attributes")).addObject().put("name", "nombre").put("dataType", "string");
        plan.putArray("relations");
        plan.putArray("audit").add("Uno.").add("Dos.");
        JsonNode p = normalizar(plan, VENTAS).comandos().getFirst().parametros();
        assertThat(p.path("classes").get(0).path("attributes").get(0).path("dataType").asText()).isEqualTo("uuid");
    }

    // --- octava ronda (2026-11-08) ---

    @Test
    void ganaderia_animal_lote_muchos_a_muchos_es_la_ubicacion_actual() throws Exception {
        ObjectNode plan = plan("""
                Animal: nombre:string
                Lote: nombre:string
                MovimientoAnimal: fechaMovimiento:datetime
                ---
                Animal 0..* -- 0..* Lote
                MovimientoAnimal 0..* -- 1 Animal
                MovimientoAnimal 0..* -- 1 Lote roles=/loteOrigen
                MovimientoAnimal 0..* -- 1 Lote roles=/loteDestino
                """, "Uno.", "Dos.");
        JsonNode p = normalizar(plan, "Ganaderia: lotes, animales, historial de movimientos entre lotes con origen y destino.")
                .comandos().getFirst().parametros();
        assertThat(relaciones(p)).contains("Animal 0..* -- 1 Lote");
    }

    @Test
    void una_clase_conserva_una_sola_identidad_tecnica() throws Exception {
        ObjectNode plan = plan("""
                ProductoSanitario: nombre:string
                Animal: nombre:string
                ---
                Animal 1 -- 0..* ProductoSanitario
                """, "Uno.", "Dos.");
        ((ArrayNode) plan.path("classes").get(0).path("attributes")).addObject()
                .put("name", "id").put("dataType", "uuid").put("pk", true);
        JsonNode p = normalizar(plan, "Ganaderia").comandos().getFirst().parametros();
        assertThat(atributos(p, "ProductoSanitario")).containsExactly("idProductoSanitario", "nombre");
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
                ObjectNode r = relaciones.addObject().put("source", t[0]).put("sourceMultiplicity", t[1])
                        .put("targetMultiplicity", t[3]).put("target", t[4]).put("kind", "ASSOCIATION");
                for (int i = 5; i < t.length; i++) {
                    if (t[i].startsWith("roles=")) {
                        String[] roles = t[i].substring(6).split("/", -1);
                        r.put("sourceRole", roles[0]).put("targetRole", roles[1]);
                    } else r.put("kind", t[i]);
                }
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
