package com.uagrm.colabmodel.interop;

import com.uagrm.colabmodel.modelcore.mapping.DerivadorLogico;
import com.uagrm.colabmodel.modelcore.mapping.ModeloCanonico;
import com.uagrm.colabmodel.modelcore.mapping.ModeloLogico;
import com.uagrm.colabmodel.modelcore.metamodel.*;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Imita lo que escribe cada herramienta externa al exportar un modelo a XMI.
 *
 * No es nuestro exportador (la exportacion XMI es un recorte declarado): es la
 * forma de probar el lector contra dialectos que no escribimos nosotros sin
 * tener instaladas las herramientas. Cada metodo copia las costumbres de su
 * herramienta, incluidas las incomodas, porque son esas las que rompen a un
 * lector ingenuo:
 *
 *   ENTERPRISE_ARCHITECT  XMI 2.1, windows-1252, ids EAID_/EAPK_, extremos
 *                         EAID_src/EAID_dst, tipos EAnone_*, y la mitad de la
 *                         verdad en la xmi:Extension (conectores, tags, bounds).
 *   PAPYRUS               XMI 2.x de Eclipse UML2: multiplicidad [1] OMITIDA,
 *                         cota inferior 0 sin `value`, tipos por `href` a
 *                         pathmap://, extremo navegable como ownedAttribute,
 *                         estereotipos como aplicaciones de perfil.
 *   ARGOUML               XMI 1.2 / UML 1.4: `xmi.id` con dos puntos, comillas
 *                         simples, AssociationEnd con participant, estereotipos
 *                         y TaggedValue por referencia a su definicion.
 *
 * Y el modelo de datos de EA («table», «column», operaciones «PK»/«FK»/«unique»,
 * conectores «FK»), con los estereotipos en la Extension o como perfil.
 *
 * Lo que ninguna herramienta guarda de forma estandar se escribe como lo haria
 * un usuario de esa herramienta: `unique` como tag en EA, como estereotipo de
 * perfil en Papyrus, como TaggedValue en ArgoUML.
 */
final class HerramientasXmi {

    enum Herramienta { ENTERPRISE_ARCHITECT, PAPYRUS, ARGOUML }

    private HerramientasXmi() { }

    static byte[] exportar(Herramienta h, ModeloCanonico.Cargado m) {
        return switch (h) {
            case ENTERPRISE_ARCHITECT -> enterpriseArchitect(m).getBytes(Charset.forName("windows-1252"));
            case PAPYRUS -> papyrus(m).getBytes(StandardCharsets.UTF_8);
            case ARGOUML -> argoUml(m).getBytes(StandardCharsets.UTF_8);
        };
    }

    // ================================================================== XML

    /** Lo minimo para escribir XML legible sin depender de un DOM. */
    static final class Xml {
        private final StringBuilder sb = new StringBuilder();
        private final char comilla;
        private final String igual;
        private int nivel;

        Xml(String declaracion, char comilla, String igual) {
            this.comilla = comilla;
            this.igual = igual;
            sb.append(declaracion).append('\n');
        }

        Xml abrir(String nombre, String... atributos) {
            etiqueta(nombre, atributos, false);
            nivel++;
            return this;
        }

        Xml vacio(String nombre, String... atributos) {
            return etiqueta(nombre, atributos, true);
        }

        Xml conTexto(String nombre, String texto) {
            sangria();
            sb.append('<').append(nombre).append('>').append(escapar(texto)).append("</").append(nombre).append(">\n");
            return this;
        }

        Xml cerrar(String nombre) {
            nivel--;
            sangria();
            sb.append("</").append(nombre).append(">\n");
            return this;
        }

        Xml crudo(String texto) {
            sb.append(texto).append('\n');
            return this;
        }

        private Xml etiqueta(String nombre, String[] atributos, boolean vacio) {
            sangria();
            sb.append('<').append(nombre);
            for (int i = 0; i + 1 < atributos.length; i += 2) {
                if (atributos[i + 1] == null) continue;
                sb.append(' ').append(atributos[i]).append(igual).append(comilla).append(escapar(atributos[i + 1]))
                        .append(comilla);
            }
            sb.append(vacio ? "/>\n" : ">\n");
            return this;
        }

        private void sangria() {
            sb.append("  ".repeat(Math.max(0, nivel)));
        }

        static String escapar(String s) {
            return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
                    .replace("'", "&apos;");
        }

        @Override
        public String toString() {
            return sb.toString();
        }
    }

    // ============================================================ COMUNES

    private record Mult(String bajo, String alto) {
        /** "0..*" -> (0, *); "1" -> (1, 1). Null queda null. */
        static Mult de(String texto) {
            if (texto == null || texto.isBlank()) return null;
            String t = texto.trim();
            if (t.contains("..")) {
                String[] p = t.split("\\.\\.");
                return new Mult(p[0], p[1]);
            }
            return t.equals("*") ? new Mult("0", "*") : new Mult(t, t);
        }

        String unaLinea() {
            return bajo.equals(alto) ? alto : bajo + ".." + alto;
        }
    }

    private static Map<UUID, List<UmlAttribute>> atributosPorClase(ModeloCanonico.Cargado m) {
        return m.atributos().stream().sorted(Comparator.comparingInt(UmlAttribute::getOrdinal))
                .collect(Collectors.groupingBy(UmlAttribute::getClassId, LinkedHashMap::new, Collectors.toList()));
    }

    private static <T> Map<UUID, String> ids(List<T> elementos, Function<T, UUID> id, String prefijo) {
        Map<UUID, String> r = new LinkedHashMap<>();
        int i = 1;
        for (T e : elementos) r.put(id.apply(e), prefijo + i++);
        return r;
    }

    private static List<UmlRelationship> asociaciones(ModeloCanonico.Cargado m) {
        return m.relaciones().stream().filter(r -> r.getKind() != RelationKind.GENERALIZATION).toList();
    }

    private static List<UmlRelationship> herencias(ModeloCanonico.Cargado m) {
        return m.relaciones().stream().filter(r -> r.getKind() == RelationKind.GENERALIZATION).toList();
    }

