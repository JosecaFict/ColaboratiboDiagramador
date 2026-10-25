package com.uagrm.colabmodel.interop;

import com.uagrm.colabmodel.interop.ArbolXml.Nodo;
import com.uagrm.colabmodel.modelcore.mapping.Nombres;
import com.uagrm.colabmodel.modelcore.metamodel.RelationKind;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * XMI 1.x y 2.x, de cualquier herramienta, con tres perfiles (ADR-013, ADR-019).
 *
 * El dialecto del docente no se conoce, asi que este lector no confia en nada
 * que el archivo diga de si mismo:
 *   - El PERFIL se decide por elemento y por contenido, no por el encabezado:
 *     una clase con «table» es una tabla, con «entity» una entidad, y sin nada
 *     una clase UML, aunque el archivo mezcle los tres.
 *   - Los ESTEREOTIPOS se juntan de todas las formas conocidas de escribirlos:
 *     la Extension de Enterprise Architect, aplicaciones de perfil
 *     (`<perfil:table base_Class="..."/>`), el `ea_stype` de XMI 1.1 y
 *     `UML:Stereotype`. Da igual cual use el archivo.
 *   - Lo que no se entiende se CUENTA y se lista en las advertencias, que
 *     llegan a la pantalla de revision. Cada elemento se lee aislado: si uno
 *     falla, se avisa y se sigue. Solo es error un archivo del que no sale
 *     ninguna clase, entidad ni tabla.
 *
 * Las tablas terminan en EsquemaRelacional, la misma ingenieria inversa que el
 * SQL: un modelo de datos de EA y su DDL se importan igual.
 */
@Component
public class LectorXmi implements LectorDeModelo {

    @Override
    public String formato() {
        return "XMI";
    }

    @Override
    public boolean acepta(String nombreArchivo, byte[] contenido) {
        String nombre = nombreArchivo == null ? "" : nombreArchivo.toLowerCase(Locale.ROOT);
        if (nombre.endsWith(".xmi") || nombre.endsWith(".uml")) return true;
        if (nombre.endsWith(".json") || nombre.endsWith(".sql") || nombre.endsWith(".ddl")) return false;
        String inicio = new String(contenido, 0, Math.min(contenido.length, 8192), StandardCharsets.UTF_8)
                .toLowerCase(Locale.ROOT);
        return inicio.contains("<xmi") || inicio.contains("xmi.version") || inicio.contains("xmlns:uml")
                || inicio.contains("omg.org/spec/xmi");
    }

    @Override
    public ModeloLeido leer(byte[] contenido) {
        if (contenido == null || contenido.length == 0) throw new ArchivoInvalidoException("El archivo XMI esta vacio");
        ArbolXml.Resultado xml = ArbolXml.leer(contenido);
        if (xml.elementos() == 0) {
            throw new ArchivoInvalidoException("El archivo no es un XML que se pueda leer"
                    + (xml.error() != null ? " (" + xml.error() + ")" : ""));
        }
        List<String> avisos = new ArrayList<>();
        if (xml.error() != null) {
            avisos.add("El XML esta roto en la " + xml.error() + ". Se importa lo que se leyo hasta ahi ("
                    + xml.elementos() + " elementos); lo que seguia en el archivo quedo afuera.");
        } else if (xml.saneado()) {
            avisos.add("El XML tenia errores de formato (" + xml.original() + ") y se corrigieron para poder leerlo:"
                    + " revisa los nombres con & o con caracteres raros.");
        }
        ModeloLeido modelo = new Lectura(xml.raiz(), avisos).leer();
        if (modelo.clases().isEmpty()) {
            throw new ArchivoInvalidoException("No se encontro ninguna clase, entidad ni tabla en el XMI. "
                    + String.join(" ", modelo.advertencias()));
        }
        return modelo;
    }

    // ======================================================================

    /** El estado de UNA lectura. */
    private static final class Lectura {

        private static final Set<String> CLASIFICADORES = Set.of("Class", "AssociationClass", "Enumeration", "DataType");
        private static final Set<String> FRONTERAS = Set.of("Class", "AssociationClass", "Enumeration", "DataType",
                "Interface", "Association", "Package", "Model", "Component", "Signal", "PrimitiveType", "Actor",
                "UseCase", "StateMachine", "Activity", "Collaboration", "Interaction", "Node", "Artifact");
        private static final Set<String> VINCULOS_SIN_ESQUEMA = Set.of("Dependency", "Realization",
                "InterfaceRealization", "Usage", "Abstraction", "InformationFlow", "Substitution", "Include",
                "Extend", "Trace", "NoteLink");
        /** Estructura que se usa o que no es modelo: nunca se reporta como desconocida. */
        private static final Set<String> CONOCIDOS = Set.of("Model", "Package", "Profile", "Stereotype", "Property",
                "Operation", "Parameter", "EnumerationLiteral", "AssociationEnd", "Attribute", "Generalization",
                "Association", "PrimitiveType", "TagDefinition", "TaggedValue", "Comment", "LiteralInteger",
                "LiteralUnlimitedNatural", "LiteralString", "LiteralBoolean", "LiteralNull", "LiteralReal",
                "OpaqueExpression", "InstanceValue", "Multiplicity", "MultiplicityRange", "ProfileApplication",
                "PackageImport", "ElementImport", "Diagram", "EAStub", "Expression", "ownedComment", "Classifier",
                "Namespace", "Subsystem", "DiagramElement", "EAnnotation", "Slot");

        private final Nodo raiz;
        private final List<String> advertencias;

        private final Map<String, Nodo> porId = new HashMap<>();
        private final List<Nodo> definiciones = new ArrayList<>();
        private final Map<String, Set<String>> estereotipos = new HashMap<>();
        private final Map<String, Map<String, String>> etiquetas = new HashMap<>();
        private final Map<String, Nodo> conectoresEa = new LinkedHashMap<>();
        private final Map<String, Nodo> elementosEa = new HashMap<>();
        private final List<String[]> estereotiposPorReferencia = new ArrayList<>();
        private final List<String[]> etiquetasPorReferencia = new ArrayList<>();
        /** Propiedades UML 2 sin multiplicidad escrita: valen [1], y se avisa. */
        private int multiplicidadesOmitidas;
        /** Referencias de tipo a algo que no vino en el archivo ni se puede leer como nombre. */
        private final List<String> tiposSinResolver = new ArrayList<>();
        private int clasesUmlLeidas;
        private int entidadesLeidas;
        private final Set<String> consumidos = new HashSet<>();
        private String exportador;
        private boolean cardinalidadChen;
        /**
         * Lo dibujado, por diagrama: id del elemento -> caja. Un archivo de EA
         * trae un `<diagram>` por cada diagrama del paquete; se usa UNO solo
         * (el que mas clases leidas dibuja), porque mezclar coordenadas de dos
         * diagramas encima una clase sobre otra.
         */
        private final Map<Nodo, Map<String, ModeloLeido.Posicion>> dibujos = new LinkedHashMap<>();

        Lectura(Nodo raiz, List<String> advertencias) {
            this.raiz = raiz;
            this.advertencias = advertencias;
        }

        // ================================================================ INDICE

        private void indexar(Nodo n, boolean enExtension) {
            boolean ext = enExtension || n.esLocal("Extension", "XMI.extensions", "XMI.extension", "diagrams",
                    "Diagram", "XMI.header", "Documentation", "XMI.documentation");
            String id = n.id();

            if (n.esLocal("Documentation") && n.atr("exporter") != null) {
                exportador = n.atr("exporter") + (n.atr("exporterVersion") != null ? " " + n.atr("exporterVersion") : "");
            }
            if (n.esLocal("XMI.exporter") && !n.texto().isEmpty()) exportador = n.texto();

            if (n.esLocal("EAStub") && id != null) {
                porId.putIfAbsent(id, n);
            } else if (!ext && id != null && n.idref() == null && n.atr("href") == null) {
                porId.putIfAbsent(id, n);
                definiciones.add(n);
            }

            if (ext && n.idref() != null) {
                if (n.esLocal("element")) elementosEa.putIfAbsent(n.idref(), n);
                if (n.esLocal("connector")) conectoresEa.putIfAbsent(n.idref(), n);
                if (n.esLocal("element", "attribute", "operation", "connector")) indexarExtensionEa(n);
            }

            // Aplicacion de un estereotipo de perfil: <perfil:table base_Class="EAID_1" length="50"/>
            for (Map.Entry<String, String> a : n.atributos.entrySet()) {
                if (!a.getKey().startsWith("base_")) continue;
                for (String destino : separar(a.getValue())) {
                    agregarEstereotipo(destino, n.local);
                    n.atributos.forEach((k, v) -> {
                        if (!k.startsWith("base_") && !k.startsWith("xmi:")) etiqueta(destino, k, v);
                    });
                }
            }

            // XMI 1.x: <UML:TaggedValue tag="ea_stype" value="table"/>
            if (n.esLocal("TaggedValue")) {
                String duenio = n.atr("modelElement") != null ? n.atr("modelElement") : idDelDuenio(n);
                String tag = n.atr("tag") != null ? n.atr("tag") : n.atr("name");
                String valor = n.atr("value") != null ? n.atr("value")
                        : n.hijo("TaggedValue.dataValue").map(Nodo::texto).orElse(n.texto());
                if (duenio != null && tag != null) {
                    if (tag.equalsIgnoreCase("ea_stype") || tag.equalsIgnoreCase("stereotype")) {
                        separarComas(valor).forEach(s -> agregarEstereotipo(duenio, s));
                    } else {
                        etiqueta(duenio, tag, valor);
                    }
                } else if (duenio != null && valor != null) {
                    // UML 1.4 (ArgoUML): el nombre esta en la TagDefinition que referencia.
                    n.hijo("TaggedValue.type").flatMap(t -> t.hijos.stream().filter(h -> h.idref() != null).findFirst())
                            .ifPresent(def -> etiquetasPorReferencia.add(new String[]{duenio, def.idref(), valor}));
                }
            }
            // XMI 1.x: <UML:Stereotype name="table" extendedElement="id1 id2"/> o anidado en el elemento
            if (n.esLocal("Stereotype") && !ext) {
                if (n.atr("name") != null && n.atr("extendedElement") != null) {
                    separar(n.atr("extendedElement")).forEach(d -> agregarEstereotipo(d, n.atr("name")));
                } else if (n.padre != null && n.padre.local.toLowerCase(Locale.ROOT).endsWith(".stereotype")) {
                    String duenio = idDelDuenio(n);
                    if (duenio != null && n.atr("name") != null) agregarEstereotipo(duenio, n.atr("name"));
                    if (duenio != null && n.idref() != null) estereotiposPorReferencia.add(new String[]{duenio, n.idref()});
                }
            }

            geometria(n);

            for (Nodo h : n.hijos) indexar(h, ext);
        }

