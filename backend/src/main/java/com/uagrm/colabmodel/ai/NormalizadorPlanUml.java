package com.uagrm.colabmodel.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.uagrm.colabmodel.modelcore.command.Lote;

import java.text.Normalizer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Ajustes seguros que no deben depender de la obediencia del modelo local. */
final class NormalizadorPlanUml {

    private NormalizadorPlanUml() { }

    static RespuestaIA normalizar(RespuestaIA respuesta, String requisito) {
        ComandoPropuestoIA comando = respuesta.comandos().stream()
                .filter(c -> EsquemaPlanUml.NOMBRE.equals(c.tipo())).findFirst().orElse(null);
        if (comando == null || !comando.parametros().isObject()) return respuesta;

        ObjectNode plan = (ObjectNode) comando.parametros().deepCopy();
        int rolesFamiliaresSimplificados = NormalizadorParentescoPlan.simplificar(plan)
                + NormalizadorParentescoPlan.completarExplicitos(plan, requisito);
        Map<String, String> nombres = new HashMap<>();
        Set<String> historicas = new HashSet<>();
        int identidadesAgregadas = 0;
        int clavesNaturalesReemplazadas = 0;
        int unicidadesRetiradas = 0;

        for (JsonNode claseNodo : plan.path("classes")) {
            ObjectNode clase = (ObjectNode) claseNodo;
            String anterior = clase.path("name").asText();
            String limpio = identificador(anterior, true);
            nombres.put(anterior, limpio);
            clase.put("name", limpio);
            for (JsonNode atributoNodo : clase.path("attributes")) {
                ObjectNode atributo = (ObjectNode) atributoNodo;
                atributo.put("name", identificador(atributo.path("name").asText(), false));
                if (atributo.path("pk").asBoolean(false) && !esIdTecnico(atributo.path("name").asText())) {
                    atributo.put("pk", false);
                    atributo.put("unique", esCandidatoUnico(atributo.path("name").asText()));
                    clavesNaturalesReemplazadas++;
                }
                if (!atributo.path("pk").asBoolean(false) && atributo.path("unique").asBoolean(false)
                        && esTextoRepetible(atributo.path("name").asText())) {
                    atributo.put("unique", false);
                    unicidadesRetiradas++;
                }
            }
            if (!tienePk(clase)) {
                ObjectNode id = ((ArrayNode) clase.path("attributes")).insertObject(0);
                id.put("name", "id" + limpio).put("dataType", "uuid")
                        .put("required", true).put("unique", true).put("pk", true);
                identidadesAgregadas++;
            }
            if (esHistorica(clase)) historicas.add(limpio);
        }

        int coberturasAgregadas = completarCoberturaExplicita(plan, requisito);
        int identificadoresGenericosRetirados = retirarIdentificadoresGenericosRedundantes(plan);

        for (JsonNode relacionNodo : plan.path("relations")) {
            ObjectNode relacion = (ObjectNode) relacionNodo;
            relacion.put("source", nombres.getOrDefault(relacion.path("source").asText(),
                    identificador(relacion.path("source").asText(), true)));
            relacion.put("target", nombres.getOrDefault(relacion.path("target").asText(),
                    identificador(relacion.path("target").asText(), true)));
            if (relacion.has("name")) relacion.put("name", identificador(relacion.path("name").asText(), false));
            if (relacion.has("sourceRole")) relacion.put("sourceRole",
                    identificador(relacion.path("sourceRole").asText(), false));
            if (relacion.has("targetRole")) relacion.put("targetRole",
                    identificador(relacion.path("targetRole").asText(), false));
            // Conserva nombres y roles significativos emitidos por el modelo.
            // Regenerarlos siempre borraba distinciones como cuentaOrigen /
            // cuentaDestino y producia dos asociaciones indistinguibles.
            String nombreActual = relacion.path("name").asText("");
            if (esAsociacion(relacion) && (nombreActual.isBlank() || normal(nombreActual).startsWith("relacion"))) {
                relacion.put("name", nombreDeRelacion(relacion.path("source").asText(),
                        relacion.path("target").asText()));
            }
        }

        int referenciasExplicitasAjustadas = convertirReferenciasExplicitas(plan);
        int relacionesFundamentalesAgregadas = asegurarRelacionesFundamentales(plan, requisito)
                + completarVinculosExplicitos(plan, requisito)
                + completarPrerrequisitos(plan, requisito);
        int rolesTrasladoAjustados = completarRolesTraslado(plan, requisito);

        // Tambien se protege a cualquier clase directamente conectada a un
        // evento historico: borrar una Copia no debe arrastrar sus Prestamos.
        Set<String> protegidas = new HashSet<>(historicas);
        for (JsonNode r : plan.path("relations")) {
            if (!esAsociacion(r)) continue;
            String source = r.path("source").asText();
            String target = r.path("target").asText();
            if (historicas.contains(source)) protegidas.add(target);
            if (historicas.contains(target)) protegidas.add(source);
        }

        int relacionesAjustadas = 0;
        int cardinalidadesHistoricasAjustadas = 0;
        for (JsonNode relacionNodo : plan.path("relations")) {
            ObjectNode relacion = (ObjectNode) relacionNodo;
            String tipo = relacion.path("kind").asText();
            if (!esAsociacion(relacion)) continue;
            boolean cicloDeVida = tipo.equals("COMPOSITION") || tipo.equals("AGGREGATION");
            if (cicloDeVida && (protegidas.contains(relacion.path("source").asText())
                    || protegidas.contains(relacion.path("target").asText()))) {
                relacion.put("kind", "ASSOCIATION");
                relacionesAjustadas++;
            }
            String source = relacion.path("source").asText();
            String target = relacion.path("target").asText();
            boolean sourceHistorica = historicas.contains(source);
            boolean targetHistorica = historicas.contains(target);
            // Una entidad historica forma la coleccion; cada registro conserva
            // exactamente un propietario. Nunca se ensanchan ambos extremos,
            // porque eso inventaria una relacion N:M (p. ej. Usuario-Historial).
            if (sourceHistorica ^ targetHistorica) {
                String extremoHistorico = sourceHistorica ? "sourceMultiplicity" : "targetMultiplicity";
                String extremoPropietario = sourceHistorica ? "targetMultiplicity" : "sourceMultiplicity";
                if (!"0..*".equals(relacion.path(extremoHistorico).asText())) {
                    relacion.put(extremoHistorico, "0..*");
                    cardinalidadesHistoricasAjustadas++;
                }
                if (!esExactamenteUno(relacion.path(extremoPropietario).asText())) {
                    relacion.put(extremoPropietario, "1");
                    cardinalidadesHistoricasAjustadas++;
                }
            }
        }

        int relacionesAsociativasReparadas = repararDetallesAsociativos(plan) + repararTitularidades(plan);
        cardinalidadesHistoricasAjustadas += corregirCardinalidadesAsociativas(plan);
        cardinalidadesHistoricasAjustadas += corregirPatronesRelacionales(plan);
        cardinalidadesHistoricasAjustadas += corregirGarantiasVendidas(plan);
        cardinalidadesHistoricasAjustadas += corregirCancelaciones(plan);
        cardinalidadesHistoricasAjustadas += corregirCatalogosReutilizables(plan, requisito);
        cardinalidadesHistoricasAjustadas += corregirParticipacionesViaje(plan, requisito);
        int datosAsociativosAgregados = asegurarDatosAsociativos(plan);

        int duplicadasRetiradas = retirarRelacionesInvertidasDuplicadas(plan);

        int referenciasDuplicadasRetiradas = retirarReferenciasDuplicadas(plan);

        int coautoriasAjustadas = corregirCoautoriaExplicita(plan, requisito);
        int decisionesDeObligatoriedad = ajustarObligatoriedadExplicita(plan, requisito);
        boolean reglaPrestamoActivo = detectaReglaPrestamoActivo(plan, requisito);
        int secundariosRecortados = recortarAtributosSecundarios(plan, requisito);

        if (plan.path("audit") instanceof ArrayNode audit) {
            if (referenciasExplicitasAjustadas > 0) audit.add("El backend recupero la semantica de referencias explicitas ("
                    + referenciasExplicitasAjustadas + " ajustes en asociaciones y sus extremos).");
            if (rolesTrasladoAjustados > 0) audit.add("El backend completo " + rolesTrasladoAjustados
                    + " rol(es) origen/destino exigidos en relaciones de traslado.");
            if (rolesFamiliaresSimplificados > 0) audit.add("El backend ajusto la representacion del parentesco ("
                    + rolesFamiliaresSimplificados + " cambios) mediante asociaciones recursivas padre/madre y descendientes.");
            for (int i = audit.size() - 1; i >= 0; i--) {
                String punto = normal(audit.get(i).asText());
                if (punto.contains("inventario") && punto.contains("sin historial")
                        && (punto.contains("entidad historica") || punto.contains("auditorias de stock"))) {
                    audit.remove(i);
                }
            }
            if (cardinalidadesHistoricasAjustadas > 0) {
                // Una justificacion anterior a los ajustes ya no acredita las
                // cardinalidades del resultado normalizado.
                for (int i = audit.size() - 1; i >= 0; i--) {
                    String punto = normal(audit.get(i).asText());
                    if (punto.contains("multiplicidad") || punto.contains("cardinalidad")) audit.remove(i);
                }
            }
            if (duplicadasRetiradas > 0) audit.add("El backend retiro " + duplicadasRetiradas
                    + " asociacion(es) duplicada(s) en sentido inverso, sin roles distintos.");
            if (relacionesAjustadas > 0) {
            for (int i = audit.size() - 1; i >= 0; i--) {
                String punto = normal(audit.get(i).asText());
                if (punto.contains("composicion") || punto.contains("agregacion")
                        || punto.contains("composition") || punto.contains("aggregation")) audit.remove(i);
            }
            audit.add("El backend sustituyo " + relacionesAjustadas
                    + " relacion(es) de ciclo de vida por ASSOCIATION para preservar entidades historicas.");
            }
            if (identidadesAgregadas > 0) audit.add("El backend agrego identidad UUID a "
                    + identidadesAgregadas + " clase(s) que no tenian clave primaria.");
            if (clavesNaturalesReemplazadas > 0) audit.add("El backend reemplazo "
                    + clavesNaturalesReemplazadas + " clave(s) natural(es) por UUID y conservo unique cuando corresponde.");
            if (unicidadesRetiradas > 0) audit.add("El backend retiro unique de " + unicidadesRetiradas
                    + " atributo(s) descriptivo(s) que pueden repetirse.");
            if (cardinalidadesHistoricasAjustadas > 0) audit.add("El backend ajusto "
                    + cardinalidadesHistoricasAjustadas + " extremo(s) de multiplicidad segun patrones estructurales; "
                    + "las restricciones temporales requieren validacion de negocio.");
            if (referenciasDuplicadasRetiradas > 0) audit.add("El backend retiro "
                    + referenciasDuplicadasRetiradas
                    + " atributo(s) que duplicaban asociaciones UML explicitas.");
            if (identificadoresGenericosRetirados > 0) audit.add("El backend retiro "
                    + identificadoresGenericosRetirados
                    + " identificador(es) generico(s) redundantes con la identidad UUID.");
            if (relacionesFundamentalesAgregadas > 0) audit.add("El backend completo o ajusto relacion(es) fundamental(es) ("
                    + relacionesFundamentalesAgregadas + " ajustes de cobertura y multiplicidad).");
            if (relacionesAsociativasReparadas > 0) audit.add("El backend reparo "
                    + relacionesAsociativasReparadas + " clase(s) asociativa(s) incompleta(s).");
            if (datosAsociativosAgregados > 0) audit.add("El backend agrego un dato de negocio esencial a "
                    + datosAsociativosAgregados + " clase(s) asociativa(s).");
            if (coberturasAgregadas > 0) audit.add("El backend completo " + coberturasAgregadas
                    + " atributo(s) mencionado(s) explicitamente en el requisito.");
            if (coautoriasAjustadas > 0) audit.add("El backend ajusto la relacion de autoria para permitir coautoria.");
            if (decisionesDeObligatoriedad > 0) audit.add("El backend ajusto la obligatoriedad de ISBN segun el requisito explicito.");
            if (reglaPrestamoActivo) audit.add("Regla de negocio detectada: un Ejemplar no puede tener dos Prestamos "
                    + "activos simultaneamente; se considera activo mientras fechaDevolucion sea nula.");
            if (secundariosRecortados > 0) audit.add("El backend omitio " + secundariosRecortados
                    + " atributo(s) secundario(s) para conservar todas las relaciones dentro del limite seguro.");
            while (audit.size() > 5) {
                int quitar = 0;
                for (int i = 0; i < audit.size(); i++) {
                    String punto = normal(audit.get(i).asText());
                    if (!punto.contains("subclase") && !punto.contains("hereda")
                            && !punto.contains("generalization")) { quitar = i; break; }
                }
                audit.remove(quitar);
            }
            while (audit.size() < 2) audit.add("Las multiplicidades se conservan y se interpretan en ambos extremos.");
        }

        return new RespuestaIA(List.of(new ComandoPropuestoIA(EsquemaPlanUml.NOMBRE, plan)),
                respuesta.notaDelModelo());
    }