    private static String agregacionUml(RelationKind k) {
        return switch (k) {
            case COMPOSITION -> "composite";
            case AGGREGATION -> "shared";
            default -> "none";
        };
    }

    // ================================================= ENTERPRISE ARCHITECT

    static String enterpriseArchitect(ModeloCanonico.Cargado m) {
        Map<UUID, String> idClase = ids(m.clases(), UmlClass::getId, "EAID_3F2A0C1B_7D4E_4A21_9C0B_00000000C");
        Map<UUID, String> idAtr = ids(m.atributos(), UmlAttribute::getId, "EAID_3F2A0C1B_7D4E_4A21_9C0B_00000000A");
        Map<UUID, String> idRel = ids(m.relaciones(), UmlRelationship::getId, "EAID_9B11D2E4_0C3A_4F5E_8D77_0000000R");
        Map<UUID, List<UmlAttribute>> atrs = atributosPorClase(m);
        Map<UUID, UmlClass> clasePorId = m.clases().stream().collect(Collectors.toMap(UmlClass::getId, c -> c));

        Xml x = new Xml("<?xml version=\"1.0\" encoding=\"windows-1252\"?>", '"', "=");
        x.abrir("xmi:XMI", "xmi:version", "2.1", "xmlns:uml", "http://schema.omg.org/spec/UML/2.1",
                "xmlns:xmi", "http://schema.omg.org/spec/XMI/2.1");
        x.vacio("xmi:Documentation", "exporter", "Enterprise Architect", "exporterVersion", "6.5", "exporterID", "1628");
        x.abrir("uml:Model", "xmi:type", "uml:Model", "name", "EA_Model", "visibility", "public");
        x.abrir("packagedElement", "xmi:type", "uml:Package", "xmi:id", "EAPK_6A0F1E2D_33B1_4C8A_A1B0_2F5D6C7E8F90",
                "name", "Modelo del docente", "visibility", "public");

        int literal = 1;
        for (UmlClass c : m.clases()) {
            x.abrir("packagedElement", "xmi:type", "uml:Class", "xmi:id", idClase.get(c.getId()), "name", c.getName(),
                    "visibility", "public", "isAbstract", c.isAbstract() ? "true" : null);
            for (UmlRelationship g : herencias(m)) {
                if (g.getSourceClassId().equals(c.getId())) {
                    x.vacio("generalization", "xmi:type", "uml:Generalization", "xmi:id", idRel.get(g.getId()),
                            "general", idClase.get(g.getTargetClassId()));
                }
            }
            for (UmlAttribute a : atrs.getOrDefault(c.getId(), List.of())) {
                x.abrir("ownedAttribute", "xmi:type", "uml:Property", "xmi:id", idAtr.get(a.getId()), "name", a.getName(),
                        "visibility", "private", "isStatic", "false", "isReadOnly", "false", "isDerived",
                        a.isDerived() ? "true" : "false", "isOrdered", "false", "isUnique", "true", "isDerivedUnion",
                        "false", "isID", a.isPk() ? "true" : null);
                x.vacio("lowerValue", "xmi:type", "uml:LiteralInteger", "xmi:id", "EAID_LI" + literal++,
                        "value", a.isRequired() || a.isPk() ? "1" : "0");
                x.vacio("upperValue", "xmi:type", "uml:LiteralUnlimitedNatural", "xmi:id", "EAID_LI" + literal++,
                        "value", a.isMultivalued() ? "*" : "1");
                x.vacio("type", "xmi:idref", "EAnone_" + a.getDataType());
                x.cerrar("ownedAttribute");
            }
            x.cerrar("packagedElement");
        }

        for (UmlRelationship r : asociaciones(m)) {
            String id = idRel.get(r.getId());
            String sufijo = id.substring("EAID_".length() + 3);
            String src = "EAID_src" + sufijo;
            String dst = "EAID_dst" + sufijo;
            x.abrir("packagedElement", "xmi:type", "uml:Association", "xmi:id", id, "name", r.getName(), "visibility", "public");
            // EA escribe primero el extremo destino.
            x.vacio("memberEnd", "xmi:idref", dst);
            x.vacio("memberEnd", "xmi:idref", src);
            SentidoEa s = SentidoEa.de(r);
            extremoEa(x, dst, s.rolDst(), id, idClase.get(s.dst()), s.multDst(), s.rombo(), literal);
            literal += 2;
            extremoEa(x, src, s.rolSrc(), id, idClase.get(s.src()), s.multSrc(), "none", literal);
            literal += 2;
            x.cerrar("packagedElement");
        }
        x.cerrar("packagedElement");
        x.cerrar("uml:Model");

        // ---------------------------------------------------- la Extension
        x.abrir("xmi:Extension", "extender", "Enterprise Architect", "extenderID", "6.5");
        x.abrir("elements");
        x.abrir("element", "xmi:idref", "EAPK_6A0F1E2D_33B1_4C8A_A1B0_2F5D6C7E8F90", "xmi:type", "uml:Package",
                "name", "Modelo del docente", "scope", "public");
        x.vacio("model", "package2", "EAID_6A0F1E2D_33B1_4C8A_A1B0_2F5D6C7E8F90", "package", "EAPK_ROOT", "tpos", "0",
                "ea_localid", "1", "ea_eleType", "package");
        x.vacio("properties", "isSpecification", "false", "sType", "Package", "nType", "0", "scope", "public");
        x.cerrar("element");
        int local = 2;
        for (UmlClass c : m.clases()) {
            x.abrir("element", "xmi:idref", idClase.get(c.getId()), "xmi:type", "uml:Class", "name", c.getName(),
                    "scope", "public");
            x.vacio("model", "package", "EAPK_6A0F1E2D_33B1_4C8A_A1B0_2F5D6C7E8F90", "tpos", "0",
                    "ea_localid", String.valueOf(local++), "ea_eleType", "element");
            x.vacio("properties", "isSpecification", "false", "sType", "Class", "nType", "0", "scope", "public",
                    "isRoot", "false", "isLeaf", "false", "isAbstract", String.valueOf(c.isAbstract()), "isActive", "false");
            x.vacio("project", "author", "docente", "version", "1.0", "phase", "1.0", "created", "2026-09-01 10:00:00",
                    "modified", "2026-09-01 10:00:00", "complexity", "1", "status", "Proposed");
            x.vacio("code", "gentype", "Java");
            x.vacio("style", "appearance", "BackColor=-1;BorderColor=-1;BorderWidth=-1;FontColor=-1;VSwimLanes=1;");
            x.vacio("tags");
            x.vacio("xrefs");
            x.vacio("extendedProperties", "tagged", "0", "package_name", "Modelo del docente");
            x.abrir("attributes");
            for (UmlAttribute a : atrs.getOrDefault(c.getId(), List.of())) {
                x.abrir("attribute", "xmi:idref", idAtr.get(a.getId()), "name", a.getName(), "scope", "Private");
                x.vacio("initial");
                x.vacio("documentation");
                x.vacio("model", "ea_localid", String.valueOf(local++), "ea_guid", "{" + a.getId() + "}");
                x.vacio("properties", "type", a.getDataType(), "derived", a.isDerived() ? "1" : "0",
                        "collection", String.valueOf(a.isMultivalued()), "duplicates", "0", "changeability", "changeable");
                x.vacio("coords", "ordered", "0", "scale", "0");
                x.vacio("containment", "containment", "Not Specified", "position", "0");
                x.vacio("stereotype");
                x.vacio("bounds", "lower", a.isRequired() || a.isPk() ? "1" : "0", "upper", a.isMultivalued() ? "*" : "1");
                x.vacio("options");
                x.vacio("style");
                x.vacio("styleex", "value", "volatile=0;");
                if (a.isUnique()) {
                    x.abrir("tags");
                    x.vacio("tag", "xmi:id", "EAID_TG" + local++, "name", "unique", "value", "true",
                            "modelElement", idAtr.get(a.getId()));
                    x.cerrar("tags");
                } else {
                    x.vacio("tags");
                }
                x.vacio("xrefs");
                x.cerrar("attribute");
            }
            x.cerrar("attributes");
            x.cerrar("element");
        }
        x.cerrar("elements");

        x.abrir("connectors");
        for (UmlRelationship r : m.relaciones()) {
            boolean herencia = r.getKind() == RelationKind.GENERALIZATION;
            x.abrir("connector", "xmi:idref", idRel.get(r.getId()));
            SentidoEa s = SentidoEa.de(r);
            conectorEa(x, "source", idClase.get(s.src()), clasePorId.get(s.src()).getName(),
                    herencia ? null : s.rolSrc(), herencia ? null : s.multSrc(), "none");
            conectorEa(x, "target", idClase.get(s.dst()), clasePorId.get(s.dst()).getName(),
                    herencia ? null : s.rolDst(), herencia ? null : s.multDst(), herencia ? "none" : s.rombo());
            x.vacio("model", "ea_localid", String.valueOf(local++));
            x.vacio("properties", "ea_type", herencia ? "Generalization"
                            : r.getKind() == RelationKind.COMPOSITION || r.getKind() == RelationKind.AGGREGATION
                              ? "Aggregation" : "Association",
                    "direction", "Source -> Destination");
            x.vacio("modifiers", "isRoot", "false", "isLeaf", "false");
            x.vacio("parameterSubstitutions");
            x.vacio("documentation");
            x.vacio("appearance", "linemode", "3", "linecolor", "-1", "linewidth", "0", "seqno", "0");
            x.vacio("labels", "mt", r.getName());
            x.vacio("extendedProperties", "virtualInheritance", "0");
            x.vacio("style");
            x.vacio("xrefs");
            x.vacio("tags");
            x.cerrar("connector");
        }
        x.cerrar("connectors");
        x.abrir("primitivetypes");
        x.vacio("packagedElement", "xmi:type", "uml:Package", "xmi:id", "EAPrimitiveTypesPackage",
                "name", "EA_PrimitiveTypes_Package", "visibility", "public");
        x.cerrar("primitivetypes");
        x.vacio("profiles");
        x.abrir("diagrams");
        x.abrir("diagram", "xmi:id", "EAID_D1A6B2C3_0000_0000_0000_000000000001");
        x.vacio("model", "package", "EAPK_6A0F1E2D_33B1_4C8A_A1B0_2F5D6C7E8F90", "localID", "1", "owner",
                "EAPK_6A0F1E2D_33B1_4C8A_A1B0_2F5D6C7E8F90");
        x.vacio("properties", "name", "Modelo del docente", "type", "Logical");
        x.abrir("elements");
        int top = 10;
        for (UmlClass c : m.clases()) {
            x.vacio("element", "geometry", "Left=10;Top=" + top + ";Right=150;Bottom=" + (top += 90) + ";",
                    "subject", idClase.get(c.getId()), "seqno", "1", "style", "DUID=1A2B3C4D;");
        }
        x.cerrar("elements");
        x.cerrar("diagram");
        x.cerrar("diagrams");
        x.cerrar("xmi:Extension");
        x.cerrar("xmi:XMI");
        return x.toString();
    }