        /**
         * Donde esta dibujado un elemento, de las dos formas conocidas:
         *   - EA (XMI 2.1 en la Extension, y XMI 1.1 en `UML:DiagramElement`):
         *     `<element geometry="Left=104;Top=97;Right=194;Bottom=167;" subject="EAID_..."/>`.
         *     En 1.1 algunas versiones escriben Top/Bottom negativos (el eje Y
         *     de su base), asi que se toma el valor absoluto.
         *   - UML DI (Papyrus, MagicDraw, EA 16 con "UML DI"): un `UMLShape`
         *     con `modelElement` y un `dc:Bounds` hijo con x/y/width/height.
         * Una geometria ilegible no se avisa: sin posicion, la clase va a la
         * grilla como siempre, y el modelo no pierde nada.
         */
        private void geometria(Nodo n) {
            String sujeto = null;
            ModeloLeido.Posicion caja = null;
            if (n.atr("geometry") != null && n.atr("subject") != null) {
                sujeto = n.atr("subject");
                Map<String, Double> g = new HashMap<>();
                for (String par : n.atr("geometry").split(";")) {
                    int igual = par.indexOf('=');
                    if (igual <= 0) continue;
                    try {
                        g.put(par.substring(0, igual).trim().toLowerCase(Locale.ROOT),
                                Double.parseDouble(par.substring(igual + 1).trim()));
                    } catch (NumberFormatException ignorada) {
                        // un valor raro en un par que no se usa no invalida la caja
                    }
                }
                Double l = g.get("left"), t = g.get("top"), r = g.get("right"), b = g.get("bottom");
                if (l != null && t != null && r != null && b != null) {
                    caja = new ModeloLeido.Posicion(l, Math.abs(t), Math.abs(r - l), Math.abs(b - t));
                }
            } else if (n.atr("modelElement") != null) {
                sujeto = separar(n.atr("modelElement")).stream().findFirst().orElse(null);
                Nodo b = n.hijos.stream().filter(h -> h.esLocal("bounds", "Bounds")).findFirst().orElse(null);
                if (b != null) {
                    try {
                        caja = new ModeloLeido.Posicion(num(b.atr("x")), num(b.atr("y")),
                                num(b.atr("width")), num(b.atr("height")));
                    } catch (NumberFormatException ignorada) {
                        caja = null;
                    }
                }
            }
            if (sujeto == null || caja == null) return;
            Nodo diagrama = raiz;
            for (Nodo p = n.padre; p != null; p = p.padre) {
                if (p.local.toLowerCase(Locale.ROOT).endsWith("diagram")) {
                    diagrama = p;
                    break;
                }
            }
            dibujos.computeIfAbsent(diagrama, k -> new HashMap<>()).putIfAbsent(sujeto, caja);
        }

        private static double num(String s) {
            return s == null || s.isBlank() ? 0 : Double.parseDouble(s.trim());
        }

        /**
         * Las posiciones del diagrama que mas clases leidas dibuja, por
         * `clave` de la clase. Las tablas se buscan por su id pero se
         * entregan por nombre, que es su clave en EsquemaRelacional.
         */
        private Map<String, ModeloLeido.Posicion> posiciones(List<ModeloLeido.ClaseLeida> clases, List<Nodo> tablas) {
            Map<String, String> claveDeId = new HashMap<>();
            Set<String> claves = clases.stream().map(ModeloLeido.ClaseLeida::clave).collect(Collectors.toSet());
            for (String c : claves) claveDeId.put(c, c);
            for (Nodo t : tablas) {
                String nombre = nombreDe(t);
                if (t.id() != null && nombre != null && claves.contains(nombre)) claveDeId.put(t.id(), nombre);
            }
            Map<String, ModeloLeido.Posicion> mejor = Map.of();
            for (Map<String, ModeloLeido.Posicion> dibujo : dibujos.values()) {
                Map<String, ModeloLeido.Posicion> r = new HashMap<>();
                dibujo.forEach((id, caja) -> {
                    String clave = claveDeId.get(id);
                    if (clave != null) r.putIfAbsent(clave, caja);
                });
                if (r.size() > mejor.size()) mejor = r;
            }
            if (dibujos.size() > 1 && !mejor.isEmpty()) {
                advertencias.add("El archivo trae " + dibujos.size() + " diagramas: se respeta la disposicion del"
                        + " que dibuja mas clases (" + mejor.size() + "); el resto va a la grilla.");
            }
            return Map.copyOf(mejor);
        }

        /** EA: <element xmi:idref> con <properties stereotype>, <stereotype>, <tags>, <xrefs>, <bounds>. */
        private void indexarExtensionEa(Nodo n) {
            String id = n.idref();
            for (Nodo h : n.hijos) {
                if (h.esLocal("properties")) {
                    h.atributos.forEach((k, v) -> etiqueta(id, "ea" + k, v));
                    if (h.atr("stereotype") != null) separarComas(h.atr("stereotype")).forEach(s -> agregarEstereotipo(id, s));
                } else if (h.esLocal("stereotype") && h.atr("stereotype") != null) {
                    separarComas(h.atr("stereotype")).forEach(s -> agregarEstereotipo(id, s));
                } else if (h.esLocal("tags")) {
                    for (Nodo t : h.hijos("tag")) {
                        if (t.atr("name") != null) etiqueta(id, t.atr("name"), t.atr("value"));
                    }
                } else if (h.esLocal("xrefs") && h.atr("value") != null) {
                    Matcher m = XREF.matcher(h.atr("value"));
                    while (m.find()) etiqueta(id, m.group(1), m.group(2));
                    // Algunas versiones de EA dejan el estereotipo SOLO aca.
                    Matcher st = XREF_ESTEREOTIPO.matcher(h.atr("value"));
                    while (st.find()) agregarEstereotipo(id, st.group(1));
                } else if (h.esLocal("styleex", "style") && h.atr("value") != null) {
                    // "OnDelete=Cascade;OnUpdate=No Action;": pares clave=valor.
                    for (String par : h.atr("value").split(";")) {
                        int igual = par.indexOf('=');
                        if (igual > 0) etiqueta(id, par.substring(0, igual), par.substring(igual + 1));
                    }
                } else if (h.esLocal("bounds")) {
                    h.atributos.forEach((k, v) -> etiqueta(id, "ea" + k, v));
                }
            }
        }

        private static final Pattern XREF = Pattern.compile("@NAME=(.*?)@ENDNAME;.*?@VALU=(.*?)@ENDVALU;");
        private static final Pattern XREF_ESTEREOTIPO = Pattern.compile("@STEREO;Name=([^;]+);");

        private String idDelDuenio(Nodo n) {
            for (Nodo p = n.padre; p != null; p = p.padre) {
                if (p.id() != null && p.idref() == null) return p.id();
            }
            return null;
        }

        private void agregarEstereotipo(String id, String crudo) {
            if (id == null || crudo == null || crudo.isBlank()) return;
            estereotipos.computeIfAbsent(id, k -> new LinkedHashSet<>()).add(normal(crudo));
        }

        private void etiqueta(String id, String nombre, String valor) {
            if (id == null || nombre == null || valor == null) return;
            etiquetas.computeIfAbsent(id, k -> new HashMap<>()).putIfAbsent(normal(nombre), valor.trim());
        }

        /** Los datos de la Extension de EA se cuelgan del `idref`; los del modelo, del `id`. */
        private static String claveDe(Nodo n) {
            return n == null ? null : n.id() != null ? n.id() : n.idref();
        }

        private Set<String> est(Nodo n) {
            String clave = claveDe(n);
            return clave == null ? Set.of() : estereotipos.getOrDefault(clave, Set.of());
        }

        private boolean tieneEst(Nodo n, String... claves) {
            for (String s : est(n)) for (String c : claves) if (s.contains(c)) return true;
            return false;
        }