    private static boolean tienePk(ObjectNode clase) {
        for (JsonNode a : clase.path("attributes")) if (a.path("pk").asBoolean(false)) return true;
        return false;
    }

    private static boolean esAsociacion(JsonNode relacion) {
        return Set.of("ASSOCIATION", "AGGREGATION", "COMPOSITION")
                .contains(relacion.path("kind").asText("ASSOCIATION"));
    }

    private static boolean esGarantiaVendida(String a, String b) {
        return normal(a).startsWith("garantia") && (normal(b).contains("detalleventa")
                || normal(b).contains("unidadvendida") || normal(b).contains("ejemplarvendido"));
    }

    private static int corregirGarantiasVendidas(ObjectNode plan) {
        int cambios = 0;
        for (JsonNode nodo : plan.path("relations")) {
            if (!esAsociacion(nodo)) continue;
            ObjectNode r = (ObjectNode) nodo;
            String s = r.path("source").asText(), t = r.path("target").asText();
            // Cada registro de garantia identifica una compra concreta. Se
            // conserva la politica del otro extremo (opcional, unica o varias).
            if (esGarantiaVendida(s, t)) cambios += ponerSiCambio(r, "targetMultiplicity", "1");
            else if (esGarantiaVendida(t, s)) cambios += ponerSiCambio(r, "sourceMultiplicity", "1");
        }
        return cambios;
    }

    private static int corregirCancelaciones(ObjectNode plan) {
        int cambios = 0;
        for (JsonNode nodo : plan.path("relations")) {
            if (!esAsociacion(nodo)) continue;
            ObjectNode r = (ObjectNode) nodo;
            String s = normal(r.path("source").asText()), t = normal(r.path("target").asText());
            boolean cancelacionOrigen = s.startsWith("cancelacion") && t.startsWith("reserva");
            boolean cancelacionDestino = t.startsWith("cancelacion") && s.startsWith("reserva");
            if (!(cancelacionOrigen || cancelacionDestino)) continue;
            cambios += ponerSiCambio(r, cancelacionOrigen ? "targetMultiplicity" : "sourceMultiplicity", "1");
            String extremo = cancelacionOrigen ? "sourceMultiplicity" : "targetMultiplicity";
            String actual = r.path(extremo).asText();
            if (actual.equals("1")) cambios += ponerSiCambio(r, extremo, "0..1");
            else if (actual.equals("1..*")) cambios += ponerSiCambio(r, extremo, "0..*");
        }
        return cambios;
    }

    private static int corregirCatalogosReutilizables(ObjectNode plan, String requisito) {
        String pedido = normal(requisito);
        if (!pedido.contains("reutiliz") && !pedido.contains("compart")) return 0;
        int cambios = 0;
        for (JsonNode nodo : plan.path("relations")) {
            if (!esAsociacion(nodo)) continue;
            ObjectNode r = (ObjectNode) nodo;
            String s = normal(r.path("source").asText()), t = normal(r.path("target").asText());
            if (s.startsWith("paquete") && (t.equals("destino") || t.startsWith("servicioturistico")))
                cambios += ponerSiCambio(r, "sourceMultiplicity", "0..*");
            else if (t.startsWith("paquete") && (s.equals("destino") || s.startsWith("servicioturistico")))
                cambios += ponerSiCambio(r, "targetMultiplicity", "0..*");
        }
        return cambios;
    }