    /**
     * En EA una composicion se dibuja de la PARTE al TODO y el rombo queda en
     * el destino. En nuestro modelo el todo es el origen: para el todo-parte
     * se da vuelta.
     */
    private record SentidoEa(UUID src, String rolSrc, String multSrc, UUID dst, String rolDst, String multDst,
                             String rombo) {
        static SentidoEa de(UmlRelationship r) {
            String rombo = agregacionUml(r.getKind());
            return rombo.equals("none")
                    ? new SentidoEa(r.getSourceClassId(), r.getSourceRole(), r.getSourceMultiplicity(),
                                    r.getTargetClassId(), r.getTargetRole(), r.getTargetMultiplicity(), rombo)
                    : new SentidoEa(r.getTargetClassId(), r.getTargetRole(), r.getTargetMultiplicity(),
                                    r.getSourceClassId(), r.getSourceRole(), r.getSourceMultiplicity(), rombo);
        }
    }

    private static void extremoEa(Xml x, String id, String rol, String asociacion, String tipo, String multiplicidad,
                                  String agregacion, int literal) {
        Mult mult = Mult.de(multiplicidad);
        x.abrir("ownedEnd", "xmi:type", "uml:Property", "xmi:id", id, "name", rol, "visibility", "public",
                "association", asociacion, "isStatic", "false", "isReadOnly", "false", "isDerived", "false",
                "isOrdered", "false", "isUnique", "true", "isDerivedUnion", "false", "aggregation", agregacion);
        x.vacio("type", "xmi:idref", tipo);
        if (mult != null) {
            x.vacio("lowerValue", "xmi:type", "uml:LiteralInteger", "xmi:id", "EAID_LE" + literal, "value", mult.bajo());
            x.vacio("upperValue", "xmi:type", "uml:LiteralUnlimitedNatural", "xmi:id", "EAID_LE" + (literal + 1),
                    "value", mult.alto().equals("*") ? "-1" : mult.alto());
        }
        x.cerrar("ownedEnd");
    }