        private String tag(Nodo n, String... nombres) {
            String clave = claveDe(n);
            if (clave == null) return null;
            Map<String, String> t = etiquetas.getOrDefault(clave, Map.of());
            for (String nombre : nombres) {
                String v = t.get(normal(nombre));
                if (v != null && !v.isBlank()) return v;
            }
            return null;
        }

        private boolean tagVerdadero(Nodo n, String... nombres) {
            String v = tag(n, nombres);
            return v != null && (v.equalsIgnoreCase("true") || v.equals("1") || v.equalsIgnoreCase("yes")
                    || v.equalsIgnoreCase("si"));
        }

        /** "EAUML::table", "Weak Entity", "PK" -> "table", "weakentity", "pk". */
        static String normal(String s) {
            String t = s.trim();
            int c = Math.max(t.lastIndexOf("::"), t.lastIndexOf(':'));
            if (c >= 0) t = t.substring(c + 1);
            return t.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        }

        // ================================================================ LECTURA

        /** Elementos del modelo que suelen venir sin id y que igual hay que poder etiquetar. */
        private static final Set<String> NECESITAN_ID = Set.of("Attribute", "Operation", "Parameter", "AssociationEnd",
                "Class", "Association", "Generalization", "Enumeration", "EnumerationLiteral");

        /**
         * EA en XMI 1.1 no le pone `xmi.id` a los atributos ni a las
         * operaciones (usa un tag `ea_guid`), y sus TaggedValue y estereotipos
         * se cuelgan del duenio mas cercano con id: sin esto, el `type` de una
         * columna terminaria etiquetando a la tabla.
         */
        private void completarIds(Nodo n, boolean enExtension) {
            boolean ext = enExtension || n.esLocal("Extension", "XMI.extensions", "XMI.extension", "XMI.header");
            if (!ext && n.id() == null && n.idref() == null && n.atr("href") == null && n.padre != null
                    && n.es(NECESITAN_ID.toArray(String[]::new))
                    && (n.atr("name") != null || n.hijo("ModelElement.name").isPresent())
                    && !n.padre.local.toLowerCase(Locale.ROOT).endsWith(".type")
                    && !n.padre.local.toLowerCase(Locale.ROOT).endsWith(".participant")) {
                n.atributos.put("xmi:id", "#linea" + n.linea + "-" + System.identityHashCode(n));
            }
            for (Nodo h : n.hijos) completarIds(h, ext);
        }

        ModeloLeido leer() {
            completarIds(raiz, false);
            indexar(raiz, false);
            for (String[] ref : estereotiposPorReferencia) {
                Nodo st = porId.get(ref[1]);
                if (st != null && st.atr("name") != null) agregarEstereotipo(ref[0], st.atr("name"));
            }
            for (String[] ref : etiquetasPorReferencia) {
                String nombre = nombreDe(porId.get(ref[1]));
                if (nombre == null) continue;
                if (nombre.equalsIgnoreCase("stereotype")) agregarEstereotipo(ref[0], ref[2]);
                else etiqueta(ref[0], nombre, ref[2]);
            }

            List<ModeloLeido.ClaseLeida> clases = new ArrayList<>();
            List<ModeloLeido.RelacionLeida> relaciones = new ArrayList<>();

            List<Nodo> tablas = new ArrayList<>();
            List<Nodo> relacionesErd = new ArrayList<>();
            List<Nodo> atributosErd = new ArrayList<>();
            List<Nodo> clasificadores = new ArrayList<>();
            int vistas = 0;
            for (Nodo d : definiciones) {
                if (tieneEst(d, "table")) tablas.add(d);
                else if (tieneEst(d, "view")) vistas++;
                else if (tieneEst(d, "relationship")) relacionesErd.add(d);
                else if (!tieneEst(d, "entity") && tieneEst(d, "attribute", "primarykey")
                        && !d.es("Property", "Attribute")) atributosErd.add(d);
                else if (d.es(CLASIFICADORES.toArray(String[]::new)) || tieneEst(d, "entity")) {
                    if (d.es("PrimitiveType") || "EARootClass".equals(d.atr("name"))) continue;
                    if (d.es("DataType") && propiedades(d).isEmpty()) continue;   // un tipo, no una clase
                    clasificadores.add(d);
                }
            }
            tablas.forEach(t -> consumidos.add(t.id()));
            relacionesErd.forEach(t -> consumidos.add(t.id()));
            atributosErd.forEach(t -> consumidos.add(t.id()));

            List<Enlace> enlaces = enlaces();
            Set<String> idsDeClase = clasificadores.stream().map(Nodo::id).collect(Collectors.toSet());

            // --- UML y entidades ERD
            Map<String, List<ModeloLeido.AtributoLeido>> extrasErd = atributosDeErd(atributosErd, enlaces);
            for (Nodo c : clasificadores) {
                aislado(c, () -> {
                    ModeloLeido.ClaseLeida clase = clase(c, relaciones, idsDeClase);
                    List<ModeloLeido.AtributoLeido> extra = extrasErd.getOrDefault(c.id(), List.of());
                    if (!extra.isEmpty()) {
                        List<ModeloLeido.AtributoLeido> todos = new ArrayList<>(clase.atributos());
                        todos.addAll(extra);
                        clase = new ModeloLeido.ClaseLeida(clase.clave(), clase.nombre(), clase.estereotipo(),
                                clase.abstracta(), List.copyOf(todos), clase.metodos());
                    }
                    clases.add(clase);
                    consumidos.add(c.id());
                    if (tieneEst(c, "entity")) entidadesLeidas++;
                    else clasesUmlLeidas++;
                    if (tieneEst(c, "weak")) {
                        advertencias.add(clase.nombre() + " es una entidad debil: entra como clase fuerte con su"
                                + " clave parcial marcada; marcala debil para recuperar la PK compuesta (Regla 11).");
                    }
                });
            }
            for (Nodo r : relacionesErd) aislado(r, () -> relacionErd(r, enlaces, idsDeClase, extrasErd, clases, relaciones));

            for (Enlace e : enlaces) {
                if (e.consumido || !idsDeClase.contains(e.a) || !idsDeClase.contains(e.b)) continue;
                if (e.nodo.es("AssociationClass") && idsDeClase.contains(e.nodo.id())) {
                    aislado(e.nodo, () -> reificar(e, relaciones));
                } else {
                    aislado(e.nodo, () -> relaciones.add(relacionUml(e)));
                }
            }

            // --- modelo de datos
            int tablasLeidas = 0;
            if (!tablas.isEmpty()) {
                Map<String, EsquemaRelacional.Tabla> esquema = esquema(tablas, enlaces);
                tablasLeidas = esquema.size();
                EsquemaRelacional.agregarA(esquema, clases, relaciones, advertencias);
            }

            reportarLoQueQuedoAfuera(enlaces, idsDeClase, vistas);
            List<ModeloLeido.ClaseLeida> sinRepetir = sinNombresRepetidos(clases);
            Map<String, ModeloLeido.Posicion> posiciones = posiciones(sinRepetir, tablas);

            List<String> todas = new ArrayList<>();
            todas.add(resumen(tablasLeidas, relacionesErd.size()));
            todas.addAll(advertencias);
            return new ModeloLeido("XMI", List.copyOf(sinRepetir), List.copyOf(relaciones), List.copyOf(todas),
                    posiciones);
        }

        private void aislado(Nodo n, Runnable accion) {
            try {
                accion.run();
            } catch (RuntimeException e) {
                advertencias.add("No se pudo leer " + n.clase() + (n.atr("name") != null ? " \"" + n.atr("name") + "\"" : "")
                        + " (linea " + n.linea + ") y se omitio: " + (e.getMessage() == null ? e.getClass().getSimpleName()
                        : e.getMessage()));
            }
        }

        // ================================================================ CLASES

