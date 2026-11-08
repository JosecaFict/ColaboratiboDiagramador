package com.uagrm.colabmodel.ai;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.uagrm.colabmodel.modelcore.command.Command;
import com.uagrm.colabmodel.modelcore.command.CommandType;
import com.uagrm.colabmodel.modelcore.command.Lote;
import com.uagrm.colabmodel.modelcore.command.Origin;
import com.uagrm.colabmodel.modelcore.metamodel.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Orquesta un pedido en lenguaje natural: le pide al proveedor activo que
 * traduzca el prompt a llamadas de herramienta, y valida esa respuesta ANTES
 * de que se pueda tocar el bus (deliverable 3 del item 4.4).
 *
 * Lo que devuelve (`PropuestaDeIA`) es de solo lectura: no escribe nada. El
 * tope y el catalogo se cumplen ACA, del lado del servidor, sin importar lo
 * que haya dicho el prompt o lo obediente que sea el modelo (ADR-014):
 * aunque el proveedor devuelva muchos comandos o uno inventado, esta clase nunca
 * deja pasar mas del tope de {@link Lote} ni un tipo fuera del catalogo.
 */
@Service
public class ServicioDeIA {

    private static final JsonNodeFactory J = JsonNodeFactory.instance;

    private final ProveedorDeIA proveedor;
    private final UmlClassRepository classes;
    private final UmlAttributeRepository attributes;
    private final UmlMethodRepository methods;
    private final UmlRelationshipRepository relationships;

    public ServicioDeIA(ProveedorDeIA proveedor, UmlClassRepository classes, UmlAttributeRepository attributes,
                        UmlMethodRepository methods, UmlRelationshipRepository relationships) {
        this.proveedor = proveedor;
        this.classes = classes;
        this.attributes = attributes;
        this.methods = methods;
        this.relationships = relationships;
    }

    @Transactional(readOnly = true)
    public PropuestaDeIA generar(UUID diagramId, String prompt, Origin origin) {
        return generar(diagramId, prompt, origin, ModoIA.MODIFICAR);
    }

    @Transactional(readOnly = true)
    public PropuestaDeIA generar(UUID diagramId, String prompt, Origin origin, ModoIA modo) {
        return generar(diagramId, prompt, origin, modo, null);
    }

    @Transactional(readOnly = true)
    public PropuestaDeIA generar(UUID diagramId, String prompt, Origin origin, ModoIA modo, String modelo) {
        if (origin != Origin.TEXT && origin != Origin.VOICE) {
            throw new IllegalArgumentException("La IA solo produce comandos con origen TEXT o VOICE, no " + origin);
        }
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("El prompt esta vacio");
        }

        List<UmlClass> existentes = classes.findByDiagramIdAndDeletedAtIsNullOrderByNameAsc(diagramId);
        List<UUID> ids = existentes.stream().map(UmlClass::getId).toList();
        Map<UUID, List<UmlAttribute>> atributosPorClase = ids.isEmpty() ? Map.of()
                : attributes.findByClassIdInOrderByOrdinalAsc(ids).stream()
                        .collect(Collectors.groupingBy(UmlAttribute::getClassId));
        Map<UUID, List<UmlMethod>> metodosPorClase = ids.isEmpty() ? Map.of()
                : methods.findByClassIdInOrderByOrdinalAsc(ids).stream()
                        .collect(Collectors.groupingBy(UmlMethod::getClassId));
        List<UmlRelationship> relacionesExistentes = relationships.findByDiagramIdAndDeletedAtIsNull(diagramId);