    private static void conectorEa(Xml x, String lado, String idClase, String nombre, String rol, String multiplicidad,
                                   String agregacion) {
        x.abrir(lado, "xmi:idref", idClase);
        x.vacio("model", "ea_localid", "1", "type", "Class", "name", nombre);
        x.vacio("role", "name", rol, "visibility", "Public", "targetScope", "instance");
        x.vacio("type", "multiplicity", multiplicidad, "aggregation", agregacion, "containment", "Unspecified");
        x.vacio("constraints");
        x.vacio("modifiers", "isOrdered", "false", "changeable", "none", "isNavigable", "true");
        x.vacio("style", "value", "Union=0;Derived=0;AllowDuplicates=0;Owned=0;Navigable=Navigable;");
        x.vacio("documentation");
        x.vacio("xrefs");
        x.vacio("tags");
        x.cerrar(lado);
    }

    // ============================================================= PAPYRUS

    private static final String PATHMAP = "pathmap://UML_LIBRARIES/UMLPrimitiveTypes.library.uml#";

    static String papyrus(ModeloCanonico.Cargado m) {
        Map<UUID, String> idClase = ids(m.clases(), UmlClass::getId, "_kR2vQE9fEe-cls");
        Map<UUID, String> idAtr = ids(m.atributos(), UmlAttribute::getId, "_kR2vQE9fEe-atr");
        Map<UUID, String> idRel = ids(m.relaciones(), UmlRelationship::getId, "_kR2vQE9fEe-rel");
        Map<UUID, List<UmlAttribute>> atrs = atributosPorClase(m);

        // Los tipos que no son primitivos de UML son PrimitiveType del propio modelo.
        Map<String, String> tipoLocal = new LinkedHashMap<>();
        for (UmlAttribute a : m.atributos()) {
            if (primitivoUml(a.getDataType()) == null) tipoLocal.putIfAbsent(a.getDataType(), "_kR2vQE9fEe-tipo-" + a.getDataType());
        }

        Xml x = new Xml("<?xml version=\"1.0\" encoding=\"UTF-8\"?>", '"', "=");
        x.abrir("xmi:XMI", "xmi:version", "20131001", "xmlns:xmi", "http://www.omg.org/spec/XMI/20131001",
                "xmlns:xsi", "http://www.w3.org/2001/XMLSchema-instance",
                "xmlns:ecore", "http://www.eclipse.org/emf/2002/Ecore",
                "xmlns:uml", "http://www.eclipse.org/uml2/5.0.0/UML",
                "xmlns:Datos", "http:///schemas/Datos/_perfil/0",
                "xsi:schemaLocation", "http:///schemas/Datos/_perfil/0 Datos.profile.uml#_perfil");
        x.abrir("uml:Model", "xmi:id", "_kR2vQE9fEe-model", "name", "Modelo");
        x.abrir("packageImport", "xmi:type", "uml:PackageImport", "xmi:id", "_kR2vQE9fEe-imp");
        x.vacio("importedPackage", "xmi:type", "uml:Model", "href", "pathmap://UML_LIBRARIES/UMLPrimitiveTypes.library.uml#_0");
        x.cerrar("packageImport");

        List<UmlRelationship> asociaciones = asociaciones(m);
        for (UmlClass c : m.clases()) {
            x.abrir("packagedElement", "xmi:type", "uml:Class", "xmi:id", idClase.get(c.getId()), "name", c.getName(),
                    "isAbstract", c.isAbstract() ? "true" : null);
            for (UmlRelationship g : herencias(m)) {
                if (g.getSourceClassId().equals(c.getId())) {
                    x.vacio("generalization", "xmi:type", "uml:Generalization", "xmi:id", idRel.get(g.getId()),
                            "general", idClase.get(g.getTargetClassId()));
                }
            }
            for (UmlAttribute a : atrs.getOrDefault(c.getId(), List.of())) {
                String primitivo = primitivoUml(a.getDataType());
                x.abrir("ownedAttribute", "xmi:type", "uml:Property", "xmi:id", idAtr.get(a.getId()), "name", a.getName(),
                        "type", primitivo == null ? tipoLocal.get(a.getDataType()) : null,
                        "isID", a.isPk() ? "true" : null);
                if (primitivo != null) x.vacio("type", "xmi:type", "uml:PrimitiveType", "href", PATHMAP + primitivo);
                multiplicidadPapyrus(x, idAtr.get(a.getId()), a.isRequired() || a.isPk() ? "1" : "0",
                        a.isMultivalued() ? "*" : "1");
                x.cerrar("ownedAttribute");
            }
            // El extremo navegable (hacia el destino) lo tiene la clase origen.
            for (UmlRelationship r : asociaciones) {
                if (!r.getSourceClassId().equals(c.getId())) continue;
                Mult mt = Mult.de(r.getTargetMultiplicity());
                x.abrir("ownedAttribute", "xmi:type", "uml:Property", "xmi:id", idRel.get(r.getId()) + "-dst",
                        "name", r.getTargetRole(), "type", idClase.get(r.getTargetClassId()),
                        "aggregation", agregacionUml(r.getKind()).equals("none") ? null : agregacionUml(r.getKind()),
                        "association", idRel.get(r.getId()));
                if (mt != null) multiplicidadPapyrus(x, idRel.get(r.getId()) + "-dst", mt.bajo(), mt.alto());
                x.cerrar("ownedAttribute");
            }
            x.cerrar("packagedElement");
        }
        for (UmlRelationship r : asociaciones) {
            String id = idRel.get(r.getId());
            x.abrir("packagedElement", "xmi:type", "uml:Association", "xmi:id", id, "name", r.getName(),
                    "memberEnd", id + "-dst " + id + "-src");
            x.abrir("eAnnotations", "xmi:type", "ecore:EAnnotation", "xmi:id", id + "-ann", "source", "org.eclipse.papyrus");
            x.vacio("details", "xmi:type", "ecore:EStringToStringMapEntry", "xmi:id", id + "-det", "key", "nature",
                    "value", "UML_Nature");
            x.cerrar("eAnnotations");
            Mult ms = Mult.de(r.getSourceMultiplicity());
            x.abrir("ownedEnd", "xmi:type", "uml:Property", "xmi:id", id + "-src", "name", r.getSourceRole(),
                    "type", idClase.get(r.getSourceClassId()), "association", id);
            if (ms != null) multiplicidadPapyrus(x, id + "-src", ms.bajo(), ms.alto());
            x.cerrar("ownedEnd");
            x.cerrar("packagedElement");
        }
        tipoLocal.forEach((nombre, id) -> x.vacio("packagedElement", "xmi:type", "uml:PrimitiveType", "xmi:id", id, "name", nombre));
        x.abrir("profileApplication", "xmi:type", "uml:ProfileApplication", "xmi:id", "_kR2vQE9fEe-pa");
        x.vacio("appliedProfile", "href", "Datos.profile.uml#_perfil");
        x.cerrar("profileApplication");
        x.cerrar("uml:Model");
        for (UmlAttribute a : m.atributos()) {
            if (a.isUnique()) x.vacio("Datos:unique", "xmi:id", idAtr.get(a.getId()) + "-st", "base_Property", idAtr.get(a.getId()));
        }
        x.cerrar("xmi:XMI");
        return x.toString();
    }