        private ModeloLeido.ClaseLeida clase(Nodo c, List<ModeloLeido.RelacionLeida> relaciones, Set<String> idsDeClase) {
            String nombre = nombreDe(c);
            if (nombre == null) throw new IllegalStateException("no tiene nombre");
            boolean abstracta = "true".equalsIgnoreCase(c.atr("isAbstract")) || tagVerdadero(c, "eaisabstract", "isabstract");

            if (c.es("Enumeration") || tieneEst(c, "enumeration", "enum")) {
                List<ModeloLeido.AtributoLeido> literales = new ArrayList<>();
                for (Nodo l : descendientes(c, "ownedLiteral", "EnumerationLiteral")) {
                    if (l.atr("name") != null) literales.add(atributoSimple(l.atr("name")));
                }
                if (literales.isEmpty()) {   // EA guarda los literales como atributos
                    for (Nodo p : propiedades(c)) if (p.atr("name") != null) literales.add(atributoSimple(p.atr("name")));
                }
                return new ModeloLeido.ClaseLeida(c.id(), nombre, "enumeration", false, List.copyOf(literales), List.of());
            }

            String estereotipo = null;
            if (c.es("DataType") || tieneEst(c, "embeddable", "valueobject")) {
                estereotipo = "embeddable";
                if (c.es("DataType")) {
                    advertencias.add(nombre + " es un DataType de UML (un valor sin identidad): se leyo como"
                            + " «embeddable» (Regla 5).");
                }
            }

            List<ModeloLeido.AtributoLeido> atributos = new ArrayList<>();
            for (Nodo p : propiedades(c)) {
                String nombreAtr = p.atr("name");
                if (nombreAtr == null || nombreAtr.isBlank()) continue;
                Tipo tipo = tipoDe(p);
                Mult m = multiplicidad(p);

                if (tipo.clase() != null && idsDeClase.contains(tipo.clase().id()) && !esValor(tipo.clase())) {
                    // `jefe : Empleado [0..1]`: un atributo tipado con otra clase es una asociacion.
                    relaciones.add(new ModeloLeido.RelacionLeida(c.id(), tipo.clase().id(), RelationKind.ASSOCIATION,
                            null, "0..*", m == null ? "0..1" : m.texto(), null, nombreAtr, false));
                    advertencias.add(nombre + "." + nombreAtr + " es de tipo " + nombreDe(tipo.clase())
                            + ": se leyo como una relacion, no como un atributo (Regla 3).");
                    continue;
                }
                boolean pk = "true".equalsIgnoreCase(p.atr("isID")) || tieneEst(p, "pk", "primarykey", "id")
                        || tagVerdadero(p, "isid", "pk", "primarykey", "isprimarykey");
                boolean requerido = m != null && m.inferior() != null && m.inferior() >= 1;
                boolean unico = tieneEst(p, "unique") || tagVerdadero(p, "unique", "isunique");
                boolean multivaluado = m != null && m.muchos();
                boolean derivado = "true".equalsIgnoreCase(p.atr("isDerived")) || tagVerdadero(p, "eaderived", "derived");
                atributos.add(new ModeloLeido.AtributoLeido(nombreAtr.trim(), tipo.nombre(), pk, requerido, unico,
                        multivaluado, derivado, tieneEst(p, "partialkey", "discriminator")));
            }
            return new ModeloLeido.ClaseLeida(c.id(), nombre, estereotipo, abstracta, List.copyOf(atributos),
                    metodosDe(c));
        }

        /**
         * Las operaciones UML de una clase (REGLA 6): se transportan enteras
         * -nombre, tipo de retorno, parametros en orden- hasta la revision, que
         * las manda como ADD_METHOD dentro del mismo BATCH que las clases y los
         * atributos. El Service generado las emite como stub con esta firma.
         */
        private List<ModeloLeido.MetodoLeido> metodosDe(Nodo c) {
            List<ModeloLeido.MetodoLeido> metodos = new ArrayList<>();
            for (Nodo op : descendientes(c, "ownedOperation", "Operation")) {
                String nombreOp = nombreDe(op);
                if (nombreOp == null) continue;
                List<ModeloLeido.ParametroLeido> parametros = new ArrayList<>();
                Nodo retorno = null;
                for (Nodo pn : descendientes(op, "ownedParameter", "Parameter")) {
                    if ("return".equalsIgnoreCase(pn.atr("direction")) || pn.esLocal("return")) {
                        if (retorno == null) retorno = pn;
                        continue;
                    }
                    String nombreParam = pn.atr("name");
                    if (nombreParam == null || nombreParam.isBlank()) continue;
                    parametros.add(new ModeloLeido.ParametroLeido(nombreParam.trim(), tipoDe(pn).nombre()));
                }
                String tipoRetorno = retorno != null ? tipoDe(retorno).nombre() : tipoDe(op).nombre();
                metodos.add(new ModeloLeido.MetodoLeido(nombreOp.trim(), tipoRetorno == null ? "void" : tipoRetorno,
                        List.copyOf(parametros)));
            }
            return metodos;
        }

        private static ModeloLeido.AtributoLeido atributoSimple(String nombre) {
            return new ModeloLeido.AtributoLeido(nombre.trim(), null, false, false, false, false, false, false);
        }

        private boolean esValor(Nodo c) {
            return c.es("Enumeration", "DataType", "PrimitiveType") || tieneEst(c, "enumeration", "embeddable");
        }

        /** Los atributos de un clasificador, sin entrar en clasificadores anidados ni tomar extremos de asociacion. */
        private List<Nodo> propiedades(Nodo c) {
            List<Nodo> r = new ArrayList<>();
            Deque<Nodo> pila = new ArrayDeque<>(c.hijos);
            while (!pila.isEmpty()) {
                Nodo n = pila.pollFirst();
                if (n.idref() != null) continue;
                if (n.esLocal("ownedAttribute", "Attribute") || (n.es("Property") && !n.esLocal("ownedEnd",
                        "navigableOwnedEnd", "memberEnd"))) {
                    if (n.atr("association") == null) r.add(n);
                    continue;
                }
                if (n.id() != null && n.es(FRONTERAS.toArray(String[]::new))) continue;
                if (n.esLocal("ownedOperation", "Operation", "generalization", "Generalization")) continue;
                n.hijos.forEach(pila::addLast);
            }
            return r;
        }

        private List<Nodo> descendientes(Nodo c, String... locales) {
            List<Nodo> r = new ArrayList<>();
            Deque<Nodo> pila = new ArrayDeque<>(c.hijos);
            while (!pila.isEmpty()) {
                Nodo n = pila.pollFirst();
                if (n.esLocal(locales) || n.es(locales)) r.add(n);
                else if (!(n.id() != null && n.es(FRONTERAS.toArray(String[]::new)))) n.hijos.forEach(pila::addLast);
            }
            return r;
        }

        private String nombreDe(Nodo n) {
            if (n == null) return null;
            String nombre = n.atr("name");
            if (nombre == null) nombre = n.hijo("ModelElement.name", "name").map(Nodo::texto).orElse(null);
            return nombre == null || nombre.isBlank() ? null : nombre.trim();
        }

        // ================================================================ TIPOS

        private record Tipo(String nombre, Nodo clase) { }

        private static final Pattern TIPO_EA = Pattern.compile("^EA[A-Za-z#+]*_(.+)$");

        private Tipo tipoDe(Nodo p) {
            String ea = tag(p, "eatype");
            if (ea != null && !ea.isBlank()) return new Tipo(conTamanio(ea, p), null);

            List<String> refs = referencias(p, "type");
            refs.addAll(referencias(p, "StructuralFeature.type"));
            for (String ref : refs) {
                Nodo destino = porId.get(ref);
                if (destino != null) {
                    String nombre = nombreDe(destino);
                    boolean esClase = destino.id() != null && destino.es(CLASIFICADORES.toArray(String[]::new))
                            && !destino.esLocal("EAStub");
                    return new Tipo(limpiarTipo(nombre), esClase ? destino : null);
                }
                String legible = legible(ref);
                if (legible != null) return new Tipo(limpiarTipo(legible), null);
            }
            String umlUno = tag(p, "type");
            if (umlUno == null && !refs.isEmpty()) tiposSinResolver.add(refs.get(0));
            return new Tipo(umlUno == null ? null : limpiarTipo(conTamanio(umlUno, p)), null);
        }

        /** EA guarda largo y precision aparte: VARCHAR + 50 -> varchar(50). */
        private String conTamanio(String tipo, Nodo p) {
            String largo = tag(p, "ealength", "length");
            String precision = tag(p, "eaprecision", "precision");
            String escala = tag(p, "eascale", "scale");
            if (tipo.contains("(")) return tipo;
            if (largo != null && !largo.equals("0")) return tipo + "(" + largo + ")";
            if (precision != null && !precision.equals("0")) {
                return tipo + "(" + precision + (escala != null && !escala.equals("0") ? "," + escala : "") + ")";
            }
            return tipo;
        }

        /** Un id que se puede leer como nombre de tipo: `EAJava_int`, `...#String`, `String`. */
        private String legible(String ref) {
            if (ref.startsWith("EAID_")) return null;   // una clase que no vino en el archivo
            Matcher ea = TIPO_EA.matcher(ref);
            if (ea.matches()) return ea.group(1);
            if (ref.matches("[A-Za-z][A-Za-z0-9 .\\[\\]<>]{0,40}") && !ref.matches(".*\\d{4,}.*")) return ref;
            return null;
        }

        private static String limpiarTipo(String t) {
            if (t == null) return null;
            String s = t.trim();
            int punto = s.lastIndexOf('.');
            if (punto >= 0 && !s.contains("(")) s = s.substring(punto + 1);   // java.lang.String
            return s.isEmpty() ? null : s;
        }

        private List<String> referencias(Nodo n, String nombre) {
            List<String> r = new ArrayList<>();
            String a = n.atr(nombre);
            if (a != null) r.addAll(separar(a));
            for (Nodo h : n.hijos(nombre)) {
                if (h.idref() != null) r.add(h.idref());
                else if (h.atr("href") != null) r.add(fragmento(h.atr("href")));
                else for (Nodo d : h.hijos) {
                    if (d.idref() != null) r.add(d.idref());
                    else if (d.atr("href") != null) r.add(fragmento(d.atr("href")));
                }
            }
            return r;
        }

        private static String fragmento(String href) {
            int n = href.indexOf('#');
            return n >= 0 ? href.substring(n + 1) : href;
        }

        // ========================================================= MULTIPLICIDAD

        record Mult(Integer inferior, String superior) {
            boolean muchos() {
                return "*".equals(superior) || (superior != null && superior.matches("\\d+") && Integer.parseInt(superior) > 1);
            }

            String texto() {
                Integer lo = inferior != null ? inferior : (superior == null ? null : ("*".equals(superior) ? 0 : 1));
                if (lo == null) return null;
                String hi = superior != null ? superior : String.valueOf(Math.max(1, lo));
                return hi.equals(String.valueOf(lo)) ? hi : lo + ".." + hi;
            }
        }