    private static int retirarRelacionesInvertidasDuplicadas(ObjectNode plan) {
        ArrayNode relaciones = (ArrayNode) plan.path("relations");
        int retiradas = 0;
        for (int i = relaciones.size() - 1; i >= 0; i--) {
            JsonNode actual = relaciones.get(i);
            if (!"ASSOCIATION".equals(actual.path("kind").asText())) continue;
            if (!actual.path("sourceRole").asText().isBlank()
                    || !actual.path("targetRole").asText().isBlank()) continue;
            for (int j = 0; j < i; j++) {
                JsonNode anterior = relaciones.get(j);
                if (!"ASSOCIATION".equals(anterior.path("kind").asText())) continue;
                if (!anterior.path("sourceRole").asText().isBlank()
                        || !anterior.path("targetRole").asText().isBlank()) continue;
                // Solo elimina la copia inversa del MISMO nombre y mismas
                // multiplicidades: nombres diferentes pueden expresar roles.
                if (actual.path("source").equals(anterior.path("target"))
                        && actual.path("target").equals(anterior.path("source"))
                        && actual.path("name").equals(anterior.path("name"))
                        && actual.path("sourceMultiplicity").equals(anterior.path("targetMultiplicity"))
                        && actual.path("targetMultiplicity").equals(anterior.path("sourceMultiplicity"))) {
                    relaciones.remove(i);
                    retiradas++;
                    break;
                }
            }
        }
        return retiradas;
    }

    private static int retirarIdentificadoresGenericosRedundantes(ObjectNode plan) {
        int retirados = 0;
        for (JsonNode claseNodo : plan.path("classes")) {
            ObjectNode clase = (ObjectNode) claseNodo;
            boolean tieneUuidPk = false;
            for (JsonNode atributo : clase.path("attributes")) {
                if (atributo.path("pk").asBoolean(false)
                        && "uuid".equalsIgnoreCase(atributo.path("dataType").asText())) {
                    tieneUuidPk = true;
                    break;
                }
            }
            if (!tieneUuidPk) continue;
            ArrayNode atributos = (ArrayNode) clase.path("attributes");
            for (int i = atributos.size() - 1; i >= 0; i--) {
                JsonNode atributo = atributos.get(i);
                if (!atributo.path("pk").asBoolean(false)
                        && normal(atributo.path("name").asText()).replace("_", "")
                        .equals("identificadorunico")) {
                    atributos.remove(i);
                    retirados++;
                }
            }
        }
        return retirados;
    }

    private static int asegurarRelacionesFundamentales(ObjectNode plan, String requisito) {
        String pedido = normal(requisito).replace("_", "");
        boolean contextoClinico = pedido.contains("consulta")
                && (pedido.contains("cita") || pedido.contains("paciente")
                || pedido.contains("profesional") || pedido.contains("medic"));
        if (!contextoClinico) return 0;

        String cita = buscarClasePorPatron(plan, "cita");
        String consulta = buscarClasePorPatron(plan, "consulta");
        String paciente = buscarClasePorPatron(plan, "paciente");
        String profesional = buscarClasePorPatron(plan, "profesional");
        if (consulta == null) return 0;

        int agregadas = 0;
        agregadas += agregarRelacionSiFalta(plan, cita, consulta,
                "0..1", "0..1", "originaConsulta");
        agregadas += agregarRelacionSiFalta(plan, paciente, consulta,
                "1", "0..*", "realizaConsultas");
        agregadas += agregarRelacionSiFalta(plan, profesional, consulta,
                "1", "0..*", "atiendeConsultas");
        return agregadas;
    }

    private static int agregarRelacionSiFalta(ObjectNode plan, String source, String target,
                                               String sourceMultiplicity, String targetMultiplicity,
                                               String nombre) {
        if (source == null || target == null || existeRelacion(plan, source, target)) return 0;
        ((ArrayNode) plan.path("relations")).addObject()
                .put("source", source).put("target", target)
                .put("kind", "ASSOCIATION")
                .put("sourceMultiplicity", sourceMultiplicity)
                .put("targetMultiplicity", targetMultiplicity)
                .put("name", nombre);
        return 1;
    }

    /** Solo completa vinculos pedidos explicitamente entre clases inequivocas ya propuestas. */
    private static int completarVinculosExplicitos(ObjectNode plan, String requisito) {
        String pedido = normal(requisito);
        int cambios = 0;
        String producto = claseUnica(plan, "producto"), inventario = claseUnica(plan, "inventario(?:producto)?");
        if (pedido.contains("inventario") || pedido.contains("stock")) cambios += agregarRelacionSiFalta(plan,
                producto, inventario, "1", "0..1", "tieneInventarioActual");
        if (pedido.contains("garantia") && (pedido.contains("vendid") || pedido.contains("compra concreta"))) {
            cambios += agregarRelacionSiFalta(plan, claseUnica(plan, "garantia"), claseUnica(plan, "detalleventa"),
                    "0..*", "1", "cubreDetalleVendido");
        }
        if (pedido.contains("prestatario") && !pedido.contains("coprestatario")) {
            String prestamo = claseUnica(plan, "prestamo");
            String cliente = claseUnica(plan, "cliente");
            boolean intermediario = false;
            for (JsonNode c : plan.path("classes")) {
                String n = c.path("name").asText();
                if ((normal(n).contains("prestatario") || normal(n).contains("titular"))
                        && existeRelacion(plan, n, prestamo) && existeRelacion(plan, n, cliente)) intermediario = true;
            }
            if (!intermediario) cambios += agregarRelacionSiFalta(plan, cliente, prestamo, "1", "0..*", "recibePrestamos");
        }
        if (pedido.contains("animal") && pedido.contains("especie") && pedido.contains("raza")) {
            String animal = claseUnica(plan, "animal");
            cambios += agregarRelacionSiFalta(plan, animal, claseUnica(plan, "especie"), "0..*", "1", "perteneceAEspecie");
            cambios += agregarRelacionSiFalta(plan, animal, claseUnica(plan, "raza"), "0..*",
                    pedido.contains("raza opcional") ? "0..1" : "1", "perteneceARaza");
        }
        if (pedido.contains("aplicacion") && pedido.contains("producto") && pedido.contains("animal")) {
            String aplicacion = claseUnica(plan, "aplicacion(?:producto|sanitaria)?");
            cambios += agregarRelacionSiFalta(plan, aplicacion, claseUnica(plan, "animal"),
                    "0..*", "1", "seAplicaA");
            cambios += agregarRelacionSiFalta(plan, aplicacion, claseUnica(plan, "producto(?:sanitario)?"),
                    "0..*", "1", "utilizaProducto");
        }
        if (pedido.contains("viajero") && pedido.contains("participantes")) {
            String participacion = claseUnica(plan, "participante(?:reserva)?|participacion(?:reserva)?");
            String reserva = claseUnica(plan, "reserva"), viajero = claseUnica(plan, "viajero");
            cambios += agregarRelacionSiFalta(plan, participacion, reserva,
                    "0..*", "1", "participaEnReserva");
            cambios += agregarRelacionSiFalta(plan, participacion, viajero,
                    "0..*", "1", "identificaViajero");
        }
        if (pedido.contains("paquete") && pedido.contains("servicio") && pedido.contains("reutiliz")) {
            String paquete = claseUnica(plan, "paquete(?:turistico)?"), servicio = claseUnica(plan, "servicioturistico");
            if (paquete != null && servicio != null && !hayCaminoAsociacion(plan, paquete, servicio))
                cambios += agregarRelacionSiFalta(plan, paquete, servicio, "0..*", "0..*", "incluyeServicios");
        }
        return cambios;
    }

    private static boolean hayCaminoAsociacion(ObjectNode plan, String desde, String hasta) {
        Set<String> visitadas = new HashSet<>();
        var pendientes = new java.util.ArrayDeque<String>(); pendientes.add(desde);
        while (!pendientes.isEmpty()) {
            String actual = pendientes.removeFirst();
            if (!visitadas.add(actual)) continue;
            if (actual.equals(hasta)) return true;
            for (JsonNode r : plan.path("relations")) if (esAsociacion(r)) {
                String s = r.path("source").asText(), t = r.path("target").asText();
                if (s.equals(actual)) pendientes.add(t); else if (t.equals(actual)) pendientes.add(s);
            }
        }
        return false;
    }