    private static String primitivoUml(String tipo) {
        return switch (tipo == null ? "" : tipo) {
            case "string" -> "String";
            case "int" -> "Integer";
            case "boolean" -> "Boolean";
            default -> null;
        };
    }

    /** Papyrus omite lo que vale lo mismo que el valor por defecto de UML: [1..1] no se escribe, y 0 va sin `value`. */
    private static void multiplicidadPapyrus(Xml x, String id, String bajo, String alto) {
        if (bajo.equals("1") && alto.equals("1")) return;
        x.vacio("lowerValue", "xmi:type", "uml:LiteralInteger", "xmi:id", id + "-lo", "value", bajo.equals("0") ? null : bajo);
        x.vacio("upperValue", "xmi:type", "uml:LiteralUnlimitedNatural", "xmi:id", id + "-up", "value", alto);
    }

    // ============================================================= ARGOUML

    static String argoUml(ModeloCanonico.Cargado m) {
        String base = "-64--88-1-100-5a3f2b1c:18a2b3c4d5e:-8000:00000000000";
        int[] n = {1000};
        Map<UUID, String> idClase = new LinkedHashMap<>();
        m.clases().forEach(c -> idClase.put(c.getId(), base + n[0]++));
        Map<UUID, String> idRel = new LinkedHashMap<>();
        m.relaciones().forEach(r -> idRel.put(r.getId(), base + n[0]++));
        Map<String, String> idTipo = new LinkedHashMap<>();
        m.atributos().forEach(a -> idTipo.computeIfAbsent(a.getDataType(), t -> base + n[0]++));
        String estereotipoPk = base + n[0]++;
        String tagUnique = base + n[0]++;
        Map<UUID, List<UmlAttribute>> atrs = atributosPorClase(m);

        Xml x = new Xml("<?xml version = '1.0' encoding = 'UTF-8' ?>", '\'', " = ");
        x.abrir("XMI", "xmi.version", "1.2", "xmlns:UML", "org.omg.xmi.namespace.UML", "timestamp", "Tue Sep 01 10:00:00 BOT 2026");
        x.abrir("XMI.header");
        x.abrir("XMI.documentation");
        x.conTexto("XMI.exporter", "ArgoUML (using Netbeans XMI Writer version 1.0)");
        x.conTexto("XMI.exporterVersion", "0.34(6) revised on $Date: 2010-01-11 22:20:14 +0200 (Mon, 11 Jan 2010) $");
        x.cerrar("XMI.documentation");
        x.vacio("XMI.metamodel", "xmi.name", "UML", "xmi.version", "1.4");
        x.cerrar("XMI.header");
        x.abrir("XMI.content");
        x.abrir("UML:Model", "xmi.id", base + "999", "name", "untitledModel", "isSpecification", "false",
                "isRoot", "false", "isLeaf", "false", "isAbstract", "false");
        x.abrir("UML:Namespace.ownedElement");

        int k = 5000;
        for (UmlClass c : m.clases()) {
            x.abrir("UML:Class", "xmi.id", idClase.get(c.getId()), "name", c.getName(), "visibility", "public",
                    "isSpecification", "false", "isRoot", "false", "isLeaf", "false",
                    "isAbstract", String.valueOf(c.isAbstract()), "isActive", "false");
            List<UmlRelationship> suyas = herencias(m).stream().filter(g -> g.getSourceClassId().equals(c.getId())).toList();
            if (!suyas.isEmpty()) {
                x.abrir("UML:GeneralizableElement.generalization");
                suyas.forEach(g -> x.vacio("UML:Generalization", "xmi.idref", idRel.get(g.getId())));
                x.cerrar("UML:GeneralizableElement.generalization");
            }
            x.abrir("UML:Classifier.feature");
            for (UmlAttribute a : atrs.getOrDefault(c.getId(), List.of())) {
                x.abrir("UML:Attribute", "xmi.id", base + k++, "name", a.getName(), "visibility", "private",
                        "isSpecification", "false", "ownerScope", "instance", "changeability", "changeable",
                        "targetScope", "instance");
                if (a.isPk()) {
                    x.abrir("UML:ModelElement.stereotype");
                    x.vacio("UML:Stereotype", "xmi.idref", estereotipoPk);
                    x.cerrar("UML:ModelElement.stereotype");
                }
                if (a.isUnique()) {
                    x.abrir("UML:ModelElement.taggedValue");
                    x.abrir("UML:TaggedValue", "xmi.id", base + k++, "isSpecification", "false");
                    x.conTexto("UML:TaggedValue.dataValue", "true");
                    x.abrir("UML:TaggedValue.type");
                    x.vacio("UML:TagDefinition", "xmi.idref", tagUnique);
                    x.cerrar("UML:TaggedValue.type");
                    x.cerrar("UML:TaggedValue");
                    x.cerrar("UML:ModelElement.taggedValue");
                }
                multiplicidadArgo(x, "UML:StructuralFeature.multiplicity", base, k,
                        a.isRequired() || a.isPk() ? "1" : "0", a.isMultivalued() ? "-1" : "1");
                k += 2;
                x.abrir("UML:StructuralFeature.type");
                x.vacio("UML:DataType", "xmi.idref", idTipo.get(a.getDataType()));
                x.cerrar("UML:StructuralFeature.type");
                x.cerrar("UML:Attribute");
            }
            x.cerrar("UML:Classifier.feature");
            x.cerrar("UML:Class");
        }

        for (UmlRelationship r : asociaciones(m)) {
            x.abrir("UML:Association", "xmi.id", idRel.get(r.getId()), "name", r.getName() == null ? "" : r.getName(),
                    "isSpecification", "false", "isRoot", "false", "isLeaf", "false", "isAbstract", "false");
            x.abrir("UML:Association.connection");
            // UML 1.4: la agregacion va en el extremo del TODO, que en nuestro modelo es el origen.
            extremoArgo(x, base, k, r.getSourceRole(), false, agregacionUml(r.getKind()), r.getSourceMultiplicity(),
                    idClase.get(r.getSourceClassId()));
            k += 3;
            extremoArgo(x, base, k, r.getTargetRole(), true, "none", r.getTargetMultiplicity(),
                    idClase.get(r.getTargetClassId()));
            k += 3;
            x.cerrar("UML:Association.connection");
            x.cerrar("UML:Association");
        }
        for (UmlRelationship g : herencias(m)) {
            x.abrir("UML:Generalization", "xmi.id", idRel.get(g.getId()), "isSpecification", "false");
            x.abrir("UML:Generalization.child");
            x.vacio("UML:Class", "xmi.idref", idClase.get(g.getSourceClassId()));
            x.cerrar("UML:Generalization.child");
            x.abrir("UML:Generalization.parent");
            x.vacio("UML:Class", "xmi.idref", idClase.get(g.getTargetClassId()));
            x.cerrar("UML:Generalization.parent");
            x.cerrar("UML:Generalization");
        }
        x.abrir("UML:Stereotype", "xmi.id", estereotipoPk, "name", "PK", "isSpecification", "false",
                "isRoot", "false", "isLeaf", "false", "isAbstract", "false");
        x.conTexto("UML:Stereotype.baseClass", "Attribute");
        x.cerrar("UML:Stereotype");
        x.vacio("UML:TagDefinition", "xmi.id", tagUnique, "name", "unique", "isSpecification", "false", "tagType", "String");
        idTipo.forEach((tipo, id) -> x.vacio("UML:DataType", "xmi.id", id, "name", tipo, "isSpecification", "false",
                "isRoot", "false", "isLeaf", "false", "isAbstract", "false"));
        x.cerrar("UML:Namespace.ownedElement");
        x.cerrar("UML:Model");
        x.cerrar("XMI.content");
        x.cerrar("XMI");
        return x.toString();
    }