        private Mult multiplicidad(Nodo p) {
            Optional<Nodo> bajo = p.hijo("lowerValue");
            Optional<Nodo> alto = p.hijo("upperValue");
            if (bajo.isPresent() || alto.isPresent()) {
                Integer lo = bajo.map(b -> entero(b.atr("value"), 0)).orElse(null);
                String hi = alto.map(a -> superior(a.atr("value"))).orElse(null);
                return new Mult(lo, hi);
            }
            if (p.atr("lower") != null || p.atr("upper") != null) {
                return new Mult(p.atr("lower") == null ? null : entero(p.atr("lower"), 0), superior(p.atr("upper")));
            }
            if (p.atr("multiplicity") != null) return deTexto(p.atr("multiplicity"));
            for (Nodo rango : descendientesSinFrontera(p, "MultiplicityRange")) {
                return new Mult(entero(rango.atr("lower"), 0), superior(rango.atr("upper")));
            }
            String loTag = tag(p, "ealower", "lowerbound");
            String hiTag = tag(p, "eaupper", "upperbound");
            if (loTag != null || hiTag != null) return new Mult(loTag == null ? null : entero(loTag, 0), superior(hiTag));
            // UML 2: una Property sin lowerValue/upperValue es [1..1], y Papyrus o
            // Eclipse UML2 no escriben lo que vale el valor por defecto. En UML 1.x
            // no hay valor por defecto: ahi queda sin declarar.
            if (p.es("Property") && !p.esLocal("Attribute", "AssociationEnd")) {
                multiplicidadesOmitidas++;
                return new Mult(1, "1");
            }
            return null;
        }

        private List<Nodo> descendientesSinFrontera(Nodo p, String local) {
            List<Nodo> r = new ArrayList<>();
            p.recorrer(n -> {
                if (n != p && n.esLocal(local)) r.add(n);
            });
            return r;
        }

        private static Integer entero(String s, int porDefecto) {
            if (s == null || s.isBlank()) return porDefecto;
            try {
                return Math.max(0, Integer.parseInt(s.trim()));
            } catch (NumberFormatException e) {
                return porDefecto;
            }
        }

        private static String superior(String s) {
            if (s == null || s.isBlank()) return null;
            String t = s.trim();
            if (t.equals("*") || t.equals("-1") || t.equalsIgnoreCase("n") || t.equalsIgnoreCase("m")
                    || t.equalsIgnoreCase("many")) return "*";
            return t.matches("\\d+") ? t : null;
        }

        /** "0..*", "1", "*", "N", "1..n" y la notacion Chen "(0,N)". */
        Mult deTexto(String s) {
            if (s == null || s.isBlank()) return null;
            String t = s.trim();
            if (t.startsWith("(") && t.endsWith(")") && t.contains(",")) {
                cardinalidadChen = true;
                String[] partes = t.substring(1, t.length() - 1).split(",");
                return new Mult(entero(partes[0], 0), superior(partes[1]));
            }
            if (t.contains("..")) {
                String[] partes = t.split("\\.\\.");
                return new Mult(entero(partes[0], 0), superior(partes.length > 1 ? partes[1] : null));
            }
            String hi = superior(t);
            if ("*".equals(hi)) return new Mult(0, "*");
            return hi == null ? null : new Mult(Integer.parseInt(hi), hi);
        }

        // ============================================================== ENLACES

        /** Una conexion entre dos elementos, venga de donde venga: uml:Association, UML 1.x, conector de EA. */
        private static final class Enlace {
            final Nodo nodo;
            final String tipo;
            String a;
            String b;
            Mult multA;
            Mult multB;
            String rolA;
            String rolB;
            boolean aEsTodo;
            boolean bEsTodo;
            String agregacion;
            boolean direccionConocida;
            String estereotipo;
            String alBorrar;
            boolean consumido;

            Enlace(Nodo nodo, String tipo) {
                this.nodo = nodo;
                this.tipo = tipo;
            }

            String otro(String id) {
                return id.equals(a) ? b : a;
            }

            Mult multDe(String id) {
                return id.equals(a) ? multA : multB;
            }

            String rolDe(String id) {
                return id.equals(a) ? rolA : rolB;
            }
        }

        private List<Enlace> enlaces() {
            List<Enlace> r = new ArrayList<>();
            Set<String> vistos = new HashSet<>();
            Set<String> generalizaciones = new HashSet<>();

            for (Nodo d : definiciones) {
                try {
                    if (d.es("Association", "AssociationClass")) {
                        Enlace e = asociacion(d);
                        if (e != null) {
                            r.add(e);
                            vistos.add(d.id());
                        }
                    } else if (d.es("Generalization")) {
                        Enlace e = generalizacion(d);
                        if (e != null && generalizaciones.add(e.a + ">" + e.b)) {
                            r.add(e);
                            vistos.add(d.id());
                        }
                    } else if (d.es(VINCULOS_SIN_ESQUEMA.toArray(String[]::new))) {
                        Enlace e = new Enlace(d, "Dependency");
                        List<String> cliente = referencias(d, "client");
                        List<String> proveedor = referencias(d, "supplier");
                        if (!cliente.isEmpty() && !proveedor.isEmpty()) {
                            e.a = cliente.get(0);
                            e.b = proveedor.get(0);
                            r.add(e);
                        }
                        vistos.add(d.id());
                    }
                } catch (RuntimeException ex) {
                    advertencias.add("No se pudo leer la conexion " + d.clase() + " (linea " + d.linea + "): se omitio.");
                }
            }
            // EA puede tener conectores que el XMI no emitio como uml:Association.
            for (Map.Entry<String, Nodo> c : conectoresEa.entrySet()) {
                if (vistos.contains(c.getKey())) continue;
                Enlace e = desdeConector(c.getValue(), null);
                if (e == null) continue;
                if (e.tipo.equals("Generalization") && !generalizaciones.add(e.a + ">" + e.b)) continue;
                r.add(e);
            }
            return r;
        }

        private Enlace asociacion(Nodo d) {
            List<Nodo> extremos = new ArrayList<>();
            for (String ref : referencias(d, "memberEnd")) {
                Nodo p = porId.get(ref);
                if (p != null && !extremos.contains(p)) extremos.add(p);
            }
            for (Nodo h : d.hijos) {
                if (h.idref() == null && h.esLocal("ownedEnd", "navigableOwnedEnd") && !extremos.contains(h)) extremos.add(h);
            }
            if (extremos.isEmpty()) {
                d.recorrer(n -> {
                    if (n != d && n.esLocal("AssociationEnd") && n.idref() == null) extremos.add(n);
                });
            }

            Nodo conector = conectoresEa.get(d.id());
            if (conector != null) {
                Enlace e = desdeConector(conector, d);
                if (e != null) return e;
            }
            if (extremos.size() > 2) {
                advertencias.add("La asociacion " + etiquetaDe(d) + " une " + extremos.size()
                        + " clases: una relacion n-aria no es mapeable, reificala como clase (Regla 10).");
                return null;
            }
            if (extremos.size() < 2) {
                advertencias.add("La asociacion " + etiquetaDe(d) + " (linea " + d.linea + ") no tiene sus dos extremos:"
                        + " se omitio.");
                return null;
            }
            Nodo x = extremos.get(0);
            Nodo y = extremos.get(1);
            // EA nombra los extremos EAID_src... y EAID_dst...: eso SI dice el sentido.
            boolean direccion = false;
            if (x.id() != null && y.id() != null && x.id().contains("dst") && y.id().contains("src")) {
                Nodo t = x;
                x = y;
                y = t;
                direccion = true;
            } else if (x.id() != null && x.id().contains("src")) {
                direccion = true;
            } else if (navegable(x, d) != navegable(y, d)) {
                // Sin ids de EA, el sentido lo dice la navegabilidad: se navega del
                // origen hacia el destino, asi que el extremo navegable es el destino.
                if (navegable(x, d)) {
                    Nodo t = x;
                    x = y;
                    y = t;
                }
                direccion = true;
            }

            Enlace e = new Enlace(d, "Association");
            e.a = claseDelExtremo(x);
            e.b = claseDelExtremo(y);
            if (e.a == null || e.b == null) {
                advertencias.add("La asociacion " + etiquetaDe(d) + " tiene un extremo que no apunta a ninguna clase del"
                        + " archivo: se omitio.");
                return null;
            }
            e.multA = multiplicidad(x);
            e.multB = multiplicidad(y);
            e.rolA = nombreDe(x);
            e.rolB = nombreDe(y);
            e.direccionConocida = direccion;
            String agX = agregacion(x.atr("aggregation"));
            String agY = agregacion(y.atr("aggregation"));
            boolean umlUno = x.esLocal("AssociationEnd");
            if (agX != null || agY != null) {
                e.agregacion = agX != null ? agX : agY;
                // UML 1.x marca el extremo del TODO; UML 2 marca el extremo tipado por la PARTE.
                boolean xMarcado = agX != null;
                boolean xEsTodo = umlUno == xMarcado;
                e.aEsTodo = xEsTodo;
                e.bEsTodo = !xEsTodo;
            }
            e.estereotipo = String.join(",", est(d));
            e.alBorrar = tag(d, "ondelete", "delete", "deleterule");
            return e;
        }