    private static int corregirParticipacionesViaje(ObjectNode plan, String requisito) {
        String pedido = normal(requisito);
        if (!pedido.contains("viajero") || !pedido.contains("participantes")) return 0;
        String participacion = claseUnica(plan, "participante(?:reserva)?|participacion(?:reserva)?");
        String reserva = claseUnica(plan, "reserva"), viajero = claseUnica(plan, "viajero");
        if (participacion == null || reserva == null || viajero == null) return 0;
        return ajustarPar(plan, normal(participacion), normal(reserva), "0..*", "1")
                + ajustarPar(plan, normal(participacion), normal(viajero), "0..*", "1");
    }

    private static String claseUnica(ObjectNode plan, String patron) {
        List<String> candidatas = new java.util.ArrayList<>();
        for (JsonNode c : plan.path("classes")) if (normal(c.path("name").asText()).replace("_", "").matches(patron))
            candidatas.add(c.path("name").asText());
        return candidatas.size() == 1 ? candidatas.getFirst() : null;
    }

    /** Recupera la semantica de una FK inequívoca antes de quitar el atributo duplicado. */
    private static int convertirReferenciasExplicitas(ObjectNode plan) {
        int cambios = 0;
        for (JsonNode c : plan.path("classes")) {
            String origen = c.path("name").asText();
            for (JsonNode a : c.path("attributes")) {
                if (a.path("pk").asBoolean()) continue;
                String campo = a.path("name").asText();
                String referencia = campo.matches("id[A-Z].*") ? campo.substring(2)
                        : campo.endsWith("Id") ? campo.substring(0, campo.length() - 2) : "";
                String tipo = a.path("dataType").asText();
                List<String> candidatas = new java.util.ArrayList<>();
                for (JsonNode destino : plan.path("classes")) {
                    String n = destino.path("name").asText();
                    if (!origen.equals(n) && (normal(n).equals(normal(referencia)) || normal(n).equals(normal(tipo))))
                        candidatas.add(n);
                }
                if (candidatas.size() != 1) continue;
                String destino = candidatas.getFirst();
                List<ObjectNode> existentes = new java.util.ArrayList<>();
                for (JsonNode r : plan.path("relations")) if (esAsociacion(r)
                        && ((origen.equals(r.path("source").asText()) && destino.equals(r.path("target").asText()))
                        || (origen.equals(r.path("target").asText()) && destino.equals(r.path("source").asText()))))
                    existentes.add((ObjectNode) r);
                if (existentes.size() == 1) {
                    ObjectNode r = existentes.getFirst(); boolean directa = origen.equals(r.path("source").asText());
                    cambios += ponerSiCambio(r, directa ? "targetMultiplicity" : "sourceMultiplicity",
                            a.path("required").asBoolean() ? "1" : "0..1");
                    cambios += ponerSiCambio(r, directa ? "sourceMultiplicity" : "targetMultiplicity",
                            a.path("unique").asBoolean() ? "0..1" : "0..*");
                }
                cambios += agregarRelacionSiFalta(plan, origen, candidatas.getFirst(),
                        a.path("unique").asBoolean() ? "0..1" : "0..*",
                        a.path("required").asBoolean() ? "1" : "0..1", "referencia" + candidatas.getFirst());
            }
        }
        return cambios;
    }

    private static int completarPrerrequisitos(ObjectNode plan, String requisito) {
        String pedido = normal(requisito);
        if (!pedido.contains("prerrequis")) return 0;
        String curso = claseUnica(plan, "asignatura(?:base)?"), requisitoClase = claseUnica(plan, "prerrequisito");
        if (curso == null || requisitoClase == null) return 0;
        ArrayNode relaciones = (ArrayNode) plan.path("relations");
        List<ObjectNode> enlaces = new java.util.ArrayList<>();
        for (JsonNode r : relaciones) if (esAsociacion(r) && (r.path("source").asText().equals(requisitoClase)
                || r.path("target").asText().equals(requisitoClase))) enlaces.add((ObjectNode) r);
        int cambios = 0;
        for (ObjectNode r : enlaces) {
            boolean sale = r.path("source").asText().equals(requisitoClase);
            if (!r.path(sale ? "target" : "source").asText().equals(curso)) continue;
            // Cada requisito identifica una asignatura por rol; el catalogo
            // puede intervenir en muchos requisitos como actual o requerida.
            cambios += ponerSiCambio(r, sale ? "targetMultiplicity" : "sourceMultiplicity", "1");
            cambios += ponerSiCambio(r, sale ? "sourceMultiplicity" : "targetMultiplicity", "0..*");
        }
        if (enlaces.size() == 1) {
            ObjectNode r = enlaces.getFirst();
            boolean requisitoOrigen = r.path("source").asText().equals(requisitoClase);
            String vecino = r.path(requisitoOrigen ? "target" : "source").asText();
            if (vecino.equals(curso)) {
                boolean actual = !requisitoOrigen || normal(r.path("targetRole").asText()).contains("actual");
                String rolCurso = actual ? "asignaturaActual" : "asignaturaRequerida";
                String rolRequisito = actual ? "requisitos" : "requisitoDe";
                if (r.path("sourceRole").asText().isBlank()) r.put("sourceRole", requisitoOrigen ? rolRequisito : rolCurso);
                if (r.path("targetRole").asText().isBlank()) r.put("targetRole", requisitoOrigen ? rolCurso : rolRequisito);
                relaciones.addObject().put("source", actual ? requisitoClase : curso).put("target", actual ? curso : requisitoClase)
                        .put("kind", "ASSOCIATION").put("name", actual ? "requiereAsignatura" : "tienePrerrequisitos")
                        .put("sourceRole", actual ? "requisitoDe" : "asignaturaActual")
                        .put("targetRole", actual ? "asignaturaRequerida" : "requisitos")
                        .put("sourceMultiplicity", actual ? "0..*" : "1").put("targetMultiplicity", actual ? "1" : "0..*");
                cambios++;
            } else if (normal(vecino).contains("detalleplan") || normal(vecino).contains("planasignatura")) {
                relaciones.addObject().put("source", requisitoClase).put("target", curso).put("kind", "ASSOCIATION")
                        .put("name", "requiereAsignatura").put("sourceRole", "requisitoDe").put("targetRole", "asignaturaRequerida")
                        .put("sourceMultiplicity", "0..*").put("targetMultiplicity", "1");
                cambios++;
            }
        }
        String planAcademico = claseUnica(plan, "plan(?:academico)?");
        if (planAcademico != null && pedido.contains("plan") && pedido.contains("asignatura")) {
            boolean detalle = false;
            for (JsonNode c : plan.path("classes")) {
                String n = c.path("name").asText();
                boolean datosContextuales = false;
                for (JsonNode a : c.path("attributes")) if (normal(a.path("name").asText()).contains("creditos"))
                    datosContextuales = true;
                if ((datosContextuales || normal(n).contains("detalle") || normal(n).contains("planasignatura")
                        || normal(n).contains("participacion") || normal(n).contains("oferta"))
                        && existeRelacion(plan, n, curso) && existeRelacion(plan, n, planAcademico)) {
                    detalle = true;
                    cambios += ajustarPar(plan, normal(n), normal(curso), "0..*", "1");
                    cambios += ajustarPar(plan, normal(n), normal(planAcademico), "0..*", "1");
                }
            }
            if (!detalle) {
                cambios += agregarRelacionSiFalta(plan, planAcademico, curso, "0..*", "0..*", "incluyeAsignaturas");
                if (pedido.contains("catalogo")) cambios += ajustarPar(plan, normal(planAcademico), normal(curso), "0..*", "0..*");
            }
        }
        return cambios;
    }

    private static int completarRolesTraslado(ObjectNode plan, String requisito) {
        String pedido = normal(requisito);
        if (!pedido.contains("origen") || !pedido.contains("destino")) return 0;
        int cambios = completarParRoles(plan, claseUnica(plan, "transferencia"), claseUnica(plan, "cuenta"));
        if (pedido.contains("entre lotes")) cambios += completarParRoles(plan,
                claseUnica(plan, "(?:movimiento|traslado)(?:animal|lote)?"), claseUnica(plan, "lote"));
        if (pedido.contains("entre establecimientos")) cambios += completarParRoles(plan,
                claseUnica(plan, "(?:movimiento|traslado)(?:animal)?"), claseUnica(plan, "establecimiento"));
        return cambios;
    }

