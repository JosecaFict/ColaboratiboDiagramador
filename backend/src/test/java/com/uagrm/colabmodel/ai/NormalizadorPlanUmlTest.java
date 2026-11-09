package com.uagrm.colabmodel.ai;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NormalizadorPlanUmlTest {

    private static final JsonNodeFactory J = JsonNodeFactory.instance;

    @Test
    void normaliza_identificadores_y_protege_el_historial_indirecto() {
        ObjectNode plan = J.objectNode();
        var clases = plan.putArray("classes");
        clase(clases.addObject(), "Obra", "idObra");
        clase(clases.addObject(), "Copia física", "códigoInventario");
        clase(clases.addObject(), "Préstamo", "fechaDevolución");
        clase(clases.addObject(), "Usuario", "identificacionUniversitaria");
        ObjectNode tituloNatural = (ObjectNode) clases.get(0).path("attributes").get(0);
        tituloNatural.put("name", "titulo").put("pk", true).put("unique", false);
        ObjectNode codigoNatural = (ObjectNode) clases.get(1).path("attributes").get(0);
        codigoNatural.put("pk", true).put("unique", false);
        ObjectNode devolucion = (ObjectNode) clases.get(2).path("attributes").get(0);
        devolucion.put("required", true);
        var relaciones = plan.putArray("relations");
        relacion(relaciones.addObject(), "Obra", "Copia física", "COMPOSITION");
        relacion(relaciones.addObject(), "Copia física", "Préstamo", "ASSOCIATION");
        plan.putArray("audit").add("Se usa COMPOSITION para el ciclo de vida").add("Se conserva el historial");

        RespuestaIA normalizada = NormalizadorPlanUml.normalizar(new RespuestaIA(
                List.of(new ComandoPropuestoIA(EsquemaPlanUml.NOMBRE, plan)), ""),
                "Debe gestionar devoluciones, vencimiento y obras con mas de un autor. "
                        + "Ningun ejemplar puede tener dos prestamos activos simultaneos. ISBN opcional.");
        var resultado = normalizada.comandos().getFirst().parametros();

        assertThat(resultado.path("classes").get(1).path("name").asText()).isEqualTo("CopiaFisica");
        assertThat(resultado.path("classes").get(1).path("attributes").toString())
                .contains("codigoInventario", "idCopiaFisica");
        assertThat(resultado.path("classes").get(2).path("name").asText()).isEqualTo("Prestamo");
        assertThat(resultado.path("classes").get(3).path("attributes").toString())
                .contains("idUsuario", "identificacionUniversitaria");
        assertThat(resultado.path("relations").get(0).path("kind").asText()).isEqualTo("ASSOCIATION");
        assertThat(resultado.path("audit").toString()).doesNotContain("COMPOSITION").contains("backend sustituyo");
        assertThat(resultado.path("classes").get(0).path("attributes").get(0).path("pk").asBoolean()).isTrue();
        assertThat(resultado.path("classes").get(0).path("attributes").toString())
                .contains("idObra", "\"titulo\"").contains("\"unique\":false", "\"pk\":false");
        assertThat(resultado.path("classes").get(1).path("attributes").toString())
                .contains("codigoInventario", "\"unique\":true", "\"pk\":false");
        assertThat(resultado.path("classes").get(2).path("attributes").toString())
                .contains("fechaDevolucion", "fechaVencimiento");
        assertThat(resultado.path("classes").get(2).path("attributes").toString())
                .contains("\"fechaDevolucion\",\"dataType\":\"string\",\"required\":false");
        assertThat(resultado.path("relations").get(1).path("targetMultiplicity").asText()).isEqualTo("0..*");
    }

    @Test
    void amplia_relaciones_clinicas_que_deben_conservar_historial() {
        ObjectNode plan = J.objectNode();
        var clases = plan.putArray("classes");
        clase(clases.addObject(), "Paciente", "nombreCompleto");
        clase(clases.addObject(), "ConsultaMedica", "fechaHoraConsulta");
        var relaciones = plan.putArray("relations");
        ObjectNode relacion = relaciones.addObject();
        relacion(relacion, "Paciente", "ConsultaMedica", "ASSOCIATION");
        relacion.put("targetMultiplicity", "1");
        plan.putArray("audit");

        RespuestaIA normalizada = NormalizadorPlanUml.normalizar(new RespuestaIA(
                List.of(new ComandoPropuestoIA(EsquemaPlanUml.NOMBRE, plan)), ""),
                "El sistema debe conservar el historial completo de consultas medicas de cada paciente.");
        var resultado = normalizada.comandos().getFirst().parametros();

        assertThat(resultado.path("relations").get(0).path("sourceMultiplicity").asText()).isEqualTo("1");
        assertThat(resultado.path("relations").get(0).path("targetMultiplicity").asText()).isEqualTo("0..*");
        assertThat(resultado.path("audit").toString()).contains("patrones estructurales");
    }

    @Test
    void especialidad_puede_existir_antes_de_tener_profesionales_asignados() {
        ObjectNode plan = J.objectNode();
        var clases = plan.putArray("classes");
        clase(clases.addObject(), "ProfesionalMedico", "nombreCompleto");
        clase(clases.addObject(), "EspecialidadMedica", "codigoEspecialidad");
        var relaciones = plan.putArray("relations");
        ObjectNode relacion = relaciones.addObject();
        relacion(relacion, "ProfesionalMedico", "EspecialidadMedica", "ASSOCIATION");
        relacion.put("sourceMultiplicity", "1..*").put("targetMultiplicity", "1..*");
        plan.putArray("audit");

        RespuestaIA normalizada = NormalizadorPlanUml.normalizar(new RespuestaIA(
                List.of(new ComandoPropuestoIA(EsquemaPlanUml.NOMBRE, plan)), ""),
                "Cada profesional tiene una o mas especialidades medicas.");
        var resultado = normalizada.comandos().getFirst().parametros().path("relations").get(0);

        assertThat(resultado.path("sourceMultiplicity").asText()).isEqualTo("0..*");
        assertThat(resultado.path("targetMultiplicity").asText()).isEqualTo("1..*");
    }

    @Test
    void recupera_relacion_entre_cita_y_consulta_y_retira_identificador_generico() {
        ObjectNode plan = J.objectNode();
        var clases = plan.putArray("classes");
        clase(clases.addObject(), "CitaProgramada", "identificadorUnico");
        clase(clases.addObject(), "ConsultaMedica", "identificadorUnico");
        plan.putArray("relations");
        plan.putArray("audit");

        RespuestaIA normalizada = NormalizadorPlanUml.normalizar(new RespuestaIA(
                List.of(new ComandoPropuestoIA(EsquemaPlanUml.NOMBRE, plan)), ""),
                "Gestiona citas programadas y consultas medicas, incluso consultas sin cita previa.");
        var resultado = normalizada.comandos().getFirst().parametros();

        assertThat(resultado.path("relations")).singleElement().satisfies(relacion -> {
            assertThat(relacion.path("source").asText()).isEqualTo("CitaProgramada");
            assertThat(relacion.path("target").asText()).isEqualTo("ConsultaMedica");
            assertThat(relacion.path("sourceMultiplicity").asText()).isEqualTo("0..1");
            assertThat(relacion.path("targetMultiplicity").asText()).isEqualTo("0..1");
        });
        assertThat(resultado.path("classes").get(0).path("attributes").toString())
                .contains("idCitaProgramada").doesNotContain("identificadorUnico");
        assertThat(resultado.path("classes").get(1).path("attributes").toString())
                .contains("idConsultaMedica").doesNotContain("identificadorUnico");
        assertThat(resultado.path("audit").toString()).contains("relacion(es) fundamental(es)");
    }

    @Test
    void recupera_actores_fundamentales_de_una_consulta_clinica() {
        ObjectNode plan = J.objectNode();
        var clases = plan.putArray("classes");
        clase(clases.addObject(), "Paciente", "nombreCompleto");
        clase(clases.addObject(), "ProfesionalMedico", "nombreCompleto");
        clase(clases.addObject(), "ConsultaMedica", "fechaHoraConsulta");
        plan.putArray("relations");
        plan.putArray("audit");

        RespuestaIA normalizada = NormalizadorPlanUml.normalizar(new RespuestaIA(
                List.of(new ComandoPropuestoIA(EsquemaPlanUml.NOMBRE, plan)), ""),
                "Cada consulta medica identifica al paciente y al profesional que la atiende.");
        var relaciones = normalizada.comandos().getFirst().parametros().path("relations");

        assertThat(relaciones).hasSize(2);
        assertThat(relaciones).anySatisfy(relacion -> {
            assertThat(relacion.path("source").asText()).isEqualTo("Paciente");
            assertThat(relacion.path("target").asText()).isEqualTo("ConsultaMedica");
            assertThat(relacion.path("sourceMultiplicity").asText()).isEqualTo("1");
            assertThat(relacion.path("targetMultiplicity").asText()).isEqualTo("0..*");
        });
        assertThat(relaciones).anySatisfy(relacion -> {
            assertThat(relacion.path("source").asText()).isEqualTo("ProfesionalMedico");
            assertThat(relacion.path("target").asText()).isEqualTo("ConsultaMedica");
            assertThat(relacion.path("sourceMultiplicity").asText()).isEqualTo("1");
            assertThat(relacion.path("targetMultiplicity").asText()).isEqualTo("0..*");
        });
    }

    @Test
    void historial_conserva_un_propietario_y_elimina_la_referencia_duplicada() {
        ObjectNode plan = J.objectNode();
        var clases = plan.putArray("classes");
        clase(clases.addObject(), "Usuario", "nombreCompleto");
        clase(clases.addObject(), "HistorialVersion", "timestampModificacion");
        ((ObjectNode) clases.get(1).path("attributes").get(0)).put("name", "usuarioModificador");
        var relaciones = plan.putArray("relations");
        ObjectNode relacion = relaciones.addObject();
        relacion(relacion, "HistorialVersion", "Usuario", "ASSOCIATION");
        relacion.put("sourceMultiplicity", "1").put("targetMultiplicity", "0..*");
        plan.putArray("audit");

        RespuestaIA normalizada = NormalizadorPlanUml.normalizar(new RespuestaIA(
                List.of(new ComandoPropuestoIA(EsquemaPlanUml.NOMBRE, plan)), ""),
                "Debe conservar el historial completo e identificar al usuario modificador.");
        var resultado = normalizada.comandos().getFirst().parametros();

        assertThat(resultado.path("relations").get(0).path("sourceMultiplicity").asText()).isEqualTo("0..*");
        assertThat(resultado.path("relations").get(0).path("targetMultiplicity").asText()).isEqualTo("1");
        assertThat(resultado.path("classes").get(1).path("attributes").toString())
                .doesNotContain("usuarioModificador");
        assertThat(resultado.path("audit").toString()).contains("duplicaban asociaciones UML");
    }

    @Test
    void distingue_historial_resultado_opcional_y_detalle_clinico() {
        ObjectNode plan = J.objectNode();
        var clases = plan.putArray("classes");
        clase(clases.addObject(), "Paciente", "nombreCompleto");
        clase(clases.addObject(), "CitaProgramada", "fechaHoraProgramada");
        clase(clases.addObject(), "ConsultaMedica", "fechaHoraRealizada");
        clase(clases.addObject(), "Diagnostico", "descripcionClinica");
        clase(clases.addObject(), "RecetaMedica", "fechaEmision");
        clase(clases.addObject(), "MedicamentoIndicado", "dosis");
        var relaciones = plan.putArray("relations");
        relacion(relaciones.addObject(), "Paciente", "ConsultaMedica", "ASSOCIATION");
        relacion(relaciones.addObject(), "CitaProgramada", "ConsultaMedica", "ASSOCIATION");
        relacion(relaciones.addObject(), "ConsultaMedica", "Diagnostico", "ASSOCIATION");
        ObjectNode consultaReceta = relaciones.addObject();
        relacion(consultaReceta, "ConsultaMedica", "RecetaMedica", "ASSOCIATION");
        consultaReceta.put("sourceMultiplicity", "1..*");
        relacion(relaciones.addObject(), "RecetaMedica", "MedicamentoIndicado", "ASSOCIATION");
        plan.putArray("audit");

        RespuestaIA normalizada = NormalizadorPlanUml.normalizar(new RespuestaIA(
                List.of(new ComandoPropuestoIA(EsquemaPlanUml.NOMBRE, plan)), ""),
                "Gestiona citas, consultas, diagnosticos, recetas y medicamentos indicados.");
        var r = normalizada.comandos().getFirst().parametros().path("relations");

        assertThat(r.get(0).path("sourceMultiplicity").asText()).isEqualTo("1");
        assertThat(r.get(0).path("targetMultiplicity").asText()).isEqualTo("0..*");
        assertThat(r.get(1).path("sourceMultiplicity").asText()).isEqualTo("0..1");
        assertThat(r.get(1).path("targetMultiplicity").asText()).isEqualTo("0..1");
        assertThat(r.get(2).path("sourceMultiplicity").asText()).isEqualTo("1");
        assertThat(r.get(2).path("targetMultiplicity").asText()).isEqualTo("0..*");
        assertThat(r.get(3).path("sourceMultiplicity").asText()).isEqualTo("1");
        assertThat(r.get(3).path("targetMultiplicity").asText()).isEqualTo("0..1");
        assertThat(r.get(4).path("sourceMultiplicity").asText()).isEqualTo("1");
        assertThat(r.get(4).path("targetMultiplicity").asText()).isEqualTo("1..*");
    }

    @Test
    void membresia_permite_varias_participaciones_sin_alterar_otras_relaciones() {
        ObjectNode plan = J.objectNode();
        var clases = plan.putArray("classes");
        clase(clases.addObject(), "Usuario", "nombreCompleto");
        clase(clases.addObject(), "ModeloColaborativo", "titulo");
        clase(clases.addObject(), "Participante", "rolAsignado");
        clase(clases.addObject(), "ElementoModelo", "contenido");
        var relaciones = plan.putArray("relations");
        ObjectNode usuarioParticipante = relaciones.addObject();
        relacion(usuarioParticipante, "Usuario", "Participante", "ASSOCIATION");
        usuarioParticipante.put("targetMultiplicity", "0..1");
        relacion(relaciones.addObject(), "ModeloColaborativo", "Participante", "ASSOCIATION");
        ObjectNode participanteElemento = relaciones.addObject();
        relacion(participanteElemento, "Participante", "ElementoModelo", "ASSOCIATION");
        participanteElemento.put("sourceMultiplicity", "1").put("targetMultiplicity", "0..*");
        plan.putArray("audit");

        RespuestaIA normalizada = NormalizadorPlanUml.normalizar(new RespuestaIA(
                List.of(new ComandoPropuestoIA(EsquemaPlanUml.NOMBRE, plan)), ""),
                "Un usuario puede participar con distintos roles en diferentes modelos colaborativos.");
        var r = normalizada.comandos().getFirst().parametros().path("relations");

        assertThat(r.get(0).path("sourceMultiplicity").asText()).isEqualTo("1");
        assertThat(r.get(0).path("targetMultiplicity").asText()).isEqualTo("0..*");
        assertThat(r.get(1).path("sourceMultiplicity").asText()).isEqualTo("1");
        assertThat(r.get(1).path("targetMultiplicity").asText()).isEqualTo("0..*");
        assertThat(r.get(2).path("sourceMultiplicity").asText()).isEqualTo("1");
        assertThat(r.get(2).path("targetMultiplicity").asText()).isEqualTo("0..*");
    }

    @Test
    void catalogo_con_identidad_se_reutiliza_entre_muchas_entidades() {
        ObjectNode plan = J.objectNode();
        var clases = plan.putArray("classes");
        clase(clases.addObject(), "Proyecto", "titulo");
        clase(clases.addObject(), "EstadoProyecto", "codigoEstado");
        clase(clases.addObject(), "Documento", "nombreArchivo");
        clase(clases.addObject(), "TipoDocumento", "codigoTipo");
        var relaciones = plan.putArray("relations");
        ObjectNode proyectoEstado = relaciones.addObject();
        relacion(proyectoEstado, "Proyecto", "EstadoProyecto", "ASSOCIATION");
        proyectoEstado.put("sourceMultiplicity", "1").put("targetMultiplicity", "1");
        ObjectNode tipoDocumento = relaciones.addObject();
        relacion(tipoDocumento, "TipoDocumento", "Documento", "ASSOCIATION");
        tipoDocumento.put("sourceMultiplicity", "1").put("targetMultiplicity", "1");
        plan.putArray("audit");

        RespuestaIA normalizada = NormalizadorPlanUml.normalizar(new RespuestaIA(
                List.of(new ComandoPropuestoIA(EsquemaPlanUml.NOMBRE, plan)), ""),
                "Cada proyecto tiene un estado y cada documento tiene un tipo.");
        var r = normalizada.comandos().getFirst().parametros().path("relations");

        assertThat(r.get(0).path("sourceMultiplicity").asText()).isEqualTo("0..*");
        assertThat(r.get(0).path("targetMultiplicity").asText()).isEqualTo("1");
        assertThat(r.get(1).path("sourceMultiplicity").asText()).isEqualTo("1");
        assertThat(r.get(1).path("targetMultiplicity").asText()).isEqualTo("0..*");
    }

    @Test
    void indicacion_reemplaza_relacion_directa_y_conecta_receta_con_medicamento() {
        ObjectNode plan = J.objectNode();
        var clases = plan.putArray("classes");
        clase(clases.addObject(), "Receta", "fechaEmision");
        clase(clases.addObject(), "Medicamento", "nombre");
        clase(clases.addObject(), "IndicacionMedicamento", "identificadorUnico");
        var relaciones = plan.putArray("relations");
        relacion(relaciones.addObject(), "Receta", "Medicamento", "ASSOCIATION");
        relacion(relaciones.addObject(), "Medicamento", "IndicacionMedicamento", "ASSOCIATION");
        plan.putArray("audit");

        RespuestaIA normalizada = NormalizadorPlanUml.normalizar(new RespuestaIA(
                List.of(new ComandoPropuestoIA(EsquemaPlanUml.NOMBRE, plan)), ""),
                "Cada receta contiene medicamentos con dosis e indicaciones propias.");
        var resultado = normalizada.comandos().getFirst().parametros();
        var r = resultado.path("relations");

        assertThat(r.get(0).path("source").asText()).isEqualTo("Receta");
        assertThat(r.get(0).path("target").asText()).isEqualTo("IndicacionMedicamento");
        assertThat(r.get(0).path("sourceMultiplicity").asText()).isEqualTo("1");
        assertThat(r.get(0).path("targetMultiplicity").asText()).isEqualTo("1..*");
        assertThat(r.get(1).path("sourceMultiplicity").asText()).isEqualTo("1");
        assertThat(r.get(1).path("targetMultiplicity").asText()).isEqualTo("0..*");
        assertThat(resultado.path("classes").get(2).path("attributes").toString()).contains("dosis");
        assertThat(resultado.path("audit").toString()).contains("asociativa");
    }

    @Test
    void elimina_relacion_directa_si_la_indicacion_ya_conecta_receta_y_medicamento() {
        ObjectNode plan = J.objectNode();
        var clases = plan.putArray("classes");
        clase(clases.addObject(), "Consulta", "fechaHora");
        clase(clases.addObject(), "Diagnostico", "descripcion");
        clase(clases.addObject(), "Receta", "fechaEmision");
        clase(clases.addObject(), "Medicamento", "codigo");
        clase(clases.addObject(), "IndicacionMedicamento", "dosis");
        var relaciones = plan.putArray("relations");
        ObjectNode consultaDiagnostico = relaciones.addObject();
        relacion(consultaDiagnostico, "Consulta", "Diagnostico", "ASSOCIATION");
        consultaDiagnostico.put("sourceMultiplicity", "1..*");
        ObjectNode consultaReceta = relaciones.addObject();
        relacion(consultaReceta, "Consulta", "Receta", "ASSOCIATION");
        consultaReceta.put("sourceMultiplicity", "1..*").put("targetMultiplicity", "0..1");
        relacion(relaciones.addObject(), "Receta", "Medicamento", "ASSOCIATION");
        ObjectNode medicamentoIndicacion = relaciones.addObject();
        relacion(medicamentoIndicacion, "Medicamento", "IndicacionMedicamento", "ASSOCIATION");
        medicamentoIndicacion.put("sourceMultiplicity", "1..*");
        relacion(relaciones.addObject(), "Receta", "IndicacionMedicamento", "ASSOCIATION");
        plan.putArray("audit");

        RespuestaIA normalizada = NormalizadorPlanUml.normalizar(new RespuestaIA(
                List.of(new ComandoPropuestoIA(EsquemaPlanUml.NOMBRE, plan)), ""),
                "Una consulta registra diagnosticos y puede emitir una receta con indicaciones de medicamentos.");
        var r = normalizada.comandos().getFirst().parametros().path("relations");

        assertThat(r).hasSize(4);
        assertThat(r.get(0).path("sourceMultiplicity").asText()).isEqualTo("1");
        assertThat(r.get(0).path("targetMultiplicity").asText()).isEqualTo("0..*");
        assertThat(r.get(1).path("sourceMultiplicity").asText()).isEqualTo("1");
        assertThat(r.get(1).path("targetMultiplicity").asText()).isEqualTo("0..1");
        assertThat(r.toString()).doesNotContain("\"source\":\"Receta\",\"target\":\"Medicamento\"");
        assertThat(r.toString()).contains("\"source\":\"Medicamento\",\"target\":\"IndicacionMedicamento\"",
                "\"sourceMultiplicity\":\"1\"", "\"targetMultiplicity\":\"0..*\"");
    }

    @Test
    void intercala_indicacion_desconectada_entre_receta_y_medicamento() {
        ObjectNode plan = J.objectNode();
        var clases = plan.putArray("classes");
        clase(clases.addObject(), "RecetaMedica", "fechaEmision");
        clase(clases.addObject(), "Medicamento", "nombreGenerico");
        clase(clases.addObject(), "IndicacionMedicamento", "dosis");
        var relaciones = plan.putArray("relations");
        relacion(relaciones.addObject(), "RecetaMedica", "Medicamento", "ASSOCIATION");
        plan.putArray("audit");

        RespuestaIA normalizada = NormalizadorPlanUml.normalizar(new RespuestaIA(
                List.of(new ComandoPropuestoIA(EsquemaPlanUml.NOMBRE, plan)), ""),
                "Una receta contiene medicamentos con dosis, frecuencia y duracion.");
        var resultado = normalizada.comandos().getFirst().parametros();
        var r = resultado.path("relations");

        assertThat(r).hasSize(2);
        assertThat(r).anySatisfy(relacion -> {
            assertThat(relacion.path("source").asText()).isEqualTo("RecetaMedica");
            assertThat(relacion.path("target").asText()).isEqualTo("IndicacionMedicamento");
            assertThat(relacion.path("sourceMultiplicity").asText()).isEqualTo("1");
            assertThat(relacion.path("targetMultiplicity").asText()).isEqualTo("1..*");
        });
        assertThat(r).anySatisfy(relacion -> {
            assertThat(relacion.path("source").asText()).isEqualTo("IndicacionMedicamento");
            assertThat(relacion.path("target").asText()).isEqualTo("Medicamento");
            assertThat(relacion.path("sourceMultiplicity").asText()).isEqualTo("0..*");
            assertThat(relacion.path("targetMultiplicity").asText()).isEqualTo("1");
        });
        assertThat(resultado.path("audit").toString()).contains("asociativa");
    }

    @Test
    void intercala_detalle_generico_entre_pedido_y_producto() {
        ObjectNode plan = J.objectNode();
        var clases = plan.putArray("classes");
        clase(clases.addObject(), "Pedido", "fechaPedido");
        clase(clases.addObject(), "Producto", "nombre");
        clase(clases.addObject(), "DetallePedido", "cantidad");
        var relaciones = plan.putArray("relations");
        relacion(relaciones.addObject(), "Producto", "Pedido", "ASSOCIATION");
        plan.putArray("audit");

        RespuestaIA normalizada = NormalizadorPlanUml.normalizar(new RespuestaIA(
                List.of(new ComandoPropuestoIA(EsquemaPlanUml.NOMBRE, plan)), ""),
                "Cada pedido contiene productos y cada detalle registra la cantidad.");
        var r = normalizada.comandos().getFirst().parametros().path("relations");

        assertThat(r).hasSize(2);
        assertThat(r.toString()).contains("\"source\":\"Pedido\",\"target\":\"DetallePedido\"",
                "\"source\":\"DetallePedido\",\"target\":\"Producto\"");
        assertThat(r.toString()).doesNotContain("\"source\":\"Producto\",\"target\":\"Pedido\"");
    }

    @Test
    void elimina_copia_inversa_pero_conserva_roles_distintos() {
        ObjectNode plan = J.objectNode();
        var clases = plan.putArray("classes");
        clase(clases.addObject(), "Proyecto", "titulo");
        clase(clases.addObject(), "Documento", "contenido");
        var relaciones = plan.putArray("relations");
        relacion(relaciones.addObject(), "Proyecto", "Documento", "ASSOCIATION");
        ((ObjectNode) relaciones.get(0)).put("name", "contiene");
        relacion(relaciones.addObject(), "Documento", "Proyecto", "ASSOCIATION");
        ((ObjectNode) relaciones.get(1)).put("name", "contiene")
                .put("sourceMultiplicity", "0..*").put("targetMultiplicity", "1");
        relacion(relaciones.addObject(), "Proyecto", "Documento", "ASSOCIATION");
        ((ObjectNode) relaciones.get(2)).put("name", "destaca").put("targetRole", "destacado");
        plan.putArray("audit");
        var resultado = NormalizadorPlanUml.normalizar(new RespuestaIA(List.of(
                new ComandoPropuestoIA(EsquemaPlanUml.NOMBRE, plan)), ""), "Gestion documental");
        assertThat(resultado.comandos().getFirst().parametros().path("relations")).hasSize(2);
    }

    private static void clase(ObjectNode clase, String nombre, String atributo) {
        clase.put("name", nombre);
        clase.putArray("attributes").addObject().put("name", atributo).put("dataType", "string")
                .put("required", true).put("unique", false).put("pk", false);
    }

    @Test
    void completa_la_segunda_asignatura_de_un_prerrequisito_sin_inventar_datos() {
        ObjectNode p = J.objectNode(); var cs = p.putArray("classes");
        clase(cs.addObject(), "Plan", "version"); clase(cs.addObject(), "Asignatura", "nombre");
        cs.addObject().put("name", "Prerrequisito").putArray("attributes")
                .addObject().put("name", "id").put("dataType", "uuid").put("pk", true);
        relacion(p.putArray("relations").addObject(), "Prerrequisito", "Asignatura", "ASSOCIATION");
        p.putArray("audit");
        var respuesta = NormalizadorPlanUml.normalizar(new RespuestaIA(List.of(
                new ComandoPropuestoIA(EsquemaPlanUml.NOMBRE, p)), ""), "Plan academico con asignaturas y prerrequisitos");
        assertThat(ValidadorPlanFinal.revisar(respuesta)).isEmpty();
        assertThat(respuesta.comandos().getFirst().parametros().path("relations")).hasSize(3);
        assertThat(respuesta.comandos().getFirst().parametros().path("classes").get(2).path("attributes")).hasSize(1);
    }

    @Test
    void solo_completa_inventario_y_prestatario_cuando_el_requisito_los_pide() {
        ObjectNode p = J.objectNode(); var cs = p.putArray("classes");
        clase(cs.addObject(), "Producto", "codigo"); clase(cs.addObject(), "Inventario", "cantidadDisponible");
        clase(cs.addObject(), "Cliente", "nombre"); clase(cs.addObject(), "Prestamo", "fechaInicio");
        p.putArray("relations"); p.putArray("audit");
        var original = new RespuestaIA(List.of(new ComandoPropuestoIA(EsquemaPlanUml.NOMBRE, p)), "");
        assertThat(NormalizadorPlanUml.normalizar(original, "Diagrama de ejemplo").comandos().getFirst()
                .parametros().path("relations")).isEmpty();
        var resultado = NormalizadorPlanUml.normalizar(original,
                "Inventario actual y prestamos donde cada prestamo identifica al cliente prestatario")
                .comandos().getFirst().parametros().path("relations");
        assertThat(resultado).hasSize(2).anySatisfy(r -> {
            assertThat(r.path("source").asText()).isEqualTo("Cliente");
            assertThat(r.path("target").asText()).isEqualTo("Prestamo");
            assertThat(r.path("sourceMultiplicity").asText()).isEqualTo("1");
            assertThat(r.path("targetMultiplicity").asText()).isEqualTo("0..*");
        });
    }

    @Test
    void cancelacion_identifica_una_reserva_y_no_es_obligatoria_para_todas() {
        ObjectNode p = J.objectNode(); var cs = p.putArray("classes");
        clase(cs.addObject(), "Reserva", "fechaReserva"); clase(cs.addObject(), "Cancelacion", "fechaCancelacion");
        var r = p.putArray("relations").addObject();
        relacion(r, "Reserva", "Cancelacion", "ASSOCIATION");
        r.put("sourceMultiplicity", "0..*").put("targetMultiplicity", "1");
        p.putArray("audit");
        var resultado = NormalizadorPlanUml.normalizar(new RespuestaIA(List.of(
                new ComandoPropuestoIA(EsquemaPlanUml.NOMBRE, p)), ""), "Historial de reservas y cancelaciones");
        var corregida = resultado.comandos().getFirst().parametros().path("relations").get(0);
        assertThat(corregida.path("sourceMultiplicity").asText()).isEqualTo("1");
        assertThat(corregida.path("targetMultiplicity").asText()).isEqualTo("0..1");
    }

    @Test
    void garantiza_una_compra_por_garantia_sin_imponer_obligatoriedad_ni_numero_de_garantias() {
        ObjectNode p = J.objectNode(); var cs = p.putArray("classes");
        clase(cs.addObject(), "Garantia", "fechaInicio");
        clase(cs.addObject(), "DetalleVenta", "cantidad");
        var r = p.putArray("relations").addObject();
        relacion(r, "Garantia", "DetalleVenta", "ASSOCIATION");
        r.put("sourceMultiplicity", "0..1").put("targetMultiplicity", "0..*");
        p.putArray("audit");
        var resultado = NormalizadorPlanUml.normalizar(new RespuestaIA(List.of(
                new ComandoPropuestoIA(EsquemaPlanUml.NOMBRE, p)), ""), "Garantias de ventas");
        var corregida = resultado.comandos().getFirst().parametros().path("relations").get(0);
        assertThat(corregida.path("sourceMultiplicity").asText()).isEqualTo("0..1");
        assertThat(corregida.path("targetMultiplicity").asText()).isEqualTo("1");
        // La orientacion inversa debe tener la misma lectura.
        r.put("source", "DetalleVenta").put("target", "Garantia")
                .put("sourceMultiplicity", "0..*").put("targetMultiplicity", "0..*");
        var inversa = NormalizadorPlanUml.normalizar(new RespuestaIA(List.of(
                new ComandoPropuestoIA(EsquemaPlanUml.NOMBRE, p)), ""), "Garantias de ventas")
                .comandos().getFirst().parametros().path("relations").get(0);
        assertThat(inversa.path("sourceMultiplicity").asText()).isEqualTo("1");
        assertThat(inversa.path("targetMultiplicity").asText()).isEqualTo("0..*");
    }

    @Test
    void no_aplica_reglas_de_eventos_o_detalles_a_una_herencia() {
        ObjectNode p = J.objectNode(); var cs = p.putArray("classes");
        clase(cs.addObject(), "Pago", "fechaPago");
        clase(cs.addObject(), "Operacion", "descripcion");
        clase(cs.addObject(), "DetallePago", "descripcion");
        var rs = p.putArray("relations");
        relacion(rs.addObject(), "Pago", "Operacion", "GENERALIZATION");
        relacion(rs.addObject(), "DetallePago", "Pago", "GENERALIZATION");
        p.putArray("audit");
        var resultado = NormalizadorPlanUml.normalizar(new RespuestaIA(List.of(
                new ComandoPropuestoIA(EsquemaPlanUml.NOMBRE, p)), ""), "Jerarquia de operaciones");
        for (var r : resultado.comandos().getFirst().parametros().path("relations")) {
            assertThat(r.path("kind").asText()).isEqualTo("GENERALIZATION");
            assertThat(r.path("sourceMultiplicity").asText()).isEqualTo("1");
            assertThat(r.path("targetMultiplicity").asText()).isEqualTo("0..*");
        }
    }

    @Test
    void recorte_no_elimina_fecha_e_importe_del_pago_para_conservar_descripciones() {
        ObjectNode p = J.objectNode();
        var cs = p.putArray("classes");
        clase(cs.addObject(), "Venta", "fechaVenta");
        clase(cs.addObject(), "Pago", "nombre");
        var attrs = (com.fasterxml.jackson.databind.node.ArrayNode) cs.get(1).path("attributes");
        for (int i = 0; i < 50; i++) attrs.addObject().put("name", "descripcion" + i).put("dataType", "string");
        attrs.addObject().put("name", "fechaPago").put("dataType", "datetime");
        attrs.addObject().put("name", "monto").put("dataType", "decimal");
        relacion(p.putArray("relations").addObject(), "Venta", "Pago", "ASSOCIATION");
        p.putArray("audit");
        var respuesta = NormalizadorPlanUml.normalizar(new RespuestaIA(List.of(
                new ComandoPropuestoIA(EsquemaPlanUml.NOMBRE, p)), ""), "Historial de pagos");
        assertThat(RevisorCoberturaPlan.revisar(respuesta, "Historial de pagos")).isEmpty();
        assertThat(ValidadorPlanFinal.revisar(respuesta)).isEmpty();
        var finales = respuesta.comandos().getFirst().parametros().path("classes").get(1).path("attributes");
        assertThat(finales).anyMatch(a -> a.path("name").asText().equals("fechaPago"))
                .anyMatch(a -> a.path("name").asText().equals("monto"));
    }

    @Test
    void banco_completa_titularidad_a_partir_del_vinculo_explicito_y_conserva_roles_de_transferencia() {
        ObjectNode p = J.objectNode();
        var clases = p.putArray("classes");
        clase(clases.addObject(), "Cliente", "nombre");
        clase(clases.addObject(), "Cuenta", "numero");
        clase(clases.addObject(), "Titular", "porcentajePropiedad");
        clase(clases.addObject(), "Transferencia", "fechaEjecucion");
        var rs = p.putArray("relations");
        relacion(rs.addObject(), "Cliente", "Cuenta", "ASSOCIATION");
        relacion(rs.addObject(), "Cuenta", "Titular", "ASSOCIATION");
        for (String rol : List.of("origen", "destino")) {
            relacion(rs.addObject(), "Transferencia", "Cuenta", "ASSOCIATION");
            ((ObjectNode) rs.get(rs.size() - 1)).put("targetRole", rol)
                    .put("sourceMultiplicity", "1").put("targetMultiplicity", "1");
        }
        p.putArray("audit");
        var respuesta = NormalizadorPlanUml.normalizar(new RespuestaIA(List.of(
                new ComandoPropuestoIA(EsquemaPlanUml.NOMBRE, p)), ""), "Cuentas y transferencias");
        var resultado = respuesta.comandos().getFirst().parametros();
        assertThat(ValidadorPlanFinal.revisar(respuesta)).isEmpty();
        assertThat(resultado.path("relations").get(0).path("target").asText()).isEqualTo("Titular");
        for (int i : List.of(2, 3)) {
            assertThat(resultado.path("relations").get(i).path("sourceMultiplicity").asText()).isEqualTo("0..*");
            assertThat(resultado.path("relations").get(i).path("targetMultiplicity").asText()).isEqualTo("1");
        }
        assertThat(resultado.path("relations").get(2).path("targetRole").asText()).isEqualTo("origen");
        assertThat(resultado.path("relations").get(3).path("targetRole").asText()).isEqualTo("destino");
    }

    @Test
    void aplicacion_producto_permite_historial_y_retiro_auditoria_contradictoria() {
        ObjectNode p = J.objectNode();
        var clases = p.putArray("classes");
        clase(clases.addObject(), "Animal", "codigo");
        clase(clases.addObject(), "AplicacionProducto", "dosis");
        clase(clases.addObject(), "ProductoSanitario", "nombre");
        var rs = p.putArray("relations");
        relacion(rs.addObject(), "AplicacionProducto", "Animal", "ASSOCIATION");
        relacion(rs.addObject(), "AplicacionProducto", "ProductoSanitario", "ASSOCIATION");
        p.putArray("audit").add("Inventario representa estado actual sin historial y es una entidad historica.");
        var respuesta = NormalizadorPlanUml.normalizar(new RespuestaIA(List.of(
                new ComandoPropuestoIA(EsquemaPlanUml.NOMBRE, p)), ""), "Aplicaciones sanitarias");
        var resultado = respuesta.comandos().getFirst().parametros();
        for (var r : resultado.path("relations")) {
            assertThat(r.path("sourceMultiplicity").asText()).isEqualTo("0..*");
            assertThat(r.path("targetMultiplicity").asText()).isEqualTo("1");
        }
        assertThat(resultado.path("audit").toString()).doesNotContain("es una entidad historica");
    }

    @Test
    void movimiento_entre_lotes_completa_origen_y_destino_sin_esas_palabras_en_el_pedido() {
        // Ganaderia, 2026-11-08: el revisor exigia roles por "entre lotes" y el
        // normalizador solo los completaba si el pedido decia origen y destino.
        ObjectNode p = J.objectNode();
        var cs = p.putArray("classes");
        clase(cs.addObject(), "Lote", "nombre");
        clase(cs.addObject(), "Animal", "sexo");
        clase(cs.addObject(), "MovimientoAnimal", "fechaMovimiento");
        var rs = p.putArray("relations");
        relacion(rs.addObject(), "Animal", "MovimientoAnimal", "ASSOCIATION");
        relacion(rs.addObject(), "MovimientoAnimal", "Lote", "ASSOCIATION");
        relacion(rs.addObject(), "Lote", "Animal", "ASSOCIATION");
        p.putArray("audit");
        String pedido = "Registrar movimientos de animales entre lotes o establecimientos";
        var n = NormalizadorPlanUml.normalizar(new RespuestaIA(List.of(new ComandoPropuestoIA(EsquemaPlanUml.NOMBRE, p)), ""), pedido);
        var relaciones = n.comandos().getFirst().parametros().path("relations");
        assertThat(relaciones).filteredOn(r -> r.toString().contains("MovimientoAnimal") && r.toString().contains("Lote"))
                .extracting(r -> r.path("source").asText().equals("Lote") ? r.path("sourceRole").asText()
                        : r.path("targetRole").asText())
                .containsExactlyInAnyOrder("loteOrigen", "loteDestino");
        assertThat(RevisorCoberturaPlan.revisar(n, pedido)).noneMatch(e -> e.contains("origen y destino"));
    }

    @Test
    void transferencia_completa_dos_roles_en_el_extremo_cuenta_y_es_idempotente() {
        ObjectNode p = J.objectNode();
        var cs = p.putArray("classes");
        clase(cs.addObject(), "Cuenta", "numero");
        clase(cs.addObject(), "Transferencia", "fecha");
        var rs = p.putArray("relations");
        relacion(rs.addObject(), "Transferencia", "Cuenta", "ASSOCIATION");
        ((ObjectNode) rs.get(0)).put("sourceRole", "destino").put("targetRole", "origen");
        p.putArray("audit");
        String pedido = "Transferencias entre cuentas con origen y destino";
        var n = NormalizadorPlanUml.normalizar(new RespuestaIA(List.of(new ComandoPropuestoIA(EsquemaPlanUml.NOMBRE, p)), ""), pedido);
        var finalPlan = n.comandos().getFirst().parametros();
        assertThat(finalPlan.path("relations")).hasSize(2);
        assertThat(finalPlan.path("relations")).anyMatch(r -> r.path("targetRole").asText().toLowerCase().contains("destino"));
        assertThat(ValidadorPlanFinal.revisar(n)).isEmpty();
        assertThat(NormalizadorPlanUml.normalizar(n, pedido).comandos().getFirst().parametros().path("relations"))
                .isEqualTo(finalPlan.path("relations"));
    }

    @Test
    void corrige_roles_invertidos_sin_duplicar_cuentas_de_origen_y_destino() {
        ObjectNode p = J.objectNode(); var cs = p.putArray("classes");
        clase(cs.addObject(), "Cuenta", "numero"); clase(cs.addObject(), "Transferencia", "fecha");
        var rs = p.putArray("relations");
        for (String rol : List.of("Origen", "Destino")) {
            relacion(rs.addObject(), "Transferencia", "Cuenta", "ASSOCIATION");
            ((ObjectNode) rs.get(rs.size() - 1)).put("sourceRole", "cuenta" + rol)
                    .put("targetRole", "movimientos" + (rol.equals("Origen") ? "Salientes" : "Entrantes"));
        }
        p.putArray("audit");
        var n = NormalizadorPlanUml.normalizar(new RespuestaIA(List.of(new ComandoPropuestoIA(EsquemaPlanUml.NOMBRE, p)), ""),
                "Transferencias con cuenta origen y destino");
        assertThat(n.comandos().getFirst().parametros().path("relations")).hasSize(2)
                .anyMatch(r -> r.path("targetRole").asText().equals("cuentaOrigen"))
                .anyMatch(r -> r.path("targetRole").asText().equals("cuentaDestino"));
    }

    @Test
    void completa_genealogia_y_aplicacion_con_extremos_explicitos_sin_clases_nuevas() {
        ObjectNode p = J.objectNode();
        var cs = p.putArray("classes");
        clase(cs.addObject(), "Animal", "codigo");
        clase(cs.addObject(), "AplicacionProducto", "dosis");
        clase(cs.addObject(), "ProductoSanitario", "nombre");
        clase(cs.addObject(), "RelacionPadreHijo", "id");
        p.putArray("relations"); p.putArray("audit");
        var n = NormalizadorPlanUml.normalizar(new RespuestaIA(List.of(new ComandoPropuestoIA(EsquemaPlanUml.NOMBRE, p)), ""),
                "Animal con padre y madre y aplicaciones de productos sanitarios");
        assertThat(n.comandos().getFirst().parametros().path("classes")).hasSize(3);
        assertThat(n.comandos().getFirst().parametros().path("relations")).hasSize(4);
        assertThat(ValidadorPlanFinal.revisar(n)).isEmpty();
    }

    @Test
    void recupera_asociaciones_de_referencias_explicitas_antes_de_retirarlas() {
        ObjectNode p = J.objectNode(); var cs = p.putArray("classes");
        clase(cs.addObject(), "Plan", "nombre");
        clase(cs.addObject(), "Asignatura", "nombre");
        var asignacion = cs.addObject(); clase(asignacion, "Asignacion", "planId");
        ((ObjectNode) asignacion.path("attributes").get(0)).put("required", true);
        ((com.fasterxml.jackson.databind.node.ArrayNode) asignacion.path("attributes")).addObject()
                .put("name", "asignaturaId").put("dataType", "uuid").put("required", true);
        p.putArray("relations"); p.putArray("audit");
        var n = NormalizadorPlanUml.normalizar(new RespuestaIA(List.of(new ComandoPropuestoIA(EsquemaPlanUml.NOMBRE, p)), ""), "Asignaciones por plan y asignatura");
        assertThat(n.comandos().getFirst().parametros().path("relations")).hasSize(2);
        assertThat(n.comandos().getFirst().parametros().path("classes").get(2).path("attributes"))
                .noneMatch(a -> a.path("name").asText().equals("planId") || a.path("name").asText().equals("asignaturaId"));
        assertThat(ValidadorPlanFinal.revisar(n)).isEmpty();
    }

    @Test
    void participantes_identifican_la_reserva_y_el_viajero_sin_duplicar_personas() {
        ObjectNode p = J.objectNode(); var cs = p.putArray("classes");
        clase(cs.addObject(), "Reserva", "fecha"); clase(cs.addObject(), "Viajero", "nombre");
        clase(cs.addObject(), "ParticipanteReserva", "esTitular");
        var rs = p.putArray("relations"); relacion(rs.addObject(), "Reserva", "ParticipanteReserva", "ASSOCIATION");
        p.putArray("audit");
        var n = NormalizadorPlanUml.normalizar(new RespuestaIA(List.of(new ComandoPropuestoIA(EsquemaPlanUml.NOMBRE, p)), ""),
                "Reservas con viajeros participantes");
        assertThat(n.comandos().getFirst().parametros().path("relations")).hasSize(2);
        assertThat(RevisorCoberturaPlan.revisar(n, "Reservas con viajeros participantes")).isEmpty();
    }

    @Test
    void catalogo_academico_no_limita_un_plan_a_una_asignatura_ni_un_requisito_a_varias() {
        ObjectNode p = J.objectNode(); var cs = p.putArray("classes");
        clase(cs.addObject(), "Plan", "nombre"); clase(cs.addObject(), "Asignatura", "nombre");
        ((com.fasterxml.jackson.databind.node.ArrayNode) cs.get(0).path("attributes")).addObject()
                .put("name", "vigenciaInicio").put("dataType", "date");
        clase(cs.addObject(), "Prerrequisito", "id");
        var rs = p.putArray("relations");
        relacion(rs.addObject(), "Plan", "Asignatura", "ASSOCIATION");
        ((ObjectNode) rs.get(0)).put("sourceMultiplicity", "0..*").put("targetMultiplicity", "1");
        relacion(rs.addObject(), "Asignatura", "Prerrequisito", "ASSOCIATION");
        ((ObjectNode) rs.get(1)).put("sourceRole", "actual").put("targetRole", "requisitos");
        relacion(rs.addObject(), "Prerrequisito", "Asignatura", "ASSOCIATION");
        ((ObjectNode) rs.get(2)).put("sourceRole", "requisitoDe").put("targetRole", "requerida");
        p.putArray("audit");
        var n = NormalizadorPlanUml.normalizar(new RespuestaIA(List.of(new ComandoPropuestoIA(EsquemaPlanUml.NOMBRE, p)), ""),
                "Planes y catalogo de asignaturas con prerrequisitos");
        var finales = n.comandos().getFirst().parametros().path("relations");
        assertThat(finales.get(0).path("sourceMultiplicity").asText()).isEqualTo("0..*");
        assertThat(finales.get(0).path("targetMultiplicity").asText()).isEqualTo("0..*");
        assertThat(finales.get(2).path("sourceMultiplicity").asText()).isEqualTo("0..*");
        assertThat(finales.get(2).path("targetMultiplicity").asText()).isEqualTo("1");
        assertThat(ValidadorPlanFinal.revisar(n)).isEmpty();
    }

    @Test
    void salidas_conservan_historial_y_cada_participacion_identifica_una_reserva_y_un_viajero() {
        ObjectNode p = J.objectNode(); var cs = p.putArray("classes");
        for (String nombre : List.of("Salida", "Paquete", "Destino", "Reserva", "Participante", "Viajero"))
            clase(cs.addObject(), nombre, "nombre");
        var rs = p.putArray("relations");
        relacion(rs.addObject(), "Salida", "Paquete", "ASSOCIATION");
        relacion(rs.addObject(), "Salida", "Destino", "ASSOCIATION");
        ((ObjectNode) rs.get(1)).put("targetMultiplicity", "1");
        relacion(rs.addObject(), "Participante", "Reserva", "ASSOCIATION");
        relacion(rs.addObject(), "Participante", "Viajero", "ASSOCIATION");
        relacion(rs.addObject(), "Reserva", "Salida", "ASSOCIATION");
        p.putArray("audit");
        var n = NormalizadorPlanUml.normalizar(new RespuestaIA(List.of(new ComandoPropuestoIA(EsquemaPlanUml.NOMBRE, p)), ""),
                "Paquetes con salidas y reservas de viajeros participantes");
        var finales = n.comandos().getFirst().parametros().path("relations");
        for (int i : List.of(0, 1, 2, 3)) {
            assertThat(finales.get(i).path("sourceMultiplicity").asText()).isEqualTo("0..*");
            assertThat(finales.get(i).path("targetMultiplicity").asText()).isEqualTo("1");
        }
    }

    @Test
    void reconoce_una_participacion_curricular_por_sus_datos_y_no_agrega_otro_vinculo_directo() {
        ObjectNode p = J.objectNode(); var cs = p.putArray("classes");
        clase(cs.addObject(), "Plan", "nombre"); clase(cs.addObject(), "Asignatura", "nombre");
        clase(cs.addObject(), "ParticipacionAsignaturaPlan", "creditos");
        clase(cs.addObject(), "Prerrequisito", "id");
        var rs = p.putArray("relations");
        relacion(rs.addObject(), "Plan", "ParticipacionAsignaturaPlan", "ASSOCIATION");
        relacion(rs.addObject(), "ParticipacionAsignaturaPlan", "Asignatura", "ASSOCIATION");
        ((ObjectNode) rs.get(1)).put("targetMultiplicity", "1");
        relacion(rs.addObject(), "Asignatura", "Prerrequisito", "ASSOCIATION");
        ((ObjectNode) rs.get(2)).put("sourceRole", "actual").put("targetRole", "requisitos");
        relacion(rs.addObject(), "Prerrequisito", "Asignatura", "ASSOCIATION");
        ((ObjectNode) rs.get(3)).put("sourceRole", "requisitoDe").put("targetRole", "requerida");
        p.putArray("audit");
        var n = NormalizadorPlanUml.normalizar(new RespuestaIA(List.of(new ComandoPropuestoIA(EsquemaPlanUml.NOMBRE, p)), ""),
                "Planes y catalogo de asignaturas con creditos en cada plan y prerrequisitos");
        var finales = n.comandos().getFirst().parametros().path("relations");
        assertThat(finales).hasSize(4);
        assertThat(finales.get(1).path("sourceMultiplicity").asText()).isEqualTo("0..*");
        assertThat(finales.get(1).path("targetMultiplicity").asText()).isEqualTo("1");
        assertThat(RevisorCoberturaPlan.revisar(n, "Planes con creditos en cada plan y prerrequisitos")).isEmpty();
    }

    @Test
    void catalogo_reutilizable_se_vincula_al_paquete_sin_duplicar_su_camino_existente() {
        ObjectNode p = J.objectNode(); var cs = p.putArray("classes");
        for (String n : List.of("Paquete", "ServicioTuristico", "Destino")) clase(cs.addObject(), n, "nombre");
        var rs = p.putArray("relations"); relacion(rs.addObject(), "ServicioTuristico", "Destino", "ASSOCIATION");
        p.putArray("audit");
        String req = "Paquetes con servicios turisticos reutilizables";
        var n = NormalizadorPlanUml.normalizar(new RespuestaIA(List.of(new ComandoPropuestoIA(EsquemaPlanUml.NOMBRE, p)), ""), req);
        assertThat(n.comandos().getFirst().parametros().path("relations")).hasSize(2)
                .anyMatch(r -> r.path("source").asText().equals("Paquete") && r.path("target").asText().equals("ServicioTuristico")
                        && r.path("sourceMultiplicity").asText().equals("0..*") && r.path("targetMultiplicity").asText().equals("0..*"));
        assertThat(NormalizadorPlanUml.normalizar(n, req).comandos().getFirst().parametros().path("relations")).hasSize(2);
    }

    @Test
    void agregacion_con_fk_obligatoria_pasa_a_asociacion_y_la_opcional_se_conserva() {
        ObjectNode plan = J.objectNode();
        var clases = plan.putArray("classes");
        clase(clases.addObject(), "Producto", "nombre");
        clase(clases.addObject(), "Inventario", "cantidadDisponible");
        clase(clases.addObject(), "Equipo", "nombreEquipo");
        clase(clases.addObject(), "Jugador", "nombreJugador");
        var relaciones = plan.putArray("relations");
        relacion(relaciones.addObject(), "Producto", "Inventario", "AGGREGATION");
        ObjectNode opcional = relaciones.addObject();
        relacion(opcional, "Equipo", "Jugador", "AGGREGATION");
        opcional.put("sourceMultiplicity", "0..1");
        plan.putArray("audit").add("Se usa agregacion entre Producto e Inventario");

        var resultado = NormalizadorPlanUml.normalizar(new RespuestaIA(
                List.of(new ComandoPropuestoIA(EsquemaPlanUml.NOMBRE, plan)), "")).comandos().getFirst().parametros();

        assertThat(resultado.path("relations").get(0).path("kind").asText()).isEqualTo("ASSOCIATION");
        assertThat(resultado.path("relations").get(1).path("kind").asText()).isEqualTo("AGGREGATION");
        assertThat(resultado.path("audit").toString())
                .contains("1 agregacion(es) por ASSOCIATION")
                .doesNotContain("Se usa agregacion");
    }

    private static void relacion(ObjectNode relacion, String source, String target, String kind) {
        relacion.put("source", source).put("target", target).put("kind", kind)
                .put("sourceMultiplicity", "1").put("targetMultiplicity", "0..*");
    }
}