        /**
         * UML 1.x lo escribe (`isNavigable`). UML 2 lo dice por quien es el
         * duenio del extremo: si lo tiene una clase (ownedAttribute) o la
         * asociacion lo lista en navigableOwnedEnd, es navegable.
         */
        private boolean navegable(Nodo extremo, Nodo asociacion) {
            if (extremo.atr("isNavigable") != null) return "true".equalsIgnoreCase(extremo.atr("isNavigable"));
            if (referencias(asociacion, "navigableOwnedEnd").contains(extremo.id())) return true;
            for (Nodo p = extremo.padre; p != null; p = p.padre) if (p == asociacion) return false;
            return true;
        }

        private String claseDelExtremo(Nodo extremo) {
            List<String> refs = referencias(extremo, "type");
            refs.addAll(referencias(extremo, "participant"));
            refs.addAll(referencias(extremo, "AssociationEnd.participant"));
            refs.addAll(referencias(extremo, "AssociationEnd.type"));
            for (String ref : refs) if (porId.containsKey(ref)) return ref;
            return null;
        }

        /** Conector de la Extension de EA: source/target explicitos, que es lo mas confiable que trae EA. */
        private Enlace desdeConector(Nodo c, Nodo uml) {
            Nodo origen = c.hijo("source").orElse(null);
            Nodo destino = c.hijo("target").orElse(null);
            if (origen == null || destino == null || origen.idref() == null || destino.idref() == null) return null;
            String tipoEa = tag(c, "eaeatype");
            String tipo = tipoEa == null ? "Association" : switch (tipoEa.toLowerCase(Locale.ROOT)) {
                case "generalization" -> "Generalization";
                case "association", "aggregation", "composition" -> "Association";
                default -> "Dependency";
            };
            Enlace e = new Enlace(uml != null ? uml : c, tipo);
            e.a = origen.idref();
            e.b = destino.idref();
            e.direccionConocida = true;
            e.multA = origen.hijo("type").map(t -> deTexto(t.atr("multiplicity"))).orElse(null);
            e.multB = destino.hijo("type").map(t -> deTexto(t.atr("multiplicity"))).orElse(null);
            e.rolA = origen.hijo("role").map(r -> r.atr("name")).filter(s -> !s.isBlank()).orElse(null);
            e.rolB = destino.hijo("role").map(r -> r.atr("name")).filter(s -> !s.isBlank()).orElse(null);
            String agA = origen.hijo("type").map(t -> agregacion(t.atr("aggregation"))).orElse(null);
            String agB = destino.hijo("type").map(t -> agregacion(t.atr("aggregation"))).orElse(null);
            if (agA != null || agB != null) {
                // En el conector de EA la agregacion va en el extremo del rombo: el todo.
                e.agregacion = agA != null ? agA : agB;
                e.aEsTodo = agA != null;
                e.bEsTodo = agA == null;
            } else if ("composition".equalsIgnoreCase(tipoEa) || "aggregation".equalsIgnoreCase(tipoEa)) {
                e.agregacion = tipoEa.equalsIgnoreCase("composition") ? "composite" : "shared";
                e.bEsTodo = true;
            }
            Set<String> st = new LinkedHashSet<>(estereotipos.getOrDefault(c.idref(), Set.of()));
            if (uml != null) st.addAll(est(uml));
            e.estereotipo = String.join(",", st);
            e.alBorrar = tag(c, "ondelete", "delete", "deleterule");
            if (tag(c, "ondelete", "delete", "deleterule") == null && uml != null) {
                e.alBorrar = tag(uml, "ondelete", "delete", "deleterule");
            }
            if (e.nodo.id() == null) consumidos.add(c.idref());
            return e;
        }

        private Enlace generalizacion(Nodo g) {
            String hija;
            String madre;
            List<String> general = referencias(g, "general");
            if (!general.isEmpty()) {
                hija = idDelDuenio(g);
                madre = general.get(0);
            } else {
                hija = primero(referencias(g, "child"), referencias(g, "subtype"), referencias(g, "Generalization.child"),
                        referencias(g, "specific"));
                madre = primero(referencias(g, "parent"), referencias(g, "supertype"), referencias(g, "Generalization.parent"));
            }
            if (hija == null || madre == null || !porId.containsKey(hija) || !porId.containsKey(madre)) {
                advertencias.add("Una generalizacion (linea " + g.linea + ") no dice entre que clases del archivo es:"
                        + " se omitio.");
                return null;
            }
            Enlace e = new Enlace(g, "Generalization");
            e.a = hija;
            e.b = madre;
            return e;
        }

        @SafeVarargs
        private static String primero(List<String>... listas) {
            for (List<String> l : listas) if (!l.isEmpty()) return l.get(0);
            return null;
        }

        private static String agregacion(String s) {
            if (s == null) return null;
            String t = s.trim().toLowerCase(Locale.ROOT);
            return switch (t) {
                case "shared", "aggregate", "1" -> "shared";
                case "composite", "2" -> "composite";
                default -> null;
            };
        }

        private ModeloLeido.RelacionLeida relacionUml(Enlace e) {
            e.consumido = true;
            if (e.tipo.equals("Generalization")) {
                return new ModeloLeido.RelacionLeida(e.a, e.b, RelationKind.GENERALIZATION, null, null, null, null, null,
                        false);
            }
            if (e.tipo.equals("Dependency")) {
                return new ModeloLeido.RelacionLeida(e.a, e.b, RelationKind.DEPENDENCY, nombreDe(e.nodo), null, null,
                        null, null, false);
            }
            // Los extremos se toman por posicion y no por id de clase: en una
            // reflexiva los dos extremos son de la misma clase.
            boolean invertir = e.agregacion != null && e.bEsTodo;
            String a = invertir ? e.b : e.a;
            String b = invertir ? e.a : e.b;
            RelationKind tipo = RelationKind.ASSOCIATION;
            if (e.agregacion != null) {
                tipo = e.agregacion.equals("composite") ? RelationKind.COMPOSITION : RelationKind.AGGREGATION;
                // El todo va de origen: asi lo lee la Regla 5 / A-1.
                advertencias.add("La " + (tipo == RelationKind.COMPOSITION ? "composicion" : "agregacion") + " entre "
                        + nombreDe(porId.get(a)) + " (todo) y " + nombreDe(porId.get(b)) + " (parte) se leyo de "
                        + (e.nodo.esLocal("AssociationEnd") || e.nodo.es("Association") && e.direccionConocida
                           ? "los extremos" : "un conector") + ": UML 1, UML 2 y EA marcan el rombo en extremos"
                        + " distintos. Si quedo al reves, corregila despues de importar.");
            }
            Mult ma = invertir ? e.multB : e.multA;
            Mult mb = invertir ? e.multA : e.multB;
            String rolA = invertir ? e.rolB : e.rolA;
            String rolB = invertir ? e.rolA : e.rolB;
            if (!e.direccionConocida && e.agregacion == null && ma != null && mb != null && !ma.muchos() && !mb.muchos()) {
                advertencias.add("La asociacion 1:1 entre " + nombreDe(porId.get(a)) + " y " + nombreDe(porId.get(b))
                        + " no dice en el XMI cual es el origen: la FK va a quedar en " + nombreDe(porId.get(b))
                        + ". Si va del otro lado, invertila despues de importar.");
            }
            return new ModeloLeido.RelacionLeida(a, b, tipo, nombreDe(e.nodo),
                    ma == null ? null : ma.texto(), mb == null ? null : mb.texto(), limpiarRol(rolA), limpiarRol(rolB),
                    false);
        }

        /**
         * A —(mA)— B con clase asociativa C: A 1 —(mB) C y B 1 —(mA) C. Es la
         * lectura OO del punto A-4, la misma forma que el canonico 05.
         */
        private void reificar(Enlace e, List<ModeloLeido.RelacionLeida> relaciones) {
            e.consumido = true;
            String c = e.nodo.id();
            relaciones.add(new ModeloLeido.RelacionLeida(e.a, c, RelationKind.ASSOCIATION, null, "1",
                    e.multB == null ? "0..*" : e.multB.texto(), limpiarRol(e.rolA), null, false));
            relaciones.add(new ModeloLeido.RelacionLeida(e.b, c, RelationKind.ASSOCIATION, null, "1",
                    e.multA == null ? "0..*" : e.multA.texto(), limpiarRol(e.rolB), null, false));
            advertencias.add("La clase asociativa " + nombreDe(e.nodo) + " se leyo como una clase unida a "
                    + nombreDe(porId.get(e.a)) + " y a " + nombreDe(porId.get(e.b)) + " (punto de ambiguedad A-4): se"
                    + " pierde que el par no se pueda repetir.");
        }

        private static String limpiarRol(String rol) {
            if (rol == null) return null;
            String r = rol.trim().replaceFirst("^[+\\-#~]", "");
            return r.isEmpty() ? null : r;
        }

        private String etiquetaDe(Nodo n) {
            String nombre = nombreDe(n);
            return nombre != null ? "\"" + nombre + "\"" : "(linea " + n.linea + ")";
        }

        // ================================================================== ERD