    private static int completarParRoles(ObjectNode plan, String evento, String recurso) {
        if (evento == null || recurso == null) return 0;
        ArrayNode relaciones = (ArrayNode) plan.path("relations");
        Set<String> presentes = new HashSet<>();
        List<ObjectNode> genericas = new java.util.ArrayList<>();
        List<JsonNode> duplicadas = new java.util.ArrayList<>();
        int cambios = 0;
        for (JsonNode nodo : relaciones) {
            if (!esAsociacion(nodo)) continue;
            ObjectNode r = (ObjectNode) nodo;
            boolean directa = r.path("source").asText().equals(evento) && r.path("target").asText().equals(recurso);
            boolean inversa = r.path("target").asText().equals(evento) && r.path("source").asText().equals(recurso);
            if (!directa && !inversa) continue;
            String ladoRecurso = directa ? "targetRole" : "sourceRole", ladoEvento = directa ? "sourceRole" : "targetRole";
            String rol = normal(r.path(ladoRecurso).asText()), rolEvento = normal(r.path(ladoEvento).asText());
            // El modelo puede colocar cuentaOrigen en Transferencia y movimientosSalientes en Cuenta.
            // Se intercambian solo cuando ambos nombres evidencian la inversion.
            if ((rolEvento.contains("origen") || rolEvento.contains("destino")) && rolEvento.contains(normal(recurso))
                    && (rol.contains("movimiento") || rol.contains("transferencia") || rol.contains("traslado"))) {
                String anterior = r.path(ladoRecurso).asText();
                r.put(ladoRecurso, r.path(ladoEvento).asText()); r.put(ladoEvento, anterior);
                rol = normal(r.path(ladoRecurso).asText()); cambios++;
            }
            if (rol.contains("origen") && !rol.contains("destino")) {
                if (!presentes.add("origen")) duplicadas.add(r);
            }
            else if (rol.contains("destino") && !rol.contains("origen")) {
                if (!presentes.add("destino")) duplicadas.add(r);
            }
            else if (rol.isBlank() || rol.equals(normal(recurso)) || rol.contains("origen") && rol.contains("destino"))
                genericas.add(r);
        }
        for (int i = relaciones.size() - 1; i >= 0; i--) {
            JsonNode actual = relaciones.get(i);
            if (duplicadas.stream().anyMatch(d -> d == actual)) { relaciones.remove(i); cambios++; }
        }
        for (String rol : List.of("origen", "destino")) {
            if (presentes.contains(rol)) continue;
            ObjectNode r = genericas.isEmpty() ? relaciones.addObject().put("source", evento).put("target", recurso)
                    .put("kind", "ASSOCIATION").put("sourceMultiplicity", "0..*").put("targetMultiplicity", "1")
                    : genericas.removeFirst();
            boolean directa = r.path("source").asText().equals(evento);
            r.put(directa ? "targetRole" : "sourceRole", identificador(recurso + " " + rol, false));
            r.put(directa ? "sourceRole" : "targetRole", rol.equals("origen") ? "movimientosSalientes" : "movimientosEntrantes");
            r.put("name", rol.equals("origen") ? "saleDe" : "llegaA");
            cambios++;
        }
        return cambios;
    }

    private static String buscarClasePorPatron(ObjectNode plan, String patron) {
        for (JsonNode clase : plan.path("classes")) {
            String nombre = clase.path("name").asText();
            if (coincidePatron(normal(nombre).replace("_", ""), patron)) return nombre;
        }
        return null;
    }

    private static boolean existeRelacion(ObjectNode plan, String primera, String segunda) {
        for (JsonNode relacion : plan.path("relations")) {
            if (!esAsociacion(relacion)) continue;
            String source = relacion.path("source").asText();
            String target = relacion.path("target").asText();
            if ((source.equals(primera) && target.equals(segunda))
                    || (source.equals(segunda) && target.equals(primera))) return true;
        }
        return false;
    }

    private static boolean esIdTecnico(String nombre) {
        if (nombre == null) return false;
        String n = nombre.trim();
        return n.equalsIgnoreCase("id") || n.matches("^id[A-Z].*")
                || n.matches("(?i)^id[_-].+");
    }

    private static boolean esCandidatoUnico(String nombre) {
        String n = normal(nombre).replace("_", "");
        return n.contains("isbn") || n.contains("codigo") || n.contains("email")
                || n.contains("correo") || n.contains("matricula") || n.contains("identificacion")
                || n.contains("documento") || n.contains("numero") || n.contains("slug")
                || n.contains("serie") || n.contains("placa");
    }

    private static boolean esTextoRepetible(String nombre) {
        String n = normal(nombre).replace("_", "");
        return n.equals("nombre") || n.endsWith("nombrecompleto") || n.contains("titulo")
                || n.contains("descripcion") || n.contains("detalle") || n.contains("observacion");
    }

    private static String nombreDeRelacion(String source, String target) {
        String clave = normal(source) + "->" + normal(target);
        return switch (clave) {
            case "obra->autor" -> "tieneAutores";
            case "obra->ejemplar", "obra->copia", "obra->copiafisica" -> "tieneEjemplares";
            case "usuario->prestamo", "usuario->prestamohistorico" -> "realizaPrestamos";
            case "ejemplar->prestamo", "copia->prestamo", "copiafisica->prestamo",
                 "copiafisica->prestamohistorico" -> "registraPrestamos";
            case "usuario->reserva" -> "solicitaReservas";
            case "obra->reserva" -> "recibeReservas";
            default -> "relacionaCon" + pluralPascal(target);
        };
    }

    private static String pluralPascal(String nombre) {
        String limpio = identificador(nombre, true);
        String n = normal(limpio);
        if (n.equals("autor")) return "Autores";
        if (n.endsWith("s")) return limpio;
        if (n.endsWith("z")) return limpio.substring(0, limpio.length() - 1) + "ces";
        return limpio + (n.endsWith("r") || n.endsWith("l") || n.endsWith("n") ? "es" : "s");
    }

    private static int ajustarObligatoriedadExplicita(ObjectNode plan, String requisito) {
        String pedido = normal(requisito);
        boolean obligatorio = pedido.contains("isbn obligatorio") || pedido.contains("isbn requerido")
                || pedido.matches("(?s).*toda(?:s)? (?:las )?obra(?:s)?[^.]*isbn.*");
        boolean opcional = pedido.contains("isbn opcional") || pedido.contains("sin isbn")
                || pedido.contains("no tenga isbn") || pedido.contains("no tienen isbn");
        if (!obligatorio && !opcional) return 0;
        for (JsonNode clase : plan.path("classes")) {
            for (JsonNode atributo : clase.path("attributes")) {
                if (normal(atributo.path("name").asText()).equals("isbn")) {
                    ((ObjectNode) atributo).put("required", obligatorio);
                    return 1;
                }
            }
        }
        return 0;
    }

    private static boolean detectaReglaPrestamoActivo(ObjectNode plan, String requisito) {
        String pedido = normal(requisito);
        boolean solicitada = pedido.contains("dos prestamos activos") || pedido.contains("prestamos activos simultaneos")
                || pedido.contains("prestamo activo simultaneo");
        if (!solicitada) return false;
        boolean devolucion = false;
        for (JsonNode clase : plan.path("classes")) {
            if (!normal(clase.path("name").asText()).contains("prestamo")) continue;
            for (JsonNode a : clase.path("attributes")) {
                if (normal(a.path("name").asText()).equals("fechadevolucion")) devolucion = true;
            }
        }
        return devolucion;
    }

    private static boolean esExactamenteUno(String multiplicidad) {
        return "1".equals(multiplicidad == null ? "" : multiplicidad.trim());
    }