    private static void multiplicidadArgo(Xml x, String envoltorio, String base, int k, String bajo, String alto) {
        x.abrir(envoltorio);
        x.abrir("UML:Multiplicity", "xmi.id", base + "m" + k);
        x.abrir("UML:Multiplicity.range");
        x.vacio("UML:MultiplicityRange", "xmi.id", base + "r" + k, "lower", bajo, "upper", alto);
        x.cerrar("UML:Multiplicity.range");
        x.cerrar("UML:Multiplicity");
        x.cerrar(envoltorio);
    }

    private static void extremoArgo(Xml x, String base, int k, String rol, boolean navegable, String agregacion,
                                    String multiplicidad, String idClase) {
        x.abrir("UML:AssociationEnd", "xmi.id", base + "e" + k, "name", rol == null ? "" : rol, "visibility", "public",
                "isSpecification", "false", "isNavigable", String.valueOf(navegable), "ordering", "unordered",
                "aggregation", agregacion, "targetScope", "instance", "changeability", "changeable");
        Mult mult = Mult.de(multiplicidad);
        if (mult != null) {
            multiplicidadArgo(x, "UML:AssociationEnd.multiplicity", base, k, mult.bajo(),
                    mult.alto().equals("*") ? "-1" : mult.alto());
        }
        x.abrir("UML:AssociationEnd.participant");
        x.vacio("UML:Class", "xmi.idref", idClase);
        x.cerrar("UML:AssociationEnd.participant");
        x.cerrar("UML:AssociationEnd");
    }

    // ============================================ MODELO DE DATOS DE EA