        /** Atributos del ERD que son elementos propios, colgados de su entidad por un conector. */
        private Map<String, List<ModeloLeido.AtributoLeido>> atributosDeErd(List<Nodo> nodos, List<Enlace> enlaces) {
            Map<String, List<ModeloLeido.AtributoLeido>> porDuenio = new HashMap<>();
            Set<String> ids = nodos.stream().map(Nodo::id).collect(Collectors.toSet());
            for (Nodo a : nodos) {
                aislado(a, () -> {
                    String nombre = nombreDe(a);
                    if (nombre == null) return;
                    List<String> vecinos = new ArrayList<>();
                    for (Enlace e : enlaces) {
                        if (a.id().equals(e.a) || a.id().equals(e.b)) {
                            vecinos.add(e.otro(a.id()));
                            e.consumido = true;
                        }
                    }
                    String duenio = vecinos.stream().filter(v -> !ids.contains(v)).findFirst().orElse(null);
                    if (duenio == null) {
                        boolean compuesto = vecinos.stream().anyMatch(ids::contains);
                        advertencias.add("El atributo ERD \"" + nombre + "\" no esta unido a ninguna entidad"
                                + (compuesto ? " (es parte de un atributo compuesto: los compuestos se aplanan, crea"
                                  + " sus partes como atributos sueltos)" : "") + ": se omitio.");
                        return;
                    }
                    if (vecinos.stream().anyMatch(ids::contains)) {
                        advertencias.add("\"" + nombre + "\" es un atributo compuesto: se importa como un solo atributo"
                                + " y sus partes quedan afuera (Regla 10).");
                    }
                    porDuenio.computeIfAbsent(duenio, k -> new ArrayList<>()).add(new ModeloLeido.AtributoLeido(nombre,
                            tipoDe(a).nombre(), tieneEst(a, "key", "primary", "identifier", "pk")
                                                && !tieneEst(a, "partial", "foreign"),
                            tieneEst(a, "key", "primary"), false, tieneEst(a, "multi"), tieneEst(a, "derived"),
                            tieneEst(a, "partial")));
                });
            }
            return porDuenio;
        }

        /** Un rombo del ERD: binario y sin atributos es una relacion; si no, una clase asociativa. */
        private void relacionErd(Nodo r, List<Enlace> enlaces, Set<String> idsDeClase,
                                 Map<String, List<ModeloLeido.AtributoLeido>> atributos,
                                 List<ModeloLeido.ClaseLeida> clases, List<ModeloLeido.RelacionLeida> relaciones) {
            String nombre = nombreDe(r) == null ? "Relacion" : nombreDe(r);
            List<String> participantes = new ArrayList<>();
            List<Mult> mults = new ArrayList<>();
            List<String> roles = new ArrayList<>();
            for (Enlace e : enlaces) {
                if (!r.id().equals(e.a) && !r.id().equals(e.b)) continue;
                String otro = e.otro(r.id());
                if (!idsDeClase.contains(otro)) continue;
                e.consumido = true;
                participantes.add(otro);
                Mult m = e.multDe(otro);
                mults.add(m != null ? m : e.multDe(r.id()));
                roles.add(e.rolDe(otro));
            }
            boolean identificadora = tieneEst(r, "identifying", "weak");
            List<ModeloLeido.AtributoLeido> propios = new ArrayList<>(atributos.getOrDefault(r.id(), List.of()));
            for (Nodo p : propiedades(r)) {
                if (p.atr("name") != null) propios.add(atributoSimple(p.atr("name")));
            }

            if (participantes.size() == 2 && propios.isEmpty()) {
                relaciones.add(new ModeloLeido.RelacionLeida(participantes.get(0), participantes.get(1),
                        RelationKind.ASSOCIATION, nombre, texto(mults.get(0)), texto(mults.get(1)), roles.get(0),
                        roles.get(1), identificadora));
                return;
            }
            if (participantes.size() < 2) {
                advertencias.add("La relacion ERD \"" + nombre + "\" no une dos entidades del archivo: se omitio.");
                return;
            }
            clases.add(new ModeloLeido.ClaseLeida(r.id(), nombre, null, false, List.copyOf(propios), List.of()));
            for (int i = 0; i < participantes.size(); i++) {
                relaciones.add(new ModeloLeido.RelacionLeida(participantes.get(i), r.id(), RelationKind.ASSOCIATION,
                        null, "1", "0..*", roles.get(i), null, false));
            }
            advertencias.add("La relacion ERD \"" + nombre + "\" " + (participantes.size() > 2
                    ? "une " + participantes.size() + " entidades" : "tiene atributos propios")
                    + ": se leyo como clase asociativa unida a cada participante (Regla 10, punto de ambiguedad A-4).");
        }

        private static String texto(Mult m) {
            return m == null ? null : m.texto();
        }

        // =========================================================== DATOS (EA)

        private Map<String, EsquemaRelacional.Tabla> esquema(List<Nodo> nodos, List<Enlace> enlaces) {
            Map<String, EsquemaRelacional.Tabla> tablas = new LinkedHashMap<>();
            Map<String, String> nombrePorId = new HashMap<>();
            Map<String, List<String[]>> fksPorTabla = new HashMap<>();

            for (Nodo t : nodos) {
                aislado(t, () -> {
                    String nombre = nombreDe(t);
                    if (nombre == null) throw new IllegalStateException("la tabla no tiene nombre");
                    if (tablas.containsKey(nombre)) {
                        advertencias.add("La tabla " + nombre + " esta dos veces en el archivo: se usa la primera.");
                        return;
                    }
                    EsquemaRelacional.Tabla tabla = new EsquemaRelacional.Tabla(nombre);
                    tablas.put(nombre, tabla);
                    nombrePorId.put(t.id(), nombre);

                    for (Nodo p : propiedades(t)) {
                        String col = p.atr("name");
                        if (col == null || col.isBlank()) continue;
                        EsquemaRelacional.Columna c = new EsquemaRelacional.Columna(col.trim());
                        String tipo = tipoDe(p).nombre();
                        c.tipo = tipo == null ? "" : tipo.toLowerCase(Locale.ROOT);
                        Mult m = multiplicidad(p);
                        // EA guarda NOT NULL en "AllowDuplicates"; segun la version sale con un nombre u otro.
                        String duplicados = tag(p, "eaduplicates", "allowduplicates", "duplicates");
                        c.nula = !(("1".equals(duplicados) || "true".equalsIgnoreCase(duplicados))
                                   || tagVerdadero(p, "notnull") || tieneEst(p, "notnull")
                                   || (duplicados == null && m != null && m.inferior() != null && m.inferior() >= 1));
                        c.unica = tagVerdadero(p, "unique", "isunique") || tieneEst(p, "unique");
                        c.pk = tieneEst(p, "pk", "primarykey") || tagVerdadero(p, "isprimarykey", "primarykey", "pk");
                        if (c.pk) c.nula = false;
                        tabla.columnas.put(c.nombre, c);
                    }

                    for (Nodo op : descendientes(t, "ownedOperation", "Operation")) {
                        String nombreOp = nombreDe(op);
                        List<String> params = new ArrayList<>();
                        for (Nodo par : descendientes(op, "ownedParameter", "Parameter")) {
                            if ("return".equalsIgnoreCase(par.atr("direction")) || "return".equalsIgnoreCase(par.atr("kind"))) continue;
                            if (par.atr("name") != null) params.add(par.atr("name").trim());
                        }
                        if (tieneEst(op, "pk", "primarykey")) {
                            params.forEach(tabla::marcarPk);
                        } else if (tieneEst(op, "fk", "foreignkey")) {
                            fksPorTabla.computeIfAbsent(nombre, k -> new ArrayList<>())
                                    .add(new String[]{nombreOp, String.join(",", params), t.id(),
                                            tag(op, "ondelete", "delete", "deleterule")});
                        } else if (tieneEst(op, "unique", "uk", "uniquekey") && params.size() == 1) {
                            tabla.columna(params.get(0)).ifPresent(c -> c.unica = true);
                        } else if (tieneEst(op, "check")) {
                            tabla.checksIgnorados++;
                        }
                    }
                });
            }

            // FK: la operacion «FK» dice las columnas; el conector «FK» que la nombra dice la tabla destino.
            for (Map.Entry<String, List<String[]>> entrada : fksPorTabla.entrySet()) {
                EsquemaRelacional.Tabla tabla = tablas.get(entrada.getKey());
                for (String[] fk : entrada.getValue()) {
                    List<String> cols = separarComas(fk[1]);
                    String destino = destinoDeFk(fk[0], fk[2], cols, enlaces, nombrePorId, tablas);
                    if (destino == null) {
                        advertencias.add("Tabla " + tabla.nombre + ": no se encontro a que tabla apunta la clave foranea "
                                + fk[0] + " " + cols + "; esas columnas quedan como atributos.");
                        continue;
                    }
                    if (cols.size() != 1) {
                        advertencias.add("Tabla " + tabla.nombre + ": la FK compuesta " + cols + " hacia " + destino
                                + " no se importa (solo claves foraneas de una columna).");
                        continue;
                    }
                    String alBorrar = fk[3];
                    tabla.columna(cols.get(0)).ifPresent(c -> c.referencia = new EsquemaRelacional.Referencia(destino, alBorrar));
                }
            }

            // Conectores «FK» sin operacion: se busca la columna por el nombre de la tabla destino.
            for (Enlace e : enlaces) {
                if (e.consumido || !nombrePorId.containsKey(e.a) || !nombrePorId.containsKey(e.b)) continue;
                e.consumido = true;
                if (e.tipo.equals("Generalization")) {
                    advertencias.add("La tabla " + nombrePorId.get(e.a) + " hereda de " + nombrePorId.get(e.b)
                            + " en el diagrama, pero en un modelo de datos la herencia es una FK: se ignora.");
                    continue;
                }
                // Sin conector de EA no se sabe que extremo lleva la FK: se prueba en los dos.
                EsquemaRelacional.Tabla hija = tablas.get(nombrePorId.get(e.a));
                String destino = nombrePorId.get(e.b);
                Optional<EsquemaRelacional.Columna> col = columnaHacia(hija, destino);
                if (col.isEmpty() && !e.direccionConocida) {
                    hija = tablas.get(nombrePorId.get(e.b));
                    destino = nombrePorId.get(e.a);
                    col = columnaHacia(hija, destino);
                }
                if (col.isPresent()) {
                    col.get().referencia = new EsquemaRelacional.Referencia(destino, e.alBorrar);
                    advertencias.add("Tabla " + hija.nombre + ": la FK hacia " + destino + " no trae operacion «FK»; se"
                            + " tomo la columna " + col.get().nombre + " por su nombre.");
                } else {
                    advertencias.add("Tabla " + hija.nombre + ": hay un conector hacia " + destino + " pero ninguna"
                            + " columna que lo lleve; no se crea esa relacion.");
                }
            }
            return tablas;
        }