    private static int corregirPatronesRelacionales(ObjectNode plan) {
        int ajustes = 0;
        ajustes += ajustarPar(plan, "cita", "consulta", "0..1", "0..1");
        ajustes += ajustarPar(plan, "consulta", "diagnostico", "1", "0..*");
        ajustes += ajustarPar(plan, "consulta", "receta", "1", "0..1");
        ajustes += ajustarPar(plan, "prestamo", "cuota", "1", "1..*");
        // Una especialidad puede registrarse en el catalogo antes de contar
        // con profesionales asignados; cada profesional clinico, en cambio,
        // debe declarar al menos una especialidad.
        ajustes += ajustarPar(plan, "profesional", "especialidad", "0..*", "1..*");

        for (JsonNode relacionNodo : plan.path("relations")) {
            ObjectNode relacion = (ObjectNode) relacionNodo;
            if (!esAsociacion(relacion)) continue;
            String source = normal(relacion.path("source").asText()).replace("_", "");
            String target = normal(relacion.path("target").asText()).replace("_", "");
            if (esCabecera(source) && esDetalle(target)) {
                ajustes += ponerSiCambio(relacion, "sourceMultiplicity", "1");
                ajustes += ponerSiCambio(relacion, "targetMultiplicity", "1..*");
            } else if (esDetalle(source) && esCabecera(target)) {
                ajustes += ponerSiCambio(relacion, "sourceMultiplicity", "1..*");
                ajustes += ponerSiCambio(relacion, "targetMultiplicity", "1");
            }

            // Una linea o indicacion referencia exactamente un concepto del
            // catalogo (medicamento, producto, servicio...), mientras ese
            // concepto puede aparecer en muchas lineas historicas.
            if (esDetalle(source) && esConceptoDetallado(target)) {
                ajustes += ponerSiCambio(relacion, "sourceMultiplicity", "0..*");
                ajustes += ponerSiCambio(relacion, "targetMultiplicity", "1");
            } else if (esConceptoDetallado(source) && esDetalle(target)) {
                ajustes += ponerSiCambio(relacion, "sourceMultiplicity", "1");
                ajustes += ponerSiCambio(relacion, "targetMultiplicity", "0..*");
            }

            if (esMembresia(source) && esActorOContenedor(target)) {
                ajustes += ponerSiCambio(relacion, "sourceMultiplicity", "0..*");
                ajustes += ponerSiCambio(relacion, "targetMultiplicity", "1");
            } else if (esActorOContenedor(source) && esMembresia(target)) {
                ajustes += ponerSiCambio(relacion, "sourceMultiplicity", "1");
                ajustes += ponerSiCambio(relacion, "targetMultiplicity", "0..*");
            }

            // Los estados, tipos y categorias con identidad propia son
            // catalogos reutilizables: cada entidad elige un valor, mientras
            // un mismo valor puede ser compartido por muchas entidades.
            if (esCatalogo(source) && !esCatalogo(target)) {
                ajustes += ponerSiCambio(relacion, "sourceMultiplicity", "1");
                ajustes += ponerSiCambio(relacion, "targetMultiplicity", "0..*");
            } else if (!esCatalogo(source) && esCatalogo(target)) {
                ajustes += ponerSiCambio(relacion, "sourceMultiplicity", "0..*");
                ajustes += ponerSiCambio(relacion, "targetMultiplicity", "1");
            }
        }
        return ajustes;
    }

    /**
     * Una clase asociativa representa registros de una relacion y, por tanto,
     * forma la coleccion frente a cada uno de sus extremos. Esta regla evita
     * 1:1 accidentales en DetalleVenta, AsignacionDocente, TitularCuenta,
     * DetallePaquete y estructuras equivalentes de otros dominios.
     */
    private static int corregirCardinalidadesAsociativas(ObjectNode plan) {
        Set<String> asociativas = new HashSet<>();
        for (JsonNode clase : plan.path("classes")) {
            String nombre = normal(clase.path("name").asText()).replace("_", "");
            boolean candidata = esDetalle(nombre) || nombre.contains("asignacion")
                    || nombre.contains("inscripcion") || nombre.contains("membresia")
                    || nombre.contains("titular") || nombre.contains("prerrequisito");
            // Participante solo es asociativa cuando modela un rol, no cuando
            // representa a la persona que viaja o asiste a una actividad.
            // Participante es ambiguo: puede ser membresia con rol o una
            // persona del dominio (turismo, eventos). Su cardinalidad ya se
            // ajusta frente a actores/contenedores en corregirPatronesRelacionales.
            if (nombre.contains("participante")) candidata = false;
            if (candidata) asociativas.add(clase.path("name").asText());
        }

        int ajustes = 0;
        for (JsonNode nodo : plan.path("relations")) {
            ObjectNode relacion = (ObjectNode) nodo;
            if (!esAsociacion(relacion)) continue;
            if (esGarantiaVendida(relacion.path("source").asText(), relacion.path("target").asText())
                    || esGarantiaVendida(relacion.path("target").asText(), relacion.path("source").asText())) continue;
            boolean source = asociativas.contains(relacion.path("source").asText());
            boolean target = asociativas.contains(relacion.path("target").asText());
            if (source == target) continue;
            ajustes += ponerSiCambio(relacion, "sourceMultiplicity", source ? "0..*" : "1");
            ajustes += ponerSiCambio(relacion, "targetMultiplicity", target ? "0..*" : "1");
            relacion.put("kind", "ASSOCIATION");
        }
        return ajustes;
    }

    private static int repararDetallesAsociativos(ObjectNode plan) {
        ArrayNode relaciones = (ArrayNode) plan.path("relations");
        int reparadas = 0;
        for (JsonNode clase : plan.path("classes")) {
            String asociativa = clase.path("name").asText();
            if (!esDetalle(normal(asociativa).replace("_", ""))) continue;
            List<ObjectNode> conexiones = new java.util.ArrayList<>();
            for (JsonNode r : relaciones) {
                if (!esAsociacion(r)) continue;
                if (asociativa.equals(r.path("source").asText()) || asociativa.equals(r.path("target").asText()))
                    conexiones.add((ObjectNode) r);
            }
            if (conexiones.isEmpty()) {
                ObjectNode directa = null;
                String cabecera = null;
                String concepto = null;
                for (JsonNode candidatoNodo : relaciones) {
                    ObjectNode candidato = (ObjectNode) candidatoNodo;
                    if (!esAsociacion(candidato)) continue;
                    String source = candidato.path("source").asText();
                    String target = candidato.path("target").asText();
                    String sourceNormal = normal(source).replace("_", "");
                    String targetNormal = normal(target).replace("_", "");
                    if (esCabecera(sourceNormal) && esConceptoDetallado(targetNormal)) {
                        directa = candidato;
                        cabecera = source;
                        concepto = target;
                        break;
                    }
                    if (esConceptoDetallado(sourceNormal) && esCabecera(targetNormal)) {
                        directa = candidato;
                        cabecera = target;
                        concepto = source;
                        break;
                    }
                }
                if (directa != null) {
                    directa.put("source", cabecera).put("target", asociativa)
                            .put("kind", "ASSOCIATION")
                            .put("sourceMultiplicity", "1")
                            .put("targetMultiplicity", "1..*")
                            .put("name", "contieneDetalles");
                    relaciones.addObject()
                            .put("source", asociativa).put("target", concepto)
                            .put("kind", "ASSOCIATION")
                            .put("sourceMultiplicity", "0..*")
                            .put("targetMultiplicity", "1")
                            .put("name", "referenciaConcepto");
                    reparadas++;
                }
                continue;
            }
            if (conexiones.size() != 1) continue;
            ObjectNode existente = conexiones.getFirst();
            String vecino = asociativa.equals(existente.path("source").asText())
                    ? existente.path("target").asText() : existente.path("source").asText();
            for (JsonNode candidatoNodo : relaciones) {
                ObjectNode candidato = (ObjectNode) candidatoNodo;
                if (!esAsociacion(candidato)) continue;
                if (candidato == existente || asociativa.equals(candidato.path("source").asText())
                        || asociativa.equals(candidato.path("target").asText())) continue;
                String source = candidato.path("source").asText();
                String target = candidato.path("target").asText();
                String otro = vecino.equals(source) ? target : vecino.equals(target) ? source : "";
                if (otro.isBlank() || !esCabecera(normal(otro).replace("_", ""))) continue;
                if (vecino.equals(source)) candidato.put("source", asociativa);
                else candidato.put("target", asociativa);
                reparadas++;
                break;
            }
        }

        // Si la asociativa ya esta conectada a sus dos extremos, la relacion
        // directa entre cabecera y concepto sobra y duplicaria el significado.
        for (JsonNode clase : plan.path("classes")) {
            String asociativa = clase.path("name").asText();
            if (!esDetalle(normal(asociativa).replace("_", ""))) continue;
            Set<String> vecinas = new HashSet<>();
            for (JsonNode r : relaciones) {
                if (!esAsociacion(r)) continue;
                if (asociativa.equals(r.path("source").asText())) vecinas.add(r.path("target").asText());
                else if (asociativa.equals(r.path("target").asText())) vecinas.add(r.path("source").asText());
            }
            String cabecera = vecinas.stream()
                    .filter(n -> esCabecera(normal(n).replace("_", ""))).findFirst().orElse(null);
            String concepto = vecinas.stream()
                    .filter(n -> esConceptoDetallado(normal(n).replace("_", ""))).findFirst().orElse(null);
            if (cabecera == null || concepto == null) continue;
            for (int i = relaciones.size() - 1; i >= 0; i--) {
                JsonNode r = relaciones.get(i);
                if (!esAsociacion(r)) continue;
                String source = r.path("source").asText();
                String target = r.path("target").asText();
                if ((source.equals(cabecera) && target.equals(concepto))
                        || (source.equals(concepto) && target.equals(cabecera))) {
                    relaciones.remove(i);
                    reparadas++;
                }
            }
        }
        return reparadas;
    }