    /** Donde pone EA el estereotipo: en su Extension, o como aplicacion del perfil EAUML. */
    enum EstereotiposEa { EXTENSION, PERFIL }

    static byte[] modeloDeDatosEa(ModeloCanonico.Cargado m, EstereotiposEa donde) {
        ModeloLogico logico = DerivadorLogico.derivar(m.clases(), m.atributos(), m.relaciones());
        return modeloDeDatosEa(logico.tablas(), donde).getBytes(Charset.forName("windows-1252"));
    }

    static String modeloDeDatosEa(List<ModeloLogico.Tabla> tablas, EstereotiposEa donde) {
        Map<String, String> idTabla = new LinkedHashMap<>();
        int i = 1;
        for (ModeloLogico.Tabla t : tablas) idTabla.put(t.nombre(), "EAID_7C0DB000_0000_0000_0000_00000000T" + i++);
        record Fk(String tabla, String operacion, ModeloLogico.Columna columna, String destino, String id) { }
        List<Fk> fks = new ArrayList<>();
        boolean enExtension = donde == EstereotiposEa.EXTENSION;

        Xml x = new Xml("<?xml version=\"1.0\" encoding=\"windows-1252\"?>", '"', "=");
        x.abrir("xmi:XMI", "xmi:version", "2.1", "xmlns:uml", "http://schema.omg.org/spec/UML/2.1",
                "xmlns:xmi", "http://schema.omg.org/spec/XMI/2.1",
                "xmlns:EAUML", "http://www.sparxsystems.com/profiles/EAUML/1.0");
        x.vacio("xmi:Documentation", "exporter", "Enterprise Architect", "exporterVersion", "6.5", "exporterID", "1628");
        x.abrir("uml:Model", "xmi:type", "uml:Model", "name", "EA_Model", "visibility", "public");
        x.abrir("packagedElement", "xmi:type", "uml:Package", "xmi:id", "EAPK_DB", "name", "Modelo fisico", "visibility", "public");

        int c = 1;
        Map<String, String> idColumna = new LinkedHashMap<>();
        Map<String, String> idOperacion = new LinkedHashMap<>();
        for (ModeloLogico.Tabla t : tablas) {
            x.abrir("packagedElement", "xmi:type", "uml:Class", "xmi:id", idTabla.get(t.nombre()), "name", t.nombre(),
                    "visibility", "public");
            for (ModeloLogico.Columna col : t.columnas()) {
                String id = "EAID_COL" + c++;
                idColumna.put(t.nombre() + "." + col.nombre(), id);
                x.abrir("ownedAttribute", "xmi:type", "uml:Property", "xmi:id", id, "name", col.nombre(),
                        "visibility", "public", "isStatic", "false", "isReadOnly", "false", "isDerived", "false",
                        "isOrdered", "false", "isUnique", "true", "isDerivedUnion", "false");
                x.vacio("lowerValue", "xmi:type", "uml:LiteralInteger", "xmi:id", id + "_LO", "value", "1");
                x.vacio("upperValue", "xmi:type", "uml:LiteralUnlimitedNatural", "xmi:id", id + "_UP", "value", "1");
                x.vacio("type", "xmi:idref", "EAPostgreSQL_" + tipoBase(col.tipo()));
                x.cerrar("ownedAttribute");
            }
            List<ModeloLogico.Columna> pk = t.columnas().stream().filter(ModeloLogico.Columna::pk).toList();
            operacion(x, idOperacion, t.nombre(), "PK_" + t.nombre(), pk);
            for (ModeloLogico.Columna col : t.columnas()) {
                if (col.unique() && !col.pk()) operacion(x, idOperacion, t.nombre(), "UQ_" + t.nombre() + "_" + col.nombre(), List.of(col));
                if (col.referencia() == null) continue;
                String op = "FK_" + t.nombre() + "_" + col.nombre();
                operacion(x, idOperacion, t.nombre(), op, List.of(col));
                fks.add(new Fk(t.nombre(), op, col, col.referencia().substring(0, col.referencia().indexOf('(')),
                        "EAID_FKCN" + (fks.size() + 1)));
            }
            x.cerrar("packagedElement");
        }
        for (Fk fk : fks) {
            x.abrir("packagedElement", "xmi:type", "uml:Association", "xmi:id", fk.id(), "visibility", "public");
            x.vacio("memberEnd", "xmi:idref", "EAID_dst" + fk.id().substring(8));
            x.vacio("memberEnd", "xmi:idref", "EAID_src" + fk.id().substring(8));
            x.abrir("ownedEnd", "xmi:type", "uml:Property", "xmi:id", "EAID_dst" + fk.id().substring(8),
                    "name", "PK_" + fk.destino(), "association", fk.id());
            x.vacio("type", "xmi:idref", idTabla.get(fk.destino()));
            x.cerrar("ownedEnd");
            x.abrir("ownedEnd", "xmi:type", "uml:Property", "xmi:id", "EAID_src" + fk.id().substring(8),
                    "name", fk.operacion(), "association", fk.id());
            x.vacio("type", "xmi:idref", idTabla.get(fk.tabla()));
            x.cerrar("ownedEnd");
            x.cerrar("packagedElement");
        }
        x.cerrar("packagedElement");
        x.cerrar("uml:Model");

        if (!enExtension) {
            idTabla.values().forEach(id -> x.vacio("EAUML:table", "xmi:id", id + "_ST", "base_Class", id));
            idColumna.values().forEach(id -> x.vacio("EAUML:column", "xmi:id", id + "_ST", "base_Property", id));
            idOperacion.forEach((clave, id) -> {
                String op = clave.substring(clave.indexOf('#') + 1);
                x.vacio("EAUML:" + op.substring(0, op.indexOf('_')).replace("UQ", "unique"),
                        "xmi:id", id + "_ST", "base_Operation", id);
            });
            fks.forEach(fk -> x.vacio("EAUML:FK", "xmi:id", fk.id() + "_ST", "base_Association", fk.id()));
        }

        x.abrir("xmi:Extension", "extender", "Enterprise Architect", "extenderID", "6.5");
        x.abrir("elements");
        for (ModeloLogico.Tabla t : tablas) {
            x.abrir("element", "xmi:idref", idTabla.get(t.nombre()), "xmi:type", "uml:Class", "name", t.nombre(), "scope", "public");
            x.vacio("model", "package", "EAPK_DB", "tpos", "0", "ea_localid", "1", "ea_eleType", "element");
            x.vacio("properties", "isSpecification", "false", "sType", "Class", "nType", "0", "scope", "public",
                    "stereotype", enExtension ? "table" : null, "isRoot", "false", "isLeaf", "false", "isAbstract", "false");
            x.vacio("extendedProperties", "tagged", "0", "package_name", "Modelo fisico");
            x.vacio("xrefs", "value", enExtension ? null
                    : "$XREFPROP=$XID={1}$XID;$NAM=Stereotypes$NAM;$TYP=element property$TYP;$VIS=Public$VIS;$PAR=0$PAR;"
                      + "$DES=@STEREO;Name=table;GUID={2};FQName=EAUML::table;@ENDSTEREO;$DES;$CLT={3}$CLT;$SUP=<none>$SUP;$ENDXREF;");
            x.abrir("attributes");
            for (ModeloLogico.Columna col : t.columnas()) {
                x.abrir("attribute", "xmi:idref", idColumna.get(t.nombre() + "." + col.nombre()), "name", col.nombre(), "scope", "Public");
                x.vacio("initial");
                x.vacio("documentation");
                x.vacio("properties", "type", tipoBase(col.tipo()), "derived", "0", "collection", "false",
                        "length", largo(col.tipo()), "precision", precision(col.tipo()), "scale", escala(col.tipo()),
                        "duplicates", col.nullable() ? "0" : "1", "changeability", "changeable");
                x.vacio("coords", "ordered", "0", "scale", "0");
                x.vacio("stereotype", "stereotype", enExtension ? "column" : null);
                x.vacio("bounds", "lower", "1", "upper", "1");
                x.vacio("tags");
                x.cerrar("attribute");
            }
            x.cerrar("attributes");
            x.abrir("operations");
            idOperacion.forEach((clave, id) -> {
                if (!clave.startsWith(t.nombre() + "#")) return;
                String op = clave.substring(clave.indexOf('#') + 1);
                x.abrir("operation", "xmi:idref", id, "name", op, "scope", "Public");
                String tipo = op.substring(0, op.indexOf('_'));
                x.vacio("stereotype", "stereotype", enExtension ? (tipo.equals("UQ") ? "unique" : tipo) : null);
                Fk fk = fks.stream().filter(f -> f.operacion().equals(op)).findFirst().orElse(null);
                x.vacio("styleex", "value", fk == null ? null
                        : "OnDelete=" + (fk.columna().onDelete() == null ? "No Action" : fk.columna().onDelete()) + ";OnUpdate=No Action;");
                x.vacio("tags");
                x.cerrar("operation");
            });
            x.cerrar("operations");
            x.cerrar("element");
        }
        x.cerrar("elements");
        x.abrir("connectors");
        for (Fk fk : fks) {
            x.abrir("connector", "xmi:idref", fk.id());
            x.abrir("source", "xmi:idref", idTabla.get(fk.tabla()));
            x.vacio("model", "ea_localid", "1", "type", "Class", "name", fk.tabla());
            x.vacio("role", "name", fk.operacion(), "visibility", "Public", "targetScope", "instance");
            x.vacio("type", "multiplicity", fk.columna().unique() ? "0..1" : "0..*", "aggregation", "none", "containment", "Unspecified");
            x.cerrar("source");
            x.abrir("target", "xmi:idref", idTabla.get(fk.destino()));
            x.vacio("model", "ea_localid", "2", "type", "Class", "name", fk.destino());
            x.vacio("role", "name", "PK_" + fk.destino(), "visibility", "Public", "targetScope", "instance");
            x.vacio("type", "multiplicity", fk.columna().nullable() ? "0..1" : "1", "aggregation", "none", "containment", "Unspecified");
            x.cerrar("target");
            x.vacio("properties", "ea_type", "Association", "stereotype", enExtension ? "FK" : null, "direction", "Source -> Destination");
            x.cerrar("connector");
        }
        x.cerrar("connectors");
        x.cerrar("xmi:Extension");
        x.cerrar("xmi:XMI");
        return x.toString();
    }