        private String destinoDeFk(String nombreOp, String idTabla, List<String> cols, List<Enlace> enlaces,
                                   Map<String, String> nombrePorId, Map<String, EsquemaRelacional.Tabla> tablas) {
            String op = limpiarRol(nombreOp);
            for (Enlace e : enlaces) {
                if (e.consumido || (!idTabla.equals(e.a) && !idTabla.equals(e.b))) continue;
                String otro = e.otro(idTabla);
                if (!nombrePorId.containsKey(otro)) continue;
                String rolPropio = limpiarRol(e.rolDe(idTabla));
                String rolOtro = limpiarRol(e.rolDe(otro));
                if (op != null && (op.equalsIgnoreCase(rolPropio) || op.equalsIgnoreCase(rolOtro))) {
                    e.consumido = true;
                    return nombrePorId.get(otro);
                }
            }
            List<Enlace> candidatos = enlaces.stream()
                    .filter(e -> !e.consumido && (idTabla.equals(e.a) || idTabla.equals(e.b)))
                    .filter(e -> nombrePorId.containsKey(e.otro(idTabla)) && e.estereotipo != null && e.estereotipo.contains("fk"))
                    .toList();
            if (candidatos.size() == 1) {
                candidatos.get(0).consumido = true;
                return nombrePorId.get(candidatos.get(0).otro(idTabla));
            }
            if (cols.size() == 1) {
                for (String nombre : tablas.keySet()) if (apuntaA(cols.get(0), nombre)) return nombre;
            }
            return null;
        }

        private static Optional<EsquemaRelacional.Columna> columnaHacia(EsquemaRelacional.Tabla tabla, String destino) {
            return tabla.columnas.values().stream().filter(c -> c.referencia == null && apuntaA(c.nombre, destino))
                    .findFirst();
        }

        /** `cliente_id`, `idCliente`, `fk_cliente` apuntan a `cliente`. */
        private static boolean apuntaA(String columna, String tabla) {
            String c = Nombres.normalizado(columna);
            String t = Nombres.normalizado(tabla);
            return c.equals(t + "_id") || c.equals("id_" + t) || c.equals("fk_" + t) || c.equals(t + "_fk");
        }

        // ============================================================ INFORME

        private void reportarLoQueQuedoAfuera(List<Enlace> enlaces, Set<String> idsDeClase, int vistas) {
            Map<String, Integer> ignorados = new TreeMap<>();
            for (Nodo d : definiciones) {
                if (consumidos.contains(d.id())) continue;
                if (d.atributos.keySet().stream().anyMatch(k -> k.startsWith("base_"))) continue;   // aplicacion de estereotipo
                String clase = d.clase();
                if (CLASIFICADORES.contains(clase) || CONOCIDOS.contains(clase) || VINCULOS_SIN_ESQUEMA.contains(clase)) continue;
                if (d.esLocal("ownedAttribute", "ownedOperation", "ownedParameter", "ownedEnd", "ownedLiteral",
                        "lowerValue", "upperValue", "defaultValue", "generalization", "specification",
                        "ownedComment", "bodyCondition", "nestedClassifier", "packageImport",
                        "profileApplication", "eAnnotations", "details", "references", "contents")) continue;
                if (d.padre != null && d.padre.id() != null && !d.es(FRONTERAS.toArray(String[]::new))
                        && nombreDe(d) == null) continue;
                ignorados.merge(clase, 1, Integer::sum);
            }
            long dependencias = enlaces.stream().filter(e -> e.tipo.equals("Dependency") && !e.consumido).count();
            if (vistas > 0) advertencias.add(vistas + (vistas == 1 ? " vista («view») no se importa" : " vistas («view») no se importan") + ": no son tablas.");
            if (dependencias > 0) {
                advertencias.add(dependencias + (dependencias == 1 ? " dependencia o realizacion no genera" : " dependencias o realizaciones no generan")
                        + " nada en el esquema y no se importan.");
            }
            if (!ignorados.isEmpty()) {
                advertencias.add("Quedaron afuera elementos que no son parte de un modelo de datos: "
                        + ignorados.entrySet().stream().map(x -> x.getValue() + " " + x.getKey()).collect(Collectors.joining(", "))
                        + ".");
            }
            if (!tiposSinResolver.isEmpty()) {
                advertencias.add(tiposSinResolver.size() + (tiposSinResolver.size() == 1
                        ? " atributo tiene un tipo que no esta en el archivo" : " atributos tienen un tipo que no esta en el archivo")
                        + " (una referencia a un perfil o biblioteca externa, como " + tiposSinResolver.get(0)
                        + "): el tipo se sugiere por el nombre del atributo; revisalo.");
            }
            if (multiplicidadesOmitidas > 0) {
                advertencias.add(multiplicidadesOmitidas + (multiplicidadesOmitidas == 1
                        ? " atributo o extremo no escribe su multiplicidad: se tomo [1]"
                        : " atributos o extremos no escriben su multiplicidad: se tomo [1]")
                        + ", el valor por defecto de UML 2 (Papyrus y Eclipse no escriben [1..1]). Quedan obligatorios;"
                        + " destilda los que admitan vacio.");
            }
            if (cardinalidadChen) {
                advertencias.add("Hay cardinalidades en notacion Chen (min,max): se tomaron en el mismo extremo en que"
                        + " estan escritas, como las muestra la vista Chen de la herramienta. Algunos textos las leen"
                        + " del extremo opuesto; revisa las multiplicidades.");
            }
        }

        /** Lo que efectivamente se leyo, no lo que habia: lo que fallo ya esta en su propia advertencia. */
        private String resumen(int tablas, int relacionesErd) {
            int entidades = entidadesLeidas;
            int clases = clasesUmlLeidas;
            List<String> partes = new ArrayList<>();
            if (clases > 0) partes.add(clases + (clases == 1 ? " clase UML" : " clases UML"));
            if (entidades > 0 || relacionesErd > 0) {
                partes.add(entidades + (entidades == 1 ? " entidad" : " entidades") + " y " + relacionesErd
                        + (relacionesErd == 1 ? " relacion" : " relaciones") + " de ERD");
            }
            if (tablas > 0) partes.add(tablas + (tablas == 1 ? " tabla" : " tablas") + " de un modelo de datos");
            String version = raiz.hijos.isEmpty() ? null : raiz.hijos.get(0).atr("xmi:version");
            if (version == null && !raiz.hijos.isEmpty()) version = raiz.hijos.get(0).atr("version");
            return "Se leyo: " + (partes.isEmpty() ? "nada importable" : String.join(", ", partes))
                    + (exportador != null ? ". Exportado por " + exportador : "")
                    + (version != null ? " (XMI " + version + ")" : "") + ".";
        }

        private List<ModeloLeido.ClaseLeida> sinNombresRepetidos(List<ModeloLeido.ClaseLeida> clases) {
            Set<String> usados = new HashSet<>();
            List<ModeloLeido.ClaseLeida> r = new ArrayList<>();
            for (ModeloLeido.ClaseLeida c : clases) {
                String nombre = c.nombre();
                int i = 2;
                while (!usados.add(nombre.toLowerCase(Locale.ROOT))) nombre = c.nombre() + "_" + i++;
                if (!nombre.equals(c.nombre())) {
                    advertencias.add("Hay dos clases llamadas " + c.nombre() + " (en paquetes distintos): la segunda se"
                            + " importa como " + nombre + ".");
                    c = new ModeloLeido.ClaseLeida(c.clave(), nombre, c.estereotipo(), c.abstracta(), c.atributos(),
                            c.metodos());
                }
                r.add(c);
            }
            return r;
        }

        private static List<String> separar(String s) {
            List<String> r = new ArrayList<>();
            if (s == null) return r;
            for (String p : s.trim().split("\\s+")) if (!p.isBlank()) r.add(p);
            return r;
        }

        private static List<String> separarComas(String s) {
            List<String> r = new ArrayList<>();
            if (s == null) return r;
            for (String p : s.split(",")) if (!p.isBlank()) r.add(p.trim());
            return r;
        }
    }
}