    private static int asegurarDatosAsociativos(ObjectNode plan) {
        int agregados = 0;
        for (JsonNode claseNodo : plan.path("classes")) {
            ObjectNode clase = (ObjectNode) claseNodo;
            String nombreClase = normal(clase.path("name").asText()).replace("_", "");
            if (!nombreClase.contains("indicacionmedicamento")) continue;
            boolean tieneDato = false;
            for (JsonNode atributo : clase.path("attributes")) {
                String nombre = normal(atributo.path("name").asText()).replace("_", "");
                if (!atributo.path("pk").asBoolean(false) && !nombre.contains("identificador")
                        && !nombre.equals("id" + nombreClase)) tieneDato = true;
            }
            if (!tieneDato) {
                ((ArrayNode) clase.path("attributes")).addObject().put("name", "dosis")
                        .put("dataType", "string").put("required", true)
                        .put("unique", false).put("pk", false);
                agregados++;
            }
        }
        return agregados;
    }

    private static int repararTitularidades(ObjectNode plan) {
        int reparadas = 0;
        for (JsonNode clase : plan.path("classes")) {
            String nombre = clase.path("name").asText();
            if (!normal(nombre).startsWith("titular")) continue;
            List<JsonNode> conexiones = new java.util.ArrayList<>();
            for (JsonNode r : plan.path("relations")) {
                if (r.path("source").asText().equals(nombre) || r.path("target").asText().equals(nombre))
                    conexiones.add(r);
            }
            if (conexiones.size() != 1) continue;
            JsonNode r = conexiones.getFirst();
            String vecino = r.path("source").asText().equals(nombre)
                    ? r.path("target").asText() : r.path("source").asText();
            List<ObjectNode> candidatas = new java.util.ArrayList<>();
            for (JsonNode otra : plan.path("relations")) {
                if (otra == r || !otra.path("kind").asText().equals("ASSOCIATION")) continue;
                String s = otra.path("source").asText(), t = otra.path("target").asText();
                String actor = s.equals(vecino) ? t : t.equals(vecino) ? s : "";
                if (Set.of("cliente", "usuario", "persona", "propietario").contains(normal(actor))
                        && otra.path("sourceRole").asText().isBlank() && otra.path("targetRole").asText().isBlank())
                    candidatas.add((ObjectNode) otra);
            }
            // Solo transforma una asociacion ya explicita, sin inventar un
            // propietario cuando hay varias interpretaciones posibles.
            if (candidatas.size() == 1) {
                ObjectNode directa = candidatas.getFirst();
                if (directa.path("source").asText().equals(vecino)) directa.put("source", nombre);
                else directa.put("target", nombre);
                directa.put("name", "tieneTitularidades");
                reparadas++;
            }
        }
        return reparadas;
    }

    private static int ajustarPar(ObjectNode plan, String primera, String segunda,
                                  String juntoAPrimera, String juntoASegunda) {
        for (JsonNode relacionNodo : plan.path("relations")) {
            ObjectNode relacion = (ObjectNode) relacionNodo;
            if (!esAsociacion(relacion)) continue;
            String source = normal(relacion.path("source").asText()).replace("_", "");
            String target = normal(relacion.path("target").asText()).replace("_", "");
            if (coincidePatron(source, primera) && coincidePatron(target, segunda)) {
                return ponerSiCambio(relacion, "sourceMultiplicity", juntoAPrimera)
                        + ponerSiCambio(relacion, "targetMultiplicity", juntoASegunda);
            }
            if (coincidePatron(source, segunda) && coincidePatron(target, primera)) {
                return ponerSiCambio(relacion, "sourceMultiplicity", juntoASegunda)
                        + ponerSiCambio(relacion, "targetMultiplicity", juntoAPrimera);
            }
        }
        return 0;
    }

    /**
     * Reconoce el concepto base aunque el modelo le agregue un calificador
     * valido, por ejemplo CitaProgramada o RecetaMedica. Los nombres ya estan
     * normalizados y sin separadores cuando llegan aqui.
     */
    private static boolean coincidePatron(String nombre, String patron) {
        return nombre.equals(patron) || nombre.startsWith(patron);
    }

    private static int ponerSiCambio(ObjectNode relacion, String campo, String valor) {
        if (valor.equals(relacion.path(campo).asText())) return 0;
        relacion.put(campo, valor);
        return 1;
    }

    private static boolean esCabecera(String nombre) {
        return nombre.contains("receta") || nombre.contains("pedido") || nombre.contains("factura")
                || nombre.contains("orden") || nombre.contains("venta") || nombre.contains("compra");
    }

    private static boolean esDetalle(String nombre) {
        return nombre.contains("detalle") || nombre.contains("linea") || nombre.contains("item")
                || nombre.contains("medicamentoindicado") || nombre.contains("indicacion");
    }

    private static boolean esConceptoDetallado(String nombre) {
        return nombre.contains("medicamento") || nombre.contains("producto")
                || nombre.contains("servicio") || nombre.contains("articulo")
                || nombre.contains("insumo") || nombre.contains("material");
    }

    private static boolean esMembresia(String nombre) {
        return nombre.contains("participante") || nombre.contains("membresia")
                || nombre.contains("inscripcion") || nombre.contains("asignacion");
    }

    private static boolean esActorOContenedor(String nombre) {
        return nombre.startsWith("usuario") || nombre.startsWith("persona")
                || nombre.startsWith("estudiante") || nombre.startsWith("empleado")
                || nombre.startsWith("miembro") || nombre.startsWith("modelo")
                || nombre.startsWith("proyecto") || nombre.startsWith("curso")
                || nombre.startsWith("organizacion") || nombre.startsWith("equipo");
    }

    private static boolean esCatalogo(String nombre) {
        return nombre.startsWith("estado") || nombre.startsWith("tipo")
                || nombre.startsWith("categoria") || nombre.startsWith("clasificacion");
    }

    private static int retirarReferenciasDuplicadas(ObjectNode plan) {
        Map<String, Set<String>> relacionadas = new HashMap<>();
        for (JsonNode relacion : plan.path("relations")) {
            if (!esAsociacion(relacion)) continue;
            String source = relacion.path("source").asText();
            String target = relacion.path("target").asText();
            relacionadas.computeIfAbsent(source, ignorado -> new HashSet<>()).add(target);
            relacionadas.computeIfAbsent(target, ignorado -> new HashSet<>()).add(source);
        }

        int retiradas = 0;
        for (JsonNode claseNodo : plan.path("classes")) {
            ObjectNode clase = (ObjectNode) claseNodo;
            Set<String> vecinas = relacionadas.getOrDefault(clase.path("name").asText(), Set.of());
            ArrayNode atributos = (ArrayNode) clase.path("attributes");
            for (int i = atributos.size() - 1; i >= 0; i--) {
                JsonNode atributo = atributos.get(i);
                if (atributo.path("pk").asBoolean(false)) continue;
                String nombre = normal(atributo.path("name").asText()).replace("_", "");
                boolean duplicada = vecinas.stream().map(NormalizadorPlanUml::normal)
                        .map(n -> n.replace("_", ""))
                        .anyMatch(relacionada -> esReferenciaA(nombre, relacionada));
                if (duplicada) {
                    atributos.remove(i);
                    retiradas++;
                }
            }
        }
        return retiradas;
    }