    private static void operacion(Xml x, Map<String, String> ids, String tabla, String nombre, List<ModeloLogico.Columna> columnas) {
        if (columnas.isEmpty()) return;
        String id = "EAID_OP" + (ids.size() + 1);
        ids.put(tabla + "#" + nombre, id);
        x.abrir("ownedOperation", "xmi:id", id, "name", nombre, "visibility", "public", "concurrency", "sequential");
        int p = 1;
        for (ModeloLogico.Columna col : columnas) {
            x.abrir("ownedParameter", "xmi:id", id + "_P" + p++, "name", col.nombre(), "direction", "in");
            x.vacio("type", "xmi:idref", "EAPostgreSQL_" + tipoBase(col.tipo()));
            x.cerrar("ownedParameter");
        }
        x.cerrar("ownedOperation");
    }

    private static String tipoBase(String tipo) {
        int p = tipo.indexOf('(');
        return p < 0 ? tipo : tipo.substring(0, p);
    }

    private static String argumento(String tipo, int n) {
        int p = tipo.indexOf('(');
        if (p < 0) return null;
        String[] partes = tipo.substring(p + 1, tipo.length() - 1).split(",");
        return n < partes.length ? partes[n].trim() : null;
    }

    private static String largo(String tipo) {
        return tipo.startsWith("varchar") ? argumento(tipo, 0) : "0";
    }

    private static String precision(String tipo) {
        return tipo.startsWith("numeric") ? argumento(tipo, 0) : "0";
    }

    private static String escala(String tipo) {
        return tipo.startsWith("numeric") ? argumento(tipo, 1) : "0";
    }
}
