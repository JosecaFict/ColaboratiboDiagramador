package com.uagrm.colabmodel.interop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uagrm.colabmodel.modelcore.mapping.Interfaces;
import com.uagrm.colabmodel.modelcore.metamodel.*;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * El diagrama, como XMI 2.5.1 / UML 2.5.1 estandar (ADR-013, ADR-022, ADR-024).
 *
 * Simetrico a `LectorXmi`, pero mas chico: no hay que tolerar un dialecto
 * ajeno, asi que se escribe la forma MAS SIMPLE que ese mismo lector ya
 * entiende de un archivo estandar -sin Extension de EA, sin perfil propio,
 * sin pathmap:// de Eclipse-, para que exportar y reimportar con NUESTRO
 * lector sea un camino real, no uno que nadie recorre.
 *
 * Misma doctrina que ADR-018 (SQL): "equivalencia salvo perdidas
 * declaradas". Lo que UML 2.5.1 puro no representa -las decisiones de mapeo
 * OO-Datos (A-1..A-4), la eleccion de estrategia de herencia, el estereotipo
 * «embeddable»/«enumeration», y banderas que son de ESTE metamodelo y no de
 * UML (unico, clave parcial, rango, relacion identificadora)- NO se fuerza
 * con un tagged value propietario ni un perfil inventado: se declara, en
 * `perdidas`, igual que `LectorSql` declara lo que no puede re-detectar.
 *
 * Lo que SI es UML 2.5.1 de verdad se escribe con el atributo estandar que le
 * corresponde, no con una convencion nuestra: `isID` y `isDerived` son
 * atributos reales de `Property` (no un truco), `visibility` y `defaultValue`
 * tambien, la multiplicidad cubre obligatorio/multivaluado, y
 * `navigableOwnedEnd` fija sin ambiguedad cual extremo es el origen.
 *
 * XMI 2.5.1 no lleva `xmi:version`: la version ES el namespace
 * (`XMI/20131001`, `UML/20161101`), que es lo que miran EA, Papyrus y
 * MagicDraw para elegir con que importarlo.
 *
 * Segundo perfil, {@link Perfil#ENTERPRISE_ARCHITECT} (ADR-031): el mismo
 * modelo en el dialecto que EA escribe y lee de si mismo -XMI 2.1, ids
 * `EAID_`/`EAPK_`, tipos `EAnone_*`, un paquete que lo contiene- mas la
 * `xmi:Extension` de EA con el estereotipo de cada clase y el DIAGRAMA: sin
 * ella EA importa las clases al Project Browser pero no dibuja nada, porque
 * UML 2.x no tiene donde guardar la disposicion y EA no lee UML DI de XMI 2.1.
 */
public final class EscritorXmi {

    /** Para quien se escribe (ADR-031). El estandar sigue siendo el de ADR-024. */
    public enum Perfil { ESTANDAR, ENTERPRISE_ARCHITECT }

    static final String NS_XMI_21 = "http://schema.omg.org/spec/XMI/2.1";
    static final String NS_UML_21 = "http://schema.omg.org/spec/UML/2.1";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static final String NS_XMI = "http://www.omg.org/spec/XMI/20131001";
    static final String NS_UML = "http://www.omg.org/spec/UML/20161101";

    /**
     * Los tipos del modelo que SON un tipo primitivo de UML 2.5.1 se tipan
     * contra la biblioteca estandar de la OMG, por `href`: cualquier
     * herramienta la trae y la resuelve sola. Solo los equivalentes exactos:
     * `text` no es `String` (perderia la diferencia con varchar) ni `decimal`
     * es `Real` (perderia la exactitud), asi que esos siguen como
     * `uml:PrimitiveType` local, con su propio nombre.
     */
    private static final Map<String, String> PRIMITIVOS_OMG = Map.of(
            "string", "String", "int", "Integer", "integer", "Integer", "boolean", "Boolean");

    private final boolean ea;
    /** Solo para las fechas `created`/`modified` que EA escribe en su Extension; fijo en las pruebas. */
    private final java.time.Clock reloj;

    private EscritorXmi(Perfil perfil, java.time.Clock reloj) {
        this.ea = perfil == Perfil.ENTERPRISE_ARCHITECT;
        this.reloj = reloj;
    }

    /** @param perdidas lo que este modelo tenia y el XMI no pudo llevarse, para mostrarlo ANTES de bajar el archivo */
    public record Exportado(byte[] xmi, List<String> perdidas) { }

    /** El perfil estandar, sin disposicion (UML 2.5.1 no la lleva). */
    public static Exportado exportar(List<UmlClass> clases, List<UmlAttribute> atributos, List<UmlMethod> metodos,
                                     List<UmlRelationship> relaciones) {
        return exportar(clases, atributos, metodos, relaciones, List.of(), Perfil.ESTANDAR);
    }

    /** @param disposicion donde esta cada clase en el lienzo; solo la usa el perfil de EA */
    public static Exportado exportar(List<UmlClass> clases, List<UmlAttribute> atributos, List<UmlMethod> metodos,
                                     List<UmlRelationship> relaciones, List<DiagramLayout> disposicion,
                                     Perfil perfil) {
        return exportar(clases, atributos, metodos, relaciones, disposicion, perfil, java.time.Clock.systemDefaultZone());
    }

    static Exportado exportar(List<UmlClass> clases, List<UmlAttribute> atributos, List<UmlMethod> metodos,
                              List<UmlRelationship> relaciones, List<DiagramLayout> disposicion, Perfil perfil,
                              java.time.Clock reloj) {
        return new EscritorXmi(perfil, reloj).escribir(clases, atributos, metodos, relaciones, disposicion);
    }

    private Exportado escribir(List<UmlClass> clases, List<UmlAttribute> atributos, List<UmlMethod> metodos,
                               List<UmlRelationship> relaciones, List<DiagramLayout> disposicion) {
        Map<UUID, List<UmlAttribute>> atrsPorClase = agrupar(atributos, UmlAttribute::getClassId);
        Map<UUID, List<UmlMethod>> metsPorClase = agrupar(metodos, UmlMethod::getClassId);
        List<UmlRelationship> generalizaciones = relaciones.stream()
                .filter(r -> r.getKind() == RelationKind.GENERALIZATION).toList();
        List<UmlRelationship> dependencias = relaciones.stream()
                .filter(r -> r.getKind() == RelationKind.DEPENDENCY).toList();
        List<UmlRelationship> realizaciones = relaciones.stream()
                .filter(r -> r.getKind() == RelationKind.REALIZATION).toList();
        // Una realizacion NO es una asociacion: sale como interfaceRealization dentro de la clase (D-15).
        List<UmlRelationship> asociaciones = relaciones.stream()
                .filter(r -> r.getKind() != RelationKind.GENERALIZATION && r.getKind() != RelationKind.DEPENDENCY
                        && r.getKind() != RelationKind.REALIZATION)
                .toList();

        // Se calculan ANTES de escribir: asi viajan en el propio archivo, en
        // el `xmi:Documentation` del principio, y no solo en la respuesta HTTP.
        List<String> perdidas = perdidasDe(clases, atributos, generalizaciones);
        Map<String, String> idTipo = ea ? tiposEa(clases, atributos, metodos) : tiposPrimitivos(atributos, metodos);
        String paquete = "EAPK_" + guid(UUID.nameUUIDFromBytes(("paquete" + clases.stream().map(UmlClass::getId)
                .map(UUID::toString).sorted().toList()).getBytes(StandardCharsets.UTF_8)));

        Xml x = new Xml();
        if (ea) {
            x.abrir("xmi:XMI", "xmi:version", "2.1", "xmlns:uml", NS_UML_21, "xmlns:xmi", NS_XMI_21);
        } else {
            x.abrir("xmi:XMI", "xmlns:xmi", NS_XMI, "xmlns:uml", NS_UML);
        }
        x.abrir("xmi:Documentation", "exporter", "ColabModel", "exporterVersion", "1.0");
        x.texto("Perdidas declaradas (ADR-018): " + String.join(" | ", perdidas));
        x.cerrar("xmi:Documentation");
        x.abrir("uml:Model", "xmi:type", "uml:Model", "xmi:id", ea ? "EAPK_MODELO" : "modelo",
                "name", ea ? "EA_Model" : "Modelo");
        // EA importa un PAQUETE ("Import Package from XMI"): las clases van
        // dentro de uno, y el diagrama cuelga de ese mismo paquete.
        if (ea) x.abrir("packagedElement", "xmi:type", "uml:Package", "xmi:id", paquete, "name", "ColabModel",
                "visibility", "public");

        for (UmlClass c : clases) {
            escribirClase(x, c, atrsPorClase.getOrDefault(c.getId(), List.of()),
                    metsPorClase.getOrDefault(c.getId(), List.of()), generalizaciones, realizaciones, idTipo);
        }
        for (UmlRelationship r : asociaciones) escribirAsociacion(x, r);
        for (UmlRelationship r : dependencias) {
            x.vacio("packagedElement", "xmi:type", "uml:Dependency", "xmi:id", id("r", r.getId()), "name", r.getName(),
                    "client", id("c", r.getSourceClassId()), "supplier", id("c", r.getTargetClassId()));
        }
        // En EA los tipos primitivos NO van en el modelo: los define la Extension (`primitivetypes`).
        if (!ea) idTipo.forEach((nombre, id) -> x.vacio("packagedElement", "xmi:type", "uml:PrimitiveType",
                "xmi:id", id, "name", nombre));

        if (ea) x.cerrar("packagedElement");
        x.cerrar("uml:Model");
        if (ea) extensionEa(x, paquete, clases, atrsPorClase, metsPorClase, disposicion, relaciones, idTipo);
        x.cerrar("xmi:XMI");

        return new Exportado(x.toString().getBytes(StandardCharsets.UTF_8), perdidas);
    }

    /**
     * Todo lo que este modelo tiene y UML 2.5.1 puro no representa, ANTES de
     * escribir nada: ni un tagged value propietario ni un perfil inventado
     * lo fuerza (ADR-020). Siempre trae, al menos, la perdida estructural de
     * las decisiones A-1..A-4 (viven en `modeling_decisions`, no en la clase
     * ni en el atributo, asi que ningun XMI las puede llevar).
     */
    private List<String> perdidasDe(List<UmlClass> clases, List<UmlAttribute> atributos,
                                           List<UmlRelationship> generalizaciones) {
        List<String> perdidas = new ArrayList<>();
        Map<UUID, List<UmlAttribute>> atrsPorClase = agrupar(atributos, UmlAttribute::getClassId);
        for (UmlClass c : clases) {
            // En EA viaja en la Extension; una enumeracion o un valor con atributos
            // salen como uml:Enumeration / uml:DataType, que son UML estandar (T-05).
            boolean estandar = !metaclase(c, atrsPorClase.getOrDefault(c.getId(), List.of())).equals("uml:Class");
            if (c.getStereotype() != null && !ea && !estandar) {
                perdidas.add(c.getName() + " es «" + c.getStereotype() + "»: el estereotipo no viaja en UML 2.5.1"
                        + " puro (Regla 5). Se exporta como una clase comun; hay que volver a marcarla al"
                        + " reimportar.");
            }
            if (c.isWeak() || c.getErKind() != ErKind.STRONG) {
                perdidas.add(c.getName() + ": la bandera ER (" + (c.isWeak() ? "debil" : c.getErKind()) + ") es"
                        + " una anotacion de mapeo de ESTE metamodelo, no de UML: no se exporta.");
            }
            if (generalizaciones.stream().anyMatch(g -> g.getTargetClassId().equals(c.getId()))
                    && c.getInheritanceStrategy() != InheritanceStrategy.JOINED) {
                perdidas.add(c.getName() + ": la estrategia de herencia (" + c.getInheritanceStrategy() + ") es"
                        + " una decision de mapeo, no de UML: no se exporta (ADR-018). JOINED es lo unico que"
                        + " UML 2.5.1 reconstruye solo, por la forma de la generalizacion.");
            }
            for (UmlAttribute a : atrsPorClase.getOrDefault(c.getId(), List.of())) {
                List<String> razones = new ArrayList<>();
                if (a.isUnique() && !a.isPk()) razones.add("unico");   // PK ya es unico por si sola: no se pierde
                if (a.isPartialKey()) razones.add("clave parcial");
                if (a.getMinValue() != null || a.getMaxValue() != null) razones.add("rango");
                if (a.getLength() != null) razones.add("largo maximo");
                if (!razones.isEmpty()) {
                    perdidas.add(c.getName() + "." + a.getName() + ": " + String.join(", ", razones)
                            + " no tiene equivalente en UML 2.5.1 estandar y no se exporta.");
                }
            }
        }
        perdidas.add("Las decisiones de mapeo OO-Datos (A-1..A-4: entidad debil, valor embebido, coleccion de"
                + " valores, clase asociativa) no son UML: quedan en el panel de ambiguedades, no en el XMI.");
        return List.copyOf(perdidas);
    }

    /**
     * Con que metaclase de UML 2.5.1 sale cada clase (T-05). Una enumeracion ES
     * una `uml:Enumeration` y un valor sin identidad, un `uml:DataType`: son
     * UML estandar, EA los muestra como lo que son y nuestro lector los vuelve a
     * leer como «enumeration» y «embeddable». Un valor sin atributos sale como
     * clase con su estereotipo: un DataType vacio se reimporta como un primitivo.
     */
    private static String metaclase(UmlClass c, List<UmlAttribute> propios) {
        String s = c.getStereotype() == null ? "" : c.getStereotype().toLowerCase(Locale.ROOT);
        // El estereotipo `interface` ES la metaclase (D-15): por eso tampoco es una perdida.
        if (Interfaces.es(c)) return "uml:Interface";
        if (s.equals("enumeration")) return "uml:Enumeration";
        if (s.equals("embeddable") && !propios.isEmpty()) return "uml:DataType";
        return "uml:Class";
    }

    private void escribirClase(Xml x, UmlClass c, List<UmlAttribute> propios, List<UmlMethod> propiosMetodos,
                                      List<UmlRelationship> generalizaciones, List<UmlRelationship> realizaciones,
                                      Map<String, String> idTipo) {
        String metaclase = metaclase(c, propios);
        // EA escribe isAbstract="true" en toda interfaz (corpus); en el estandar solo si la clase lo marca.
        boolean abstracta = c.isAbstract() || (ea && metaclase.equals("uml:Interface"));
        x.abrir("packagedElement", "xmi:type", metaclase, "xmi:id", id("c", c.getId()), "name", c.getName(),
                "visibility", ea ? "public" : null, "isAbstract", abstracta ? "true" : ea ? "false" : null);

        if (metaclase.equals("uml:Enumeration")) {
            for (UmlAttribute a : propios) {
                x.vacio("ownedLiteral", "xmi:type", "uml:EnumerationLiteral", "xmi:id", id("a", a.getId()),
                        "name", a.getName());
            }
            for (UmlMethod m : propiosMetodos) escribirOperacion(x, m, idTipo);
            x.cerrar("packagedElement");
            return;
        }

        for (UmlRelationship g : generalizaciones) {
            if (g.getSourceClassId().equals(c.getId())) {
                x.vacio("generalization", "xmi:type", "uml:Generalization", "xmi:id", id("r", g.getId()),
                        "general", id("c", g.getTargetClassId()));
            }
        }

        for (UmlRelationship r : realizaciones) {
            if (r.getSourceClassId().equals(c.getId())) {
                // client = quien realiza; supplier = contract = la interfaz (UML 2.5.1 §10.3.7).
                x.vacio("interfaceRealization", "xmi:type", "uml:InterfaceRealization", "xmi:id", id("r", r.getId()),
                        "client", id("c", c.getId()), "supplier", id("c", r.getTargetClassId()),
                        "contract", id("c", r.getTargetClassId()));
            }
        }

        for (UmlAttribute a : propios) {
            String ida = id("a", a.getId());
            x.abrir("ownedAttribute", "xmi:type", "uml:Property", "xmi:id", ida, "name", a.getName(),
                    "visibility", visibilidad(a.getVisibility()),
                    "type", ea ? null : idTipo.get(a.getDataType()),   // EA tipa con un hijo <type>, no con atributo
                    "isID", a.isPk() ? "true" : null,
                    "isDerived", a.isDerived() ? "true" : null);
            if (!ea) tipoOmg(x, a.getDataType());
            multiplicidad(x, ida, a.isRequired() || a.isPk() ? "1" : "0", a.isMultivalued() ? "*" : "1");
            // EA escribe el tipo DESPUES de lowerValue/upperValue y lo referencia por idref a un tipo definido
            // en la Extension (`primitivetypes`) o a una clase del modelo.
            if (ea && idTipo.containsKey(a.getDataType())) x.vacio("type", "xmi:idref", idTipo.get(a.getDataType()));
            if (a.getDefaultValue() != null && !a.getDefaultValue().isBlank()) {
                if (ea) {
                    // EA 2.1 guarda el valor inicial como LiteralString.
                    x.vacio("defaultValue", "xmi:type", "uml:LiteralString", "xmi:id", ida + "_DEF",
                            "value", a.getDefaultValue());
                } else {
                    // OpaqueExpression y no un Literal*: el valor es texto libre del
                    // usuario ("0", "now()", "'activo'"), no se adivina su tipo.
                    x.abrir("defaultValue", "xmi:type", "uml:OpaqueExpression", "xmi:id", ida + "-def");
                    x.elemento("body", a.getDefaultValue());
                    x.cerrar("defaultValue");
                }
            }
            x.cerrar("ownedAttribute");
        }

        for (UmlMethod m : propiosMetodos) {
            escribirOperacion(x, m, idTipo);
        }

        x.cerrar("packagedElement");
    }

    /**
     * REGLA 6: el metodo entero, con su firma. Sin parametro de retorno
     * cuando es "void": asi el lector, que no encuentra ninguno, vuelve a
     * completar "void" el mismo default con el que se escribio.
     */
    private void escribirOperacion(Xml x, UmlMethod m, Map<String, String> idTipo) {
        String idm = id("m", m.getId());
        x.abrir("ownedOperation", "xmi:type", "uml:Operation", "xmi:id", idm, "name", m.getName(),
                "visibility", visibilidad(m.getVisibility()));
        int i = 0;
        for (JsonNode p : parametros(m)) {
            String nombre = p.path("name").asText(null);
            if (nombre == null || nombre.isBlank()) continue;
            parametro(x, ea ? idm + "_P" + (i++) : "mp" + m.getId() + "-" + (i++), nombre, "in", p.path("type").asText("string"), idTipo);
        }
        if (m.getReturnType() != null && !"void".equalsIgnoreCase(m.getReturnType())) {
            // EA nombra "return" al parametro de retorno; sin nombre lo importa como `DuplicateParam_1`.
            parametro(x, ea ? idm + "_RET" : "mr" + m.getId(), ea ? "return" : null, "return", m.getReturnType(), idTipo);
        }
        x.cerrar("ownedOperation");
    }

    private void parametro(Xml x, String id, String nombre, String direccion, String tipo,
                                  Map<String, String> idTipo) {
        x.abrir("ownedParameter", "xmi:type", "uml:Parameter", "xmi:id", id, "name", nombre, "direction", direccion,
                "type", idTipo.get(tipo));
        if (!ea) tipoOmg(x, tipo);
        x.cerrar("ownedParameter");
    }

    /**
     * Perfil EA: el id de cada tipo usado. Si el nombre es el de una clase del
     * modelo (una enumeracion, una entidad), se refiere a esa clase; si no, a un
     * `uml:PrimitiveType` de `EA_Java_Types_Package` con el id `EAJava_<nombre>`,
     * que es como EA define y referencia los suyos. Antes se referenciaba
     * `EAnone_<nombre>` SIN definirlo y EA descartaba todos los tipos.
     */
    private static Map<String, String> tiposEa(List<UmlClass> clases, List<UmlAttribute> atributos,
                                               List<UmlMethod> metodos) {
        Map<String, UUID> clasePorNombre = new HashMap<>();
        for (UmlClass c : clases) clasePorNombre.putIfAbsent(c.getName(), c.getId());
        Map<String, String> idTipo = new LinkedHashMap<>();
        List<String> usados = new ArrayList<>();
        for (UmlAttribute a : atributos) usados.add(a.getDataType());
        for (UmlMethod m : metodos) {
            if (m.getReturnType() != null && !"void".equalsIgnoreCase(m.getReturnType())) usados.add(m.getReturnType());
            for (JsonNode p : parametros(m)) usados.add(p.path("type").asText("string"));
        }
        for (String t : usados) {
            if (t == null || t.isBlank()) continue;
            UUID clase = clasePorNombre.get(t);
            idTipo.putIfAbsent(t, clase != null ? "EAID_" + guid(clase) : "EAJava_" + t.trim().replace(' ', '_'));
        }
        return idTipo;
    }

    /**
     * El tipo, cuando es de la biblioteca de la OMG, va como elemento hijo con
     * `href` (asi serializa XMI una referencia a otro archivo); si no, ya quedo
     * como atributo `type` apuntando al `uml:PrimitiveType` local.
     */
    private void tipoOmg(Xml x, String tipo) {
        if (ea) return;   // EA: ver tiposEa y el tipo de ownedAttribute; los parametros llevan el atributo `type`
        String omg = tipo == null ? null : PRIMITIVOS_OMG.get(tipo.toLowerCase(Locale.ROOT));
        if (omg != null) x.vacio("type", "xmi:type", "uml:PrimitiveType", "href", NS_UML + "/PrimitiveTypes.xmi#" + omg);
    }

    private static String visibilidad(Visibility v) {
        return v == null ? null : v.name().toLowerCase(Locale.ROOT);
    }

    private static List<JsonNode> parametros(UmlMethod m) {
        try {
            JsonNode raiz = MAPPER.readTree(m.getParams() == null ? "[]" : m.getParams());
            List<JsonNode> r = new ArrayList<>();
            raiz.forEach(r::add);
            return r;
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * Dos `ownedEnd` DENTRO de la Association (no en la clase origen): el
     * primero tipado por el origen, el segundo por el destino, y
     * `navigableOwnedEnd` apuntando al segundo para que la direccion no
     * dependa de adivinar por ids o por navegabilidad implicita. `memberEnd`
     * se escribe aunque repita a los `ownedEnd`: en UML 2.5.1 no es derivado
     * y es ordenado, y EA/Papyrus lo usan para armar la relacion.
     */
    private void escribirAsociacion(Xml x, UmlRelationship r) {
        String idr = id("r", r.getId());
        // EA nombra los extremos EAID_src.../EAID_dst..., y LectorXmi los
        // reconoce por eso: en ese perfil el sentido queda fijo dos veces.
        String origen = ea ? "EAID_src" + guid(r.getId()).substring(3) : idr + "-o";
        String destino = ea ? "EAID_dst" + guid(r.getId()).substring(3) : idr + "-d";
        x.abrir("packagedElement", "xmi:type", "uml:Association", "xmi:id", idr, "name", r.getName(),
                "visibility", ea ? "public" : null, "memberEnd", origen + " " + destino, "navigableOwnedEnd", destino);

        x.abrir("ownedEnd", "xmi:type", "uml:Property", "xmi:id", origen, "name", r.getSourceRole(),
                "type", id("c", r.getSourceClassId()), "association", idr);
        multiplicidadDe(x, origen, r.getSourceMultiplicity());
        x.cerrar("ownedEnd");

        boolean parte = r.getKind() == RelationKind.COMPOSITION || r.getKind() == RelationKind.AGGREGATION;
        x.abrir("ownedEnd", "xmi:type", "uml:Property", "xmi:id", destino, "name", r.getTargetRole(),
                "type", id("c", r.getTargetClassId()), "association", idr,
                "aggregation", !parte ? null : r.getKind() == RelationKind.COMPOSITION ? "composite" : "shared");
        multiplicidadDe(x, destino, r.getTargetMultiplicity());
        x.cerrar("ownedEnd");

        x.cerrar("packagedElement");
    }

    private void multiplicidadDe(Xml x, String id, String mult) {
        String[] rango = rango(mult);
        multiplicidad(x, id, rango[0], rango[1]);
    }

    /** "0..*" -> [0, *]; "1" -> [1, 1]; null (no se declaro) -> [0, *], la mas permisiva. */
    private static String[] rango(String mult) {
        if (mult == null || mult.isBlank()) return new String[]{"0", "*"};
        String t = mult.trim();
        if (t.contains("..")) return t.split("\\.\\.", 2);
        return new String[]{t, t};
    }

    private void multiplicidad(Xml x, String id, String bajo, String alto) {
        // EA no asume el 0 por defecto de UML: sin value, deja la multiplicidad en blanco.
        x.vacio("lowerValue", "xmi:type", "uml:LiteralInteger", "xmi:id", id + (ea ? "_LO" : "-lo"),
                "value", bajo.equals("0") && !ea ? null : bajo);
        x.vacio("upperValue", "xmi:type", "uml:LiteralUnlimitedNatural", "xmi:id", id + (ea ? "_UP" : "-up"),
                "value", alto);
    }

    /**
     * Un `uml:PrimitiveType` local por cada tipo usado (atributos y firmas de
     * metodo) que NO esta en la biblioteca de la OMG (`date`, `decimal`,
     * `text`...). Los que si estan van por `href` al URI oficial, no a un
     * `pathmap://` de Eclipse: ese URI lo resuelve cualquier herramienta.
     */
    private static Map<String, String> tiposPrimitivos(List<UmlAttribute> atributos, List<UmlMethod> metodos) {
        Map<String, String> idTipo = new LinkedHashMap<>();
        for (UmlAttribute a : atributos) local(idTipo, a.getDataType());
        for (UmlMethod m : metodos) {
            if (m.getReturnType() != null && !"void".equalsIgnoreCase(m.getReturnType())) local(idTipo, m.getReturnType());
            for (JsonNode p : parametros(m)) local(idTipo, p.path("type").asText("string"));
        }
        return idTipo;
    }

    private static void local(Map<String, String> idTipo, String tipo) {
        if (tipo == null || PRIMITIVOS_OMG.containsKey(tipo.toLowerCase(Locale.ROOT))) return;
        idTipo.putIfAbsent(tipo, "t-" + tipo);
    }

    /** "c" + uuid en el estandar; `EAID_` + el GUID con guiones bajos en EA, que es la forma que EA espera. */
    private String id(String prefijo, UUID uuid) {
        return ea ? "EAID_" + guid(uuid) : prefijo + uuid;
    }

    private static String guid(UUID uuid) {
        return uuid.toString().toUpperCase(Locale.ROOT).replace('-', '_');
    }

    // ===================================================== EXTENSION DE EA

    /** Ancho minimo de una clase en EA: por debajo corta los nombres. */
    private static final int ANCHO_MINIMO = 110;

    /** Los bloques de configuracion de un diagrama, copiados tal cual de un export de EA 16.1 (build 1625). */
    private static final String SW = "orientation=0;width=0;inbar=false;names=false;color=-1;bold=false;fcol=0;tcol=-1;"
            + "ofCol=-1;ufCol=-1;hl=1;ufh=0;hh=0;cls=0;bw=0;hli=0;bro=0;SwimlaneFont=lfh:-13,lfw:0,lfi:0,lfu:0,lfs:0,"
            + "lfface:Calibri,lfe:0,lfo:0,lfchar:1,lfop:0,lfcp:0,lfq:0,lfpf=0,lfWidth=0;";
    private static final String DIAGRAMA_STYLE1 = "ShowPrivate=1;ShowProtected=1;ShowPublic=1;HideRelationships=0;"
            + "Locked=0;Border=1;HighlightForeign=1;PackageContents=1;SequenceNotes=0;ScalePrintImage=0;PPgs.cx=0;"
            + "PPgs.cy=0;DocSize.cx=850;DocSize.cy=1098;ShowDetails=0;Orientation=P;Zoom=100;ShowTags=0;OpParams=1;"
            + "VisibleAttributeDetail=0;ShowOpRetType=1;ShowIcons=1;CollabNums=0;HideProps=0;ShowReqs=0;ShowCons=0;"
            + "PaperSize=1;HideParents=0;UseAlias=0;HideAtts=0;HideOps=0;HideStereo=0;HideElemStereo=0;ShowTests=0;"
            + "ShowMaint=0;ConnectorNotation=UML 2.1;ExplicitNavigability=0;ShowShape=1;AllDockable=0;"
            + "AdvancedElementProps=1;AdvancedFeatureProps=1;AdvancedConnectorProps=1;m_bElementClassifier=1;SPT=1;"
            + "ShowNotes=0;SuppressBrackets=0;SuppConnectorLabels=0;PrintPageHeadFoot=0;ShowAsList=0;";
    private static final String DIAGRAMA_STYLE2 = "ExcludeRTF=0;DocAll=0;HideQuals=0;AttPkg=1;ShowTests=0;ShowMaint=0;"
            + "SuppressFOC=1;MatrixActive=0;SwimlanesActive=1;KanbanActive=0;MatrixLineWidth=1;MatrixLineClr=0;"
            + "MatrixLocked=0;TConnectorNotation=UML 2.1;TExplicitNavigability=0;AdvancedElementProps=1;"
            + "AdvancedFeatureProps=1;AdvancedConnectorProps=1;m_bElementClassifier=1;SPT=1;MDGDgm=;STBLDgm=;"
            + "ShowNotes=0;VisibleAttributeDetail=0;ShowOpRetType=1;SuppressBrackets=0;SuppConnectorLabels=0;"
            + "PrintPageHeadFoot=0;ShowAsList=0;SuppressedCompartments=;Theme=:119;SaveTag=FB9A30B1;";
    private static final String DIAGRAMA_SWIMLANES = "locked=false;" + SW;
    private static final String DIAGRAMA_MATRIZ = "locked=false;matrixactive=false;swimlanesactive=true;"
            + "kanbanactive=false;width=1;clrLine=0;";
    private static final String DIAGRAMA_PERSISTENTE = "DGS=On=0:CNT=8:W=120:H=40:SG=0:SGH=0:AEB=0:;AR=0;DCL=0;"
            + SW + ";" + SW + ";" + SW + ";" + SW + ";Swimlanes=locked=false;" + SW + ";";
    private static final String ESTILO_CAJA = "NSL=0;BCol=-1;BFol=-1;LCol=-1;LWth=-1;fontsz=0;bold=0;black=0;"
            + "italic=0;ul=0;charset=0;pitch=0;";

    /**
     * La parte que UML no tiene y EA si, en el orden y con los nodos con que EA 16.1 la escribe de si mismo:
     * `elements` (el paquete y cada clase), `connectors` (TODAS las relaciones), `primitivetypes` (los tipos
     * que referencian los atributos y parametros), `profiles` y `diagrams`. La forma sale de un export real de
     * EA (docs/MesaDeTrabajo/pruebas-ea/tanda-1-linea-base/exportado-desde-EA), no de memoria.
     *
     * Corrige dos cosas que EA 16.1 real rechazo con el archivo anterior: los tipos (se referenciaban
     * `EAnone_x` sin definirlos) y el diagrama (faltaban `connectors`, `primitivetypes`, `profiles`, los nodos de
     * configuracion del diagrama y la entrada de cada conector en el diagrama).
     */
    private void extensionEa(Xml x, String paquete, List<UmlClass> clases,
                             Map<UUID, List<UmlAttribute>> atrsPorClase, Map<UUID, List<UmlMethod>> metsPorClase,
                             List<DiagramLayout> disposicion, List<UmlRelationship> relaciones,
                             Map<String, String> idTipo) {
        String ahora = java.time.LocalDateTime.now(reloj).withNano(0).toString().replace('T', ' ');
        Map<UUID, UmlClass> porId = new HashMap<>();
        Map<UUID, Integer> localId = new HashMap<>();
        int n = 1;   // el paquete es el 1
        for (UmlClass c : clases) {
            porId.put(c.getId(), c);
            localId.put(c.getId(), ++n);
        }
        // Solo los conectores cuyos dos extremos estan en el diagrama.
        List<UmlRelationship> conectores = relaciones.stream()
                .filter(r -> porId.containsKey(r.getSourceClassId()) && porId.containsKey(r.getTargetClassId()))
                .toList();

        x.abrir("xmi:Extension", "extender", "Enterprise Architect", "extenderID", "6.5");

        x.abrir("elements");
        x.abrir("element", "xmi:idref", paquete, "xmi:type", "uml:Package", "name", "ColabModel", "scope", "public");
        x.vacio("model", "package2", "EAID_" + paquete.substring("EAPK_".length()), "package", "EAPK_MODELO",
                "tpos", "0", "ea_localid", "1", "ea_eleType", "package");
        x.vacio("properties", "isSpecification", "false", "sType", "Package", "nType", "0", "scope", "public");
        x.vacio("project", "author", "ColabModel", "version", "1.0", "phase", "1.0", "created", ahora,
                "modified", ahora, "complexity", "1", "status", "Proposed");
        x.vacio("code", "gentype", "Java");
        x.vacio("style", "appearance", "BackColor=-1;BorderColor=-1;BorderWidth=-1;FontColor=-1;VSwimLanes=1;"
                + "HSwimLanes=1;BorderStyle=0;");
        x.vacio("tags");
        x.vacio("xrefs");
        x.vacio("extendedProperties", "tagged", "0", "package_name", "EA_Model");
        x.vacio("packageproperties", "version", "1.0");
        x.vacio("paths");
        x.vacio("times", "created", ahora, "modified", ahora);
        x.vacio("flags", "iscontrolled", "0", "isprotected", "0", "usedtd", "0", "logxml", "0");
        x.cerrar("element");

        for (UmlClass c : clases) {
            List<UmlAttribute> atrs = atrsPorClase.getOrDefault(c.getId(), List.of());
            String metaclase = metaclase(c, atrs);
            x.abrir("element", "xmi:idref", id("c", c.getId()), "xmi:type", metaclase, "name", c.getName(),
                    "scope", "public");
            x.vacio("model", "package", paquete, "tpos", "0", "ea_localid", String.valueOf(localId.get(c.getId())),
                    "ea_eleType", "element");
            x.vacio("properties", "isSpecification", "false", "sType", metaclase.substring("uml:".length()),
                    "nType", "0", "scope", "public",
                    "isAbstract", c.isAbstract() || metaclase.equals("uml:Interface") ? "true" : "false",
                    // En una uml:Interface el estereotipo ya ES la metaclase: repetirlo mostraria «interface» dos veces.
                    "stereotype", metaclase.equals("uml:Interface") ? null : c.getStereotype());
            x.vacio("project", "author", "ColabModel", "version", "1.0", "phase", "1.0", "created", ahora,
                    "modified", ahora, "complexity", "1", "status", "Proposed");
            x.vacio("code", "gentype", "Java");
            x.vacio("extendedProperties", "tagged", "0", "package_name", "ColabModel");
            if (!atrs.isEmpty()) {
                x.abrir("attributes");
                for (UmlAttribute a : atrs) {
                    x.abrir("attribute", "xmi:idref", id("a", a.getId()), "name", a.getName(),
                            "scope", a.getVisibility() == null ? "Private" : capital(a.getVisibility().name()));
                    // `type` es el nombre del tipo, como lo muestra EA (en un export real: type="date").
                    x.vacio("properties", "type", a.getDataType(), "derived", a.isDerived() ? "1" : "0",
                            "collection", a.isMultivalued() ? "true" : "false", "changeability", "changeable");
                    x.vacio("bounds", "lower", a.isRequired() || a.isPk() ? "1" : "0",
                            "upper", a.isMultivalued() ? "*" : "1");
                    x.cerrar("attribute");
                }
                x.cerrar("attributes");
            }
            x.cerrar("element");
        }
        x.cerrar("elements");

        x.abrir("connectors");
        int idConector = 0;
        for (UmlRelationship r : conectores) {
            escribirConector(x, r, porId.get(r.getSourceClassId()), porId.get(r.getTargetClassId()),
                    localId.get(r.getSourceClassId()), localId.get(r.getTargetClassId()), ++idConector);
        }
        x.cerrar("connectors");

        // Los tipos que no son clases del modelo: EA los define aca y los referencia por `EAJava_<nombre>`.
        List<String> primitivos = idTipo.values().stream().filter(v -> v.startsWith("EAJava_")).distinct().toList();
        Map<String, String> nombrePorId = new LinkedHashMap<>();
        idTipo.forEach((nombre, v) -> {
            if (v.startsWith("EAJava_")) nombrePorId.putIfAbsent(v, nombre);
        });
        x.abrir("primitivetypes");
        x.abrir("packagedElement", "xmi:type", "uml:Package", "xmi:id", "EAPrimitiveTypesPackage",
                "name", "EA_PrimitiveTypes_Package", "visibility", "public");
        if (!primitivos.isEmpty()) {
            x.abrir("packagedElement", "xmi:type", "uml:Package", "xmi:id", "EAJavaTypesPackage",
                    "name", "EA_Java_Types_Package", "visibility", "public");
            for (String v : primitivos) {
                x.vacio("packagedElement", "xmi:type", "uml:PrimitiveType", "xmi:id", v,
                        "name", nombrePorId.get(v), "visibility", "public");
            }
            x.cerrar("packagedElement");
        }
        x.cerrar("packagedElement");
        x.cerrar("primitivetypes");
        x.vacio("profiles");

        x.abrir("diagrams");
        x.abrir("diagram", "xmi:id", "EAID_" + guid(UUID.nameUUIDFromBytes(("diagrama" + paquete)
                .getBytes(StandardCharsets.UTF_8))));
        x.vacio("model", "package", paquete, "localID", "1", "owner", paquete);
        x.vacio("properties", "name", "Diagrama de clases", "type", "Logical");
        x.vacio("project", "author", "ColabModel", "version", "1.0", "created", ahora, "modified", ahora);
        x.vacio("style1", "value", DIAGRAMA_STYLE1);
        x.vacio("style2", "value", DIAGRAMA_STYLE2);
        x.vacio("swimlanes", "value", DIAGRAMA_SWIMLANES);
        x.vacio("matrixitems", "value", DIAGRAMA_MATRIZ);
        x.vacio("extendedProperties");
        x.vacio("persistentstyle", "value", DIAGRAMA_PERSISTENTE);
        x.vacio("xrefs");
        x.abrir("elements");
        Map<UUID, DiagramLayout> donde = new HashMap<>();
        for (DiagramLayout l : disposicion) donde.put(l.getElementId(), l);
        List<DiagramLayout> dibujadas = clases.stream().map(c -> donde.get(c.getId())).filter(Objects::nonNull).toList();
        double minX = dibujadas.stream().mapToDouble(DiagramLayout::getX).min().orElse(0);
        double minY = dibujadas.stream().mapToDouble(DiagramLayout::getY).min().orElse(0);
        double piso = dibujadas.stream().mapToDouble(l -> l.getY() + l.getHeight()).max().orElse(minY) - minY;
        int seq = 1;
        int sinLugar = 0;
        for (UmlClass c : clases) {
            DiagramLayout l = donde.get(c.getId());
            double izq;
            double arriba;
            double ancho;
            if (l != null) {
                // Se corre todo para que la clase mas arriba a la izquierda
                // quede en (40, 40): EA no dibuja en coordenadas negativas.
                izq = l.getX() - minX + 40;
                arriba = l.getY() - minY + 40;
                ancho = Math.max(ANCHO_MINIMO, l.getWidth());
            } else {
                // Sin layout (no deberia pasar): en grilla, debajo de lo dibujado.
                izq = 40 + (sinLugar % 4) * 240;
                arriba = 40 + (dibujadas.isEmpty() ? 0 : piso + 120) + (sinLugar / 4) * 220;
                ancho = 170;
                sinLugar++;
            }
            // El alto que se ve en ColabModel sale de lo que tiene la clase, no
            // del layout guardado (ver altoDeCaja en Canvas.tsx): se calcula
            // igual, para que EA no la recorte.
            int filas = atrsPorClase.getOrDefault(c.getId(), List.of()).size();
            int ops = metsPorClase.getOrDefault(c.getId(), List.of()).size();
            double alto = 30 + filas * 18 + 8 + (ops > 0 ? 6 + ops * 18 : 0) + (c.getStereotype() != null ? 14 : 0);
            x.vacio("element", "geometry", "Left=" + entero(izq) + ";Top=" + entero(arriba) + ";Right="
                            + entero(izq + ancho) + ";Bottom=" + entero(arriba + alto) + ";",
                    "subject", id("c", c.getId()), "seqno", String.valueOf(seq++),
                    "style", "DUID=" + duid(c.getId()) + ";" + ESTILO_CAJA);
        }
        // EA tambien lista cada conector en el diagrama, con los DUID de sus dos cajas (EOID = destino, SOID = origen).
        for (UmlRelationship r : conectores) {
            x.vacio("element", "geometry", "EDGE=2;$LLB=;LLT=;LMT=;LMB=;LRT=;LRB=;IRHS=;ILHS=;Path=;",
                    "subject", id("r", r.getId()),
                    "style", "Mode=3;EOID=" + duid(r.getTargetClassId()) + ";SOID=" + duid(r.getSourceClassId())
                            + ";Color=-1;LWidth=0;Hidden=0;");
        }
        x.cerrar("elements");
        x.cerrar("diagram");
        x.cerrar("diagrams");

        x.cerrar("xmi:Extension");
    }

    /** Los 8 primeros digitos hexadecimales del id: el `DUID` que EA le da a la caja de un elemento en un diagrama. */
    private static String duid(UUID id) {
        return id.toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
    }

    /**
     * El conector de la Extension, con la forma de los de un export real de EA. Asociacion, agregacion y
     * composicion salen todas como `ea_type="Association"`, con el rombo en el atributo `aggregation` del extremo
     * del todo (asi las guardo EA al importar nuestro archivo y asi las dibujo).
     */
    private void escribirConector(Xml x, UmlRelationship r, UmlClass origen, UmlClass destino, int localOrigen,
                                  int localDestino, int localConector) {
        RelationKind k = r.getKind();
        boolean asociativa = k == RelationKind.ASSOCIATION || k == RelationKind.AGGREGATION
                || k == RelationKind.COMPOSITION;
        String tipoEa = switch (k) {
            case GENERALIZATION -> "Generalization";
            case DEPENDENCY -> "Dependency";
            case REALIZATION -> "Realisation";   // EA la escribe con «s»
            default -> "Association";
        };
        x.abrir("connector", "xmi:idref", id("r", r.getId()), "name", asociativa ? r.getName() : null);
        extremoDeConector(x, "source", origen, localOrigen, asociativa, r.getSourceRole(), r.getSourceMultiplicity(),
                "none", false);
        extremoDeConector(x, "target", destino, localDestino, asociativa, r.getTargetRole(), r.getTargetMultiplicity(),
                k == RelationKind.COMPOSITION ? "composite" : k == RelationKind.AGGREGATION ? "shared" : "none", true);
        x.vacio("model", "ea_localid", String.valueOf(localConector));
        x.vacio("properties", "ea_type", tipoEa, "direction", "Source -> Destination");
        if (asociativa) {
            x.vacio("modifiers", "isRoot", "false", "isLeaf", "false");
            x.vacio("parameterSubstitutions");
            x.vacio("documentation");
            x.vacio("appearance", "linemode", "3", "linecolor", "-1", "linewidth", "0", "seqno", "0",
                    "headStyle", "0", "lineStyle", "0");
            x.vacio("labels", "mt", r.getName());
            x.vacio("extendedProperties", "virtualInheritance", "0");
            x.vacio("style");
        }
        x.vacio("xrefs");
        x.vacio("tags");
        x.cerrar("connector");
    }

    private void extremoDeConector(Xml x, String lado, UmlClass c, int localId, boolean asociativa, String rol,
                                   String multiplicidad, String agregacion, boolean navegable) {
        x.abrir(lado, "xmi:idref", id("c", c.getId()));
        x.vacio("model", "ea_localid", String.valueOf(localId), "type",
                metaclase(c, List.of()).substring("uml:".length()), "name", c.getName());
        x.vacio("role", "name", asociativa && rol != null && !rol.isBlank() ? rol : null, "visibility", "Public",
                "targetScope", "instance");
        x.vacio("type", "multiplicity", asociativa && multiplicidad != null && !multiplicidad.isBlank()
                ? multiplicidad : null, "aggregation", agregacion, "containment", "Unspecified");
        if (asociativa) {
            x.vacio("constraints");
            x.vacio("modifiers", "isOrdered", "false", "changeable", "none", "isNavigable",
                    navegable ? "true" : "false");
            x.vacio("style", "value", "Union=0;Derived=0;AllowDuplicates=0;Owned=0;Navigable="
                    + (navegable ? "Navigable" : "Non-Navigable") + ";");
            x.vacio("documentation");
            x.vacio("xrefs");
            x.vacio("tags");
        }
        x.cerrar(lado);
    }

    private static String entero(double v) {
        return String.valueOf(Math.round(v));
    }

    private static String capital(String s) {
        return s.isEmpty() ? s : s.charAt(0) + s.substring(1).toLowerCase(Locale.ROOT);
    }

    private static <T> Map<UUID, List<T>> agrupar(List<T> elementos, java.util.function.Function<T, UUID> clave) {
        Map<UUID, List<T>> r = new LinkedHashMap<>();
        for (T e : elementos) r.computeIfAbsent(clave.apply(e), k -> new ArrayList<>()).add(e);
        return r;
    }

    /** XML minimo, sin dependencias: abrir/cerrar/vacio, con escape y sangria. */
    private static final class Xml {
        private final StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        private int nivel;

        Xml abrir(String nombre, String... atributos) {
            etiqueta(nombre, atributos, false);
            nivel++;
            return this;
        }

        Xml vacio(String nombre, String... atributos) {
            return etiqueta(nombre, atributos, true);
        }

        Xml cerrar(String nombre) {
            nivel--;
            sangria();
            sb.append("</").append(nombre).append(">\n");
            return this;
        }

        /** `<nombre>contenido</nombre>` en una linea: el texto es el valor, sin sangria adentro. */
        Xml elemento(String nombre, String contenido) {
            sangria();
            sb.append('<').append(nombre).append('>').append(escapar(contenido))
                    .append("</").append(nombre).append(">\n");
            return this;
        }

        Xml texto(String contenido) {
            sangria();
            sb.append(escapar(contenido)).append('\n');
            return this;
        }

        private Xml etiqueta(String nombre, String[] atributos, boolean vacio) {
            sangria();
            sb.append('<').append(nombre);
            for (int i = 0; i + 1 < atributos.length; i += 2) {
                if (atributos[i + 1] == null) continue;
                sb.append(' ').append(atributos[i]).append("=\"").append(escapar(atributos[i + 1])).append('"');
            }
            sb.append(vacio ? "/>\n" : ">\n");
            return this;
        }

        private void sangria() {
            sb.append("  ".repeat(Math.max(0, nivel)));
        }

        private static String escapar(String s) {
            return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
        }

        @Override
        public String toString() {
            return sb.toString();
        }
    }
}