    private static boolean esReferenciaA(String atributo, String claseRelacionada) {
        if (atributo.equals(claseRelacionada) || atributo.equals(claseRelacionada + "id")
                || atributo.equals("id" + claseRelacionada)) return true;
        boolean mencionaClase = atributo.startsWith(claseRelacionada) || atributo.endsWith(claseRelacionada);
        boolean pareceReferencia = atributo.contains("id") || atributo.contains("identificador")
                || atributo.contains("modificador") || atributo.contains("creador")
                || atributo.contains("responsable");
        if (mencionaClase && pareceReferencia) return true;
        // Tolera nombres de clase enriquecidos: servicioId debe reconocerse
        // como referencia a ServicioTuristico y asignatura a AsignaturaBase.
        String base = atributo.endsWith("id") ? atributo.substring(0, atributo.length() - 2) : atributo;
        return base.length() >= 4 && claseRelacionada.startsWith(base);
    }

    private static int completarCoberturaExplicita(ObjectNode plan, String requisito) {
        String pedido = normal(requisito);
        int agregados = 0;
        if (pedido.contains("devolucion")) agregados += agregarAClase(plan, "prestamo", "fechaDevolucion", "datetime", false);
        if (pedido.contains("vencimiento")) agregados += agregarAClase(plan, "prestamo", "fechaVencimiento", "datetime", true);
        return agregados;
    }

    private static int agregarAClase(ObjectNode plan, String claseBuscada, String nombre,
                                     String tipo, boolean requerido) {
        for (JsonNode claseNodo : plan.path("classes")) {
            ObjectNode clase = (ObjectNode) claseNodo;
            if (!normal(clase.path("name").asText()).contains(claseBuscada)) continue;
            for (JsonNode a : clase.path("attributes")) {
                if (normal(a.path("name").asText()).equals(normal(nombre))) {
                    if (normal(nombre).equals("fechadevolucion")) ((ObjectNode) a).put("required", false);
                    return 0;
                }
            }
            ((ArrayNode) clase.path("attributes")).addObject().put("name", nombre).put("dataType", tipo)
                    .put("required", requerido).put("unique", false).put("pk", false);
            return 1;
        }
        return 0;
    }

    private static int corregirCoautoriaExplicita(ObjectNode plan, String requisito) {
        String pedido = normal(requisito);
        boolean solicitada = pedido.contains("mas de un autor") || pedido.contains("varios autores")
                || pedido.contains("multiples autores") || pedido.contains("coautoria");
        if (!solicitada) return 0;
        for (JsonNode relacionNodo : plan.path("relations")) {
            ObjectNode r = (ObjectNode) relacionNodo;
            if (!esAsociacion(r)) continue;
            String source = normal(r.path("source").asText());
            String target = normal(r.path("target").asText());
            if (source.contains("obra") && target.contains("autor")) {
                r.put("sourceMultiplicity", "0..*");
                r.put("targetMultiplicity", "1..*");
                r.put("kind", "ASSOCIATION");
                return 1;
            }
            if (source.contains("autor") && target.contains("obra")) {
                r.put("sourceMultiplicity", "1..*");
                r.put("targetMultiplicity", "0..*");
                r.put("kind", "ASSOCIATION");
                return 1;
            }
        }
        return 0;
    }

    private static int recortarAtributosSecundarios(ObjectNode plan, String requisito) {
        int eliminados = 0;
        String requisitoCompacto = normal(requisito).replaceAll("[^a-z0-9]", "");
        while (cantidadCambios(plan) > Lote.TOPE_TEXTO_O_VOZ) {
            boolean eliminado = false;
            for (int c = plan.path("classes").size() - 1; c >= 0 && !eliminado; c--) {
                JsonNode clase = plan.path("classes").get(c);
                ArrayNode atributos = (ArrayNode) plan.path("classes").get(c).path("attributes");
                if (atributos.size() <= 2) continue; // identidad + al menos un dato propio
                for (int a = atributos.size() - 1; a >= 0; a--) {
                    JsonNode atributo = atributos.get(a);
                    String nombre = normal(atributo.path("name").asText());
                    boolean protegido = atributo.path("pk").asBoolean(false)
                            || nombre.equals("fechadevolucion") || nombre.equals("fechavencimiento")
                            || nombre.equals("dosis") || nombre.equals("cantidad")
                            || nombre.equals("frecuencia") || nombre.contains("precioacordado")
                            || nombre.contains("preciounitariohistorico") || nombre.contains("monto")
                            || nombre.contains("importe")
                            || ((requisitoCompacto.contains("cupo") || requisitoCompacto.contains("capacidad"))
                            && (nombre.contains("cupo") || nombre.contains("capacidad")))
                            || (esHistorica((ObjectNode) clase) && (nombre.contains("fecha")
                            || nombre.equals("inicio") || nombre.equals("fin")
                            || Set.of("date", "datetime").contains(atributo.path("dataType").asText())))
                            || nombre.contains("porcentaje") || nombre.contains("prioridad")
                            || nombre.contains("orden") || nombre.contains("rol")
                            || requisitoCompacto.contains(nombre.replaceAll("[^a-z0-9]", ""));
                    if (!protegido) {
                        atributos.remove(a);
                        eliminados++;
                        eliminado = true;
                        break;
                    }
                }
            }
            if (!eliminado) break;
        }
        return eliminados;
    }

    private static int cantidadCambios(ObjectNode plan) {
        int atributos = 0;
        for (JsonNode clase : plan.path("classes")) atributos += clase.path("attributes").size();
        return plan.path("classes").size() + atributos + plan.path("relations").size();
    }

    private static boolean esHistorica(ObjectNode clase) {
        String nombre = normal(clase.path("name").asText());
        if (nombre.equals("venta") || nombre.equals("compra")
                || nombre.contains("prestamo") || nombre.contains("reserva") || nombre.contains("historial")
                || nombre.contains("transaccion") || nombre.contains("movimiento") || nombre.contains("evento")
                || nombre.contains("transferencia") || nombre.contains("traslado")
                || nombre.contains("cancelacion") || nombre.startsWith("salida")
                || nombre.contains("cita") || nombre.contains("consulta") || nombre.contains("diagnostico")
                || nombre.contains("receta") || nombre.contains("pedido") || nombre.contains("pago")
                || nombre.contains("aplicacionproducto") || nombre.contains("aplicacionmedicamento")
                || nombre.contains("version") || nombre.contains("asignacion")) {
            return true;
        }
        for (JsonNode a : clase.path("attributes")) {
            String atributo = normal(a.path("name").asText());
            if (atributo.contains("fechadevolucion") || atributo.contains("fechavencimiento")
                    || atributo.contains("fechacierre")
                    || atributo.equals("inicio") || atributo.equals("fin")) {
                return true;
            }
        }
        return false;
    }

    private static String identificador(String valor, boolean pascalCase) {
        String sinTildes = Normalizer.normalize(valor == null ? "" : valor, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        String[] partes = sinTildes.replaceAll("[^A-Za-z0-9]+", " ").trim().split("\\s+");
        StringBuilder resultado = new StringBuilder();
        for (int i = 0; i < partes.length; i++) {
            if (partes[i].isEmpty()) continue;
            String parte = partes[i];
            if (i == 0 && !pascalCase) resultado.append(Character.toLowerCase(parte.charAt(0))).append(parte.substring(1));
            else resultado.append(Character.toUpperCase(parte.charAt(0))).append(parte.substring(1));
        }
        String limpio = resultado.toString();
        if (limpio.isBlank()) return pascalCase ? "Elemento" : "valor";
        if (Character.isDigit(limpio.charAt(0))) return (pascalCase ? "Elemento" : "valor") + limpio;
        return limpio;
    }

    private static String normal(String valor) {
        return Normalizer.normalize(valor == null ? "" : valor, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }
}