        Map<UUID, String> nombres = existentes.stream().collect(Collectors.toMap(UmlClass::getId, UmlClass::getName));
        List<SolicitudIA.ClaseEnContexto> contexto = existentes.stream()
                .map(c -> new SolicitudIA.ClaseEnContexto(c.getName(),
                        atributosPorClase.getOrDefault(c.getId(), List.of()).stream()
                                .map(a -> a.getName() + ": " + a.getDataType()).toList(),
                        metodosPorClase.getOrDefault(c.getId(), List.of()).stream()
                                .map(m -> m.getName() + "(): " + m.getReturnType()).toList(),
                        c.getStereotype(), c.isAbstract()))
                .toList();
        List<SolicitudIA.RelacionEnContexto> relaciones = relacionesExistentes.stream()
                .map(r -> new SolicitudIA.RelacionEnContexto(
                        nombres.getOrDefault(r.getSourceClassId(), r.getSourceClassId().toString()),
                        nombres.getOrDefault(r.getTargetClassId(), r.getTargetClassId().toString()),
                        r.getKind().name(), r.getName(), r.getSourceMultiplicity(), r.getTargetMultiplicity()))
                .toList();

        List<HerramientaIA> herramientas = switch (modo) {
            case CONSULTAR, REVISAR -> List.of();
            case DISENAR -> List.of(EsquemaPlanUml.herramienta());
            case MODIFICAR -> EsquemaDeComandos.herramientas();
        };
        RespuestaIA respuesta = proveedor.generar(new SolicitudIA(prompt, modo, contexto, relaciones, herramientas, modelo));
        boolean planCompleto = modo == ModoIA.DISENAR && respuesta.comandos().stream()
                .anyMatch(c -> EsquemaPlanUml.NOMBRE.equals(c.tipo()));
        if (modo == ModoIA.DISENAR) {
            respuesta = revisarPlan(prompt, respuesta, contexto, relaciones, modelo);
            respuesta = NormalizadorPlanUml.normalizar(respuesta, prompt);
            List<String> pendientes = hallazgosFinales(respuesta, prompt, contexto);
            if (!pendientes.isEmpty()) {
                respuesta = repararPlanFinal(prompt, respuesta, pendientes, contexto, relaciones, modelo);
            }
            respuesta = ExpansorPlanUml.expandir(respuesta);
        }

        PropuestaDeIA propuesta = validar(diagramId, origin, respuesta, existentes, atributosPorClase, metodosPorClase,
                relacionesExistentes, modelo == null || modelo.isBlank() ? proveedor.nombre() : modelo);
        if (planCompleto && (propuesta.truncada() || !propuesta.rechazados().isEmpty())) {
            throw new ProveedorIAException("El plan no pudo convertirse completo en cambios validos: "
                    + propuesta.rechazados().stream().map(PropuestaDeIA.ComandoRechazado::motivo)
                    .collect(java.util.stream.Collectors.joining("; "))
                    + (propuesta.truncada() ? "; se excedio el limite de cambios" : ""));
        }
        return propuesta;
    }

    /**
     * Segunda pasada independiente: no pide continuar el borrador sino
     * cuestionarlo contra el requisito original. Si el revisor local falla,
     * se conserva el primer plan valido en vez de perder toda la consulta.
     */
    private RespuestaIA revisarPlan(String requisito, RespuestaIA borrador,
                                    List<SolicitudIA.ClaseEnContexto> contexto,
                                    List<SolicitudIA.RelacionEnContexto> relaciones, String modelo) {
        ComandoPropuestoIA plan = borrador.comandos().stream()
                .filter(c -> EsquemaPlanUml.NOMBRE.equals(c.tipo())).findFirst().orElse(null);
        if (ProveedorOllama.errorDeFormatoDelPlan(plan) != null) return borrador;

        RespuestaIA baseNormalizada = NormalizadorPlanUml.normalizar(borrador, requisito);
        List<String> hallazgosBase = hallazgosFinales(baseNormalizada, requisito, contexto);

        String pedidoRevision = """
                ACTUA COMO REVISOR FINAL DEL PLAN UML, no como autor complaciente.

                REQUISITO ORIGINAL:
                %s

                BORRADOR A AUDITAR:
                %s

                HALLAZGOS VERIFICABLES DEL BACKEND:
                %s

                Devuelve mediante PROPONER_MODELO_UML el modelo completo ya corregido, no una lista de consejos.
                Comprueba uno por uno los requisitos del texto original y corrige omisiones y contradicciones.
                Para cada relacion aplica esta regla sin excepciones: la multiplicidad junto a una clase indica
                cuantas instancias DE ESA CLASE puede tener una instancia del extremo contrario. Verifica la lectura
                en ambos sentidos antes de responder. Las entidades de evento o historial normalmente deben conectar
                al menos al actor y al objeto involucrado. Comprueba que la auditoria describa exactamente las
                relaciones realmente incluidas. Antes de agregar atributos secundarios, garantiza que ninguna clase
                quede desconectada y que toda clase asociativa conecte exactamente los conceptos que vincula. Reserva
                primero el presupuesto para todas las clases, una identidad minima por clase y todas las relaciones
                fundamentales; usa el espacio restante para atributos descriptivos. Conserva lo correcto y no agregues
                funciones fuera del alcance.
                """.formatted(requisito, baseNormalizada.comandos().getFirst().parametros().toString(),
                        hallazgosBase.isEmpty() ? "Sin hallazgos estructurales; revisa la cobertura del requisito."
                                : String.join("\n", hallazgosBase));
        try {
            RespuestaIA revisada = proveedor.generar(new SolicitudIA(pedidoRevision, ModoIA.DISENAR,
                    contexto, relaciones, List.of(EsquemaPlanUml.herramienta()), modelo));
            ComandoPropuestoIA planRevisado = revisada.comandos().stream()
                    .filter(c -> EsquemaPlanUml.NOMBRE.equals(c.tipo())).findFirst().orElse(null);
            if (ProveedorOllama.errorDeFormatoDelPlan(planRevisado) != null) return borrador;
            RespuestaIA candidata = NormalizadorPlanUml.normalizar(revisada, requisito);
            return hallazgosFinales(candidata, requisito, contexto).size() > hallazgosBase.size()
                    ? borrador : revisada;
        } catch (ProveedorIAException e) {
            return new RespuestaIA(borrador.comandos(), unirNotas(borrador.notaDelModelo(),
                    "La revision automatica no estuvo disponible; se conserva el primer plan validado."));
        }
    }

    private static String unirNotas(String primera, String segunda) {
        return primera == null || primera.isBlank() ? segunda : primera + "\n\n" + segunda;
    }

    private static List<String> hallazgosFinales(RespuestaIA respuesta, String requisito,
            List<SolicitudIA.ClaseEnContexto> contexto) {
        List<String> hallazgos = new ArrayList<>(ValidadorPlanFinal.revisar(respuesta,
                contexto.stream().map(SolicitudIA.ClaseEnContexto::nombre).collect(Collectors.toSet())));
        hallazgos.addAll(RevisorCoberturaPlan.revisar(respuesta, requisito));
        return List.copyOf(new java.util.LinkedHashSet<>(hallazgos));
    }

    private RespuestaIA repararPlanFinal(String requisito, RespuestaIA borrador, List<String> hallazgos,
            List<SolicitudIA.ClaseEnContexto> contexto, List<SolicitudIA.RelacionEnContexto> relaciones,
            String modelo) {
        String pedido = "REPARACION FINAL DEL PLAN UML. Devuelve el plan completo mediante PROPONER_MODELO_UML. "
                + "Conserva las partes correctas. Corrige estos hallazgos verificables: " + String.join("\n", hallazgos)
                + "\nNo agregues asociaciones sin respaldo en el requisito. Si repites una pareja usa roles distintos. "
                + "Una cuenta puede intervenir en muchas transferencias por cada rol; cada transferencia identifica "
                + "una cuenta por rol. Una entidad puede contener muchos detalles, cada detalle pertenece a su cabecera. "
                + "Prioriza requisitos esenciales y evita clases artificiales para roles. "
                + "La auditoria debe describir exclusivamente el plan que entregas.\nREQUISITO:\n" + requisito
                + "\nPLAN NORMALIZADO:\n" + borrador.comandos().getFirst().parametros().toString();
        RespuestaIA reparada;
        try {
            reparada = proveedor.generar(new SolicitudIA(pedido, ModoIA.DISENAR, contexto, relaciones,
                    List.of(EsquemaPlanUml.herramienta()), modelo));
        } catch (ProveedorIAException e) {
            return repararConAjustes(requisito, borrador, hallazgos, modelo);
        }
        boolean tienePlan = reparada.comandos().stream().anyMatch(c -> EsquemaPlanUml.NOMBRE.equals(c.tipo()));
        if (!tienePlan) return repararConAjustes(requisito, borrador, hallazgos, modelo);
        reparada = NormalizadorPlanUml.normalizar(reparada, requisito);
        List<String> pendientes = hallazgosFinales(reparada, requisito, contexto);
        if (!pendientes.isEmpty()) return pendientes.size() > hallazgos.size()
                ? repararConAjustes(requisito, borrador, hallazgos, modelo)
                : repararConAjustes(requisito, reparada, pendientes, modelo);
        return reparada;
    }

    private RespuestaIA repararConAjustes(String requisito, RespuestaIA borrador, List<String> pendientes, String modelo) {
        var plan = borrador.comandos().stream().filter(c -> EsquemaPlanUml.NOMBRE.equals(c.tipo())).findFirst().orElseThrow();
        List<SolicitudIA.ClaseEnContexto> virtuales = new ArrayList<>();
        for (var c : plan.parametros().path("classes")) {
            List<String> attrs = new ArrayList<>();
            for (var a : c.path("attributes")) attrs.add(a.path("name").asText() + ": " + a.path("dataType").asText());
            virtuales.add(new SolicitudIA.ClaseEnContexto(c.path("name").asText(), attrs));
        }
        StringBuilder indices = new StringBuilder();
        int indice = 0;
        for (var r : plan.parametros().path("relations")) indices.append(indice++).append(": ").append(r.toString()).append('\n');
        String pedido = "REPARACION DIRIGIDA DE UN BORRADOR VIRTUAL. Conserva las clases existentes; no regeneres todo el modelo. "
                + "Puedes agregar hasta dos clases esenciales omitidas, solo si los hallazgos y el requisito las necesitan, "
                + "con atributos propios y asociaciones completas en este mismo lote. Nunca borres ni reemplaces clases existentes. "
                + "Usa solamente las herramientas disponibles para corregir los hallazgos enumerados. "
                + "Las clases del contexto ya existen dentro del borrador. Devuelve hasta 16 ajustes necesarios en una respuesta. "
                + "Conserva las relaciones y datos correctos. No agregues funciones fuera del requisito. "
                + "Cada relacion nueva debe incluir multiplicidad en ambos extremos.\nREQUISITO ORIGINAL:\n" + requisito
                + "\nHALLAZGOS A RESOLVER:\n" + String.join("\n", pendientes)
                + "\nRELACIONES POR indiceRelacion (para UPDATE/DELETE, no adivines una pareja con varios roles):\n" + indices
                + "\nLas clases y sus atributos estan en el contexto adjunto; no los regeneres ni los dupliques. "
                + "Los indices anteriores son estables durante este lote de ajustes. "
                + "Resuelve todos los hallazgos con cambios concretos, no promesas de cambios posteriores.";
        try {
            RespuestaIA ajustes = proveedor.generar(new SolicitudIA(pedido, ModoIA.MODIFICAR, virtuales, List.of(),
                    ReparadorDirigidoPlan.herramientas(), modelo));
            RespuestaIA reparada = NormalizadorPlanUml.normalizar(ReparadorDirigidoPlan.aplicar(borrador, ajustes), requisito);
            List<String> restantes = hallazgosFinales(reparada, requisito, virtuales);
            if (restantes.isEmpty()) return reparada;
            throw new ProveedorIAException("El plan sigue incompleto tras los ajustes dirigidos: " + String.join("; ", restantes));
        } catch (ProveedorIAException e) {
            throw new ProveedorIAException("El plan sigue incompleto tras la reparacion: " + String.join("; ", pendientes)
                    + ". Reparacion dirigida: " + e.getMessage());
        }
    }

    private record Candidato(int posicion, CommandType tipo, com.fasterxml.jackson.databind.JsonNode payload,
                             String resumen) { }

    private PropuestaDeIA validar(UUID diagramId, Origin origin, RespuestaIA respuesta, List<UmlClass> existentes,
                                  Map<UUID, List<UmlAttribute>> atributosPorClase,
                                  Map<UUID, List<UmlMethod>> metodosPorClase,
                                  List<UmlRelationship> relacionesExistentes, String modelo) {
        ResolutorDeReferencias resolutor = new ResolutorDeReferencias(existentes, atributosPorClase,
                metodosPorClase, relacionesExistentes);

        List<Candidato> validos = new ArrayList<>();
        List<PropuestaDeIA.ComandoRechazado> rechazados = new ArrayList<>();

        int posicion = 0;
        for (ComandoPropuestoIA c : respuesta.comandos()) {
            posicion++;
            CommandType tipo;
            try {
                tipo = CommandType.valueOf(c.tipo());
            } catch (IllegalArgumentException e) {
                rechazados.add(new PropuestaDeIA.ComandoRechazado(posicion, c.tipo(),
                        "\"" + c.tipo() + "\" no es un comando del catalogo (ADR-014)"));
                continue;
            }
            if (!EsquemaDeComandos.esHerramienta(tipo)) {
                rechazados.add(new PropuestaDeIA.ComandoRechazado(posicion, c.tipo(),
                        "la IA no puede proponer " + tipo + ": no forma parte de su catalogo de edicion"));
                continue;
            }
            try {
                ResolutorDeReferencias.Traduccion t = resolutor.traducir(tipo, c.parametros());
                validos.add(new Candidato(posicion, tipo, t.payload(), t.resumen()));
            } catch (ComandoIAInvalidoException e) {
                rechazados.add(new PropuestaDeIA.ComandoRechazado(posicion, c.tipo(), e.getMessage()));
            }
        }

        int tope = Lote.TOPE_TEXTO_O_VOZ;
        boolean excede = validos.size() > tope;
        List<Candidato> aceptadosLista = excede ? validos.subList(0, tope) : validos;
        List<String> descartadosPorTope = excede
                ? validos.subList(tope, validos.size()).stream()
                        .map(v -> v.resumen() + " (cambio " + v.posicion() + " del pedido)")
                        .toList()
                : List.of();

        List<PropuestaDeIA.ComandoAceptado> aceptados = aceptadosLista.stream()
                .map(v -> new PropuestaDeIA.ComandoAceptado(v.tipo(), v.payload(), v.resumen()))
                .toList();

        List<String> advertencias = AuditorDePropuestaIA.auditar(aceptados);
        return new PropuestaDeIA(diagramId, origin, aceptados, List.copyOf(rechazados), descartadosPorTope,
                advertencias, respuesta.comandos().size(), respuesta.notaDelModelo(), modelo, null);
    }

    /**
     * El BATCH listo para el bus: mismo contrato que importacion y mouse
     * (ADR-002/ADR-016), con origin TEXT o VOICE segun quien pidio la
     * propuesta. Deliberadamente no hay un metodo que lo mande solo: aplicar
     * es escribir, y escribir pasa siempre por el mismo camino (DiagramCommandBus),
     * nunca por un segundo endpoint del modulo de IA.
     */
    public Command comandoBatchDe(PropuestaDeIA propuesta) {
        if (propuesta.aceptados().isEmpty()) {
            throw new IllegalStateException("La propuesta no tiene ningun cambio aceptado para aplicar");
        }
        ObjectNode payload = J.objectNode();
        payload.put("descripcion", "Generado por IA (" + propuesta.origin() + ")");
        ArrayNode lista = payload.putArray("comandos");
        for (PropuestaDeIA.ComandoAceptado a : propuesta.aceptados()) {
            ObjectNode c = lista.addObject();
            c.put("type", a.tipo().name());
            c.set("payload", a.payload());
        }
        return new Command(UUID.randomUUID(), propuesta.diagramId(), 0, CommandType.BATCH, propuesta.origin(), payload);
    }
}
